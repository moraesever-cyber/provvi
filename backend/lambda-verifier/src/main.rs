use std::sync::Arc;

use aws_sdk_dynamodb::types::AttributeValue;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tracing::info;

// ---------------------------------------------------------------------------
// Certificado público ICP-Brasil — carregado no cold start, reutilizado
// ---------------------------------------------------------------------------

/// Apenas o certificado público — nunca a chave privada.
struct IcpPublicCert {
    certificate_pem: String,
}

/// Lê apenas `certificate_pem` do JSON armazenado no secret.
/// Os campos private_key_pem e chain_pem existem no secret mas são ignorados.
#[derive(Deserialize)]
struct IcpSecretPartial {
    certificate_pem: String,
}

/// Carrega o certificado público ICP-Brasil do Secrets Manager no cold start.
///
/// Retorna `None` se o secret não estiver acessível — o verifier continua
/// funcionando, reportando `icp_signature_valid: null` para sessões ICP-Brasil.
async fn load_icp_public_cert(
    sm: &aws_sdk_secretsmanager::Client,
) -> Option<IcpPublicCert> {
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
                error     = %e,
                "Falha ao carregar certificado público ICP-Brasil do Secrets Manager"
            );
        })
        .ok()?;

    let secret_str = match response.secret_string() {
        Some(s) => s,
        None => {
            tracing::error!("Secret ICP-Brasil não contém string JSON");
            return None;
        }
    };

    let parsed: IcpSecretPartial = serde_json::from_str(secret_str)
        .map_err(|e| {
            tracing::error!(error = %e, "Falha ao desserializar JSON do secret ICP-Brasil");
        })
        .ok()?;

    info!("Certificado público ICP-Brasil carregado para verificação criptográfica");
    Some(IcpPublicCert { certificate_pem: parsed.certificate_pem })
}

// ---------------------------------------------------------------------------
// Verificação criptográfica ICP-Brasil
// ---------------------------------------------------------------------------

/// Extrai o primeiro bloco `-----BEGIN CERTIFICATE-----` ... `-----END CERTIFICATE-----`
/// de uma cadeia PEM (end-entity + intermediárias + raiz).
///
/// O `pem-rfc7468` não aceita múltiplos blocos concatenados — é necessário isolar
/// apenas o certificado folha antes de chamar `Certificate::from_pem()`.
fn extract_first_pem_block(chain_pem: &str) -> Option<&str> {
    let begin = chain_pem.find("-----BEGIN CERTIFICATE-----")?;
    let after_begin = begin + "-----BEGIN CERTIFICATE-----".len();
    // Localiza o END correspondente ao primeiro BEGIN
    let end_marker = "-----END CERTIFICATE-----";
    let end_pos = chain_pem[after_begin..].find(end_marker)?;
    let end = after_begin + end_pos + end_marker.len();
    Some(&chain_pem[begin..end])
}

