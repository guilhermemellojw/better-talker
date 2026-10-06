# Publicações Grandes + Catálogo Unificado (2026-10-06)

## Commits
- **T1 — limites + streaming:** `9a9fd49` — Build ✅ + Deploy ✅
- **T2 — catálogo (it/rsg/nwt):** `34dd2a4` — Build ✅ + Deploy ✅
- **T3 — escopo A–I/J–Z:** `a9d65e9` — Build ✅ + Deploy ✅
- **T1b — teto de frases:** `6e10a93` — Build ✅ + Deploy ✅

## Testes JVM
- **1368 passed / 0 failed** (+11 nesta rodada).

## Diagnóstico (por que os JWPUBs falhavam)
| Arquivo | Falha | Causa |
|---|---|---|
| `rsg_T.jwpub` (113MB) | "Banco interno muito grande." | banco interno > **120MB** (`MAX_DB_BYTES`) |
| `it_T.jwpub` (354MB) | "Arquivo muito grande (limite 150 MB)." | registro em **150MB** (`MAX_IMPORT_BYTES`) |

Além disso, o it seria truncado em **duas camadas escondidas**: `MAX_TEXT`
(400k chars) e `MAX_SENTENCES` (2500 frases no worker).

## T1 — Limites + streaming
- `ImportLimits`: **esboço 10MB** · **publicação 400MB** (o it é 354MB).
- `JwpubExtractor`: `MAX_DB_BYTES` 120MB → **400MB**; `MAX_TEXT` → **8M**.
- **Streaming do `contents`**: o zip interno descomprimido vai para arquivo no
  cache (antes ia inteiro para RAM — OOM com o it); `unzipDb` lê do arquivo.
- Mensagens por caminho ("limite 10 MB" / "limite 400 MB").

## T1b — Teto de frases proporcional
- `passageCapFor(textLength)` ≈ 1 frase/40 chars, **piso 2500** e **teto
  120k** (antes: toda publicação capada em 2500 frases).

## T2 — Catálogo
- **`it` unificado** (Estudo Perspicaz) + **`rsg`** (Guia de Pesquisa atual) +
  **`nwt`** (Bíblia, card).
- **Removidos:** `nwtsty` (não baixável em nenhum formato — só online/JW
  Library) e **`it-3`** (não existe; a WOL publica 2 volumes).
- **`dx` → alias de `rsg`** (edição antiga).
- `PubCatalog.unifiedOf("it-1"/"it-2") = "it"`; `matchEdition` resolve
  canônico + unificado (it-1/it-2 → `it_T.jwpub`; dx → `rsg_T.jwpub`).
- `STUDY_BOOK_RE` aceita `rsg` e `it-[12]`.
- Curadoria final: Bíblia `nwt` · Apostilas `th/be/lmd` · Instruções `s38` ·
  Pesquisa `it`+`rsg` · Revistas `w/g/mwb` · Livros `rr/ia` (12 cards).

## T3 — Escopo por volume (unificado)
- Refs `it-1` → artigos **A–I**; `it-2` → **J–Z** (1ª letra da seção,
  acentos normalizados); **fallback** para o filtro de página quando o range
  vem vazio.

## Validação no device — BLOQUEADA
- O **ADB (wireless) caiu** antes de instalar o APK da rodada
  (`adb devices` vazio mesmo após reiniciar o servidor) — **parado e
  reportado**.
- Pendente: registrar `rsg_T.jwpub` (113MB) e `it_T.jwpub` (354MB) → medir
  extração/indexação; validar refs `(it-1 …)`, `(it-2 …)`, `(rsg …)`.

## Estado
- Código/testes: **fechados** (4 commits, CI verde).
- Device: pendente de reconexão do ADB.
