# F19-B.3 — Persistência do OutlineDocument

## 1. Modelo escolhido

Quatro tabelas normalizadas espelhadas nas duas plataformas:

```text
s34_outlines    (id, sourceAttachmentId, symbol, title, objective,
                 headerLinesJson, createdAt, updatedAt, parserVersion)
s34_sections    (id, outlineId, position, title, content, minutes, sourceLine)
s34_subsections (id, sectionId, position, content, sourceLine)
s34_references  (id, outlineId, sectionId?, subsectionId?, position, type,
                 rawText, normalizedReference, sourceLine, book, bookNorm,
                 chapter, verse, pubKind, pubKey, pubLabel, editionKey)
```

- `order` do domínio vira `position` (`order` é palavra reservada no SQLite;
  `position` é coluna de índice e não tem semântica de ranking).
- `id` estável da B.2 preservado (`s34-<hash>`); filhos determinísticos
  (`sec-N`, `sec-N-M`, `<outlineId>-ref-<order>`).
- Sem FK formal (convenção do projeto: cascade explícito, como
  `attachment`→`passages` já faz).

### Relação com `attachments/passages`

```text
attachments (arquivo importado)
   ├── passages          (índice textual existente — INTOCADO)
   └── s34_outlines ─┐   (estrutura do esboço — NOVO)
        ├── s34_sections
        ├── s34_subsections
        └── s34_references
```

Fonte estrutural do S-34 = `s34_outlines`; `passages` continua servindo
o que já usa (retrieval lexical atual). Nenhuma representação paralela
divergente: `s34_*` deriva do parser, não é mantida à mão.

## 2. Schema/version

- Room: **v10 → v11** (aditiva). `MigrationSql.MIGRATION_10_11` com as
  mesmas strings que a produção executa.
- Dexie: **v4 → v5** (aditiva): stores `s34outlines` (`id,
  sourceAttachmentId`), `s34sections` (`id, outlineId, position`),
  `s34subsections` (`id, sectionId, position`), `s34references` (`id,
  outlineId, sectionId, position`). Nenhum `orderBy` em campo sem índice
  (lição da F13); ordenação em JS por `position` após query indexada.

## 3. Repositories

**Android** `S34OutlineRepository(S34Dao)`:
`save(doc, sourceAttachmentId)`, `get(outlineId)`, `getBySource(id)`,
`deleteBySource(id)`. Reconstrução em 4 queries (outline + seções + subs +
refs) — sem N+1.

**Web** `s34Repository.ts`: `saveS34Outline`, `getS34Outline`,
`getS34BySource`, `deleteS34BySource` (transação única por operação).

O parser não conhece banco; o repositório não parseia (§15/§16).

## 4. Cascade / delete

- Android: `LibraryRepository.delete(id)` e
  `NotesRepository.deleteForever(id)` chamam `deleteBySource` antes de
  apagar o attachment.
- Web: `speechStorage.deletePublication` → `deletePublicationCascade`
  (transação: publications + passages + s34_*).
- Remoção sempre na ordem: refs → subs → sections → outline.

## 5. Idempotência

`save` procura outline existente pela **source**; se houver (mesmo id ou
não), apaga tudo antes de reinserir. Resultado: mesma entrada → 1 outline;
entrada alterada → estrutura nova sem restos da anterior (4ª seção não
soma às 3 antigas; seção removida leva subs/refs junto). `createdAt` é
preservado quando o id não muda.

## 6. Invariantes (testadas nas duas plataformas)

1. seção pertence a um único outline; 2. subseção a uma única seção;
3. referência a um único outline; 4. `sectionId` aponta seção do mesmo
outline; 5. `subsectionId` aponta subseção da seção indicada; 6. sem IDs
duplicados; 7. ordem determinística (`position`); 8. persistência não cria
conteúdo (contagens parser = contagens restauradas).

## 7. Limitações herdadas da B.2

- Refs fora de seção (título/objetivo) não são estruturadas: não existem
  como `s34_references` (contrato §12: sem associação inventada).
- `headerLines` persiste como JSON array (texto não-estruturado, sem
  provenance fina) — round-trip estrutural não depende dele, mas ele
  volta.
- `pubKey` derivado do `rawText` no web (o `CitationCard` não carrega
  `pubKey`); no Android vem do `DetectedRef`.
- `passages` continua sem `s34_references`; unificar é B.4, não aqui.

## 8. Como a B.4 deverá consultar

```text
getBySource(attachmentId) → S34Document
   → sections[order]            ("quais são os pontos, em ordem")
   → section.subsections        ("subideias do ponto 2")
   → section.references         ("refs do ponto 2", com sourceLine)
   → objective                  ("qual é o objetivo")
```

Filtro por `sectionId` no retrieval entrará por cima de `s34_sections.id`
/ `s34_references.sectionId`, sem tocar no índice textual existente.

## 9. Performance

S-34 típico da fixture: 4 rows de outline/subs/refs? Não — 1 outline,
3 seções, 2 subseções, 5 referências = **11 rows**. Leitura completa = 4
queries (Android) / 4 queries (web), sem N+1. Cresce linear com o
documento.
