# Fase 18 — Nativo: Copilot Core (LLM real + pipeline integrado)

## Resumo

| Campo | Valor |
|---|---|
| Baseline real | `b3507e6` (F17 + crash-fix; spec dizia `b564771`) |
| Estado final | `NATIVE_COPILOT_CORE_STABLE_WITH_BACKLOG` |
| Testes Android | **291/291** (228 + 63 novos, 0 falhas) |
| Testes web | **231/231** (regressão verde) |
| Build Android / web | **VERDE / VERDE** |
| Lint Android | 2 erros pré-existentes, **0 novos** |
| Lint web | 6 warnings pré-existentes, 0 erros |
| E2E Android | **dispositivo físico SM-A346M**, fluxos §37-41 PASS |
| Modelo | `gemini-3.6-flash` (ver §5) |

## 1. Arquitetura antes → depois

```text
ANTES                              DEPOIS
Chat UI                            Chat UI (inalterada, F17 preservada)
  ↓                                  ↓
CopilotViewModel                   CopilotViewModel
  ↓ ChatIntent.classify (único)      ↓ inferIntent → buildTurnFor (sempre)
answerXxx determinístico             ├─ com chave → provider.generate (NOVO)
ChatPromptBuilder (só testes)        └─ sem chave → legado local (inalterado)
ContextPack (só testes)
LlmProvider inexistente            LlmProvider + GeminiProvider + Factory
F5/F6 inexistentes                  EditProposal + Verifier + ProposalCard
```

## 2. Componente | Antes | Depois

| Componente | Antes | Depois |
|---|---|---|
| `ChatIntent` | cérebro do `send()` | **mantido** para rota sem chave (desvio doc. de §46, ver §12) |
| `askScoped` | chat principal | RAG do chat agora é `RoomContextPackRepository`; `askScoped` segue nas respostas legadas |
| `ContextPack` | só testes | **produção** (`buildTurnFor` → `RoomContextPackRepository.buildPack`) |
| `ChatPromptBuilder` | só testes (`_lastPrompt`) | **produção** (consumido pelo `GeminiProvider`; teste §32 prova) |
| `LlmProvider` | inexistente | **produção** (`data/llm/`) |
| F5 | inexistente | **produção** (`data/edit/` + `ProposalCard` + accept/reject/stale/undo) |
| F6 | inexistente | **produção** (`data/verify/` + Verificar no cartão) |

## 3. Provider (§4-5)

`data/llm/LlmProvider.kt`: `LlmRequest` (text, message, history, pack, block, timeout, `responseFormat`, `editMode`, `brief`), `LlmResponse` + `Meta` (providerId, model, durationMs, attempts, offline), `ProviderError(code, providerId, attempts)`, `LlmProvider.generate()` suspend, `LlmHttpClient` injetável (fake nos testes).

`GeminiProvider`: system+user concatenados, temp 0.2, 1000 tokens, timeout 30s, retry SÓ em 429/5xx/rede (backoff 400ms×n, max 2), sem retry em 400/401/403, `CancellationException` → CANCELLED, `parseCandidateText` validado, sem chave → offline explícito. **Sem fallback silencioso**: falha do provider vira `friendlyChatError`, nunca resposta do motor legado.

## 4. BYOD + factory (§6-7)

Chave no `SettingsStore` (mesmo DataStore, `llm_api_key`; sem armazenamento paralelo). UI na tela Modelo IA (campo senha + salvar/remover). `ProviderFactory`: `create(settings)` / `createWithKey` / `useRemoteRoute` (só com chave não-branca). Nada hardcoded, nada em log/erro/telemetry (teste `chaveNuncaApareceEmErro`).

## 5. Modelo

Default `gemini-3.6-flash`. Motivo (verificado em 2026-09-26): `gemini-2.5-flash` (default web) retorna **404 aposentado** para chaves novas; `gemini-3.8-flash` funciona mas oscila (sequências de 503 + 18s de latência); `gemini-3.6-flash` responde 200 em ~3s. Mesmos parâmetros de geração — paridade de comportamento, não de nome. Web intocado (§35).

## 6. Vertical slice (§8) + RAG (§9-11) + prompt (§14-15)

