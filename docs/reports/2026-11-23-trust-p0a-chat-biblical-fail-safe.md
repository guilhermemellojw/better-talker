# P0A — Fail-safe bíblico do chat — Cadeia de prova (P0)

**Disposição:** handoff do trabalho P0A — Fail-safe bíblico determinístico do chat: citações bíblicas sem retorno do acervo (TNM) são marcadas.

**HEAD do PR (nova branch):** `b56715de585a50be67be6e5578b304b88348e359`

---

## A. Mapa das rotas (P3 — auditoria)

Rota principal de respostas bíblicas do Mentor (Copilot chat):
1. `answerRemote` — T3: `questionVerses` (recovery TNM via `repo.biblePassages`), `questionPubs`, `bibleChapterPassage`, `structuralBibleDocuments` → corpus via `chatVerificationCorpus(...)`.
2. `answerRemote` — `verifyChatText(text, corpus)` — modo warn — notice de citações descobertas.
3. `SourceGate` — perguntas específicas — gate antes do LLM (não afetou este fix).
4. Route alternativa não-Copilot: `GlobalAssistantViewModel` (CitationContract), `BibleReaderViewModel`, `MainActivity` — sem respostas textualmente "bíblicas" no chat-Mentor — sem alteração.

**Fluxos com funções consultadas:** `SourceGate`, `GroundednessVerifier`, `CitationContract`, `ChatPromptBuilder`, `RoomBiblePassageSource`, `GenerationVerification`, `EvidenceCorpus`.

## B. Defeitos reproduzidos (P2)

1. **GAP-REPRO-1 — corpus vazio (TNM ausente/não indexado) + resposta com versículo → `notice = null`.** Em `verifyChatText(...)`, `GroundednessVerifier.verify(...)` com `sources.isEmpty()` → retorna o texto intacto sem flags — resposta apresentada SEM restrição factual bíblica. Vermelho no teste `repro_verifyChatTextSemCorpusBíblicoNãoProduzAvisoBíblico` (`assertNotNull(outcome.notice)` — falha pre-fix).
2. Corpo de chat envia o texto intacto — a restrição é o notice após; com corpus vazio nada aparece.

## C. Contrato (P4)

Novo objeto puro `ChatBiblicalSupport` (data/copilot) — P0:

- `ChatBiblicalSupport.biblicalGaps(answer, documentaryCorpus): List<BiblicalGap>` — detecta versículos bíblicos citados pela resposta que não estão no corpus documental elegível (comparação canônica via `RefDetector.detectBible` + `RefCanonicalizer.canonicalBook` — mesma base do verificador).
- `ChatBiblicalSupport.notice(gaps): String?` — notice visível: "⚠️ Citação(ões) bíblicas sem retorno do acervo local: [refs]. Use paráfrase, ou informe que consultei apenas o acervo local."
- `ChatBiblicalSupport.noticeWithBiblicalSupport(existingNotice, answer, corpus): String?` — notice final do chat = existente + biblical (apenas quando ainda não há).
- **`verifyChatText(text, corpus)`** (CopilotViewModel) — integração em um ponto: `notice = ChatBiblicalSupport.noticeWithBiblicalSupport(chatVerificationNotice(...), text, corpus)` — puro, testável, sem alteração da resposta (texto intacto — warn-only).

**Contrato esperado atingido:** sem TNM recuperada, nenhuma citação bíblica é apresentada como verificada — apenas marcada como sem retorno (além do texto intacto). BE/TH não produz gaps (não são livros bíblicos). Texto do usuário/histórico: gap (sem apoio documental). Pedagógico: sem citação bíblica = sem gap.

## D. Matriz adversarial (P2 — determinística, fixas por `ChatBiblicalSupportTest`)

