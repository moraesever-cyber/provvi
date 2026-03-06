use aws_sdk_dynamodb::types::AttributeValue;
use aws_sdk_s3::primitives::ByteStream;
use chrono::Utc;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use serde::{Deserialize, Serialize};
use tracing::info;

// ---------------------------------------------------------------------------
// Tipos de entrada e saída da Lambda
// ---------------------------------------------------------------------------

/// Envelope da Function URL — o body pode vir como string JSON ou direto
#[derive(Deserialize, Serialize)]
struct FunctionUrlEvent {
    body: Option<String>,
    #[serde(rename = "isBase64Encoded")]
    is_base64_encoded: Option<bool>,
    // Headers HTTP enviados pelo SDK (case-insensitive na Function URL)
    headers: Option<std::collections::HashMap<String, String>>,
    // Campos diretos para invocação via CLI (sem envelope)
    session_id: Option<String>,
}

/// Payload real da requisição
#[derive(Deserialize)]
struct SignRequest {
    session_id: String,
    image_base64: String,
    manifest_json: String,
    frame_hash_hex: String,
    captured_at_nanos: i64,
    assertions: serde_json::Value,
}

/// Resposta retornada ao SDK após processamento
#[derive(Serialize)]
struct SignResponse {
    /// UUID da sessão confirmado
    session_id: String,
    /// URL S3 do manifesto armazenado (presigned, válido 7 dias)
    manifest_url: String,
    /// Timestamp de processamento no servidor
    processed_at: String,
    /// Status do armazenamento
    status: String,
    /// Indica se a re-assinatura KMS foi bem-sucedida
    kms_signed: bool,
}

/// Resposta de erro padronizada
#[derive(Serialize)]
struct ErrorResponse {
    error: String,
    session_id: Option<String>,
}

// ---------------------------------------------------------------------------
// Handler principal
// ---------------------------------------------------------------------------

async fn handler(
    event: LambdaEvent<FunctionUrlEvent>,
) -> Result<SignResponse, Error> {
    // ------------------------------------------------------------------
    // 0. Autenticação por API Key
    // Invocações via CLI (sem headers) ignoram a validação — apenas
    // requisições HTTP via Function URL precisam do header x-api-key.
    // ------------------------------------------------------------------
    if event.payload.headers.is_some() {
        let expected_key = std::env::var("API_KEY")
            .map_err(|_| "API_KEY não configurada")?;

        let provided_key = event.payload.headers
            .as_ref()
            .and_then(|h| {
                h.get("x-api-key")
                    .or_else(|| h.get("X-Api-Key"))
                    .or_else(|| h.get("X-API-KEY"))
            })
            .map(|s| s.as_str())
            .unwrap_or("");

        if provided_key != expected_key {
            return Err("Unauthorized: API Key inválida ou ausente".into());
        }
    }

    // Extrai o SignRequest do envelope Function URL ou do payload direto CLI
    let req: SignRequest = if let Some(body) = event.payload.body {
        let decoded = if event.payload.is_base64_encoded.unwrap_or(false) {
            String::from_utf8(
                base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &body)?
            )?
        } else {
            body
        };
        serde_json::from_str(&decoded)
            .map_err(|e| format!("Falha ao desserializar body: {e}"))?
    } else {
        // Invocação direta via CLI — deserializa o evento inteiro
        serde_json::from_value(serde_json::to_value(event.payload)?)
            .map_err(|e| format!("Falha ao desserializar payload direto: {e}"))?
    };

    info!(
        session_id = %req.session_id,
        frame_hash = %req.frame_hash_hex,
        "Recebendo requisição de assinatura"
    );

    // Inicializa clientes AWS com config da região do ambiente
    let config    = aws_config::load_from_env().await;
    let s3        = aws_sdk_s3::Client::new(&config);
    let dynamodb  = aws_sdk_dynamodb::Client::new(&config);
    let kms       = aws_sdk_kms::Client::new(&config);

    let bucket    = std::env::var("S3_BUCKET").unwrap_or_else(|_| "provvi-manifests-dev".to_string());
    let table     = std::env::var("DYNAMODB_TABLE").unwrap_or_else(|_| "provvi-sessions".to_string());
    let processed_at = Utc::now().to_rfc3339();

    // ------------------------------------------------------------------
    // 1. Decodifica a imagem JPEG recebida em base64
    // ------------------------------------------------------------------
    let image_bytes = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        &req.image_base64,
    ).map_err(|e| format!("Falha ao decodificar imagem base64: {e}"))?;

    // ------------------------------------------------------------------
    // 2. Armazena a imagem JPEG no S3
    //    Chave: {session_id}/image.jpg
    // ------------------------------------------------------------------
    let image_key = format!("{}/image.jpg", req.session_id);
    s3.put_object()
        .bucket(&bucket)
        .key(&image_key)
        .body(ByteStream::from(image_bytes))
        .content_type("image/jpeg")
        .send()
        .await
        .map_err(|e| format!("Falha ao salvar imagem no S3: {e}"))?;

    info!(key = %image_key, "Imagem salva no S3");

    // ------------------------------------------------------------------
    // 3. Armazena o manifesto C2PA no S3
    //    Chave: {session_id}/manifest.json
    // ------------------------------------------------------------------
    let manifest_key = format!("{}/manifest.json", req.session_id);
    s3.put_object()
        .bucket(&bucket)
        .key(&manifest_key)
        .body(ByteStream::from(req.manifest_json.clone().into_bytes()))
        .content_type("application/json")
        .send()
        .await
        .map_err(|e| format!("Falha ao salvar manifesto no S3: {e}"))?;

    info!(key = %manifest_key, "Manifesto salvo no S3");

    // ------------------------------------------------------------------
    // 4. Re-assina o manifesto com KMS (validade jurídica via ICP-Brasil)
    //    Falha no KMS não invalida a sessão — assinatura local permanece válida.
    // ------------------------------------------------------------------
    let (kms_signature_hex, kms_public_key_hex) = match sign_with_kms(&kms, &req.manifest_json).await {
        Ok(sig) => {
            info!(session_id = %req.session_id, "Manifesto re-assinado via KMS");
            let pub_key = get_kms_public_key(&kms).await.unwrap_or_default();
            (sig, pub_key)
        }
        Err(e) => {
            tracing::warn!(
                session_id = %req.session_id,
                error = %e,
                "Falha na re-assinatura KMS — mantendo assinatura local"
            );
            ("".to_string(), "".to_string())
        }
    };

    // ------------------------------------------------------------------
    // 5. Gera URL presigned para acesso ao manifesto (válida 7 dias)
    // ------------------------------------------------------------------
    let presigned_config = aws_sdk_s3::presigning::PresigningConfig::expires_in(
        std::time::Duration::from_secs(7 * 24 * 3600),
    )?;

    let manifest_url = s3
        .get_object()
        .bucket(&bucket)
        .key(&manifest_key)
        .presigned(presigned_config)
        .await
        .map_err(|e| format!("Falha ao gerar URL presigned: {e}"))?
        .uri()
        .to_string();

    // ------------------------------------------------------------------
    // 6. Registra a sessão no DynamoDB
    // ------------------------------------------------------------------
    dynamodb
        .put_item()
        .table_name(&table)
        .item("session_id",      AttributeValue::S(req.session_id.clone()))
        .item("captured_at",     AttributeValue::N(req.captured_at_nanos.to_string()))
        .item("frame_hash",      AttributeValue::S(req.frame_hash_hex.clone()))
        .item("manifest_s3_key", AttributeValue::S(manifest_key.clone()))
        .item("image_s3_key",    AttributeValue::S(image_key.clone()))
        .item("processed_at",    AttributeValue::S(processed_at.clone()))
        .item("assertions",      AttributeValue::S(req.assertions.to_string()))
        .item("kms_signature",   AttributeValue::S(kms_signature_hex.clone()))
        .item("kms_public_key",  AttributeValue::S(kms_public_key_hex.clone()))
        .item("kms_signed",      AttributeValue::Bool(!kms_signature_hex.is_empty()))
        .item("status",          AttributeValue::S("stored".to_string()))
        .send()
        .await
        .map_err(|e| format!("Falha ao registrar sessão no DynamoDB: {e}"))?;

    info!(session_id = %req.session_id, "Sessão registrada no DynamoDB");

    Ok(SignResponse {
        session_id:   req.session_id,
        manifest_url,
        processed_at,
        status:       "stored".to_string(),
        kms_signed:   !kms_signature_hex.is_empty(),
    })
}

