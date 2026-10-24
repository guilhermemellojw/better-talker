# Discovery — caminho de importação de material na Home (read-only)

**Data:** 2026-10-23 · **Build de referência:** v2.7.4 (`cbe8e13`) + Fix 1/2 do parser (`21c109d`, `d27767b`) na `feat/copilot-citations`
**Método:** leitura de código (`grep`/`sed`), sem build, sem install, sem tag, sem commit.
**Objetivo:** mapear todos os caminhos de entrada de material na Home e decidir onde o Formato A (1 título com tempo + N pontos com refs → 1 seção com N pontos) deve entrar.

---

## 0. Correções importantes ao contexto do pedido

| Premissa do prompt | O que o código mostra |
|---|---|
| Existe um FAB "Importar esboço" | **Não existe esse label.** O FAB é um `+` (`content-desc "Nova nota"`, `HomeScreen.kt:409-412`) que abre `CreateNoteSheet`; a opção se chama **"Inserir material"** (`HomeScreen.kt:584`), e dentro dela há **"Colar material"** (`:619`) e **"Escolher arquivo"** (`:625`). |
| O fix atual está em `createNoteFromPasted` | **Não existe `createNoteFromPasted` no código.** O método do chat/editor é **`CopilotViewModel.pasteOutline`** (`CopilotViewModel.kt:4558`). O nome `createNoteFromPaste` é o método **da Home** (`HomeViewModel.kt:122`). |
| A Home mostra "1 card Corpo" e a nota fica com 0 palavras | A Home cria **1 BODY "Corpo" com o texto inteiro** (`PasteNoteBuilder.kt:641-657`), não vazio. O 0 palavras era o caminho do **chat** (3 seções, bodies vazios), que já foi corrigido. Na Home o problema é **título/minutos genéricos** ("Corpo", 5 min), não ausência de body. |
| `S34Detector` é dead code | **Não é dead code global**: é chamado em `CopilotViewModel.kt:4527` (import de arquivo) e por `S34Parser` (`S34Parser.kt:170-171, 231, 239, 257`); o `S34ImportHook` também o usa (`S34ImportHook.kt:50`). Ele **não participa do caminho de colagem** (`planPaste`), que é outra coisa. Não foi tocado. |
| "DOCX/JWPUB/PDF" são formatos que o FAB já cobre | A **extração de texto** existe para os três (`DocExtractors.kt:20-39`), mas o import de **esboço** exige `sections.size >= 2` (`OutlineRepository.kt:80-86`). Um arquivo no Formato A (1 título + N pontos) é **rejeitado** com "Não reconheci seções no formato (N min)". |

---

## 1. Resumo (≤10 linhas)

1. A Home tem **um único FAB** (`HomeScreen.kt:409-412`) → `CreateNoteSheet` (`:569`) com 3 intenções: **Inserir material** (`:584`), **Começar do zero** (`:590`) e **Nota rápida** (`:599`).
2. **Inserir material** (`InsertMaterialDialog`, `:607`) abre **dois caminhos distintos**: **Colar material** (`:619`) e **Escolher arquivo** (`:625`).
3. **Colar** → `PastePreviewSheet` (`HomeScreen.kt:251-270`) → `HomeViewModel.createNoteFromPaste` (`HomeViewModel.kt:122`) → `planPaste` (`:123`) → `PasteNoteBuilder.kt:466`.
4. **Arquivo** → SAF `OpenDocument` (`HomeScreen.kt:141-143`; `:246` `arrayOf("*/*")`) → `MainActivity.onImportMaterial` (`:214-219`) → `pendingImport` → `CopilotViewModel.importOutlineFile(uri, autoLink = true)` (`MainActivity.kt:283-290`).
5. O caminho de arquivo usa `OutlineRepository.previewFile` (`OutlineRepository.kt:28`), **não** `planPaste`; ele extrai o texto (`DocExtractors`) e chama `OutlineParser.parse` (`:80`), mas rejeita menos de 2 seções (`:81-86`).
6. A colagem da Home hoje cai em `PasteKind.FreeText`: **1 BODY "Corpo"**, `GENERIC_SECTION_MINUTES = 5` (`PasteNoteBuilder.kt:252`, `:641-657`), com o texto completo no body; o preview mostra **"1 card (Corpo)"** (`:154`) e **"Sem V&M/S-34 detectado"** (`:139`).
7. O fix atual (Formato A) está **só no chat/editor**, em `pasteOutline` (`CopilotViewModel.kt:4576-4588`), **depois** de `planPaste`; a Home não passa por ele.
8. `planPaste` é o **cérebro compartilhado** de Home, Editor e Chat (`HomeViewModel.kt:123`; `CopilotViewModel.kt:4561`; comentário em `PasteNoteBuilder.kt:11-14`) — é o menor ponto para o Formato A de **colagem**.
9. Para **arquivo**, o funil é outro: `previewFile` (parse + gate `<2`) e, no chat/editor, `linkDraft` também exige `sections.size >= 2` (`CopilotViewModel.kt:4717-4723`). Não basta baixar o gate.
10. Recomendação: **mover** a detecção do Formato A para `planPaste` (não duplicar na Home); **manter** o chat/editor (é o mesmo pipeline de "nota atual", não só o `+`); tratar DOCX/PDF/JWPUB em **backlog separado**, com o gate de 1 seção resolvido de propósito.