| Cenário | Mecanismo | Resultado Pós-Fix |
|---|---|---|
| 1. TNM ausente | corpus vazio, resposta cite Mateus 27:26 | gap não vazio ✓ |
| 2. TNM importada, não indexada | corpus vazio (sem leitura) | gap não vazio ✓ |
| 3. Referência sem texto recuperado | ref detectada sem doc | gap não vazio ✓ |
| 4. Texto recuperado parcialmente | n/a — ferramentas de leitura propagam parcionamento (ex.: `EditionChanged`) | (precedente do UX-REF) ✓ |
| 5. Outra Bíblia disponível, TNM ausente | recovery legítimo pelo `RoomBiblePassageSource` (fallback) | sem gaps (recuperado) ✓ |
| 6. Referência inexistente citada | gap no corpus | gap não vazio ✓ |
| 7. jw.org ausente | correspondência bloqueada — sem doc | n/a (consistente) |
| 8. Texto do usuário com versículo | corpus vazio + cite user text | gap não vazio ✓ |
| 9. Histórico citando citação incorreta | corpus vazio | gap não vazio ✓ |
| 10. Memória confirmada sem fonte | outra camada (decisions) — memória ≠ documental | n/a (D1, documentado no monitoramento; cadastro de decisão exige aceite) |
| 11. Pedagógico | sem citação bíblica | sem gaps ✓ |
| 12. Oração + doutrinário | mistura BE/TH + versículo | gaps apenas do versículo sem apoio ✓ |

## E. Correção mínima (P4)

1. Novo arquivo: `ChatBiblicalSupport.kt` (+ ~70 linhas puros).
2. Edita em `CopilotViewModel.verifyChatText` (+8 linhas) — integração única.
3. Nenhum alteração de rotas/prompt/produção.

## F. Testes e CI (P5)

- Testes JVM executados: `./gradlew :app:testPublicAppDebugUnitTest` — **PASS** (15 testes do ChatBiblicalSupportTest — compile + fluxos; outras rotas intactas).
- Instrumentado compilado: `./gradlew :app:assembleAndroidTest` — **PASS**.
- Nenhuma chamada LLM (fakes/fíxados — `verifyChatText` — puro).
- JVM/instrumentado: `SIGSEGV` transitorio — retentativas documentadas; daemon stopado.

## G. Dependências

- PR #42 (Agente A, OPEN) — infra MEMORY — não bloqueia.
- PR #43 (INT-P4 — B1b/adaptação) — status: PRONTO; independente desta tarefa.
- PR #44 (Agente D, OPEN) — desacoplado.
- PRs #36/#37 (MERGED) — infra base.

## H. Veredito

**PRONTO PARA REVIEW.**

- HEAD P0A: `b56715de585a50be67be6e5578b304b88348e359` — **CI push: success/completed** (id 45280022841).
- Testes JVM + instrumentado compilado: PASS.
- Repro negativo identificado (red) e corrigido (green).
- PR: `feat/trust-p0a-fail-safe` — status aberto; aguarda review antes do merge (sem merge por scope de P0A).

**Critério de sucesso (p/ review):** cenários QA-repro estão cobertos (cenário acima) + fallback (paráfrase pedagógica) + 1 citação destrutiva identificável.

## H. Riscos reais para o beta

1. **BE/TH dobling** — de propósito, prompt + fail-safe — possível aviso excessivo; alinhado ao design (T4 no chat já exige paráfrase).
2. **Suggestion marcadores** — versículos/números isentos para comentários sugestivos — inalterado.
3. **Distinção (P0.5/P6)** — BE/TH têm categoria própria de gap? — sem categoria — menção esperada do modelo como uso de oratory de forma distinta — verificar rota na produção posteriormente (müdicao de SUACELO — para J.

---

## Referências

- `ChatBiblicalSupport.kt` — objeto puro.
- `CopilotViewModel.verifyChatText` — integração.
- `ChatBiblicalSupportTest.kt` — contratos + repros.
- Git: `b56715de585a50be67be6e5578b304b88348e359` (P0A).
- PRs: #38 (d710ca4a, MERGED), #40 (9172cd46, MERGED), #41 (299f79c7, MERGED), #42, #43, #44.
- Relatórios: `2026-11-23-qafix43-scenarios-17-18.md` (QA — PR #43).
