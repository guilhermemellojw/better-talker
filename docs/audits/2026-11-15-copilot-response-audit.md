# Auditoria de qualidade das respostas do Copilot — 2026-11-15

**App:** 6.0.4 (78) · **Banco:** v26 · **Dispositivo principal:** A34
**Provider/modelo:** preencher (ex.: DeepSeek / deepseek-chat)
**Rede:** preencher (Wi-Fi / 4G)
**Nota de teste:** preencher (id/título, S-34 vinculado)

## Objetivo

Medir, antes de mexer no prompt, se a resposta do Copilot:

1. trata **o ponto certo**;
2. usa **a fonte certa**, inteira e citável;
3. não inventa referência, número ou citação;
4. responde **no formato pedido**, sem meta/preâmbulo;
5. é **honesta** quando a fonte falta, está indexando ou o trecho não existe.

O 6.0.4 fechou o problema visual do streaming. Esta auditoria fecha a pergunta seguinte: **a resposta está correta e conferível?**

## Preparação

Nota controlada com:

- S-34 importado com pelo menos 3 pontos, introdução e conclusão;
- `w17.10` (artigo 23) indexado;
- `jr` (livro Jeremias) indexado, com página 29;
- `Je` (TNM) com capítulo 29;
- `be` ou `th`/`lmd` indexado como orientação;
- uma publicação **ausente** para o caso de recusa honesta;
- uma fonte em **indexação** ou `text-not-found` para os casos de status.

Antes de cada caso:

- conferir o chip de foco (`Tópico N/M` ou `Discurso inteiro`);
- abrir “Fontes e apoio” e deixar “Ver todas” disponível;
- anotar versão, modelo e rede no topo do relatório.

## Casos

| # | Pedido | Foco inicial | O que precisa passar |
|---|---|---|---|
| 1 | Desenvolva o ponto 2 | ponto 1 | chip/card no ponto 2; conteúdo do S-34 ponto 2; nada do ponto 1 |
| 2 | Faça uma conclusão | ponto 2 | alvo conclusão; sem corpo; duração ~1 min |
| 3 | Crie uma transição do ponto 1 para o 2 | ponto 1 | origem/destino corretos; sem vazar o ponto 3 |
| 4 | Desenvolva este ponto | ponto 3 | ponto 3; não herda o anterior |
| 5 | Melhore | após o caso 1 | herda ponto 2; não reinicia no ponto 1 |
| 6 | O que diz `w17.10 23 §§9-10` sobre a esperança? | ponto 2 | os dois parágrafos; `[Fonte N]` válido; reader completo |
| 7 | O que diz `jr 29`? | ponto 2 | publicação, página 29; não Bíblia |
| 8 | Leia `Je 29` | discurso inteiro | Bíblia, capítulo 29; não publicação |
| 9 | O que diz `be` lição 3? | discurso inteiro | orientação de oratória; não prova doutrinal |
| 10 | Quais fontes desta nota ainda faltam? | ponto 2 | status/ações corretos; sem inventar |
| 11 | O que diz `w99.01`? | ponto 2 | recusa honesta + Abrir Biblioteca; sem texto inventado |
| 12 | O que diz a publicação que está indexando? | ponto 2 | “indexando”; sem resposta falsa |
| 13 | O que diz `w17.10` página/parágrafo inexistente? | ponto 2 | “texto não encontrado” + reader/metadados; sem invenção |
| 14 | Discurso inteiro: quais são os pontos principais? | discurso inteiro | cobre a nota inteira; não fica só na seção |
| 15 | Resuma este ponto em 3 frases | ponto 2 | curto, sem preâmbulo/meta, sem refs inventadas |

Opcionais:

| # | Pedido | O que precisa passar |
|---|---|---|
| 16 | Desenvolva o ponto 9 | mensagem honesta; sem card |
| 17 | Capítulo/artigo longo | marca de truncamento; reader mostra tudo; resposta não finge ter visto o resto |

## Relatório por caso

Copiar este bloco por caso:

```markdown
### Caso N — <pedido>

- **Foco inicial / final:** <Tópico N/M | Discurso inteiro>
- **Rota:** <Oratory / General / Structural / ...> (logcat `ChatRouter`)
- **Card target:** <Ponto N / Conclusão / nenhum>
- **Fontes:** <N do ponto · N no prompt · N sem texto · N fora do orçamento>
- **Texto usado:** <“O Copilot viu X de Y” quando truncado>
- **Citação:** <[Fonte N] válidos; faltou citação? fora do intervalo?>
- **Fidelidade:** <refs/números/aspas inventados; aviso do verificador>
- **Forma:** <segue S-34? sem meta? tamanho pedido? corte?>
- **Honestidade:** <missing/indexing/not-found com ação?>
- **Custo:** <duração; tokens; finish_reason>
- **Resultado:** <PASS / FAIL>
- **Evidência:** <print/card/reader/log>
- **Observação:**
```

