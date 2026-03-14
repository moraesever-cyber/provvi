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
#[derive(Deserialize)]
struct IcpBrasilSecretJson {
    certificate_pem: String,
    private_key_pem: String,
    chain_pem:       String,
    #[serde(default)]
    valid_until: String,
}

// Metadados conhecidos do certificado e-CNPJ A1 Certisign RFB G5.
const ICP_SUBJECT: &str = "EVERALDO ARISTOTELES DE MORAES";
const ICP_CNPJ:    &str = "26988458000194";

// ---------------------------------------------------------------------------
// Google Hardware Attestation Root CA — carregado no cold start
// ---------------------------------------------------------------------------

/// Certificados raiz do Google Hardware Attestation CA em DER.
/// Contém RSA Root CA 1 e EC P-384 Root CA 2 (dois certificados).
struct GoogleAttestationRoots {
    der_certs: Vec<Vec<u8>>,
}

/// Converte uma string PEM (com header/footer e quebras de linha reais ou \\n) para DER.
fn pem_to_der(pem: &str) -> Result<Vec<u8>, String> {
    // Normaliza \\n literal (como vem do Secrets Manager) para quebra de linha real
    let normalized = pem.replace("\\n", "\n");
    let b64: String = normalized
        .lines()
        .filter(|l| !l.trim_start().starts_with("-----"))
        .collect();
    base64::Engine::decode(&base64::engine::general_purpose::STANDARD, &b64)
        .map_err(|e| format!("Falha ao decodificar PEM base64: {e}"))
}

/// Carrega os certificados raiz do Google Hardware Attestation CA do Secrets Manager.
///
/// O secret deve conter um JSON array de strings PEM — formato baixado diretamente
/// da documentação Android (dois CAs: RSA Root CA 1 e EC P-384 Root CA 2).
///
/// Retorna `None` se `GOOGLE_ATTESTATION_ROOT_CA_SECRET` não estiver configurada,
/// o que desabilita a verificação de raiz (aceita cadeias sem ancoragem).
async fn load_google_attestation_roots(
    sm: &aws_sdk_secretsmanager::Client,
) -> Option<GoogleAttestationRoots> {
    let secret_id = std::env::var("GOOGLE_ATTESTATION_ROOT_CA_SECRET").ok()?;
    if secret_id.is_empty() {
        return None;
    }

    let response = sm
        .get_secret_value()
        .secret_id(&secret_id)
        .send()
        .await
        .map_err(|e| {
            tracing::warn!(
                secret_id = %secret_id,
                error      = %e,
                "Falha ao carregar Google Attestation Root CA — verificação de raiz desabilitada"
            );
        })
        .ok()?;

    let secret_str = match response.secret_string() {
        Some(s) => s,
        None => {
            tracing::warn!("Google Attestation Root CA: secret não contém string");
            return None;
        }
    };

    let pems: Vec<String> = serde_json::from_str(secret_str)
        .map_err(|e| {
            tracing::warn!(error = %e, "Google Attestation Root CA: JSON inválido — esperado array de strings PEM");
        })
        .ok()?;

    let der_certs: Vec<Vec<u8>> = pems
        .iter()
        .filter_map(|pem| {
            pem_to_der(pem)
                .map_err(|e| {
                    tracing::warn!(error = %e, "Google Attestation Root CA: falha ao decodificar PEM");
                })
                .ok()
        })
        .collect();

    if der_certs.is_empty() {
        tracing::warn!("Google Attestation Root CA: nenhum certificado válido carregado");
        return None;
    }

    info!(
        count = der_certs.len(),
        "Google Hardware Attestation Root CA carregados com sucesso"
    );

    Some(GoogleAttestationRoots { der_certs })
}

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
    session_id:        String,
    image_base64:      String,
    manifest_json:     String,
    frame_hash_hex:    String,
    captured_at_ms:    i64,
    assertions:        serde_json::Value,
    /// Cadeia de certificados DER em Base64 — presente quando SDK usou Key Attestation fallback
    attestation_chain: Option<Vec<String>>,
    /// "play_integrity" | "key_attestation" — informa o mecanismo usado pelo SDK
    #[serde(default = "default_attestation_type")]
    attestation_type:  String,
}

fn default_attestation_type() -> String { "play_integrity".to_string() }

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
    /// "granted" | "skipped" — resultado da âncora temporal RFC 3161 (DT-016)
    tsa_status:       String,
    /// URL da TSA usada nesta requisição (vazia se TSA_URL não configurada)
    tsa_url:          String,
    /// "play_integrity" | "key_attestation" | "none"
    attestation_type: String,
}

