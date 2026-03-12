package br.com.provvi

/**
 * Tipo de retorno padronizado do Provvi SDK para operações que podem falhar (DT-019).
 *
 * Substitui o padrão de exceções soltas e o sealed class [CaptureOutcome] nos
 * novos pontos de entrada da API. Os métodos legados (captureBlocking) permanecem
 * inalterados para manter compatibilidade JNI.
 *
 * Uso típico:
 * ```kotlin
 * when (val result = provviCapture.captureResult(lifecycleOwner)) {
 *     is ProvviResult.Success -> salvarSessao(result.value)
 *     is ProvviResult.Failure -> tratarErro(result.error)
 * }
 * ```
 */
sealed class ProvviResult<out T> {
    /**
     * Operação concluída com sucesso.
     * @param value Valor produzido pela operação.
     */
    data class Success<out T>(val value: T) : ProvviResult<T>()

    /**
     * Operação falhou com erro classificado.
     * @param error Detalhes do erro, incluindo tipo e mensagem.
     */
    data class Failure(val error: ProvviError) : ProvviResult<Nothing>()

    /** true se esta instância é [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** true se esta instância é [Failure]. */
    val isFailure: Boolean get() = this is Failure

    /**
     * Retorna o valor em caso de sucesso, ou null em caso de falha.
     * Útil para encadeamento com `?.let { }`.
     */
    fun getOrNull(): T? = (this as? Success)?.value

    /**
     * Retorna o valor em caso de sucesso, ou o resultado de [onFailure] em caso de falha.
     */
    inline fun getOrElse(onFailure: (ProvviError) -> @UnsafeVariance T): T =
        when (this) {
            is Success -> value
            is Failure -> onFailure(error)
        }
}