`send()`: valida → posta → `inferIntent` → `buildTurnFor` (Room pack 8/4/12, factual sem training) → com chave `answerRemote` (HTTP em `Dispatchers.IO`), sem chave legado. Histórico 6/500 no prompt, nunca o banco. Prompt contém system + mensagem + histórico + foco + pack — teste `promptConstruidoChegaAoHttp` prova consumo real.

## 7. Seleção (§16-17)

`RichTextState.selection` (confirmado no AAR) → `EditorViewModel.selectedText` (volátil, cap 2000) → rota chat coleta e `copilotVm.setSelection` → prioridade seleção > bloco > corpo; rótulo e prompt usam o MESMO texto (§16: nunca rotular bloco de "seleção"). `contextLabel(..., selectedText?)` com teste.

## 8. F5 (§20-23)

`data/edit/EditProposal.kt`: tipos, FNV-1a **byte-idêntico ao web** (5 vetores travados, inclusive emoji), validate/apply atômico, `captureBaseHashes`, parser de cerca JSON com **veto de delete via LLM**, `EditHistory` (cap 50), `renderAfterText`. UI: "Criar proposta" nas respostas → cartão ANTES/DEPOIS → Verificar/Aceitar/Rejeitar/Desfazer. Apply via `NotesRepository.save` (editor re-renderiza pela via externa); stale = foco ausente do texto atual → bloqueia com mensagem humana; undo restaura snapshot.

## 9. F6 (§24-26)

`data/verify/Verifier.kt`: claims (sentenças sem partir "24.01", compostas, 7 tipos, cap 20), `extractNumbers`, `numbersOk` (ignora dígitos em refs), `refsOk`, thresholds 0.45/0.5, teto parcial p/ interpretive, training nunca autoridade, 4 estados + razões humanas idênticas ao web. Retrieval injetado (Room no aparelho, fake nos testes). Judge remoto: **não implementado** (§27, backlog).

## 10. Testes (291)

| Arquivo | N | Cobre (§) |
|---|---|---|
| `LlmProviderTest` | 21+2 | config, request/response, timeout, cancel, retry, definitivo, chave-fora-de-log, factory, pipeline, consumo do prompt (§32), isolamento (§33), seleção |
| `EditProposalTest` | 22+2 | hashes, parser, válida/inválida, stale, atomicidade, limites, delete-veto, undo/redo, `renderAfterText` |
| `VerificationTest` | 15 | claims, números, refs, 4 estados, cap interpretive, training |
| `ChatPromptParityTest` | +2 | prompt edit-proposal |
| existentes | 228 | regressão intacta |

## 11. E2E Android (SM-A346M, §§37-44)

| Fluxo | Resultado |
|---|---|
| §37 chat real ("Naturalidade?", "Melhore?") | ✅ respostas contextuais e conversacionais |
| §38 continuidade ("Melhore?" sem contexto) | ✅ continua o fio (introdução + naturalidade) |
| RAG real no aparelho | ✅ "5 conteúdo · 4 técnica" |
| Criar proposta (JSON parse) | ✅ cartão ANTES/DEPOIS |
| Verificar (F6 local) | ✅ ✓0·⚠1·?0·💡0 |
| §39 aceitar → editor alterado | ✅ DB confirma DEPOIS aplicado |
| Undo (Desfazer) | ✅ original restaurado |
| §40 rejeitar → intacto + cartão some | ✅ |
| §41 stale (editar → aceitar) | ✅ "Proposta obsoleta...", nada aplicado |
| §42 offline (rádio) | ⚠️ **não executado** — mataria o adb sem fio; caminhos offline unit-testados + erros honestos exercitados ao vivo (UNAVAILABLE/RATE_LIMIT/TIMEOUT reais) |
| §43 interrupções (Home/lock) | ✅ mesmo PID, sem crash |
| §44 force-stop/reboot de processo | ✅ mensagens, nota, chave preservados |
| §45 logs | ✅ sem FATAL/ANR/Room; só taxonomia esperada |

## 12. Bugs encontrados e corrigidos (E2E)

