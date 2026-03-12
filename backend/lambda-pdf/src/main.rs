use std::sync::Arc;

use aws_sdk_dynamodb::types::AttributeValue;
use lambda_runtime::{run, service_fn, Error, LambdaEvent};
use printpdf::{
    BuiltinFont, Color, IndirectFontRef, Line,
    Mm, PdfDocument, PdfDocumentReference, PdfLayerReference,
    Point, Rect, Rgb,
};
use printpdf::path::PaintMode;
use serde::{Deserialize, Serialize};
use tracing::info;

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

const MARGIN_MM: f32 = 15.0;
const PAGE_W_MM: f32 = 210.0; // A4
const PAGE_H_MM: f32 = 297.0;

// ---------------------------------------------------------------------------
// Certificado público ICP-Brasil — carregado no cold start
// ---------------------------------------------------------------------------

#[allow(dead_code)]
struct IcpPublicCert {
    certificate_pem: String,
}

#[derive(Deserialize)]
struct IcpSecretPartial {
    certificate_pem: String,
}

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
        .map_err(|e| tracing::error!(error = %e, "Falha ao carregar certificado ICP-Brasil"))
        .ok()?;

    let secret_str = match response.secret_string() {
        Some(s) => s,
        None => {
            tracing::error!("Secret ICP-Brasil não contém string JSON");
            return None;
        }
    };

    let parsed: IcpSecretPartial = serde_json::from_str(secret_str)
        .map_err(|e| tracing::error!(error = %e, "Falha ao desserializar JSON do secret ICP-Brasil"))
        .ok()?;

    info!("Certificado público ICP-Brasil carregado");
    Some(IcpPublicCert { certificate_pem: parsed.certificate_pem })
}

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct FunctionUrlEvent {
    #[allow(dead_code)]
    body:                    Option<String>,
    #[allow(dead_code)]
    #[serde(rename = "isBase64Encoded")]
    is_base64_encoded:       Option<bool>,
    headers:                 Option<std::collections::HashMap<String, String>>,
    #[serde(rename = "queryStringParameters")]
    query_string_parameters: Option<std::collections::HashMap<String, String>>,
    session_id:              Option<String>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

fn cors_json(body: serde_json::Value, status: u16) -> serde_json::Value {
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

fn pdf_response(bytes: Vec<u8>) -> serde_json::Value {
    let b64 = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &bytes);
    serde_json::json!({
        "statusCode": 200,
        "headers": {
            "Content-Type": "application/pdf",
            "Content-Disposition": "inline; filename=\"provvi-certificate.pdf\"",
            "Cache-Control": "no-store, no-cache, must-revalidate",
            "Pragma": "no-cache",
            "Access-Control-Allow-Origin": "*"
        },
        "body": b64,
        "isBase64Encoded": true
    })
}

// ---------------------------------------------------------------------------
// DynamoDB helpers
// ---------------------------------------------------------------------------

fn get_str(item: &std::collections::HashMap<String, AttributeValue>, key: &str) -> String {
    item.get(key)
        .and_then(|v| v.as_s().ok())
        .cloned()
        .unwrap_or_default()
}

fn get_num(item: &std::collections::HashMap<String, AttributeValue>, key: &str) -> i64 {
    item.get(key)
        .and_then(|v| v.as_n().ok())
        .and_then(|s| s.parse().ok())
        .unwrap_or(0)
}

fn get_bool(item: &std::collections::HashMap<String, AttributeValue>, key: &str) -> bool {
    item.get(key)
        .and_then(|v| v.as_bool().ok())
        .copied()
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Formatação
// ---------------------------------------------------------------------------

fn format_cnpj(cnpj: &str) -> String {
    if cnpj.len() == 14 {
        format!("{}.{}.{}/{}-{}",
            &cnpj[0..2], &cnpj[2..5], &cnpj[5..8],
            &cnpj[8..12], &cnpj[12..14])
    } else {
        cnpj.to_string()
    }
}

fn format_valid_until(date: &str) -> String {
    if date.len() == 10 {
        format!("{}/{}/{}", &date[8..10], &date[5..7], &date[0..4])
    } else {
        date.to_string()
    }
}

fn format_date_ms(ms: i64) -> String {
    use chrono::{FixedOffset, TimeZone, Utc};
    let brt   = FixedOffset::west_opt(3 * 3600).unwrap();
    let secs  = ms / 1_000;
    let nanos = ((ms % 1_000) as u32) * 1_000_000;
    Utc.timestamp_opt(secs, nanos)
        .single()
        .map(|dt| dt.with_timezone(&brt).format("%d/%m/%Y %H:%M BRT").to_string())
        .unwrap_or_default()
}

fn generated_at_str() -> String {
    use chrono::{Duration, Datelike, Timelike, Utc};
    let n = Utc::now() - Duration::hours(3); // UTC → BRT
    format!("{:02}/{:02}/{} {:02}:{:02} BRT", n.day(), n.month(), n.year(), n.hour(), n.minute())
}

// ---------------------------------------------------------------------------
// QR Code desenhado como retângulos preenchidos (sem crate image)
// ---------------------------------------------------------------------------

fn draw_qr(layer: &PdfLayerReference, url: &str, x_mm: f32, y_mm: f32, size_mm: f32) {
    use qrcode::QrCode;
    use qrcode::types::Color as QrColor;

    let Ok(qr) = QrCode::new(url.as_bytes()) else { return };
    let width  = qr.width();
    let module = size_mm / (width as f32);

    layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));

    for row in 0..width {
        for col in 0..width {
            if qr[(row, col)] == QrColor::Dark {
                let lx = x_mm + col as f32 * module;
                // PDF y é de baixo para cima; y_mm é o canto inferior esquerdo do QR
                let ly = y_mm + (width - 1 - row) as f32 * module;
                let rect = Rect::new(Mm(lx), Mm(ly), Mm(lx + module), Mm(ly + module))
                    .with_mode(PaintMode::Fill);
                layer.add_rect(rect);
            }
        }
    }
    layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));
}

