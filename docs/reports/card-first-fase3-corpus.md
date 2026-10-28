# Card-first fase 3 — métricas e corpus A34 (2026-10-28)

- Branch: `exp/card-metrics` (base `dcb8f3f`, inclui atomicidade).
- Commit: `46118a2` + relatório.
- Suíte: **2.611 testes / 0 falhas**. `lintRelease`/CI: verdes.
- Escopo: **só telemetria**; nenhuma mudança de UX, prompt ou fluxo.
- Corpus: 20 turnos reais no A34, tópico `FOMOS CRIADOS...`, Android 16/API 36.

## Eventos implementados

`CardMetrics` (logcat, tag `CardMetrics`), somente metadados:

- `card|emitted|route=...|target=...|source=...|chars=...|cards=...`
- `card|applied|action=APPEND|stale=...|undo=true|chars=...`
- `card|replaced|confirmed=true|stale=...|chars=...`
- `card|undone|restored=true`
- `card|stale|action=DETECTED|APPEND|chars=...`
- `card|dismissed|reason=USER|GATE_NO`
- `card|gate|action=ACCEPT|REJECT|IGNORED|reason=...`
- `card|target|kind=NO_TARGET|picked=USER_PICK|target=SECTION`
- `card|target|kind=TARGET_MISSING|picked=NONE`
- `card|failed|stage=INSERT|UNDO`
- `card|second_ignored|count=...`
- `card|transform|target=...|source=TRANSFORM|chars=...`
- `card|superseded|target=...`
- `proposal_text|used|route=ORATORY`

As linhas são puras/testáveis (`CardMetricsTest`); nenhum texto de
usuário/card entra no log.

## Corpus — 20 turnos

| # | Pedido | Rota | Card? |
|---|---|---|---|
| 1 | “me de uma sugestao para este ponto” | chat | não (sugestão na conversa) |
| 2 | “reescreva esse texto de forma mais curta” | oratória | não (“ainda não gerei”) |
| 3 | “deixe mais natural” | oratória | não (“ainda não gerei”) |
| 4 | “outra versao” | chat | **sim** |
| 5 | “e o ponto 2?” | chat | não |
| 6 | “quais referencias apoiam este ponto?” | resposta vazia | não |
| 7 | “gere um texto para este ponto” | chat | **sim** |
| 8 | “me de uma introducao curta” | chat | **sim** |
| 9 | “explique o que e vida eterna” | chat | não |
| 10 | “o que voce acha deste esboco?” | chat | não |
| 11 | “me de uma transicao para este ponto” | oratória/ProposalUi | não |
| 12 | “me de uma conclusao curta” | chat | **sim** |
| 13 | “faca uma versao mais direta” | chat | **sim** |
| 14 | “resuma este ponto em uma frase” | oratória/ProposalUi | não |
| 15 | “quais sao os pontos principais?” | chat | não |
| 16 | “como aplicar isso na pratica?” | chat | não |
| 17 | “me de uma ilustracao para este ponto” | chat | não |
| 18 | “escreva um paragrafo de abertura” | oratória/ProposalUi | não |
| 19 | “quero uma versao mais calorosa” | chat | **sim** |
| 20 | “o que significa eternidade no coracao?” | chat | não |

### Números do corpus

- Cards emitidos: **6/20** (30%).
- Turnos com texto aplicável: **11**; cards **6/11** (55%).
  - 3/11 foram para a rota oratória/ProposalUi;
  - 2/11 ficaram na conversa (sugestão/ilustração).
- Cards `SUPERSEDED`: **5** (cada novo card no mesmo alvo marcou os
  anteriores).
- Cards aplicados no corpus: **0** até a ação manual; depois:
  - 1 append manual + undo;
  - 1 gate ACCEPT + append (teste pós-corpus);
  - total **2 aplicados**, 1 undo, 1 dismissed.
- Gate: **1 ACCEPT** válido, **0 falso positivo**; REJECT/IGNORED não
  exercitados.
- Alvo errado: **0** — todos os cards (7, incluindo o pós-corpus) miravam
  `SECTION:c987ef29...`.
- Stale: **6 detecções** após o primeiro append; o append stale com aviso
  não foi reexercitado nesta rodada.
- Propostas de texto: **3 geradas**, **0 usadas**.
- Chat exploratório: 5 pedidos de explicação/opinião/lista não viraram
  card — comportamento correto.
- Resposta vazia: o pedido de referências voltou sem texto (`len=0`);
  “reescreva”/“deixe mais natural” responderam “ainda não gerei nenhuma
  parte”. São achados de qualidade, não do card.

## Critérios de saída

| Critério | Resultado |
|---|---|
| Card cobre a maioria dos turnos com texto aplicável | **Parcial**: 6/11 = 55% (maioria mínima); 6/20 no total |
| Cards aplicados > propostas no chat | **Depende da leitura**: 2 aplicados vs 3 propostas geradas / 0 usadas |
| Zero insert no alvo errado | **Sim** (0/7) |
| Gate sem falso positivo | **1/1**, sem falso positivo; amostra pequena |
| Sem aumento de citações sem apoio | **Não medido** neste corpus |
| Oratória inalterada | **Sim**: 3 turnos na rota oratória/ProposalUi |
| Suíte + lintRelease verdes | **Sim**: 2.611/0 e lint/CI verdes |

## Leitura

- O card-first já cobre o caminho de chat quando o modelo decide escrever,
  mas ainda perde para a rota oratória/ProposalUi em “transição”,
  “resumo” e “parágrafo de abertura”.
- “Sugestão” e “ilustração” ficaram na conversa; são os dois ajustes de
  prompt/roteamento mais óbvios antes da fase 4.
- Aplicar um card torna os cards antigos do mesmo alvo `STALE` (hash
  mudou) antes de `SUPERSEDED`; o comportamento é tecnicamente correto,
  mas vale decidir se a UI deve preferir “Sugestão anterior” nesse caso.
- A resposta vazia de referências e os dois “ainda não gerei” precisam de
  investigação separada; não são causados pela telemetria.
- Recomendação: **fase 4** para limitar/aposentar a ProposalUi nos pedidos
  de texto e ajustar a decisão de card para “sugestão/ilustração”, com
  nova medição usando `CardMetrics`.
