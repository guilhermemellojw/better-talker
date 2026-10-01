# F19-A — Auditoria: por que o Copilot não compreende o S-34 como estrutura

**Fase:** F19-A (SOMENTE AUDITORIA — nenhum código alterado, nenhum comportamento modificado)
**Branch:** `main` · **Commit:** `98b3155` · **Tree:** limpo (verificado antes e depois)
**Data:** 2026-09-27

---

## 1. Executive summary

O Copilot não entende o S-34 como estrutura porque **em nenhum ponto da cadeia existe o conceito de "documento estruturado"**. O S-34 entra como bytes, vira texto corrido, é fatiado em frases/chunks planos (`passages` com `section` heurística + `ord` posicional), e o retrieval devolve chunks por score lexical — destruindo a ordem do documento. Não há detecção de S-34, parser de S-34, campo de objetivo, vínculo ponto↔referência persistido, filtro por ponto, nem menção a S-34 no prompt. O OutlineModel (`OutlineSection`) só conhece seções quando o texto contém marcadores `(N min)` ou quando o usuário cola/vincula manualmente — e mesmo assim guarda lista plana sem filhos, sem objetivo e sem refs por seção.

**Principal gargalo:** a perda acontece na **importação/indexação** (estrutura descartada no parse) e é **irrecuperável** nas etapas seguintes — retrieval, ContextPack e prompt operam sobre chunks sem árvore.

---

## 2. Current pipeline (arquivos e classes reais)

```text
S-34 (arquivo: pdf/docx/epub/jwpub/rtf/txt/zip)
 ↓  Importação — SEM detecção de tipo documental
 │   Web:   SpeechListModal → App.handleImportOutline (App.tsx:198)
 │          → parseOutline(buffer) (services/outlineParser.ts)
 │          Biblioteca: LibraryModal → libraryIndexer.indexPublication
 │   Android: LibraryScreen → LibraryRepository → AttachmentEntity
 │          → IndexPublicationWorker (data/work/IndexPublicationWorker.kt)
 ↓  Parser/extração — texto corrido, estrutura descartada
 │   Web DOCX: TextDecoder().decode(buffer) — .docx BINÁRIO NÃO extraído
 │          (mammoth está no package.json mas sem nenhum import fora de
 │          testes; importParse.test.ts documenta o fallback honesto)
 │   Web EPUB: JSZip, arquivos ordenados por NOME (≈spine), heading por
 │          arquivo ou nome do arquivo; stripHtmlTags (libraryIndexer.ts)
 │   Web PDF:  pdfjs getTextContent, items unidos com ' ' (estrutura zero)
 │   Android:  DocExtractors (data/util/DocExtractors.kt) —
 │          DOCX: w:t + \n em p/tc/tr/tbl (SEM headings/negrito/listas);
 │          PDF: PDFBox texto corrido (60 págs); EPUB: zip concatenado;
 │          RTF: stripRtf; ZIP: só .rtf com marcador === nome ===;
 │          JWPUB: JwpubExtractor (decrypt SQLite Document → texto)
 ↓  Chunking — frases planas
 │   Web:   3 frases/overlap 1, mín 20 chars (libraryIndexer.ts:82-104)
 │   Android: splitWithSections → 1 frase por passage (IndexPublicationWorker.kt:53)
 ↓  Storage — Room/Dexie, plano
 │   AttachmentEntity + PassageEntity (db/AppDatabase.kt:42-78)
 │   Web: publications/passages no Dexie (services/db.ts)
 ↓  Retrieval — lexical por score, SEM filtro estrutural
 │   RoomRetrievalRepository/RoomTrainingRepository
 │   (data/repo/RetrievalRepository.kt) → HybridRetrieval.rank
 │   (data/domain/HybridRetrieval.kt:142) — SEMANTIC_WEIGHT * 0.0
 ↓  ContextPack — 8 content + 4 training (+legado até 12)
 │   ContextPacks.fromCandidates (domain) / contextPack.ts (web)
 ↓  Prompt — regras de fidelidade, SEM cláusula S-34
 │   buildChatPrompt (data/copilot/ChatPromptBuilder.kt:142)
 ↓  LLM — GeminiProvider (data/llm/) / geminiProvider.ts
```

