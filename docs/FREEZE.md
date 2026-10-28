# Itens congelados / decisões pendentes

**Atualizado:** 2026-10-28 · **Build de referência:** v2.8.2 (versionCode 27) · **Fase 2:** concluída (`506f813`) · **Fase 3:** concluída (`ebdcd92`) · **UX:** concluído (`5e5e1fa`) · **Confiança:** concluída (`ae041b4`) · **Agrupamento:** concluído (`93c7b02`) · **Refs unificadas:** concluído (`c53c0b7`) · **Atalho ✨:** concluído (`fe63742`) · **T3-A:** concluído (`83a76e1`) · **Geração longa:** concluída (`1485b2d`) · **C1 — orçamentos:** concluído (`938c0f7`) · **C2a — prefixo de página:** concluído (`41c546d`) · **P0 voz:** concluído (`8ac6c0e`) · **A1/A2:** concluído (2.8.1/26) · **Release v2.8.2:** em **draft** (`4f4efa1c…dbd5a5`) até o teste de restauração da keystore em outra máquina · **Release v2.8.1:** em **draft** como checkpoint (`1397ca64…a93133`)

> “Congelado” = não reintroduzir sem decisão explícita. “Decisão pendente” = o
> código/schema pode existir, mas o produto ainda não fechou o comportamento.

> **C2:** mergeado na `main` em `fb47f52` (fast-forward), **sem tag**. A
> v2.9.0/28 só depois da validação no tablet e do teste da keystore em outra
> máquina; o v2.8.2 continua **draft** e não inclui o C2.

> **M1/M1.1:** mergeados na `main` em `ffeb1e3` (fast-forward), **sem tag**.
> O turno único e o CANCELLED persistido estão validados no A34. A próxima
> frente é o card-first; a v2.9.0 continua sem tag até tablet + keystore.

> **Card-first fase 1:** mergeado na `main` em `03004b1` (fast-forward),
> **sem tag**. Alvo capturado na geração, append/replace, undo, gate “Sim”,
> stale, SUPERSEDED, verificação warn-only e “Transformar em card” validados
> no A34; **2.601 testes / 0 falhas** e `lintRelease` verde. A validação no
> tablet segue pendente; a v2.9.0 só sai depois de tablet + keystore.

> **Card-first — estados A34-only:** mergeado na `main` em `cda89e6`
> (merge commit), **sem tag**. `NO_TARGET`/`USER_PICK`, `TARGET_MISSING`,
> `FAILED` (retry da ação certa), `CANCELLED` read-only e SUPERSEDED sem
> alvo validados no A34; relatório em
> `docs/reports/validacao-2026-10-28-card-first-estados.md`.

> **Card-first fase 2:** mergeado na `main` em `1b989e3` (merge commit),
> **sem tag**. Regra única em `CHAT_TAIL_INSTRUCTIONS`, “um card por
> resposta”, segundo bloco vira texto com aviso e telemetria `EMIT`.
> Validado no A34 na rota de chat; a rota oratória legada ainda usa
> ProposalUi (fase 4). `main`: **2.608 testes / 0 falhas**, lint/CI verdes.

## Congelados / removidos

| Item | Status | Observação |
|---|---|---|
| Web v1 (Capacitor/Vite) | **REMOVIDO do produto** | Resíduos de build em `public/`, `tools/e2e-*.cjs`, `vite.config.ts`, `tsconfig*.json` são legado congelado; não entram no APK. Remover ou arquivar é limpeza pendente. |
| Multi-LLM (Gemini, Groq, Qwen, Gemma, fallback, circuit breaker) | **REMOVIDO** | DeepSeek-only (BYOD) + motor determinístico offline. |
| Kokoro TTS / bench de vozes | **REMOVIDO** | Piper “Jeff” 100% offline nos assets. |
| Testes de vozes obsoletos (`KokoroVoicesTest`, `KokoroBenchTest`, `PiperVoicesTest`) | **REMOVIDO** | — |
| `activity_main.xml` | **REMOVIDO** | Compose é a UI. |
| Consideração V&M (“O que você diria?”) | **REMOVIDO do fluxo de colagem** | Rejeitada com aviso; não cria nota (decisão 1b, 2026-10-25). |
| Logs `hs_err_pid*.log` / `replay_pid*.log` | **FORA DO VERSIONAMENTO** | Cobertos pelo `.gitignore`; não commitar. |