/// Resposta HTTP proxy para Lambda Function URL — permite controlar status code (DT-017).
///
/// Lambda Function URL interpreta retornos com campo `statusCode` como respostas HTTP
/// proxy, usando o valor numérico como status da resposta HTTP ao cliente.
#[derive(Serialize)]
struct LambdaHttpResponse {
    #[serde(rename = "statusCode")]
    status_code: u16,
    headers:     std::collections::HashMap<String, String>,
    body:        String,
}

impl LambdaHttpResponse {
    fn ok(body: &SignResponse) -> Result<Self, Error> {
        Ok(Self {
            status_code: 200,
            headers:     [("Content-Type".to_string(), "application/json".to_string())].into(),
            body:        serde_json::to_string(body)?,
        })
    }

    /// HTTP 503 com body `{"error":"TSA_UNAVAILABLE"}` — detectado pelo SDK como TSA_UNAVAILABLE (DT-017)
    fn tsa_unavailable() -> Self {
        Self {
            status_code: 503,
            headers:     [("Content-Type".to_string(), "application/json".to_string())].into(),
            body:        r#"{"error":"TSA_UNAVAILABLE","message":"Âncora temporal RFC 3161 indisponível após 3 tentativas"}"#.to_string(),
        }
    }

    fn unauthorized() -> Self {
        Self {
            status_code: 401,
            headers:     [("Content-Type".to_string(), "application/json".to_string())].into(),
            body:        r#"{"error":"UNAUTHORIZED","message":"API Key inválida ou ausente"}"#.to_string(),
        }
    }

    /// HTTP 403 com body `{"error":"DEVICE_COMPROMISED"}` — bootloader unlocked ou
    /// verified boot falhou, detectado via Key Attestation.
    fn device_compromised() -> Self {
        Self {
            status_code: 403,
            headers:     [("Content-Type".to_string(), "application/json".to_string())].into(),
            body:        r#"{"error":"DEVICE_COMPROMISED","message":"Dispositivo comprometido detectado via Key Attestation — bootloader desbloqueado ou verified boot falhou"}"#.to_string(),
        }
    }
}

/// Classificação de falhas na obtenção do token TSA RFC 3161
#[derive(Debug)]
enum TsaError {
    /// Falha de rede ou HTTP ao contactar o servidor TSA
    Network(String),
    /// Resposta TSA não contém status "granted" (PKIStatus != 0)
    NotGranted(u8),
    /// Resposta DER malformada ou campos obrigatórios ausentes
    ParseError(String),
}

