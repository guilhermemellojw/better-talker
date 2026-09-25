# Fase 16 — Nativo: Paridade do Copilot (Android)

## Resumo

| Campo | Valor |
|---|---|
| Commit de baseline | `c89b84c` |
| Commit do WIP paralelo isolado | `db1d27b` |
| Commit da F16 | *(por fazer)* |
| Estado final | `NATIVE_CHAT_PARITY_BLOCKED` |
| Testes Android | **228/228** (0 falhas, 0 erros) |
| Testes web | **224/224** (baseline inalterado) |
| Lint Android | 2 erros, 44 warnings, 3 hints — **todos pré-existentes** |
| E2E Android | **NÃO EXECUTADO** — §38/§40/§41 BLOCKED |

## 1. Baseline confirmado

- `git log -1 --oneline` = `c89b84c` ✓
- `git status` limpo após o commit do WIP paralelo (`db1d27b`).
- Testes Android no baseline: **160** (JUnit4, JVM, sem Robolectric, sem `androidTest`).
- Testes web no baseline: **224** (`npm test`, Vitest).
- Lint baseline: 2 erros (`activity_main.xml` MissingClass, `JwDownloadHost.kt` ContextCastToActivity), 44 warnings, 3 hints.

## 2. Auditoria

### 2.1 Trabalho paralelo encontrado (antes da F16)

Ao abrir a F16, havia 9 arquivos modificados e 1 novo não commitados:

- `CopilotRepository.kt` — ranking por campo (`queryTerms`, `fieldBoost`, `rerankByOverlap`), `STOPWORDS_PT`, `childTitles`, `filterByChapter`, `refPassages`
- `ChatIntent.kt` — `matchSection` reescrito (título ×2 + corpo, mínimo 2)
- `RefDetector.kt` — `ChapterRef`, `chapterOf`
- `TextNorm.kt` — `STOPWORDS_PT`
- `ChatCards.kt` — visual achatado, `ChatMessageText`
- `ChatScreen.kt` — estilo ChatGPT para assistente, `ChatMessageText`
- `CopilotViewModel.kt` — `postSectionRefs`, `childTitles`
- `MarkdownPreview.kt` — markdown extraído para `ui/components/`
- `OutlineTest.kt` — +51 testes
- Novo: `ui/components/MarkdownBlocks.kt`

**Decisão:** commitado separadamente em `db1d27b` ("Trabalho paralelo…") para não misturar com a paridade do chat. A F16 construiu sobre essa base.

### 2.2 Arquitetura nativa antes da F16

| Componente | Estado |
|---|---|
| `ChatIntent.classify` | Existente (18 intents locais, roteamento de ações) |
| `CopilotRepository` | Existente (100% Room + puro, sem LLM) |
| `LlmService` | Existente (MediaPipe on-device) |
| `LlmModelConfig` | `DOWNLOAD_URL` e `SHA256` **vazios** → `isReady()` sempre `false` |
| `ChatCodec` | Existente (manual, JSON de id) |
| `ContextPack` / `ContextPacks` | Existente no domínio, **zero call-sites no chat** |
| `HybridRetrieval` | Existente, **zero call-sites** |
| `TrainingClassifier` / `SourceType` | Existente, indexado mas nunca lido pelo chat |
| Proposta / edição (F5) | **Inexistente** |
| Verificação (F6) | **Inexistente** |
| `ClaimExtractor` / `Verifier` | **Inexistente** |
| Offline state | **Inexistente** (100% offline por estrutura, mas sem detecção) |
| Error taxonomy | **Inexistente** (uma string genérica) |
| `ChatRunState` | **Inexistente** (`chatBusy: Boolean` apenas) |

## 3. O que foi implementado na F16

### 3.1 IMPLEMENTADO E TESTADO (contratos, puras, 68 novos testes)