## Resolvidos na Fase 2 — 2026-10-25

Commit `506f813` · validação no A34 (S-34 novo com 30 min; Tesouros novo com 10 min) ·
**2273 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Título da nota V&M com tempo | Preview e nota criada sem `(N min)`; o tempo fica nos cards. |
| Minutos de INTRO/CONCLUSÃO no S-34 | O total declarado é respeitado; BODYs normalizados (30 = 1+5+11+7+5+1). |
| Contador de palavras do S-34 | Separa `developed` de `outline`: “0 desenvolvidas · N no esboço” + dica “desenvolva este ponto”. |
| Entrada do Copilot no S-34 | ✨ movido para o cabeçalho do card ativo (S-34 longo e V&M). |
| Rodapé/ruído no parser S-34 | `A SER ABRANGIDO EM N MINUTOS` vira total declarado; `N.° …-T …` é descartado. |
| Espaços internos em palavras do S-34 | `stripXml` remove tags inline sem inserir espaço; blocos viram quebra de linha. O caso “Jeov de de deá” do A34 era draft/edição, não parser. |

## Resolvidos na Fase 3 — 2026-10-25

Commit `ebdcd92` · validação no A34 (discurso 10 min/3 pontos; demonstração livre
4 min) · **2293 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Criar do zero | `StartFromScratchSheet` (ModalBottomSheet) com título, tipo, duração, pontos e prévia; botão desabilitado se inválido. |
| Estrutura sugerida | `StructureSuggestion`: Intro 1 + N pontos + Conclusão 1, distribuição `base + remainder`; demo livre = 1 BODY. |
| Criação atômica | `createNoteFromStructure` usa `createPastedNote`; seções nascem vazias. |
| Demonstração livre | Novo `FREE_DEMO`, 1 BODY, sem S-38; persona neutra `FREE_DEMO_SCRIPTWRITER`/`FREE_DEMO_CHAT_HINT`. |
| Parte da reunião | Fora do “Criar do zero”; continua via colagem V&M. |
| FREE_DEMO no app | Validator (single body), editor, Hub (badge “Demonstração”), Tribuna e Ensaio (turnos). |

## Resolvidos no bloco UX — 2026-10-25

Commit `5e5e1fa` · validação no A34 (S-34, V&M, FREE_DEMO) · **2293 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Card minimalista | Minutos, ✨ e ⋮ no cabeçalho; badge “Demonstração” só no hub; rodapé com handle + chips de demo. |
| Linha de raciocínio | Colapsável: vazia = “+ Estrutura”; até 5 aberta; acima de 5 fechada com contagem. |
| Dica vazia | Texto “Converse com o Copilot…” removido; placeholder do editor assume. |
| Acessibilidade | Alvos de 48dp (swatches, linhas compactas, handle, menu), labels/headings, seção em português, grid de cores selecionável. |
| Validação | A34 via uiautomator (content-desc/bounds) em Home, Editor e Chat; TalkBack/Scanner manual ficam para a revisão. |

## Resolvidos no bloco Confiança — 2026-10-25

Commit `ae041b4` · validação no A34 (S-34, V&M, FREE_DEMO) · **2307 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| lmd apêndice | `RefDetector` reconhece `lmd apêndice A ponto N`, casa a edição e usa o ponto no conteúdo. |
| Checklist | `NoteSourcesResolver` coleta outline + seções + subpontos + plainText/mdText, deduplica e classifica. |
| UI | Chip “Fontes X/Y” no editor + `SourcesSheet`; `RESOLVED`, `EDITION_MISSING` (“Baixar/Importar”) e `TEXT_NOT_FOUND` (“Abrir edição”). |
| Sem fonte | Nota sem refs mostra “Esta nota não cita fontes…” no sheet (menu ⋮) e não exibe chip. |
| Prontidão | `readNoteReadiness` e `SetupBanner` passam a considerar refs da nota, não só outline. |
| Prompt | Inalterado; o checklist é preventivo e informativo. |
| Validação A34 | `lmd apêndice` e refs bíblicas/publicações no chip/sheet; `EDITION_MISSING` e `RESOLVED` vistos. `TEXT_NOT_FOUND` coberto por teste puro. |