impl std::fmt::Display for TsaError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TsaError::Network(e)    => write!(f, "Falha de rede TSA: {e}"),
            TsaError::NotGranted(s) => write!(f, "TSA retornou status {s} (esperado 0=granted)"),
            TsaError::ParseError(e) => write!(f, "Resposta TSA malformada: {e}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Cold start — carrega o certificado ICP-Brasil do Secrets Manager
// ---------------------------------------------------------------------------

/// Carrega o certificado ICP-Brasil do Secrets Manager uma única vez no cold start.
///
/// Retorna `None` se o secret não estiver acessível ou estiver malformado.
/// O handler tratará a ausência como fallback para KMS.
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
    event:        LambdaEvent<FunctionUrlEvent>,
    icp_cert:     Arc<Option<IcpBrasilCert>>,
    google_roots: Arc<Option<GoogleAttestationRoots>>,
) -> Result<LambdaHttpResponse, Error> {
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
            return Ok(LambdaHttpResponse::unauthorized());
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
    // 1b. Key Attestation — verifica integridade do dispositivo quando presente
    //
    // Executada ANTES da TSA e dos writes no S3/DynamoDB — se o dispositivo
    // estiver comprometido, rejeita sem persistir nada.
    //
    // O challenge na cadeia de certificados deve corresponder ao session_id:
    // prova que a cadeia foi gerada especificamente para esta sessão.
    // ------------------------------------------------------------------
    let (attestation_security_level, attestation_bootloader_locked, attestation_verified_boot) =
        if let Some(ref chain) = req.attestation_chain {
            match verify_key_attestation(chain, &req.session_id, google_roots.as_ref().as_ref()) {
                Ok(fields) => {
                    if !fields.challenge_valid {
                        tracing::warn!(
                            session_id = %req.session_id,
                            "Key Attestation: challenge não corresponde ao session_id — possível reutilização de cadeia"
                        );
                        // Bloqueia: challenge inválido indica tentativa de replay
                        return Ok(LambdaHttpResponse::device_compromised());
                    }
                    if !fields.bootloader_locked || fields.verified_boot == "failed" {
                        tracing::warn!(
                            session_id        = %req.session_id,
                            bootloader_locked = fields.bootloader_locked,
                            verified_boot     = %fields.verified_boot,
                            "Key Attestation: dispositivo comprometido — rejeitando captura"
                        );
                        return Ok(LambdaHttpResponse::device_compromised());
                    }
                    info!(
                        session_id        = %req.session_id,
                        security_level    = %fields.security_level,
                        bootloader_locked = fields.bootloader_locked,
                        verified_boot     = %fields.verified_boot,
                        "Key Attestation verificada com sucesso"
                    );
                    (fields.security_level, fields.bootloader_locked, fields.verified_boot)
                }
                Err(e) => {
                    tracing::warn!(
                        session_id = %req.session_id,
                        error      = %e,
                        "Falha na verificação de Key Attestation — continuando sem attestation"
                    );
                    ("unknown".to_string(), false, "unknown".to_string())
                }
            }
        } else {
            (String::new(), false, String::new())
        };

    // ------------------------------------------------------------------
    // 2. Âncora temporal RFC 3161 (DT-016)
    //
    // Obtida ANTES dos writes no S3/DynamoDB — se a TSA falhar após 3
    // tentativas retornamos HTTP 503 sem persistir nada (DT-017).
    //
    // Hash timestampado: SHA-256 do manifesto JSON, que é o documento C2PA
    // que está sendo assinado e armazenado nesta invocação.
    //
    // Se TSA_URL não estiver configurada (ex.: testes locais), o passo é
    // ignorado e tsa_status = "skipped".
    // ------------------------------------------------------------------
    let tsa_url_opt = std::env::var("TSA_URL").ok().filter(|s| !s.is_empty());
    let (tsa_status, tsa_token_b64) = match tsa_url_opt.as_deref() {
        None => {
            tracing::warn!(
                session_id = %req.session_id,
                "TSA_URL não configurada — âncora temporal ignorada (skipped)"
            );
            ("skipped".to_string(), String::new())
        }
        Some(tsa_url) => {
            use sha2::{Sha256, Digest};
            let mut hasher = Sha256::new();
            hasher.update(req.manifest_json.as_bytes());
            let manifest_hash = hasher.finalize();

            match request_tsa_with_retry(&manifest_hash, tsa_url).await {
                Ok(token_bytes) => {
                    info!(
                        session_id = %req.session_id,
                        tsa_url    = %tsa_url,
                        token_len  = token_bytes.len(),
                        "Âncora temporal TSA obtida com sucesso"
                    );
                    let token_b64 = base64::Engine::encode(
                        &base64::engine::general_purpose::STANDARD,
                        &token_bytes,
                    );
                    ("granted".to_string(), token_b64)
                }
                Err(e) => {
                    tracing::error!(
                        session_id = %req.session_id,
                        tsa_url    = %tsa_url,
                        error      = %e,
                        "TSA indisponível após 3 tentativas — retornando 503"
                    );
                    return Ok(LambdaHttpResponse::tsa_unavailable());
                }
            }
        }
    };

    // ------------------------------------------------------------------
    // 3. Armazena a imagem JPEG no S3
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
    // 4. Armazena o manifesto C2PA no S3
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
    // 5. Assina o manifesto
    //
    // Prioridade: ICP-Brasil A1 → KMS dev (fallback)
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
    // 6. Gera URL presigned para acesso ao manifesto (válida 7 dias)
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
    // 7. Registra a sessão no DynamoDB
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
        // Âncora temporal RFC 3161 (DT-016)
        .item("tsa_status",                  AttributeValue::S(tsa_status.clone()))
        .item("tsa_token",                   AttributeValue::S(tsa_token_b64))
        .item("tsa_url",                     AttributeValue::S(tsa_url_opt.clone().unwrap_or_default()))
        // Key Attestation (DT-002 fallback)
        .item("attestation_type",            AttributeValue::S(req.attestation_type.clone()))
        .item("attestation_security_level",  AttributeValue::S(attestation_security_level.clone()))
        .item("attestation_bootloader",      AttributeValue::Bool(attestation_bootloader_locked))
        .item("attestation_verified_boot",   AttributeValue::S(attestation_verified_boot.clone()))
        .item("status",                      AttributeValue::S("stored".to_string()))
        .send()
        .await
        .map_err(|e| format!("Falha ao registrar sessão no DynamoDB: {e}"))?;

    info!(
        session_id   = %req.session_id,
        signing_mode = %signing_mode,
        icp_brasil   = icp_signed,
        tsa_status   = %tsa_status,
        "Sessão registrada no DynamoDB"
    );

    LambdaHttpResponse::ok(&SignResponse {
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
        tsa_status,
        tsa_url:          tsa_url_opt.as_deref().unwrap_or("").to_string(),
        attestation_type: req.attestation_type,
    })
}

