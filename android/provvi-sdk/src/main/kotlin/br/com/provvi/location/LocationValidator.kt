package br.com.provvi.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// Tipos de resultado de validação de localização
// ---------------------------------------------------------------------------

/**
 * Localização consolidada a partir de múltiplas fontes do sistema.
 *
 * @param latitude          Latitude da coordenada consolidada (GPS tem prioridade).
 * @param longitude         Longitude da coordenada consolidada (GPS tem prioridade).
 * @param accuracyMeters    Precisão em metros reportada pela fonte primária (GPS).
 * @param isMockLocation    true se o GPS detectou localização simulada. Em API 31+
 *                          usa [Location.isMock]; em versões anteriores usa o campo
 *                          legado [Location.isFromMockProvider].
 * @param locationSuspicious true se a divergência entre GPS e NETWORK for superior
 *                           a 500 metros — indica possível manipulação, mas não bloqueia.
 * @param divergenceMeters  Maior distância calculada entre as fontes disponíveis.
 *                           Zero se apenas uma fonte respondeu.
 * @param sourcesUsed       Fontes de localização que responderam dentro do timeout,
 *                           ex.: ["GPS", "NETWORK"].
 */
data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val isMockLocation: Boolean,
    val locationSuspicious: Boolean,
    val divergenceMeters: Float,
    val sourcesUsed: List<String>
)

/**
 * Resultado da validação de localização multi-fonte.
 */
sealed class LocationValidationOutcome {
    // Localização obtida e aprovada — GPS respondeu e não foi detectado mock
    data class Valid(val result: LocationResult) : LocationValidationOutcome()

    // GPS respondeu mas a localização é simulada — inclui resultado para auditoria
    data class MockDetected(val result: LocationResult) : LocationValidationOutcome()

    // Nenhuma fonte de localização respondeu dentro do timeout de 5 segundos
    data object LocationUnavailable : LocationValidationOutcome()
}

// ---------------------------------------------------------------------------
// Validador principal
// ---------------------------------------------------------------------------

/**
 * Valida a localização do dispositivo consultando múltiplas fontes via [LocationManager].
 *
 * Utiliza [LocationManager] diretamente (sem Fused Location Provider) para evitar
 * dependência do Google Play Services nesta camada do SDK, conforme ADR-003.
 * Isso permite que o SDK funcione em dispositivos com GMS ausente ou desativado.
 *
 * A divergência entre fontes superiores a 500 metros é registrada como suspeita
 * no resultado, mas não impede a continuação da sessão de captura — a decisão
 * de bloqueio cabe à camada de negócio da aplicação host.
 */
class LocationValidator(private val context: Context) {

    // Timeout individual para cada fonte de localização
    // 1.5s — suficiente para NETWORK; GPS tenta mas não bloqueia em ambiente fechado
    private val sourceTimeoutMillis = 1_500L

    // Limiar de divergência entre fontes definido no ADR-001 (camada 3.5)
    private val divergenceThresholdMeters = 500f

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Executa a validação de localização consultando GPS_PROVIDER e NETWORK_PROVIDER
     * em paralelo com timeout individual de 5 segundos por fonte.
     *
     * A coordenada consolidada prioriza o GPS por ser a fonte de maior precisão e
     * mais difícil de falsificar sem ferramentas específicas.
     *
     * @return [LocationValidationOutcome] com o resultado consolidado ou indicação
     *         de indisponibilidade.
     */
    suspend fun validate(): LocationValidationOutcome {
        val manager = locationManager
            ?: return LocationValidationOutcome.LocationUnavailable

        // Tenta last known de ambas as fontes antes de aguardar atualização.
        // Isso é O(1) e resolve o caso mais comum (app já estava rodando ou
        // outra sessão usou localização nos últimos 5 minutos).
        val lastGps     = getLastKnownFresh(manager, LocationManager.GPS_PROVIDER)
        val lastNetwork = getLastKnownFresh(manager, LocationManager.NETWORK_PROVIDER)

        if (lastGps != null || lastNetwork != null) {
            return buildOutcome(lastGps, lastNetwork)
        }

        // Sem cache — aguarda nova atualização em paralelo com timeout reduzido
        val (gpsLocation, networkLocation) = coroutineScope {
            val gpsDeferred = async(Dispatchers.IO) {
                requestLocation(manager, LocationManager.GPS_PROVIDER)
            }
            val networkDeferred = async(Dispatchers.IO) {
                requestLocation(manager, LocationManager.NETWORK_PROVIDER)
            }
            Pair(gpsDeferred.await(), networkDeferred.await())
        }

        if (gpsLocation == null && networkLocation == null) {
            return LocationValidationOutcome.LocationUnavailable
        }

        return buildOutcome(gpsLocation, networkLocation)
    }

