use aws_sdk_dynamodb::types::AttributeValue;
use aws_sdk_s3::primitives::ByteStream;
use chrono::Utc;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use serde::{Deserialize, Serialize};
use tracing::info;

// ---------------------------------------------------------------------------
// Tipos de entrada e saída da Lambda
// ---------------------------------------------------------------------------

/// Payload enviado pelo SDK Android após captura local
#[derive(Deserialize)]
struct SignRequest {
    /// UUID da sessão gerado no dispositivo
    session_id: String,
    /// Imagem JPEG em base64
    image_base64: String,
    /// Manifesto C2PA gerado pelo SDK (assinado com cert dev)
    manifest_json: String,
    /// Hash SHA-256 do frame YUV original
    frame_hash_hex: String,
    /// Timestamp do sensor em nanosegundos
    captured_at_nanos: i64,
    /// Asserções de negócio do integrador
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
    event: LambdaEvent<SignRequest>,
) -> Result<SignResponse, Error> {
    let req = event.payload;

    info!(
        session_id = %req.session_id,
        frame_hash = %req.frame_hash_hex,
        "Recebendo requisição de assinatura"
    );

    // Inicializa clientes AWS com config da região do ambiente
    let config    = aws_config::load_from_env().await;
    let s3        = aws_sdk_s3::Client::new(&config);
    let dynamodb  = aws_sdk_dynamodb::Client::new(&config);

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
        .body(ByteStream::from(req.manifest_json.into_bytes()))
        .content_type("application/json")
        .send()
        .await
        .map_err(|e| format!("Falha ao salvar manifesto no S3: {e}"))?;

    info!(key = %manifest_key, "Manifesto salvo no S3");

    // ------------------------------------------------------------------
    // 4. Gera URL presigned para acesso ao manifesto (válida 7 dias)
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
    // 5. Registra a sessão no DynamoDB
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
        .item("status",          AttributeValue::S("stored".to_string()))
        .send()
        .await
        .map_err(|e| format!("Falha ao registrar sessão no DynamoDB: {e}"))?;

    info!(session_id = %req.session_id, "Sessão registrada no DynamoDB");

    Ok(SignResponse {
        session_id:   req.session_id,
        manifest_url,
        processed_at,
        status: "stored".to_string(),
    })
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