// ---------------------------------------------------------------------------
// Android Key Attestation — verificação de cadeia e parse da extensão (DT-002)
// ---------------------------------------------------------------------------

/// Campos extraídos da extensão KeyDescription do Android Key Attestation.
struct AttestationFields {
    /// "software" | "tee" | "strongbox"
    security_level:    String,
    /// true se bootloader está bloqueado (locked)
    bootloader_locked: bool,
    /// "verified" | "self_signed" | "unverified" | "failed"
    verified_boot:     String,
    /// true se o challenge da cadeia corresponde ao session_id da requisição
    challenge_valid:   bool,
}

/// Converte erros de DER (TsaError) para String — reutiliza os helpers DER existentes.
fn der_str<'a>(bytes: &'a [u8], tag: u8) -> Result<(&'a [u8], &'a [u8]), String> {
    der_unwrap_tag(bytes, tag).map_err(|e| format!("{e}"))
}

/// Lê o próximo elemento DER com qualquer um dos tags permitidos.
/// Retorna (tag encontrado, conteúdo, bytes restantes).
fn der_any_of<'a>(bytes: &'a [u8], tags: &[u8]) -> Result<(u8, &'a [u8], &'a [u8]), String> {
    if bytes.is_empty() {
        return Err("Buffer vazio ao ler elemento DER".to_string());
    }
    let tag = bytes[0];
    if !tags.contains(&tag) {
        return Err(format!("Tag DER 0x{tag:02X} não esperado aqui"));
    }
    let (content, rest) = der_str(bytes, tag)?;
    Ok((tag, content, rest))
}

/// Avança um elemento DER completo sem inspecionar o conteúdo.
fn der_skip(bytes: &[u8]) -> Result<&[u8], String> {
    if bytes.is_empty() {
        return Err("Buffer vazio ao tentar pular elemento DER".to_string());
    }
    let mut pos = 1usize;
    // Trata tags de forma longa (high-tag-number form: bit5-1 = 0x1F)
    if bytes[0] & 0x1F == 0x1F {
        while pos < bytes.len() && bytes[pos] & 0x80 != 0 {
            pos += 1;
        }
        pos += 1;
    }
    if pos > bytes.len() {
        return Err("Tag DER truncada".to_string());
    }
    let (len, len_bytes) = der_read_length(&bytes[pos..]).map_err(|e| format!("{e}"))?;
    pos += len_bytes + len;
    if pos > bytes.len() {
        return Err("Elemento DER truncado".to_string());
    }
    Ok(&bytes[pos..])
}

/// Parseia o campo `rootOfTrust` da `AuthorizationList` do hardware enforced.
///
/// `rootOfTrust` tem CONTEXT-SPECIFIC CONSTRUCTED tag [704] em DER: 0xBF 0x85 0x40.
/// Dentro há uma SEQUENCE com: verifiedBootKey (OCTET STRING), deviceLocked (BOOLEAN),
/// verifiedBootState (ENUMERATED), verifiedBootHash (OCTET STRING, opcional).
fn parse_root_of_trust(hw_content: &[u8]) -> Result<(bool, String), String> {
    let mut remaining = hw_content;

    while !remaining.is_empty() {
        // rootOfTrust: CONTEXT tag [704] = 0xBF 0x85 0x40
        if remaining.len() >= 3
            && remaining[0] == 0xBF
            && remaining[1] == 0x85
            && remaining[2] == 0x40
        {
            // Lê o comprimento após os 3 bytes de tag
            let (len, len_bytes) = der_read_length(&remaining[3..])
                .map_err(|e| format!("{e}"))?;
            let start = 3 + len_bytes;
            if remaining.len() < start + len {
                return Err("rootOfTrust truncado".to_string());
            }
            let rot_outer = &remaining[start..start + len];

            // O conteúdo de [704] é uma SEQUENCE
            let (rot_content, _) = der_str(rot_outer, 0x30)?;

            // verifiedBootKey — OCTET STRING (ignoramos o valor)
            let (_, rest) = der_str(rot_content, 0x04)?;

            // deviceLocked — BOOLEAN
            let (locked_bytes, rest) = der_str(rest, 0x01)?;
            let bootloader_locked = locked_bytes.first().copied().unwrap_or(0) != 0;

            // verifiedBootState — ENUMERATED (0x0A)
            let (state_bytes, _) = der_str(rest, 0x0A)?;
            let verified_boot = match state_bytes.first().copied().unwrap_or(0) {
                0 => "verified",
                1 => "self_signed",
                2 => "unverified",
                3 => "failed",
                _ => "unknown",
            }
            .to_string();

            return Ok((bootloader_locked, verified_boot));
        }

        remaining = der_skip(remaining)?;
    }

    // rootOfTrust ausente — dispositivo mais antigo ou schema diferente; assumir seguro com aviso
    tracing::warn!("rootOfTrust não encontrado em hardwareEnforced — assumindo bootloader=locked, verified");
    Ok((true, "verified".to_string()))
}

