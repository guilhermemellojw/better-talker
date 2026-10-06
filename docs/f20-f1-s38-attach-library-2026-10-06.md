# S-38-T no Catálogo + Anexar do Acervo no Chat (2026-10-06)

## Commits
- **T1 — S-38-T:** `0442fe8` — Build ✅ + Deploy ✅
- **T2 — Anexar do acervo:** `9f3f77c` — Build ✅ + Deploy ✅
- **T3 — dica contextual:** `0b43c53` — Build ✅ + Deploy ✅

## Testes JVM
- **1353 passed / 0 failed** (T1 +3; T2 +3; T3 +3).

## T1 — S-38-T no catálogo
- `s-38` no `PubCatalog` (kind `manual`; WOL `…/S-38` verificado 200) +
  alias `s38 → s-38` (esboços/arquivos usam sem hífen).
- Curadoria: entrada `s38` na **categoria nova "Instruções"** (posicionada
  após Apostilas), com a **página oficial** do jw.org verificada (200, com
  "OPÇÕES DE DOWNLOAD").
- Badge de acervo reconhece `S-38_T_194.docx` (variante compacta `s38t194`).
- Nota: a categoria do S-38 no jw.org é `orientacoes` (não `livros`).

## T2 — Anexar do acervo no chat
- 4ª opção no `AttachSheet` (**Anexar do acervo**, ícone LibraryBooks) →
  `PublicationPickerSheet` novo: busca por nome/símbolo, status
  (Indexado/Indexando/Falhou/Baixando), badge "Nesta nota",
  Vincular/Desvincular/"Tentar de novo" e diálogo de **relink**
  ("Mover para esta nota?").
- VM expõe `publications`/`currentNoteId` + `linkPublication`/
  `retryRegisterPublication` (reusa `LibraryRepository.linkToNote`; null =
  desvincular).
- Ao vincular: mensagem no chat "Vinculado ✓. Já posso citar." e o sheet
  fecha; ao desvincular: "Desvinculado.".
- "Publicações recomendadas" renomeada para **"Baixar publicações"**.
- Divergência registrada: o texto do relink cita "outra nota" (sem o título
  da nota de destino — exigiria query extra no DAO).

## T3 — Dica contextual
- Quando a resposta é a frase de insuficiência **e** o escopo está sem fontes
  **e** há publicações prontas não vinculadas, o chat acrescenta:
  *"Dica: você tem N publicação(ões) no acervo. Toque no + → Anexar do
  acervo."* — helper puro `contextualScopeTip` + fiação no `answerRemote`.

## Validação no device — BLOQUEADA
- **ADB/USB caiu** antes deste ciclo e permanece fora
  (`no devices/emulators found`) — validação física **parada e reportada**.
- Pendente: S-38-T na seção, sheet de acervo (busca/vincular/desvincular/
  relink) e a dica contextual.

## Estado
- S-38-T + Anexar do acervo + dica: **fechados no código/testes**.
- `BASE_PUBS` inalterado; CI verde.