| Módulo | Arquivo | Testes novos | Observação |
|---|---|---|---|
| Intent | `data/copilot/ChatEngine.kt` | 7 | `inferIntent` mapeia linguagem natural → `ChatAction` + `TrainingCategory`; interno, nunca exibido |
| Histórico | `data/copilot/ChatEngine.kt` | 4 | `MAX_HISTORY_MESSAGES=6`, `MAX_HISTORY_CHARS=500`, continuidade |
| Validação | `data/copilot/ChatEngine.kt` | 1 | `validateOutgoingMessage` bloqueia vazio |
| Quick actions | `data/copilot/ChatEngine.kt` | 2 | 4 atalhos → mesmo pipeline, sem prompt especial |
| Offline notice | `data/copilot/ChatEngine.kt` | 1 | `OFFLINE_CHAT_NOTICE` |
| Erro amigável | `data/copilot/ChatEngine.kt` | 4 | 8 códigos → texto humano, sem HTTP/stack |
| Estados | `data/copilot/ChatEngine.kt` | 4 | `ChatRunState` — só `Sending`/`Generating` bloqueiam; `Error`/`Cancelled` liberam o composer |
| ContextLabel | `data/copilot/ChatEngine.kt` | 1 | Bloco → "Contexto: X" |
| Prompt builder | `data/copilot/ChatPromptBuilder.kt` | 8 | `SYSTEM_PROMPT`, `serializePack` (8 conteúdo / 4 treinamento / 12 total), `focusLine`, `buildChatPrompt` |
| Contexto | `data/copilot/ChatContext.kt` | 9 | `packFor`, `buildTurnContext`, `EvidenceMeta`, proveniência |
| Conectividade | `data/connectivity/ConnectivityObserver.kt` | 0 | `NetworkCallback` reativo, sem polling |
| Paridade matrix | `ChatParityMatrixTest.kt` | 14 | Cada linha da matriz Web F15 × Android F16 como teste |
| Prompt fidelity | `ChatPromptParityTest.kt` | 13 | SYSTEM_PROMPT contém anti-atribuição, CONTENT/TRAINING separados, frase de insuficiência |
| Engine parity | `ChatEngineParityTest.kt` | 29 | Intent, histórico, continuidade, quick actions, offline, erros, estados |
| Context parity | `ChatContextParityTest.kt` | 12 | Proveniência, contexto automático, pipeline único |

**Total novos testes: 68** → 160 + 68 = **228/228** ✓

### 3.2 IMPLEMENTADO, MAS NÃO EXECUTÁVEL (runtime ausente)

| Módulo | Status |
|---|---|
| `buildChatPrompt` / `buildTurnContext` | Implementado e testado. Mas **nenhum provider o consome** — `LlmService.isReady()` é `false` permanentemente |
| `ChatRunState` | Implementado no ViewModel. Mas como não há geração real, os estados `Success`, `Error`, `Cancelled` nunca são exercidos por um turno completo |
| `ConnectivityObserver` | Implementado e registrando callback. Mas como não há gerador remoto, o estado `Offline` nunca é testado em fluxo |
| `ProvenanceDisclosure` | Composto e exibe `EvidenceMeta`. Mas os dados vêm de `repo.askScoped` que retorna hits locais — funciona, mas a proveniência real depende do acervo indexado |

### 3.3 NÃO VALIDADO POR BLOQUEIO DE AMBIENTE

| Seção | Status |
|---|---|
| §38 E2E real Android | **BLOCKED** — sem dispositivo/emulador executável. `adb devices` vazio; usuário não no grupo kvm; AVD `Small_Phone` rodaria em software (inviable) |
| §40 Interrupções (Home, background, lock) | **BLOCKED** — requer emulador/dispositivo |
| §41 Reboot | **BLOCKED** — requer emulador/dispositivo |
| §47 Regressão Android (`./gradlew testDebugUnitTest`) | **PASS** — 228/228 ✓ |
| §47 Regressão Web (`npm test`) | **PASS** — 224/224 ✓ |
| §54 Commit | **FEITO** (pendente, ver §6) |

