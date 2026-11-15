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

## Execução 2026-11-15 — A34 público 6.0.4 (78)

**Nota:** #35 — “É possível viver para sempre? O que você precisa fazer?”
**Foco inicial:** Tópico 3/7 — “COMO A VIDA ETERNA FOI PERDIDA” (ponto 2).
**Provider/rede:** DeepSeek via app, Wi-Fi.

| # | Caso | Resultado | Evidência |
|---|---|---|---|
| 1 | Desenvolva o ponto 2 | **PASS com aviso** | Chip foi para Tópico 3/7; card `Destino: COMO A VIDA ETERNA FOI PERDIDA`; 441 palavras; card aberto. Aviso `referências sem apoio: Gênesis 5:5` — o log `focus ref` mostra Gênesis 5:5 como ref do dossiê do ponto 2; provável falso positivo do verificador. |
| 6 | O que diz `w17.10 23 §§9-10`? | **FAIL** | `ChatRouter` classificou como `STRUCTURAL_QUERY topic=REFERENCES section=10`; a resposta falou do ponto 2/S-34 e do Despertai! 12/08, não dos §§9-10. `SourceGate` detectou `w17.10 23 §§9-10`, e 1 trecho da publicação foi injetado — mas o modelo não o usou. |
| 7 | O que diz `jr 29`? | **FAIL** | Rota `GENERAL`; resposta interpretou “Jeremias 29” (Bíblia) e ofereceu Despertai! 12/08, não a página 29 do livro `jr`. 1 trecho de `jr 29` foi injetado e ignorado. |
| 8 | O que diz `Je 29`? | **FAIL** | Rota `GENERAL`; resposta falou do ponto 2/S-34 e tratou Jeremias 29 como “apoio extra”. Nenhum bloco de capítulo bíblico foi injetado (o injetor cobre versículo, não capítulo). |
| 11 | O que diz `w99.01`? | **PASS com ressalva** | Rota `STRUCTURAL_QUERY/REFERENCES`; a resposta foi honesta (“não tenho o texto, não vou inventar”) e não citou conteúdo falso. Faltou ação direta “Abrir Biblioteca”; o texto só orienta a abrir a edição. |

### Achados

1. **Rota sequestrada por `w\d{2}`.** `ChatRouter.TOPIC_REFERENCES` inclui `w\d{2}`; qualquer pergunta com `w17.10`/`w99.01` vira `STRUCTURAL_QUERY/REFERENCES` antes do chat/RAG.
2. **`§§9-10` virou “ponto 10”.** `OratorySession.requestedPoint` usa o fallback de número solto e leu o `10` do range de parágrafos.
3. **O dossiê/S-34 domina o prompt.** A fonte citada na pergunta entra como bloco secundário no user; o system abre com “CONTEXTO DO DOSSIÊ (seção em foco)” e o ponto atual. O modelo responde sobre o ponto 2 mesmo quando a pergunta é sobre `jr 29`/`Je 29`.
4. **Capítulo bíblico não é injetado.** `biblePassages` resolve versículos (`Je 29:11`), não capítulo (`Je 29`). O reader de capítulo existe, mas o chat não usa.
5. **Range de publicação pode ser truncado.** `questionPublicationBlock` corta cada trecho em `MAX_QUESTION_PUB_CHARS = 600`; o reader mostra o texto completo, mas o prompt pode ver menos.
6. **Aviso de fidelidade possivelmente falso.** `Gênesis 5:5` está no dossiê do ponto 2, mas o `OratoryFidelityCheck` o marcou como “sem apoio” — porque comparava só com as refs do S-34 `ready`, não com as refs do dossiê da nota. **Corrigido no 6.0.5:** o gate recebe as refs do dossiê que foram ao prompt (`extraAuthorizedRefs`).
7. **Auto-scroll com teclado.** Após enviar, o turno nasceu abaixo da viewport e apareceu a pílula “Nova mensagem”; o chat não acompanhou até o fim enquanto o teclado estava aberto. É UX, não conteúdo.

### Correções propostas

- **P0 — router:** remover `w\d{2}` de `TOPIC_REFERENCES`; exigir frase estrutural explícita (“referências do ponto/esboço”, “quais textos o S-34 indica”). Guardar `requestedPoint` contra `§`/ranges.
- **P0 — modo pergunta-de-fonte:** quando a mensagem cita uma ref específica, montar um bloco prioritário “PERGUNTA SOBRE FONTE” com interpretação (`jr 29` = livro, p. 29; `Je 29` = Bíblia, cap. 29), texto integral e instrução para responder **dessa fonte**, não do ponto atual. Para pergunta puramente sobre fonte, rebaixar/omitir o dossiê e o S-34 do system.
- **P0 — capítulo bíblico:** usar o leitor de capítulo/faixa no injetor da pergunta, não só `findByRef` de versículo.
- **P1 — range completo:** incluir os dois parágrafos de `§§9-10`; se o orçamento não couber, marcar truncamento com `seenChars`/`originalChars` e nunca fingir que viu tudo.
- **P1 — fidelidade:** considerar as refs do dossiê da nota no corpus do `OratoryFidelityCheck` ou distinguir “não está no S-34” de “não está na nota”. **Feito:** `check(..., extraAuthorizedRefs)` recebe as refs do dossiê que foram ao prompt; o falso positivo de `Gênesis 5:5` não volta, e uma ref fora do dossiê continua sendo reportada.
- **P1 — pós-check:** se uma fonte da pergunta foi injetada e a resposta não a usou, refazer uma vez com instrução estrita; se persistir, responder de forma determinística/abrir o reader.
- **P2 — scroll:** corrigir o “followEnd” com teclado aberto; o turno novo deve entrar na viewport.

