# Publicações Grandes + Catálogo Unificado (2026-10-06)

## Commits
- **T1 — limites + streaming:** `9a9fd49` — Build ✅ + Deploy ✅
- **T2 — catálogo (it/rsg/nwt):** `34dd2a4` — Build ✅ + Deploy ✅
- **T3 — escopo A–I/J–Z:** `a9d65e9` — Build ✅ + Deploy ✅
- **T1b — teto de frases:** `6e10a93` — Build ✅ + Deploy ✅
- **T1c — calibração medida:** `1d34e6d` — Build ✅ + Deploy ✅
- **T4 — retrieval por termos:** `9c5329c` — Build ✅ + Deploy ✅

## Testes JVM
- **1370 passed / 0 failed** (+13 nesta rodada).

## Diagnóstico (por que os JWPUBs falhavam)
| Arquivo | Falha | Causa |
|---|---|---|
| `rsg_T.jwpub` (113MB) | "Banco interno muito grande." | banco interno > **120MB** (`MAX_DB_BYTES`) |
| `it_T.jwpub` (354MB) | "Arquivo muito grande (limite 150 MB)." | registro em **150MB** (`MAX_IMPORT_BYTES`) |

Camadas ocultas de truncamento descobertas na validação:
`MAX_TEXT` (400k → 8M), `MAX_SENTENCES` (2500 no worker) e, na medição,
**MAX_TEXT=8M ainda cortava o it na letra M**.

## T1 — Limites + streaming
- `ImportLimits`: **esboço 10MB** · **publicação 400MB** (o it é 354MB).
- `JwpubExtractor`: `MAX_DB_BYTES` 120MB → **400MB**; `MAX_TEXT` → 8M.
- **Streaming do `contents`**: o zip interno descomprimido vai para arquivo no
  cache (antes ia inteiro para RAM — OOM com o it); `unzipDb` lê do arquivo.
- Mensagens por caminho ("limite 10 MB" / "limite 400 MB").

## T1b — Teto de frases proporcional
- `passageCapFor(textLength)` ≈ 1 frase/40 chars, **piso 2500** e teto
  (antes: toda publicação capada em 2500 frases).

## T1c — Calibração com o arquivo real (medida no host)
Medição replicando a extração (AES+inflate+stripXml) sobre os JWPUBs reais:
| Publicação | Docs | Texto total | Observação |
|---|---|---|---|
| `it` (unificado) | 5.023 | **12,43M chars** | o teto de 8M cortava no doc 3.226 ("Mina, Mineração") |
| `rsg` | 1.233 | **4,68M chars** | já cabia inteiro |

- `MAX_TEXT` 8M → **16M** (folga p/ edições futuras).
- `MAX_DOCS` 5k → **20k** (o it tem 5.023 docs).
- `MAX_PASSAGE_CAP` 120k → **300k** (it completo ≈ 124k frases).

## T2 — Catálogo
- **`it` unificado** (Estudo Perspicaz) + **`rsg`** (Guia de Pesquisa atual) +
  **`nwt`** (Bíblia, card).
- **Removidos:** `nwtsty` (não baixável em nenhum formato) e **`it-3`**
  (não existe; a WOL publica 2 volumes).
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

## T4 — Retrieval cobre publicações grandes
- `loadCandidates` carregava **todos** os trechos do escopo e usava só os
  2000 primeiros (por `attachmentId, ord`) — com it+rsg (240k+ trechos) o
  resto ficava invisível, e a carga materializava tudo.
- Agora: candidatos **guiados pelos termos** da query (LIKE no normalizado,
  até 6 termos × 400, teto 2000) + **fallback com `LIMIT` no SQL**
  (`forAttachmentsLimited`) quando não há match.

## Validação no device (SM-A346M)
| Item | Resultado |
|---|---|
| `rsg_T.jwpub` (113MB) | **ready** · 115.758 trechos |
| `it_T.jwpub` (354MB) | **ready** · **126.633 trechos** em ~46s |
| Cobertura do it | A–Z (Zaanã…Zair; "Créditos das fotos" no fim) |
| Escopo por volume | 57.649 trechos A–I vs 60.526 J–Z |
| Banco Room | **127MB** (era 104MB) |
| Memória / crashes | PSS ~400MB · 0 FATAL/ANR |
| Retrieval e2e | "Zafenate-Paneia" citado do it no chat (antes invisível) |

## Estado
- Código/testes: **fechados** (6 commits, CI verde).
- Device: **validado** (import, indexação, escopo e retrieval).