// ---------------------------------------------------------------------------
// Dados da sessão
// ---------------------------------------------------------------------------

struct SessionData {
    session_id:          String,
    captured_at_ms:      i64,
    frame_hash_hex:      String,
    kms_signed:          bool,
    location_suspicious: bool,
    assertions:          serde_json::Value,
    captured_by:         String,
    reference_id:        String,
    icp_brasil:          bool,
    icp_subject:         String,
    icp_cnpj:            String,
    icp_valid_until:     String,
    signing_mode:        String,
    manifest_json:       Option<String>,
}

// ---------------------------------------------------------------------------
// Builder de PDF
// ---------------------------------------------------------------------------

struct PdfBuilder {
    doc:       PdfDocumentReference,
    layer:     PdfLayerReference,
    y:         f32, // posição vertical atual em mm
    font_reg:  IndirectFontRef,
    font_bold: IndirectFontRef,
    font_mono: IndirectFontRef,
}

impl PdfBuilder {
    fn new() -> Result<Self, Error> {
        let (doc, page, layer_idx) = PdfDocument::new(
            "Provvi — Certificado de Captura Autenticada",
            Mm(PAGE_W_MM),
            Mm(PAGE_H_MM),
            "Camada 1",
        );
        let layer = doc.get_page(page).get_layer(layer_idx);

        let font_reg  = doc.add_builtin_font(BuiltinFont::Helvetica)?;
        let font_bold = doc.add_builtin_font(BuiltinFont::HelveticaBold)?;
        let font_mono = doc.add_builtin_font(BuiltinFont::Courier)?;

        Ok(Self { doc, layer, y: PAGE_H_MM - MARGIN_MM, font_reg, font_bold, font_mono })
    }

    fn ensure_space(&mut self, needed: f32) {
        if self.y - needed < MARGIN_MM + 10.0 {
            let (new_page, new_layer_idx) = self.doc.add_page(
                Mm(PAGE_W_MM), Mm(PAGE_H_MM), "Camada 1"
            );
            self.layer = self.doc.get_page(new_page).get_layer(new_layer_idx);
            self.y     = PAGE_H_MM - MARGIN_MM;
        }
    }

