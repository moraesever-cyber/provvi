# ADR-001 — Arquitetura do Pipeline de Captura Segura

**Status:** Aceito  
**Data:** 2026-03-04  
**Contexto do produto:** SDK C2PA para vistorias — mercado de seguros BR  

---

## Contexto

O produto é um SDK móvel (iOS/Android) que assina criptograficamente imagens no momento da captura, gerando um manifesto C2PA com asserções verificáveis. O problema central de segurança é garantir que a imagem assinada representa um objeto físico real fotografado naquele momento — não uma imagem pré-existente, uma tela, ou conteúdo injetado no feed da câmera.

A referência de mercado (Truepic Lens SDK) resolve esse problema com sua tecnologia "Controlled Capture". A pergunta arquitetural é: como estruturar um pipeline equivalente que seja tecnicamente sólido, não dependa de componentes proprietários da Truepic, e seja implementável por equipe enxuta.

---

## Decisão

O pipeline de captura segura será estruturado em **quatro camadas sequenciais**, todas executadas antes de qualquer dado sair do dispositivo:

### Camada 1 — Controle exclusivo da câmera

O SDK substitui a câmera nativa do SO durante a sessão de captura. A integração ocorre diretamente com as APIs de câmera de baixo nível:

- **Android:** CameraX (ou Camera2) com acesso direto ao `ImageReader`, antes do pipeline JPEG nativo
- **iOS:** `AVCaptureSession` com `AVCapturePhotoOutput`, capturando o buffer RAW antes de qualquer processamento do sistema

Isso impede ataques de injeção: não há como substituir o feed da câmera por conteúdo externo porque o SDK nunca passa pelo pipeline de câmera do SO que poderia ser interceptado.

### Camada 2 — Verificação de integridade do dispositivo

Antes de autorizar qualquer captura assinada, o SDK verifica:

- Se o dispositivo está comprometido (jailbreak/root) via APIs nativas de attestation
  - Android: [Play Integrity API](https://developer.android.com/google/play/integrity)
  - iOS: [DeviceCheck](https://developer.apple.com/documentation/devicecheck) / [App Attest](https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-servers)
- Se a versão do SO e do app atendem aos requisitos mínimos de segurança

Dispositivos comprometidos podem ter o pipeline de câmera adulterado em nível de SO — a captura nesses dispositivos não é confiável independentemente do SDK.

### Camada 3 — Hash pré-codec

O frame bruto (RAW/YUV) é hasheado com SHA-256 **antes** de qualquer compressão JPEG. Isso garante que o hash no manifesto C2PA corresponde aos pixels originais capturados pelo sensor, não a uma versão reprocessada pelo codec.

```
[Sensor] → [Buffer RAW] → [SHA-256] → [Compressão JPEG] → [Imagem final]
               ↑
         hash gerado aqui
```

O hash do frame RAW é incluído como asserção no manifesto C2PA junto com o hash da imagem final. Qualquer divergência entre os dois indica adulteração pós-captura.

### Camada 4 — Assinatura C2PA e timestamp

Após a captura, o SDK executa:

1. Geração do manifesto C2PA via `c2pa-rs` (biblioteca de referência da CAI, Rust, open-source)
2. Inclusão das asserções padrão: GPS (WGS84), device make/model, ações aplicadas
3. Inclusão das asserções de negócio (custom assertions): número de apólice, ID do perito, tipo de sinistro — definidas pelo integrador via template
4. Timestamp via **ACT credenciada pela ICP-Brasil** (Autoridade de Carimbo do Tempo) — fornece validade jurídica local equivalente à data certa notarial, diferente do TSA próprio da Truepic que não tem reconhecimento no Brasil
5. Assinatura criptográfica com chave privada gerenciada pelo backend (PKI própria, sem exposição de chaves no dispositivo)

---

## Alternativas consideradas

### Alternativa A — Usar o Truepic Lens SDK como base
Descartada. O SDK é proprietário, oferecido apenas como binário, sem acesso ao código-fonte. Cria dependência irremovível de um fornecedor estrangeiro para um componente central do produto. Incompatível com o objetivo de construção independente.

### Alternativa B — Usar câmera nativa do SO + assinar post-capture
Descartada para o caso de uso principal. Sem controle do pipeline de captura, não há como impedir que o usuário substitua o arquivo antes da assinatura. Pode ser oferecida como modalidade secundária para conteúdo já existente (re-signing), mas não como captura segura.

### Alternativa C — Depender de hardware seguro (TEE/Secure Enclave)
Considerada como evolução futura. A Truepic desenvolveu o "Foresight" com integração ao TEE do Snapdragon, o que eleva a segurança ao nível de firmware. Para v1.0, a abordagem de software é suficiente para o mercado-alvo e elimina a dependência de modelos específicos de hardware. O caminho para TEE fica aberto como v2.x.

---

## Consequências

**Positivas:**
- Pipeline completamente controlado — sem dependência de terceiros no caminho crítico
- ACT ICP-Brasil fornece timestamp com validade jurídica local que a Truepic não oferece
- `c2pa-rs` é open-source, mantido pela CAI, com roadmap público
- Arquitetura permite offline-first: captura segura sem conexão, assinatura diferida ao reconectar (hash local garante integridade no intervalo)

**Negativas / trade-offs:**
- Maior complexidade de implementação vs. integrar um SDK pronto
- Exige expertise em CameraX/AVFoundation de baixo nível
- O hash pré-codec adiciona ~50-100ms ao tempo de captura em dispositivos de gama média

**Restrições para implementação:**
- A imagem assinada **não deve** ser salva via UIKit/Gallery no iOS — o pipeline nativo de fotos do iOS descarta metadados JUMBF que contêm o manifesto C2PA. Salvar via `Data.write(to: url)` no diretório da aplicação.
- O integrador **não deve** recomprimir ou redimensionar a imagem após a assinatura — qualquer alteração invalida o hash e quebra a verificação do manifesto.

---

## Referências

- [c2pa-rs — Content Authenticity Initiative](https://github.com/contentauth/c2pa-rs)
- [C2PA Technical Specification v2.0](https://c2pa.org/specifications/specifications/2.0/specs/C2PA_Specification.html)
- [Android Play Integrity API](https://developer.android.com/google/play/integrity)
- [Apple App Attest](https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-servers)
- [ICP-Brasil — Autoridades de Carimbo do Tempo credenciadas](https://www.gov.br/iti/pt-br/assuntos/certificacao-digital/cadeia-de-certificados)
