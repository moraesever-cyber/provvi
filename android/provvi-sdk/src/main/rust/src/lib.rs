// Camada 4 do pipeline ADR-001: criação e assinatura de manifestos C2PA via c2pa-rs.
// Compilado como .so via `cargo ndk` e carregado em runtime por C2paEngine.kt.

use c2pa::{CallbackSigner, Manifest, ManifestStore, SigningAlg};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jstring;
use serde_json::Value;

// Certificado de desenvolvimento auto-assinado embutido em tempo de compilação.
// Algoritmo: Ed25519 — não requer OpenSSL; usa ed25519_dalek, dep transitiva do c2pa-rs.
// ATENÇÃO: usar apenas em builds de desenvolvimento — nunca em produção.
// Em produção, o signing deve ocorrer no backend com chave privada ICP-Brasil.
const CERT_PEM: &[u8] = include_bytes!("test_cert.pem");
const KEY_PEM: &[u8]  = include_bytes!("test_key.pem");

/// Ponto de entrada JNI chamado por C2paEngine.createManifest() em Kotlin.
///
/// Recebe os bytes da imagem JPEG e o JSON com as asserções consolidadas das
/// camadas anteriores (hash do frame, GPS, integridade do dispositivo) e retorna
/// o manifesto C2PA assinado serializado como JSON.
///
/// Em caso de qualquer falha, retorna `{"error":"<mensagem>"}` em vez de panic,
/// garantindo que o processo Android não seja encerrado abruptamente.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn Java_br_com_provvi_c2pa_C2paEngine_createManifest<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    image_bytes: JByteArray<'local>,
    assertions_json: JString<'local>,
) -> jstring {
    // Extrai os tipos JNI dentro de uma closure para garantir que env seja liberado
    // antes de ser reutilizado em new_string() ao final da função
    let result = (|| -> Result<String, Box<dyn std::error::Error>> {
        // Copia os bytes da imagem do heap Java para o heap Rust
        let image_data = env.convert_byte_array(image_bytes)?;

        // Converte a JString para String Rust
        let json_str: String = env.get_string(&assertions_json)?.into();

        sign_with_c2pa(&image_data, &json_str)
    })();

    // Serializa o resultado ou o erro para o formato esperado pelo Kotlin
    let output = match result {
        Ok(json) => json,
        Err(e) => {
            // Escapa caracteres especiais para garantir JSON válido na mensagem de erro
            let msg = e.to_string()
                .replace('\\', "\\\\")
                .replace('"', "\\\"");
            format!(r#"{{"error":"{}"}}"#, msg)
        }
    };

    // Cria a JString de retorno — panic apenas em falha catastrófica de alocação JVM
    env.new_string(&output)
        .expect("falha crítica ao alocar JString de retorno")
        .into_raw()
}

/// Implementação pura Rust da criação e assinatura do manifesto C2PA.
///
/// Separada da função JNI para facilitar testes unitários sem overhead de JNI.
///
/// # Fluxo
/// 1. Desserializa o JSON de asserções recebido do Kotlin
/// 2. Cria um [`Manifest`] com a asserção customizada `com.provvi.capture`
/// 3. Assina via [`CallbackSigner`] + Ed25519 (sem dependência de OpenSSL)
/// 4. Embute o manifesto assinado nos bytes da imagem JPEG via `embed_from_memory`
/// 5. Lê o [`ManifestStore`] do asset assinado e retorna o JSON via Display
#[allow(deprecated)] // embed_from_memory é deprecated em favor de Builder (unstable_api)
fn sign_with_c2pa(
    image_data: &[u8],
    assertions_json: &str,
) -> Result<String, Box<dyn std::error::Error>> {
    // Desserializa o JSON de asserções consolidadas (hash, GPS, device, custom)
    let assertions: Value = serde_json::from_str(assertions_json)?;

    // Cria o manifesto identificando o gerador como Provvi SDK
    let mut manifest = Manifest::new("Provvi SDK/1.0");

    // Adiciona a asserção customizada com todas as provas da vistoria:
    // hash pré-codec do frame RAW (Camada 3), localização multi-fonte (Camada 3.5),
    // veredicto de integridade do dispositivo (Camada 2)
    manifest.add_labeled_assertion("com.provvi.capture", &assertions)?;

    // Cria o assinador Ed25519 via CallbackSigner.
    // CallbackSigner não requer OpenSSL — usa ed25519_dalek já presente como
    // dependência transitiva do c2pa-rs.
    let signer = CallbackSigner::new(
        |_ctx, data| CallbackSigner::ed25519_sign(data, KEY_PEM),
        SigningAlg::Ed25519,
        CERT_PEM,
    );

    // Embute o manifesto assinado nos bytes da imagem JPEG em memória.
    // O hash SHA-256 do frame RAW (Camada 3, ADR-001) já está nas asserções,
    // garantindo rastreabilidade até o sensor antes da compressão JPEG.
    let signed_bytes = manifest.embed_from_memory("image/jpeg", image_data, &signer)?;

    // Lê o ManifestStore da imagem assinada.
    // verify=false: não re-verifica a assinatura que acabou de ser criada.
    let store = ManifestStore::from_bytes("image/jpeg", &signed_bytes, false)?;

    // ManifestStore implementa Display retornando o manifesto serializado em JSON
    Ok(store.to_string())
}