## Agrupamento do sheet — 2026-10-25

Commit `93c7b02` · validação no A34 (S-34 0/69; V&M 0/2; Tesouros 5/7) ·
**2314 testes JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Agrupamento primário | Publicação: TNM (`nwt`) única; `w25.07`, `lmd`, `th`… por edição. |
| TNM | Subgrupos por livro, ordem canônica; refs por capítulo/versículo. |
| Ordem | Publicações específicas faltantes primeiro, TNM, publicações específicas OK. |
| Expansão | ≤3 aberto; >3 fechado; TNM fechada; livros da TNM ≤3 abertos. |
| Resumo | X de Y + contagem `publicação resolvidas/total`. |
| Ações | Por grupo (baixar publicação; Abrir Biblioteca na TNM) e por item. |
| Acessibilidade | Cabeçalhos com contagem/status e alvos de 48dp. |

## Refs unificadas e aquisição — 2026-10-25

Commit `c53c0b7` · validação no A34 e no tablet (S-34 54/69; V&M 1/2;
TNM 53/53; download/importação de `g_T_200711.jwpub`) · **2339 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Coletor único | `NoteRefsCollector` lê título, mdText/plainText, outline, seções e subpontos; dedup canônico. |
| Copilot | `citedSources`/`refScopeIds` recebem a mesma lista do checklist; Bíblia da TNM vira evidência. |
| Código de refs | `PublicationRef.article/chapter` sobrevivem ao JSON de seções/subpontos. |
| Aquisição | Ação primária “Baixar no jw.org” abre o `JwDownloadDialog` (WebView), nunca download direto por API. |
| Já baixei | “Já baixei — procurar em Downloads” abre a Biblioteca com `scanDownloads` automático. |
| Estados | `DOWNLOADING`/`INDEXING` no checklist; `attachmentDao.observe()` revalida sozinho até `No acervo`. |
| URLs antigas | Sentinela dia 1 usa `wpYYYYMMDD` (pública); dia 15 usa `wYYYYMMDD` (estudo). |
| Biblioteca | “No acervo” só com anexo indexado; falha/placeholder não conta. |
| Prompt | O caminho de geração não mudou; o coletor só amplia as refs que chegam ao prompt. |

## Correção do atalho ✨ — 2026-10-25

Commit `fe63742` · validação no tablet (S-34, V&M, FREE_DEMO) · **2339 testes
JVM verdes**.

| Item | Decisão/ação |
|---|---|
| Sintoma | O ✨ não aparecia no cabeçalho de cards no tablet; o editor rico consumia o toque que ativava a seção. |
| Correção | O atalho aparece em TODO card (não só no ativo) e o foco no editor/subponto ativa a seção. |
| Alvo | 48dp no cabeçalho; `contentDescription="Copilot deste ponto"`. |
| Validação | S-34 (2 cards), V&M (3 cards) e FREE_DEMO (1 card); clique abriu o chat com “Contexto: …”. |
| Chat E2E | S-34 → ✨ do ponto → pergunta “Mateus 7:13”; resposta usou Mateus 7:13/14 e Romanos 6:23, refs de subpontos. |

## Ensaio com análise (T3-A) — 2026-10-25

Commit `83a76e1` · validação no A34 (Stage S-34) · **2375 testes JVM
verdes**.