---

## 2. As 10 perguntas

| # | Resposta curta | Evidência principal | Classificação |
|---|---|---|---|
| 1 | Um FAB → "Criar nota" → "Inserir material" → **dois** caminhos: colar e arquivo. "Importar esboço" não existe como label. | `HomeScreen.kt:409-412`, `:569-601`, `:607-633`, `:251-270` | [CÓDIGO] / [NÃO ENCONTRADO] (label "Importar esboço") |
| 2 | Arquivo aceita PDF, EPUB, DOCX, RTF, ZIP, TXT, JWPUB; UI anuncia só "DOCX, PDF ou texto". O parser de esboço rejeita <2 seções. | `HomeScreen.kt:141-143`, `:246`; `TextNorm.kt:207-222`; `DocExtractors.kt:20-39`; `OutlineRepository.kt:37-42, 80-86` | [CÓDIGO] |
| 3 | Colar na Home = 1 BODY "Corpo", 5 min, body com todo o texto; título da nota = 1ª linha (com "(10 min)"); **não** há `_draftTotal` na Home. | `HomeViewModel.kt:122-140`; `PasteNoteBuilder.kt:466-468, 641-657, 252, 327-329, 139, 154` | [CÓDIGO] |
| 4 | Além do FAB: editor (anexar/colar na nota atual), chat (`+` importar/colar), Biblioteca (acervo) e um share intent **declarado mas sem handler**. | `EditorScreen.kt:668-674, 717-730`; `MainActivity.kt:331-332`; `ChatScreen.kt:326-328, 878-887, 906-919`; `LibraryScreen.kt:78-80`; `AndroidManifest.xml:30-34` | [CÓDIGO] / [NÃO ENCONTRADO] (handler do share) |
| 5 | `pasteOutline` tem **2 consumidores**: chat (botão `+` → Colar) e editor (estado vazio/anexar). Não é só o chat. | `ChatScreen.kt:919`; `MainActivity.kt:332`; `EditorScreen.kt:729-730` | [CÓDIGO] |
| 6 | Home e chat compartilham `planPaste`, `PasteTextNormalizer`, `PastedNote`/`PastedSection`, `RefDetector`; **não** compartilham persistência (Home: `createPastedNote`/`OutlineImportService`; chat: `_draft` + `linkDraft`). | `HomeViewModel.kt:123, 128-139`; `CopilotViewModel.kt:4560-4561, 4717`; `PasteNoteBuilder.kt:466-468` | [CÓDIGO] |
| 7 | DOCX existe para **texto** (OOXML bruto, parágrafos/tabelas → `\n`), sem estilos/headings; não há importador dedicado. Formato A DOCX é barrado pelo gate `<2`. | `DocExtractors.kt:84-124`; `OutlineRepository.kt:80-86` | [CÓDIGO] |
| 8 | JWPUB é extraído (`JwpubExtractor`), mas o caminho próprio é o **acervo/Biblioteca** (picker + `LibraryRepository.importUri` + worker/S34 hook). No FAB ele é selecionável (`*/*`), mas não vira esboço de nota. | `DocExtractors.kt:27, 38`; `LibraryScreen.kt:78-80`; `LibraryViewModel.kt:56-59`; `LibraryRepository.kt:94`; `IndexPublicationWorker.kt:56` | [CÓDIGO] |
| 9 | PDF existe: PDFBox, 1–60 páginas, texto puro, sem OCR/estrutura de página. Há dois caminhos: acervo (indexado) e esboço (FAB). | `DocExtractors.kt:42-50`; `IndexPublicationWorker.kt:39-47`; `OutlineRepository.kt:70-71` | [CÓDIGO] |
| 10 | O Formato A de **colagem** deve ir para `planPaste` (Home + chat/editor); o de **arquivo** precisa de decisão própria em `previewFile`/persistência, porque `previewFile` e `linkDraft` exigem ≥2 seções. | `PasteNoteBuilder.kt:625-626`; `HomeViewModel.kt:123`; `CopilotViewModel.kt:4561, 4721`; `OutlineRepository.kt:81-86` | [CÓDIGO] |

