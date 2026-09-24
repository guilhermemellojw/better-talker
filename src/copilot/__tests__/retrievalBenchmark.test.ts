// Executa o benchmark e registra Recall@K / MRR@K — Fase 3 (§18).

import { describe, expect, it } from 'vitest';
import { HybridRetriever } from '../hybridRetriever';
import { fixtureStore } from './fixtures';
import {
  BENCHMARK_CASES,
  BENCHMARK_K,
  benchmarkScope,
  recallAtK,
  reciprocalRank,
} from './retrievalBenchmark';

describe('retrieval benchmark', () => {
  it('mede Recall@K e MRR@K sobre os casos de referência', async () => {
    const retriever = new HybridRetriever(fixtureStore());
    const scope = benchmarkScope();
    let recallSum = 0;
    let mrrSum = 0;
    const rows: string[] = [];
    for (const c of BENCHMARK_CASES) {
      const res = await retriever.retrieve(c.query, scope, { track: c.track, limit: BENCHMARK_K });
      const ids = res.hits.map((h) => h.passage.id);
      const r = recallAtK(ids, c.expectedPassageIds, BENCHMARK_K);
      const m = reciprocalRank(ids, c.expectedPassageIds, BENCHMARK_K);
      recallSum += r;
      mrrSum += m;
      rows.push(`${c.name}: Recall@${BENCHMARK_K}=${r.toFixed(2)} MRR=${m.toFixed(2)} top1=${ids[0] ?? '-'}`);
    }
    const recall = recallSum / BENCHMARK_CASES.length;
    const mrr = mrrSum / BENCHMARK_CASES.length;
    console.log(`\nBenchmark retrieval (K=${BENCHMARK_K}):\n- Recall@${BENCHMARK_K} médio: ${recall.toFixed(3)}\n- MRR médio: ${mrr.toFixed(3)}\n${rows.map((row) => `- ${row}`).join('\n')}`);
    expect(recall).toBeGreaterThanOrEqual(0);
    expect(recall).toBeLessThanOrEqual(1);
    expect(mrr).toBeGreaterThanOrEqual(0);
    expect(mrr).toBeLessThanOrEqual(1);
  });
});