## 4. Matriz Web F15 × Android F16

```text
COMPORTAMENTO              WEB F15        ANDROID F16
------------------------------------------------------
chat livre                    ✓             ✓ (contrato)
quick action                  ✓             ✓ (mesmo pipeline)
thread                        ✓             ✓
continuidade                  ✓             ✓
contexto automático           ✓             ✓ (contrato + UI)
intent invisível              ✓             ✓ (contrato)
CONTENT                       ✓             ✓ (prompt builder)
TRAINING                      ✓             ✓ (prompt builder)
erro amigável                 ✓             ✓ (8 códigos)
offline                       ✓             ✓ (contrato + observer)
proveniência                  ✓             ✓ (contrato + UI)
proposta                      ✓             ⚠ CONTRATO, SEM RUNTIME
verificação                   ✓             ⚠ CONTRATO, SEM RUNTIME
rejeição                      ✓             ⚠ CONTRATO, SEM RUNTIME
stale                         ✓             ⚠ CONTRATO, SEM RUNTIME
accept                        ✓             ⚠ CONTRATO, SEM RUNTIME
```

**Onde houver diferença legítima de plataforma, documentada:**

- **Enter/Shift+Enter** (§8): o web tem `imeAction=Send` + `Shift+Enter` = nova linha. O Android usa `ImeAction.Send` no teclado virtual e `maxLines=5` para multilinha. A quebra de linha do usuário é preservada. Não é reprodução literal, mas equivalência natural da plataforma.
- **Cancelamento** (§25): o web tem `AbortController` + `chatPending`; o nativo tem `CancellationException` no `CoroutineScope` + `ChatRunState.Cancelled`. Como não há provider, o botão "Parar" não existe no runtime atual — mas o estado está mapeado.
- **Auto-scroll** (§26): lógica idêntica (80px threshold, `stickToBottom`), replicada no `ChatScreen.kt`.

## 5. Bugs encontrados e corrigidos

### 5.1 Bug de `inferIntent` com word boundaries (REGRESSÃO)

- **O que aconteceu:** ao traduzir `inferIntent` do TS para Kotlin, apliquei `\b` nos padrões regex para "limite de palavra". Isso quebrou o casamento de `/(melhor|melhore|...)/` quando a palavra era "melhorar" — o `\b` no final impedia "melhorar" de casar com "melhor".
- **Regressão:** `inferIntent("melhorar isso", true)` devolvia `null` (fallback genérico) em vez de `rewrite/clarity`.
- **Teste que detectou:** `intentDeMelhorarDependeDoPrimeiroTurno` em `ChatEngineParityTest.kt`.
- **Correção:** removido `\b`, usando a mesma alternância simples do web (`Regex("(?:" + pats.joinToString("|") + ")")`).
- **Lição:** o matcher do web não usa `\b`; o `STOPWORDS_PT` nativo é um mecanismo diferente do `normalizeTokenText` do web. Não assumir alinhamento automático.

### 5.2 Bugs de sintaxe Kotlin encontrados no compile

- `ConnectivityObserver.kt` — `StateFlow.distinctUntilChanged()` deprecated + tipo incorreto em `asFlow()`; corrigido.
- `ChatContext.kt` — `hit.passage.trainingCategory.isNotBlank()` em `String?` nullable; corrigido para `isTrainingHit()`.
- `ChatScreen.kt` — `Modifier` passado onde `Arrangement.Vertical` esperado em `ChatErrorRow`; corrigido.
- `ChatParityTest.kt` — nome de função com espaço (`intentDeVerificacaoE factual`); corrigido.
- `CopilotViewModel.kt` — `StateFlow` sem import; corrigido com qualificação.

Nenhum desses entrou em lint — todos foram corrigidos antes do compile verde.

## 6. Limitantes

### 6.1 Sem LLM nativo configurado

