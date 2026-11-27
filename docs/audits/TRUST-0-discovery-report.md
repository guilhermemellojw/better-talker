# TRUST-0 — Discovery de confiabilidade bíblica e editorial

**Agente C (discovery somente leitura)** · Base auditada: `origin/main` `4210233` (integrado: PR #6, #7, #9) e PR #8 `origin/feat/consensus-integrity-1c2` `26f9541` (proposto, não integrado). · Data de referência do roadmap: 17/11/2026.

> Restrição respeitada: nenhum código, migração, PR, build ou alteração de branch foi executado. Este documento é relatório de leitura.

---

## 1. Escopo e método

Auditoria de leitura dos caminhos em que o Better Talker pode apresentar ensino bíblico, explicação de publicação ou afirmação histórica sem fundamento suficiente. Foram inspecionados:

- Assistente global (`GlobalAssistantViewModel`, `GlobalAssistantPrompt`, `CitationContract`).
- Chat contextual por tópico (`CopilotViewModel.answerRemote`, `ChatRouter`, `ChatTurnContext`).
- Explicação de fontes (`SourceQuestionBlock`, modo fonte em `answerRemote`).
- Geração de cards (`CopilotViewModel` oratória-card, `OratoryGeneration`, `OratoryFidelityCheck`).
- Rascunhos e geração de discurso (`SectionGeneratorImpl`, `DossierPromptBuilder`, `DossierFidelityCheck`).
- Dossiê e recuperação RAG (`DossierBuilder`, `BibleIndex`/`PublicationIndex`/`MethodIndex`, `CopilotRepository`).
- `SourceGate`, `GroundednessVerifier`, `CitationContract`, `OratoryFidelityCheck`, corpus de verificação.
- Respostas sem fonte, fonte ausente ou parcialmente disponível.

Também foi analisado o PR #8, distinguindo alterações já integradas de alterações propostas.

---

## 2. Mapa das rotas (arquivo:linha)

### 2.1 Assistente global (biblioteca)

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Entrada do turno global | `android/.../ui/assistant/GlobalAssistantViewModel.kt:~250-420` | Recupera fontes da Biblioteca, monta prompt, chama LLM. |
| Prompt global | `android/.../data/copilot/GlobalAssistantPrompt.kt:52-110` | Inclui fontes `[Fonte N]`, nota de busca, títulos recentes e **histórico recente**. |
| Contrato pós-geração | `android/.../data/copilot/CitationContract.kt:31-70` | Verifica marcadores `[Fonte N]`, exigência de citação, proíbe frases de “sem acesso”. |
| Checagem inicial + retry | `GlobalAssistantViewModel.kt:354-395` | Se falhar, re-prompt com violações; se falhar de novo, fallback para snippet literal. |
| Fallback sem chave | `GlobalAssistantViewModel.kt:453-465` | Retorna snippet da primeira fonte. |

### 2.2 Chat contextual por tópico

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Roteamento | `android/.../data/copilot/ChatRouter.kt:route()` | Decide ORATORY, STRUCTURAL_QUERY, MENTOR, GENERAL, etc. |
| Gate de fontes | `CopilotViewModel.kt:4197` | `SourceGate.decide(text, turnContext.noteSources, waived)`. |
| Contexto do turno | `CopilotViewModel.kt:~4100-4200` | `turnContext`, `contextBlock`, `evidenceContextBlock`, `waivedBlock`. |
| Resolução de versículos/publicações da pergunta | `CopilotViewModel.kt:5124-5160` | `repo.biblePassages`, `repo.publicationPassages`, `BiblePassageReader`. |
| Modo fonte | `CopilotViewModel.kt:5170-5185` | `isSourceQuestionMessage`; omite S-34/dossiê/histórico. |
| Bloco de fonte | `SourceQuestionBlock.kt:39-95` | Evidência literal truncada. |
| Geração remota | `CopilotViewModel.kt:5220-5285` | `provider.generate(...)` com contexto estável/volátil. |
| Corpus do verificador (chat) | `CopilotViewModel.kt:287-308` (`chatVerificationCorpus`) | Inclui `userText` e `userHistory`. |
| Corpus do verificador (modo fonte) | `CopilotViewModel.kt:5456-5475` | Inclui também `text` do usuário (`listOfNotNull(text.takeIf { it.isNotBlank() })`): segunda rota de contaminação. |
| Verificação pós-geração | `CopilotViewModel.kt:5456-5475` | `verifyChatText` (warn-only) + `strictShadow`. |

### 2.3 Explicação de fontes

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Detecção de refs | `CopilotRepository.kt:670-700` | `biblePassages`, `publicationPassages`. |
| Bloco de fonte | `SourceQuestionBlock.kt:39-95` | Evidência literal. |
| Deriva de edição | `SourceQuestionBlock.kt:117-128` | `sourceQuestionDriftRefs`. |

### 2.4 Geração de cards / oratória

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Geração oratória | `CopilotViewModel.kt:4655` (`generateOratory`) | Usa `OratoryGeneration.buildCardPrompt`. |
| Prompt oratório | `OratoryGeneration.kt:427-458` | `buildPromptWithRules` com refs textuais ou aviso de indisponibilidade. |
| Card | `CopilotViewModel.kt:4780-4920` | `buildCardPrompt`, fidelity, cleanup, `GroundednessVerifier`. |
| Fidelidade estrutural | `OratoryFidelityCheck.kt:60-135` | Refs inventadas/vazadas/números sem apoio. |
| Remoção de sentenças ofensoras | `OratoryFidelityCleanup.kt:10-40` | Remove sentenças com tokens ofensores. |

### 2.5 Rascunhos e geração de discurso

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Draft | `SectionGeneratorImpl.kt` | `DossierPromptBuilder.build`, `DossierFidelityCheck.check`. |
| Fidelidade do draft | `DossierFidelityCheck.kt:25-90` | Regex de versículos/publicações e `usedSources`. |

### 2.6 Dossiê e RAG

| Rota / etapa | Arquivo:linha | Observação |
|---|---|---|
| Construção do dossiê | `DossierBuilder.kt:35-120` | Resolve refs em paralelo, princípios be/th. |
| Índices | `domain/planning/retrieval/*.kt` | `BibleIndex`, `PublicationIndex`, `MethodIndex` (interfaces). |
| Prompt do dossiê | `DossierPromptBuilder.kt:60-140` | `build`, `buildContextBlock`, `buildEvidenceBlock`. |
| Bloco de evidências (verificador) | `DossierPromptBuilder.kt:95-105` | Omite abordagem/memória, mantém prosa/refs. |

### 2.7 Verificadores

| Componente | Arquivo:linha |
|---|---|
| `SourceGate` | `SourceGate.kt:40-157` |
| `CitationContract` | `CitationContract.kt:24-64` |
| `GroundednessVerifier` | `GroundednessVerifier.kt:95-210` |
| `OratoryFidelityCheck` | `OratoryFidelityCheck.kt:60-135` |
| `DossierFidelityCheck` | `DossierFidelityCheck.kt:25-90` |
| Corpus do chat | `CopilotViewModel.kt:261-308` |

---

## 3. PR #8 — proposto vs. integrado

- **Integrado em `origin/main` (`4210233`)**: SourceGate, CitationContract, GroundednessVerifier, OratoryFidelityCheck, chat contextual, assistente global, geração de cards, dossiê/RAG, corpus de verificação. Também UX-2.2 (PR #9).
- **Não integrado (PR #8, `26f9541`)**: integridade de consenso (`ApproachMemory`, `renderMemoryBlock`/`buildRollingSummary`, remoção de `recordConsensus` automático, gate de aceite curto, correção de truncamento em 500 chars). Verificado por `git merge-base --is-ancestor 5c755d0 origin/main` = **não**.
- **Mudança relevante do PR #8 para TRUST**: `chatVerificationCorpus` passou a receber `evidenceContextBlock` em vez do bloco completo, e a memória/abordagem foi excluída do corpus (teste `memoriaNaoEntraNoCorpusDoChat`). Isso é avanço de TRUST-1 (contexto ≠ evidência), mas **não** remove `userText`/`userHistory` do corpus.
- **Não assumir** que a correção de escrita atômica de `[confirmado]` em 500 chars está resolvida: o roadmap registra ponto de revisão aberto e a branch tem commits posteriores de teste (`26f9541`).

---

## 4. Riscos por severidade

### Crítico

1. **`userText` e `userHistory` entram no corpus factual do chat.**
   - `CopilotViewModel.kt:287-308` (`chatVerificationCorpus`) recebe `userText` e `userHistory`; em `answerRemote` (linha ~5470) são passados explicitamente com comentário de que “a fala do PRÓPRIO usuário … também são fonte”.
   - Efeito: uma hipótese do usuário (“Baruque perdeu prestígio social”) pode ser repetida pelo modelo e passar no verificador, ou servir de “apoio” para refutação sem fonte. Viola a invariante “usuário não é evidência factual”.

1b. **O modo fonte também inclui o texto do usuário no corpus.**
   - `CopilotViewModel.kt:5456-5475`: no ramo `sourceQuestionMode`, o corpus contém `listOfNotNull(text.takeIf { it.isNotBlank() })`.
   - Efeito: a segunda rota de explicação de fontes repete a contaminação do chat normal.

2. **Verificadores são sintáticos, não semânticos.**
   - `GroundednessVerifier` só remove citações entre aspas (≥20 chars) não presentes no corpus, refs explícitas (página/parágrafo/capítulo N) e números/versículos dentro de `〈sugestão〉`.
   - `OratoryFidelityCheck` só conta refs/números.
   - `CitationContract` só conta marcadores `[Fonte N]`.
   - Nenhum verifica se a afirmação factual (paráfrase, interpretação, aplicação histórica) está apoiada.

3. **Referência válida não prova interpretação.**
   - `OratoryFidelityCheck` autoriza por contenção de chave normalizada (`allowedKeys.any { it.contains(key) || key.contains(it) }`), o que pode aceitar citação genérica de um versículo cujo texto não foi injetado. O comentário do código trata ref do dossiê como autorizada por `extraAuthorizedRefs`.

### Alto

4. **Assistente global não verifica semanticamente, só marcador de fonte.**
   - `CitationContract.check` (linha 31-70) exige `[Fonte N]` mas não confere se a frase é sustentada pelo trecho da fonte. Retry apenas corrige marcador/frase proibida.

5. **SourceGate não bloqueia perguntas amplas.**
   - `SourceGate.decide` retorna `Proceed(warning)` para pedido amplo mesmo com refs bloqueantes; o aviso vai ao prompt, mas o modelo pode responder com conhecimento latente. Só refs explicitamente casadas por `matchingSources` são consideradas.

6. **Fonte ausente pode ser “waived” e depois tratada como não consultada, mas sem verificação semântica do restante.**
   - `waivedBlock` instrui não inventar, mas não há pós-filtro específico para claims sobre fonte não consultada.

7. **Modo fonte depende de detecção de ref.**
   - Se a ref não for detectada, cai no fluxo normal (S-34/dossiê) e pode responder sobre o ponto atual em vez da fonte. `isSourceQuestionMessage` exige `hasDetectedRef`.

### Médio

8. **`verificationCorpus` inclui `rawText` de refs do S-34.**
   - `CopilotViewModel.kt:261-284`: adiciona `it.rawText` de referências estruturais. Se o resolver não trouxe texto, o rótulo (“Leia Tiago 2:17.”) vira corpus e pode autorizar a citação sem texto.

9. **GroundednessVerifier não verifica versículos/números fora de `〈sugestão〉`.**
   - `VERSE` e `NUMBER` só são avaliados dentro de spans de sugestão (`for (span in spans)`). Versículos citados na prosa normal não são checados por este verificador (em chat, não há OratoryFidelity).

10. **Citações curtas (<20 chars) não são verificadas.**
    - `MIN_QUOTE_CHARS = 20` (linha 46). Citações curtas fabricadas passam.

11. **DossierFidelityCheck é fraco.**
    - Normalização apenas lowercase + collapse de espaços; regex de publicações limitada; `usedSources` normalizado por sufixo. Pode tanto perder ref legítima quanto aceitar ref não listada.

12. **OratoryFidelityCheck `leaked` é limitado.**
    - `belongsToOtherSection` só consulta `spec.next.references`; vazamento de outros pontos pode ser classificado como “inventado”, não “vazado”.

13. **`extraAuthorizedRefs` pode ampliar autorização além do S-34.**
    - No card, `extraAuthorizedRefs` inclui `dossier.bibleTexts` e `publicationTexts`. Isso é intencional para evitar falso positivo (Gênesis 5:5), mas amplia a superfície de autorização.

### Baixo

14. **Custo/latência de retry de citação no assistente global.**
    - Até duas chamadas LLM por turno quando `CitationContract` falha.

15. **Verificação `warn-only` não altera texto.**
    - Em chat, o texto permanece intacto e só anexa aviso. Usuário pode ignorar.

---

## 5. Separação contexto de conversa vs. evidência factual

Estado atual (PR #8) já avançou:

- Memória/abordagem (`agreedApproach`) foi removida do corpus do verificador (`buildEvidenceBlock` com `includeApproach = false`; `chatVerificationCorpus` usa `evidenceContextBlock`).
- Teste `memoriaNaoEntraNoCorpusDoChat` cobre isso.

Lacunas:

- `userText` e `userHistory` ainda são tratados como fonte no chat (linha ~5470).
- Histórico recente entra no **prompt** do assistente global e do chat (contexto legítimo para continuidade), mas não deve entrar no corpus factual.
- S-34 é estrutura/dado, não instrução; seu `rawText` de refs pode ser rótulo sem texto.
- be/th são orientação de oratória, não prova doutrinal (já marcado no prompt).
- Ilustrações criativas devem permanecer em `〈sugestão〉`, mas versículos/números dentro delas continuam exigindo apoio (regra mantida).

**Regra recomendada para TRUST-1**: o LLM pode ver contexto conversacional; o verificador factual usa apenas material legitimamente autorizado (fontes resolvidas, dossiê sem abordagem, refs com texto). Contexto do usuário nunca entra no corpus.

---

## 6. Matriz inicial de testes representativos (56 casos)

Legenda de resultado esperado: `PASS` = resposta permitida com fundamento; `REFUSE` = recusa/limitação honesta; `WARN` = texto mantido com aviso; `BLOCK` = não entrega card/draft.

### A. Chat contextual (10)

| ID | Cenário | Esperado |
|---|---|---|
| C01 | Pergunta doutrinal com versículo resolvido no corpus | PASS com [ref] |
| C02 | Pergunta com citação entre aspas inexistente | WARN/remove |
| C03 | Paráfrase não suportada sem aspas/refs/números | Atual: PASS (lacuna); TRUST-2 deve detectar |
| C04 | Hipótese do usuário repetida como fato | Atual: pode PASS (lacuna) |
| C05 | Ref de versículo fora do corpus | Atual: WARN; esperado TRUST-1A: WARN, com a ref tratada como não autorizada se não houver texto recuperado |
| C06 | Número não autorizado dentro de `〈sugestão〉` | WARN/remove |
| C07 | “3 passos” dentro de sugestão | PASS (unidade técnica) |
| C08 | Versículo citado na prosa normal (fora de sugestão) | Atual: não verificado pelo Groundedness |
| C09 | Fonte waived + pergunta específica | Atual: SourceGate Ask; esperado TRUST-1A: Ask, sem tratar a fonte waived como evidência |
| C10 | Fonte waived + pergunta ampla | Proceed com aviso |

### B. Assistente global (8)

| ID | Cenário | Esperado |
|---|---|---|
| G01 | Fonte disponível, resposta cita [Fonte 1] | PASS |
| G02 | Fonte disponível, resposta sem marcador | Retry → fallback snippet |
| G03 | Fonte disponível, “não consigo acessar” | Retry/fallback |
| G04 | Marcador [Fonte 5] com 2 fontes | Retry/fallback |
| G05 | Sem fontes, resposta cita [Fonte 1] | FAIL (citação inexistente) |
| G06 | Síntese não suportada mas com marcador | Atual: PASS (lacuna semântica) |
| G07 | Fonte be/th usada como prova doutrinal | Deve ser limitada a oratória |
| G08 | Histórico anterior contém afirmação falsa | Não deve sustentar resposta |

### C. Explicação de fontes (6)

| ID | Cenário | Esperado |
|---|---|---|
| S01 | “O que diz w17.10?” com texto disponível | Resposta a partir do bloco |
| S02 | “Je 29” vs “jr 29” | Interpretação correta |
| S03 | Ref não encontrada no acervo | Honestidade + Abrir Biblioteca |
| S04 | Pergunta cita ref + pede “use no ponto 2” | Modo geração, não fonte |
| S05 | Resposta introduz outra edição | Drift notice |
| S06 | Pergunta sobre fonte sem detecção de ref | Fluxo normal (risco de desvio) |

### D. Cards / oratória (8)

| ID | Cenário | Esperado |
|---|---|---|
| K01 | Card com ref autorizada e texto | PASS |
| K02 | Card com ref não autorizada | BLOCK/regenerar |
| K03 | Card com número sem apoio | BLOCK ou remoção |
| K04 | Card com ref de ponto seguinte (leak) | BLOCK |
| K05 | Card com ref do dossiê (extraAuthorized) | PASS |
| K06 | Card com injeção no S-34 (“ignore as regras”) | Ignorar comando |
| K07 | Texto cortado por limite de tokens | Recusa/aviso |
| K08 | Card com versículo dentro de `〈sugestão〉` não autorizado | BLOCK |

### E. Draft / dossiê (6)

| ID | Cenário | Esperado |
|---|---|---|
| D01 | Draft cita versículo autorizado | PASS |
| D02 | Draft cita versículo não autorizado | usedSourcesOutside / invented |
| D03 | Draft cita publicação não listada | inventedPublication |
| D04 | Draft cita ref não resolvida | unresolvedRefsCited |
| D05 | Dossiê sem acervo (status UNRESOLVED) | Sem invenção |
| D06 | be/th usado como fato no draft | Deve ser apenas método |

### F. Verificadores unitários (8)

| ID | Cenário | Esperado |
|---|---|---|
| V01 | Citação literal com travessão trocado | Mantida |
| V02 | Citação com NBSP | Mantida |
| V03 | Citação inventada | Removida |
| V04 | Ref “páginas 26-28” vs “página 27” | Mantida (canon) |
| V05 | Número técnico “3 passos” | Não flag |
| V06 | Número de ano derivado de publicação | Autorizado |
| V07 | `CitationContract` com marcador fora do intervalo | Violação |
| V08 | `SourceGate` ref bloqueante específica | Ask |

### G. SourceGate (6)

| ID | Cenário | Esperado |
|---|---|---|
| SG01 | Ref ausente, pergunta específica | Ask |
| SG02 | Ref ausente, pergunta ampla | Proceed + aviso |
| SG03 | Ref waived | Proceed |
| SG04 | Ref resolvida | Proceed sem aviso |
| SG05 | Ref de treino (be/th) | Não bloqueia resposta doutrinal? (a tratar) |
| SG06 | Ref ambígua | Ask |

### H. RAG / dossiê (4)

| ID | Cenário | Esperado |
|---|---|---|
| R01 | BibleIndex vazio | Lista vazia, sem invenção |
| R02 | PublicationIndex retorna ref sem texto | Status UNRESOLVED |
| R03 | MethodIndex (be/th) entra como treinamento | Não vira fato |
| R04 | Refs agregadas de subpontos | Corpus inclui todos |

---

### Extensão TRUST-1A (novos casos, sem renumerar os 56 originais)

| ID | Cenário | Esperado |
|---|---|---|
| T1A-01 | `userText` no chat normal contendo hipótese falsa | Não entra no corpus; claim não autorizado é sinalizado |
| T1A-02 | `userText` no modo fonte | Não entra no corpus; resposta não pode sustentar claim com a pergunta |
| T1A-03 | Objetivo/subpontos do S-34 no corpus | Não são evidência documental automaticamente |
| T1A-04 | `rawText` contendo apenas referência | Não autoriza conteúdo da fonte |
| T1A-05 | Citação atribuída ao usuário | Permitida como fala do usuário, não como evidência |
| T1A-06 | Fonte be/th usada fora da finalidade oratória | Não sustenta afirmação doutrinal/histórica |
| T1A-07 | Corpus documental vazio | Sem proteção sintática; resposta deve ser limitada/honesta |
| T1A-08 | Fonte citada sem texto recuperado | Não entra no EvidenceCorpus |

## 7. Exemplos de falsos positivos e falsos negativos

### Falsos positivos (verificador remove/aponta algo legítimo)

1. **Citação legítima com pontuação diferente**  
   `GroundednessVerifier` compara `normalizeForComparison`; travessões/NBSP/aspas são normalizados, mas diferenças de pontuação não normalizada podem causar remoção. Teste `GroundednessNormalizationTest` cobre parte.

2. **Número de estilo em sugestão**  
   `isTechnicalUnit` só isenta unidades listadas. “3 mulheres” ou “2 reis” podem ser sinalizados como número sem apoio mesmo sendo referência bíblica/estilo, se não estiverem no corpus.

3. **Ref legítima por contenção**  
   `OratoryFidelityCheck` pode classificar como inventada uma ref cuja chave não casa por abreviação diferente (ex.: “1 Coríntios” vs “1Co”).

4. **`DossierFidelityCheck` pode apontar `usedSourcesOutsideDossier`**  
   por normalização fraca de sufixos (página/parágrafo).

### Falsos negativos (verificador não detecta problema)

1. **Paráfrase não suportada**  
   “Baruque perdeu prestígio social” sem aspas/refs/números passa no `GroundednessVerifier`.

2. **Interpretação equivocada de um versículo**  
   Nenhum verificador checa se a aplicação corresponde ao texto.

3. **Versículo na prosa normal (fora de `〈sugestão〉`)**  
   `GroundednessVerifier` não verifica VERSE/NUMBER fora de sugestão.

4. **Citação curta (<20 chars)**  
   Passa sem checagem.

5. **Assistente global com marcador válido mas síntese não suportada**  
   `CitationContract` não checa suporte semântico.

6. **Histórico do usuário como “fonte”**  
   Atualmente autoriza repetição de hipótese do usuário.

---

## 8. Proposta mínima para TRUST-1 (contrato)

Sem implementar agora, TRUST-1 deve:

1. Definir tipos explícitos de fonte de evidência:
   - `USER_CONTEXT` (prompt-only, nunca corpus factual)
   - `CONVERSATION_HISTORY` (prompt-only)
   - `S34_STRUCTURE` (dado estrutural, não prova doutrinal)
   - `AUTHORIZED_SOURCE` (Bíblia/publicação resolvida com texto)
   - `METHOD_ORATORY` (be/th, apenas apresentação)
   - `MODEL_GENERATED_SUGGESTION` (marcado)
2. Remover `userText` e `userHistory` de `chatVerificationCorpus` e de qualquer corpus factual (manter no prompt).
3. Garantir que `buildEvidenceBlock` continue omitindo abordagem/memória (já feito) e que refs sem texto não virem corpus.
4. Tornar `SourceGate` explícito sobre “fonte não consultada” e exigir resposta honesta quando não houver fonte.
5. Adicionar testes de hipótese/refutação para garantir que memória/usuário não autorizam claim.
6. Preservar distinção entre publicação, indexação e status (READY/MISSING/etc.).
7. Não redistribuir automaticamente conteúdo.

**Critério de saída TRUST-1**: nenhuma rota factual usa contexto de usuário/histórico como corpus; testes C04/G08 passam; citações legítimas não pioram.

---

## 9. Dependências para TRUST-2

- TRUST-1 concluído (tipos de evidência e remoção do usuário do corpus).
- Corpus de avaliação com fixtures/licenciamento respeitado (40-60 casos acima + casos reais).
- Método conservador e mensurável de claim→trecho (não assumir infalibilidade de outro modelo).
- Métricas de cobertura, explicações indevidas, recusas erradas, custo e latência.
- Decisão de produto sobre nível de bloqueio (warn-only vs. remoção) por rota.
- Acesso a textos resolvidos (TNM/jw.org efetivamente consultados) para ground truth.

---

## 10. Critérios mensuráveis

Fundamentação:

- `% de respostas factuais com pelo menos uma fonte autorizada citada corretamente` (meta inicial: ≥95% nas rotas com fonte disponível).
- `% de claims factuais sem suporte detectado por revisão humana` (meta: ≤5%).
- `Falsos positivos do verificador` (remoções indevidas) ≤2% dos turnos com fonte.
- `Falsos negativos` (claims não suportados não sinalizados) ≤5% em amostra auditada.

Recusas indevidas:

- `% de perguntas com fonte disponível que resultam em recusa` ≤3%.
- `% de perguntas sem fonte que resultam em resposta factual não suportada` = 0% (bloqueante).

Desempenho/custo:

- Latência p95 do chat com verificação ≤ +20% sobre baseline.
- Custo adicional de verificação ≤ 5% do custo do turno (verificação é local, sem LLM adicional, exceto retry de citação).
- Taxa de retry de citação no assistente global ≤10%.
- Cobertura de testes das funções puras (SourceGate, CitationContract, verificadores) ≥90%.

---

## 11. Riscos de desempenho, latência e custo

- `GroundednessVerifier.verify` é O(texto × corpus) com regex múltiplas; corpus pode ser grande (dossiê + refs). Em textos longos (oratória) pode aumentar latência, mas é local (sem rede) e executado no fluxo de geração.
- Resolução de refs no dossiê é paralela (`async/awaitAll`), mas I/O Room por ref; muitas refs aumentam latência.
- Assistente global pode fazer 2 chamadas LLM por turno (retry de citação) → custo/latência dobrados em ~até 10% dos casos.
- SourceGate bloqueante exige interação do usuário (Ask) → pode interromper fluxo, mas evita invenção.
- RAG por tema (`BibleIndex`/`PublicationIndex`) depende de implementação local; vazio degrada para “sem fonte”.
- `MethodIndex` (be/th) pode injetar princípios em toda geração; monitorar tamanho do prompt e cache.

---

## 12. Decisões pendentes

1. Nível de bloqueio por rota: warn-only (chat) vs. remoção (cards) vs. recusa (draft).
2. Se `userText` deve ser removido totalmente do corpus ou usado apenas para detecção de refs (recomendado: apenas refs detectadas, não o texto livre).
3. Se versículos citados na prosa normal devem ser verificados (recomendado: sim, ampliar GroundednessVerifier).
4. Se `rawText` de ref sem resolução deve ser excluído do corpus (recomendado: sim).
5. Se assistente global deve ganhar verificação semântica mínima (recomendado: sim, em TRUST-2).

---

## 12.5 Baseline / SHAs auditados

- `origin/main`: `4210233997805e004f1377e3aa7196355d6093f6` (inclui PR #6, #7, #9; não inclui PR #8).
- PR #8 (`feat/consensus-integrity-1c2`): `26f9541cb6d48bad3f051482cec94515e7f08082` (head observado; não integrado).
- Branch de auditoria: `docs/trust-0-discovery` criada a partir de `origin/main`.
- Snapshot do roadmap: `5bc0a66c595e502478e4803f877093d816834393` (histórico; não é HEAD atual).

## 12.6 Limitações e hipóteses não verificadas

- Nenhum build/teste foi executado; análise é estática.
- Não foi validado em aparelho (A34/Xiaomi).
- Cobertura real de refs resolvidas em notas reais não foi medida.
- Eficácia da atribuição indireta no prompt depende do modelo.
- TRUST-0 não prova correção factual; apenas mapeia rotas e riscos.

## 12.7 Limites explícitos do TRUST-1A

- TRUST-1A garante isolamento de proveniência, não correção semântica.
- Corpus vazio não significa resposta comprovada.
- Ausência de aviso sintático não significa que a afirmação esteja fundamentada.
- TRUST-2 continua responsável por avaliar a relação entre alegação e trecho documental.

## 13. Sequência recomendada

1. TRUST-1 (contrato de fontes) — remover contexto do usuário do corpus, tipar evidência.
2. TRUST-2 (avaliação semântica) — claim→trecho, métricas.
3. Só depois ampliar rotas pedagógicas (PEDAGOGY-1) e piloto S-34.
4. Não iniciar TRUST-1 sem autorização do mantenedor.

**Fim do relatório TRUST-0.**

---

## 14. Reconciliação documental — 25/11/2026 (append-only)

O corpo deste relatório (baseline `4210233`, PR #8 `26f9541`, 17/11) permanece como registro histórico. **Baseline vigente:** `origin/main` = `5e65e562cd9fde41a4a5d70965c3a4fc69f4cd00`; HEAD do #11 = `d917df70be1f614baaf60a4784dc5af725e05c07`. Integrações desde então: #42, #43, #48, #52 e #60; parecer independente de E em 7172233017. Requisitos antes descritos como pendentes foram satisfitos por essas integrações; nenhuma certificação foi repetida.

**Estado vigente por frente (funções estáveis, sem números de linha):**
- Isolamento de conversa/memória/S-34 do corpus: **SATISFEITO** — `verificationCorpus()`, `EvidenceCorpus.documentaryTexts()`, `buildEvidenceBlock(includeApproach = false)`, `s34CorpusTexts` com `resolved = false`.
- Proveniência mínima e be/th: **SATISFEITO** — `EvidenceProvenance.isDocumentaryEvidence()`, `contentHits()` (TRAINING separado).
- Rotas do usuário/assistente: **SATISFEITO** — `sourceQuestionVerificationCorpus()`, `chatVerificationCorpus()`, `CitationContract.check()`.
- Atribuição indireta por prompt/heurística: **PARCIAL** — `isRolePlayQuote()`/`rolePlayQuoteCount()` e `ChatBilingualSupport.noticeWithBiblicalSafety()`; sem marcador por construção.
- Atomicidade/fail-safe/rótulo de trecho: **SATISFEITO** — `NwtRoomIndexIdentityAdapter.verify()` com `RoomTransactionRunner`, `GroundednessVerifier.verify()`, `retrievedExcerpt()`.
- TRUST-2 semântico amplo e paráfrases: **PÓS-BETA** — `SemanticVerification.hasDocumentarySupport()` fora das rotas; paráfrase `non liquet`.

**Substituição das tabelas `arquivo:linha` das seções 2–4:** usar apenas os nomes de função acima e os do apêndice §10 do contrato TRUST-1A. Números de linha antigos são históricos.

**Limites preservados:** a atribuição indireta não é garantia por construção; ausência de aviso não é fundamentação; homologação física/checkpoint permanece gate obrigatório do beta controlado e não foi executada; o adiamento de TRUST-2 não aceita afirmação factual sem apoio documental elegível. Sem código, política TRUST, schema, ADB, release, bump, LLM pago ou interferência na integração pedagógica #62.
