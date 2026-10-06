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

## Validação no device — CONCLUÍDA (ADB wireless)
- **T2 (Anexar do acervo) — validado por completo:**
  - `+` mostra as 4 opções (com "Baixar publicações" renomeado) ✅
  - Sheet abre com lista + busca funcional (filtro "lmd" → só `lmd_T.jwpub`) ✅
  - Status visual: `it_T`/`rsg_T` = Falhou + "Tentar de novo"; `lmd_T`/`th_T` =
    Indexado + Vincular ✅
  - **Vincular**: mensagem "Vinculado ✓. Já posso citar." + DB com `noteId`
    ✅; reabrir mostra "Indexado • Nesta nota" + **Desvincular** ✅
  - **Desvincular**: mensagem "Desvinculado." + DB `noteId` vazio ✅
  - **Relink**: `S-34_T_194.rtf` (vinculada a outra nota) → diálogo "Mover
    para esta nota?"; **Cancelar preservou o vínculo antigo** ✅
- **T1 (S-38-T) — validado:** categoria **"Instruções"** após Apostilas;
  "Instruções para a Reunião Nossa Vida e Ministério Cristão (s38)" com badge
  **"No acervo"** (reconheceu `S-38_T.jwpub`) ✅; **Baixar abriu a página
  específica** ("Instruções para a reunião Nossa Vida e Ministério Cristão")
  ✅.
- **Links específicos:** o `rr` não foi alcançado no scroll da validação, mas
  o **S-38-T provou o mecanismo no device** (página específica) e as 13 URLs
  seguem verificadas por HTTP.
- **T3 (dica contextual) — não reproduzida no device:** a nota atual tem
  fontes vinculadas (`scopeEmpty` falso) e o modelo respondeu à pergunta
  fora-de-escopo sem a frase de insuficiência; o helper puro está coberto por
  testes (3).

## Estado
- S-38-T + Anexar do acervo: **fechados** (código + testes + device).
- Dica contextual: fechada no código/testes; gatilho físico não reproduzido.
- `BASE_PUBS` inalterado; CI verde.
