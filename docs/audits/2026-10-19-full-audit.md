# Auditoria completa — Better Talker (2026-10-19)

> **Baseline:** `main` em `58c4837` (Nível 1 do verifier) + política de chat testável (`c20c830`).
> **Método:** auditoria estática read-only (grep + leitura integral dos pontos críticos).
> **Escopo:** 189 arquivos Kotlin, `AndroidManifest.xml`, `build.gradle`, `firestore.rules`, `.gitignore` e testes JVM.
> **Fora do escopo:** build, profiler, StrictMode, heap dump, TalkBack real, backup/restore real e sync com dois aparelhos.

## Sumário executivo

**4 candidatos a P0**, ~20 P1 e ~25 P2. Nenhum P0 foi corrigido nesta rodada — todos exigem decisão de produto/arquitetura e estão documentados para revisão.

### P0 candidatos

| # | Sev | Área | Achado | Impacto |
|---|---|---|---|---|
| 1 | P0 | Sync | `UNIQUE(noteId, order)` + `@Insert(REPLACE)` em seções/sub-pontos; pull por `id` e push de snapshot pré-pull | Perda/ressurreição alternada de seção e sub-pontos quando dois aparelhos criam na mesma posição |
| 2 | P0 | Main thread | Importação de esboço (PDF/EPUB/DOCX/JWPUB + parse S-34) na Main | ANR/travamento ao importar arquivo |
| 3 | P0 | Main thread | Importação da Biblioteca copia até 400 MB na Main, duas vezes, e deixa `.tmp` no cache | ANR + consumo de disco a cada importação |
| 4 | P0 | TTS | `VoiceRouter` compartilhado; qualquer ViewModel chama `shutdown()` em `onCleared` | TTS/Piper morre para o app inteiro após sair de uma tela; “Ouvir” fica mudo |

### P1 prioritários

1. Autosave do editor/seções com debounce é cancelado ao sair da tela — última edição pode ser perdida.
2. `deleteForever` e `LibraryRepository.delete` não são transacionais; chat da nota excluída fica órfão.
3. Sync não mapeia `folderId=null`, `discourseType` e `tribunaJson` — pasta “Todas”, tipo V&M e cápsulas da Tribuna podem sumir/voltar.
4. `wipeCloud` monta um batch por coleção; >500 docs falham no meio e deixam a nuvem parcial.
5. `IndexPublicationWorker` apaga o índice antigo antes de inserir o novo, fora de transação, e trata cancelamento como falha.
6. Extração de PDF/EPUB/DOCX sem teto por entrada — OOM em arquivo grande/malicioso.
7. LWW substitui a nota inteira e não propaga `trashed=true` — edição offline pode ser perdida ou nota excluída ressuscitar.
8. API key DeepSeek em texto claro no DataStore + `allowBackup=true` sem regras.
9. Piper pode carregar dois `OfflineTts` e o shutdown não é idempotente.
10. Acessibilidade: swatches de 28dp sem rótulo; Ensaio auto-oculta controles em 3s; linhas compactas dos hubs abaixo de 48dp; composer do chat sem label persistente.

---

## A1 — Crash risks

