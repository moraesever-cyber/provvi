use aws_sdk_dynamodb::types::AttributeValue;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tracing::info;

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct FunctionUrlEvent {
    body:               Option<String>,
    #[serde(rename = "isBase64Encoded")]
    is_base64_encoded:  Option<bool>,
    headers:            Option<std::collections::HashMap<String, String>>,
    // Campos diretos para invocação CLI
    session_id:         Option<String>,
    image_base64:       Option<String>,
}

#[derive(Deserialize)]
struct VerifyRequest {
    session_id:   Option<String>,
    image_base64: Option<String>,
}

#[derive(Serialize)]
struct VerifyResponse {
    valid:               bool,
    session_id:          String,
    captured_at_nanos:   i64,
    captured_at_iso:     String,
    frame_hash_hex:      String,
    frame_hash_match:    Option<bool>,  // None se imagem não foi enviada
    kms_signed:          bool,
    location_suspicious: bool,
    assertions:          serde_json::Value,
    verification_note:   String,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

async fn handler(
    event: LambdaEvent<FunctionUrlEvent>,
) -> Result<serde_json::Value, Error> {
    // Autenticação — verifier é público (sem API Key) para permitir
    // validação por qualquer pessoa com a imagem
    // CORS é suficiente para controle de origem no site

    // Extrai o VerifyRequest
    let req: VerifyRequest = if let Some(body) = event.payload.body {
        let decoded = if event.payload.is_base64_encoded.unwrap_or(false) {
            String::from_utf8(
                base64::Engine::decode(
                    &base64::engine::general_purpose::STANDARD, &body)?
            )?
        } else {
            body
        };
        serde_json::from_str(&decoded)
            .map_err(|e| format!("Falha ao desserializar body: {e}"))?
    } else {
        VerifyRequest {
            session_id:   event.payload.session_id,
            image_base64: event.payload.image_base64,
        }
    };

    // Valida que pelo menos um campo foi fornecido
    if req.session_id.is_none() && req.image_base64.is_none() {
        return Ok(serde_json::to_value(ErrorResponse {
            error: "Forneça session_id, image_base64, ou ambos.".to_string(),
        })?);
    }

    let config   = aws_config::load_from_env().await;
    let dynamodb = aws_sdk_dynamodb::Client::new(&config);
    let table    = std::env::var("DYNAMODB_TABLE")
        .unwrap_or_else(|_| "provvi-sessions".to_string());

    // Calcula hash da imagem se fornecida
    let image_hash: Option<String> = if let Some(ref b64) = req.image_base64 {
        let bytes = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD, b64)
            .map_err(|e| format!("Falha ao decodificar imagem: {e}"))?;
        let mut hasher = Sha256::new();
        hasher.update(&bytes);
        Some(hex::encode(hasher.finalize()))
    } else {
        None
    };

    // Busca o item no DynamoDB
    let item = if let Some(ref sid) = req.session_id {
        // Busca direta por session_id
        info!(session_id = %sid, "Buscando por session_id");
        find_by_session_id(&dynamodb, &table, sid).await?
    } else {
        // Busca por frame_hash via GSI
        let hash = image_hash.as_ref().unwrap();
        info!(frame_hash = %hash, "Buscando por frame_hash");
        find_by_frame_hash(&dynamodb, &table, hash).await?
    };

    let Some(item) = item else {
        return Ok(serde_json::to_value(VerifyResponse {
            valid:               false,
            session_id:          req.session_id.unwrap_or_default(),
            captured_at_nanos:   0,
            captured_at_iso:     "".to_string(),
            frame_hash_hex:      "".to_string(),
            frame_hash_match:    image_hash.map(|_| false),
            kms_signed:          false,
            location_suspicious: false,
            assertions:          serde_json::Value::Null,
            verification_note:   "Sessão não encontrada. Imagem não registrada ou adulterada."
                                     .to_string(),
        })?);
    };

    // Extrai campos do item DynamoDB
    let session_id     = get_str(&item, "session_id");
    let frame_hash_hex = get_str(&item, "frame_hash");
    let captured_at    = get_num(&item, "captured_at");
    let kms_signed     = get_bool(&item, "kms_signed");
    let location_suspicious = get_bool(&item, "location_suspicious");
    let assertions_str = get_str(&item, "assertions");
    let assertions: serde_json::Value = serde_json::from_str(&assertions_str)
        .unwrap_or(serde_json::Value::Null);

