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
    /// "granted" | "skipped" — resultado da âncora temporal RFC 3161 (DT-016)
    tsa_status:       String,
    /// URL da TSA usada nesta requisição (vazia se TSA_URL não configurada)
    tsa_url:          String,
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
    event:    LambdaEvent<FunctionUrlEvent>,
    icp_cert: Arc<Option<IcpBrasilCert>>,
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
        .item("tsa_status",      AttributeValue::S(tsa_status.clone()))
        .item("tsa_token",       AttributeValue::S(tsa_token_b64))
        .item("tsa_url",         AttributeValue::S(tsa_url_opt.clone().unwrap_or_default()))
        .item("status",          AttributeValue::S("stored".to_string()))
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
        tsa_url:         tsa_url_opt.as_deref().unwrap_or("").to_string(),
    })
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
    // Cold start — carrega o certificado ICP-Brasil uma única vez.
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