| Sev | Local | Achado | Gatilho / impacto | Fix | Conf. |
|---|---|---|---|---|---|
| P1 | `ui/editor/EditorViewModel.kt:217-226`; `ui/editor/SectionsController.kt:437-451,512-519` | Autosave com `delay(400)` em `viewModelScope`/`innerScope`; sem flush em `onCleared`/Back. `repo.save` sem `try/catch`. | Sair em <400 ms ou erro de Room: última edição perdida; `_saving` pode ficar preso. | Flush em `onBack/onCleared` (`NonCancellable`), try/catch. | Alta ✅ |
| P1 | `data/work/SyncWorker.kt:133-140`; `IndexPublicationWorker.kt:105-108`; `RegisterDownloadWorker.kt:86-89` | `catch (Exception)` engole `CancellationException` e vira `Result.retry()`/status de erro. | WorkManager cancela; worker é marcado como falha ou retenta. | `catch (e: CancellationException) { throw e }`. | Alta |
| P2 | `MainActivity.kt:102,447`; `LibraryViewModel.kt:13`; `LibraryRepository.kt:40`; `EditorViewModel.kt:37,363-365` | ViewModel/repositório retém `Context` de Activity. | Rotação: leak de Activity. | Guardar `applicationContext`. | Alta |
| P2 | `PiperTtsManager.kt:64-100,107-110,143-163,179-206` | `ensureLoaded()` check-then-act sem lock; `stop()` não checa `isActive` antes de tocar; `shutdown()` assíncrono e não idempotente. | Tocar/Parar durante o primeiro load; áudio após stop; engine “pronta” morta. | `Mutex`/single-flight; `ensureActive`; shutdown síncrono/idempotente. | Média |
| P2 | `PiperModelStore.kt:51-79`; `MainActivity.kt:66-70` | Duas instâncias copiam o mesmo `.part` sem lock; `ready()` só valida tamanho mínimo. | Sair/entrar no Ensaio durante provisionamento. | Lock de processo; validação por tamanho/checksum. | Média |
| P2 | `TextToSpeechManager.kt:102-129,161-167` | `shutdown()` antes do `onInit`: `tts=null`, `languageOk=true`, `_ready=true` sem engine. | Abrir/fechar Stage rapidamente. | Checar `tts != null` no callback; geração/cancelamento. | Alta |
| P2 | `PdfExportManager.kt:39-70` | `WebView` estático + `WebViewClient` capturando Activity; destroy só na próxima impressão. | Exportar PDF e sair/rotacionar. | Destruir no fim do print; usar application context. | Média |
| P2 | `JwDownloadHost.kt:25` | `dlg.show(fm, tag)` sem `fm.isStateSaved`. | Estado salvo/background. | Guardar `isStateSaved`/post. | Média (suspeita) |
| P2 | `EditorScreen.kt:851-866`; `TribunaScreen.kt:320-340` | `writeText` no main, nome fixo, sem `.tmp`+rename. | Exportar duas vezes/disco cheio. | IO + escrita atômica + catch. | Alta |
| P2 | `MainActivity.kt:374` | `nav.getBackStackEntry(EDITOR)` pode lançar `IllegalArgumentException`. | Deep link/restauração sem editor na pilha. | `runCatching`/checar destino. | Baixa |
| P2 | `OutlineParser.kt:63`; `TextNorm.kt:8-13`; `PassageBuilder.kt:58`; `RefDetector.kt:914` | Regex compilada por linha/chamada em loops quentes. | Importação grande/RAG. | `private val`/cache. | Alta |
| P2 | `AuthRepository.kt:91`; `AccountViewModel.kt:52-59` | `authOrNull()!!` após `isConfigured()`; `signIn` sem `finally` e catch que engole cancelamento. | Firebase mal inicializado/cancelamento. | `?: return`; try/finally + rethrow. | Baixa/Alta |

**Verificado OK em A1:** não há `lateinit`, `GlobalScope` nem `runBlocking` em produção. Os `!!` encontrados estão protegidos por `matches()`/`find()!=null`/filtro. `PiperTtsManager` libera `AudioTrack` em `finally`; `chunkForTts` não perde conteúdo.

---

## A2 — Data integrity

