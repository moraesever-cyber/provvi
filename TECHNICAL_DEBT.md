# Provvi SDK — Débitos Técnicos

Registro formal de decisões técnicas temporárias e melhorias pendentes.
Formato: [prioridade] [status] descrição + contexto + solução proposta.

---

## DT-001 — Instrumentação de Performance do Pipeline
**Prioridade:** Média | **Status:** Resolvido | **Registrado:** 2026-03-06

**Problema:**
O pipeline de captura executa 5 camadas em sequência sem telemetria de tempo por camada.
Tempo total percebido pelo usuário é alto mas não há dados sobre qual camada é o gargalo.

**Sintoma observado:**
Tempo de resposta elevado no app demo em testes de 06/03/2026.

**Impacto:**
Sem dados, não é possível otimizar com precisão.
Meta do roadmap: latência < 800ms P95 (backend) — pipeline local pode consumir 2–5s antes do backend.

**Solução proposta:**
- Adicionar `System.nanoTime()` antes e depois de cada camada em `ProvviCapture.capture()`
- Acumular em `CaptureSession.pipelineTimingsMs: Map<String, Long>?`
- Logar no manifesto em modo debug
- Implementar antes dos testes de ataque (otimizações precisam de baseline)

**Resolução (2026-03-06):**
Pipeline otimizado de 12s → 3.5s warm (-71%).
Integrity + GPS paralelizados. RecaptureDetector e yuvToJpeg
movidos para Dispatchers.Default. GPS interno paralelizado.
CameraCapture: executor recriado entre capturas.
Baseline: cold start ~7s, warm ~3.5s, GPS dominante ~3s.

---

## DT-002 — Play Integrity: Migração Classic → Standard + cloudProjectNumber
**Prioridade:** Alta | **Status:** Implementado, aguardando configuração | **Registrado:** 2026-03-06 | **Revisado:** 2026-03-12

**Problema original:**
Play Integrity usando a Classic API sem `cloudProjectNumber` — resultado "Indisponível"
e latência de ~5s por captura.

**Implementado em 2026-03-11:**
- `DeviceIntegrityChecker.kt`: `warmUp(cloudProjectNumber: Long)` com guard `<= 0L`
- `BackendConfig`: campo `cloudProjectNumber` com `PROVVI_DEFAULT_CLOUD_PROJECT = 0L`
- `ProvviCapture.kt`: warmup assíncrono disparado no `init {}`
- Com `cloudProjectNumber = 0L`, warmup é ignorado silenciosamente — sem regressão

**Pendente (bloqueio externo):**
Substituir `0L` pelo número real do projeto Google Cloud após:
1. Abertura de conta Provvi na Play Store (aguardando alteração de razão social)
2. Publicação do app em internal testing

Quando configurado: latência ~5000ms → ~150ms, card "❌ Indisponível" → "✅ Verificado"

**Entendimento revisado em 2026-03-12 — campos são independentes:**

O veredicto do Play Integrity é composto por três campos com origens e comportamentos
distintos. A implementação atual trata o resultado como um bloco único — isso precisa
ser corrigido (ver DT-014).

| Campo | Via Play Store | Via MDM/sideload | Âncora |
|---|---|---|---|
| `deviceIntegrity` (`MEETS_DEVICE_INTEGRITY`) | ✅ Funciona | ✅ Funciona | Hardware (TEE/StrongBox) |
| `appLicensingVerdict` (`LICENSED`) | ✅ Funciona | ❌ `UNLICENSED` (estrutural) | Play Store account |
| `appRecognitionVerdict` (`PLAY_RECOGNIZED`) | ✅ Funciona | `UNEVALUATED` | Play Store binary |

**Implicação para distribuição MDM (HabilitAi):**
`deviceIntegrity: MEETS_DEVICE_INTEGRITY` funciona normalmente via MDM — é ancorado
em hardware e independente de Play Store. Prova que o aparelho é um Android genuíno,
não rooteado, não comprometido. Este é o sinal com maior valor para o caso de uso
de vistoria/laudo.

`appLicensingVerdict: UNLICENSED` em MDM é estrutural e esperado — não indica fraude,
indica apenas que o app não veio do Play Store. Deve ser tratado como `IGNORE` neste
contexto de distribuição (ver DT-014).

**O que mudar no `DeviceIntegrityChecker.kt`:**
- Ler e registrar os três campos separadamente no resultado
- Não agregar em score único — expor cada campo individualmente
- Passar enforcement por campo via `BackendConfig` (ver DT-014)

