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