/// Parseia a extensão `KeyDescription` do Android Key Attestation.
///
/// OID: 1.3.6.1.4.1.11129.2.1.17 — presente no certificado folha da cadeia.
/// Extrai: attestationSecurityLevel, attestationChallenge, bootloaderLocked, verifiedBootState.
fn parse_attestation_extension(
    ext_value:          &[u8],
    expected_challenge: &[u8],
) -> Result<AttestationFields, String> {
    // KeyDescription é uma SEQUENCE no nível raiz
    let (kd, _) = der_str(ext_value, 0x30)?;

    // attestationVersion — INTEGER
    let (_, rest) = der_str(kd, 0x02)?;

    // attestationSecurityLevel — ENUMERATED (0x0A) ou INTEGER (0x02) dependendo da versão
    let (_, sec_level_bytes, rest) = der_any_of(rest, &[0x0A, 0x02])?;
    let security_level = match sec_level_bytes.first().copied().unwrap_or(0) {
        0 => "software",
        1 => "tee",
        2 => "strongbox",
        _ => "unknown",
    }
    .to_string();

    // keymasterVersion — INTEGER
    let (_, rest) = der_str(rest, 0x02)?;

    // keymasterSecurityLevel — ENUMERATED ou INTEGER
    let (_, _, rest) = der_any_of(rest, &[0x0A, 0x02])?;

    // attestationChallenge — OCTET STRING
    let (challenge_bytes, rest) = der_str(rest, 0x04)?;
    let challenge_valid = challenge_bytes == expected_challenge;

    // uniqueId — OCTET STRING
    let (_, rest) = der_str(rest, 0x04)?;

    // softwareEnforced — SEQUENCE (pulamos o conteúdo)
    let rest = der_skip(rest)?;

    // hardwareEnforced — SEQUENCE
    let (hw_content, _) = der_str(rest, 0x30)?;

    let (bootloader_locked, verified_boot) = parse_root_of_trust(hw_content)?;

    Ok(AttestationFields {
        security_level,
        bootloader_locked,
        verified_boot,
        challenge_valid,
    })
}