**Campo no manifesto C2PA (estrutura alvo):**
```json
"device_integrity": {
  "meets_device_integrity": true,
  "app_licensing": "unlicensed",
  "app_recognition": "unevaluated",
  "distribution": "mdm",
  "enforced_fields": ["device_integrity"]
}
```

Isso torna o manifesto auditável: seguradora ou perito vê exatamente o que foi
verificado e o que foi desconsiderado por contexto de distribuição.

**Decisões de design mantidas:**
- `cloudProjectNumber` default Provvi simplifica onboarding; sobrescrevível por integrador
- Política de enforcement por campo: `BackendConfig.integrityEnforcement` — ver DT-014

**Pendência de configuração (cloudProjectNumber):**
Substituir `PROVVI_DEFAULT_CLOUD_PROJECT = 0L` pelo número real do projeto Google Cloud após:
1. Abertura de conta Provvi na Play Store (aguardando alteração de razão social)
2. Publicação do app em internal testing

Impacto quando configurado:
- Latência: ~5000ms → ~150ms por captura
- `meetsStrongIntegrity` / `meetsDeviceIntegrity` passam a ter veredicto real
  (hoje retornam `false` por padrão quando API indisponível)
- `integrityRisk` calculado por DT-014 passa a ser plenamente informativo

## DT-003 — Certificados C2PA de Desenvolvimento
**Prioridade:** Alta | **Status:** Resolvidp | **Resolvido em:** 2026-03-11

**Decisão:**
Usar certificado ICP-Brasil emitido para o CNPJ da ME
(EVERALDO ARISTOTELES DE MORAES ME) como solução de curto prazo.
Nome fantasia não consta no certificado — razão social aparece no carimbo.
Aceitável para fase de demonstração: o que importa é kms_signed: true.

**Plano:**
- Curto prazo: emitir certificado PJ para assinatura digital no CNPJ da ME
- Médio prazo: constituir CNPJ da Provvi e re-emitir certificado em nome próprio
- Alternativa técnica: DigiCert internacional (já na CAI Trust List) se
  necessário resolver signingCredential.trusted antes da Provvi ter CNPJ

**Resolução:**
- Certificado e-CNPJ A1 ICP-Brasil emitido pela Certisign para EVERALDO ARISTOTELES DE MORAES (CNPJ 26988458000194). Armazenado no AWS Secrets Manager (provvi/icp-brasil/a1-cert, sa-east-1). Lambda-signer atualizado para assinar com sha256WithRSAEncryption via AC Certisign RFB G5. signing_mode: icp_brasil_a1 ativo. Válido até 2027-03-11.

---

## DT-004 — Thresholds de Detecção de Recaptura sem Calibração
**Prioridade:** Média | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
Thresholds do `RecaptureDetector` (MOIRE=0.5, SPECULAR=0.4, CHROMATIC=0.5, SUSPICIOUS=0.6)
são estimativas iniciais sem validação com dataset real.

**Impacto:**
- Falsos negativos em telas OLED/4K (Moiré menos pronunciado)
- Falsos positivos em ambientes com iluminação artificial intensa
- Meta ADR-002: detecção ≥ 90%, falso positivo ≤ 2% — não validada

**Solução proposta:**
- Coletar dataset de 200+ capturas (100 reais, 100 de telas)
- Executar análise de curva ROC para otimizar thresholds
- Testar especificamente telas AMOLED (padrão de subpixels diferente de LCD)

---

## DT-005 — Backend sem Autenticação
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
Lambda exposta sem autenticação — qualquer requisição HTTP pode armazenar sessões
e consumir S3/DynamoDB.

**Impacto:**
Em produção, expõe a conta AWS a abuso e custos indevidos.

**Solução proposta:**
- Adicionar API Key via AWS API Gateway
- Ou Lambda Function URL com IAM auth
- SDK passa a chave no header `x-api-key` configurada via `BackendConfig`
- Implementar antes de qualquer teste com dados reais

## DT-006 — Fluxo Offline-First não implementado
**Prioridade:** Baixa (adiado) | **Status:** Adiado — roadmap médio prazo | **Registrado:** 2026-03-06 | **Revisado:** 2026-03-12

**Problema:**
O diferencial competitivo "captura segura offline" do roadmap (item 1.3) está
parcialmente implementado. A assinatura local C2PA funciona sem rede, mas não há
armazenamento local da sessão quando o upload falha por falta de conectividade.

**Decisão de 2026-03-12 — Adiado:**
O offline-first foi removido do escopo atual após identificação de vulnerabilidade
de timestamp: o relógio do dispositivo Android é manipulável sem root, o que permite
fraude por data retroativa. Sem âncora temporal externa obtida no momento da captura,
o manifesto offline não tem garantia jurídica de temporalidade.