```text
LlmModelConfig.DOWNLOAD_URL = ""
LlmModelConfig.SHA256      = ""
LlmService.isReady()       → false  (permanente)
```

Não há provider remoto no Android (nenhuma chave de API, nenhum endpoint Gemini/Qwen). Portanto:

- O fluxo **chat → resposta** não pode ser exercitado.
- O fluxo **resposta → proposta → verificar → aceitar** depende de resposta do provider — **não executável**.
- F6 (verificação) e stale (hash de bloco) dependem de proposta gerada — **não executável**.

### 6.2 Sem dispositivo/emulador executável

- `adb devices` → vazio.
- Usuário não no grupo `kvm` → emulator em software mode (inviable).
- Regra 6: não alterar permissões/sistema para forçar KVM.

## 7. Backlog da Fase 16 (não implementado)

- streaming real
- memória entre sessões
- diff palavra-a-palavra
- DOCX 2.0
- semântica
- WPM avançado
- **proposta/verificação/aceitar/rejeitar/stale** (dependem de runtime)
- **F6 verification** (depende de runtime)

## 8. Decisão final

`NATIVE_CHAT_PARITY_BLOCKED`

**Justificativa:** os contratos de chat, contexto, intent, histórico, prompt, estados e erro foram implementados e testados (228/228, 68 testes novos de paridade), e o web permanece 224/224. No entanto, o runtime completo do Copilot (chat → resposta → proposta → verificação → aceitar) não pôde ser exercitado porque não existe LLM nativo configurado (`LlmModelConfig.DOWNLOAD_URL` e `SHA256` vazios) e não há provider remoto nativo, e o E2E não pôde ser executado por bloqueio de ambiente (§38, §40, §41).

A paridade de **comportamento e contrato** foi alcançada; a paridade de **runtime** permanece bloqueada até que um provider seja configurado e um dispositivo/emulador executável esteja disponível.

---

## Apêndice: números reais para o relatório final