    /**
     * Constrói o [LocationValidationOutcome] a partir de duas fontes opcionais.
     * Extrai a lógica de consolidação para evitar duplicação entre o caminho de
     * cache e o caminho de espera.
     */
    private fun buildOutcome(gps: Location?, network: Location?): LocationValidationOutcome {
        val sourcesUsed = buildList {
            if (gps != null)     add("GPS")
            if (network != null) add("NETWORK")
        }
        val divergenceMeters   = calculateDivergence(gps, network)
        val locationSuspicious = divergenceMeters > divergenceThresholdMeters
        val primary            = gps ?: network!!

        val result = LocationResult(
            latitude           = primary.latitude,
            longitude          = primary.longitude,
            accuracyMeters     = primary.accuracy,
            isMockLocation     = isMockLocation(primary),
            locationSuspicious = locationSuspicious,
            divergenceMeters   = divergenceMeters,
            sourcesUsed        = sourcesUsed
        )

        return if (result.isMockLocation) {
            LocationValidationOutcome.MockDetected(result)
        } else {
            LocationValidationOutcome.Valid(result)
        }
    }

    /**
     * Retorna a última localização conhecida do provider se estiver dentro da janela
     * de frescor, ou null caso o provider esteja desativado ou o cache seja muito antigo.
     */
    @Suppress("MissingPermission")
    private fun getLastKnownFresh(manager: LocationManager, provider: String): Location? {
        if (!manager.isProviderEnabled(provider)) return null
        val last = manager.getLastKnownLocation(provider) ?: return null
        return if (isLocationFresh(last)) last else null
    }

    /**
     * Retorna um mapa com os campos de localização prontos para inclusão no manifesto C2PA.
     *
     * Coordenadas são incluídas com 6 casas decimais (~11 cm de precisão), suficiente
     * para o caso de uso de vistorias de veículos sem expor precisão desnecessária.
     *
     * @param result Resultado obtido de [LocationValidationOutcome.Valid] ou
     *               [LocationValidationOutcome.MockDetected].
     * @return Mapa compatível com o schema de asserções do manifesto C2PA.
     */
    fun toManifestAssertion(result: LocationResult): Map<String, Any> = mapOf(
        "latitude"            to "%.6f".format(result.latitude),
        "longitude"           to "%.6f".format(result.longitude),
        "accuracy_meters"     to result.accuracyMeters,
        "is_mock"             to result.isMockLocation,
        "location_suspicious" to result.locationSuspicious,
        "divergence_meters"   to result.divergenceMeters,
        "sources_used"        to result.sourcesUsed
    )

    // ---------------------------------------------------------------------------
    // Funções auxiliares privadas
    // ---------------------------------------------------------------------------

    /**
     * Solicita a última localização conhecida de um provider e, se não disponível,
     * aguarda a próxima atualização com timeout de 5 segundos.
     *
     * Usa [suspendCancellableCoroutine] para adaptar o callback [LocationListener]
     * ao modelo de coroutines. O listener é removido quando a coroutine é cancelada
     * ou concluída para evitar vazamento de recursos.
     *
     * @param manager  [LocationManager] do sistema.
     * @param provider [LocationManager.GPS_PROVIDER] ou [LocationManager.NETWORK_PROVIDER].
     * @return [Location] da fonte ou null se o provider estiver desativado ou o timeout expirar.
     */
    @Suppress("MissingPermission") // Permissões verificadas no AndroidManifest da app host
    private suspend fun requestLocation(
        manager: LocationManager,
        provider: String
    ): Location? {
        // Provider desabilitado nas configurações do dispositivo — não tenta registrar listener
        if (!manager.isProviderEnabled(provider)) return null

        // Verificação de lastKnown removida daqui — tratada em validate() via getLastKnownFresh()
        // antes de entrar no fluxo de espera, evitando duplicação.

        return withTimeoutOrNull(sourceTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // Remove o listener imediatamente após a primeira atualização
                        manager.removeUpdates(this)
                        // Retoma a coroutine apenas se ainda estiver ativa
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Necessário para compatibilidade com API < 29")
                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        // Provider foi desativado durante a espera — retoma com null via cancel
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                manager.requestSingleUpdate(provider, listener, context.mainLooper)

                // Garante remoção do listener se a coroutine for cancelada externamente
                continuation.invokeOnCancellation {
                    manager.removeUpdates(listener)
                }
            }
        }
    }

    /**
     * Verifica se uma localização foi obtida recentemente (menos de 5 minutos atrás).
     * Janela ampliada de 30s para 5min — reduz necessidade de aguardar nova atualização
     * em sessões iniciadas pouco depois de outra sessão ter usado localização.
     */
    private fun isLocationFresh(location: Location): Boolean {
        val ageMillis = System.currentTimeMillis() - location.time
        return ageMillis < 300_000L
    }

    /**
     * Detecta localização simulada (mock) de forma compatível com diferentes versões da API.
     *
     * - API 31+: usa [Location.isMock] (campo oficial, não depreciado)
     * - API < 31: usa [Location.isFromMockProvider] (campo legado, depreciado mas funcional)
     */
    private fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    /**
     * Calcula a maior distância entre as fontes de localização disponíveis.
     *
     * Usa [Location.distanceBetween] que implementa a fórmula de Vincenty sobre o
     * elipsoide WGS-84, adequada para distâncias de até centenas de quilômetros.
     *
     * @return Distância em metros entre as fontes, ou 0f se apenas uma fonte respondeu.
     */
    private fun calculateDivergence(gps: Location?, network: Location?): Float {
        if (gps == null || network == null) return 0f

        val results = FloatArray(1)
        Location.distanceBetween(
            gps.latitude, gps.longitude,
            network.latitude, network.longitude,
            results
        )
        return results[0]
    }
}