A solução técnica correta requer PKI de dispositivo (certificado de longa validade
por aparelho, modelo Truepic/Keyfactor): o dispositivo receberia um certificado
pré-instalado com validade de meses, eliminando a dependência de rede na captura
e resolvendo o problema de timestamp simultaneamente. Ponto de pauta para reunião
técnica com a Certisign.

**O que já funciona offline (mantido como código, não como fluxo ativo):**
- Captura do frame
- Hash SHA-256 pré-codec
- Validação GPS local
- Assinatura Ed25519 local

**O que falta para reativar:**
- PKI de dispositivo (certificado por aparelho, longa validade)
- Fila local de sessões pendentes (Room ou SharedPreferences)
- Retry automático quando conectividade é restaurada (WorkManager) — ver DT-011
- Re-assinatura com timestamp ICP-Brasil no backend ao sincronizar

**Bloqueio:**
Infraestrutura PKI de dispositivo (Keyfactor/EJBCA ou equivalente Certisign).
Não iniciar antes de definir parceria e modelo comercial.

---
TODO now
## DT-007 — GPS Cold Start (~3s) em Primeira Captura
**Prioridade:** Baixa | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
GPS domina o pipeline em ~3s — é o timeout mínimo do requestSingleUpdate
quando não há fix recente em cache.

**Impacto:**
Cold start ~7s. Warm start ~3.5s. Em uso real o app cliente
tipicamente já tem GPS aquecido antes de chamar o SDK.

**Solução proposta:**
- Pré-aquecer GPS no app cliente antes de chamar capture()
- Documentar na guia de integração: "inicialize o LocationManager
  antes de exibir a tela de captura"
- Avaliar getLastKnownLocation com janela de 60s (hoje são 30s)

## DT-008 — Autenticação por Cliente e Controle de Licença
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
A autenticação atual usa uma única API Key compartilhada — adequada para
fase inicial com um único integrador, mas insuficiente para múltiplos clientes.

**Dois problemas distintos:**

**8a — Autenticação por cliente:**
- Uma API Key por integrador (HabilitAi, Autovist, etc.)
- Rastreabilidade de uso por cliente no backend
- Rate limiting individual via AWS API Gateway
- Rotação de chaves sem coordenação entre clientes

**8b — Controle de licença do SDK:**
- Cada integrador recebe uma licença com validade, volume de capturas e ambiente permitido
- SDK valida licença online no momento da captura (ou periodicamente)
- Backend rejeita capturas de licenças expiradas, suspensas ou acima do limite
- Painel de gestão de licenças para o Provvi controlar contratos

**Impacto sem implementação:**
- Sem 8a: impossível ter múltiplos clientes com controle individual
- Sem 8b: qualquer um com o .aar pode usar o SDK sem contrato

**Solução proposta:**
- 8a: AWS API Gateway + Usage Plans + uma API Key por cliente
- 8b: `ProvviLicense` — token JWT assinado com validade, clientId, volumeLimit
  e ambientes permitidos. SDK valida JWT localmente (chave pública embutida)
  e backend valida no momento do armazenamento.

**Dependências:**
- DT-005 resolvido parcialmente (API Key única implementada)
- Requer definição comercial: modelo de cobrança por captura ou por período?

**Bloqueio:**
Decisão comercial sobre modelo de licenciamento antes da implementação técnica.

## DT-009 — Serviço de Verificação de Autenticidade
**Prioridade:** Alta | **Status:** Resolvido parcialmente | **Registrado:** 2026-03-06

**Implementado:**
- lambda-verifier operacional com Function URL pública
- Verificação por session_id (busca direta DynamoDB)
- Verificação por imagem (SHA-256 → GSI frame-hash-index)
- Verificação combinada (session_id + imagem → confirmação completa)
- verification_note com mensagem contextual para o usuário
- kms_signed, frame_hash_match, assertions, location_suspicious no response

**Function URL:** https://ldp4x25jnk2mrppz75ts2kiq7a0bylxw.lambda-url.sa-east-1.on.aws/

**Pendente (bloqueado por DT-003):**
- Relatório PDF exportável com cadeia de custódia
- Verificação da assinatura KMS (hoje confirma existência, não verifica criptograficamente)
- Após cert ICP-Brasil: verificar assinatura contra chave pública do certificado

## DT-010 — Validação do Wrapper MAUI em Dispositivo Real
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
O wrapper MAUI foi escrito e revisado mas ainda não foi compilado nem testado
em dispositivo físico. A invocação JNI é feita manualmente — erros de descritor,
nome de método ou assinatura só aparecem em runtime.

