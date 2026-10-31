# C2.7 — citações por título de livro com página/§ (2026-10-31)

**Branch:** `fix/c2.7-book-title-pages` (a partir da `main` 3.0.2/33)
**Escopo:** fechar as 16 perdas de livro da matriz C2.6 e o dedup `lvs`.

## O que mudou

### Título de livro com página/§

O matcher de título integral agora aceita unidade depois do título mesmo
sem palavra-guia:

- `Beneficie-se, páginas 52-55; 166-169` → `book|be`, page=52, pageEnd=55.
- `Criador, páginas 8-9` → `book|ct`, page=8, pageEnd=9.
- `Continue, pág. 150-151 parág. 10-12` → `book|lvs`, page=150, §10-12.

A cauda é lida do texto **original** (não do normalizado), preservando o
hífen da faixa e NBSP/zero-width.

### Dedup `lvs 194 §18`

`lvs 194 § 18` gerava duas entradas:

- `book|lvs`, page=194, §18 (correta);
- `book|lvs`, chapter="estudo 194", §18 (ruído do fallback de estudo).

O dedup remove a variante `estudo N` quando existe uma ref por página N com
o mesmo parágrafo. `lvs 194 §18` agora rende **uma** entrada.

## Matriz após o C2.7

- 49 DOCX, **391 refs** (393 − 2 duplicatas `lvs`).
- **0 página perdida** (antes 16).
- **0 §/parág. perdido**.
- **0 duplicata `lvs`**.
- **0 ambígua de revista**; as 16 antigas de livro agora têm página.

## Testes

- `BookTitleCitationTest` (3):
  - Beneficie-se páginas 52-55;
  - Criador páginas 8-9;
  - `lvs 194 §18` sem duplicata.
- **2.760 testes JVM / 0 falhas** na branch.
- `lintRelease` verde.

## Limites

- Faixas de página **não contíguas** (“52-55; 166-169”) guardam a primeira
  faixa; a segunda fica como contexto do texto, não como `pageEnd`.
- T082 e T090–T099 continuam ausentes dos Downloads.
- Texto/ação das 391 refs ainda depende do acervo completo.

## Release

Correções publicadas na **v3.0.3/34**.