| Sev | Local | Achado | Gatilho / impacto | Fix | Conf. |
|---|---|---|---|---|---|
| **P0** | `AppDatabase.kt:280-290,325-335,427-430,458-461`; `SyncWorker.kt:488-533,553-592` | `UNIQUE(noteId, order)` + `REPLACE` + pull por `id` sem reconciliar `order`; push usa `local` lido antes do pull. | Dois aparelhos criam seção/sub-ponto na mesma posição: uma linha REPLACE apaga a outra e seus filhos; o push antigo a ressuscita no próximo sync. | Identidade por `id`; atualizar `order` em duas fases; `@Upsert`; reconciliar duplicatas; teste com fake que honre UNIQUE. | Alta ✅ |
| P1 | `NotesRepository.kt:122-139`; `LibraryRepository.kt:238-249`; `AppDatabase.kt:353-373` | Delete definitivo não transacional; `chat_messages` sem FK e sem limpeza no `deleteForever`. | Crash/falha entre delete e tombstone: nota/anexo ressuscita; chat órfão. | `withTransaction` incluindo tombstone; `chatDao.clear(noteId)`. | Alta ✅ |
| P1 | `SyncWorker.kt:235-258,619-654`; `FirestoreSyncRemote.kt:23-24` | `noteMap` usa `filterValues { it != null }`; `folderId=null` não apaga o campo remoto. `discourseType` e `tribunaJson` não são mapeados. | Mover para “Todas” não propaga; demo chega como discurso; pull de seção apaga Tribuna. | Mapear todos os campos; `FieldValue.delete()` para null; round-trip por campo. | Alta ✅ |
| P1 | `AccountViewModel.kt:92-115` | `wipeCloud` monta um batch por coleção; Firestore limita 500 writes. | Usuário com >500 seções/sub-pontos: wipe falha no meio. | Lotes ≤400 + retomada/erro explícito. | Alta ✅ |
| P1 | `IndexPublicationWorker.kt:93-108`; `LibraryRepository.kt:317-322` | `deleteForAttachment` + `insertAll` fora de transação; cancelamento vira falha. | Worker parado/morte: índice parcial ou perdido; outline órfão. | Transação; só marcar `ready` no fim; rethrow de cancelamento; retry idempotente. | Alta |
| P1 | `DocExtractors.kt:42-52,69,92,133`; `NwtEpubParser.kt:37-53` | `PDDocument.load` do arquivo inteiro; `ZipEntry.readBytes()` antes de checar tamanho. | PDF/EPUB/DOCX grande ou zip bomb: OOM no processo do app. | `MemoryUsageSetting`, teto por entry/stream, validar tamanho. | Alta (mecanismo) |
| P1 | `SyncWorker.kt:213-219,723-731`; `SyncWorkerTrashTest.kt:13-32` | LWW substitui a nota inteira; `remoteCanTrashLocal` impede propagar `trashed=true`; teste só da função pura. | Dois devices offline: merge não existe; nota excluída pode voltar. | Política de merge/versionamento + teste E2E com dois “devices”. | Alta |
| P1 | `AndroidManifest.xml:6` | `allowBackup=true` sem `fullBackupContent`/`dataExtractionRules`. | Auto Backup/transferência copia Room, DataStore (API key, `last_uid`) e arquivos. | `allowBackup=false` ou XML excluindo DB/DataStore/segredos. | Alta ✅ |
| P2 | `CopilotViewModel.kt:3037-3084` | `applyVmPastePlan` faz upserts de nota+seções sem transação (o FAB usa `OutlineImportService` transacional). | Falha no meio: nota/seções parciais. | `withTransaction`/reusar serviço. | Alta |
| P2 | `S34OutlineRepository.kt:48-91` | `save()` deleta outline e insere em chamadas separadas. | Reindexação interrompida: estrutura parcial. | `withTransaction`. | Alta |
| P2 | `SectionsController.kt:512-519` | Dois `add/move` rápidos disparam `scope.launch` concorrentes com snapshots antigos. | Toques rápidos/D&D: última transação sobrescreve a mais nova. | `Mutex`/canal único. | Média |
| P2 | `SyncWorker.kt:192-203,503-513` | Tombstone é removido comparando com `cloud` lido antes do delete; pode ficar eterno. | Delete sincronizado: re-delete/re-leitura a cada sync. | Reconsultar após delete. | Alta |
| P2 | `SyncWorker.kt:600-607` | `deleteNoteDeep` faz `getAll("sections")`+`getAll("subPoints")` por tombstone. | Muitas notas excluídas: sync quadraticamente caro. | Uma passada/índice em cache. | Alta |
| P2 | `SyncWorker.kt:374-409` | Pull de anexo faz `update(cur.copy(sourceUrl=...))` com `cur` antigo; mudanças de `indexed/sizeBytes/status` não sobem. | Sync durante indexação/reindexação. | UPDATE só da coluna; push de metadados. | Média/Alta |
| P2 | `SettingsStore.kt:63-72`; `MainActivity.kt:127-130` | `needsIndexFormat` grava versão antes de `reindexAll()` concluir. | Reindexação interrompida: acervo parcial no formato antigo. | Marcar por anexo/só no fim. | Alta |
| P2 | `LibraryRepository.kt:107-133` | `.tmp` de `cacheDir` não é apagado no sucesso. | Importações SAF repetidas: até 400 MB por import. | `finally { tmp.delete() }`. | Alta ✅ |
| P2 | `CopilotViewModel.kt:1284-1296`; `AppDatabase.kt:404-408` | “Editar pergunta” desativa mensagens e só depois grava a nova; falha no `send` deixa conversa vazia. | Erro de DB/LLM no meio. | Transação abrangendo deactivate+insert. | Média |
| P2 | `AccountViewModel.kt:71-88` | `clearAllTables()` concorre com Sync/index workers. | Wipe com workers ativos: linhas órfãs reinseridas. | Cancelar work/ordem explícita. | Baixa |
| P2 | `HomeViewModel.kt:128-137` | Caminho S34 cria nota e só depois persiste seções. | Erro no persist: nota vazia. | Transação incluindo criação. | Média |
| P2 | `AppDatabase.kt:261-265`; `MigrationSqlTest.kt:17-112` | `exportSchema=false`; testes SQL por migração, mas sem `MigrationTestHelper`/schema exportado. | Upgrade real de app antigo: mismatch só aparece em produção. | Exportar schemas + testes 1→20. | Alta |
| P2 | `OutlineRepository.kt:119-124` | `unlink` deleta outline antes do tombstone, sem transação. | Falha entre operações. | `withTransaction`. | Alta |