---

## 3. What is preserved (com evidência)

1. **Texto integral** — extração funciona para todos os formatos (com as perdas do §4).
2. **Ordem posicional bruta** — `ord` (índice da frase no doc) e `order` (web) são gravados; `attachmentId` liga passage↔arquivo.
3. **Rótulo de seção heurístico** — `PassageEntity.section`: regex `lição|capítulo|estudo|parte|seção + N` ou linha curta (4–80 chars) seguida de linha >120 chars (TextNorm.kt:227); EPUB usa heading/nome do arquivo.
4. **Trilho content/training/bible** — `sourceType` por slot (`be`/`th`→training) + `trainingCategory` só p/ training (IndexPublicationWorker.kt:66-76).
5. **Referências detectáveis sob demanda** — `RefDetector.detect` (publicações: MAGAZINE/BOOK com pubKey+editionKey+label) e `detectBible` (livro/cap/versículo); web `detectCitations` (services/citationDetector.ts:19).
6. **Resolução contra acervo local** — `matchEdition` + `downloadUrl` (página oficial/API); download **manual** via UI (JwDownloadDialog/ChatCards/ChatScreen).
7. **Seções de esboço do usuário** — `OutlineSection(title/minutes/order/body/level)` + `preamble`, ordem persistida em `sectionsJson` (mas SÓ via `(N min)` ou colagem manual).
8. **Proveniência por chunk** — `EvidenceSource` (reference/score/matchedTerms) até o prompt.
9. **Separação content×training no prompt** — trilhos serializados em blocos distintos com anti-atribuição (`ChatPromptBuilder.kt:48`).

---

## 4. What is lost (com o ponto exato da perda)

| # | Informação | Onde morre | Evidência |
|---|---|---|---|
| 1 | Tipo documental (isto é um S-34) | Importação | nenhum `detectS34`/classificador em `src/`, `android/`, `tools/`, `docs/` (grep vazio); `AttachmentEntity` não tem campo de papel/tipo |
| 2 | Headings/negrito/listas/tabelas | Extração | DOCX: só `w:t` + `\n` (DocExtractors.kt:79-87); web DOCX: nem extrai (TextDecoder); PDF: `join(' ')` / PDFTextStripper corrido |
| 3 | Objetivo/visão geral/instruções | Modelagem | `OutlineSection` não tem campo; só `preamble` livre + `title` (OutlineParser.kt:12-26) |
| 4 | Hierarquia ponto→subponto | Storage | `sectionsJson` é lista PLANA (`level` é inteiro solto, sem filhos); `childTitles()` deriva filhos em query-time, não persiste |
| 5 | Vínculo ponto↔referência | Toda a cadeia | `refsJson` do outline é global (uma lista p/ o esboço inteiro, detectada sobre o texto colado inteiro — CopilotViewModel.kt:1961); `postSectionRefs` detecta por seção só em runtime de chat e não persiste (CopilotViewModel.kt:1305) |
| 6 | Ordem do documento no retrieval | Retrieval | `HybridRetrieval.rank` ordena por score (final→lexical→metadata→id), nunca por `ord` (HybridRetrieval.kt:170-175); nada reordena por `(attachmentId, ord)` depois |
| 7 | `ref`/`page`/`paragraph` no Android | Indexação | `IndexPublicationWorker` grava só text/normalized/section/ord/trainingCategory — `ref`/`page`/`paragraph` ficam nos defaults vazios (linhas 66-77); só o web preenche |
| 8 | Filtro por ponto/seção/documento-aberto | Retrieval | `RoomRetrievalRepository.retrieve(query, scope, limit)` — sem parâmetro de ponto/seção; escopo = bases+vinculados+citados, nunca "o S-34 aberto" |
| 9 | Cláusula S-34 no prompt | Prompt | grep `S-34\|esboço\|outline` em `ChatPromptBuilder.kt` = vazio; SYSTEM_PROMPT só cita BE/TH |
| 10 | Sub-ideias (A/B/C dentro do ponto) | Modelagem | corpo da seção é texto plano; sem modelo de ideia |

