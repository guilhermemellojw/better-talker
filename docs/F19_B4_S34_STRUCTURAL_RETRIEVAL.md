# F19-B.4 — Retrieval estrutural por `sectionId` + ponto atual

## 1. O que entrou

```text
S-34 persistido (B.3)
   ↓  getBySource
S34Document
   ↓  resolveSection(dica)  ──┐
   ↓  scopeToSection(id)      ├─ S34StructuralRetrieval (puro)
   ↓  markMatches(query)    ──┘
ScopedView: ponto + subpontos + referências, EM ORDEM
```

Arquivos:
- Android `data/s34/S34StructuralRetrieval.kt` (puro) +
  `data/repo/S34StructuralRetriever.kt` (fino, sobre B.3);
- Web `src/copilot/s34StructuralRetrieval.ts` (puro) +
  `retrieveS34Structural` em `s34Repository.ts` (fino).

## 2. Contrato explícito (nunca fallback silencioso)

```text
SectionFocus(view)         ponto em foco, ordenado
DocumentScope(id, views)   escopo pedido foi o documento (todos os pontos)
NoOutline                  não há S-34 nesta source → legado decide
UnknownSection(id, req)    havia outline; a seção pedida não é dele
UnmatchedSection(id, hint) dica ambígua/ausente → nunca chuta
```

`sectionId` tem precedência sobre `sectionHint`. Nenhum estado devolve
outro outline, outra seção ou o documento inteiro no lugar do pedido.

## 3. Regras

- **Ordem = `sourceLine`** do S-34 original (documento), desempate por
  `id`. Score não existe nesta camada.
- **Query só marca** `matched` (booleano informativo). Não reordena, não
  remove, não promove.
- **Referência permanece no dono**: `ownerId` = seção ou subseção; refs
  de subseção nunca aparecem soltas na seção.
- **Escopo é fronteira dura**: outra seção do mesmo outline e qualquer
  outro S-34 ficam fora.
- **Ponto atual**: igualdade de título normalizado; depois contenção
  única. Ambíguo ⇒ `UnmatchedSection`.
- Offline, sem LLM, sem IO na lógica pura; provenance (`sourceLine`,
  `ownerId`, `id`) preservada em toda entrada.

## 4. Bug real encontrado pelo teste de isolamento

As chaves de seção do parser (`sec-1`, `sec-N-M`) são determinísticas
**por documento**. Ao persistir dois S-34, ambos escreviam `sec-1` na
mesma chave primária → **um sobrescrevia o outro**. Detectado pelo teste
obrigatório de isolamento (S34-A e S34-B com `sec-1`/`sec-2`).

Correção (camada de persistência, sem tocar o contrato da B.2):

```text
armazenamento:  "<outlineId>:sec-2"      (único entre documentos)
domínio:        "sec-2"                  (id da B.2 intacto)
```

- Android: `S34OutlineRepository.storageKey/localId`; Room **v11 → v12**
  (`MigrationSql.MIGRATION_11_12`) recria as tabelas `s34_*` — cache
  derivado, reconstruível do arquivo-fonte; nenhum dado do usuário é
  tocado.
- Web: `s34StorageKey/s34LocalId`; Dexie **v5 → v6** com o mesmo
  upgrade (clear dos stores `s34*`).
- `PARSER_VERSION` 1 → 2.

## 5. Legado intacto

`RoomRetrievalRepository` / `HybridRetrieval` / `ContextPack` / prompt /
UI / ChatIntent não foram tocados (verificado por `git diff`). Sem S-34
persistido o resultado é `NoOutline` e o chamador segue no índice
textual — o retriever estrutural não inventa escopo nem substitui nada.

## 6. Como a B.5 consulta

```kotlin
val r = retriever.retrieve(sourceId, sectionHint = blocoAtivo, query = mensagem)
when (r) {
  is SectionFocus -> r.view.entries        // já em ordem, com provenance
  is DocumentScope -> r.sections           // mapa ordenado do discurso
  NoOutline -> /* índice textual legado */
  else -> /* estado explícito, sem chute */
}
```

O bloco estruturado do ContextPack sai de `view.entries` (ordem
documental) + `objective` do documento; a cláusula S-34 entra no
SYSTEM_PROMPT. Nada disso é feito nesta fase.