## Critérios de aceite

**Críticos — precisam ser 100%:**

- alvo S-34 correto (chip e card);
- fonte citada correta e presente no reader;
- `[Fonte N]` dentro do intervalo e abrindo uma fonte existente;
- nenhuma referência, número ou citação inventada;
- nenhuma recusa falsa (“não consigo acessar”) com fonte disponível;
- missing/indexing/text-not-found sempre com status e ação.

**Não críticos — meta ≥ 80%:**

- forma e tamanho pedidos;
- ausência de meta/preâmbulo;
- latência aceitável para o uso no A34.

## Suspeitos técnicos a confirmar

Estes pontos do código podem explicar falhas de qualidade. A auditoria deve dizer **se** e **onde** eles aparecem:

- `ChatPromptBuilder.MAX_CONTEXT_PASSAGE_CHARS = 800` e `MAX_CONTENT_SOURCES = 8`: uma fonte citada pode entrar truncada ou ser omitida.
- `legacyPassages.take(800)`: trecho antigo pode chegar ao modelo sem marca explícita de corte.
- `FocusSources`: já expõe `sentText`, `seenChars`, `truncatedChars` e motivo de omissão; confirmar se o prompt avisa o modelo sobre omissões/truncamentos.
- `GroundednessVerifier` em modo warn-only no chat: hallucination pode sobreviver com aviso.
- `checkCitations` contra corpus truncado: pode dar falso positivo/negativo.
- `stripMetaPreamble` só na inserção: “Aqui está…” pode aparecer na resposta do chat.
- `SourceGate`: confirmar se a decisão de bloquear/continuar é compreensível e se a ação oferecida resolve.

Referências:

- `android/app/src/main/java/com/bettertalker/app/data/copilot/ChatPromptBuilder.kt`
- `android/app/src/main/java/com/bettertalker/app/data/copilot/FocusSources.kt`
- `android/app/src/main/java/com/bettertalker/app/data/ai/CitationCheck.kt`
- `android/app/src/main/java/com/bettertalker/app/data/llm/GroundednessVerifier.kt`
- `android/app/src/main/java/com/bettertalker/app/ui/copilot/CopilotViewModel.kt`
- `android/app/src/main/java/com/bettertalker/app/ui/copilot/ChatFocusSources.kt`

## Coleta

Na UI:

- chip de foco;
- “Fontes e apoio” → resumo, “Ver todas”, “Texto usado”, “O Copilot viu X de Y”;
- card (`Destino`, “Recolher/Ver”);
- reader da fonte;
- duração e aviso de truncamento.

No logcat (filtros):

- `ChatRouter` — rota e motivo;
- `SourceGate` — decisão de fontes;
- `CopilotLLM` — `generation|...`, `focus|...`, flags do verificador;
- `CopilotM1` — turno/parcial, quando necessário.

## Triagem

| Severidade | Exemplo | Ação |
|---|---|---|
| **P0** | alvo errado, fonte errada, invenção, recusa falsa | corrigir antes de qualquer release; 6.0.5 se o gate dos amigos estiver ativo |
| **P1** | fonte truncada sem aviso, citação inválida, honestidade parcial | corrigir no 6.1 |
| **P2** | forma/tamanho/meta, latência | 6.1 ou backlog |

## Resultado

Preencher após a execução:

| # | Alvo | Fonte | Citação | Fidelidade | Honestidade | Forma | Resultado |
|---|---|---|---|---|---|---|---|
| 1 |  |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |  |
| 6 |  |  |  |  |  |  |  |
| 7 |  |  |  |  |  |  |  |
| 8 |  |  |  |  |  |  |  |
| 9 |  |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |  |
| 11 |  |  |  |  |  |  |  |
| 12 |  |  |  |  |  |  |  |
| 13 |  |  |  |  |  |  |  |
| 14 |  |  |  |  |  |  |  |
| 15 |  |  |  |  |  |  |  |

## Saída da auditoria

1. Lista dos casos FAIL, com severidade.
2. Top 3 padrões (ex.: fonte omitida, citação errada, alvo herdado).
3. Correções propostas, com teste puro quando possível.
4. Reexecução dos casos afetados + casos críticos.
5. Decisão: 6.1 ou 6.0.5.

**Regra:** não alterar prompt, budget ou verifier antes de ter o baseline. Sem baseline, não há como provar melhoria.
