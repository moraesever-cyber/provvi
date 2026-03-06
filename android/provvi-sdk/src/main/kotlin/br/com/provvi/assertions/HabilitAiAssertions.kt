// ARQUIVO DE ENTREGA — não incluído no provvi-sdk.aar
// Copiar para o projeto do integrador

package br.com.provvi.assertions

import org.json.JSONObject

/**
 * Asserções específicas do HabilitAi para registro em manifesto C2PA.
 *
 * Cada captura no HabilitAi representa um evento de biometria ou odômetro
 * dentro de uma aula prática de habilitação.
 *
 * @param classId   Identificador único da aula prática.
 * @param deviceId  Identificador do dispositivo utilizado.
 * @param event     Tipo do evento biométrico ou de odômetro.
 */
class HabilitAiAssertions(
    private val classId:  String,
    private val deviceId: String,
    private val event:    HabilitAiEvent
) : ProvviAssertions {

    fun toJson(): JSONObject = JSONObject().apply {
        put("domain",    "habilitai")
        put("class_id",  classId)
        put("device_id", deviceId)
        put("event",     event.code)
        put("event_description", event.description)
    }

    fun label(): String = "com.habilitai.capture"

    // Satisfaz o contrato da interface ProvviAssertions — delega para toJson()
    override fun toMap(): Map<String, Any> = mapOf(
        "domain"            to "habilitai",
        "class_id"          to classId,
        "device_id"         to deviceId,
        "event"             to event.code,
        "event_description" to event.description
    )
}

/**
 * Eventos registráveis pelo HabilitAi.
 * Cada evento tem um código curto (armazenado no manifesto) e uma descrição PT-BR.
 */
enum class HabilitAiEvent(val code: String, val description: String) {

    /** Biometria facial do aluno no início da aula */
    STUDENT_START_BIOMETRY("student_start_biometry", "Biometria de início do aluno"),

    /** Biometria facial do instrutor no início da aula */
    INSTRUCTOR_START_BIOMETRY("instructor_start_biometry", "Biometria de início do instrutor"),

    /** Biometria facial do aluno no fim da aula */
    STUDENT_END_BIOMETRY("student_end_biometry", "Biometria de fim do aluno"),

    /** Biometria facial do instrutor no fim da aula */
    INSTRUCTOR_END_BIOMETRY("instructor_end_biometry", "Biometria de fim do instrutor"),

    /** Registro fotográfico do odômetro no início da aula */
    ODOMETER_START("odometer_start", "Odômetro início"),

    /** Registro fotográfico do odômetro no fim da aula */
    ODOMETER_END("odometer_end", "Odômetro fim");
}
