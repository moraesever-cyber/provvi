# Provvi SDK — Contexto para Claude Code

## O que é
Provvi é um SDK móvel (Android + iOS) que autentica mídia capturada via padrão C2PA.
Cada foto gera um manifesto criptograficamente assinado provando: dispositivo íntegro,
câmera não interceptada, localização e horário reais, objeto fisicamente presente.

## Mercado-alvo
Seguros Brasil — vistorias de veículos, prevenção de fraude em sinistros.

## Arquitetura — Pipeline de 4 camadas (ADR-001)
1. Controle exclusivo da câmera (CameraX/Camera2 direto, sem pipeline do SO)
2. Verificação de integridade do dispositivo (Play Integrity API)
3. Hash pré-codec SHA-256 do frame RAW antes da compressão JPEG
3.5. Validação de localização multi-fonte (GPS + WiFi + Cell, divergência > 500m = flag)
4. Assinatura C2PA via c2pa-rs + timestamp via ACT ICP-Brasil

## Decisões técnicas (ADRs)
- ADR-001: Pipeline de captura — CameraX Camera2 interop, hash pré-codec, c2pa-rs
- ADR-002: Detecção de recaptura — análise de Moiré via FFT, sem ML em v1.0
- ADR-003: Stack — SDK nativo Kotlin (Android) + Swift (iOS), demo em Flutter

## Estrutura do repositório
- `android/provvi-sdk/` — SDK Kotlin, entregável .aar
- `ios/ProvviSDK/` — SDK Swift, entregável .xcframework
- `backend/` — Serviço de assinatura Rust + AWS Lambda
- `demo-app/` — App Flutter consumindo ambos os SDKs
- `docs/adr/` — Architecture Decision Records

## Convenções
- Linguagem dos comentários: português brasileiro
- Package base Android: br.com.provvi
- minSdk: 26 (Android 8.0)
- Kotlin + Coroutines, sem RxJava
- Sem dependências de UI no SDK (sem Activity, Fragment, View)
- Sealed classes para resultados de operações

---

## Status do Projeto

### Fase 0 — Concluída (Mar 2026)
Pipeline Android implementado e validado externamente via CAI Verify.

**Entregáveis concluídos:**
- Pipeline de captura Android completo (5 camadas ADR-001)
- Sistema de asserções extensível (ProvviAssertions + GenericAssertions)
- HabilitAiAssertions.kt — arquivo de entrega para cliente HabilitAi
- provvi-sdk-release.aar (7MB) compilando limpo
- Manifesto C2PA validado via verify.contentcredentials.org e c2patool
- validation_state: Valid — claimSignature.validated, dataHash.match

**Pendente da Fase 0 — incorporado nas fases seguintes:**
- iOS: AVFoundation pipeline → Fase 1 paralelo ao demo Flutter
- Backend de assinatura (AWS Lambda Rust + S3, latência < 800ms P95) → Fase 1
- Detecção de recaptura v1.0 (Moiré via FFT, ADR-002) → Fase 1 pós-demo
- Documentação PT-BR completa → ao longo da Fase 1

**Certificado de desenvolvimento:**
- Ed25519, CA:FALSE, digitalSignature, CN=Provvi Dev, O=Provvi, C=BR
- Localização: android/provvi-sdk/src/main/rust/src/ (excluído do git via .gitignore)
- signingCredential.untrusted esperado — certificado não está na CAI Trust List

---

### Fase 1 — Em andamento (Mai–Jun 2026)

**Próximo passo imediato:** app demo Flutter
- Objetivo: validar SDK end-to-end em dispositivo físico antes de integrar HabilitAi
- Usa GenericAssertions (sem asserções específicas de domínio)
- Platform Channels: Flutter → SDK nativo Android

**Sequência planejada Fase 1:**
1. App demo Flutter (em andamento)
2. Testes de ataque no ambiente do demo
3. Wrapper MAUI/.NET para HabilitAi
4. Integração ML Kit liveness → custom assertion
5. Fluxo offline-first

**Decisão arquitetural registrada:**
- Demo Flutter antes da integração HabilitAi — ambiente isolado para amadurecer o SDK
- MAUI continua no escopo da Fase 1, após o demo

**Sequência obrigatória após qualquer alteração no provvi-sdk:**
1. `./gradlew :provvi-sdk:assembleRelease`
2. `cp android/provvi-sdk/build/outputs/aar/provvi-sdk-release.aar demo-app/android/app/libs/`
3. `cd demo-app && flutter build apk --debug`

Esquecer o passo 2 faz o demo rodar com o .aar antigo — sintoma: comportamento divergente entre SDK e demo.

**ADR-002 Caminho 1 — implementado:**
- RecaptureDetector.kt: Moiré (FFT, peso 0.5) + reflexo especular (peso 0.3) + padrão cromático (peso 0.2)
- Thresholds iniciais: SUSPICIOUS > 0.6, calibração pendente com dataset real
- Risco principal: falso negativo em telas OLED/4K (Moiré menos pronunciado)
- Demo atualizado com tratamento de RECAPTURE_SUSPECTED