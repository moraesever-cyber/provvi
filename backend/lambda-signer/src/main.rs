use std::sync::Arc;

use aws_sdk_dynamodb::types::AttributeValue;
use aws_sdk_s3::primitives::ByteStream;
use chrono::Utc;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use serde::{Deserialize, Serialize};
use tracing::info;

// ---------------------------------------------------------------------------
// Certificado ICP-Brasil — carregado no cold start, reutilizado entre invocações
// ---------------------------------------------------------------------------

/// Certificado e-CNPJ A1 ICP-Brasil carregado do Secrets Manager.
/// Armazenado em Arc para compartilhamento zero-copy entre invocações.
struct IcpBrasilCert {
    #[allow(dead_code)]
    certificate_pem: String,
    private_key_pem: String,
    #[allow(dead_code)]
    chain_pem:       String,
    /// Data de validade extraída do campo opcional do secret ("2027-03-11").
    valid_until:     String,
}

/// Estrutura do JSON armazenado no Secrets Manager.
/// Os campos subject, cnpj e valid_until são opcionais — usados se presentes,
/// caso contrário usa as constantes hardcoded abaixo.
#[derive(Deserialize)]
struct IcpBrasilSecretJson {
    certificate_pem: String,
    private_key_pem: String,
    chain_pem:       String,
    /// "2027-03-11" — adicionar ao secret via aws secretsmanager update-secret
    #[serde(default)]
    valid_until: String,
}

// Metadados conhecidos do certificado e-CNPJ A1 Certisign RFB G5.
// Atualize se um novo certificado for emitido.
const ICP_SUBJECT: &str = "EVERALDO ARISTOTELES DE MORAES";
const ICP_CNPJ:    &str = "26988458000194";

// ---------------------------------------------------------------------------
// Tipos de entrada e saída da Lambda
// ---------------------------------------------------------------------------

/// Envelope da Function URL — o body pode vir como string JSON ou direto
#[derive(Deserialize, Serialize)]
struct FunctionUrlEvent {
    body: Option<String>,
    #[serde(rename = "isBase64Encoded")]
    is_base64_encoded: Option<bool>,
    headers: Option<std::collections::HashMap<String, String>>,
    session_id: Option<String>,
}

/// Payload real da requisição
#[derive(Deserialize)]
struct SignRequest {
    session_id:      String,
    image_base64:    String,
    manifest_json:   String,
    frame_hash_hex:  String,
    captured_at_ms:  i64,
    assertions:      serde_json::Value,
}

/// Resposta retornada ao SDK após processamento
#[derive(Serialize)]
struct SignResponse {
    session_id:       String,
    manifest_url:     String,
    processed_at:     String,
    status:           String,
    /// Mantido por compatibilidade com o verifier atual; true apenas quando KMS é usado como fallback
    kms_signed:       bool,
    /// true quando a assinatura ICP-Brasil A1 foi aplicada com sucesso
    icp_brasil:       bool,
    /// Subject do certificado ICP-Brasil usado
    icp_subject:      String,
    /// CNPJ do titular do certificado
    icp_cnpj:         String,
    /// Data de validade do certificado ("YYYY-MM-DD")
    icp_valid_until:  String,
    /// "icp_brasil_a1" | "kms_dev"
    signing_mode:     String,
}

// ---------------------------------------------------------------------------
// Cold start — carrega o certificado ICP-Brasil do Secrets Manager
// ---------------------------------------------------------------------------