### 2.1 Detalhe por pergunta

#### 1. Caminhos de entrada na Home

- **FAB:** `FloatingActionButton(onClick = { showCreateSheet = true })` em `HomeScreen.kt:409-412`; ícone `Add` com `content-desc "Nova nota"`.
- **`CreateNoteSheet`** (`HomeScreen.kt:569-601`):
  - **"Inserir material"** (`:584`) → `showInsertDialog = true` (`:223-226`).
  - **"Começar do zero"** (`:590`) → `StartFromScratchDialog` (`:636-663`): "Discurso" → `createSpeechNote` (`HomeViewModel.kt:143-146`); "Demonstração" → `createDemoNote` (`:148-160`).
  - **"Nota rápida"** (`:599`) → `vm.newNote()` (`:231-234`).
- **`InsertMaterialDialog`** (`HomeScreen.kt:607-633`):
  - **"Colar material"** (`:619`) → `showPasteSheet = true` (`:240-243`).
  - **"Escolher arquivo"** (`:625`) → `materialPicker.launch(arrayOf("*/*"))` (`:245-247`).
- **"Importar esboço"**: `[NÃO ENCONTRADO]` como label. O que existe é "Inserir material → Escolher arquivo". A doc interna chama esse fluxo de "Inserir material → Escolher arquivo" (`HomeScreen.kt:105`, `:140`).
- **São dois caminhos distintos** dentro do mesmo FAB: colar (`planPaste`/HomeViewModel) e arquivo (`pendingImport`/CopilotViewModel). Não são o mesmo handler.

#### 2. O que o "Escolher arquivo" suporta

- Picker: `ActivityResultContracts.OpenDocument()` (`HomeScreen.kt:141-143`) com MIME `*/*` (`:246`). Não há filtro por extensão na Home.
- `detectKind` reconhece por extensão e faz sniffing de header: PDF, EPUB, DOCX, RTF, ZIP, TXT/MD, JWPUB (`TextNorm.kt:207-222`).
- `DocExtractors.supports` aceita PDF, EPUB, DOCX, RTF, ZIP, TXT, JWPUB (`DocExtractors.kt:36-39`); `UNSUPPORTED` é rejeitado com "Formato não suportado. Use DOCX, PDF, RTF ou JWPUB." (`OutlineRepository.kt:37-42`).
- Extração: `DocExtractors.extract` (`:20-28`) → `readPdf` (`:42-50`), `readDocx` (`:84-124`), `readEpub` (`:54`), `readZipRtfs`, `readTxt`, `JwpubExtractor`.
- Depois da extração: `OutlineParser.parse(text, name)` (`OutlineRepository.kt:80`) e **gate `< 2` seções** (`:81-86`). É aqui que um esboço de 1 título + N pontos é rejeitado.
- **Gaps explícitos:** `.doc` legado, `.odt`, imagens/OCR, e **Formato A com 1 seção**. A UI também subdeclara os formatos (diz "DOCX, PDF ou texto", mas o código aceita EPUB/RTF/ZIP/TXT/JWPUB).

#### 3. O que "Colar material" da Home faz hoje

- `PastePreviewSheet` com `defaultDestination = NEW_NOTE` (`HomeScreen.kt:251-255`), `onApply` → `vm.createNoteFromPaste(text)` (`:257-260`).
- `HomeViewModel.createNoteFromPaste` (`:122-140`):
  - chama `planPaste(text)` (`:123`);
  - `VmRejected` → não cria nota (`:125-127`);
  - `S34` → nota tipada + `OutlineConverter` + `OutlineImportService.persist` (`:128-137`);
  - qualquer outro → `repo.createPastedNote(folderId, plan.toPastedNote())` (`:139`).
