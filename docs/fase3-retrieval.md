# Fase 3 — Retrieval híbrido com ranking (documentação §21)

## 1. Arquitetura

```text
query (texto do bloco ativo)
  ↓  tokenize.ts (normalização NFD, stopwords PT, min 3 letras)
RetrievalScope { contentSourceIds, trainingSourceIds }  (buildScopeFromLibrary)
  ↓  §3: carrega SOMENTE passages dos ids do escopo (dexiePassageStore)
  ├─ lexicalRetriever.ts   TF-IDF sobre índice invertido em memória + bônus frase exata
  ├─ metadataRetriever.ts  símbolo 0.45 + título 0.20 + seção 0.20 + referência 0.15
  └─ semanticRetriever.ts  NullSemanticScorer (score 0) — só abstração
  ↓  §8: fusão em RetrievalCandidate { passage, 3 scores, finalScore, matchedTerms, foundBy }
  ↓  §9: rerank = 0.4·lex + 0.2·meta + 0.3·sem + 0.1·sourcePriority
  ↓  §10: desempate final→lex→sem→meta→id (determinístico)
  ↓  RetrievalResult { status, hits } → ContextPack / Evidence → Copilot
```

Sem FTS nativo (IndexedDB não tem): o "FTS" é o índice invertido em memória
por chamada. Sem embeddings: nenhuma dependência nova de runtime (só `vitest`
como devDependency para os testes).

## 2. Como o scope funciona

- `buildScopeFromLibrary()` divide as publicações do Dexie por `source_type`:
  `speech_training` → `trainingSourceIds`; demais (incl. registros v2 sem
  campo) → `contentSourceIds`.
- `HybridRetriever.retrieve(query, scope, { track })` usa só os ids do trilho
  pedido. **Escopo vazio → `insufficient_scope`, sem varredura global** (§4).
- Corpus vazio no escopo → `empty_corpus`.
- Fonte fora do escopo jamais aparece como evidência (teste caso 4).

## 3. Como os scores são calculados

- **Lexical [0,1]**: `0.7·cobertura ponderada por IDF + 0.3·bônus frase exata`.
  Campos: `normalizedText` + texto auxiliar (seção, ref). TF saturado.
- **Metadata [0,1]**: sinais independentes do lexical (símbolo literal na
  query, overlap título/seção, referência citada ≥50%).
- **Semântico**: sempre 0 (stub). Peso 0.3 mantido na fórmula para quando
  houver scorer real — sem "redistribuição" silenciosa.
- **Fonte**: bible 1.0, publication 0.9, speech_training 0.9, user 0.7,
  indefinido (v2) 0.85. **Só impulsiona candidatos já sinalizados** (nunca
  admite sozinha — decisão tomada após o teste caso 9 falhar).
- Pesos em `DEFAULT_RETRIEVAL_WEIGHTS`, sobrescrevíveis por chamada.

## 4. Fusão e trilhos

- Cada hit carrega `matchedTerms` e `foundBy[]` (proveniência da estratégia).
- `track:'content'` exclui BE/TH; `track:'training'` retorna só BE/TH (§12).
- `candidateToEvidence()` / `buildContextPackFromCandidates()` levam
  `score`, `matchedTerms`, `foundBy` ao `ContextPack` (§13, compatível —
  campos opcionais em `EvidenceSource`).

## 5. Limitações atuais

1. Semântica real inexistente (interface pronta).
2. Índice em memória por consulta: OK até ~5000 passages; acima disso,
   considerar tabela de tokens no Dexie (migração v4, Fase 3b).
3. `getPassagesByPubId` em loop (N queries); aceitável no volume atual.
4. `retrieve()` decide "sem sinal" de forma binária por estratégia; limiar
   de corte de `finalScore` não calibrado em corpus real.
5. Benchmark sobre fixture mínima — mede regressão, não qualidade real.

## 6. Métricas do benchmark (`npm test`, K=5, 8 casos)

Recall@5 médio **1.000**, MRR médio **1.000** — esperado numa fixture de 7
passages; o valor está na **detecção de regressões futuras**, não em qualidade
absoluta. Casos em `src/copilot/__tests__/retrievalBenchmark.ts`.

## 7. Decisões com impacto futuro

- **Fase 4**: `SemanticScorer` é o ponto de encaixe para embeddings/Qwen;
  pesos já reservam 0.3 ao semântico — recalibrar com benchmark ao ativar.
- **Fase 5**: `getRelevantEvidence()` + `buildContextPackFromCandidates()`
  prontos; `App.tsx` ainda descarta o pack (`void`) e insere texto via append.
- **Fase 6**: `foundBy`, `matchedTerms`, `score` e `ref/seção/página` fluem
  até o candidato — base suficiente para verificação.
- `ragRetriever.ts` mantido intocado, sem consumidores ativos.