**Verificado OK em A2:** `createPastedNote` e `OutlineImportService.persist` são transacionais; `wipeCloud` inclui `notes/folders/attachments/outlines/sections/subPoints/tombstones`; `withPageMarkers`/`paragraphPages` tratam `firstPage<=0`; `PiperModelStore` usa `.part`+rename e valida artefatos; `sourceUrl` não sobrescreve `indexed/sizeBytes` no fluxo normal; `@Query` nullable usa `IS :param`; `clearAllTables` só no fluxo destrutivo de conflito de conta; migrations 1→20 estão ligadas; não há `fallbackToDestructiveMigration`.

---

## A3 — Resource leaks

| Sev | Local | Achado | Impacto | Fix | Conf. |
|---|---|---|---|---|---|
| **P0** | `MainActivity.kt:72-80,276,348,361`; `StageViewModel.kt:195-197`; `TribunaViewModel.kt:183-184`; `CopilotViewModel.kt:4883`; `VoiceRouter.kt:57-60` | Um `VoiceRouter` compartilhado é injetado em 3 VMs; cada uma chama `shutdown()` no `onCleared`. | Sair de qualquer tela mata TTS/Piper do processo; “Ouvir” fica mudo; Piper `ready=true` com scope cancelado. | Ownership no topo; VMs só `stop()`; refcount; cancelar scope no shutdown. | Alta ✅ |
| P1 | `PiperTtsManager.kt:64-100,193-206` | `ensureLoaded()` sem lock; `warmUp()` e `speak()` podem criar dois `OfflineTts` (~63 MB); um é sobrescrito sem `release()`. | Memória nativa duplicada; engine inconsistente. | `Mutex`/single-flight; shutdown idempotente. | Alta (race) |
| P2 | `TextToSpeechManager.kt:102-129,161-167` | Callback `onInit` após `shutdown()` deixa `_ready=true` sem engine. | Botão habilitado, sem áudio. | Guardar geração/`if (tts==null) return`. | Alta |
| P2 | `VoiceRouter.kt:35,41-49,57-60` | Dois `stateIn(Eagerly)` em scope próprio; `shutdown()` não cancela o scope. | Collectors/scope vivos após shutdown. | `scope.cancel()`; `WhileSubscribed`. | Alta |
| P1 | `MainActivity.kt:107-126`; `JwDownloadDialog.kt:29,107` | Lambda estático `onEnqueued` captura Activity, repo e `rememberCoroutineScope`; nunca é limpo. | Rotação/saída: Activity + views retidas; callback obsoleto. | `DisposableEffect { onDispose { onEnqueued=null } }`; app context. | Alta |
| P1 | `PdfExportManager.kt:39-70` | `WebView` estático + client capturando Activity; destroy só na próxima impressão. | WebView/Activity retidos. | Destruir no fim do print; anular estático. | Alta |
| P1 | `LibraryRepository.kt:94-135` | `.tmp` de `cacheDir` não apagado no sucesso nem em falha. | Duplicata integral (até 400 MB) por import. | `finally { tmp.delete() }`. | Alta ✅ |
| P2 | `JwpubExtractor.kt:58-66,80-114,322-333` | `Inflater.end()` fora de `finally`; arquivos parciais no cache se `readOuterTo` lançar. | Vazamento de zlib/temp em JWPUB malformado. | `finally` + limpeza de parciais. | Alta |
| P2 | `NwtEpubParser.kt:37-53`; `JwpubExtractor.kt:28` | `readBytes()` sem teto; `StringBuilder` + `toString()` de ~32 MB. | Pico de memória. | Limites por entry/stream. | Média |
| P2 | `AuthRepository.kt:134-138` | `CoroutineScope(IO).launch` solto no `signOut`; sem job/cancel. | Trabalho órfão após logout. | Escopo de aplicação com SupervisorJob. | Alta |
| P2 | `LibraryRepository.kt:304-321`; `RegisterDownloadWorker.kt:62`; `SyncWorker.kt:133-140` | `enqueue` sem `UniqueWorkName`; `.tmp` compartilhado por placeholder; `retry()` sem teto. | Workers duplicados/concorrentes; retry infinito. | `enqueueUniqueWork`; retry por `runAttemptCount`. | Alta |