| Item | Decisão/ação |
|---|---|
| Entrada | STT nativo em modo sessão (`SessionSpeechRecognizer`), com reinício por segmentos. |
| Controle | Iniciar → Pausar → Retomar → Parar; pausa/erro preservam a transcrição; Result só após Parar. |
| Estados | `AnalysisState`: Idle, Listening, Paused, Processing, Interrupted, Result. |
| Erros | Silêncio reinicia; falha persistente vira Interrupted (Retomar/Parar), nunca Result automático. |
| Métricas | `SpeechMetrics`: speechDurationSec (wpm), sessionDurationSec (contexto), palavras, cobertura e muletas. |
| Feedback | `SpeechCoach`: 4 blocos + 1 exercício; sem nota/julgamento; pausas não penalizam o ritmo. |
| UI | Microfone 48dp, banner “Ouvindo/Pausado/Interrompido”, sheet com Fala/Sessão, blocos, TTS e descarte. |
| Privacidade | Áudio não é gravado; transcrição só em memória e descartada ao fechar/calcular. |
| Offline | Serviço **padrão do sistema** (mesmo caminho dos apps que funcionam); sem `EXTRA_PREFER_OFFLINE`/extras de silêncio. O reconhecimento pode usar rede; a UI avisa. |
| Calibração | Relógio de fala começa no primeiro texto reconhecido; `hasEnoughSpeech` exige ≥20 palavras, ≥10 s e ≥70 wpm; ensaio parcial não cobra cobertura/tempo total; aspas duplo-escapadas decodificadas. |
| A34 | Pausar/retomar 2× sem perder tempo/transcrição; banner Pausado congela; resultado `Fala: 18 s · Sessão: 1 min 7 s`; Interrupted com Retomar/Parar; CTA de permissão negada; `Pouca fala capturada` validado no sheet. O STT do A34 falhou com 37 palavras/45 s (49 wpm) e depois `ERROR_SERVER_DISCONNECTED`/`TOO_MANY_REQUESTS`; captura real segue pendente no tablet. |

## Geração longa com tempos corretos — 2026-10-26

Working tree sobre `275709a` · validação real no A34 · **2.445 testes JVM
verdes no tree atual** (o bloco long-text adiciona ~50; há mudanças paralelas
de contexto/refs não commitadas).

| Item | Decisão/ação |
|---|---|
| Regra de tempo | Geração de seção usa `section.minutes`, nunca o total. Demo V&M: 1 BODY, tempo da parte, `minutos × 2,5` réplicas. Discurso: Intro 1 + N BODY + Conclusão 1; BODYs usam `total - 2`. S-34: cada seção pelo seu tempo; soma = total. |
| `LongTextRequest` | `kind` SPEECH/DEMO, `scope` SECTION/TOTAL, `minutes`, `sectionMinutes`, `targetWords`/`targetTurns`. Pedido explícito diferente da seção vira `NeedsConfirmation` (o chat avisa e mantém o tempo da seção). |
| `budgetForRequest` | Discurso `palavras×1,5+200`; demo `réplicas×80+200`; coerce 2.048..8.192; acima de 8.192 divide em partes iguais. `reasoning=none`. |
| DeepSeek | `max_tokens` até 8.192; retry por `length` (até 8.192); se o endpoint rejeitar 8.192, cai uma vez para 4.096 + divisão. |
| Extensão | `section.minutes` vira extensão alvo no prompt. Card abaixo de 80% da meta com `finish=stop` dispara até 2 correções: a 1ª reescreve com piso; a 2ª reenvia o texto anterior, pede expansão dirigida e sobe o orçamento para 4.300. |
| `OutputBudget.sectionDraft` | Teto 8.192; seção de 20+ min com subpontos prefere `DraftTarget.SubPoint` (primeiro subponto); 45+ min usa 8.192. Sub-ponto usa a fatia proporcional de `section.minutes`. |
| “Texto total” | O chat não tenta um texto único: monta a fila por seção (20+ min por subponto) e abre o **DraftSheet** com “Próximo (N)”. Intro/Conclusão por seção; demo = 1 BODY. |
| Prompt/chat | `blockMinutes = section.minutes`; `CHAT_TAIL_INSTRUCTIONS` e `SPEECH_MENTOR_CHAT_HINT` respeitam o bloco e não o discurso inteiro; a “TAREFA DE TEXTO LONGO” excepciona a regra de parágrafos curtos. |
| Estrutura | `StructureSuggestion`/`PasteNoteBuilder` garantem Intro 1 + Conclusão 1 e soma = total; colagem V&M com total < 3 é ajustada para 3; demo 1 BODY. |
| Telemetria | `generation` com `route`, `budget`, `finish_reason`, `chars`, `words`, `truncated`, `hasCard`, `thinking` e `retries` no chat, oratória, draft, proposta, estrutura, minimal, outline e tribuna. |
| A34 — demo 4 min | Parte real “Cultivando o interesse (4 min) TESTEMUNHO INFORMAL… lmd lição 9 ponto 5” criada do zero: **11 réplicas**, 177 palavras no card, `budget=2048`, `finish=stop`, `truncated=false`. |
| A34 — demo 5 min | Parte real “Fazendo discípulos (5 min) ESTUDO BÍBLICO… lmd lição 12 ponto 2” do zero: **12 réplicas**, 185 palavras no card, `budget=2048`, `finish=stop`, `truncated=false`. |
| A34 — discurso 10 min | “Ter esperança é o segredo para o contentamento”: **1.159 palavras no card**, `budget=2150`, `finish=stop`, `truncated=false`, após 1 retry de extensão; ~89% da meta de 1.300 e acima do piso de 80%. |
| A34 — “texto total” | Nota “A ordem nos céus (10 min)”: o chat abriu o DraftSheet na Seção 1 e mostrou “Próximo (4)”; aceite aplicou a Intro e o Desfazer reverteu. O parcial JSON do draft não vaza mais para a bolha do chat. |
| Limite conhecido | O modelo ainda pode “fechar curto”; o piso de 80% + até 2 correções resolvem o caso de 10 min. Na medição final, 1 correção bastou. Para 20+ min, a divisão por subponto é o caminho preferido. |

