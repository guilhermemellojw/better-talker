# Matriz C2.6 — 49 esboços S-34 reais (2026-10-30)

**Fonte:** `/home/guilherme/Downloads/S-34_T_*.docx`
**Branch:** `fix/c2.6-magazine-unit-context` + correções da matriz
**Escopo:** detecção de publicações, unidade (página/§), edição e
ambiguidade. Texto/ação por ref depende do acervo completo.

## Cobertura

- **49 arquivos únicos** (T001–T020, T070–T081, T083–T089, T100–T109).
- **393 referências de publicação** detectadas.
- Duplicatas `T003(1)`/`T080(1)` ignoradas.
- Ausentes na pasta: **T082** e **T090–T099** (não existem nos Downloads).

## Resultado

| Métrica | Resultado |
|---|---|
| Refs detectadas | **393** |
| Edição canônica gerada | **393** (nenhuma ref sem `editionKey`) |
| Ambíguas (`g 1/16`) | **0** |
| §/parág. perdido após normalização | **0** |
| Página perdida em revista | **0** |
| Página perdida em citação por título de livro | **16** (`Beneficie-se`/`Criador` com “páginas 52-55”) |

As 16 perdas restantes são citações por **título de livro** (“Beneficie-se,
páginas 52-55”), não revistas; o parser de título ainda não lê a página. Ficam
fora do C2.6 de revistas e entram como débito de livro.

## Correções que a matriz revelou

- NBSP do DOCX (`pág.\u00A06`, `parág.\u00A011`) não era espaço para as regexes.
- Zero-width em ranges (`22-\u200B23`) quebrava `pageEnd`.
- `¶5` (pilcrow) não virava parágrafo.
- `g 3/15 4 §§ 1-4` (Despertai por código) não capturava cauda.
- `wp`/`gn` por código não capturavam cauda.
- `lvs 8-9 §§ 8-9` (livro com página/§) não entrava no matcher de livros.

Todas cobertas por `MagazineTailTest`.

## Tabela por arquivo

| Esboço | Refs | Unidade perdida |
|---|---:|---:|
| 001 | 13 | 0 |
| 002 | 11 | 0 |
| 003 | 11 | 0 |
| 004 | 12 | 0 |
| 005 | 8 | 0 |
| 006 | 13 | 0 |
| 007 | 13 | 0 |
| 008 | 7 | 0 |
| 009 | 5 | 1 |
| 010 | 8 | 1 |
| 011 | 7 | 1 |
| 012 | 14 | 1 |
| 013 | 10 | 1 |
| 014 | 13 | 1 |
| 015 | 7 | 1 |
| 016 | 8 | 1 |
| 017 | 5 | 1 |
| 018 | 7 | 1 |
| 019 | 6 | 1 |
| 020 | 6 | 1 |
| 070 | 2 | 0 |
| 071 | 10 | 0 |
| 072 | 5 | 1 |
| 073 | 12 | 0 |
| 074 | 9 | 1 |
| 075 | 13 | 1 |
| 076 | 7 | 0 |
| 077 | 10 | 0 |
| 078 | 3 | 0 |
| 079 | 2 | 0 |
| 080 | 3 | 0 |
| 081 | 5 | 0 |
| 083 | 7 | 0 |
| 084 | 8 | 0 |
| 085 | 4 | 0 |
| 086 | 7 | 0 |
| 087 | 5 | 0 |
| 088 | 5 | 0 |
| 089 | 10 | 0 |
| 100 | 7 | 0 |
| 101 | 6 | 1 |
| 102 | 11 | 0 |
| 103 | 7 | 0 |
| 104 | 11 | 0 |
| 105 | 7 | 0 |
| 106 | 14 | 0 |
| 107 | 9 | 0 |
| 108 | 2 | 0 |
| 109 | 8 | 0 |

As linhas com 1 perda são o `Beneficie-se, páginas 52-55` (título de livro).

## Limite desta medição

O CSV sanitizado (apenas refs/edição/unidade, sem texto dos DOCX) está em
`docs/reports/matriz-c26-2026-10-30.csv`. O fixture CI-safe equivalente está
em `android/app/src/test/resources/s34-c26-fixture.tsv` e roda via
`S34CorpusFixtureTest`. A
medição de **texto/ação** por ref não foi feita para os 49 esboços porque o
acervo do A34 tinha apenas `g 2013`, `wp 2019` e `lmd`; as quatro refs reais
dessas edições foram validadas no A34 (relatório C2.6). Para fechar a matriz
completa de texto/ação, é preciso baixar/indexar as edições citadas ou rodar
os 49 esboços com o acervo completo.

## Correções publicadas

As correções de NBSP/zero-width/¶/`lvs`/cauda `g`-`wp` entram na **v3.0.2/33**
(ver `docs/releases/v3.0.2.md`).