**Verificado OK em A3:** `AudioTrack.stop/release` no `finally` sob lock; cursores com `use`; SQLite do JWPUB fechado no `finally`; Room é singleton; não há `MediaPlayer`, `ExoPlayer`, `AudioRecord` nem `System.loadLibrary` no app.

---

## A4 — Main thread risks

| Sev | Local | Achado | Impacto | Fix | Conf. |
|---|---|---|---|---|---|
| **P0** | `CopilotViewModel.kt:4295-4307,4330-4393`; `OutlineRepository.kt:28-90` | Cópia SAF + PDFBox/ZIP/SQLite/AES + `OutlineParser` + `RefDetector` na Main; repetido no S-34. | ANR ao importar esboço. | `withContext(IO/Default)`. | Alta ✅ |
| **P0** | `LibraryViewModel.kt:56-65`; `LibraryRepository.kt:94-135,139-166` | Cópia de até 400 MB na Main, duas vezes no disco. | ANR garantido em arquivo grande. | IO + mover em vez de copiar + cleanup. | Alta ✅ |
| P1 | `CopilotViewModel.kt:1574,1686,1898-1911`; `RetrievalRepository.kt:219-252`; `HybridRetrieval.kt:151-184` | RAG + fallback + training + ranking de até 2.000 candidatos na Main. | Jank/ANR a cada mensagem. | `withContext(Default)` no retrieval/rank. | Alta |
| P1 | `TextNorm.kt:8-13`; `HybridRetrieval.kt:59,161-163`; `RefDetector.kt:914` | `normalizeText` recompila 3 regex por chamada; `RefDetector` compila dentro de loop. | Milhares de compilações por mensagem/importação. | Regex `private val`/cache. | Alta |
| P1 | `PastePreviewSheet.kt:86,90-94,156`; `CopilotViewModel.kt:4401-4437`; `HomeViewModel.kt:122-140` | `planPaste`/normalização/analyzer no primeiro frame e na Main. | Trava ao abrir a folha com texto grande. | `Default` + estado “Analisando”. | Alta |
| P2 | `ChatScreen.kt:1797-1800`; `CopilotRepository.kt:364-411` | `resolveChatRefs` na Main; por ref, `attachmentDao().all()` + até 500 passagens + normalize. | Jank em mensagem com refs. | Default + cache por mensagem. | Média |
| P2 | `StageViewModel.kt:118-143`; `TribunaViewModel.kt:109-127` | `buildExportPlainText`/`parseStageTurns`/capsule parse na Main; N+1 de sub-pontos. | Jank ao abrir/atualizar Ensaio/Tribuna. | Default/cache por linha. | Média |
| P2 | `EditorScreen.kt:254-256`; `WordCount.kt:13-30` | Contador de palavras de todas as seções a cada edição, com regex. | Jank de digitação em notas grandes. | `derivedStateOf`/debounce/incremental. | Média |
| P2 | `ChatScreen.kt:163-164,1447-1448,1634-1641` | `stripSuggestionTags`/`parseBlocks`/`cardTextToMarkdown` na Main; recalcula no streaming. | Jank com resposta longa. | Default/throttle/regex pré-compilada. | Média |
| P2 | `TextToSpeechManager.kt:35-64` | ~15 regex por fala na Main. | Jank ao tocar “Ouvir” em texto longo. | Regex pré-compiladas/Default. | Alta |
| P2 | `EditorScreen.kt:635-641,851-880`; `TribunaScreen.kt:316-335` | Build de HTML + `writeText` no cache na Main. | Jank no compartilhar/PDF. | IO/Default. | Alta |
| P2 | `MainActivity.kt:137-143`; `HomeScreen.kt:437`; `ChatPrompt.kt:407,420` | `items()` sem `key`; `db`/repo recriados por recomposição. | Recomposição custosa/perda de estado. | `key` estável/`remember`. | Média |

