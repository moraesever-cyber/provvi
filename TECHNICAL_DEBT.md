# Provvi SDK — Débitos Técnicos

Registro formal de decisões técnicas temporárias e melhorias pendentes.
Formato: [prioridade] [status] descrição + contexto + solução proposta.

---

## DT-001 — Instrumentação de Performance do Pipeline
**Prioridade:** Média | **Status:** Pendente | **Registrado:** 2026-03-06

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