- Para o texto da apostila, `planPaste`:
  - `OutlineParser.parse` encontra **1 seção** (a linha 1 com `(10 min)`); as linhas 2–4 caem no body dessa seção;
  - `parsed.sections.size >= 2` é **falso** (`PasteNoteBuilder.kt:625-626`), então não vira `PasteKind.S34`;
  - cai em `PasteKind.FreeText` (`:641-657`): 1 `PastedSection(BODY, title = "Corpo", minutes = 5, contentHtml = paragraphsToHtml(trimmed))`.
- Resultado real: **1 seção com body preenchido** (todo o texto, ~90 palavras), mas:
  - título da seção = **"Corpo"** (não o tema);
  - minutos = **5** (`GENERIC_SECTION_MINUTES`, `:252`), não 10;
  - título da nota = `pastedTitleOf(trimmed)` (`:327-329`) = primeira linha, incluindo **"(10 min)"**;
  - preview = **"Texto livre" / "Sem V&M/S-34 detectado" / "1 card (Corpo)"** (`:119, 139, 154`).
- **`_draftTotal`**: não existe no fluxo da Home. O equivalente é `PastedSection.minutes` (hoje 5). No chat, `_draftTotal` é estado de draft (`CopilotViewModel.kt:4586`).
- **Nuance do parser:** no `OutlineParser.parse`, a linha 1 com `(10 min)` vira a **seção**; a primeira linha sem `MIN_RE` (linha 2, um ponto) vira o `parsed.title` (`OutlineParser.kt:160-168`). Por isso o Formato A não pode usar `parsed.title` como tema; o título correto vem de `inlineApostila().title`.
- O preview não mostra "1 seção com 3 pontos"; mostra "1 card (Corpo)" porque `pastePreviewOf` só olha o `PasteKind` (`PasteNoteBuilder.kt:148-155`).

#### 4. Outros caminhos (não FAB) que criam/aplicam material

- **Editor — "Anexar material"** (`EditorScreen.kt:661-712`):
  - "Importar arquivo" (`:681`) → `outlinePicker.launch(arrayOf("*/*"))` (`:674`) → `onImportOutline` (`MainActivity.kt:331`) → `copilotVm.importOutlineFile(uri)` **sem autoLink**.
  - "Colar material" (`:702`) → `PastePreviewSheet` (`:717-730`) → `onPasteOutline` (`MainActivity.kt:332`) → `copilotVm.pasteOutline(text)` + `onOpenChat()` (`:729-730`).
- **Chat — `+`** (`ChatScreen.kt:878-887`):
  - "Importar arquivo" → `outlinePicker.launch(OUTLINE_MIMES)` (`:880`) → `vm.importOutlineFile(uri)` (`:328`);
  - "Colar material" → `PastePreviewSheet` (`:906-919`) → `vm.pasteOutline(text)` (`:919`).
- **Biblioteca (acervo)** — `LibraryScreen.kt:78-80` `OpenDocument()` → `LibraryViewModel.importUri` (`:56-59`) → `LibraryRepository.importUri` (`:94`). É importação de **publicação indexada**, não de esboço de nota. O worker chama `S34ImportHook.onExtracted` (`IndexPublicationWorker.kt:56`).
- **Share intent (Android share sheet)** — **declarado e não implementado**: `AndroidManifest.xml:30-34` tem `ACTION_SEND` + `text/plain`, mas não há `getIntent`, `onNewIntent`, `ACTION_SEND` ou `EXTRA_TEXT` em nenhum `.kt` (busca em todo `app/src/main/java`). Abrir o app por "Compartilhar texto" não cria nota. `[NÃO ENCONTRADO]`.

#### 5. Quem chama `pasteOutline` (o "createNoteFromPasted" do chat)

Busca em todo o código:

- `ChatScreen.kt:919` — `vm.pasteOutline(text)` no `PastePreviewSheet` do chat (`+ → Colar material`).
- `MainActivity.kt:332` — `onPasteOutline = { text -> copilotVm.pasteOutline(text) }`, callback do **EditorScreen** (`EditorScreen.kt:729-730` chama `onPasteOutline` e abre o chat).

