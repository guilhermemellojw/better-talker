# Card-first fase 4 — oratória no card e qualidade (2026-10-28)

- Branch: `feat/v2.9-card-fase4` (base `d820362`).
- Suíte: **2.617 testes / 0 falhas**. `lintRelease`: **verde**.
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
- `me de uma transicao para este ponto` (seção BODY com S-34 carregado):
  - rota `ORATORY mode=TRANSITION section=sec-1`;
  - card de 771 chars;
  - alvo **próxima seção** `COMO COMEÇAR A ANDAR...` (`996f25c2...`);
  - `suggestedAction=APPEND`, status `AVAILABLE`;
  - `Usar este texto` → 793 chars na próxima seção; undo restaurou 0.
  - Sem S-34 carregado o app pede os pontos (caso anterior); o seletor
  pré-geração ainda é uma pendência.

### Métricas da amostra (definições corrigidas)

| Métrica | Valor |
|---|---|
| `text_requests` | 8 |
| `cards_emitted` | 7 (inclui a transição com S-34) |
| `card_coverage` | **7/8 = 87,5%** |
| `card_adoption` | 2/7 = 29% (1 replace + 1 append) |
| `proposal_generated` | 0 |
| `proposal_adoption` | n/a (0 geradas) |
| `card_share_of_text_outputs` | 7/(7+0) = **100%** (dos outputs de texto, não dos pedidos) |
| `applied_text_ops` | 2 (replace + append) |
| `no_target_rate` | 0/8 na amostra com S-34; transição sem S-34 pede pontos |
| `clarified_then_card` | 1 (transição sem S-34 → depois card com S-34) |

- Alvo errado: **0**; todos os cards da introdução/transição usaram o alvo
  capturado (`650ec896...` ou a próxima `996f25c2...`).
- Explicação/refs: sem card, como esperado.
- `replaced`: 1; `undone`: 1; `superseded`: 6 **eventos**.

### Leitura do `card_adoption` (2/7 = 29%)

- A amostra tem 7 cards; 2 operações aplicadas (1 replace + 1 append).
- A maior parte dos não aplicados foi **iteração no mesmo alvo**: o usuário
  pediu “mais natural”, “outra versão”, “mais direta” e o card novo marcou o
  anterior como `SUPERSEDED`. Isso é o fluxo esperado, não rejeição de
  qualidade.
- Não houve `dismissed`/`stale` nessa amostra; o número não mede “não gostei
  do texto”. Para separar iteração de rejeição, a telemetria precisa de um
  motivo explícito de dispensa/feedback (fase 4.2 opcional).
- `card_coverage` continua sendo `cards_emitted / text_requests` (7/8 = 87,5%);
  `card_share_of_text_outputs` não deve ser lido como cobertura de pedidos.
- **Superseded:** com N cards no mesmo alvo, os N-1 anteriores viram
  `SUPERSEDED` e o último fica `AVAILABLE`. O `6` é contagem de eventos, não
  estados finais. Testes puros: 3 cards → 2 `SUPERSEDED` + 1 `AVAILABLE`; alvos
  diferentes → nenhum; `APPLIED` não vira `SUPERSEDED`.

## Ainda pendente

- Seletor de alvo **pré-geração** para transição sem S-34; hoje o app pede os
  pontos, não abre o `USER_PICK` antes de gerar.
- Eventos `USER_PICK`/`TARGET_MISSING`/`failed`/`transform`/`proposal_text|used`
  no corpus da fase 4; os estados/atomicidade já foram validados no A34, mas a
  amostra de telemetria ficou zero nesta rodada.
- Corpus final com 20–30 turnos usando as definições acima; esta amostra de
  tablet é de 8 pedidos e serve de checkpoint.
- Tablet: C2 refs e M1 stop/retry validados neste build; card-first 1–3 já foi
  validado no A34. Keystore continua gate da `v2.9.0`.