**Verificado OK em A4:** não há `runBlocking`, `Thread.sleep` nem IO de arquivo direto na Main fora dos casos acima; queries `suspend`/`Flow` do Room usam executores próprios; DataStore faz IO interno.

---

## A5 — Security / Privacy

| Sev | Local | Achado | Risco | Fix | Conf. |
|---|---|---|---|---|---|
| P1 | `data/prefs/SettingsStore.kt:15,88-90` | API key DeepSeek em `preferencesDataStore("settings")` em texto claro. | Extração por root/backup/imagem forense; credencial paga. | Android Keystore/Tink; excluir do backup; revogação. | Alta ✅ |
| P1 | `AndroidManifest.xml:6` | `allowBackup=true` sem regras; DB, chat e DataStore (chave/uid) entram no backup. | Auto Backup/transferência leva notas + credencial. | `allowBackup=false` ou XML de exclusão. | Alta ✅ |
| P1 | `android/app/build.gradle:22-28` | Release assinado com a keystore de debug; `minifyEnabled=false`. | Atualização maliciosa com chave debug; símbolos/logs no APK. **Aceito conscientemente para sideload interno; não usar para distribuição ampla.** | Keystore de release em CI; minify. | Alta ✅ |
| P2 | `ui/jw/JwDownloadDialog.kt:73-114` | WebView com JS/DOM storage sem allowlist de navegação/validação de host do download. | Página comprometida/redirecionada executa JS e baixa URL arbitrária. | Allowlist `jw.org`/`jw-cdn.org`; Custom Tabs; validar esquema/host. | Alta config / média explorabilidade |
| P2 | `res/xml/file_paths.xml:3-6` | FileProvider expõe `external-path Download/`, `files-path publicacoes/` e `cache-path "."`. | Grants futuros podem expor mais que `cache/exports`. | Restringir a `cache-path exports/` e `files-path publicacoes/`. | Alta |
| P2 | `AndroidManifest.xml:60-62` | `READ_MEDIA_IMAGES/VIDEO/AUDIO` sem uso no código. | Superfície/permissões desnecessárias. | Remover; usar SAF/MediaStore. | Alta |
| P2 | `AndroidManifest.xml:30-34` | `MainActivity` exportada com `ACTION_SEND text/plain`, mas `onCreate` não lê o intent. | Share target fantasma/superfície exportada. | Remover filtro ou implementar import validado. | Alta |
| P2 | `ui/account/AccountViewModel.kt:71-80` | “Apagar dados locais” limpa DB/publicações, mas não o DataStore (API key, tema, uid). | Chave antiga permanece após troca/wipe. | Wipe completo de preferências. | Alta |
| P2 | `DeepSeekProvider.kt:260`; `SectionGeneratorImpl.kt:92`; `OutlineGeneratorImpl.kt:49`; `SyncWorker.kt:135` | Logs de body de erro/exception/stack; sem redaction. | Conteúdo de resposta/SQL/Firestore em logcat. Não encontrei chave/prompt/nota/chat. | Remover body, redigir, gate DEBUG, minify. | Média |
| P2 | `EditorScreen.kt:851-877`; `ChatScreen.kt:1216-1228`; `TribunaScreen.kt:320-362` | Exportações em `cache/exports`; “Copiar” usa clipboard comum; sem `FLAG_SECURE`. | Conteúdo sensível em cache/clipboard/recents. | Limpar cache; `EXTRA_IS_SENSITIVE`; avaliar `FLAG_SECURE`. | Média |
| P2 | `data/voice/SpeechRecognizerManager.kt:124-138` | STT do sistema sem `EXTRA_PREFER_OFFLINE`; áudio pode ir à nuvem do provedor. | Privacidade não divulgada. | Preferir offline/avisar fallback. | Média |
| P2 | `firestore.rules:12-18` | Regra escopa `users/{uid}/{document=**}` e exige `request.auth.uid == uid`. | OK; sem acesso cruzado. Validação de campos ausente. | Manter; validar Console. | Alta |
| OK | `.gitignore:7`; `android/app/google-services.json` | `google-services.json` ignorado e não tracked; `*.log` ignorado. Nenhuma chave de alta entropia em arquivos tracked. | — | Manter. | Alta |
| OK | `AndroidManifest.xml:5-12`; `variables.gradle` | Sem `usesCleartextTraffic`/`debuggable`; target 36 → HTTPS; chamadas HTTPS. | — | Manter. | Alta |

