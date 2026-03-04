# ADR-002 — Detecção de Recaptura: Abordagem Alternativa às Patentes Truepic

**Status:** Aceito  
**Data:** 2026-03-04  
**Contexto do produto:** SDK C2PA para vistorias — mercado de seguros BR  
**Relacionado a:** ADR-001 (Pipeline de Captura Segura)

---

## Contexto

"Detecção de recaptura" é o problema de identificar quando alguém fotografa uma tela, uma foto impressa, ou um vídeo em reprodução — em vez de um objeto físico real. É um vetor de fraude relevante em vistorias: o segurado apresenta foto de um veículo em boas condições (pré-dano) como se fosse atual.

A Truepic resolve esse problema com uma combinação patenteada específica:

*"Extrair features de uma imagem; aplicar as features como input a um modelo de ML treinado que retorna um score; obter metadados da imagem; realizar análise estatística dos metadados; gerar um segundo score; combinar os dois scores para produzir uma probabilidade de recaptura."* — US Patent, Truepic Inc. (portfólio de \~25 patentes registradas)

O fluxo específico **score de ML \+ score estatístico de metadados → probabilidade combinada** está protegido. Reimplementar esse pipeline exato criaria risco de litígio de patente, mesmo que os componentes individuais sejam de domínio público.

---

## Decisão

Implementar detecção de recaptura por **dois caminhos alternativos** que alcançam resultado funcionalmente equivalente sem seguir o pipeline patenteado:

### Caminho 1 — Análise de artefatos físicos de tela (v1.0)

Telas digitais produzem padrões visuais detectáveis que não existem em objetos físicos reais:

- **Padrão de Moiré:** interferência entre a grade de pixels da tela fotografada e o sensor da câmera que fotografa. Detectável via análise de frequência no domínio da transformada de Fourier (FFT) — picos periódicos no espectro de frequência indicam grade de pixels  
- **Reflexo especular:** telas têm reflexo característico de superfície plana e uniforme. Análise da distribuição de highlights na imagem  
- **Aberração cromática de tela:** pixels RGB de telas emitem luz em padrão diferente de superfícies físicas iluminadas — detectável via análise dos canais de cor

Essa abordagem trabalha **exclusivamente no domínio de sinais físicos**, sem ML treinado, e é completamente distinta do pipeline patenteado da Truepic.

### Caminho 2 — Consistência de sensores no momento da captura (v1.x)

Durante a captura, o SDK coleta dados de sensores do dispositivo além da câmera:

- Acelerômetro e giroscópio: uma captura real de objeto físico tem micro-movimentos característicos de mão humana segurando o dispositivo. Uma captura de tela (dispositivo pousado em suporte, filmando outra tela) tem padrão de movimento diferente  
- Sensor de luz ambiente: iluminação de tela tem espectro diferente de luz natural/artificial refletida em objeto físico

Os dados de sensores são incluídos como asserção no manifesto C2PA e analisados no servidor no momento da verificação — não no dispositivo, o que elimina superfície de ataque local.

**Nota importante:** esse caminho usa sensores de forma distinta da Truepic, que usa fusão de sensores principalmente para anti-injeção (ADR-001, Camada 1). Aqui o uso é para análise de consistência pós-captura, finalidade diferente.

---

## O que deliberadamente NÃO será implementado

O seguinte pipeline está fora de escopo por risco de patente:

\[features da imagem\] → \[modelo ML treinado\] → \[score 1\]

\[metadados\]          → \[análise estatística\] → \[score 2\]

                                    ↓

                         \[probabilidade combinada\]

Qualquer implementação que siga essa estrutura — independentemente dos algoritmos específicos usados — reproduz a arquitetura patenteada. Se futuramente houver razão técnica para esse caminho, consultar assessoria jurídica antes de implementar.

---

## Avaliação de risco de patente

O risco de litígio no contexto atual é **baixo**, pelas seguintes razões:

1. **Jurisdição:** as patentes da Truepic são americanas (USPTO). Para um produto operando no Brasil, o risco direto é reduzido — patentes americanas não têm validade automática no Brasil, e a Truepic não registrou patentes equivalentes no INPI até onde foi possível verificar  
2. **Escala:** a Truepic é uma empresa de \~61 funcionários sem aporte de capital desde 2021\. Litígio internacional contra produto nascente no Brasil não está no perfil de ação provável  
3. **Arquitetura alternativa:** este ADR documenta explicitamente a decisão de usar caminhos diferentes, o que estabelece evidência de boa-fé na diferenciação

O risco só se torna relevante se o produto crescer a ponto de competir diretamente com a Truepic no mercado americano — cenário distante e que exigiria revisão deste ADR.

---

## Alternativas consideradas

### Alternativa A — Ignorar o problema e não implementar detecção de recaptura

Descartada. É um vetor de fraude real e documentado no mercado de seguros BR. Não ter detecção de recaptura enfraquece o argumento de venda do SDK.

### Alternativa B — Replicar o pipeline Truepic

Descartada. Risco de patente desnecessário quando há caminhos alternativos que alcançam resultado equivalente.

### Alternativa C — Licenciar a tecnologia da Truepic

Não avaliada formalmente. A Truepic oferece o Lens SDK como produto, não como tecnologia licenciável para competidores. Improvável que aceitem licenciar para um produto que compete diretamente com eles.

### Alternativa D — Adiar para versão posterior

Parcialmente aceita: o Caminho 1 (análise de artefatos físicos) entra na v1.0. O Caminho 2 (consistência de sensores) entra na v1.x, após validação do produto no mercado.

---

## Consequências

**Positivas:**

- Implementação limpa, sem risco jurídico  
- Caminho 1 é determinístico e auditável — não depende de modelo de ML opaco  
- Resultados da análise podem ser incluídos como asserção customizada no manifesto C2PA, tornando a evidência verificável pelo cliente

**Negativas / trade-offs:**

- Caminho 1 pode ter taxa de falsos negativos maior que ML treinado em casos de telas de alta resolução onde o padrão de Moiré é menos pronunciado  
- Ausência de modelo treinado significa que o sistema não aprende com novos ataques sem atualização de código — a Truepic atualiza o modelo continuamente

**Métricas de aceitação para v1.0:**

- Taxa de detecção de recaptura via tela comum (celular/monitor) ≥ 90% em testes controlados  
- Taxa de falso positivo (rejeitar foto legítima) ≤ 2%

---

## Referências

- Justia Patents — portfólio Truepic Inc.: [https://patents.justia.com/assignee/truepic-inc](https://patents.justia.com/assignee/truepic-inc)  
- Fourier Transform para detecção de padrões periódicos em imagens: domínio público, literatura acadêmica extensa  
- [C2PA Custom Assertions](https://c2pa.org/specifications/specifications/2.0/specs/C2PA_Specification.html#_custom_assertions)  
- INPI (Instituto Nacional da Propriedade Industrial) — busca de patentes BR: [https://busca.inpi.gov.br](https://busca.inpi.gov.br)