/// Verifica a cadeia de certificados do Android Key Attestation e extrai os campos
/// de integridade do dispositivo.
///
/// Passos:
/// 1. Decodifica base64 → DER para cada certificado
/// 2. Parseia com x509-parser e verifica assinaturas (cada cert assinado pelo próximo)
/// 3. Extrai e parseia a extensão KeyDescription do certificado folha
/// 4. Verifica que o challenge corresponde ao session_id da requisição
///
/// **TODO produção:** verificar o certificado raiz contra a Google Hardware Attestation Root CA.
/// O PEM está disponível em: https://developer.android.com/training/articles/security-key-attestation
/// Configurar a env var `GOOGLE_ATTESTATION_ROOT_CA_PEM` na Lambda para verificação completa.
fn verify_key_attestation(
    chain_b64:    &[String],
    session_id:   &str,
    google_roots: Option<&GoogleAttestationRoots>,
) -> Result<AttestationFields, String> {
    use x509_parser::prelude::*;

    if chain_b64.is_empty() {
        return Err("Cadeia de certificados vazia".to_string());
    }

    // Decodifica base64 → DER
    let chain_ders: Vec<Vec<u8>> = chain_b64
        .iter()
        .map(|b| {
            base64::Engine::decode(&base64::engine::general_purpose::STANDARD, b)
                .map_err(|e| format!("Base64 inválido na cadeia de attestation: {e}"))
        })
        .collect::<Result<_, _>>()?;

    // Parseia todos os certificados
    let certs: Vec<X509Certificate<'_>> = chain_ders
        .iter()
        .enumerate()
        .map(|(i, der)| {
            X509Certificate::from_der(der)
                .map(|(_, c)| c)
                .map_err(|e| format!("Falha ao parsear certificado [{i}] da cadeia: {e:?}"))
        })
        .collect::<Result<_, _>>()?;

    // Verifica que cada certificado é assinado pelo próximo (integridade da cadeia)
    for i in 0..certs.len().saturating_sub(1) {
        certs[i]
            .verify_signature(Some(certs[i + 1].public_key()))
            .map_err(|e| format!("Falha na verificação de assinatura do cert [{i}]: {e:?}"))?;
    }

    // Ancoragem no Google Hardware Attestation Root CA
    //
    // Compara os bytes DER do certificado raiz da cadeia contra os CAs conhecidos
    // carregados do Secrets Manager no cold start.
    //
    // Se google_roots não estiver disponível (env var não configurada), a verificação
    // de raiz é ignorada com aviso — útil para testes locais sem Secrets Manager.
    if let Some(roots) = google_roots {
        let root_der = chain_ders
            .last()
            .ok_or_else(|| "Cadeia vazia — sem certificado raiz".to_string())?;
        if !roots.der_certs.iter().any(|known| known == root_der) {
            // Loga subject e base64 do root para diagnóstico — permite identificar
            // qual CA Google adicionar ao secret caso seja uma CA válida não catalogada.
            let root_b64 = base64::Engine::encode(
                &base64::engine::general_purpose::STANDARD,
                root_der,
            );
            let root_subject = X509Certificate::from_der(root_der)
                .map(|(_, c)| c.subject().to_string())
                .unwrap_or_else(|_| "<parse error>".to_string());
            tracing::warn!(
                chain_len    = chain_ders.len(),
                root_subject = %root_subject,
                root_b64     = %root_b64,
                "Root CA não reconhecido — logar para diagnóstico"
            );
            return Err(
                "Certificado raiz da cadeia não corresponde a nenhum Google Hardware Attestation Root CA conhecido"
                    .to_string(),
            );
        }
        tracing::info!("Cadeia de Key Attestation ancorada no Google Root CA com sucesso");
    } else {
        tracing::warn!(
            session_id = %session_id,
            "Google Attestation Root CA não configurado — verificação de raiz omitida"
        );
    }

    // Extrai a extensão KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17) do cert folha
    let leaf = &certs[0];
    let attestation_ext = leaf
        .extensions()
        .iter()
        .find(|ext| ext.oid.to_id_string() == "1.3.6.1.4.1.11129.2.1.17")
        .ok_or_else(|| {
            "Extensão Android Key Attestation (1.3.6.1.4.1.11129.2.1.17) ausente no certificado folha"
                .to_string()
        })?;

    parse_attestation_extension(attestation_ext.value, session_id.as_bytes())
}

// ---------------------------------------------------------------------------
// Âncora temporal RFC 3161 — DER encoding, parse e HTTP (DT-016)
// ---------------------------------------------------------------------------

/// Codifica o comprimento em DER (BER definite short/long form).
fn der_length(len: usize) -> Vec<u8> {
    if len < 128 {
        vec![len as u8]
    } else if len < 256 {
        vec![0x81, len as u8]
    } else {
        vec![0x82, (len >> 8) as u8, (len & 0xFF) as u8]
    }
}

/// Codifica tag + comprimento + conteúdo.
fn der_tlv(tag: u8, value: &[u8]) -> Vec<u8> {
    let mut out = vec![tag];
    out.extend_from_slice(&der_length(value.len()));
    out.extend_from_slice(value);
    out
}

fn der_sequence(content: &[u8]) -> Vec<u8>    { der_tlv(0x30, content) }
fn der_octet_string(b: &[u8]) -> Vec<u8>      { der_tlv(0x04, b) }
fn der_null() -> Vec<u8>                       { vec![0x05, 0x00] }
fn der_boolean_true() -> Vec<u8>               { vec![0x01, 0x01, 0xFF] }

/// Codifica INTEGER 1 (versão v1 do TimeStampReq).
fn der_integer_one() -> Vec<u8> { vec![0x02, 0x01, 0x01] }

/// Codifica um INTEGER u64 em DER minimal encoding.
fn der_integer_u64(n: u64) -> Vec<u8> {
    let bytes = n.to_be_bytes();
    // Remove zeros à esquerda, mas mantém pelo menos um byte
    let start = bytes.iter().position(|&b| b != 0).unwrap_or(7);
    let mut minimal = bytes[start..].to_vec();
    // Adiciona 0x00 se o bit mais significativo estiver setado (evita interpretação como negativo)
    if minimal[0] & 0x80 != 0 {
        minimal.insert(0, 0x00);
    }
    der_tlv(0x02, &minimal)
}