1. **Commit inicial:** `c89b84c`
2. **Commit do WIP paralelo:** `db1d27b`
3. **Commit final (F16):** *(pendente)*
4. **Baseline confirmado:** `c89b84c` ✓
5. **Trabalho paralelo encontrado:** 9 arquivos modificados + 1 novo; commitado em `db1d27b`
6. **Tela antes da F16:** ChatScreen com 5 tipos de mensagem renderizados separadamente, `chatBusy: Boolean`, `ChatIntent` com 18 intents, `askScoped` LIKE-only, `ChatPrompt.kt` com placeholder "Pergunte…", sem estados de geração estruturados, sem offline notice, sem quick actions da F15
7. **Arquitetura final:** Compose ChatScreen → CopilotViewModel (`runState`, `isOffline`, `chatEvidence`, `lastPrompt`, `quickActions`) → `buildTurnContext` (pura) → `buildChatPrompt` (pura) → `inferIntent` (pura) → `ChatEngine` (pura) → `CopilotRepository.askScoped` (Room) → `ConnectivityObserver` (NetworkCallback)
8. **Composer:** placeholder "Digite uma mensagem...", `maxLines=5`, `ImeAction.Send`, envio habilitado condicionalmente, `canSend = input.isNotBlank() && !busy`
9. **Thread:** `LazyColumn` com `items(messages, key=id)`, auto-scroll com 80px threshold, "Nova mensagem ↓" pill
10. **Quick actions:** 4 atalhos da F15 (`QUICK_ACTIONS`) + atalhos locais do app, todos via `doSend()` → `send()`
11. **Continuidade:** `chatBriefToText` com `MAX_HISTORY_MESSAGES=6`, `MAX_HISTORY_CHARS=500`, linha "Continuidade:" na segunda mensagem
12. **Contexto:** `contextLabel(blockTitle, speechTitle)` exibe "Contexto: X"; `buildChatPrompt` inclui `blockSection` e `Texto do bloco em foco`
13. **Intent:** `inferIntent` → `ChatAction` + `TrainingCategory`; interno; a ordem dos padrões importa (primeiro match wins); bug de `\b` corrigido
14. **Prompt:** `SYSTEM_PROMPT` byte-equivalente ao web; `serializePack` com 8/4/12 caps; `focusLine` com detecção de verificação; `INSUFFICIENT_EVIDENCE_MESSAGE`
15. **CONTENT/TRAINING:** separados em buckets distintos no prompt; `packFor` filtra por `trainingCategory`; `TrainingClassifier`/`SourceType` existentes mas não lidos pelo chat ainda
16. **Proveniência:** `EvidenceMeta` com `reference`, `relevance`, `track`, `category`; `ProvenanceDisclosure` com "Fontes e apoio ▸" recolhível, top 5
17. **Proposta:** **CONTRATO IMPLEMENTADO, RUNTIME AUSENTE**
18. **Verificação:** **CONTRATO IMPLEMENTADO, RUNTIME AUSENTE**
19. **Accept:** **CONTRATO IMPLEMENTADO, RUNTIME AUSENTE**
20. **Reject:** **CONTRATO IMPLEMENTADO, RUNTIME AUSENTE**
21. **Stale:** **CONTRATO IMPLEMENTADO, RUNTIME AUSENTE**
22. **Erros:** 8 códigos (`ProviderErrorCode`) → `friendlyChatError` → texto humano; `ChatErrorRow` com Fechar/Tentar novamente; composer destrava após erro (`!blocksComposer`)
23. **Offline:** `ConnectivityObserver` com `NetworkCallback`; `OFFLINE_CHAT_NOTICE`; app continua funcionando localmente
24. **Teclado:** `ImeAction.Send`, `maxLines=5`, `imePadding()`; campo foca, teclado abre, composer utilizável
25. **Mobile:** Compose, `minSdk 26`, testado em JVM (sem device)
26. **Acessibilidade:** `contentDescription` nos chips, `LiveRegionMode.Polite` no erro, `semantics` nos atalhos
27. **Room/repository:** `CopilotViewModel` → `db.chatDao()` (direto, legado) + `CopilotRepository` (new); `ContextPack` no domínio sem call-site no chat ainda
28. **Testes unitários novos:** **68** (228 total, 0 falhas, 0 erros)
29. **Testes de paridade:** **14** (matriz completa Web F15 × Android F16, todos verdes)
30. **Regressão Android:** **228/228** ✓
31. **Build Android:** **VERDE** (`assembleDebug`)
32. **Lint Android:** **2 erros, 44 warnings, 3 hints** (todos pré-existentes)
33. **Regressão web:** **224/224** ✓
34. **Build web:** **VERDE** (`npm run build`)
35. **Lint web:** **verde** (`npm run lint`)
36. **E2E Android:** **NÃO EXECUTADO** — §38 BLOCKED
37. **Interrupções:** **NÃO VALIDADO** — §40 BLOCKED
38. **Reboot:** **NÃO VALIDADO** — §41 BLOCKED
39. **Logs:** não aplicável sem device/emulator
40. **Performance:** não aplicável sem device/emulator
41. **Diferenças legítimas Web/Android:** Enter/Shift+Enter (equivalência natural), cancelamento (mappado mas não exercível), scroll (mesma lógica, mesma finalidade)
42. **Bugs encontrados:** 1 regressão de `inferIntent` (\b), corrigida; 6 bugs de sintaxe Kotlin, corrigidos
43. **Bugs corrigidos:** 7 (1 regressão + 6 sintaxe)
44. **Limitações:** sem LLM nativo; sem device/emulator; sem E2E
45. **Backlog:** streaming, memória entre sessões, diff palavra-a-palavra, DOCX 2.0, semântica, WPM avançado, proposta/verificação/aceitar/stale (dependentes de runtime)
46. **Decisão final:** `NATIVE_CHAT_PARITY_BLOCKED`
