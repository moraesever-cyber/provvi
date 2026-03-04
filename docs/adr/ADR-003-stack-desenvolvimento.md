# ADR-003 — Stack de Desenvolvimento: SDKs Nativos + App de Demonstração em Flutter

**Status:** Aceito  
**Data:** 2026-03-04  
**Contexto do produto:** SDK C2PA para vistorias — mercado de seguros BR  
**Relacionado a:** ADR-001 (Pipeline de Captura Segura)

---

## Contexto

O produto é entregue como SDK móvel para integração por terceiros (apps de vistoria). A decisão de stack afeta diretamente a viabilidade técnica das camadas críticas definidas no ADR-001, o esforço de desenvolvimento com equipe enxuta, e a qualidade do que é entregue ao integrador.

A alternativa mais imediata seria construir em Flutter com Platform Channels para as partes nativas — aproveitando experiência recente de desenvolvimento de app completo nessa stack. A questão é se essa abordagem é adequada para um SDK com requisitos de acesso de baixo nível a hardware.

### Perfil técnico da equipe

- Dez anos de experiência em Java — base conceitual direta para Kotlin (mesma JVM, transição suave)
- Experiência com Objective-C — familiaridade com paradigma iOS, base para Swift
- Desenvolvimento recente de app completo em Flutter com apoio de IA
- Modelo de trabalho: desenvolvimento bootstrapped com uso intensivo de ferramentas de IA (Claude, Gemini) como apoio de implementação

---

## Decisão

**SDKs nativos separados** para Android (Kotlin) e iOS (Swift), com **app de demonstração em Flutter** consumindo ambos via Platform Channels.

### Estrutura de repositório

```
sdk-c2pa-br/
├── android/          ← Kotlin, CameraX/Camera2, c2pa-rs via JNI
├── ios/              ← Swift, AVFoundation, c2pa-rs via SPM
└── demo-app/         ← Flutter, consome os dois SDKs nativos
```

### Entregáveis por plataforma

| Plataforma | Formato de entrega | Linguagem |
|---|---|---|
| Android | `.aar` (Android Archive) | Kotlin |
| iOS | `.xcframework` | Swift |
| App de demonstração | APK + IPA | Flutter |

---

## Justificativa

### Por que não Flutter para o SDK

As três camadas críticas do ADR-001 exigem acesso direto a APIs nativas que o Flutter não expõe adequadamente:

**Camada 1 — Controle exclusivo da câmera**
O plugin padrão `camera` do Flutter passa pelo pipeline nativo do SO — exatamente o ponto vulnerável a ataques de injeção. CameraX (Android) e AVCaptureSession (iOS) precisam ser acessados diretamente, antes de qualquer abstração de framework.

**Camada 2 — Verificação de integridade do dispositivo**
Play Integrity API (Android) e App Attest (iOS) não têm wrappers Flutter maduros e confiáveis para uso em contexto de segurança. Dependências de terceiros nessa camada introduzem superfície de ataque inaceitável.

**Camada 3 — Hash pré-codec**
O buffer RAW/YUV antes da compressão JPEG não é exposto pelo Flutter. Sem acesso a esse buffer, o hash cobre a imagem já processada — enfraquecendo a garantia de integridade.

Construir em Flutter com Platform Channels não elimina nenhum desses problemas — o código nativo teria que ser escrito de qualquer forma, com Flutter adicionando uma camada de orquestração sem benefício para um SDK que não tem UI própria.

### Por que nativo é viável dado o perfil da equipe

**Java → Kotlin:** transição direta. Mesma JVM, mesma orientação a objetos, sintaxe mais concisa. Dez anos de Java significa que os padrões arquiteturais (interfaces, injeção de dependência, tratamento de erros) são imediatamente transferíveis. A curva de aprendizado é de semanas, não meses.

**Objective-C → Swift:** mais distante em sintaxe, mas a experiência com o paradigma iOS (ciclo de vida, delegates, gerenciamento de memória) reduz significativamente a curva. Swift é considerado mais acessível que Objective-C para quem já conhece o ecossistema Apple.