### Tablet — 6.0.4 público (pré-fix)

Nota: “Uma reação compreensível diante de uma terrível perda” (sem S-34; 6 anexos, 2 extras).
Provider/rede: DeepSeek via app, Wi-Fi.

| # | Caso | Resultado | Evidência |
|---|---|---|---|
| 6 | `w17.10 23 §§9-10` | **PASS com ressalva** | Rota `GENERAL`; 1 trecho injetado; a resposta usou o texto e disse honestamente que ele trata de Zedequias/juramento quebrado, não de esperança. Não citou `[Fonte N]` e derivou para o Ponto 1/w24.08 (fora da fonte). |
| 7 | `jr 29` | **PASS** | Rota `GENERAL`; interpretou como **livro Jeremias — Uma Mensagem de Deus para Nós, página 29**, não como capítulo 29; usou o texto da página (Zedequias) e não inventou o capítulo. |
| 8 | `Je 29` | **FAIL/PARCIAL** | Rota `GENERAL`; não injetou capítulo (só versículo); a resposta disse que não tinha o capítulo e ofereceu uma paráfrase geral. Também confundiu com a página 29 do `jr` do turno anterior. |

**Comparação A34 × tablet:** a A34 (nota com S-34) falhou 6/7/8 porque o dossiê/S-34 dominou o prompt; o tablet (nota sem S-34) passou 6/7, mas falhou o capítulo bíblico. Isso confirma as duas causas: **S-34/dossiê dominante** e **capítulo não injetado**.

### Tablet — build local do fix 6.0.5 (código da branch)

Build release assinado local, instalado por cima do 6.0.4 público (mesma assinatura/versionCode 78). Dados preservados; a UI ainda mostra 6.0.4 (ainda sem bump).

| # | Caso | Resultado | Evidência |
|---|---|---|---|
| 6 | `w17.10 23 §§9-10` | **PASS** | Fonte usada; leu o trecho de Zedequias e disse que ele não sustenta “esperança”. Na segunda execução, após o aviso de deriva, não citou Ponto 1/w24.08. |
| 7 | `jr 29` | **PASS** | Interpretação explícita “livro Jeremias — Uma Mensagem de Deus para Nós, página 29”; texto de Zedequias citado; sem confusão com o capítulo 29. |
| 8 | `Je 29` | **PASS** | Capítulo injetado pelo leitor; resposta com versículos 4, 10, 11 e 13 e aviso contra falsos profetas; sem confusão com `jr`. |
| 11 | `w99.01` | **PASS** | “Não encontrei o texto de A Sentinela de 1º de janeiro de 1999 no acervo local. Não vou arriscar citar de memória…”; ofereceu “Ver na Biblioteca”. |

O modo fonte também reduziu o prompt de ~6,4k para ~1,4–3,2k tokens de entrada nesses turnos.

**Build/validação:** 3.349 testes JVM / 0 falhas; `lintPublicAppRelease` 0 errors / 50 warnings. O build local assinado foi instalado por cima do 6.0.4 público no tablet (mesma assinatura/versionCode); a nota e o chat abriram, dados preservados. A UI ainda mostra 6.0.4 porque o bump para 6.0.5/79 será feito só no release.

### Próximos casos

Ainda faltam os casos 1–5, 9–10 e 12–15 na A34 (que está desligada). O tablet cobre os casos de fonte 6–8 e o capítulo bíblico. Antes de fechar o 6.0.5, falta:

1. rodar os casos 6–8 na A34 com S-34 (regressão do dossiê dominante);
2. revisar o vazamento de Ponto 1/w24.08 na resposta de `w17.10` (o aviso de deriva já cobre; a segunda execução saiu limpa);
3. CI da branch e, se verde, bump 6.0.5/79 + release unico.

**Roteiro A34 (S-34):**

1. backup da A34;
2. instalar o build local assinado (mesma assinatura/versionCode 78) por cima do 6.0.4 público;
3. abrir a nota #35, chat, foco no ponto 2;
4. pedir `w17.10 23 §§9-10`, `jr 29` e `Je 29`;
5. conferir: resposta usa a fonte, não o S-34; `jr` = livro/p. 29; `Je` = capítulo; `w17.10` não sustenta “esperança” se o texto for sobre Zedequias;
6. se passar, bump **6.0.5/79** e release único.

## Saída da auditoria

1. Lista dos casos FAIL, com severidade.
2. Top 3 padrões (ex.: fonte omitida, citação errada, alvo herdado).
3. Correções propostas, com teste puro quando possível.
4. Reexecução dos casos afetados + casos críticos.
5. Decisão: 6.1 ou 6.0.5.

**Regra:** não alterar prompt, budget ou verifier antes de ter o baseline. Sem baseline, não há como provar melhoria.