/// Re-assina o manifesto C2PA usando AWS KMS (ECC_NIST_P256).
///
/// O dispositivo já assinou com cert dev (garante integridade local).
/// Esta assinatura adiciona validade jurídica via certificado ICP-Brasil
/// armazenado no KMS (quando disponível — por ora usa chave KMS pura).
///
/// Retorna a assinatura DER em hex para registro no DynamoDB.
async fn sign_with_kms(
    kms: &aws_sdk_kms::Client,
    manifest_json: &str,
) -> Result<String, Error> {
    use sha2::{Sha256, Digest};

    let key_id = std::env::var("KMS_KEY_ID")
        .unwrap_or_else(|_| "alias/provvi-c2pa-signing".to_string());

    // Hash SHA-256 do manifesto — KMS assina o hash, não o conteúdo completo
    let mut hasher = Sha256::new();
    hasher.update(manifest_json.as_bytes());
    let digest = hasher.finalize();

    let response = kms
        .sign()
        .key_id(&key_id)
        .message(aws_sdk_kms::primitives::Blob::new(digest.as_slice()))
        .message_type(aws_sdk_kms::types::MessageType::Digest)
        .signing_algorithm(aws_sdk_kms::types::SigningAlgorithmSpec::EcdsaSha256)
        .send()
        .await
        .map_err(|e| format!("Falha na assinatura KMS: {e}"))?;

    let signature_bytes = response
        .signature()
        .ok_or("KMS não retornou assinatura")?
        .as_ref();

    Ok(hex::encode(signature_bytes))
}

/// Busca a chave pública KMS para verificação externa.
/// Retorna a chave em formato DER hex.
async fn get_kms_public_key(
    kms: &aws_sdk_kms::Client,
) -> Result<String, Error> {
    let key_id = std::env::var("KMS_KEY_ID")
        .unwrap_or_else(|_| "alias/provvi-c2pa-signing".to_string());

    let response = kms
        .get_public_key()
        .key_id(&key_id)
        .send()
        .await
        .map_err(|e| format!("Falha ao obter chave pública KMS: {e}"))?;

    let pub_key_bytes = response
        .public_key()
        .ok_or("KMS não retornou chave pública")?
        .as_ref();

    Ok(hex::encode(pub_key_bytes))
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("lambda_signer=info".parse()?)
        )
        .json()
        .init();

    run(service_fn(handler)).await
}