## Resolvidos no C1 — orçamentos por caminho (2026-10-26)

Commit `938c0f7` · **2.518 testes JVM verdes** · CI da branch · matriz final no A34 em andamento.

| Item | Decisão/ação |
|---|---|
| Fonte única | `domain/planning/OutputBudget.kt` com funções nomeadas; `LlmRequest.maxOutputTokens = 0` significa “resolver pelo caminho”, nunca 1.000 silencioso. |
| `continueGeneration` | 1.024–8.192; usa palavras/réplicas restantes da meta persistida no payload (`targetWords`/`targetTurns`). |
| `createProposal` | 1.500–4.096; foco + nº estimado de operações; `finish=length` vira aviso, não proposta parcial. |
| `requestApplyStructure` | 4.096–8.192; acima de 30 min divide em partes com títulos explícitos e une as respostas; aviso se alguma parte cortar. |
| `answerMinimal` | 2.048–4.096; “texto completo” no modo colar é desviado para o DraftSheet (nunca resposta única). |
| `OutlineGeneratorImpl` | 1.500 fixo (TPM de 8K); `finish=length` retorna null e loga telemetria. |
| `generateOratory` | 1.280–4.096 por duração da seção/foco (substitui o 1.280 fixo); corte visível. |
| `SectionGeneratorImpl` | `OutputBudget.sectionDraft` por `section.minutes` ou pela fatia do sub-ponto; `possiblyTruncated` preservado. |
| Retry | `maxOutputTokensCap` por caminho; o provider sobe o budget no `length` até o cap e informa `retries` na telemetria (inclui extensão/fallback). |
| A34 — chat curto | `budget=2048`, `finish=stop`, 189 palavras, `retries=0`. |
| A34 — seção 5 min | `budget=2048`, `finish=stop`, 748 palavras, `retries=0`. |
| A34 — seção 15 min | `budget=3125` inicial → retry por `length` → `6250`, `finish=stop`, 2.311 palavras, `retries=2`. |
| A34 — seção 20 min | `budget=4100`, `finish=stop`, 2.327 palavras, `retries=1`. |
| A34 — seção 30 min | `budget=6050`, `finish=stop`, 3.491 palavras, `retries=1`. |
| A34 — seção 45 min | “Texto completo” em 10 partes; a seção de 45 min com 5 subpontos virou 5 alvos + intro/conclusão; nenhuma resposta única. |
| A34 — demo 4 min | `budget=2048`, `finish=stop`, 10 réplicas, 286 palavras, `retries=0`. |
| A34 — proposta/“Sim” | `route=proposal budget=1500 finish=stop`; “Sim” aplicou sem logcat de LLM; undo disponível. |
| Gaps | Seção 45 min sem subpontos ainda usa um draft no teto 8.192 (truncamento visível); V&M discurso 5 min não reexecutado do zero; `lmd_T.jwpub` continua `failed`. |
| Relatório | [validacao-2026-10-26-a34-c1.md](reports/validacao-2026-10-26-a34-c1.md). |
| Testes | `ContinueGenerationBudgetTest`, `ProposalBudgetTest`, `StructureBudgetTest`, `MinimalBudgetTest`, `OutputBudgetCapTest`, `TruncationRetryTest`, `StructurePartPromptTest`, `SectionDraftBudgetTest`. |