    fn fill_rect(&self, x: f32, y_bot: f32, w: f32, h: f32, r: f32, g: f32, b: f32) {
        self.layer.set_fill_color(Color::Rgb(Rgb::new(r, g, b, None)));
        let rect = Rect::new(Mm(x), Mm(y_bot), Mm(x + w), Mm(y_bot + h))
            .with_mode(PaintMode::Fill);
        self.layer.add_rect(rect);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));
    }

    fn section_header(&mut self, label: &str) {
        self.ensure_space(12.0);
        self.fill_rect(MARGIN_MM, self.y - 5.0, PAGE_W_MM - 2.0 * MARGIN_MM, 8.5, 0.051, 0.106, 0.165);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(1.0, 1.0, 1.0, None)));
        self.layer.use_text(label, 8.5, Mm(MARGIN_MM + 2.0), Mm(self.y), &self.font_bold);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));
        self.y -= 10.0;
    }

    fn separator(&mut self) {
        self.ensure_space(6.0);
        let pts = vec![
            (Point::new(Mm(MARGIN_MM), Mm(self.y)), false),
            (Point::new(Mm(PAGE_W_MM - MARGIN_MM), Mm(self.y)), false),
        ];
        self.layer.set_outline_color(Color::Rgb(Rgb::new(0.8, 0.8, 0.8, None)));
        self.layer.set_outline_thickness(0.3);
        self.layer.add_line(Line { points: pts, is_closed: false });
        self.y -= 5.0;
    }

    fn label_value(&mut self, label: &str, value: &str) {
        self.ensure_space(11.0);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.25, 0.30, 0.40, None)));
        self.layer.use_text(label, 7.5, Mm(MARGIN_MM), Mm(self.y), &self.font_bold);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.059, 0.09, 0.165, None)));
        self.layer.use_text(value, 10.0, Mm(MARGIN_MM), Mm(self.y - 4.5), &self.font_reg);
        self.y -= 11.0;
    }

    fn label_value_mono(&mut self, label: &str, value: &str) {
        self.ensure_space(11.0);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.25, 0.30, 0.40, None)));
        self.layer.use_text(label, 7.5, Mm(MARGIN_MM), Mm(self.y), &self.font_bold);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.28, 0.33, 0.42, None)));
        self.layer.use_text(value, 8.0, Mm(MARGIN_MM), Mm(self.y - 4.5), &self.font_mono);
        self.y -= 11.0;
    }

    /// Exibe até 2 linhas de 80 chars cada
    fn label_value_wrap(&mut self, label: &str, value: &str) {
        const CHUNK: usize = 80;
        if value.len() <= CHUNK {
            self.label_value_mono(label, value);
            return;
        }
        self.ensure_space(17.0);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.25, 0.30, 0.40, None)));
        self.layer.use_text(label, 7.5, Mm(MARGIN_MM), Mm(self.y), &self.font_bold);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.28, 0.33, 0.42, None)));
        let (l1, l2) = value.split_at(CHUNK.min(value.len()));
        self.layer.use_text(l1, 8.0, Mm(MARGIN_MM), Mm(self.y - 4.5), &self.font_mono);
        self.layer.use_text(l2, 8.0, Mm(MARGIN_MM), Mm(self.y - 9.5), &self.font_mono);
        self.y -= 17.0;
    }

    fn check_item(&mut self, ok: bool, text: &str) {
        self.ensure_space(7.0);
        let (mark, r, g, b): (&str, f32, f32, f32) = if ok {
            ("OK ", 0.086, 0.502, 0.239)
        } else {
            ("!  ", 0.863, 0.149, 0.149)
        };
        self.layer.set_fill_color(Color::Rgb(Rgb::new(r, g, b, None)));
        self.layer.use_text(mark, 9.0, Mm(MARGIN_MM), Mm(self.y), &self.font_bold);
        self.layer.set_fill_color(Color::Rgb(Rgb::new(0.059, 0.09, 0.165, None)));
        self.layer.use_text(text, 10.0, Mm(MARGIN_MM + 8.0), Mm(self.y), &self.font_reg);
        self.y -= 7.0;
    }

    fn build(self) -> Result<Vec<u8>, Error> {
        let mut buf = std::io::BufWriter::new(Vec::new());
        self.doc.save(&mut buf)
            .map_err(|e| format!("Falha ao serializar PDF: {e}"))?;
        Ok(buf.into_inner()
            .map_err(|e| format!("Falha ao flush do PDF: {e}"))?)
    }
}