1. **`NetworkOnMainThreadException` no `createProposal`** (só `answerRemote` tinha `Dispatchers.IO`) — erro genérico mascarava; logs `CopilotLLM` adicionados (só código/tentativas, sem segredo).
2. **Entidades HTML quebravam o aceite** (`&ccedil;` do richeditor vs âncora em texto puro) — `unescapeHtmlEntities` + ordem web preservada + teste.
3. **Modelo default aposentado** (`gemini-2.5-flash` → 404) — `gemini-3.6-flash`.
4. **Parse sem diagnóstico** — `ParseResult.Invalid(reason)` (12 motivos).
5. **VM duplicada por rota** (comentário dizia "MESMO", mas cada rota tinha sua instância — Voltar matava a proposta) — `CopilotViewModel` ancorado na entry do editor.
6. **`Log` Android quebrava testes JVM** — logger injetável no provider.
7. **Foco incluía o título** (nunca no richHtml → stale eterno) — `_noteBody` separado.
8. **Chave corrompida por digitação** (auto-pontuação Samsung inseria espaço) — escrita via run-as + verificação byte-a-byte; documentado.

## 13. Desvio consciente de §46-47 (dois cérebros)

O legado (`ChatIntent` → `answerXxx`) foi **mantido** como rota sem-chave, contra §46. Motivo: `ToolsSheet`, sugestões locais, `SectionsBody`, `DraftBody` e 6 resolvers dependem dele; removê-lo seria reescrever meio app sem cobertura E2E dessas trilhas. A rota nova é primária com chave; sem chave, o motor local honesto continua. Revisitado no backlog.

## 14. Limitações / backlog

- Offline por rádio não executado (motivo acima); streaming; memória entre sessões; judge remoto; diff palavra-a-palavra; DOCX 2.0; semântica; aninhamento `<p><p>` cosmético no HTML aplicado; remoção do legado (§13); `generateForSection` ainda no `askScoped`.
- Proposta é volátil por design (§28): morte do processo limpa o cartão (mensagens/nota/chave persistem).

## 15. Decisão final

**`NATIVE_COPILOT_CORE_STABLE_WITH_BACKLOG`** — core real funcionando e validado no aparelho (chat → RAG → LLM → proposta → F6 → aceitar/rejeitar/stale/undo), regressões verdes, com as limitações documentadas acima.

---

## Apêndice — números reais (§56)

1. inicial: `b3507e6` (F17 + crash-fix; baseline da spec `b564771` defasado)
2. final: *(este commit)*
3. paralelas: nenhuma (tree limpo no início; confirmado)
4. auditoria: SettingsStore/DataStore, ModelScreen sem chave, `RichTextState.selection` no AAR, `getBackStackEntry` no chat
5. provider: `data/llm/` (contrato + Gemini + factory + HttpURLConnection)
6. configuração: `llmApiKey` no DataStore + card na Modelo IA
7. segurança: grep sem chave real; teste anti-vazamento; DataStore local
8. factory: decisão única, sem fallback silencioso
9. vertical slice: validado no aparelho (3 respostas reais)
10. RAG: `RoomContextPackRepository` em produção no chat
11. ContextPack: 8/4/12, factual sem training
12. prompt: `buildChatPrompt` consumido (teste §32) + `buildEditProposalPrompt`
13. histórico: 6/500; 14. seleção: `setSelection`, prioridade e honestidade
15. intent: `inferIntent` decide a trilha; `ChatIntent` decide a rota sem-chave
16. troca do cérebro: rota nova com chave, legado intacto sem chave
17. F5: portado + UI; 18. F6: portado + UI; 19. stale: bloqueia (E2E)
20. accept: aplica via NotesRepository; 21. reject: intacto; 22. offline: parcial (§11)
23. persistência: mensagens/nota/chave sim; runState/seleção/proposta não
24-28. testes: 63 novos (21 + 24 + 15 + 3), todos verdes
29. regressão Android: **291/291**; 30. web: **231/231**
31. build Android: verde; 32. build web: verde
33. lint: Android 2 erros pré-existentes; web 6 warnings pré-existentes
34. E2E Android: §37-41 PASS no SM-A346M; 35. interrupções PASS; 36. reboot/force-stop PASS
37. logs: limpos; 38. bugs: 8 encontrados; 39. corrigidos: 8
40. legado removido: **nenhum** (desvio §13)
41. limitações: §14; 42. backlog: §14; 43. decisão: `NATIVE_COPILOT_CORE_STABLE_WITH_BACKLOG`