## Resolvidos na A — release seguro (2026-10-26)

Build 2.8.1/26 · **2.524 testes JVM verdes** · `lintRelease` verde · APK assinado
com certificado de release (RSA 4096) · SHA-256 `ca8a42a6…40e3cae`.

| Item | Decisão/ação |
|---|---|
| Backup | `allowBackup=false` + `fullBackupContent=false`; `bmgr backupnow` responde “Backup is not allowed”. Sem export/import JSON ainda, não há backup automático. |
| Chave DeepSeek | `KeystoreSecretCipher` AES/GCM, chave não exportável no Android Keystore. DataStore guarda `deepseek_api_key_encrypted`; migração automática do texto claro legado. Teste no A34: antes `sk-` presente, depois 0 ocorrências e conexão Online. |
| Keystore de release | Gerada fora do repo em `~/.better-talker-keystore/` (PKCS12, alias `better-talker`, RSA 4096, validade até 2054). `android/keystore.properties` e `*.jks` ignorados. **Backup offline ainda obrigatório.** |
| Assinatura | `signingConfigs.release` lê `keystore.properties`/env; `debuggable=false`; `apksigner verify` mostra o certificado de release. Release nunca usa a debug key. |
| Rede/WebView | `usesCleartextTraffic=false` + `network_security_config`; WebView do jw.org só HTTPS e sem `allowFileAccess`/`allowContentAccess`; PDF export offline com `blockNetworkLoads`. |
| Sync | Sem `google-services.json`, `SyncScheduler` não agenda worker; sync permanece desligado/experimental. |
| ABI | `arm64-v8a` apenas. Documentado em [RELEASE.md](RELEASE.md). |
| CI | `.github/workflows/android-release.yml`: testes, `lintRelease`, `assembleRelease`, `apksigner`, SHA-256 e publicação no GitHub Releases por tag. Dry-run [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) **success**; APK da CI `dd49abf1…a08781`, baixado e instalado no A34. |
| Secrets | Os quatro `BT_*` + `GOOGLE_SERVICES_JSON` configurados; `gh secret list` confirma os nomes; `github-secrets.txt` apagado. |
| A2 — A34 | Instalação limpa do release; chave salva/reiniciada/removida; S-34 `S-34_T_192.docx` importado; V&M 5 min (1+1+1+1+1) criado; demo 4 min `budget=2048/stop/hasCard`; chat real Online. Relatório: [validacao-2026-10-26-a34-release-2.8.1.md](reports/validacao-2026-10-26-a34-release-2.8.1.md). |
| A2 — smoke restante | No APK da CI: PDF A4 com texto/formatação ok (1 página, 17,8 KB), HTML share sheet ok, TTS play/stop/background ok; STT falhou no serviço do sistema (`error=10`) com fallback Interrupted/“Pouca fala” e sem áudio gravado; `w25.07` no acervo, mas chat ainda `refs=0` (C2); `lmd` “Faltando” com ação de download. |
| Release preparado | Tag `v2.8.1` → build `7eb6785`; release em **draft**; APK `1397ca64…a93133` + `.sha256`; baixado, assinatura conferida e instalado no A34. Publicar só após o teste de restauração da keystore. |
| Escopo v2.8.1 | **Opção A — security-only.** `refs=0` é limitação C2, não regressão do C1. Refs/TTS/STT P0 ficam para v2.8.2. |
| Pendente | Confirmar duas cópias offline da keystore; TTS troca de voz (sem seletor exposto); refs no chat (C2); aparelho limpo de um amigo. |