// ---------------------------------------------------------------------------
// Parsing do manifesto C2PA
// ---------------------------------------------------------------------------

/// Campos extraídos do manifesto C2PA armazenado no S3.
/// A estrutura real é `manifests[active_manifest].*`.
struct C2paFields {
    claim_generator:    String,
    format:             String,
    sig_alg:            String,
    sig_issuer:         String,
    cert_serial:        String,
    // assertion com.provvi.capture
    captured_by:        String,
    reference_id:       String,
    latitude:           String,
    longitude:          String,
    accuracy_meters:    String,
    authenticity_score: String,
}

impl C2paFields {
    fn parse(json: &str) -> Option<Self> {
        let root: serde_json::Value = serde_json::from_str(json).ok()?;

        // Navega manifests[active_manifest]
        let active = root.get("active_manifest")?.as_str()?;
        let manifest = root
            .get("manifests")?
            .get(active)?;

        let claim_generator = manifest
            .get("claim_generator").and_then(|v| v.as_str())
            .unwrap_or("—").to_string();
        let format = manifest
            .get("format").and_then(|v| v.as_str())
            .unwrap_or("—").to_string();

        let sig_info   = manifest.get("signature_info");
        let sig_alg    = sig_info.and_then(|s| s.get("alg")).and_then(|v| v.as_str()).unwrap_or("—").to_string();
        let sig_issuer = sig_info.and_then(|s| s.get("issuer")).and_then(|v| v.as_str()).unwrap_or("—").to_string();
        let cert_serial = sig_info.and_then(|s| s.get("cert_serial_number")).and_then(|v| v.as_str()).unwrap_or("—").to_string();

        // Assertion com.provvi.capture
        let capture_data = manifest
            .get("assertions")
            .and_then(|a| a.as_array())
            .and_then(|arr| arr.iter().find(|a| {
                a.get("label").and_then(|l| l.as_str()) == Some("com.provvi.capture")
            }))
            .and_then(|a| a.get("data"));

        let str_field = |key: &str| -> String {
            capture_data
                .and_then(|d| d.get(key))
                .and_then(|v| v.as_str())
                .unwrap_or("—")
                .to_string()
        };

        let captured_by   = str_field("captured_by");
        let reference_id  = str_field("reference_id");
        let latitude      = str_field("latitude");
        let longitude     = str_field("longitude");
        let accuracy_meters = capture_data
            .and_then(|d| d.get("accuracy_meters"))
            .and_then(|v| v.as_f64())
            .map(|v| format!("{:.0}m", v))
            .unwrap_or_else(|| "—".to_string());
        let authenticity_score = capture_data
            .and_then(|d| d.get("authenticity_score"))
            .and_then(|v| v.as_f64())
            .map(|s| format!("{:.1}%", s * 100.0))
            .unwrap_or_else(|| "—".to_string());

        Some(Self {
            claim_generator, format,
            sig_alg, sig_issuer, cert_serial,
            captured_by, reference_id,
            latitude, longitude, accuracy_meters, authenticity_score,
        })
    }
}

// ---------------------------------------------------------------------------
// Geração do PDF
// ---------------------------------------------------------------------------