---

## 5. Reference mapping

**Veredito: não existe.**

```text
Desejado:   S-34 → ponto → subponto → Bíblia/publicação (persistido)
Real:       texto → [detect on-demand] → lista plana sem dono
```

- Detecção existe e é boa (revistas por código `w24.12`, mensais por extenso, siglas `lff cap. 5`, livros por nome, versículos) — mas opera sobre **textos inteiros** (nota, `title+\nbody` da seção no chat, texto colado no paste).
- `RefStatus` (resolved/fileName/downloadUrl/exact/apiPub) responde "tenho o arquivo?" — nunca "de qual ponto veio?".
- Repetição entre pontos: cada detecção é independente; sem identidade canônica compartilhada (o `editionKey` poderia servir, mas não é usado como chave de ligação).
- Ordem: preservada na lista detectada, mas descartada ao serializar (só `refsJson` global do outline).
- Exceção parcial: `repo.refPassages(ref, 2)` filtra trechos pelo capítulo/lição citado (`filterByChapter`) — ligação ref→trecho, não ref→ponto.

---

## 6. Structural understanding gap

| Capacidade (perguntas A–H, §14) | Hoje | Por quê |
|---|---|---|
| A. objetivo do discurso | ❌ | sem campo; `preamble`/`title` são texto livre não-rotulado |
| B. pontos principais | ⚠️ | SÓ se `(N min)` no texto ou colagem manual vinculada |
| C. sequência dos pontos | ⚠️ | ordem de `sectionsJson` quando B existe; retrieval ignora |
| D. textos bíblicos do ponto 2 | ❌ | só transiente em `postSectionRefs`; nada persistido |
| E. publicações do ponto 3 | ❌ | idem (`refsJson` é global) |
| F. desenvolver ponto 2 sem sair | ⚠️ | `ideasForSection` usa título/corpo+`childTitles`+retrieval, sem guardrails estruturais |
| G/H. introduzir/concluir | ❌ | nada infere intro/conclusão de um S-34; `exampleKindFor` INTRO/CONCLUSION gera modelos, não lê esboço |

Sem fixture S-34 no repo (busca vazia) — **nenhuma das respostas acima foi validada contra documento real**. Como o S-34 real apresenta objetivo/intro/conclusão é **desconhecido pelo código** (nenhum parser conhece o layout; §6 da spec não pode ser respondido por evidência interna).

---

## 7. Web vs Android

| Capacidade | Web | Android |
|---|---|---|
| Importa S-34 (pdf/docx/epub) | ⚠️ parcial (DOCX binário NÃO extrai; EPUB/PDF sim) | ✅ texto (DOCX/EPUB/PDF/RTF/ZIP/JWPUB/TXT) |
| Detecta S-34 | ❌ | ❌ |
| Preserva estrutura | ❌ (ordem de blocos só via `(N min)`; chunks planos) | ❌ (`ord`+`section` heurística; sections planas) |
| Extrai referências | ✅ sob demanda (`detectCitations`) | ✅ sob demanda (`RefDetector` + `detectBible`) |
| Liga referência ao ponto | ❌ | ❌ (transiente em `postSectionRefs`) |
| Recupera ponto atual | ❌ (chunks por similaridade) | ❌ (idem; `matchSection` casa título, não recupera ponto) |
| ContextPack estruturado | ❌ (8+4 flat por score) | ❌ (idem) |
| BE/TH separado | ✅ (`speech_training`, trilhos) | ✅ (`sourceType`, trilhos) |
| Introdução inferida | ❌ (F9 analisa o que o usuário escreveu) | ❌ |
| Conclusão inferida | ❌ | ❌ |

---

## 8. Legacy paths (runtime real, sem remover)