**Riscos específicos:**
- Descritor JNI de `captureBlocking()` pode divergir do bytecode Kotlin gerado
- `ProcessLifecycleOwner.get()` pode não estar disponível no contexto MAUI
- `HasIntegrityToken` não está sendo extraído em `ExtractSuccess()` — campo ausente
- `PipelineTimingsMs` não está sendo extraído — campo ausente

**Plano de validação:**
1. Criar app MAUI mínimo de teste (`maui-test-app/`) com uma tela e um botão
2. Referenciar `provvi-maui.csproj`
3. Chamar `CaptureHabilitAiAsync()` com evento `STUDENT_START_BIOMETRY`
4. Verificar que retorna `ProvviCaptureResult` com `SessionId` preenchido
5. Verificar no DynamoDB que a sessão foi registrada com asserções HabilitAi

**Bloqueio:**
Requer ambiente .NET MAUI Android configurado com workload Android instalado.
O dev do HabilitAi pode executar este plano com o ambiente dele.

**Campos pendentes em `ExtractSuccess()`:**
- `HasIntegrityToken` — getter: `getHasIntegrityToken()`, tipo: `bool`
- `PipelineTimingsMs` — getter: `getPipelineTimingsMs()`, tipo: `Map<String, Long>?`

## DT-011 — Upload Assíncrono (Background Sync)
**Prioridade:** Baixa (adiado) | **Status:** Adiado — depende de DT-006 | **Registrado:** 2026-03-06 | **Revisado:** 2026-03-12

**Decisão:**
Upload para o backend deve ser assíncrono — não bloquear a UI após a captura local completar.

**Adiado em 2026-03-12:**
Com a remoção do offline-first (DT-006), o upload assíncrono perde seu caso de uso
primário. A captura atual exige conexão ativa — o upload ocorre em sequência imediata
à captura, e a diferença de tempo é de segundos (não bloqueia o operador de forma
perceptível). Pode ser revisitado para otimização de UX em conexões lentas, sem
implicações de segurança.

**Fluxo alvo (quando reativado junto com DT-006):**
1. SDK captura + hash + assina localmente → retorna para o app (~3.5s)
2. App libera UI imediatamente
3. Upload acontece em background via WorkManager
4. Notificação silenciosa quando sincronizado

**Dependências:**
- DT-006 (offline-first) — implementar juntos quando PKI de dispositivo estiver disponível

---

## DT-012 — Migração Assinatura C2PA para Backend (KMS)
**Status:** ✅ Resolvido | **Resolvido em:** 2026-03-06

**Decisão arquitetural:**
Manter assinatura local com cert dev como fallback de integridade.
Assinatura com validade jurídica plena migra para backend via AWS KMS.

**Fluxo alvo:**
- Dispositivo: captura → hash → manifesto → assina com cert dev (fallback)
- Backend: recebe manifesto → re-assina com cert ICP-Brasil via KMS
- Manifesto final: duas assinaturas — local (integridade) + backend (jurídica)

**O que muda na Lambda:**
- Recebe manifesto com assinatura local
- Adiciona segunda assinatura via AWS KMS
- Armazena manifesto duplamente assinado no S3

**Bloqueio atual:**
Certificado ICP-Brasil pendente — CNPJ ME sendo atualizado para nome
fantasia Provvi. Implementar estrutura KMS agora, plugar certificado depois.

**Meta de produto:**
Site com validador de integridade público — visitante faz upload de imagem
capturada pelo Provvi e recebe confirmação de autenticidade com cadeia de custódia.

## DT-013 — Remover Assinatura Local como Padrão (pós cert ICP-Brasil)
**Prioridade:** Média | **Status:** Resolvido | **Resolvido em:** 2026-03-11

**Contexto:**
Hoje o SDK Android assina o manifesto com cert dev Ed25519 sempre — online e offline.
A assinatura KMS no backend é uma segunda assinatura independente.
Essa arquitetura é correta para a fase atual (cert dev + KMS sem cert ICP-Brasil).

**Decisão pendente:**
Quando o certificado ICP-Brasil estiver configurado no KMS, alterar o SDK para:

- Online:  dispositivo gera hash + manifesto → envia sem assinar → Lambda assina com cert ICP-Brasil
- Offline: dispositivo assina com cert dev como fallback → re-assina via KMS quando sincronizar

**Fluxo alvo:**
1. SDK verifica conectividade antes de capturar
2. Com conectividade: não assina localmente, faz upload imediato
3. Sem conectividade: assina com cert dev, enfileira para sync (DT-011)
4. Ao sincronizar: Lambda re-assina com KMS, grava kms_signed=true