/// AlgorithmIdentifier para SHA-256: SEQUENCE { OID(2.16.840.1.101.3.4.2.1), NULL }
fn sha256_alg_id() -> Vec<u8> {
    // OID 2.16.840.1.101.3.4.2.1 em DER:
    // primeiro octeto: 2*40+16 = 96 = 0x60; depois BER-encoded 840, 1, 101, 3, 4, 2, 1
    let oid_value: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01];
    let mut content = der_tlv(0x06, oid_value);
    content.extend_from_slice(&der_null());
    der_sequence(&content)
}

/// Constrói um TimeStampReq DER-encoded (RFC 3161 §2.4.1).
///
/// Estrutura:
/// ```text
/// TimeStampReq SEQUENCE {
///   version INTEGER { v1(1) },
///   messageImprint MessageImprint SEQUENCE {
///     hashAlgorithm AlgorithmIdentifier,  -- SHA-256
///     hashedMessage OCTET STRING          -- 32 bytes
///   },
///   nonce   INTEGER,
///   certReq BOOLEAN TRUE
/// }
/// ```
fn build_ts_req(hash: &[u8], nonce: u64) -> Vec<u8> {
    // MessageImprint
    let mut msg_imp_content = sha256_alg_id();
    msg_imp_content.extend_from_slice(&der_octet_string(hash));
    let msg_imp = der_sequence(&msg_imp_content);

    // TimeStampReq
    let mut req_content = der_integer_one();
    req_content.extend_from_slice(&msg_imp);
    req_content.extend_from_slice(&der_integer_u64(nonce));
    req_content.extend_from_slice(&der_boolean_true());

    der_sequence(&req_content)
}

/// Lê o comprimento DER a partir de `bytes`. Retorna (comprimento, bytes_consumidos).
fn der_read_length(bytes: &[u8]) -> Result<(usize, usize), TsaError> {
    if bytes.is_empty() {
        return Err(TsaError::ParseError("DER length: buffer vazio".to_string()));
    }
    if bytes[0] < 0x80 {
        Ok((bytes[0] as usize, 1))
    } else {
        let num_bytes = (bytes[0] & 0x7F) as usize;
        if num_bytes == 0 || num_bytes > 3 || bytes.len() < 1 + num_bytes {
            return Err(TsaError::ParseError(
                format!("DER length: num_bytes={num_bytes} inválido ou buffer truncado")
            ));
        }
        let mut len = 0usize;
        for i in 0..num_bytes {
            len = (len << 8) | bytes[1 + i] as usize;
        }
        Ok((len, 1 + num_bytes))
    }
}

/// Desembrulha um elemento DER com a tag esperada.
/// Retorna `(conteúdo_do_elemento, bytes_restantes_após_o_elemento)`.
fn der_unwrap_tag<'a>(bytes: &'a [u8], expected_tag: u8) -> Result<(&'a [u8], &'a [u8]), TsaError> {
    if bytes.is_empty() || bytes[0] != expected_tag {
        return Err(TsaError::ParseError(format!(
            "DER tag esperado 0x{:02X}, encontrado 0x{:02X}",
            expected_tag,
            bytes.first().copied().unwrap_or(0)
        )));
    }
    let (len, len_bytes) = der_read_length(&bytes[1..])?;
    let start = 1 + len_bytes;
    if bytes.len() < start + len {
        return Err(TsaError::ParseError(
            format!("DER: buffer truncado (precisa {} bytes, tem {})", start + len, bytes.len())
        ));
    }
    Ok((&bytes[start..start + len], &bytes[start + len..]))
}

/// Parseia uma resposta TimeStampResp DER-encoded (RFC 3161 §2.4.2).
///
/// Verifica PKIStatus == 0 (granted) e retorna os bytes do TimeStampToken.
/// O token é um ContentInfo (CMS SEQUENCE) que começa imediatamente após o PKIStatusInfo.
fn parse_tsa_response(bytes: &[u8]) -> Result<Vec<u8>, TsaError> {
    // TimeStampResp SEQUENCE { PKIStatusInfo, TimeStampToken? }
    let (resp_content, _) = der_unwrap_tag(bytes, 0x30)?;

    // PKIStatusInfo SEQUENCE { status INTEGER, ... }
    let (status_info_content, token_bytes) = der_unwrap_tag(resp_content, 0x30)?;

    // status INTEGER — primeiro elemento do PKIStatusInfo
    let (status_value, _) = der_unwrap_tag(status_info_content, 0x02)?;
    let status = status_value.first().copied().unwrap_or(255);
    if status != 0 {
        return Err(TsaError::NotGranted(status));
    }

    // TimeStampToken é o ContentInfo SEQUENCE que segue o PKIStatusInfo
    if token_bytes.is_empty() {
        return Err(TsaError::ParseError(
            "TimeStampToken ausente na resposta com status=granted".to_string()
        ));
    }

    Ok(token_bytes.to_vec())
}

