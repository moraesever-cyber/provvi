package br.com.provvi.assertions

/**
 * Contrato que todas as implementações de asserções do Provvi SDK devem satisfazer.
 *
 * O SDK (`provvi-sdk.aar`) depende apenas desta interface — nunca das implementações
 * concretas. Isso permite que cada integrador forneça seu próprio modelo de dados
 * sem qualquer modificação no .aar distribuído.
 *
 * ## Modelo de extensão
 *
 * O .aar inclui [GenericAssertions] como implementação padrão, adequada para a maioria
 * dos integradores. Para domínios com schema próprio (ex.: autoescolas, seguradoras,
 * inspeção veicular), o integrador recebe um arquivo `XxxxAssertions.kt` separado —
 * nunca compilado dentro do .aar — e o inclui no projeto de sua aplicação.
 *
 * ## Uso mínimo
 *
 * ```kotlin
 * val outcome = provviCapture.capture(
 *     lifecycleOwner = this,
 *     assertions = GenericAssertions(referenceId = "apolice-12345")
 * )
 * ```
 *
 * ## Uso com implementação específica do cliente
 *
 * ```kotlin
 * val outcome = provviCapture.capture(
 *     lifecycleOwner = this,
 *     assertions = HabilitAiAssertions(
 *         instructorId = "inst-001",
 *         studentId    = "aluno-007",
 *         lessonType   = HabilitAiLessonType.AULA_PRATICA,
 *         vehiclePlate = "ABC1D23"
 *     )
 * )
 * ```
 */
interface ProvviAssertions {

    /**
     * Converte as asserções para um mapa plano de chaves e valores serializáveis.
     *
     * O mapa retornado é mesclado no manifesto C2PA como parte da asserção
     * `com.provvi.capture`. Todas as chaves devem ser strings não vazias;
     * valores devem ser tipos primitivos, [String], [Boolean], [Number] ou
     * coleções desses tipos para garantir compatibilidade com a serialização JSON.
     *
     * Campos opcionais nulos não devem ser incluídos no mapa — o chamador
     * não deve precisar filtrar nulls do resultado.
     *
     * @return Mapa pronto para mesclagem no manifesto C2PA; nunca nulo.
     */
    fun toMap(): Map<String, Any>
}