**Apoio de IA no desenvolvimento:** Kotlin e Swift têm excelente cobertura nos modelos de linguagem atuais — documentação, padrões de código, APIs de câmera e criptografia são casos de uso bem representados no treinamento dos modelos.

### Por que Flutter faz sentido para o demo-app

O app de demonstração tem UI complexa (fluxo de captura, visualização de manifesto C2PA, relatório de asserções) e não tem requisitos de acesso de baixo nível a hardware — é consumidor do SDK, não o SDK em si. Flutter é produtivo exatamente nesse contexto, e a experiência recente é diretamente aplicável.

O demo-app em Flutter tem um benefício adicional: valida que a API pública do SDK é simples o suficiente para ser consumida via Platform Channels. Se a integração Flutter funciona bem, a integração em qualquer outro contexto nativo também funcionará.

---

## Alternativas consideradas

### Alternativa A — Flutter + Platform Channels para tudo
Descartada. Não elimina a necessidade de código nativo nas camadas críticas. Adiciona complexidade de orquestração (serialização de dados entre Dart e nativo, gestão de threads, tratamento de erros cross-layer) sem benefício mensurável para o integrador final, que recebe um `.aar` ou `.xcframework` independentemente.

### Alternativa B — React Native
Descartada pelos mesmos motivos técnicos da Alternativa A, com agravante: a experiência recente da equipe é em Flutter, não React Native — mesmo que o stack React/Supabase do marketplace seja familiar, React Native é suficientemente diferente para não haver ganho de produtividade imediato.

### Alternativa C — Kotlin Multiplatform (KMP)
Considerada para iteração futura. KMP permite compartilhar lógica de negócio (assinatura C2PA, verificação de manifesto, custom assertions) entre Android e iOS, mantendo a UI e o acesso a hardware nativos em cada plataforma. Para v1.0, a complexidade adicional de KMP não se justifica — os dois SDKs terão lógica suficientemente distinta. Reavaliar em v2.x quando a base de código estiver estabilizada.

### Alternativa D — Rust puro com bindings para Android e iOS
Tecnicamente ideal para o núcleo criptográfico — `c2pa-rs` já é Rust, e compilar para Android (via NDK) e iOS (via `cargo-lipo`) é viável. Descartada como stack principal pela curva de aprendizado e pelo overhead de manutenção de bindings para equipe enxuta. Rust permanece como dependência para `c2pa-rs`, não como linguagem principal do SDK.

---

## Consequências

**Positivas:**
- Acesso direto às APIs de câmera e segurança sem camadas intermediárias
- Entregável padrão de mercado para SDKs móveis (`.aar` / `.xcframework`)
- Kotlin é transição natural da experiência Java existente
- Demo-app em Flutter valida a API pública e aproveita experiência recente
- Sem dependência de manutenção de plugins Flutter de terceiros no caminho crítico

**Negativas / trade-offs:**
- Lógica compartilhável (assinatura C2PA, parsing de manifesto) precisa ser implementada duas vezes — uma em Kotlin, uma em Swift — até eventual migração para KMP
- Curva de aprendizado em Swift para a parte iOS, mitigada por experiência prévia com Objective-C e apoio de IA
- Manutenção de dois repositórios de SDK independentes vs. codebase unificado

**Mitigação do trabalho duplicado:**
Isolar a lógica de negócio compartilhável em módulos bem definidos desde o início facilita eventual migração para KMP. A interface pública do SDK (nomes de métodos, tipos de dados, estrutura de callbacks) deve ser idêntica entre Android e iOS — o integrador não deve perceber diferença entre as duas plataformas.

---

## Referências

- [CameraX — Android Developers](https://developer.android.com/training/camerax)
- [AVFoundation — Apple Developer](https://developer.apple.com/av-foundation/)
- [c2pa-rs — Android NDK build](https://github.com/contentauth/c2pa-rs/blob/main/docs/build-from-source.md)
- [Swift Package Manager — integração com Rust via FFI](https://github.com/nickel-lang/nickel)
- [Kotlin Multiplatform — documentação oficial](https://kotlinlang.org/docs/multiplatform.html)
- [Flutter Platform Channels](https://docs.flutter.dev/platform-integration/platform-channels)