**O que muda no SDK Android:**
- ProvviCapture.kt: assinatura local condicional (só se offline)
- CaptureSession: campo signing_mode: "local_fallback" | "kms_backend"
- BackendClient: indicar no payload se a assinatura local é definitiva ou fallback

**Dependências:**
- DT-003: certificado ICP-Brasil plugado no KMS (pré-requisito)
- DT-011: upload assíncrono + fila offline (implementar junto)

**Não fazer antes de:**
DT-003 resolvido — sem cert ICP-Brasil no KMS, a assinatura local
com cert dev continua sendo a única com integridade verificável.

**Resolução:**
- Resolução: Assinatura ICP-Brasil A1 ativa no backend. Assinatura local Ed25519 permanece apenas como fallback offline (signing_mode: kms_dev) — não é mais o caminho padrão.

---

## DT-014a — Play Integrity: risco granular por veredicto
**Prioridade:** Alta | **Status:** ✅ Implementado | **Registrado:** 2026-03-12

**O que foi feito:**
`ProvviCapture.kt` calcula `integrityRisk` como o máximo entre dois eixos independentes:

- `recaptureRisk` — derivado do `RecaptureDetector` (comportamento anterior)
- `deviceRisk` — derivado do veredicto Play Integrity:
  - `!meetsDeviceIntegrity` → `HIGH` (sem certificação de hardware)
  - `!meetsStrongIntegrity` → `MEDIUM` (certificado, sem strongbox)
  - API indisponível (`deviceVerdict == null`) → `NONE` (sem penalidade — DT-002)

Campos granulares adicionados ao manifesto C2PA / DynamoDB:
`meets_strong_integrity`, `meets_device_integrity`, `meets_basic_integrity`,
`device_risk`, `recapture_risk`.

**Nota:** enquanto `PROVVI_DEFAULT_CLOUD_PROJECT = 0L` (conta Play Store pendente — DT-002),
`deviceVerdict` é sempre `null` e `deviceRisk` é sempre `"NONE"`. A lógica está correta
e passa a ser plenamente informativa quando o número do projeto for configurado.

---

## DT-014b — IntegrityEnforcementConfig + DistributionContext MDM
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-12

**Problema:**
A política de enforcement é hardcoded — não diferencia contextos de distribuição
(Play Store vs MDM) nem permite configuração remota por cliente sem recompilar o SDK.

**Contexto — por que importa:**
Para distribuição MDM (HabilitAi), `appLicensingVerdict: UNLICENSED` é estrutural
e não indica fraude — bloquear por esse campo geraria falsos positivos.
Já `deviceIntegrity: MEETS_BASIC_INTEGRITY` falhando é sinal de dispositivo
comprometido e deve sempre bloquear, independente do contexto.

**Estrutura proposta no `BackendConfig`:**
```kotlin
data class IntegrityEnforcementConfig(
  val deviceIntegrity: EnforcementPolicy = BLOCK,     // hardware — sempre verificar
  val appLicensing: EnforcementPolicy = WARN,         // Play Store — ignorar em MDM
  val appRecognition: EnforcementPolicy = WARN,       // binário — ignorar em MDM
  val distributionContext: DistributionContext = AUTO  // AUTO | PLAY_STORE | MDM
)

enum class EnforcementPolicy { BLOCK, WARN, IGNORE }
enum class DistributionContext { AUTO, PLAY_STORE, MDM }
```

Com `distributionContext = MDM`, o SDK aplica automaticamente `IGNORE` em
`appLicensing` e `appRecognition`, mantendo `BLOCK` em `deviceIntegrity`.

**Configuração remota:**
Mover `IntegrityEnforcementConfig` para o payload de licença retornado pelo backend.
O SDK consulta e aplica sem recompilar — cada integrador pode ter política própria.

**Dependências:**
- DT-008 (licenciamento): `IntegrityEnforcementConfig` é parte do mesmo payload
  de JWT de licença por cliente. Implementar junto no Sprint 5.

---

## DT-015 — Desativar Upload Queue e Captura Offline como Fluxo Padrão
**Prioridade:** Alta | **Status:** Implementado | **Registrado:** 2026-03-12

**Problema:**
O Upload Queue e a assinatura Ed25519 como caminho offline estavam planejados
como fluxo padrão. Isso cria vulnerabilidade de timestamp: o operador pode
manipular o relógio do dispositivo e capturar com data retroativa, sem que
o SDK detecte — o Play Integrity API não verifica alteração de relógio.

**Decisão:**
Captura exige conexão ativa. Sem conexão: bloquear e exibir mensagem clara
ao operador. O token TSA RFC 3161 é obtido no backend segundos após o upload,
eliminando qualquer janela de manipulação temporal relevante.

