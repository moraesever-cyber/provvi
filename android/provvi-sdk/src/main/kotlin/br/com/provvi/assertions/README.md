# Asserções do Provvi SDK

Este diretório define o sistema de extensão de asserções C2PA do Provvi SDK.

## Visão geral

Toda captura autenticada pelo SDK produz um **manifesto C2PA** com uma asserção
`com.provvi.capture`. Essa asserção contém os dados de rastreabilidade do
pipeline (hash do frame, localização, integridade do dispositivo) e os campos
fornecidos pelo integrador via `ProvviAssertions`.

## Interface `ProvviAssertions`

```kotlin
interface ProvviAssertions {
    fun toMap(): Map<String, Any>
}
```

O SDK chama `toMap()` internamente e mescla o resultado na asserção C2PA.
Campos opcionais nulos **não devem** aparecer no mapa — o manifesto fica enxuto.

---

## `GenericAssertions` — uso padrão

Incluída no `provvi-sdk.aar`. Cobre a maioria dos casos sem schema específico.

```kotlin
val outcome = provviCapture.capture(
    lifecycleOwner = this,
    assertions = GenericAssertions(
        capturedBy  = "João Silva",
        referenceId = "apolice-12345",
        notes       = "Vistoria de entrada"
    )
)
```

Campos suportados:

| Campo          | Tipo     | Chave no manifesto | Obrigatório |
|----------------|----------|--------------------|-------------|
| `capturedBy`   | `String` | `captured_by`      | Não         |
| `referenceId`  | `String` | `reference_id`     | Não         |
| `notes`        | `String` | `notes`            | Não         |
| `extraFields`  | `Map`    | (chaves livres)    | Não         |

Campos nulos são omitidos automaticamente. `extraFields` permite adicionar dados
específicos do contexto sem precisar criar uma implementação customizada.

---

## Implementações específicas por domínio

Para domínios com schema fixo, o integrador recebe um arquivo `XxxxAssertions.kt`
separado — **nunca compilado dentro do `.aar`** — e o inclui no projeto da aplicação.

### Modelo de entrega

```
provvi-sdk.aar          → entregue via Maven / arquivo
HabilitAiAssertions.kt  → entregue diretamente ao integrador HabilitAi
InsuranceAssertions.kt  → entregue diretamente ao integrador de seguros
```

O `.aar` conhece apenas a interface `ProvviAssertions`. Em tempo de compilação
do projeto do integrador, as implementações específicas satisfazem o contrato
sem qualquer modificação no SDK.

---

## `HabilitAiAssertions` — autoescola digital

Exemplo de implementação específica de domínio. Arquivo entregue ao integrador
**HabilitAi** para inclusão no projeto da aplicação host.

```kotlin
val outcome = provviCapture.capture(
    lifecycleOwner = this,
    assertions = HabilitAiAssertions(
        instructorId          = "inst-001",
        studentId             = "aluno-007",
        lessonType            = HabilitAiLessonType.AULA_PRATICA,
        vehiclePlate          = "ABC1D23",
        registrationEventType = HabilitAiEventType.INICIO_AULA
    )
)
```

Campos obrigatórios validados no construtor — `IllegalArgumentException` se vazios.

---

## Criando uma implementação própria

```kotlin
data class MinhaAssertions(
    val apólice: String,
    val tipoVistoria: String
) : ProvviAssertions {
    override fun toMap(): Map<String, Any> = mapOf(
        "apolice"       to apólice,
        "tipo_vistoria" to tipoVistoria
    )
}
```

Regras:
- Todas as chaves devem ser `String` não-vazia
- Valores: `String`, `Boolean`, `Number` ou coleções desses tipos
- Campos opcionais nulos **não** devem entrar no mapa
