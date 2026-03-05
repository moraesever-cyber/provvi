package br.com.provvi.assertions

/**
 * Implementação padrão de [ProvviAssertions] incluída no `provvi-sdk.aar`.
 *
 * Cobre a maioria dos casos de uso sem necessidade de schema específico:
 * o integrador identifica quem capturou, associa a um ID de referência de seu sistema
 * e inclui campos extras sem estrutura fixa via [extraFields].
 *
 * Esta é a implementação usada pelo app demo e recomendada como ponto de partida
 * para integradores que ainda não definiram um modelo de domínio próprio.
 *
 * Para domínios específicos (ex.: autoescolas, seguradoras), o integrador recebe
 * um arquivo `XxxxAssertions.kt` separado em vez de usar esta classe diretamente.
 *
 * @param capturedBy  Identificação livre de quem realizou a captura (ex.: nome do operador,
 *                    matrícula, ID de usuário no sistema do integrador). Opcional.
 * @param referenceId ID de referência no sistema do integrador associado a esta captura
 *                    (ex.: número da apólice, código da vistoria, ID do sinistro). Opcional.
 * @param notes       Observações livres sobre a captura. Opcional.
 * @param extraFields Campos adicionais sem schema fixo — use para dados específicos do
 *                    contexto que não se encaixam nos campos acima. Mapa deve conter apenas
 *                    tipos serializáveis em JSON (String, Number, Boolean ou coleções destes).
 */
data class GenericAssertions(
    val capturedBy:  String?           = null,
    val referenceId: String?           = null,
    val notes:       String?           = null,
    val extraFields: Map<String, Any>  = emptyMap()
) : ProvviAssertions {

    /**
     * Retorna apenas os campos não nulos mesclados com [extraFields].
     *
     * Campos nulos são omitidos do mapa — o manifesto C2PA não conterá
     * chaves com valores ausentes, mantendo o manifesto enxuto.
     * As chaves de [extraFields] têm precedência sobre os campos nomeados
     * em caso de colisão de chaves.
     */
    override fun toMap(): Map<String, Any> = buildMap {
        // Inclui apenas campos com valor — nulos não entram no manifesto
        capturedBy?.let  { put("captured_by",   it) }
        referenceId?.let { put("reference_id",  it) }
        notes?.let       { put("notes",         it) }

        // Campos adicionais do integrador — mesclados por último para permitir override
        putAll(extraFields)
    }
}
