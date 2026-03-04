# Provvi SDK

SDK C2PA de captura segura com validade jurídica — mercado de seguros BR.

## Estrutura
```
provvi/
├── android/          # SDK Kotlin (.aar)
├── ios/              # SDK Swift (.xcframework)
├── backend/          # Serviço de assinatura (Rust + AWS Lambda)
├── demo-app/         # App de demonstração (Flutter)
└── docs/adr/         # Architecture Decision Records
```

## ADRs

- [ADR-001](docs/adr/ADR-001-pipeline-captura-segura.md) — Pipeline de captura segura
- [ADR-002](docs/adr/ADR-002-deteccao-recaptura.md) — Detecção de recaptura
- [ADR-003](docs/adr/ADR-003-stack-desenvolvimento.md) — Stack de desenvolvimento

## Setup

Veja [docs/setup.md](docs/setup.md).