---

## A6 — Accessibility

| Sev | Local | Achado | Risco | Fix | Conf. |
|---|---|---|---|---|---|
| P1 | `ui/home/HomeScreen.kt:196-203` | Swatches de pasta `28.dp` com `clickable`, sem `contentDescription`, `role`, `selected` ou estado visual. | TalkBack anuncia controles vazios; alvo <48dp; seleção não confirmada. | Alvo 48dp, `selectable`/RadioButton, nomes de cor, check. | Alta ✅ |
| P1 | `ui/stage/StageScreen.kt:135-151,206-225` | Controles auto-ocultam em 3s; `Box.fillMaxSize().clickable` sem `onClickLabel`. | TalkBack/switch/keyboard podem não revelar controles; semântica fundida. | Não auto-ocultar com a11y ativa; ação “Mostrar controles”; controles fixos. | Alta |
| P1 | `ui/hub/HubNoteList.kt:114-118`; `ui/components/NoteItems.kt:163-167` | `NoteRowCompact` com padding vertical 2dp (~30dp). | Alvo <48dp nas telas principais Treinar/Apresentar. | `heightIn(min=48.dp)`/padding. | Alta ✅ |
| P1 | `ui/copilot/ChatPrompt.kt:132-151` | Composer só com placeholder; sem `label`/`contentDescription`. | Após digitar, TalkBack lê só o conteúdo, sem finalidade. | `label` “Mensagem para o Copilot”/semântica. | Alta |
| P2 | `ui/editor/SectionCardEditor.kt:162-164,579-588`; `EditorScreen.kt:752-754` | Título/subtópico/“Orientações” clicáveis com ~28-30dp. | Toque difícil. | 48dp/menu. | Alta |
| P2 | `ui/editor/SectionCardEditor.kt:757-773` | Handle de reordenação 24dp sem ação TalkBack (há menu alternativo). | Atalho inacessível. | 48dp + `customActions` ou remover nó. | Alta |
| P2 | `ui/editor/FormattingToolbar.kt:375-407` | Grid de cores `Box.clickable` sem nome/role/seleção. | TalkBack não identifica opções. | Semântica de cor/RadioButton. | Alta |
| P2 | `ui/settings/AppearanceScreen.kt:262-331` | `PaletteCard` seleção só visual; `TextureCard` rótulo fora do `Surface` clicável. | Estado não anunciado; alvo sem nome. | `selectable`/stateDescription; rótulo dentro. | Alta |
| P2 | `ui/editor/SectionCardEditor.kt:209` | `contentDescription = "Seção ${role.name}"` expõe enum interno em inglês. | Leitura genérica/confusa. | `friendlyRoleName` + `heading()`. | Média |
| P2 | `ui/copilot/ChatScreen.kt:1485-1491` | Títulos Markdown sem `Modifier.semantics { heading() }`. | TalkBack não navega por títulos. | `heading()` em `MdBlock.Title`. | Alta |
| P2 | `ui/stage/StageScreen.kt:162-167,301-312` | 15sp/11sp hardcoded; 11sp abaixo do confortável. | Escala com fontScale, mas base pequena. | `MaterialTheme.typography`, mínimo 12sp. | Alta |
| P2 | `ui/jw/JwDownloadDialog.kt:45-118` | Dialog sem título acessível/paneTitle; progresso sem descrição; foco no WebView. | Contexto/foco ruins. | `Window.setTitle`/paneTitle; descrição no progresso. | Média |
| P2 | `ui/stage/StageScreen.kt:64-91,236-286`; `ChatScreen.kt:1098`; `FormattingToolbar.kt:321-329` | Cores hardcoded fora de `MaterialTheme.colorScheme`. | Estados não acompanham tema; contraste não medido. | Tokens semânticos; manter paleta do Ensaio documentada. | Alta config |