fn generate_pdf(data: &SessionData, verifier_url: &str) -> Result<Vec<u8>, Error> {
    let mut p = PdfBuilder::new()?;

    // Extrai campos do manifesto C2PA (S3) uma única vez
    let c2pa = data.manifest_json.as_deref().and_then(C2paFields::parse);

    // Precedência DynamoDB → manifesto S3 para captured_by / reference_id
    let captured_by = if !data.captured_by.is_empty() {
        data.captured_by.as_str()
    } else {
        c2pa.as_ref().map(|c| c.captured_by.as_str()).unwrap_or("—")
    };
    let reference_id = if !data.reference_id.is_empty() {
        data.reference_id.as_str()
    } else {
        c2pa.as_ref().map(|c| c.reference_id.as_str()).unwrap_or("—")
    };

    // -----------------------------------------------------------------------
    // 1. Cabeçalho
    // -----------------------------------------------------------------------
    p.fill_rect(0.0, PAGE_H_MM - 28.0, PAGE_W_MM, 28.0, 0.051, 0.106, 0.165);
    p.layer.set_fill_color(Color::Rgb(Rgb::new(1.0, 1.0, 1.0, None)));
    p.layer.use_text("provvi", 22.0, Mm(MARGIN_MM), Mm(PAGE_H_MM - 13.0), &p.font_bold.clone());
    p.layer.set_fill_color(Color::Rgb(Rgb::new(0.58, 0.62, 0.72, None)));
    p.layer.use_text("Certificado de Captura Autenticada", 9.0, Mm(MARGIN_MM), Mm(PAGE_H_MM - 21.0), &p.font_reg.clone());
    p.layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));
    p.y = PAGE_H_MM - 31.0;

    // -----------------------------------------------------------------------
    // 2. Identificação
    // -----------------------------------------------------------------------
    p.section_header("IDENTIFICACAO DA VISTORIA");
    p.label_value("Vistoriador", captured_by);
    p.label_value("Referencia", reference_id);
    p.label_value("Data e Hora da Captura", &format_date_ms(data.captured_at_ms));
    p.label_value_mono("Session ID", &data.session_id);

    // -----------------------------------------------------------------------
    // 2b. Localização GPS (do manifesto C2PA)
    // -----------------------------------------------------------------------
    if let Some(ref c) = c2pa {
        if c.latitude != "—" || c.longitude != "—" {
            p.separator();
            p.section_header("LOCALIZACAO DA CAPTURA");
            let coords = format!(
                "Lat: {}  ·  Lon: {}  ·  Precisao: {}",
                c.latitude, c.longitude, c.accuracy_meters
            );
            p.label_value("Coordenadas", &coords);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Resultado de verificação
    // -----------------------------------------------------------------------
    p.separator();
    p.section_header("RESULTADO DA VERIFICACAO");

    let integrity_risk    = data.assertions.get("integrity_risk").and_then(|v| v.as_str()).unwrap_or("NONE");
    let clock_suspicious  = data.assertions.get("clock_suspicious").and_then(|v| v.as_bool()).unwrap_or(false);
    let recapture_verdict = data.assertions.get("recapture_verdict").and_then(|v| v.as_str()).unwrap_or("clean");

    p.check_item(!clock_suspicious,  &format!("Relogio do dispositivo — {}", if clock_suspicious { "Suspeito" } else { "Verificado" }));
    p.check_item(!data.location_suspicious, &format!("Localizacao GPS — {}", if data.location_suspicious { "Suspeita" } else { "Verificada" }));
    p.check_item(recapture_verdict == "clean", &format!("Deteccao de recaptura — {}", if recapture_verdict == "clean" { "Nao detectada" } else { "Suspeita" }));
    p.check_item(data.kms_signed || data.icp_brasil, &format!("Assinatura do servidor — {}", if data.icp_brasil { "ICP-Brasil A1" } else if data.kms_signed { "KMS" } else { "Local" }));

    let risk_label = match integrity_risk {
        "MEDIUM" => "Risco Medio",
        "HIGH"   => "Risco Alto",
        _        => "Nenhum Risco",
    };
    p.check_item(integrity_risk == "NONE", &format!("Risco geral — {risk_label}"));

    // Score de autenticidade (do manifesto C2PA)
    if let Some(ref c) = c2pa {
        if c.authenticity_score != "—" {
            p.label_value("Score de autenticidade", &c.authenticity_score);
        }
    }

    // -----------------------------------------------------------------------
    // 4. Assinatura Digital
    // -----------------------------------------------------------------------
    p.separator();
    p.section_header("ASSINATURA DIGITAL");

    let mode_label = match data.signing_mode.as_str() {
        "icp_brasil_a1" => "ICP-Brasil A1 (sha256WithRSAEncryption)",
        "kms_dev"       => "AWS KMS (desenvolvimento)",
        _               => "Assinatura local",
    };
    p.label_value("Modo de Assinatura", mode_label);

    if data.icp_brasil && !data.icp_subject.is_empty() {
        p.label_value("Titular do Certificado", &data.icp_subject);
        p.label_value("CNPJ", &format_cnpj(&data.icp_cnpj));
        p.label_value("Valido ate", &format_valid_until(&data.icp_valid_until));
    }

    // -----------------------------------------------------------------------
    // 5. Hash SHA-256
    // -----------------------------------------------------------------------
    p.separator();
    p.section_header("HASH SHA-256 DO FRAME (PRE-CODEC)");

    let hash = &data.frame_hash_hex;
    if hash.len() == 64 {
        p.label_value_mono("Primeiros 32 chars", &hash[..32]);
        p.label_value_mono("Ultimos 32 chars",   &hash[32..]);
    } else {
        p.label_value_wrap("SHA-256", hash);
    }

    // -----------------------------------------------------------------------
    // 6. Resumo do manifesto C2PA
    // -----------------------------------------------------------------------
    if let Some(ref c) = c2pa {
        p.separator();
        p.section_header("MANIFESTO C2PA (RESUMO)");
        p.label_value("Gerador", &c.claim_generator);
        p.label_value("Formato", &c.format);
        p.label_value("Algoritmo de Assinatura", &c.sig_alg);
        p.label_value("Emissor do Certificado", &c.sig_issuer);
        // Trunca serial em 32 chars para caber na linha
        let serial_display = if c.cert_serial.len() > 32 {
            format!("{}...", &c.cert_serial[..32])
        } else {
            c.cert_serial.clone()
        };
        p.label_value_mono("Serial do Certificado", &serial_display);
    } else if data.manifest_json.is_some() {
        p.separator();
        p.section_header("MANIFESTO C2PA (RESUMO)");
        p.label_value("Aviso", "Estrutura do manifesto nao reconhecida");
    }

    // -----------------------------------------------------------------------
    // Rodapé — QR Code + URL + linha de geração
    // -----------------------------------------------------------------------
    p.separator();

    if !verifier_url.is_empty() {
        p.ensure_space(38.0);
        let qr_bottom = p.y - 30.0;
        let qr_x      = MARGIN_MM;
        let qr_size   = 28.0;

        // Fundo branco para o QR
        p.fill_rect(qr_x - 1.0, qr_bottom - 1.0, qr_size + 2.0, qr_size + 2.0, 1.0, 1.0, 1.0);
        draw_qr(&p.layer, verifier_url, qr_x, qr_bottom, qr_size);

        // Texto ao lado do QR
        p.layer.set_fill_color(Color::Rgb(Rgb::new(0.25, 0.30, 0.40, None)));
        p.layer.use_text("Verificar online:", 8.0, Mm(qr_x + qr_size + 4.0), Mm(p.y - 6.0), &p.font_bold.clone());
        p.layer.set_fill_color(Color::Rgb(Rgb::new(0.28, 0.33, 0.42, None)));
        let url_display = if verifier_url.len() > 68 {
            format!("{}...", &verifier_url[..65])
        } else {
            verifier_url.to_string()
        };
        p.layer.use_text(&url_display, 7.0, Mm(qr_x + qr_size + 4.0), Mm(p.y - 12.0), &p.font_mono.clone());
        p.y = qr_bottom - 4.0;
    }

    p.ensure_space(8.0);
    p.layer.set_fill_color(Color::Rgb(Rgb::new(0.58, 0.62, 0.72, None)));
    p.layer.use_text(
        &format!("Provvi SDK · C2PA · ICP-Brasil · {}", generated_at_str()),
        7.5, Mm(MARGIN_MM), Mm(p.y),
        &p.font_reg.clone(),
    );
    p.layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));

    p.build()
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