Portanto **o chat não é o único consumidor**: o editor (estado vazio/anexar) usa o mesmo pipeline. O `PastePreviewSheet` aparece nos dois (`ChatScreen.kt:906`, `EditorScreen.kt:717`), e ambos mandam S34/FreeText para revisão no chat.

`importOutlineFile` tem 3 call sites: Home (`MainActivity.kt:290`, `autoLink = true`), editor (`:331`, default `false`) e chat (`ChatScreen.kt:328`, default `false`).

#### 6. O que Home e chat compartilham

| Compartilhado | Evidência |
|---|---|
| `planPaste` (decisão S-34/V&M/FreeText) | `HomeViewModel.kt:123`; `CopilotViewModel.kt:4561` |
| `PasteTextNormalizer.normalize` | `planPaste` já normaliza (`PasteNoteBuilder.kt:466-468`); o chat normaliza de novo (`CopilotViewModel.kt:4560`) |
| `PastedNote`/`PastedSection`/`PastePlan` | `PasteNoteBuilder.kt:38-94`; Home usa `toPastedNote` (`:139`), chat usa `_draft` |
| `RefDetector` / `OutlineParser` | `planPaste:627`, `previewFile:87-89` |
| **Não compartilhado:** persistência | Home: `repo.createPastedNote` (`HomeViewModel.kt:139`) e `OutlineImportService.persist` (`:133`); chat: `_draft` + `linkDraft` (`CopilotViewModel.kt:4717`) |
| **Não compartilhado:** UI de revisão | Home cria a nota direto; chat/editor abrem `DraftSheet`/chat |

#### 7. DOCX

- Extração existe: `DocExtractors.readDocx` (`DocExtractors.kt:84-124`) lê `word/document.xml`, `footnotes.xml`, `endnotes.xml` do ZIP; parágrafos/tabelas viram `\n`; `<w:br>`/`<w:tab>` viram espaço (`:96-112`); colapsa 3+ quebras (`:121`).
- **Não** lê estilos/headings (`Heading 1`, `Title`), não preserva numeração nem estrutura de página. Não há `XWPF`, `POI`, `docx4j` ou `mammoth`; é parsing OOXML manual.
- O texto extraído vai para `OutlineParser.parse`; o único critério de estrutura é `(N min)` (`OutlineParser.kt:31, 108-146`).
- **Gap:** um DOCX no Formato A (1 título + N pontos) produz `sections.size == 1` e é rejeitado por `OutlineRepository.kt:81-86`. `.doc` legado não é suportado (`TextNorm.kt:207-222`).

#### 8. JWPUB

- Extração existe: `DocKind.JWPUB` (`DocExtractors.kt:27`) → `JwpubExtractor.extract` (`JwpubExtractor.kt:44`) → `extractTo` (`:50`), com leitura do `contents` em arquivo (não em RAM).
- O caminho **próprio** é a Biblioteca/acervo: `LibraryScreen.kt:78-80` (`OpenDocument`) → `LibraryViewModel.importUri` (`:56-59`) → `LibraryRepository.importUri` (`:94`); o download in-app (`JwDownloadDialog`) e o `IndexPublicationWorker` indexam e chamam `S34ImportHook` (`:56`).
- O FAB da Home **tecnicamente** aceita `.jwpub` porque o picker é `*/*` e `detectKind` reconhece a extensão (`HomeScreen.kt:246`; `TextNorm.kt:218`), mas o texto de uma publicação não é um esboço com `(N min)`; cai no gate `<2` (`OutlineRepository.kt:81-86`). Portanto **o FAB não cobre JWPUB como esboço de nota**; mantenha separado.
- `S34Parser` é o parser do S-34 **oficial** e é chamado pelo `S34ImportHook` (`S34ImportHook.kt:51`), não pelo `planPaste`.

#### 9. PDF

- Extração existe: `DocExtractors.readPdf` (`DocExtractors.kt:42-50`), PDFBox (`PDFTextStripper`), páginas 1–60 (`MAX_PDF_PAGES`), texto puro com teto de 400k (`MAX_TEXT`).
- **Não** há OCR, `PdfRenderer`, `itext`, `tesseract`; não preserva página/parágrafo como metadado — o `OutlineParser` vê texto com quebras.
- Há **dois caminhos**:
  - **acervo/indexação:** `LibraryRepository.importUri` (`:94`) + `IndexPublicationWorker` (`:39-47`, fallback `readPdf`);
  - **esboço de nota:** FAB/editor/chat → `OutlineRepository.previewFile` (`:70-71`) → `OutlineParser.parse` + gate `<2`.