**Verificado OK em A6:** `IconButton`/`Surface(onClick)` do Material3 aplicam mínimo de 48dp; diálogos/sheets usam `onDismissRequest`; não encontrei `fontScale` desativado nem texto em `dp`; o tema tem `ContrastAuditTest`; a maioria dos componentes usa `MaterialTheme.colorScheme`.

---

## Ordem de correção recomendada (impacto × esforço)

| Ordem | Item | Sev | Esforço | Por quê |
|---|---|---|---|---|
| 1 | Identidade de seção/sub-ponto no sync (`id`, não `order`; `@Upsert`; reconciliação) | P0 | L | Perda/ressurreição de dados reais |
| 2 | Importações fora da Main (`Outline`, `Library`) + cleanup do `.tmp` | P0 | S/M | ANR em fluxo comum |
| 3 | Ownership do TTS (`shutdown` só no topo; VMs `stop()`) | P0 | S/M | Recurso central morre após navegar |
| 4 | Flush do autosave em Back/`onCleared` | P1 | S | Perda da última edição |
| 5 | Transações em `deleteForever`/`Library.delete` + limpar chat | P1 | S | Ressurreição e dados órfãos |
| 6 | Mapeamento completo no sync (`folderId=null`, `discourseType`, `tribunaJson`) | P1 | M | Campos somem/voltam |
| 7 | Backup/API key (excluir do backup ou cifrar) | P1 | S/M | Privacidade/credencial |
| 8 | A11y quick wins (swatches, Ensaio, linhas compactas, label do composer) | P1 | S | TalkBack/toque nas telas principais |
| 9 | RAG/regex fora da Main | P1 | S/M | Jank em cada mensagem |
| 10 | LWW/trash policy + testes de dois devices | P1 | L | Consistência multi-aparelho |
| 11 | P2 em lote (tombstones, PDF, WorkManager, logs, headings) | P2 | M | Polimento/robustez |

## Cobertura de testes

| Área | Cobertura atual | Lacuna |
|---|---|---|
| Verifier | 28 testes (`GroundednessVerifierTest`) + 11 de avisos | REMOÇÃO estrita de REF, métricas em produção |
| Sync | fakes JVM + funções puras | UNIQUE/REPLACE por `order`, dois devices, trash remoto |
| Room | testes SQL de migração | `MigrationTestHelper`/schema exportado |
| Import | parsers unitários | main-thread/ANR, zip bomb/OOM |
| TTS | testes de callback/fakes | lifecycle real, shutdown entre telas |
| A11y | `ContrastAuditTest` | TalkBack/semântica/touch targets |
| Backup/privacy | — | backup/restore, Keystore, logcat de release |

**Suíte no momento da auditoria:** 2095 testes, 0 falhas. O número inclui os testes de política do chat adicionados nesta rodada.

## Limitações

- Análise **estática**; não executei StrictMode, profiler, heap dump, TalkBack, `adb backup` nem sync com dois aparelhos.
- Linhas podem deslocar após commits; os P0/P1 marcados ✅ foram reconferidos no código, os demais vêm da leitura dos arquivos citados.
- Severidade de ANR/jank é qualitativa (tamanhos de 10 MB/400 MB e ranking de 2.000 itens).
- Regras do Firestore podem divergir do Console; não há `storage.rules` nem uso de Storage.
- Nenhum segredo foi impresso; `google-services.json` está ignorado e não tracked.
