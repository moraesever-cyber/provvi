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

## DT-002 — Play Integrity sem cloudProjectNumber
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
Play Integrity sendo chamada sem `cloudProjectNumber` — modo "standard" sem binding do app.

**Sintoma observado:**
Log `IntegrityTokenRequest{nonce=..., cloudProjectNumber=null}` em todos os testes.

**Impacto:**
Em produção, fraudadores podem reutilizar tokens de outros apps.
Verificação backend comprometida.

**Solução proposta:**
- Criar projeto no Google Cloud Console
- Vincular ao app Android via SHA-1
- Passar `cloudProjectNumber` no `IntegrityTokenRequest`
- Adicionar como parâmetro de configuração do SDK

**Bloqueio:**
Requer publicação do app na Play Store (mesmo em internal testing).

---

## DT-003 — Certificados de Desenvolvimento C2PA
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
Certificado Ed25519 autoassinado (CA:FALSE, CN=Provvi Dev) usado em desenvolvimento.
Não está na CAI Trust List — `signingCredential.untrusted` em toda verificação externa.

**Impacto:**
Manifesto gerado em produção aparece como "não reconhecido" no CAI Verify.
Sem parceria PKI ICP-Brasil, não há validade jurídica plena do timestamp.

**Solução proposta:**
- Fase 2: parceria com V/Cert (VALID) ou Serasa Experian como CA intermediária
- Fase 5: submissão à CAI Trust List como primeiro SDK brasileiro listado
- Migrar `embed_from_memory` → `Builder` quando c2pa-rs estabilizar `unstable_api`

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
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
O diferencial competitivo "captura segura offline" do roadmap (item 1.3) está
parcialmente implementado. A assinatura local C2PA funciona sem rede, mas não há
armazenamento local da sessão quando o upload falha por falta de conectividade.

**O que já funciona offline:**
- Captura do frame (Camada 1)
- Hash SHA-256 pré-codec (Camada 3)
- Validação GPS local (Camada 3.5)
- Assinatura C2PA com certificado local (Camada 4)

**O que falta:**
- Fila local de sessões pendentes de upload (Room ou SharedPreferences)
- Retry automático quando conectividade é restaurada (WorkManager)
- Re-assinatura com timestamp ICP-Brasil no backend (diferencial jurídico)
- Indicação na UI de "sessão pendente de sincronização"

**Impacto sem implementação:**
- Se upload falha por falta de rede, a sessão local é válida mas não é persistida
- App reiniciado = sessão perdida
- Diferencial do roadmap parcialmente verdadeiro

**Solução proposta:**
1. `ProvviSessionStore`: armazena sessões localmente via Room
2. `ProvviSyncWorker`: WorkManager com constraint de rede para retry automático
3. Backend: endpoint de re-assinatura com timestamp ACT ICP-Brasil
4. `BackendConfig.offlineMode: Boolean` para controle pelo integrador

**Bloqueio:**
Parceria PKI ICP-Brasil (V/Cert ou Serasa) necessária para timestamp com validade jurídica plena.

---

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
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Problema:**
Não existe endpoint de verificação de autenticidade de imagens capturadas.
O manifesto C2PA e a imagem estão armazenados separadamente no S3 — o CAI Verify
público não consegue verificar sem um serviço intermediário.

**Fluxo necessário:**
1. Auditor envia: session_id + imagem original
2. Serviço busca manifesto no S3 e hash no DynamoDB
3. Calcula hash da imagem recebida
4. Compara: hash(imagem) == hash_manifesto == hash_dynamodb
5. Verifica assinatura C2PA do manifesto
6. Retorna relatório: válido/inválido + metadados + cadeia de custódia

**Impacto sem implementação:**
- Diferencial de auditabilidade do roadmap não está disponível
- HabilitAi não consegue responder questionamentos do MEC sobre provas de presença
- Modelo de receita por verificação (Modelo A) não pode ser implementado

**Solução proposta:**
- Segunda Lambda `lambda-verifier` em Rust
- Endpoint: POST /verify com body: {session_id, image_base64}
- Retorna: {valid: bool, session_id, captured_at, location, assertions, report_pdf_url}
- Relatório PDF exportável com cadeia de custódia (CPC 2015)
- Implementar antes do go-live com HabilitAi

**Dependências:**
- DT-003: certificado de produção ICP-Brasil para validade jurídica plena

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
**Prioridade:** Alta | **Status:** Pendente | **Registrado:** 2026-03-06

**Decisão:** Upload para o backend deve ser assíncrono — não bloquear a UI
após a captura local completar.

**Fluxo alvo:**
1. SDK captura + hash + assina localmente → retorna para o app (~3.5s)
2. App libera UI imediatamente
3. Upload acontece em background via WorkManager
4. Notificação silenciosa quando sincronizado

**Impacto:**
- Tempo percebido pelo usuário: 12s → 3.5s (captura local apenas)
- backend_upload_ms some dos timings visíveis ao usuário
- Habilita naturalmente o fluxo offline-first (DT-006)

**Dependências:**
- DT-006 (offline-first) — implementar juntos
- DT-003 (assinatura KMS no backend) — upload assíncrono é pré-requisito
  para assinatura backend não bloquear UI

---

## DT-012 — Migração Assinatura C2PA para Backend (KMS)
**Prioridade:** Alta | **Status:** Em implementação | **Registrado:** 2026-03-06

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
