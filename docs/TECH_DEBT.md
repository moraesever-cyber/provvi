# Débitos Técnicos — Demo App Provvi

## DT-DEMO-001 — Autenticação do vistoriador
Login real (Google ou e-mail) vinculando capturedBy a identidade verificada.
Prioridade: Média. Bloqueado por: definição de backend de autenticação.

## DT-DEMO-002 — Histórico de capturas
Lista de sessões da sessão atual com navegação para resultados anteriores.
Prioridade: Baixa.

## DT-DEMO-003 — Device binding ao vistoriador
Vincular Play Integrity token ao perfil autenticado.
Prioridade: Média. Bloqueado por: DT-DEMO-001 e DT-003 (certificado ICP-Brasil).

## DT-DEMO-004 — URL amigável para o verifier
Substituir Lambda URL por https://verificar.provvi.com.br?session={id}
Prioridade: Média. Bloqueado por: configuração de DNS.

## DT-DEMO-005 — Exibição da imagem capturada
Expor bytes JPEG da última captura via bridge (lastCapturedJpeg: ByteArray? no ProvviCapture).
Necessário para: exibir a imagem real na ResultScreen e TamperScreen.
Prioridade: Alta — desbloqueia o impacto visual máximo da demo.
Bloqueado por: modificação controlada do SDK nativo.

## DT-DEMO-006 — TamperScreen com imagem real
Substituir a demo de hash por manipulação visual real da imagem capturada.
Pré-requisito: DT-DEMO-005 concluído.
Prioridade: Média.

## DT-SDK-001 — Validação server-side do Play Integrity token no lambda-signer
O `lambda-signer` atual armazena o `deviceIntegrityToken` no DynamoDB mas não o valida
com os servidores do Google. A verificação client-side em `DeviceIntegrityChecker` é
indicativa e pode ser removida por APK modificado. O backend deve ser a fonte de verdade.

**Arquitetura correta (Provvi como fornecedor de SDK):**
- Provvi cria e mantém 1 projeto Google Cloud com Play Integrity API habilitada
- Todos os integradores usam `ProvviConfig(cloudProjectNumber = PROVVI_CLOUD_NUMBER)`
- O token gerado atesta o app do integrador (package name + cert de assinatura) — isso
  vem do Google Play Services, independente de quem é o cloudProjectNumber
- O `lambda-signer` chama `playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken`
  usando service account próprio da Provvi para descriptografar o token
- O backend valida: `meetsBasicIntegrity`, package name autorizado, cert esperado
- Se falhar: marcar sessão como `integrity_risk: HIGH` no DynamoDB e no manifesto C2PA
  (não bloquear retroativamente — registrar para decisão da seguradora)

**Onboarding de integradores:**
- Cada novo cliente cadastra o package name do app dele no Google Play Console
  associado ao projeto Cloud da Provvi
- Provvi mantém lista de package names autorizados no lambda-signer

**O que não é necessário:**
- Credenciais do integrador no backend da Provvi
- Projeto Cloud separado por integrador

Prioridade: Alta — gap de segurança documentado, não bloqueia demo.
Bloqueado por: conta Google Play Console corporativa da Provvi + projeto Cloud configurado.

## DT-DEMO-008 — Calibração dos limiares de detecção de recaptura
Telas de alta resolução (OLED, 400+ PPI, 120Hz) produzem padrões de moiré
muito sutis que podem não atingir THRESHOLD_SUSPICIOUS = 0.6.

Durante os testes, uma foto de tela não foi detectada como recaptura.

Ações necessárias:
- Coletar amostras: 20+ fotos de telas (vários modelos) + 20+ fotos de objetos reais
- Calcular distribuição de scores para cada indicador nas duas classes
- Ajustar THRESHOLD_MOIRE, THRESHOLD_CHROMATIC e THRESHOLD_SUSPICIOUS com base
  nos dados reais
- Considerar adicionar indicador de análise de bordas (edge sharpness) —
  telas têm bordas de texto/ícones mais nítidas que objetos físicos

Prioridade: Alta — afeta a confiabilidade do SDK em contexto de fraude.
Não bloqueia a demo, mas deve ser resolvido antes de produção com seguradoras.

## DT-DEMO-007 — Timestamp via TSA RFC 3161 (ICP-Brasil)
Atualmente o timestamp de captura é `System.currentTimeMillis()` do dispositivo — passível de
manipulação (ajuste do relógio do sistema). A solução definitiva é obter um token de
timestamp assinado de uma Autoridade de Carimbo do Tempo (ACT) ICP-Brasil via RFC 3161,
incluído no manifesto C2PA como `c2pa.time-stamp.rfc3161`. Isso torna o horário verificável
independentemente do dispositivo.
Prioridade: Alta — bloqueador para produção.
Bloqueado por: contratação de ACT ICP-Brasil homologada (ex.: Serpro, Certisign).