## C2a — refs com prefixo de página (2026-10-27)

Branch `feat/v2.8.2-c2a` (a partir da main `0174496`) · **2.533 testes JVM / 0 falhas** · `lintRelease` verde.

| Item | Decisão/ação |
|---|---|
| Detector | `W_CODE_RE` aceita `p.`, `p.22`, `pág.`, `página` e `pg.` antes da página; mantém `§`/`§§` e ranges. |
| Rótulo | `resolveByPageParagraph` limpa a cauda do `symbol` para não duplicar `pág. 22 §8`. |
| Testes | 8 casos puros em `RefDetectorTest` + integração `detect → resolvePublication → publicationHitOf` em `RoomReferenceResolverTest`. |
| A34 | “o que w25.07 p. 22 §8 diz sobre este ponto?” → `1 trecho(s) de publicação da pergunta injetado(s)`; resposta usa Baruque/§8. Controle “w25.07 22 §8” também injetou 1. |
| Relatório | [validacao-2026-10-27-a34-c2a.md](reports/validacao-2026-10-27-a34-c2a.md). |

## P0 voz — coexistência TTS/STT (2026-10-27)

Branch `fix/v2.8.2-voice-coexistence` (a partir da main `41c546d`) · **2.538 testes JVM / 0 falhas** · `lintRelease` verde.

| Item | Ação |
|---|---|
| Stage | `startAnalysis()` para o TTS; “Ouvir” bloqueado durante Listening/Paused/Interrupted/Processing. |
| Chat | Microfone bloqueado durante TTS; “Ouvir” bloqueado durante escuta/processamento; `startVoiceInput()` para o TTS. |
| `VoiceRouter` | `speak`/`stop`/`shutdown`/`setPremiumEnabled` param os dois motores; troca de engine não deixa fala órfã. |
| `TextToSpeechManager` | Checa retorno de `speak`; `onStop`/`onError` limpam `_speaking`. |
| `SpeechRecognizerManager` | Timeout de 5 s no `finish()`; falha de `startListening` vira erro visível. |
| Testes | `VoiceRouterBehaviorTest`, `TtsSpeakResultTest`, `AnalysisStateTest` + suíte. |
| A34 | TTS→mic parou o TTS e abriu a análise; STT do sistema continua `error=10` com fallback Interrupted. |
| Relatório | [validacao-2026-10-27-a34-voice-p0.md](reports/validacao-2026-10-27-a34-voice-p0.md). |

## Decisões pendentes (não remover ainda)

| Item | Estado atual | O que falta decidir |
|---|---|---|
| Folders/tags/cores/pin na Home | Schema Room dormente; UI usa lista simples | **Reativar, remover de vez ou manter dormente?** Não apagar schema/migração sem decisão. |
| Backup / chave DeepSeek | **RESOLVIDO na A** | `allowBackup=false` + chave no Android Keystore (2.8.1/26). |
| Keystore de release | **RESOLVIDO na A** | Keystore fora do repo; falta apenas o backup offline e os secrets do CI. |
| Sync de seções/subpontos | P0 de 2026-10-19; correções posteriores | Revisar identidade/reconciliação multi-device antes de prometer sync. |

## Regra de processo

Antes de remover qualquer item “congelado” ou mexer em decisão pendente:

1. Registrar a decisão (data + motivo) neste arquivo ou no relatório da sessão.
2. Garantir teste JVM que trave o comportamento escolhido.
3. Validar no aparelho de referência (Redmi 9 / A34 / tablet) quando a mudança for de UX.
