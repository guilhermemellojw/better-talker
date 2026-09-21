# Catálogo WOL (fonte do PubCatalog.kt)

- `publications.json`: sigla -> {title, kind, wol} — 130 entradas.
- Gerado rastreando (set/2026):
  - `https://wol.jw.org/pt/wol/library/r5/lp-t/todas-as-publicações/livros`
  - `.../brochuras-e-livretos`
  - `.../apostilas`
  - padrão dos cards: `Título (sigla)` -> `/pt/wol/publication/r5/lp-t/<sigla>`
  - + entradas manuais: w, wp, g, mwb.
- Para regenerar: extrair os cards das 3 categorias e rodar o gerador
  (ver PubCatalog.kt header). Só metadados (títulos/links) — sem conteúdo.