**Com chave (rota nova F18):** `send()` → `inferIntent` → `buildTurnFor` (Room pack) → `GeminiProvider` → thread → proposta (`data/edit/`) → verificação (`data/verify/`). Contratos F16 (`ChatEngine`, `ChatPromptBuilder`, `ChatContext`, `ConnectivityObserver`) consumidos.

**Sem chave (rota legada, mantida por decisão F18 §46):** `send()` → 6 resolvers (`resolveConfirm/Guide/DraftCommand/FollowUp/More`) → `ChatIntent.classify` (18 intents) → 15 `answerXxx` → `CopilotRepository.askScoped` + `partitionGuideHits` (split por **substring de título**, paralelo ao `sourceType` estrutural — dois cérebros de trilho). `LlmService`/MediaPipe só no caminho `Compose`, com modelo nunca configurado.

**Compartilhado:** `ChatCodec`/`ChatEntity`, `RefDetector`, `OutlineParser`, `NotesRepository`.

---

## 9. Recommended architecture (conceitual — NÃO implementar)

```text
OutlineDocument                            ← 1 por attachment S-34 detectado
 ├── metadata (fileName, sourceRef, importedAt)
 ├── objective   (texto rotulado, pode ser "" se ausente)
 ├── overview    (preamble/instruções rotuladas)
 ├── sections[]
 │    ├── id (estável: hash do caminho)
 │    ├── order     (índice explícito — fonte da sequência)
 │    ├── level     (hierarquia real, com children[])
 │    ├── title
 │    ├── content
 │    ├── subsections[]
 │    └── references[]  (BibleRef | PubRef COM offsets — persistido)
 ├── references[]        (união com identidade por editionKey)
 └── inferredOratoryStructure
      ├── introduction  (seção candidata + motivo, nunca inventada)
      ├── development   (ordem exata das seções)
      └── conclusion    (seção candidata + motivo)
```

Regras: parser S-34 separado do extrator genérico; ordem vem de `order`, nunca de score; retrieval com filtro `sectionId` opcional; ContextPack ganha bloco `--- ESTRUTURA DO ESBOÇO (ordem) ---` antes dos chunks; prompt declara `S-34 = fonte estrutural; BE/TH = como apresentar`.

---

## 10. Recommended next phase

**F19-B — S-34 structural intelligence (implementação)**, em fatias:
1. **Detecção + fixture**: classificador `isS34` (marcadores reais do layout) + 1 S-34 anonimizado como fixture versionada; sem fixture, nada se valida.
2. **Parser S-34 → OutlineDocument** (puro/testável): objetivo, seções ordenadas com `order` explícito, subseções, refs com offsets; reutilizar `RefDetector`/`detectBible` por seção e PERSISTIR o vínculo.
3. **Storage**: estender sem quebrar (`outline_documents` ou campos novos; `ord` continua; índice por `sectionId`).
4. **Retrieval estrutural**: filtro opcional por documento/seção + modo "ponto atual" (seção interia, ordem preservada) ao lado do modo lexical.
5. **ContextPack + prompt**: bloco de estrutura ordenada + cláusula S-34 no SYSTEM_PROMPT; manter insuficiência honesta.
6. **Perguntas A–H como testes de aceitação** contra a fixture.

Fora de escopo (manter backlog): download automático de citadas, streaming, memória entre sessões, semântica, DOCX 2.0.

---

## Anexo — baselines (§20)

```text
Web:      231/231 (vitest)
Android:  291/291 (testDebugUnitTest, failures=0 errors=0)
Build:    :app:assembleDebug BUILD SUCCESSFUL
Lint web:     6 warnings, 0 errors (oxlint — baseline inalterado)
Lint Android: 2 errors, 44 warnings, 3 hints — os 2 erros são pré-existentes
              e intocados (activity_main.xml MissingClass; JwDownloadHost.kt
              ContextCastToActivity), idênticos ao baseline F18
```

Princípios de segurança (§19) preservados pela auditoria: nada foi alterado; BE/TH segue fora do trilho factual; nenhum chunk foi promovido a "esboço compreendido" neste documento.