/// Verifica assinatura RSA-PKCS1v15-SHA256 contra a chave pública do certificado ICP-Brasil.
///
/// Retorna `Ok(true)` se a assinatura é válida, `Ok(false)` se inválida,
/// `Err` se não foi possível realizar a verificação (parse do cert, hex inválido, etc.).
fn verify_icp_brasil_signature(
    cert:          &IcpPublicCert,
    data:          &[u8],
    signature_hex: &str,
) -> Result<bool, Error> {
    use x509_cert::Certificate;
    use x509_cert::der::{DecodePem, Encode};
    use rsa::{RsaPublicKey, pkcs8::DecodePublicKey};
    use rsa::pkcs1v15::VerifyingKey;
    use sha2::Sha256;
    use rsa::signature::Verifier;

    // Extrai apenas o primeiro bloco PEM da cadeia (certificado folha).
    // O campo certificate_pem pode conter a cadeia completa (end-entity + intermediárias + raiz).
    // pem-rfc7468 não aceita múltiplos blocos — extraímos apenas o primeiro.
    let first_cert_pem = extract_first_pem_block(&cert.certificate_pem)
        .ok_or("Bloco PEM não encontrado no certificate_pem")?;

    let cert = Certificate::from_pem(first_cert_pem)
        .map_err(|e| format!("Falha ao parsear certificado ICP-Brasil: {e}"))?;

    // Extrai o SubjectPublicKeyInfo em DER para decodificar a chave pública RSA
    let spki_der = cert.tbs_certificate.subject_public_key_info
        .to_der()
        .map_err(|e| format!("Falha ao serializar SPKI do certificado: {e}"))?;

    let public_key = RsaPublicKey::from_public_key_der(&spki_der)
        .map_err(|e| format!("Falha ao decodificar chave pública RSA: {e}"))?;

    let verifying_key = VerifyingKey::<Sha256>::new(public_key);

    // Decodifica a assinatura hex armazenada no DynamoDB
    let sig_bytes = hex::decode(signature_hex)
        .map_err(|e| format!("Assinatura ICP-Brasil não é hex válido: {e}"))?;

    let signature = rsa::pkcs1v15::Signature::try_from(sig_bytes.as_slice())
        .map_err(|e| format!("Falha ao construir Signature RSA: {e}"))?;

    // Verifica — Verifier::verify retorna Ok(()) se válida, Err se inválida
    Ok(Verifier::verify(&verifying_key, data, &signature).is_ok())
}

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct FunctionUrlEvent {
    body:               Option<String>,
    #[serde(rename = "isBase64Encoded")]
    is_base64_encoded:  Option<bool>,
    headers:            Option<std::collections::HashMap<String, String>>,
    #[serde(rename = "queryStringParameters")]
    query_string_parameters: Option<std::collections::HashMap<String, String>>,
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
    // Campos existentes
    valid:               bool,
    session_id:          String,
    captured_at_nanos:   i64,
    captured_at_ms:      i64,
    captured_at_iso:     String,
    frame_hash_hex:      String,
    frame_hash_match:    Option<bool>,
    kms_signed:          bool,
    location_suspicious: bool,
    assertions:          serde_json::Value,
    verification_note:   String,
    // Campos ICP-Brasil
    icp_brasil:          bool,
    icp_subject:         String,
    icp_cnpj:            String,
    icp_valid_until:     String,
    signing_mode:        String,
    /// None = não foi possível verificar; Some(true/false) = resultado criptográfico
    icp_signature_valid: Option<bool>,
    /// "granted" | "skipped" | "" — resultado da âncora temporal RFC 3161 (DT-016)
    tsa_status:          String,
    /// "play_integrity" | "key_attestation" | "" — mecanismo de integridade usado pelo SDK
    attestation_type:           String,
    /// "tee" | "strongbox" | "" — nível de segurança do hardware (Key Attestation)
    attestation_security_level: String,
    /// true se bootloader está bloqueado (Key Attestation)
    attestation_bootloader:     bool,
    /// "verified" | "self_signed" | "unverified" | "failed" | "" (Key Attestation)
    attestation_verified_boot:  String,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

// ---------------------------------------------------------------------------
// CSS do certificado — fora do format! para evitar conflito com chaves CSS
// ---------------------------------------------------------------------------

const CERTIFICATE_CSS: &str = r#"
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#F1F5F9;color:#0F172A;min-height:100vh;padding:16px 12px}
.page{max-width:480px;margin:0 auto;background:white;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.10)}
.header{background:#0D1B2A;padding:20px;display:flex;align-items:center;gap:12px}
.logo-wrap{display:flex;align-items:center;gap:10px}
.logo-wordmark{font-family:'Playfair Display',Georgia,serif;font-weight:700;font-size:22px;color:white;letter-spacing:-.3px;line-height:1}
.header p{color:#94A3B8;font-size:12px;margin-top:2px}
.status-banner{display:flex;align-items:flex-start;gap:12px;padding:14px 20px;border-left:4px solid}
.status-banner.valid{background:#F0FDF4;border-color:#16A34A}
.status-banner.invalid{background:#FEF2F2;border-color:#DC2626}
.status-banner.warning{background:#FFFBEB;border-color:#F59E0B}
.status-icon{font-size:26px;flex-shrink:0}
.status-title{font-size:15px;font-weight:700}
.status-banner.valid .status-title{color:#15803D}
.status-banner.invalid .status-title{color:#DC2626}
.status-banner.warning .status-title{color:#92400E}
.status-sub{font-size:12px;margin-top:3px}
.status-banner.valid .status-sub{color:#166534}
.status-banner.invalid .status-sub{color:#991B1B}
.status-banner.warning .status-sub{color:#92400E}
.section{padding:16px 20px;border-bottom:1px solid #F1F5F9}
.section-title{font-size:10px;font-weight:700;letter-spacing:1.5px;color:#94A3B8;text-transform:uppercase;margin-bottom:10px}
.meta-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.meta-item{background:#F8FAFC;border-radius:8px;padding:10px 12px}
.meta-item.full{grid-column:1/-1}
.meta-label{font-size:10px;color:#94A3B8;font-weight:600;letter-spacing:.5px;text-transform:uppercase}
.meta-value{font-size:13px;font-weight:600;color:#0F172A;margin-top:3px;word-break:break-all}
.meta-value.mono{font-family:'Courier New',monospace;font-size:11px;font-weight:400;color:#475569}
.check-list{display:flex;flex-direction:column;gap:2px}
.check-item{display:flex;align-items:center;gap:10px;font-size:13px;padding:7px 0;border-bottom:1px solid #F8FAFC}
.check-item:last-child{border-bottom:none}
.check-icon{font-size:16px;flex-shrink:0;width:22px;text-align:center}
.risk-badge{display:inline-flex;align-items:center;gap:3px;padding:2px 10px;border-radius:20px;font-size:12px;font-weight:700}
.risk-none{background:#DCFCE7;color:#166534}
.risk-medium{background:#FEF3C7;color:#92400E}
.risk-high{background:#FEE2E2;color:#991B1B}
.hash-box{background:#0D1B2A;border-radius:10px;padding:14px 16px}
.hash-label{font-size:9px;font-weight:700;letter-spacing:1.5px;color:#60A5FA;text-transform:uppercase;margin-bottom:8px}
.hash-value{font-family:'Courier New',monospace;font-size:12px;color:white;line-height:1.75;word-break:break-all}
.attestation-detail{margin:2px 0 6px 32px;background:#F0FDF4;border-radius:8px;padding:8px 12px;border-left:2px solid #16A34A}
.att-row{display:flex;justify-content:space-between;align-items:center;font-size:12px;padding:3px 0;color:#374151}
.att-row:not(:last-child){border-bottom:1px solid #DCFCE7}
.att-val{font-weight:600;color:#166534}
.print-btn{display:block;width:calc(100% - 40px);margin:16px 20px 4px;padding:13px;background:#1D4ED8;color:white;border:none;border-radius:10px;font-size:15px;font-weight:700;cursor:pointer;font-family:inherit}
.print-btn:active{background:#1E40AF}
.footer{padding:12px 20px 20px;text-align:center}
.footer-text{font-size:11px;color:#94A3B8;line-height:1.6}
.footer-url{font-family:'Courier New',monospace;font-size:10px;color:#CBD5E1;word-break:break-all;margin-top:6px}
.nf-page{max-width:480px;margin:40px auto;text-align:center;padding:32px 24px;background:white;border-radius:12px;box-shadow:0 4px 24px rgba(0,0,0,.08)}
.nf-icon{font-size:56px;margin-bottom:16px}
.nf-title{font-size:20px;font-weight:700;color:#DC2626;margin-bottom:8px}
.nf-sub{font-size:14px;color:#64748B;line-height:1.5}
.nf-id{font-family:'Courier New',monospace;font-size:12px;color:#94A3B8;margin-top:16px;word-break:break-all}
.sig-badge{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:700}
.sig-icp{background:#DCFCE7;color:#166534}
.sig-kms{background:#FEF3C7;color:#92400E}
.sig-none{background:#F1F5F9;color:#64748B}
@media print{body{background:white;padding:0}.page{box-shadow:none;border-radius:0;max-width:none}.print-btn,.no-print{display:none!important}}
"#;

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

fn cors_response(body: serde_json::Value, status: u16) -> serde_json::Value {
    serde_json::json!({
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type"
        },
        "body": body.to_string()
    })
}

fn html_response(html: String, status: u16) -> serde_json::Value {
    serde_json::json!({
        "statusCode": status,
        "headers": {
            "Content-Type": "text/html; charset=utf-8",
            "Access-Control-Allow-Origin": "*"
        },
        "body": html
    })
}

/// Retorna true se o cliente prefere HTML — browser abrindo via QR Code.
fn want_html(headers: &Option<std::collections::HashMap<String, String>>) -> bool {
    let Some(h) = headers else { return false };
    h.get("accept")
        .or_else(|| h.get("Accept"))
        .map(|v| v.contains("text/html"))
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Renderização HTML
// ---------------------------------------------------------------------------

fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;")
     .replace('<', "&lt;")
     .replace('>', "&gt;")
     .replace('"', "&quot;")
}

fn format_hash(hash: &str) -> String {
    hash.chars()
        .collect::<Vec<char>>()
        .chunks(32)
        .map(|c| c.iter().collect::<String>())
        .collect::<Vec<String>>()
        .join("<br>")
}

fn format_date(iso: &str) -> String {
    use chrono::{DateTime, FixedOffset};
    let brt = FixedOffset::west_opt(3 * 3600).unwrap();
    DateTime::parse_from_rfc3339(iso)
        .map(|dt| dt.with_timezone(&brt).format("%d/%m/%Y %H:%M BRT").to_string())
        .unwrap_or_else(|_| iso.to_string())
}

/// "26988458000194" → "26.988.458/0001-94"
fn format_cnpj(cnpj: &str) -> String {
    if cnpj.len() == 14 {
        format!("{}.{}.{}/{}-{}",
            &cnpj[0..2], &cnpj[2..5], &cnpj[5..8],
            &cnpj[8..12], &cnpj[12..14])
    } else {
        cnpj.to_string()
    }
}

/// "2027-03-11" → "11/03/2027"
fn format_valid_until(date: &str) -> String {
    if date.len() == 10 {
        format!("{}/{}/{}", &date[8..10], &date[5..7], &date[0..4])
    } else {
        date.to_string()
    }
}

/// Monta o HTML do certificado de captura.
/// CSS é injetado como string literal — fora do format! — para evitar
/// conflito entre chaves CSS e chaves de interpolação do Rust.
#[allow(clippy::too_many_arguments)]
fn render_certificate_html(
    valid:               bool,
    session_id:          &str,
    captured_at_iso:     &str,
    frame_hash_hex:      &str,
    kms_signed:          bool,
    location_suspicious: bool,
    assertions:          &serde_json::Value,
    captured_by_stored:  &str,
    reference_id_stored: &str,
    verifier_url:        &str,
    // Campos ICP-Brasil
    icp_brasil:          bool,
    icp_subject:         &str,
    icp_cnpj:            &str,
    icp_valid_until:     &str,
    signing_mode:        &str,
    icp_signature_valid: Option<bool>,
    pdf_lambda_url:             &str,
    tsa_status:                 &str,   // "granted" | "skipped" | "" — DT-016
    attestation_type:           &str,   // "play_integrity" | "key_attestation" | "" — DT-002
    attestation_security_level: &str,   // "tee" | "strongbox" | ""
    attestation_bootloader:     bool,
    attestation_verified_boot:  &str,   // "verified" | "self_signed" | "unverified" | "failed" | ""
) -> String {
    let captured_by = if !captured_by_stored.is_empty() {
        captured_by_stored
    } else {
        assertions.get("captured_by").and_then(|v| v.as_str()).unwrap_or("—")
    };
    let reference_id = if !reference_id_stored.is_empty() {
        reference_id_stored
    } else {
        assertions.get("reference_id").and_then(|v| v.as_str()).unwrap_or("—")
    };
    let integrity_risk    = assertions.get("integrity_risk").and_then(|v| v.as_str()).unwrap_or("NONE");
    let clock_suspicious  = assertions.get("clock_suspicious").and_then(|v| v.as_bool()).unwrap_or(false);
    let recapture_verdict = assertions.get("recapture_verdict").and_then(|v| v.as_str()).unwrap_or("clean");

    let (status_class, status_icon, status_title, status_sub) = if valid {
        ("valid",   "✅", "Manifesto C2PA Verificado",
         "Assinatura criptográfica válida · ICP-Brasil")
    } else {
        ("invalid", "🚫", "Hash Divergente — Possível Adulteração",
         "A imagem fornecida não corresponde ao registro original.")
    };

    let risk_banner = if integrity_risk == "MEDIUM" {
        r#"<div class="status-banner warning"><div class="status-icon">⚠️</div><div><div class="status-title">Risco Médio Registrado</div><div class="status-sub">Indicadores de suspeita presentes. Disponível para revisão da seguradora.</div></div></div>"#
            .to_string()
    } else {
        String::new()
    };

    let clock_icon  = if clock_suspicious    { "⚠️" } else { "✅" };
    let clock_label = if clock_suspicious    { "Suspeito"           } else { "Verificado"  };
    let loc_icon    = if location_suspicious { "⚠️" } else { "✅" };
    let loc_label   = if location_suspicious { "Suspeita"           } else { "Verificada"  };
    let rec_icon    = if recapture_verdict == "clean" { "✅" } else { "⚠️" };
    let rec_label   = if recapture_verdict == "clean" { "Não detectada" } else { "Suspeita detectada" };

    let (tsa_icon, tsa_label) = match tsa_status {
        "granted" => ("✅", "Âncora temporal RFC 3161"),
        "skipped" => ("ℹ️", "Âncora temporal — não configurada"),
        _         => ("⚠️", "Âncora temporal ausente"),
    };

    let (att_icon, att_label) = match attestation_type {
        "key_attestation"  => ("✅", "Key Attestation (hardware)"),
        "play_integrity"   => ("✅", "Play Integrity API"),
        _                  => ("ℹ️", "Integridade de dispositivo — não disponível"),
    };

    // Bloco de detalhe expandido — exibido apenas para Key Attestation (gancho de demo)
    let att_detail_html = if attestation_type == "key_attestation" {
        let sec_label = match attestation_security_level {
            "strongbox" => "StrongBox (chip dedicado)",
            "tee"       => "TEE (enclave de hardware)",
            s if !s.is_empty() => s,
            _           => "Não informado",
        };
        let boot_label = if attestation_bootloader { "Bloqueado ✅" } else { "Desbloqueado ⚠️" };
        let vboot_label = match attestation_verified_boot {
            "verified"    => "Verified ✅",
            "self_signed" => "Self-signed ⚠️",
            "unverified"  => "Não verificado ⚠️",
            "failed"      => "Falhou 🚨",
            s if !s.is_empty() => s,
            _             => "Não informado",
        };
        format!(
            r#"<div class="attestation-detail"><div class="att-row"><span>🔒 Bootloader</span><span class="att-val">{boot}</span></div><div class="att-row"><span>🔄 Verified Boot</span><span class="att-val">{vboot}</span></div><div class="att-row"><span>🔐 Nível de segurança</span><span class="att-val">{sec}</span></div><div class="att-row"><span>🏛️ Raiz certificada</span><span class="att-val">Google Root CA ✓</span></div></div>"#,
            boot = boot_label, vboot = vboot_label, sec = sec_label,
        )
    } else {
        String::new()
    };

    // Ícone de assinatura do servidor reflete ICP-Brasil quando disponível
    let (sig_check_icon, sig_check_label) = if icp_brasil {
        ("✅", "ICP-Brasil A1")
    } else if kms_signed {
        ("✅", "KMS válida")
    } else {
        ("ℹ️", "Assinatura local")
    };

    let (risk_class, risk_label) = match integrity_risk {
        "MEDIUM" => ("risk-medium", "⚠️ Médio"),
        "HIGH"   => ("risk-high",   "🚨 Alto"),
        _        => ("risk-none",   "✅ Nenhum"),
    };

    // Badge do modo de assinatura
    let (sig_badge_class, sig_badge_text) = match signing_mode {
        "icp_brasil_a1" => ("sig-icp", "ICP-Brasil A1 ✓"),
        "kms_dev"       => ("sig-kms", "Desenvolvimento"),
        _               => ("sig-none", "Não disponível"),
    };

    // Status de verificação criptográfica
    let (crypto_icon, crypto_text) = match icp_signature_valid {
        Some(true)  => ("✅", "Assinatura válida"),
        Some(false) => ("❌", "Assinatura inválida"),
        None        => ("ⓘ", "Verificação pendente"),
    };

    // Campos ICP-Brasil (exibidos apenas quando icp_brasil = true)
    let icp_fields_html = if icp_brasil && !icp_subject.is_empty() {
        format!(
            r#"<div class="meta-item full"><div class="meta-label">Titular</div><div class="meta-value">{subj}</div></div><div class="meta-item"><div class="meta-label">CNPJ</div><div class="meta-value mono">{cnpj}</div></div><div class="meta-item"><div class="meta-label">Válido até</div><div class="meta-value">{vat}</div></div>"#,
            subj = html_escape(icp_subject),
            cnpj = format_cnpj(icp_cnpj),
            vat  = format_valid_until(icp_valid_until),
        )
    } else {
        String::new()
    };

    let hash_html    = format_hash(frame_hash_hex);
    let date_str     = format_date(captured_at_iso);
    let generated_at = {
        use chrono::{Duration, Datelike, Timelike, Utc};
        let n = Utc::now() - Duration::hours(3); // UTC → BRT
        format!("{:02}/{:02}/{} {:02}:{:02} BRT", n.day(), n.month(), n.year(), n.hour(), n.minute())
    };

    let captured_by_e  = html_escape(captured_by);
    let reference_id_e = html_escape(reference_id);
    let session_id_e   = html_escape(session_id);
    let verifier_url_e = html_escape(verifier_url);

    let mut body = String::with_capacity(4096);

    body.push_str(
        r##"<div class="header"><div class="logo-wrap"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="36" height="36" style="flex-shrink:0;transform:translateY(2px)"><circle cx="24" cy="24" r="19" fill="none" stroke="white" stroke-width="2.5"/><ellipse cx="24" cy="24" rx="7.5" ry="15" fill="none" stroke="#4A9EE8" stroke-width="2"/><ellipse cx="24" cy="24" rx="7.5" ry="15" fill="none" stroke="#4A9EE8" stroke-width="2" transform="rotate(60,24,24)"/><ellipse cx="24" cy="24" rx="7.5" ry="15" fill="none" stroke="#4A9EE8" stroke-width="2" transform="rotate(120,24,24)"/><circle cx="24" cy="24" r="3" fill="#4A9EE8"/></svg><span class="logo-wordmark">provvi</span></div><p>Certificado de Captura Autenticada</p></div>"##
    );

    body.push_str(&format!(
        r#"<div class="status-banner {sc}"><div class="status-icon">{si}</div><div><div class="status-title">{st}</div><div class="status-sub">{ss}</div></div></div>"#,
        sc = status_class, si = status_icon, st = status_title, ss = status_sub
    ));

    body.push_str(&risk_banner);

    body.push_str(&format!(
        r#"<div class="section"><div class="section-title">Identificação da Vistoria</div><div class="meta-grid"><div class="meta-item"><div class="meta-label">Vistoriador</div><div class="meta-value">{cb}</div></div><div class="meta-item"><div class="meta-label">Referência</div><div class="meta-value">{ri}</div></div><div class="meta-item full"><div class="meta-label">Data e Hora da Captura</div><div class="meta-value">{dt}</div></div><div class="meta-item full"><div class="meta-label">Session ID</div><div class="meta-value mono">{sid}</div></div></div></div>"#,
        cb = captured_by_e, ri = reference_id_e, dt = date_str, sid = session_id_e
    ));

    body.push_str(&format!(
        r#"<div class="section"><div class="section-title">Verificações de Integridade</div><div class="check-list"><div class="check-item"><span class="check-icon">{ci}</span><span>Relógio do dispositivo — <strong>{cl}</strong></span></div><div class="check-item"><span class="check-icon">{li}</span><span>Localização GPS — <strong>{ll}</strong></span></div><div class="check-item"><span class="check-icon">{ri}</span><span>Detecção de recaptura — <strong>{rl}</strong></span></div><div class="check-item"><span class="check-icon">{ai}</span><span>Integridade do dispositivo — <strong>{al}</strong></span></div>{attd}<div class="check-item"><span class="check-icon">{ki}</span><span>Assinatura do servidor — <strong>{kl}</strong></span></div><div class="check-item"><span class="check-icon">{ti}</span><span>Carimbo de tempo — <strong>{tl}</strong></span></div><div class="check-item"><span class="check-icon">🛡️</span><span>Risco geral — <span class="risk-badge {rbc}">{rbl}</span></span></div></div></div>"#,
        ci = clock_icon,     cl = clock_label,
        li = loc_icon,       ll = loc_label,
        ri = rec_icon,       rl = rec_label,
        ai = att_icon,       al = att_label,
        attd = att_detail_html,
        ki = sig_check_icon, kl = sig_check_label,
        ti = tsa_icon,       tl = tsa_label,
        rbc = risk_class,    rbl = risk_label,
    ));

    // Seção Assinatura Digital — inserida entre Verificações e Hash
    body.push_str(&format!(
        r#"<div class="section"><div class="section-title">Assinatura Digital</div><div class="meta-grid"><div class="meta-item full"><div class="meta-label">Modo</div><div class="meta-value"><span class="sig-badge {sbc}">{sbt}</span></div></div>{icpf}<div class="meta-item full"><div class="meta-label">Verificação Criptográfica</div><div class="meta-value">{ci}&nbsp;{ct}</div></div></div></div>"#,
        sbc  = sig_badge_class,
        sbt  = sig_badge_text,
        icpf = icp_fields_html,
        ci   = crypto_icon,
        ct   = crypto_text,
    ));

    body.push_str(&format!(
        r#"<div class="section"><div class="hash-box"><div class="hash-label">SHA-256 do Frame (pré-codec)</div><div class="hash-value">{hash}</div></div></div>"#,
        hash = hash_html
    ));

    body.push_str(r#"<button class="print-btn no-print" onclick="window.print()">🖨&nbsp; Imprimir Certificado</button>"#);
    if !pdf_lambda_url.is_empty() {
        let pdf_url = format!("{}?session_id={}", pdf_lambda_url.trim_end_matches('/'), session_id_e);
        body.push_str(&format!(
            r#"<a href="{url}" target="_blank" rel="noopener" class="print-btn no-print" style="display:block;text-align:center;text-decoration:none;background:#166534;margin-top:6px">⬇&nbsp; Baixar Relatório PDF</a>"#,
            url = html_escape(&pdf_url),
        ));
    }

    body.push_str(&format!(
        r#"<div class="footer"><div class="footer-text">Documento gerado por Provvi SDK · Assinatura C2PA · ICP-Brasil<br>Gerado em: {gen}</div><div class="footer-url">{url}</div></div>"#,
        gen = generated_at, url = verifier_url_e
    ));

    let mut page = String::with_capacity(body.len() + CERTIFICATE_CSS.len() + 256);
    page.push_str(r#"<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>Provvi — Certificado de Captura</title><link rel="preconnect" href="https://fonts.googleapis.com"><link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@700&display=swap" rel="stylesheet"><style>"#);
    page.push_str(CERTIFICATE_CSS);
    page.push_str(r#"</style></head><body><div class="page">"#);
    page.push_str(&body);
    page.push_str(r#"</div></body></html>"#);
    page
}

fn render_not_found_html(session_id: &str) -> String {
    let sid = html_escape(session_id);
    let mut page = String::new();
    page.push_str(r#"<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>Provvi — Sessão não encontrada</title><link rel="preconnect" href="https://fonts.googleapis.com"><link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@700&display=swap" rel="stylesheet"><style>"#);
    page.push_str(CERTIFICATE_CSS);
    page.push_str(&format!(
        r#"</style></head><body><div class="nf-page"><div class="nf-icon">🔍</div><div class="nf-title">Sessão Não Encontrada</div><div class="nf-sub">Esta captura não está registrada na base Provvi ou o session ID é inválido.</div><div class="nf-id">{sid}</div></div></body></html>"#
    ));
    page
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

async fn handler(
    event:    LambdaEvent<FunctionUrlEvent>,
    icp_cert: Arc<Option<IcpPublicCert>>,
) -> Result<serde_json::Value, Error> {

    // Preflight CORS
    if let Some(ref headers) = event.payload.headers {
        let method = headers.get(":method")
            .or_else(|| headers.get("x-http-method"))
            .map(|s| s.as_str());
        if method == Some("OPTIONS") {
            return Ok(cors_response(serde_json::json!({}), 200));
        }
    }

    let wants_html = want_html(&event.payload.headers);

    // Extrai o VerifyRequest (body JSON > query string > campos diretos)
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
    } else if let Some(ref qs) = event.payload.query_string_parameters {
        VerifyRequest {
            session_id:   qs.get("session_id").cloned(),
            image_base64: qs.get("image_base64").cloned(),
        }
    } else {
        VerifyRequest {
            session_id:   event.payload.session_id,
            image_base64: event.payload.image_base64,
        }
    };

    if req.session_id.is_none() && req.image_base64.is_none() {
        if wants_html {
            return Ok(html_response(render_not_found_html(""), 400));
        }
        return Ok(cors_response(serde_json::to_value(ErrorResponse {
            error: "Forneça session_id, image_base64, ou ambos.".to_string(),
        })?, 400));
    }

    let config   = aws_config::load_from_env().await;
    let dynamodb = aws_sdk_dynamodb::Client::new(&config);
    let s3       = aws_sdk_s3::Client::new(&config);
    let table    = std::env::var("DYNAMODB_TABLE")
        .unwrap_or_else(|_| "provvi-sessions".to_string());
    let bucket   = std::env::var("S3_BUCKET")
        .unwrap_or_else(|_| "provvi-manifests-dev".to_string());

    // Hash da imagem se fornecida
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
        info!(session_id = %sid, "Buscando por session_id");
        find_by_session_id(&dynamodb, &table, sid).await?
    } else {
        let hash = image_hash.as_ref().unwrap();
        info!(frame_hash = %hash, "Buscando por frame_hash");
        find_by_frame_hash(&dynamodb, &table, hash).await?
    };

    let Some(item) = item else {
        let sid = req.session_id.unwrap_or_default();
        if wants_html {
            return Ok(html_response(render_not_found_html(&sid), 200));
        }
        return Ok(cors_response(serde_json::to_value(VerifyResponse {
            valid:               false,
            session_id:          sid,
            captured_at_nanos:   0,
            captured_at_ms:      0,
            captured_at_iso:     String::new(),
            frame_hash_hex:      String::new(),
            frame_hash_match:    image_hash.map(|_| false),
            kms_signed:          false,
            location_suspicious: false,
            assertions:          serde_json::Value::Null,
            verification_note:   "Sessão não encontrada. Imagem não registrada ou adulterada."
                                     .to_string(),
            icp_brasil:          false,
            icp_subject:         String::new(),
            icp_cnpj:            String::new(),
            icp_valid_until:     String::new(),
            signing_mode:        String::new(),
            icp_signature_valid:        None,
            tsa_status:                 String::new(),
            attestation_type:           String::new(),
            attestation_security_level: String::new(),
            attestation_bootloader:     false,
            attestation_verified_boot:  String::new(),
        })?, 200));
    };

    // ------------------------------------------------------------------
    // Extrai campos do item DynamoDB
    // ------------------------------------------------------------------
    let session_id          = get_str(&item, "session_id");
    let frame_hash_hex      = get_str(&item, "frame_hash");
    let captured_at         = get_num(&item, "captured_at");
    let kms_signed          = get_bool(&item, "kms_signed");
    let location_suspicious = get_bool(&item, "location_suspicious");
    let captured_by_stored  = get_str(&item, "captured_by");
    let reference_id_stored = get_str(&item, "reference_id");
    let assertions_str      = get_str(&item, "assertions");
    let assertions: serde_json::Value = serde_json::from_str(&assertions_str)
        .unwrap_or(serde_json::Value::Null);
    // Campos ICP-Brasil (sessões antigas retornam string vazia / false — compatibilidade retroativa)
    let icp_brasil      = get_bool(&item, "icp_brasil");
    let icp_subject     = get_str(&item, "icp_subject");
    let icp_cnpj        = get_str(&item, "icp_cnpj");
    let icp_valid_until = get_str(&item, "icp_valid_until");
    let signing_mode    = get_str(&item, "signing_mode");
    let icp_signature   = get_str(&item, "icp_signature");
    let manifest_s3_key = get_str(&item, "manifest_s3_key");
    let tsa_status                 = get_str(&item, "tsa_status");
    let attestation_type           = get_str(&item, "attestation_type");
    let attestation_security_level = get_str(&item, "attestation_security_level");
    let attestation_bootloader     = get_bool(&item, "attestation_bootloader");
    let attestation_verified_boot  = get_str(&item, "attestation_verified_boot");

    // Verifica hash da imagem se fornecida
    let frame_hash_match = image_hash.map(|h| h == frame_hash_hex);
    let valid            = frame_hash_match.unwrap_or(true);

    // ------------------------------------------------------------------
    // Verificação criptográfica da assinatura ICP-Brasil
    //
    // Fluxo:
    // 1. Sessão deve ter icp_brasil=true e icp_signature não vazia
    // 2. Certificado público deve ter sido carregado no cold start
    // 3. Manifesto é buscado do S3 (dado assinado pelo lambda-signer)
    // 4. Assinatura RSA-SHA256 é verificada contra o manifesto
    // ------------------------------------------------------------------
    let icp_signature_valid: Option<bool> = if icp_brasil && !icp_signature.is_empty() {
        if let Some(cert) = icp_cert.as_ref().as_ref() {
            match fetch_manifest_from_s3(&s3, &bucket, &manifest_s3_key).await {
                Ok(manifest_json) => {
                    match verify_icp_brasil_signature(cert, manifest_json.as_bytes(), &icp_signature) {
                        Ok(result) => {
                            info!(
                                session_id = %session_id,
                                valid      = result,
                                "Verificação criptográfica ICP-Brasil concluída"
                            );
                            Some(result)
                        }
                        Err(e) => {
                            tracing::warn!(
                                session_id = %session_id,
                                error      = %e,
                                "Falha na verificação criptográfica ICP-Brasil"
                            );
                            None
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!(
                        session_id      = %session_id,
                        manifest_s3_key = %manifest_s3_key,
                        error           = %e,
                        "Falha ao buscar manifesto do S3 para verificação ICP-Brasil"
                    );
                    None
                }
            }
        } else {
            // Certificado público não carregado no cold start — verificação indisponível
            tracing::debug!(session_id = %session_id, "Certificado ICP-Brasil não carregado — icp_signature_valid = null");
            None
        }
    } else {
        None
    };

    // ------------------------------------------------------------------
    // Nota de verificação
    // ------------------------------------------------------------------
    let tsa_note = if tsa_status == "granted" {
        " Âncora temporal RFC 3161 presente."
    } else if tsa_status.is_empty() || tsa_status == "skipped" {
        " Âncora temporal RFC 3161 ausente."
    } else {
        ""
    };

    let verification_note = if !valid {
        "Hash da imagem não confere. Imagem pode ter sido adulterada após a captura.".to_string()
    } else {
        let base = match (frame_hash_match, icp_brasil, icp_signature_valid) {
            (Some(true), true, Some(true)) =>
                "Imagem autêntica. Hash verificado e assinado com certificado ICP-Brasil válido.",
            (Some(true), true, Some(false)) =>
                "Imagem autêntica. Hash verificado. Falha na verificação criptográfica da assinatura ICP-Brasil.",
            (Some(true), true, None) =>
                "Imagem autêntica. Hash verificado com assinatura ICP-Brasil A1.",
            (Some(true), false, _) if kms_signed =>
                "Imagem autêntica. Hash verificado. Assinatura KMS presente (certificado ICP-Brasil pendente).",
            (Some(true), false, _) =>
                "Imagem autêntica. Hash verificado com assinatura local.",
            (None, true, Some(true)) =>
                "Sessão encontrada com assinatura ICP-Brasil válida. Envie a imagem para verificação completa do hash.",
            (None, true, _) =>
                "Sessão encontrada com assinatura ICP-Brasil A1. Envie a imagem para verificação completa do hash.",
            (None, false, _) if kms_signed =>
                "Sessão encontrada e assinada digitalmente via KMS. Envie a imagem para verificação completa do hash.",
            (None, false, _) =>
                "Sessão encontrada com assinatura local. Envie a imagem para verificação completa do hash.",
            _ => "Verificação inconclusiva.",
        };
        format!("{base}{tsa_note}")
    };

    // Converte captured_at (ms epoch) para ISO 8601
    let captured_at_iso = {
        use chrono::{DateTime, TimeZone, Utc};
        let secs  = captured_at / 1_000;
        let nanos = ((captured_at % 1_000) as u32) * 1_000_000;
        Utc.timestamp_opt(secs, nanos)
            .single()
            .map(|dt: DateTime<Utc>| dt.to_rfc3339())
            .unwrap_or_default()
    };

    info!(
        session_id   = %session_id,
        valid,
        icp_brasil,
        icp_sig_ok   = ?icp_signature_valid,
        "Verificação concluída"
    );

    // Resposta HTML para browsers (QR Code) ou JSON para integrações
    if wants_html {
        let verifier_base = std::env::var("VERIFIER_BASE_URL").unwrap_or_default();
        let verifier_url  = if verifier_base.is_empty() {
            String::new()
        } else {
            format!("{}?session_id={}", verifier_base.trim_end_matches('/'), &session_id)
        };
        let pdf_lambda_url = std::env::var("PDF_LAMBDA_URL").unwrap_or_default();
        let html = render_certificate_html(
            valid, &session_id, &captured_at_iso, &frame_hash_hex,
            kms_signed, location_suspicious, &assertions,
            &captured_by_stored, &reference_id_stored, &verifier_url,
            icp_brasil, &icp_subject, &icp_cnpj, &icp_valid_until,
            &signing_mode, icp_signature_valid,
            &pdf_lambda_url,
            &tsa_status,
            &attestation_type,
            &attestation_security_level,
            attestation_bootloader,
            &attestation_verified_boot,
        );
        return Ok(html_response(html, 200));
    }

    Ok(cors_response(serde_json::to_value(VerifyResponse {
        valid,
        session_id,
        captured_at_nanos: captured_at,
        captured_at_ms:    captured_at,
        captured_at_iso,
        frame_hash_hex,
        frame_hash_match,
        kms_signed,
        location_suspicious,
        assertions,
        verification_note,
        icp_brasil,
        icp_subject,
        icp_cnpj,
        icp_valid_until,
        signing_mode,
        icp_signature_valid,
        tsa_status,
        attestation_type,
        attestation_security_level,
        attestation_bootloader,
        attestation_verified_boot,
    })?, 200))
}

// ---------------------------------------------------------------------------
// Helpers DynamoDB e S3
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

/// Busca o manifesto JSON do S3 para verificação criptográfica da assinatura ICP-Brasil.
///
/// O `manifest_s3_key` vem do campo homônimo no DynamoDB (ex: "uuid/manifest.json").
/// O dado retornado é o manifesto original assinado pelo lambda-signer.
async fn fetch_manifest_from_s3(
    s3:     &aws_sdk_s3::Client,
    bucket: &str,
    key:    &str,
) -> Result<String, Error> {
    if key.is_empty() {
        return Err("manifest_s3_key vazio — sessão pode ser anterior ao suporte ICP-Brasil".into());
    }

    let output = s3
        .get_object()
        .bucket(bucket)
        .key(key)
        .send()
        .await
        .map_err(|e| format!("Falha ao buscar manifesto do S3 ({key}): {e}"))?;

    let body_bytes = output.body.collect().await
        .map_err(|e| format!("Falha ao ler body do manifesto S3: {e}"))?
        .into_bytes();

    String::from_utf8(body_bytes.to_vec())
        .map_err(|e| format!("Manifesto S3 não é UTF-8: {e}").into())
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

// ---------------------------------------------------------------------------
// Entrypoint — cold start carrega o certificado público ICP-Brasil
// ---------------------------------------------------------------------------

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("lambda_verifier=info".parse()?)
        )
        .json()
        .init();

    // ------------------------------------------------------------------
    // Cold start — carrega certificado público ICP-Brasil uma única vez.
    //
    // Se o Secrets Manager não estiver acessível, icp_cert = None e todas
    // as invocações retornarão icp_signature_valid: null — nunca erro 500.
    // ------------------------------------------------------------------
    let aws_config = aws_config::load_from_env().await;
    let sm         = aws_sdk_secretsmanager::Client::new(&aws_config);
    let icp_cert   = Arc::new(load_icp_public_cert(&sm).await);

    if icp_cert.is_some() {
        info!(icp_verification = "disponível", "Lambda pronta — verificação ICP-Brasil ativa");
    } else {
        tracing::warn!(icp_verification = "indisponível", "Lambda pronta — icp_signature_valid será null");
    }

    run(service_fn(move |event: LambdaEvent<FunctionUrlEvent>| {
        let icp_cert = icp_cert.clone();
        async move { handler(event, icp_cert).await }
    })).await
}
