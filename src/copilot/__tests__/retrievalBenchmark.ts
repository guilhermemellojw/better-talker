// Benchmark de retrieval — Fase 3 (§18).
// Mede Recall@K e MRR@K sobre casos de referência fixos. Objetivo: referência
// objetiva para detectar regressões futuras, não números altos.

import type { RetrievalTrack } from '../retrievalTypes';
import { FIX_SCOPE } from './fixtures';

export interface BenchmarkCase {
  name: string;
  query: string;
  track: RetrievalTrack;
  expectedPassageIds: string[];
}

export const BENCHMARK_K = 5;

export const BENCHMARK_CASES: BenchmarkCase[] = [
  { name: 'frase exata (confiança)', query: 'Confiar em Jeová nos ajuda a enfrentar problemas graves com coragem.', track: 'content', expectedPassageIds: ['p-w24-1'] },
  { name: 'variação lexical (confiança)', query: 'confiando em jeova durante problemas graves', track: 'content', expectedPassageIds: ['p-w24-1'] },
  { name: 'oração', query: 'como a oração fortalece a amizade com Deus', track: 'content', expectedPassageIds: ['p-w24-2'] },
  { name: 'família', query: 'estudar a Bíblia em família e união', track: 'content', expectedPassageIds: ['p-w24-3'] },
  { name: 'metadata símbolo', query: 'oração w24', track: 'content', expectedPassageIds: ['p-w24-2'] },
  { name: 'training ilustrações', query: 'ilustrações simples do cotidiano no ensino', track: 'training', expectedPassageIds: ['p-be-1'] },
  { name: 'training transições', query: 'transições breves entre pontos principais', track: 'training', expectedPassageIds: ['p-be-2'] },
  { name: 'training entrega', query: 'modular a voz e pausas estratégicas', track: 'training', expectedPassageIds: ['p-th-1'] },
];

export function recallAtK(retrievedIds: string[], expectedIds: string[], k: number): number {
  if (expectedIds.length === 0) return 1;
  const top = new Set(retrievedIds.slice(0, k));
  const hits = expectedIds.filter((id) => top.has(id)).length;
  return hits / expectedIds.length;
}

export function reciprocalRank(retrievedIds: string[], expectedIds: string[], k: number): number {
  const expected = new Set(expectedIds);
  const top = retrievedIds.slice(0, k);
  for (let i = 0; i < top.length; i++) {
    if (expected.has(top[i])) return 1 / (i + 1);
  }
  return 0;
}

export function benchmarkScope() {
  return FIX_SCOPE;
}
