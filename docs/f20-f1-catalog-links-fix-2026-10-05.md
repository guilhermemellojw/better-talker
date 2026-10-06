# Correção — Links do Catálogo (jw.org, não WOL) — 2026-10-05

## Commits
- **T1 — redirecionamento:** `2678801` — Build ✅ + Deploy ✅

## Testes JVM
- **1343 passed / 0 failed** (T1: +2).

## T1 — Links de download no jw.org
- `RecommendedPub.downloadUrl` (novo): `https://www.jw.org/finder?wtlocale=T&srcid=share&wfile=<symbol>`
  — destino do botão **Baixar**.
- `pageUrl` (WOL) permanece apenas como **secundária** (leitura/estudo).
- `LibraryScreen` passa a usar `downloadUrl` no Baixar; "Formatos" (be/th)
  inalterado; fluxo de refs segue com `JwMediaApi` (arquivo direto).
- **Verificação HTTP:** as 13 URLs do finder → **200**; a página usa a API
  oficial `GETPUBMEDIALINKS` (a mesma do app) para oferecer os arquivos.
- +2 testes: download nunca aponta para `wol.jw.org` (sempre finder com
  `wfile=<símbolo>`); `pageUrl` segue WOL e nunca é o download.

## T2 — Validação no device (13/13)
Todas as 13 publicações abriram a **WebView BYOD no jw.org** (título
"Testemunhas de Jeová — site oficial | jw.org | Português (Brasil)"):

| Publicação | Abriu | Download na página |
|---|---|---|
| nwtsty | jw.org (finder) | sim (página oficial de download) |
| th | jw.org (finder) | sim |
| be | jw.org (finder) | sim |
| lmd | jw.org (finder) | sim |
| dx | jw.org (finder) | sim |
| it-1 | jw.org (finder) | sim |
| it-2 | jw.org (finder) | sim |
| it-3 | jw.org (finder) | sim |
| w | jw.org (finder) | sim |
| g | jw.org (finder) | sim |
| mwb | jw.org (finder) | sim |
| rr | jw.org (finder) | sim |
| ia | jw.org (finder) | sim |

Notas da captura: o finder é uma aplicação JS — a árvore de acessibilidade
expôs apenas o título do site (sem texto "Baixar" no dump), e a validação
HTTP confirmou 200 + uso do `GETPUBMEDIALINKS` em todas. Nenhum "Baixar"
abriu a WOL. ✅

## Divergências / débitos
- **1 toque para revistas** no catálogo não se aplica (não há edição
  definida); permanece no fluxo de refs (`downloadEdition` com `apiIssue`).
  O catálogo usa o finder (BYOD) — o usuário escolhe a edição e baixa.
- WOL mantida como link secundário de leitura (não usada no Baixar).

## Estado
- Links do catálogo: **fechado** (código + testes + device 13/13).
- CI: verde (Build + Deploy).
