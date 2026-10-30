# C2.5 — normalização de Sentinela/Despertai (2026-10-30)

**Status:** implementação fechada e validada no tablet. A34 pendente (aparelho
não conectado ao ADB nesta sessão). Sem bump de versão e sem tag.

- Branch: `feat/c2.5-revistas` (base `d950a81`, `main` 2.8.2/27).
- Commits: `3b704b6` (parser puro) e `381d610` (integração).
- Testes: **2.652 JVM / 0 falhas** (+19).
- `lintRelease`: **verde**. CI: **verde** (`40991333564`).
- Tablet: Xiaomi `25040RP0AL`, Android 16/API 36, debug com dados preservados.

## Modelo

`MagazineEditionParser` é puro e devolve:

| Campo | Valores |
|---|---|
| família | `WATCHTOWER` / `AWAKE` |
| tipo | `STUDY` / `PUBLIC` / `UNKNOWN` |
| idioma | `PT` / `EN` (campo separado, nunca sufixo da chave) |
| confiança | `EXACT` / `AMBIGUOUS` + nota |
| unidade | ano, mês, dia, issue, página/faixa, volume encadernado |

Chaves canônicas (as mesmas do resolvedor):

- `w|YYYY|M` — Sentinela de estudo mensal (`w25.07`).
- `wp|YYYY|M` — Sentinela pública numerada (`wp20.1`, `Sentinela N.º 3 de 2019`).
- `w|YYYY|M|D` — Sentinela antiga dia/mês (`w94 1/8`, `Sentinela 01/04/09`).
- `g|YYYY|M` — Despertai mensal (`Despertai! 08/13`, `g 6/14`).
- `g|YYYY|M|D` — Despertai antiga dia/mês (`g93 8/1`).
- `gn|YYYY|I` — Despertai numerada (`g16.1`, `gn 1/24`, `Despertai! N.º 1 2024`).
- `w|YYYY|bound` — volume encadernado (`w73 652`).

Faixas abreviadas são expandidas (`26-9` → `26-29`, `140-1` → `141`).

## Ambiguidade

`g 1/16` continua sendo **janeiro/2016 ou edição 1/2016**. O parser devolve
`AMBIGUOUS` com nota; o detector marca `DetectedRef.ambiguous`; `matchEdition`
recusa casar arquivo; o checklist mostra “Referência ambígua” sem link/probe.
A versão ambígua não colapsa com `g 1/2016` (chave de fonte distinta).

## Integração

- `RefDetector.detect` usa o parser para a chave de todas as revistas; página/§
  continuam vindo do detector. Nova detecção de `g16.1` como `gn`.
- `NoteRefsCollector.publicationRefToDetected` normaliza símbolos canônicos
  (`w94 1/8`, `g 6/07`, `gn 1/24`, `g16.1`) pelo mesmo parser.
- `NoteSourcesResolver.toNoteSources` força `EDITION_MISSING` com o motivo
  quando a ref é ambígua, mesmo que exista edição casável.
- `detectedFromJson` recompõe a ambiguidade a partir do `raw` persistido.

## Validação no tablet

Nota de validação com a frase:

`C25 g 1/16, g 1/2016, g16.1, Sentinela numero 3 de 2019, Despertai N. 1 2024, w94 1/8, wp24 N. 1`

Resultado: **7 fontes detectadas, 1 resolvida no acervo**.

- `g 1/16` → “Referência ambígua: g 1/16 pode ser janeiro/2016 ou edição
  1/2016”, sem “Baixar no jw.org”/“Baixar direto” (só Biblioteca).
- `g 1/2016` → `g|2016|1` exato, separado da ambígua.
- `g16.1` → “Despertai! — N.º 1/2016”, distinto de janeiro/2016.
- `Sentinela numero 3 de 2019` → “A Sentinela — N.º 3/2019”, **1/1 no acervo**.
- `Despertai N. 1 2024` → “Despertai! — N.º 1/2024”.
- `w94 1/8` → “A Sentinela — 1/8/1994”.
- `wp24 N. 1` → “A Sentinela — N.º 1/2024”.
- Regressão: nota existente com `w25.07 22 §§ 8-9` continua **7 de 7 no
  acervo**, com 5 fontes de “A Sentinela — julho de 2025”.
- Sem crash de `RefDetector`/checklist; nota de teste removida e #192
  preservada.

## A34

Pendente: o aparelho não apareceu no ADB nesta sessão (`adb devices` só
listou o tablet). A mudança é Kotlin puro (sem código específico de aparelho);
falta repetir o mesmo roteiro no A34 antes do release final.