**O que implementar:**
- `ProvviCapture.kt`: verificar conectividade antes de iniciar o pipeline
- Sem conexão: retornar erro `PROVVI_ERROR_NO_CONNECTIVITY` antes da captura
- Remover Upload Queue do fluxo de execução padrão (manter código para DT-006 futuro)
- Documentar para Sarah/HabilitAi: captura requer cobertura móvel ativa

**Impacto HabilitAi:**
Aulas práticas ocorrem em locais com cobertura móvel — impacto operacional
esperado baixo. Confirmar com Sarah antes do deploy.

**Dependências:**
- DT-016 deve estar implementado antes do deploy desta mudança.

---

## DT-016 — Implementar TSA RFC 3161 no Backend (lambda-signer)
**Prioridade:** Alta | **Status:** Implementado | **Registrado:** 2026-03-12

**Problema:**
O manifesto C2PA não possui âncora temporal externa. O campo `captured_at` vem
do dispositivo e é manipulável. Sem um token TSA de uma ACT credenciada ICP-Brasil,
não há prova jurídica irrefutável de quando a captura ocorreu.

**Decisão arquitetural:**
TSA ocorre no backend (lambda-signer), não no SDK. A ACT Certisign exige
autenticação de servidor — apps móveis não têm acesso direto ao endpoint RFC 3161.

**Fluxo técnico:**
1. Backend recebe manifesto + imagem do SDK
2. Calcula hash SHA-256 do manifesto
3. Envia `TimeStampRequest` RFC 3161 à ACT
4. Recebe token TSA assinado
5. Embute token no campo `timestamp_token` do manifesto C2PA
6. Assina manifesto + TSA com e-CNPJ A1 (sha256WithRSAEncryption)
7. Grava em S3 + DynamoDB

**Ambientes:**
- Dev/testes: FreeTSA (`https://freetsa.org/tsr`) — público, gratuito, sem auth,
  protocolo RFC 3161 idêntico. Sem validade jurídica ICP-Brasil.
- Produção: ACT Certisign — endpoint autenticado, credenciado ICP-Brasil.
  Troca requer apenas mudança de URL e certificado raiz no lambda-signer.

**Campo no manifesto C2PA:**
```json
"timestamp_token": {
  "alg": "RFC3161",
  "issuer": "ACT Certisign",
  "token": "<base64>"
}
```

**Pendente (bloqueio externo):**
Confirmar com Certisign na reunião técnica se endpoint TSA RFC 3161 está
incluso no contrato e-CNPJ A1 atual ou requer contratação separada do serviço ACT.

**Dependências:**
- DT-015 (captura requer conexão ativa)

---

## DT-017 — Tratamento de Falha de TSA (ACT Indisponível)
**Prioridade:** Alta | **Status:** Implementado | **Registrado:** 2026-03-12

**Problema:**
A ACT Certisign pode estar temporariamente indisponível. O comportamento do
backend nesse cenário precisa ser definido explicitamente para não gravar
manifestos sem âncora temporal.

**Decisão recomendada:**
Bloquear: backend rejeita o manifesto e retorna erro ao SDK se não conseguir
obter token TSA após retentativas. A perda eventual de uma captura por falha
de ACT é preferível a gravar um manifesto sem timestamp irrefutável.

**Retry no backend:**
3 tentativas com backoff exponencial (1s / 2s / 4s). Após falha total:
retornar HTTP 503 ao SDK com mensagem `TSA_UNAVAILABLE`. SDK exibe mensagem
ao operador para tentar novamente.

**Fallback FreeTSA em produção:**
Avaliar com time jurídico/comercial se FreeTSA pode servir como fallback
temporário em indisponibilidade da Certisign. Se aprovado: gravar
`tsa_source: "fallback"` no manifesto para identificação e revisão posterior.
Token FreeTSA não tem validade jurídica ICP-Brasil.

**lambda-verifier:**
Deve validar presença e assinatura do token TSA. Manifestos sem token
devem retornar `verification_status: "degraded"`, não aprovação plena.

**Dependências:**
- DT-016

---

## DT-018 — Esclarecer Papel do Ed25519: Manter como Camada de Integridade de Dispositivo
**Prioridade:** Média | **Status:** Implementado | **Registrado:** 2026-03-12

**Contexto:**
Versão anterior deste débito (v1.0, 12/03/2026) propunha remover o Ed25519
inteiramente. Decisão revertida após análise.

**Decisão:**
Manter Ed25519 como camada de integridade de dispositivo. A chave é gerada
por aparelho, fica armazenada localmente, não é nominativa e não requer
distribuição de certificado. Ela prova que aquele hardware específico (device_id)
gerou aquele manifesto — papel distinto do ICP-Brasil A1 (nominativo, no servidor).

