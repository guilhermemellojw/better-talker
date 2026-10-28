# Card-first fase 4 — oratória no card e qualidade (2026-10-28)

- Branch: `feat/v2.9-card-fase4` (base `d820362`).
- Suíte: **2.614 testes / 0 falhas**. `lintRelease`: **verde**.
- Validação: tablet Xiaomi `25040RP0AL` (Android 16/API 36), A34 ocupado.

## O que mudou

### Oratória de texto → card

- `Route.Oratory` não pede mais `EDIT_PROPOSAL`/JSON.
- `OratoryGeneration.buildCardPrompt` usa a mesma estrutura S-34/refs/modo, mas
  pede o texto aplicável em `<texto-do-card>` e “um card por resposta”.
- O alvo é capturado **antes** da chamada (`route.sectionId` ou seção em foco);
  o modelo não escolhe seção.
- `suggestedAction=REPLACE` é gravado no payload para “reescreva”; a UI mostra
  “Sugestão: substituir o texto atual (com confirmação)” e coloca **Substituir**
  como ação primária. A confirmação explícita continua obrigatória.
- O gate de fidelidade/verifier da oratória continua rodando sobre o texto do
  card; se houver remoções, o texto final é o verificado. Avisos vão para o
  notice do card.
- `ProposalUi` deixou de ser a saída do texto oratório; fica para edição
  cirúrgica.

### Qualidade

- **“Ainda não gerei nenhuma parte”**: refinamentos (“reescreva”, “mais
  natural”, “resuma”) agora semeiam a sessão oratória com o **conteúdo atual
  da seção** quando não há geração anterior. A seção com conteúdo vira a base.
- **Referências vazias**: não era resposta vazia; era uma mensagem `refs`
  (card “Referências da nota”). O payload agora também leva `text`, e a
  métrica deve ler `kind=refs`, não `text` vazio.
- **Explicação/análise**: `ChatRouter.allowsCard()` impede card em rotas de
  explicação/orientação/meta-linguagem. Se o modelo emitir marcador, ele é
  removido e o conteúdo fica na conversa.
- **Sugestão/ilustração**: a regra única ganhou o critério “parágrafo,
  transição ou ilustração PRONTA → card; ideia/lista/análise → conversa”.

## Validação no tablet

Nota “# 192...”, seção Introdução (2.115 chars de conteúdo).

- `reescreva esse texto de forma mais curta`:
  - rota `ORATORY mode=INTRODUCTION action=REPLACE`;
  - card com 1.026/1.139 chars, alvo da seção;
  - `suggestedAction=REPLACE`, confirmação exibida;
  - `card|replaced|confirmed=true|chars=1026`;
  - undo restaurou os 2.115 chars.
- `resuma este ponto em uma frase`:
  - rota `ORATORY mode=INTRODUCTION action=REPLACE`;
  - card de 101 chars.
- `escreva um paragrafo de abertura`:
  - rota `ORATORY mode=INTRODUCTION action=INSERT`;
  - card de 461/597 chars.
- `me de uma sugestao para este ponto`:
  - rota `GENERAL`, card de 1.071/1.423 chars.
- `me de uma ilustracao para este ponto`:
  - em uma amostra virou card (1.277 chars); em outra ficou como ideia na
  conversa — o critério “pronta vs. ideia” está sendo aplicado.
- `explique o que e vida eterna`:
  - rota `GENERAL reason=pedido de explicação/análise`;
  - o modelo emitiu marcador, mas `allowCard=false` o removeu;
  - o DB final não tem chaves `card*`.
- `quais referencias apoiam este ponto?`:
  - rota `STRUCTURAL_QUERY`, mensagem `refs` com `text=Referências da nota`;
  - sem resposta vazia.
- `me de uma transicao para este ponto`:
  - no tablet, a nota não tinha S-34 carregado na sessão (`section=—`), então o
  app pediu quais pontos; sem card.
  - o caminho com alvo S-34 usa o mesmo `generateOratory`/card; falta uma
  amostra com S-34 carregado.

### Números da amostra

- Pedidos de texto: 8 (7 com alvo utilizável no tablet + transição sem S-34).
- Cards emitidos: 6.
- Cobertura: **6/7 = 86%** dos pedidos com alvo; **6/8 = 75%** incluindo a
  transição sem alvo S-34.
- Propostas de texto geradas/usadas nessa rota: **0/0**.
- Alvo errado: **0** (todos os cards da Introdução em `650ec896...`).
- Explicação/refs: sem card, como esperado.
- `replaced`: 1; `undone`: 1; `superseded`: 6.

## Ainda pendente

- Amostra de **transição** com S-34 carregado e próximo ponto.
- Eventos `USER_PICK`/`TARGET_MISSING`/`failed`/`transform`/`proposal_text|used`
  no corpus da fase 4; os estados/atomicidade já foram validados no A34, mas a
  amostra de telemetria ficou zero nesta rodada.
- Repetir a medição com as definições `text_requests`, `card_coverage`,
  `card_adoption`, `proposal_generated/adoption` e `applied_text_ops`.
- Tablet/keystore continuam gates da `v2.9.0`.
