# Catálogo Curado de Publicações (2026-10-05)

## Commits
- **T1 — dx/nwt no PubCatalog:** `0c0d0a0` — Build ✅ + Deploy ✅
- **T2 — Publicações recomendadas:** `dc2fb09` — Build ✅ + Deploy ✅
- **T3 — atalho no +:** `e93af5f` — Build ✅ + Deploy ✅

## Testes JVM
- **1341 passed / 0 failed** (T1: +2; T2: +3).

## T1 — Catálogo de metadados
- `dx` — "Guia de Pesquisa para Testemunhas de Jeová" (kind book). A rota WOL
  de publicação **não existe** (`/lp-t/dx` → 404); link oficial usado é o
  **finder** (`jw.org/finder?…&wfile=dx`, verificado 200).
- `nwt` — "Tradução do Novo Mundo" (kind `bible`); `nwtsty` — "Bíblia de
  Estudo" (kind `bible`). Páginas WOL verificadas (200); título conferido
  ("Bíblia de Estudo" é `nwtsty`).
- **Divergência registrada:** o handoff chamava `nwt` de "Bíblia de Estudo";
  na WOL a edição de estudo é `nwtsty` — ambos entraram no catálogo.

## T2 — Publicações recomendadas (LibraryScreen)
- `RecommendedPublications` (data/catalog): **13 entradas** conforme a tabela
  de decisão (nwtsty; th, be, lmd; dx, it-1/2/3; w, g, mwb; rr, ia),
  agrupadas em Bíblia/Apostilas/Pesquisa/Revistas/Livros; títulos e links
  derivam do `PubCatalog` (fonte única).
- Seção dentro do LazyColumn da Biblioteca: cabeçalho, grupos, cards com
  **badge** "No acervo"/"Faltando", **Baixar** (WebView BYOD), **Formatos**
  (be/th → navegador) e **Vincular** (quando há nota e arquivo no acervo).
- `findInLibrary`: casa símbolo canônico e edições (`w19.03`, `g 6/07`,
  `mwb24.05`, `it-1`); puro/testável.
- **Device:** seção visível e agrupada; `be` = "No acervo" + Vincular;
  `nwtsty`/`th`/`lmd` = "Faltando"; **Baixar abriu a WebView BYOD** na página
  oficial do `lmd` (topbar Voltar/Avançar/Recarregar/Fechar). ✅

## T3 — Atalho no menu +
- `AttachSheet` ganha "Publicações recomendadas" (ícone MenuBook, BYOD) →
  abre a Biblioteca (a seção fica no topo — sem deep link novo).
- **Divergência:** o sheet vive em `ChatPrompt.kt` (mesmo componente; o
  handoff listava ChatScreen.kt). Sem mudanças de navegação/MainActivity.
- **Device:** opção visível no sheet; navegação abre a Biblioteca. ✅

## Legal (BYOD preservado)
- Só metadados + links oficiais (verificados por HTTP); download pelo usuário
  na WebView/página; nada de scraping nem download em lote.

## Fora de escopo / débitos
- Catálogo remoto via Pages (P2).
- 1 toque via API oficial para revistas: no catálogo não há edição definida —
  segue no fluxo de refs (onde há `apiIssue`).
- `mwb`/`rr`/`ia` entraram na curadoria (tabela de decisão), elevando de 10
  para 13 entradas.

## Estado
- Catálogo curado: **fechado** (código + testes + device).
- CI: verde nos 3 commits (Build + Deploy).