/// Envia um TimeStampReq e retorna os bytes do TimeStampToken da resposta (RFC 3161).
async fn request_tsa_token(hash: &[u8], tsa_url: &str) -> Result<Vec<u8>, TsaError> {
    use rand::Rng;
    let nonce: u64 = rand::thread_rng().gen();
    let ts_req = build_ts_req(hash, nonce);

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| TsaError::Network(e.to_string()))?;

    let response = client
        .post(tsa_url)
        .header("Content-Type", "application/timestamp-query")
        .body(ts_req)
        .send()
        .await
        .map_err(|e| TsaError::Network(e.to_string()))?;

    if !response.status().is_success() {
        return Err(TsaError::Network(format!(
            "TSA retornou HTTP {}", response.status()
        )));
    }

    let response_bytes = response.bytes().await
        .map_err(|e| TsaError::Network(e.to_string()))?;

    parse_tsa_response(&response_bytes)
}

/// Tenta obter o token TSA até 3 vezes com backoff 1s / 2s / 4s (DT-017).
async fn request_tsa_with_retry(hash: &[u8], tsa_url: &str) -> Result<Vec<u8>, TsaError> {
    let delays_secs: &[u64] = &[1, 2, 4];
    let mut last_err = TsaError::Network("nenhuma tentativa realizada".to_string());

    for (attempt, &delay_s) in delays_secs.iter().enumerate() {
        match request_tsa_token(hash, tsa_url).await {
            Ok(token) => return Ok(token),
            Err(e) => {
                tracing::warn!(
                    attempt    = attempt + 1,
                    delay_s    = delay_s,
                    tsa_url    = %tsa_url,
                    error      = %e,
                    "Tentativa TSA falhou"
                );
                last_err = e;
                if attempt + 1 < delays_secs.len() {
                    tokio::time::sleep(std::time::Duration::from_secs(delay_s)).await;
                }
            }
        }
    }

    Err(last_err)
}

// ---------------------------------------------------------------------------
// Assinatura ICP-Brasil — RSA PKCS#1 v1.5 + SHA-256 (sha256WithRSAEncryption)
// ---------------------------------------------------------------------------

/// Assina `data` com a chave privada RSA do certificado ICP-Brasil A1.
fn sign_with_icp_brasil(cert: &IcpBrasilCert, data: &[u8]) -> Result<String, Error> {
    use rsa::{RsaPrivateKey, pkcs1v15::SigningKey};
    use rsa::pkcs8::DecodePrivateKey;
    use rsa::pkcs1::DecodeRsaPrivateKey;
    use sha2::Sha256;
    use rsa::signature::{Signer, SignatureEncoding};

    let private_key = RsaPrivateKey::from_pkcs8_pem(&cert.private_key_pem)
        .or_else(|_| RsaPrivateKey::from_pkcs1_pem(&cert.private_key_pem))
        .map_err(|e| format!("Falha ao carregar chave RSA ICP-Brasil: {e}"))?;

    let signing_key = SigningKey::<Sha256>::new(private_key);
    let signature: rsa::pkcs1v15::Signature = signing_key.sign(data);

    Ok(hex::encode(signature.to_bytes()))
}

/// Valida que o PEM da chave privada é parseável.
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
    // Cold start — carrega certificados uma única vez.
    // ------------------------------------------------------------------
    let aws_config   = aws_config::load_from_env().await;
    let sm           = aws_sdk_secretsmanager::Client::new(&aws_config);
    let icp_cert     = Arc::new(load_icp_brasil_cert(&sm).await);
    let google_roots = Arc::new(load_google_attestation_roots(&sm).await);

    if icp_cert.is_some() {
        info!(signing_mode = "icp_brasil_a1", "Lambda pronta — assinatura ICP-Brasil ativa");
    } else {
        tracing::warn!(signing_mode = "kms_dev", "Lambda pronta — assinatura ICP-Brasil indisponível, usando KMS");
    }

    if google_roots.is_some() {
        info!("Google Hardware Attestation Root CA carregados — ancoragem de Key Attestation ativa");
    } else {
        tracing::warn!("GOOGLE_ATTESTATION_ROOT_CA_SECRET não configurada — verificação de raiz de Key Attestation desabilitada");
    }

    run(service_fn(move |event: LambdaEvent<FunctionUrlEvent>| {
        let icp_cert     = icp_cert.clone();
        let google_roots = google_roots.clone();
        async move { handler(event, icp_cert, google_roots).await }
    })).await
}