async fn handler(
    event:     LambdaEvent<FunctionUrlEvent>,
    _icp_cert: Arc<Option<IcpPublicCert>>,
) -> Result<serde_json::Value, Error> {

    // Preflight CORS
    if let Some(ref headers) = event.payload.headers {
        let method = headers.get(":method")
            .or_else(|| headers.get("x-http-method"))
            .map(|s| s.as_str());
        if method == Some("OPTIONS") {
            return Ok(cors_json(serde_json::json!({}), 200));
        }
    }

    let session_id = if let Some(ref qs) = event.payload.query_string_parameters {
        qs.get("session_id").cloned()
    } else {
        event.payload.session_id
    };

    let Some(session_id) = session_id else {
        return Ok(cors_json(serde_json::to_value(ErrorResponse {
            error: "Forneça session_id como query parameter.".to_string(),
        })?, 400));
    };

    let config   = aws_config::load_from_env().await;
    let dynamodb = aws_sdk_dynamodb::Client::new(&config);
    let s3       = aws_sdk_s3::Client::new(&config);
    let table    = std::env::var("TABLE_NAME")
        .unwrap_or_else(|_| "provvi-sessions".to_string());
    let bucket   = std::env::var("S3_BUCKET")
        .unwrap_or_else(|_| "provvi-manifests-dev".to_string());
    let verifier_base = std::env::var("VERIFIER_BASE_URL").unwrap_or_default();

    // Busca sessão no DynamoDB
    let result = dynamodb
        .query()
        .table_name(&table)
        .key_condition_expression("session_id = :sid")
        .expression_attribute_values(":sid", AttributeValue::S(session_id.clone()))
        .limit(1)
        .send()
        .await
        .map_err(|e| format!("Erro DynamoDB: {e}"))?;

    let Some(item) = result.items().first().cloned() else {
        return Ok(cors_json(serde_json::to_value(ErrorResponse {
            error: format!("Sessão não encontrada: {session_id}"),
        })?, 404));
    };

    let captured_at_ms      = get_num(&item, "captured_at");
    let frame_hash_hex      = get_str(&item, "frame_hash");
    let kms_signed          = get_bool(&item, "kms_signed");
    let location_suspicious = get_bool(&item, "location_suspicious");
    let captured_by         = get_str(&item, "captured_by");
    let reference_id        = get_str(&item, "reference_id");
    let assertions_str      = get_str(&item, "assertions");
    let assertions: serde_json::Value = serde_json::from_str(&assertions_str)
        .unwrap_or(serde_json::Value::Null);
    let icp_brasil     = get_bool(&item, "icp_brasil");
    let icp_subject    = get_str(&item, "icp_subject");
    let icp_cnpj       = get_str(&item, "icp_cnpj");
    let icp_valid_until = get_str(&item, "icp_valid_until");
    let signing_mode   = get_str(&item, "signing_mode");
    let manifest_s3_key = get_str(&item, "manifest_s3_key");

    // Busca manifesto C2PA do S3 (opcional)
    let manifest_json: Option<String> = if !manifest_s3_key.is_empty() {
        match s3.get_object().bucket(&bucket).key(&manifest_s3_key).send().await {
            Ok(output) => {
                output.body.collect().await.ok()
                    .and_then(|d| String::from_utf8(d.into_bytes().to_vec()).ok())
            }
            Err(e) => {
                tracing::warn!(error = %e, key = %manifest_s3_key, "Falha ao buscar manifesto S3");
                None
            }
        }
    } else {
        None
    };

    let verifier_url = if verifier_base.is_empty() {
        String::new()
    } else {
        format!("{}?session_id={}", verifier_base.trim_end_matches('/'), &session_id)
    };

    let data = SessionData {
        session_id: session_id.clone(),
        captured_at_ms,
        frame_hash_hex,
        kms_signed,
        location_suspicious,
        assertions,
        captured_by,
        reference_id,
        icp_brasil,
        icp_subject,
        icp_cnpj,
        icp_valid_until,
        signing_mode,
        manifest_json,
    };

    info!(session_id = %session_id, "Gerando PDF de custodia");

    let pdf_bytes = generate_pdf(&data, &verifier_url)?;
    Ok(pdf_response(pdf_bytes))
}

// ---------------------------------------------------------------------------
// Entrypoint
// ---------------------------------------------------------------------------

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("lambda_pdf=info".parse()?)
        )
        .json()
        .init();

    let aws_config = aws_config::load_from_env().await;
    let sm         = aws_sdk_secretsmanager::Client::new(&aws_config);
    let icp_cert   = Arc::new(load_icp_public_cert(&sm).await);

    info!("Lambda PDF pronta");

    run(service_fn(move |event: LambdaEvent<FunctionUrlEvent>| {
        let icp_cert = icp_cert.clone();
        async move { handler(event, icp_cert).await }
    })).await
}
