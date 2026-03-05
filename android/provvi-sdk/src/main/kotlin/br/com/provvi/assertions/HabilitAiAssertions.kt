// ARQUIVO DE ENTREGA — não incluído no provvi-sdk.aar
// Copiar para o projeto do integrador

package br.com.provvi.assertions

/**
 * Tipos de aula para o domínio HabilitAi.
 * Indica a modalidade da atividade sendo registrada pela captura.
 */
enum class HabilitAiLessonType {
    AULA_PRATICA,  // Aula prática de direção em via pública ou pista
    AULA_TEORICA,  // Aula teórica em sala ou plataforma digital
    EXAME          // Exame de habilitação prático ou teórico
}

/**
 * Tipos de evento de registro para o domínio HabilitAi.
 * Determina o propósito da foto dentro do fluxo da autoescola.
 */
enum class HabilitAiEventType {
    INICIO_AULA,    // Foto no início da aula — registro de presença do aluno
    FIM_AULA,       // Foto no fim da aula — confirmação de conclusão
    FOTO_ODOMETRO,  // Foto do odômetro para registro de quilometragem
    FOTO_VEICULO    // Foto geral do veículo para inspeção de estado
}

/**
 * Asserções específicas para o cliente HabilitAi — autoescola digital.
 *
 * Este arquivo é entregue diretamente ao integrador HabilitAi e incluído
 * no projeto da aplicação host. Nunca é compilado dentro do `provvi-sdk.aar`.
 *
 * O SDK recebe uma instância desta classe via [br.com.provvi.ProvviCapture.capture]
 * através da interface [ProvviAssertions] — o .aar não conhece este tipo em tempo
 * de compilação, apenas o contrato da interface.
 *
 * @param instructorId          ID do instrutor responsável pela aula.
 * @param studentId             ID do aluno sendo instruído.
 * @param lessonType            Modalidade da atividade ([HabilitAiLessonType]).
 * @param vehiclePlate          Placa do veículo utilizado na aula.
 * @param odometerKm            Quilometragem registrada no odômetro. Opcional.
 * @param registrationEventType Tipo de evento de registro ([HabilitAiEventType]).
 */
data class HabilitAiAssertions(
    val instructorId:          String,
    val studentId:             String,
    val lessonType:            HabilitAiLessonType,
    val vehiclePlate:          String,
    val odometerKm:            Int?                 = null,
    val registrationEventType: HabilitAiEventType
) : ProvviAssertions {

    init {
        // Campos obrigatórios pelo schema HabilitAi — validados na construção do objeto
        // para garantir que nenhuma captura seja iniciada com identificadores inválidos
        require(instructorId.isNotBlank()) {
            "instructorId não pode ser vazio — identifique o instrutor antes de iniciar a captura"
        }
        require(studentId.isNotBlank()) {
            "studentId não pode ser vazio — identifique o aluno antes de iniciar a captura"
        }
        require(vehiclePlate.isNotBlank()) {
            "vehiclePlate não pode ser vazio — informe a placa do veículo antes de iniciar a captura"
        }
    }

    /**
     * Retorna o mapa de asserções em snake_case, omitindo campos nulos.
     *
     * As chaves seguem o padrão de nomenclatura do domínio HabilitAi para
     * rastreabilidade nos relatórios de conformidade da autoescola.
     */
    override fun toMap(): Map<String, Any> = buildMap {
        put("instructor_id",           instructorId)
        put("student_id",              studentId)
        put("lesson_type",             lessonType.name)
        put("vehicle_plate",           vehiclePlate)
        put("registration_event_type", registrationEventType.name)

        // Odômetro omitido do mapa quando não informado
        odometerKm?.let { put("odometer_km", it) }
    }
}