    // Verifica hash se imagem foi fornecida
    let frame_hash_match = image_hash.map(|h| h == frame_hash_hex);

    // A sessão é válida se:
    // 1. Foi encontrada no DynamoDB (registro imutável)
    // 2. Se imagem foi fornecida, o hash bate
    let valid = frame_hash_match.unwrap_or(true);

    let verification_note = match (valid, frame_hash_match, kms_signed) {
        (false, _, _) =>
            "Hash da imagem não confere. Imagem pode ter sido adulterada após a captura."
                .to_string(),
        (true, None, true) =>
            "Sessão encontrada e assinada digitalmente via KMS. \
             Envie a imagem para verificação completa do hash.".to_string(),
        (true, None, false) =>
            "Sessão encontrada com assinatura local. \
             Envie a imagem para verificação completa do hash.".to_string(),
        (true, Some(true), true) =>
            "Imagem autêntica. Hash verificado e assinatura KMS válida.".to_string(),
        (true, Some(true), false) =>
            "Imagem autêntica. Hash verificado com assinatura local.".to_string(),
        _ => "Verificação inconclusiva.".to_string(),
    };

    // Converte timestamp nanosegundos para ISO 8601
    let captured_at_iso = {
        use chrono::{DateTime, TimeZone, Utc};
        let secs  = captured_at / 1_000_000_000;
        let nanos = (captured_at % 1_000_000_000) as u32;
        Utc.timestamp_opt(secs, nanos)
            .single()
            .map(|dt: DateTime<Utc>| dt.to_rfc3339())
            .unwrap_or_default()
    };

    info!(
        session_id = %session_id,
        valid = valid,
        kms_signed = kms_signed,
        "Verificação concluída"
    );

    Ok(serde_json::to_value(VerifyResponse {
        valid,
        session_id,
        captured_at_nanos: captured_at,
        captured_at_iso,
        frame_hash_hex,
        frame_hash_match,
        kms_signed,
        location_suspicious,
        assertions,
        verification_note,
    })?)
}

// ---------------------------------------------------------------------------
// Helpers DynamoDB
// ---------------------------------------------------------------------------

async fn find_by_session_id(
    dynamodb:   &aws_sdk_dynamodb::Client,
    table:      &str,
    session_id: &str,
) -> Result<Option<std::collections::HashMap<String, AttributeValue>>, Error> {
    let result = dynamodb
        .query()
        .table_name(table)
        .key_condition_expression("session_id = :sid")
        .expression_attribute_values(":sid", AttributeValue::S(session_id.to_string()))
        .limit(1)
        .send()
        .await
        .map_err(|e| format!("Erro DynamoDB query: {e}"))?;

    Ok(result.items().first().cloned())
}

async fn find_by_frame_hash(
    dynamodb:   &aws_sdk_dynamodb::Client,
    table:      &str,
    frame_hash: &str,
) -> Result<Option<std::collections::HashMap<String, AttributeValue>>, Error> {
    let result = dynamodb
        .query()
        .table_name(table)
        .index_name("frame-hash-index")
        .key_condition_expression("frame_hash = :hash")
        .expression_attribute_values(":hash", AttributeValue::S(frame_hash.to_string()))
        .limit(1)
        .send()
        .await
        .map_err(|e| format!("Erro DynamoDB GSI query: {e}"))?;

    Ok(result.items().first().cloned())
}

fn get_str(
    item: &std::collections::HashMap<String, AttributeValue>,
    key:  &str,
) -> String {
    item.get(key)
        .and_then(|v| v.as_s().ok())
        .cloned()
        .unwrap_or_default()
}

fn get_num(
    item: &std::collections::HashMap<String, AttributeValue>,
    key:  &str,
) -> i64 {
    item.get(key)
        .and_then(|v| v.as_n().ok())
        .and_then(|s| s.parse().ok())
        .unwrap_or(0)
}

fn get_bool(
    item: &std::collections::HashMap<String, AttributeValue>,
    key:  &str,
) -> bool {
    item.get(key)
        .and_then(|v| v.as_bool().ok())
        .copied()
        .unwrap_or(false)
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("lambda_verifier=info".parse()?)
        )
        .json()
        .init();

    run(service_fn(handler)).await
}