- **Gap:** PDF escaneado (sem camada de texto) extrai vazio (`readPdf` retorna `""` no catch, `:51`; `previewFile` lança "Não extraí texto", `:77-79`). PDF no Formato A é barrado pelo gate `<2`.

#### 10. Onde o Formato A deve entrar

- **Colagem (P0):** em `PasteNoteBuilder.planPaste`, **antes** do gate `parsed.sections.size >= 2` (`:625-626`), usando o helper puro já existente `PastedOutlineAnalyzer.inlineApostila` (`OutlineParser.kt`). A Home (`HomeViewModel.kt:123`) e o chat/editor (`CopilotViewModel.kt:4561`) já chamam `planPaste`; mover para lá corrige os três de uma vez, sem duplicar.
- **Arquivo (P1, decisão separada):** `OutlineRepository.previewFile` (`:80-86`) e `linkDraft` (`CopilotViewModel.kt:4721`) assumem **≥2 seções**. Um DOCX/PDF/TXT no Formato A não passa. Não basta baixar o gate: é preciso definir a persistência de **1 BODY** (a Home já sabe fazer isso via `createPastedNote`; o chat/editor não). Recomendo um fallback explícito em `previewFile` + uma rota de 1 seção, ou sintetizar Intro/Conclusão como o `OutlineConverter` faz para S-34 — decisão de produto, não de parser.
- **Chat/editor:** **manter** o `pasteOutline`; ele é o pipeline de "nota atual" e é chamado pelo editor (`EditorScreen.kt:729-730`, `MainActivity.kt:332`), não só pelo `+` do chat. Depois de mover a detecção para `planPaste`, ele fica consistente sem duplicação.

---

## 3. Diagrama ASCII — Home × Chat/Editor

```
[Home]
  └── FAB "+"  (HomeScreen.kt:409-412)
        └── CreateNoteSheet (HomeScreen.kt:569)
              ├── "Inserir material" (HomeScreen.kt:584)
              │     └── InsertMaterialDialog (HomeScreen.kt:607)
              │           ├── "Colar material" (HomeScreen.kt:619)
              │           │     └── PastePreviewSheet (HomeScreen.kt:251-270)
              │           │           └── HomeViewModel.createNoteFromPaste (HomeViewModel.kt:122)
              │           │                 └── planPaste (PasteNoteBuilder.kt:466)
              │           │                       ├── S34 (>= 2 seções) → OutlineConverter/persist
              │           │                       └── FreeText → 1 BODY "Corpo", 5 min
              │           │                             └── repo.createPastedNote (HomeViewModel.kt:139)
              │           │
              │           └── "Escolher arquivo" (HomeScreen.kt:625)
              │                 └── SAF OpenDocument, MIME "*/*" (HomeScreen.kt:141-143, :246)
              │                       └── MainActivity.onImportMaterial (MainActivity.kt:214-219)
              │                             └── pendingImport → importOutlineFile(autoLink = true) (MainActivity.kt:283-290)
              │                                   └── OutlineRepository.previewFile (OutlineRepository.kt:28)
              │                                         ├── DocExtractors.extract (PDF/EPUB/DOCX/RTF/ZIP/TXT/JWPUB)
              │                                         ├── OutlineParser.parse (OutlineRepository.kt:80)
              │                                         └── gate: sections.size >= 2 (OutlineRepository.kt:81-86)
              │                                               └── [Formato A: REJEITADO]
              │
              ├── "Começar do zero" (HomeScreen.kt:590) → Discurso / Demonstração (HomeViewModel.kt:143-160)
              └── "Nota rápida" (HomeScreen.kt:599) → vm.newNote()

[Chat]  (ChatScreen.kt)
  └── "+" → AttachSheet (ChatScreen.kt:878-887)
        ├── "Importar arquivo" → OUTLINE_MIMES (ChatScreen.kt:880) → importOutlineFile (ChatScreen.kt:328)
        └── "Colar material" (ChatScreen.kt:882) → PastePreviewSheet (ChatScreen.kt:906-919)
              └── vm.pasteOutline (CopilotViewModel.kt:4558)
                    ├── planPaste (CopilotViewModel.kt:4561)  ← MESMO cérebro da Home
                    └── PastedOutlineAnalyzer.inlineApostila (CopilotViewModel.kt:4576-4588)  ← FIX 1+2 AQUI
                          └── _draft = 1 seção com body + _draftTotal = 10

[Editor]  (EditorScreen.kt)
  └── "Anexar material" (EditorScreen.kt:661)
        ├── "Importar arquivo" (EditorScreen.kt:681) → importOutlineFile (MainActivity.kt:331)  [sem autoLink]
        └── "Colar material" (EditorScreen.kt:702) → PastePreviewSheet (EditorScreen.kt:717-730)
              └── onPasteOutline (MainActivity.kt:332) → pasteOutline + onOpenChat (EditorScreen.kt:729-730)

DIVERGÊNCIA PRINCIPAL
  Home (o caminho que o usuário usa para criar nota):
    planPaste → FreeText → 1 BODY "Corpo", 5 min  ← NÃO passa pelo Fix 1+2
  Chat/Editor:
    planPaste → inlineApostila → 1 seção com título do tema, 10 min, N pontos  ← Fix 1+2
  Arquivo (Home/Editor/Chat):
    previewFile → OutlineParser.parse → gate < 2 → Formato A rejeitado
```