/// Carrega o certificado ICP-Brasil do Secrets Manager uma única vez no cold start.
///
/// Retorna `None` se o secret não estiver acessível ou estiver malformado.
/// O handler tratará a ausência como fallback para KMS — nenhuma invocação
/// retornará erro 500 por causa desta função.
async fn load_icp_brasil_cert(
    sm: &aws_sdk_secretsmanager::Client,
) -> Option<IcpBrasilCert> {
    let secret_id = std::env::var("ICP_SECRET_ID")
        .unwrap_or_else(|_| "provvi/icp-brasil/a1-cert".to_string());

    let response = sm
        .get_secret_value()
        .secret_id(&secret_id)
        .send()
        .await
        .map_err(|e| {
            tracing::error!(
                secret_id = %secret_id,
                error = %e,
                "Falha ao carregar certificado ICP-Brasil do Secrets Manager — usando KMS como fallback"
            );
        })
        .ok()?;

    let secret_str = match response.secret_string() {
        Some(s) => s,
        None => {
            tracing::error!("Secret ICP-Brasil não contém string JSON válido");
            return None;
        }
    };

    let parsed: IcpBrasilSecretJson = serde_json::from_str(secret_str)
        .map_err(|e| {
            tracing::error!(error = %e, "Falha ao desserializar JSON do secret ICP-Brasil");
        })
        .ok()?;

    // Valida que a chave privada é parseável antes de cachear.
    // Falha rápida no cold start — melhor que falhar na primeira invocação.
    validate_rsa_pem(&parsed.private_key_pem)
        .map_err(|e| {
            tracing::error!(
                error = %e,
                "Chave privada ICP-Brasil inválida ou não suportada — usando KMS como fallback"
            );
        })
        .ok()?;

    info!(
        subject      = ICP_SUBJECT,
        cnpj         = ICP_CNPJ,
        valid_until  = %parsed.valid_until,
        "Certificado ICP-Brasil e-CNPJ A1 carregado com sucesso"
    );

    Some(IcpBrasilCert {
        certificate_pem: parsed.certificate_pem,
        private_key_pem: parsed.private_key_pem,
        chain_pem:       parsed.chain_pem,
        valid_until:     parsed.valid_until,
    })
}

// ---------------------------------------------------------------------------
// Handler principal
// ---------------------------------------------------------------------------