**Modelo de três camadas — todo manifesto válido em produção deve ter:**
1. **Ed25519** (SDK) — prova de integridade de hardware/dispositivo
2. **Token TSA RFC 3161** (backend, ACT Certisign) — âncora temporal irrefutável
3. **Assinatura ICP-Brasil A1** (backend, e-CNPJ Certisign) — autoria jurídica

**O que remover:**
Apenas a lógica que usava Ed25519 como substituto da assinatura ICP-Brasil
quando o backend estava indisponível. Essa lógica criava ambiguidade sobre
o nível de garantia do manifesto.

**Comportamento correto:**
Manifesto sem qualquer uma das três camadas → `verification_status: "incomplete"`.
Ed25519 permanece ativo no SDK como camada 1 — sem alteração no pipeline de captura.

**Dependências:**
- DT-016 (TSA) deve estar funcional para que o modelo de três camadas seja completo.

---

## DT-019 — Padronização de Erros do SDK (ProvviError)
**Prioridade:** Alta | **Status:** Implementado (parcial — validação em dispositivo pendente, ver DT-010) | **Registrado:** 2026-03-12

**Problema:**
O SDK não possui um modelo de erros padronizado e tipado. Erros de origens distintas
(permissões, ambiente, câmera, backend, criptografia) são tratados de forma
inconsistente — alguns lançam exceções, outros retornam null, outros são silenciados.
O integrador (HabilitAi/Sarah) não tem como reagir programaticamente a cada situação.

**Referência:**
Modelado a partir do `LensError` da Truepic, adaptado para o escopo da Provvi
(sem vídeo, sem áudio, sem geofencing, sem enrollment de dispositivo).

**Estrutura proposta:**

```kotlin
data class ProvviError(
  val type: ProvviErrorType,
  val message: String,
  val exception: Throwable? = null
)

enum class ProvviErrorType {

  // Permissões — usuário precisa conceder antes de tentar novamente
  PERMISSIONS_CAMERA,        // Permissão de câmera negada ou revogada
  PERMISSIONS_LOCATION,      // Permissão de localização negada ou revogada

  // Ambiente — condições do dispositivo impedem captura segura
  ENV_AIRPLANE_MODE,         // Modo avião ativo — sem conexão (captura exige rede)
  ENV_DEV_MODE,              // USB debugging ou modo desenvolvedor ativo
  ENV_NETWORK,               // Sem conexão com a internet
  ENV_ROOTED,                // Dispositivo rooteado — captura não permitida
  ENV_PLAY_SERVICES,         // Google Play Services ausente ou desatualizado
  ENV_LOCATION_DISABLED,     // Serviço de localização desabilitado no dispositivo
  ENV_LOCATION_NOT_READY,    // GPS ainda adquirindo fix — erro temporário, tentar novamente
  ENV_LOCATION_NOT_ACCURATE, // Precisão do GPS abaixo do threshold (~50m)
  ENV_STORAGE_SPACE,         // Espaço de armazenamento insuficiente

  // Câmera
  CAMERA_ERROR,              // Erro genérico ao acessar ou operar a câmera
  CAPTURE_IMAGE_ERROR,       // Erro durante a captura do frame

  // Integridade do dispositivo
  ATTESTATION_ERROR,         // Play Integrity: deviceIntegrity comprometido (MEETS_BASIC_INTEGRITY falhou)
  STRONGBOX_UNAVAILABLE,     // StrongBox não disponível — fallback para TEE (não bloqueante)
  KEY_GENERATION_ERROR,      // Erro ao gerar ou acessar chave Ed25519 local

  // Backend / rede
  BACKEND_UPLOAD_ERROR,      // Erro ao enviar manifesto/imagem ao lambda-signer
  BACKEND_TSA_UNAVAILABLE,   // ACT indisponível após retentativas (ver DT-017)
  BACKEND_SIGNING_ERROR,     // Erro na assinatura ICP-Brasil A1 no backend
  BACKEND_AUTH_ERROR,        // API Key inválida ou licença expirada (ver DT-008)

  // Outros
  UNDEFINED                  // Erro não categorizado — verificar message e exception
}
```

**Contrato de retorno proposto:**

Alterar `ProvviCapture.capture()` para retornar um tipo `Result<CaptureSession, ProvviError>`
em vez de lançar exceções ou retornar null:

```kotlin
sealed class ProvviResult<out T> {
  data class Success<T>(val value: T) : ProvviResult<T>()
  data class Failure(val error: ProvviError) : ProvviResult<Nothing>()
}
```