---

## 4. Decisão (Fase 3)

| Decisão | Opções | Recomendação | Justificativa (com `arquivo:linha`) |
|---|---|---|---|
| Onde o fix do Formato A mora | (A) mover para a Home; (B) duplicar na Home com TODO; (C) redirecionar chat → Home | **(A) mover para o módulo compartilhado `planPaste`** — não para `HomeViewModel` | Home e chat/editor já chamam `planPaste` (`HomeViewModel.kt:123`; `CopilotViewModel.kt:4561`), e o comentário do arquivo diz que ele é o cérebro único (`PasteNoteBuilder.kt:11-14`). Duplicar criaria dois parsers; redirecionar o chat para a Home quebraria o editor (`MainActivity.kt:332`, `EditorScreen.kt:729`). |
| Chat "+ → Colar material" | (A) remover; (B) redirecionar; (C) manter | **(C) manter** (e, se quiser, renomear para "Colar na nota atual") | É o mesmo `pasteOutline` chamado pelo editor (`MainActivity.kt:332`; `EditorScreen.kt:729-730`) e aplica V&M na nota atual via `applyVmPastePlan` (`CopilotViewModel.kt:4562-4567`). Remover quebraria um caminho legítimo; depois do fix em `planPaste`, ele fica consistente. |
| DOCX | (A) implementar agora; (B) backlog; (C) não suportar | **(B) backlog** para estrutura completa; **P0 só** para o gate de 1 seção | A extração de texto já existe (`DocExtractors.kt:84-124`); falta estilo/heading e o gate `<2` (`OutlineRepository.kt:81-86`). Não é preciso um novo importador DOCX para o Formato A. |
| JWPUB no FAB | (A) incluir no FAB; (B) manter separado | **(B) manter separado** (Biblioteca/acervo) | O caminho próprio já existe (`LibraryScreen.kt:78-80` → `LibraryViewModel.kt:56-59` → `LibraryRepository.kt:94`) e o worker indexa/S34 (`IndexPublicationWorker.kt:56`). No FAB, JWPUB cai no gate `<2` (`OutlineRepository.kt:81-86`); incluir exigiria tratar publicação como esboço, o que não é o caso. |
| PDF colado | (A) implementar agora; (B) backlog | **(B) backlog** (a extração já existe; falta OCR/estrutura) | `readPdf` já extrai texto (`DocExtractors.kt:42-50`), mas sem OCR/página; PDF escaneado retorna vazio (`:51`) e `previewFile` falha (`:77-79`). PDF no Formato A também é barrado por `<2`. |

### 4.1 Respostas diretas às 6 perguntas de retorno

