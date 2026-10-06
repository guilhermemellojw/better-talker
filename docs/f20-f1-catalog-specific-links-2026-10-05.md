# Catálogo — Links Específicos por Publicação (2026-10-05)

## Commit
- `11ba9c6` — Build ✅ + Deploy ✅

## Testes JVM
- **1344 passed / 0 failed** (+1: páginas específicas; testes de "nunca WOL"
  atualizados).

## Problema
O "Baixar" usava o finder genérico (`jw.org/finder?…&wfile=<símbolo>`), que
**caía na home do jw.org** (validado no device: título genérico do site). O
`/download/?pub=` também é shell JS (renderiza vazio).

## Correção
Cada entrada do catálogo ganhou `downloadUrl` **explícita** — a página da
publicação no jw.org (com opções de download), **todas verificadas por HTTP
(200)**:

| Publicação | URL |
|---|---|
| nwtsty | `jw.org/pt/biblioteca/biblia/biblia-de-estudo/` |
| th | `jw.org/pt/biblioteca/brochuras/leitura-e-ensino/` |
| be | `jw.org/pt/biblioteca/livros/Beneficie-se-da-Escola-do-Ministério-Teocrático/` |
| lmd | `jw.org/pt/biblioteca/brochuras/ame-pessoas-faca-discipulos/` |
| dx | `jw.org/pt/biblioteca/indices/` (categoria onde vive o Guia de Pesquisa) |
| it-1/2/3 | `jw.org/pt/biblioteca/livros/estudo-perspicaz-das-escrituras/` |
| w / g | `jw.org/pt/biblioteca/revistas/` (escolha da edição) |
| mwb | `jw.org/pt/biblioteca/jw-apostila-do-mes/` |
| rr | `jw.org/pt/biblioteca/livros/adoracao-pura/` |
| ia | `jw.org/pt/biblioteca/livros/` (sem página dedicada encontrada) |

- `pageUrl` (WOL) permanece secundária; "Formatos" (be/th) inalterado.

## Validação no device — BLOQUEADA
- O **ADB/USB caiu** antes de abrir o app (`no devices/emulators found`; device
  ausente do `lsusb` mesmo após `kill-server`/`start-server`).
- Validação **parada e reportada** conforme a regra.
- Pendente: confirmar no device que o Baixar abre as páginas específicas
  (rr/lmd/it-1 como representativos + sanidade geral).

## Estado
- Correção: **fechada no código/testes** (13 URLs verificadas por HTTP; CI
  verde).
- Device: pendente de reconexão.