O integrador trata o retorno com when:

```kotlin
when (val result = provvi.capture()) {
  is ProvviResult.Success -> handleSession(result.value)
  is ProvviResult.Failure -> when (result.error.type) {
    ENV_ROOTED        -> showBlockingError("Dispositivo não permitido")
    ENV_NETWORK       -> showRetryDialog("Sem conexão. Tente novamente.")
    PERMISSIONS_CAMERA -> requestCameraPermission()
    STRONGBOX_UNAVAILABLE -> continue // não bloqueante
    else              -> showGenericError(result.error.message)
  }
}
```

**Erros bloqueantes vs. não bloqueantes:**

| Tipo | Comportamento | Ação do integrador |
|---|---|---|
| `ENV_ROOTED` | Bloquear captura | Exibir erro permanente |
| `ENV_DEV_MODE` | Bloquear captura | Exibir erro, pedir desativar USB debug |
| `ATTESTATION_ERROR` | Bloquear captura | Registrar, notificar Provvi |
| `ENV_NETWORK` / `ENV_AIRPLANE_MODE` | Bloquear captura | Retry dialog |
| `ENV_LOCATION_NOT_READY` | Bloquear captura | Retry automático (~3s) |
| `ENV_LOCATION_NOT_ACCURATE` | Bloquear captura | Pedir ao operador mover ao ar livre |
| `PERMISSIONS_CAMERA` / `PERMISSIONS_LOCATION` | Bloquear captura | Solicitar permissão |
| `STRONGBOX_UNAVAILABLE` | Não bloqueante | Logar, continuar com TEE |
| `BACKEND_TSA_UNAVAILABLE` | Bloquear captura | Retry dialog |
| `BACKEND_AUTH_ERROR` | Bloquear captura | Contatar Provvi |

**Wrapper MAUI (HabilitAi):**
Mapear `ProvviErrorType` para constantes C# equivalentes no `provvi-maui`.
Sarah precisa conseguir distinguir erros de permissão (que o app pode resolver)
de erros de ambiente (que bloqueiam permanentemente) sem parsear strings.

**Resolução parcial (2026-03-12):**
`ProvviError.kt`, `ProvviResult.kt` criados. `captureResult()` e `captureResultBlocking()` adicionados
a `ProvviCapture.kt`. Wrapper MAUI atualizado com `ProvviCaptureOutcome`, `CaptureResultAsync()` e
campos `ClockSuspicious`/`IntegrityRisk` corrigidos. Bugs de getter JNI corrigidos (DT-019 pós-revisão).
`ProvviErrorType` implementado com 21 valores. `CapturedAtNanos` renomeado para `CapturedAtMs`.

**Dependências:**
- DT-010 (validação MAUI): testar o mapeamento de erros no dispositivo físico
- DT-014 (enforcement): `ATTESTATION_ERROR` deve respeitar a política
  `IntegrityEnforcementConfig.deviceIntegrity` — se `WARN`, não lançar erro bloqueante

---

## DT-020 — cloudProjectNumber Duplicado em ProvviConfig e BackendConfig
**Prioridade:** Baixa | **Status:** Pendente | **Registrado:** 2026-03-12

**Problema:**
O número do projeto Google Cloud existe em dois lugares com default `0L`:
- `ProvviConfig.cloudProjectNumber` — usado para warmUp da Standard Play Integrity API no SDK
- `BackendConfig.cloudProjectNumber` — campo reservado para uso futuro no backend

Enquanto o valor for `0L` (padrão Provvi), não há problema. Quando o número real de produção
for configurado, o integrador terá que lembrar de preencher os dois campos com o mesmo valor.
Risco de dessincronização silenciosa: warmUp funcionaria, backend não.

**Mitigação atual:**
Comentários `(DT-020)` adicionados aos KDocs de ambos os campos apontando a dependência.

**Solução futura:**
Remover `cloudProjectNumber` de `BackendConfig` e propagar via `ProvviConfig → ProvviCapture → BackendClient`
internamente, sem expor ao integrador. Requer coordenação com o wrapper C# porque `BackendConfig`
é instanciado via JNI em `ProvviCapture.cs` com assinatura de construtor fixa:
```
(Ljava/lang/String;JLjava/lang/String;)V  // lambdaUrl, timeoutSeconds, apiKey
```
Adicionar `cloudProjectNumber` ao construtor JNI quebraria integradores existentes.

**Bloqueio:**
Mudança de assinatura do construtor `BackendConfig` quebra JNI em `ProvviCapture.cs`.
Fazer junto com próxima refatoração maior do wrapper MAUI.
