package br.com.provvi

/**
 * Tipos de erro padronizados do Provvi SDK (DT-019).
 *
 * Cada valor corresponde a uma condição específica do pipeline de captura,
 * permitindo ao integrador tomar decisões programáticas sem inspecionar mensagens.
 */
enum class ProvviErrorType {
    // --- Permissões ---
    /** Permissão de câmera ou localização negada pelo usuário. */
    PERMISSION_DENIED,

    // --- Integridade do dispositivo (Camada 2) ---
    /** basicIntegrity = false — dispositivo rooteado, emulado ou com software adulterado. Bloqueante. */
    DEVICE_COMPROMISED,
    /** Strongbox não disponível no hardware — não bloqueante; captura prossegue. */
    STRONGBOX_UNAVAILABLE,
    /** Play Integrity API inacessível — comportamento definido por IntegrityEnforcementConfig. */
    ATTESTATION_ERROR,

    // --- Câmera (Camada 1) ---
    /** Câmera física não encontrada no dispositivo. */
    CAMERA_NOT_FOUND,
    /** Câmera em uso exclusivo por outro processo. */
    CAMERA_IN_USE,
    /** Timeout: câmera não entregou frame no prazo de 10 segundos. */
    CAMERA_TIMEOUT,
    /** Erro genérico no pipeline de câmera. */
    CAMERA_ERROR,

    // --- Localização (Camada 3.5) ---
    /** Localização simulada detectada — possível fraude em andamento. Bloqueante. */
    MOCK_LOCATION_DETECTED,
    /** Nenhuma fonte de localização respondeu — não bloqueante; registrado no manifesto. */
    LOCATION_UNAVAILABLE,

    // --- Detecção de recaptura (ADR-002) ---
    /** Score de artefatos de tela acima do limiar THRESHOLD_BLOCK. Bloqueante. */
    RECAPTURE_SUSPECTED,

    // --- Assinatura C2PA (Camada 4) ---
    /** Falha na assinatura C2PA do manifesto. */
    SIGNING_FAILED,
    /** Manifesto C2PA inválido ou não parseável. */
    MANIFEST_INVALID,
    /** Falha no cálculo do hash SHA-256 do frame YUV. */
    FRAME_HASH_FAILED,

    // --- Rede e backend ---
    /** Sem conexão de rede no momento da captura (DT-015). */
    NETWORK_UNAVAILABLE,
    /** Upload ao backend falhou após retentativas. */
    BACKEND_UNAVAILABLE,
    /** Backend retornou 401 — API Key inválida ou expirada. */
    BACKEND_AUTH_FAILED,
    /** Timeout no upload ao backend. */
    BACKEND_TIMEOUT,
    /** TSA inacessível após 3 tentativas com backoff (DT-016). */
    TSA_UNAVAILABLE,

    // --- Relógio ---
    /** Deriva entre capturedAtMs e relógio atual superior a 300 s — possível manipulação. */
    CLOCK_SUSPICIOUS,

    // --- Catch-all ---
    /** Erro não classificado nos tipos acima. Consulte ProvviError.message para detalhes. */
    UNKNOWN
}

/**
 * Erro padronizado do Provvi SDK (DT-019).
 *
 * @param type      Classificação do erro — use para lógica de tratamento.
 * @param message   Descrição legível do erro em português.
 * @param exception Throwable original, quando disponível.
 */
data class ProvviError(
    val type: ProvviErrorType,
    val message: String,
    val exception: Throwable? = null
)