async fn handler(
    event:    LambdaEvent<FunctionUrlEvent>,
    icp_cert: Arc<Option<IcpBrasilCert>>,
) -> Result<SignResponse, Error> {
    // ------------------------------------------------------------------
    // 0. Autenticação por API Key
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
        serde_json::from_value(serde_json::to_value(event.payload)?)
            .map_err(|e| format!("Falha ao desserializar payload direto: {e}"))?
    };

    info!(
        session_id = %req.session_id,
        frame_hash = %req.frame_hash_hex,
        "Recebendo requisição de assinatura"
    );

    let config    = aws_config::load_from_env().await;
    let s3        = aws_sdk_s3::Client::new(&config);
    let dynamodb  = aws_sdk_dynamodb::Client::new(&config);
    let kms       = aws_sdk_kms::Client::new(&config);

    let bucket       = std::env::var("S3_BUCKET").unwrap_or_else(|_| "provvi-manifests-dev".to_string());
    let table        = std::env::var("DYNAMODB_TABLE").unwrap_or_else(|_| "provvi-sessions".to_string());
    let processed_at = Utc::now().to_rfc3339();

    // ------------------------------------------------------------------
    // 1. Decodifica a imagem JPEG
    // ------------------------------------------------------------------
    let image_bytes = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        &req.image_base64,
    ).map_err(|e| format!("Falha ao decodificar imagem base64: {e}"))?;

    // ------------------------------------------------------------------
    // 2. Armazena a imagem JPEG no S3
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
    // 4. Assina o manifesto
    //
    // Prioridade: ICP-Brasil A1 (validade jurídica plena via ICP-Brasil)
    //             → KMS dev (fallback se o certificado não estiver disponível)
    //
    // Falhas de assinatura NÃO invalidam a sessão — o manifesto C2PA do
    // SDK Android já possui assinatura Ed25519 local garantindo integridade.
    // ------------------------------------------------------------------
    let (signing_mode, icp_signed, icp_signature_hex, kms_signature_hex) =
        if let Some(cert) = icp_cert.as_ref().as_ref() {
            match sign_with_icp_brasil(cert, req.manifest_json.as_bytes()) {
                Ok(sig) => {
                    info!(
                        session_id   = %req.session_id,
                        algorithm    = "RSA-PKCS1v15-SHA256",
                        subject      = ICP_SUBJECT,
                        "Manifesto re-assinado via certificado ICP-Brasil A1"
                    );
                    ("icp_brasil_a1".to_string(), true, sig, String::new())
                }
                Err(e) => {
                    // Falha inesperada na assinatura — fallback para KMS
                    tracing::error!(
                        session_id = %req.session_id,
                        error      = %e,
                        "Falha na assinatura ICP-Brasil — usando KMS como fallback"
                    );
                    let kms_sig = sign_with_kms(&kms, &req.manifest_json).await
                        .unwrap_or_default();
                    ("kms_dev".to_string(), false, String::new(), kms_sig)
                }
            }
        } else {
            // Certificado não carregado no cold start — KMS como fallback
            tracing::warn!(
                session_id = %req.session_id,
                "Certificado ICP-Brasil indisponível — usando KMS como fallback"
            );
            let kms_sig = sign_with_kms(&kms, &req.manifest_json).await
                .map_err(|e| tracing::warn!(error = %e, "Falha também no KMS"))
                .unwrap_or_default();
            ("kms_dev".to_string(), false, String::new(), kms_sig)
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
    let (captured_by, reference_id) = extract_provvi_assertions(&req.manifest_json);

    let icp_subject_str  = if icp_signed { ICP_SUBJECT } else { "" };
    let icp_valid_until: String = icp_cert.as_ref().as_ref()
        .map(|c| c.valid_until.clone())
        .unwrap_or_default();

    dynamodb
        .put_item()
        .table_name(&table)
        .item("session_id",      AttributeValue::S(req.session_id.clone()))
        .item("captured_at",     AttributeValue::N(req.captured_at_ms.to_string()))
        .item("frame_hash",      AttributeValue::S(req.frame_hash_hex.clone()))
        .item("manifest_s3_key", AttributeValue::S(manifest_key.clone()))
        .item("image_s3_key",    AttributeValue::S(image_key.clone()))
        .item("processed_at",    AttributeValue::S(processed_at.clone()))
        .item("assertions",      AttributeValue::S(req.assertions.to_string()))
        .item("captured_by",     AttributeValue::S(captured_by))
        .item("reference_id",    AttributeValue::S(reference_id))
        // Assinatura ICP-Brasil (primária)
        .item("icp_brasil",      AttributeValue::Bool(icp_signed))
        .item("icp_subject",     AttributeValue::S(icp_subject_str.to_string()))
        .item("icp_cnpj",        AttributeValue::S(if icp_signed { ICP_CNPJ.to_string() } else { String::new() }))
        .item("icp_valid_until", AttributeValue::S(icp_valid_until.clone()))
        .item("icp_signature",   AttributeValue::S(icp_signature_hex.clone()))
        .item("signing_mode",    AttributeValue::S(signing_mode.clone()))
        // Assinatura KMS (fallback — mantida por compatibilidade com verifier atual)
        .item("kms_signature",   AttributeValue::S(kms_signature_hex.clone()))
        .item("kms_public_key",  AttributeValue::S(String::new()))
        .item("kms_signed",      AttributeValue::Bool(!kms_signature_hex.is_empty()))
        .item("status",          AttributeValue::S("stored".to_string()))
        .send()
        .await
        .map_err(|e| format!("Falha ao registrar sessão no DynamoDB: {e}"))?;

    info!(
        session_id   = %req.session_id,
        signing_mode = %signing_mode,
        icp_brasil   = icp_signed,
        "Sessão registrada no DynamoDB"
    );

    Ok(SignResponse {
        session_id:      req.session_id,
        manifest_url,
        processed_at,
        status:          "stored".to_string(),
        kms_signed:      !kms_signature_hex.is_empty(),
        icp_brasil:      icp_signed,
        icp_subject:     icp_subject_str.to_string(),
        icp_cnpj:        if icp_signed { ICP_CNPJ.to_string() } else { String::new() },
        icp_valid_until,
        signing_mode,
    })
}

// ---------------------------------------------------------------------------
// Assinatura ICP-Brasil — RSA PKCS#1 v1.5 + SHA-256 (sha256WithRSAEncryption)
// ---------------------------------------------------------------------------

/// Assina `data` com a chave privada RSA do certificado ICP-Brasil A1.
///
/// Algoritmo: RSA PKCS#1 v1.5 com SHA-256 (sha256WithRSAEncryption),
/// conforme emitido pela AC Certisign RFB G5.
///
/// Suporta chave privada em formato PKCS#8 (`-----BEGIN PRIVATE KEY-----`)
/// e PKCS#1/tradicional (`-----BEGIN RSA PRIVATE KEY-----`).
///
/// Retorna a assinatura RSA em hex para registro no DynamoDB.
fn sign_with_icp_brasil(cert: &IcpBrasilCert, data: &[u8]) -> Result<String, Error> {
    use rsa::{RsaPrivateKey, pkcs1v15::SigningKey};
    use rsa::pkcs8::DecodePrivateKey;
    use rsa::pkcs1::DecodeRsaPrivateKey;
    use sha2::Sha256;
    use rsa::signature::{Signer, SignatureEncoding};

    // Tenta PKCS#8 (-----BEGIN PRIVATE KEY-----) primeiro,
    // depois PKCS#1/tradicional (-----BEGIN RSA PRIVATE KEY-----)
    let private_key = RsaPrivateKey::from_pkcs8_pem(&cert.private_key_pem)
        .or_else(|_| RsaPrivateKey::from_pkcs1_pem(&cert.private_key_pem))
        .map_err(|e| format!("Falha ao carregar chave RSA ICP-Brasil: {e}"))?;

    let signing_key = SigningKey::<Sha256>::new(private_key);

    // sign() aplica SHA-256 internamente e assina com RSA PKCS#1 v1.5
    let signature: rsa::pkcs1v15::Signature = signing_key.sign(data);

    Ok(hex::encode(signature.to_bytes()))
}