1. **O FAB cobre quais formatos?** O picker aceita `*/*` (`HomeScreen.kt:246`) e o extrator suporta PDF, EPUB, DOCX, RTF, ZIP, TXT e JWPUB (`DocExtractors.kt:36-39`), mas o **import de esboço** exige ≥2 seções (`OutlineRepository.kt:81-86`). Na prática: esboços com múltiplas seções temporizadas; **não** cobre o Formato A.
2. **O caminho do FAB é o mesmo de "Colar material"?** Não. O FAB tem dois caminhos distintos: colar → `HomeViewModel.createNoteFromPaste`/`planPaste`; arquivo → `MainActivity.onImportMaterial`/`pendingImport`/`importOutlineFile`/`previewFile`.
3. **O fix atual deve ser movido, duplicado ou redirecionado?** **Movido** para `planPaste` (módulo compartilhado). O chat/editor continua consumindo o mesmo plano; a Home passa a receber a seção correta sem duplicação.
4. **DOCX/PDF/JWPUB no FAB são gaps?** DOCX/PDF são suportados para **texto**; o gap é o Formato A (1 seção) e a estrutura (estilos/OCR). JWPUB é suportado como **acervo**, não como esboço no FAB.
5. **O que o usuário vê ao colar a apostila na Home?** 1 card **"Corpo"**, 5 min, body com as 3 frases + refs; título da nota com "(10 min)"; preview "Texto livre / Sem V&M/S-34 detectado / 1 card (Corpo)". Não é 0 palavras; é estrutura genérica.
6. **Menor ponto de entrada que cobre todos os formatos?** **Não há um único** hoje: `planPaste` cobre a colagem (Home/Editor/Chat); `previewFile` cobre os arquivos (Home/Editor/Chat). Para o Formato A, comece por `planPaste` (P0) e trate `previewFile`/`linkDraft` como P1 separado.

---

## 5. Gaps explícitos

1. **Formato A na colagem da Home:** `planPaste` cai em `FreeText` → "Corpo"/5 min (`PasteNoteBuilder.kt:641-657`). O fix 1+2 está só em `pasteOutline` (`CopilotViewModel.kt:4576-4588`).
2. **Formato A no arquivo:** `previewFile` rejeita 1 seção (`OutlineRepository.kt:81-86`); `linkDraft` também exige ≥2 (`CopilotViewModel.kt:4721`). DOCX/PDF/TXT de 1 parte não entram.
3. **DOCX:** só texto OOXML bruto; sem headings/estilos; `.doc` não suportado (`TextNorm.kt:207-222`).
4. **PDF:** sem OCR/página/parágrafo; 60 páginas e 400k caracteres (`DocExtractors.kt:15-18, 42-50`); escaneado falha.
5. **JWPUB no FAB:** extrai, mas não é esboço; o caminho correto é a Biblioteca (`LibraryScreen.kt:78-80`).
6. **Share intent:** declarado (`AndroidManifest.xml:30-34`), **sem handler**; compartilhar texto não cria nota.
7. **Preview da Home:** "1 card (Corpo)" / "Sem V&M/S-34 detectado" (`PasteNoteBuilder.kt:139, 154`) não distingue o Formato A; o usuário não vê "1 parte, 3 pontos, 10 min" antes de criar.
8. **Nomenclatura:** `createNoteFromPasted` não existe; o método é `pasteOutline` (`CopilotViewModel.kt:4558`). `S34Detector` não é dead code global; só não está no caminho de colagem.

---

## 6. Próximo passo recomendado

Implementar o Formato A **em `planPaste`** (`PasteNoteBuilder.kt`, antes de `:625`), usando `PastedOutlineAnalyzer.inlineApostila` como helper puro: se casar, retornar um `PastePlan` com `noteTitle = inline.title`, **1 `PastedSection(BODY, title = inline.title, minutes = inline.minutes ?: 5, contentHtml = inline.body)`** e as refs do texto. Isso corrige a Home (`HomeViewModel.kt:123` → `createPastedNote`) e mantém o chat/editor consistentes (`CopilotViewModel.kt:4561`), sem duplicar parser. Em seguida, validar no tablet pelo caminho **Home → Inserir material → Colar material** (1 seção, título sem "(10 min)", body com 3 pontos, ~90 palavras, minutos 10) e só depois decidir o P1 de arquivo: `previewFile`/`linkDraft` exigem ≥2 seções (`OutlineRepository.kt:81-86`; `CopilotViewModel.kt:4721`), então o Formato A de DOCX/PDF precisa de uma rota explícita de 1 BODY — não basta baixar o gate.

---

## 7. Evidência de read-only

- Nenhum arquivo de produção foi alterado nesta discovery; nenhum build/install/tag/commit.
- `git status` deve mostrar apenas relatórios não commitados em `docs/reports/` (incluindo este).
- O único artefato novo é este relatório.