/// Valida que o PEM da chave privada é parseável.
/// Chamada no cold start para falha rápida antes de cachear o certificado.
fn validate_rsa_pem(pem: &str) -> Result<(), Error> {
    use rsa::RsaPrivateKey;
    use rsa::pkcs8::DecodePrivateKey;
    use rsa::pkcs1::DecodeRsaPrivateKey;

    RsaPrivateKey::from_pkcs8_pem(pem)
        .or_else(|_| RsaPrivateKey::from_pkcs1_pem(pem))
        .map_err(|e| format!("Chave RSA inválida ou formato não suportado: {e}"))?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Assinatura KMS — fallback quando ICP-Brasil não está disponível
// ---------------------------------------------------------------------------

/// Re-assina com KMS (ECC_NIST_P256) como fallback de desenvolvimento.
///
/// Usado apenas quando o certificado ICP-Brasil não estiver disponível.
/// Retorna a assinatura DER em hex para registro no DynamoDB.
async fn sign_with_kms(
    kms: &aws_sdk_kms::Client,
    manifest_json: &str,
) -> Result<String, Error> {
    use sha2::{Sha256, Digest};

    let key_id = std::env::var("KMS_KEY_ID")
        .unwrap_or_else(|_| "alias/provvi-c2pa-signing".to_string());

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

// ---------------------------------------------------------------------------
// Utilitários
// ---------------------------------------------------------------------------

/// Extrai captured_by e reference_id do manifesto C2PA para armazenamento
/// direto no DynamoDB — evita que o verifier precise parsear o manifest_json.
fn extract_provvi_assertions(manifest_json: &str) -> (String, String) {
    let Ok(manifest) = serde_json::from_str::<serde_json::Value>(manifest_json) else {
        return (String::new(), String::new());
    };

    let active = manifest.get("active_manifest")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    let data = manifest
        .get("manifests")
        .and_then(|m| m.get(active))
        .and_then(|m| m.get("assertions"))
        .and_then(|a| a.as_array())
        .and_then(|a| a.iter().find(|item| {
            item.get("label")
                .and_then(|l| l.as_str())
                .map(|l| l == "com.provvi.capture")
                .unwrap_or(false)
        }))
        .and_then(|a| a.get("data"));

    let captured_by  = data.and_then(|d| d.get("captured_by")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let reference_id = data.and_then(|d| d.get("reference_id")).and_then(|v| v.as_str()).unwrap_or("").to_string();

    (captured_by, reference_id)
}

// ---------------------------------------------------------------------------
// Entrypoint
// ---------------------------------------------------------------------------

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("lambda_signer=info".parse()?)
        )
        .json()
        .init();

    // ------------------------------------------------------------------
    // Cold start — carrega o certificado ICP-Brasil uma única vez.
    //
    // O Arc permite compartilhar o cert entre invocações sem cópia.
    // Se o Secrets Manager não estiver acessível, icp_cert = None e todas
    // as invocações usarão KMS como fallback — nenhuma retornará erro 500.
    // ------------------------------------------------------------------
    let aws_config = aws_config::load_from_env().await;
    let sm         = aws_sdk_secretsmanager::Client::new(&aws_config);
    let icp_cert   = Arc::new(load_icp_brasil_cert(&sm).await);

    if icp_cert.is_some() {
        info!(signing_mode = "icp_brasil_a1", "Lambda pronta — assinatura ICP-Brasil ativa");
    } else {
        tracing::warn!(signing_mode = "kms_dev", "Lambda pronta — assinatura ICP-Brasil indisponível, usando KMS");
    }

    run(service_fn(move |event: LambdaEvent<FunctionUrlEvent>| {
        let icp_cert = icp_cert.clone();
        async move { handler(event, icp_cert).await }
    })).await
}
