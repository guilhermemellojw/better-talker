// Retrieval especializado de treinamento — Fase 7 (§§10,11,13).
// Trilho exclusivo trainingSourceIds; filtro/boost por categoria de intenção.
// Nunca mistura conteúdo: o chamador combina os trilhos no ContextPack.

import { HybridRetriever } from './hybridRetriever';
import { trainingCategoryOf } from './trainingClassifier';
import type { TrainingCategory } from './domain';
import type {
  PassageStore,
  RetrievalCandidate,
  RetrievalResult,
  RetrievalScope,
} from './retrievalTypes';

export interface TrainingQuery {
  query: string;
  /** Intenção (ex: 'transition'). Ausente = genérico, sem filtro. */
  category?: TrainingCategory;
  scope: RetrievalScope;
  store: PassageStore;
  limit?: number;
}

/** Bônus determinístico de intenção — documentado, sem tocar no finalScore. */
export const CATEGORY_MATCH_BOOST = 0.15;

export function passageTrainingCategory(
  passage: { training_category?: string; symbol?: string; title?: string; section?: string; text: string },
  publicationTitle?: string,
): TrainingCategory {
  return trainingCategoryOf(passage.training_category, {
    symbol: passage.symbol,
    title: publicationTitle ?? passage.title,
    section: passage.section,
    text: passage.text,
  });
}

export async function retrieveTraining(input: TrainingQuery): Promise<RetrievalResult> {
  const { query, category, scope, store, limit = 4 } = input;
  if (scope.trainingSourceIds.length === 0) {
    return { status: 'insufficient_scope', hits: [] };
  }
  const retriever = new HybridRetriever(store);
  // Busca ampla no trilho; intenção reordena depois (sem re-query).
  const res = await retriever.retrieve(query, scope, { track: 'training', limit: Math.max(limit * 3, 6) });
  if (res.status !== 'ok') return res;

  let hits: RetrievalCandidate[] = res.hits;
  if (category && category !== 'unknown') {
    const scored = hits.map((h) => ({
      hit: h,
      adjusted:
        h.finalScore +
        (passageTrainingCategory(h.passage, h.publicationTitle) === category ? CATEGORY_MATCH_BOOST : 0),
    }));
    scored.sort(
      (a, b) =>
        b.adjusted - a.adjusted ||
        b.hit.finalScore - a.hit.finalScore ||
        (a.hit.passage.id < b.hit.passage.id ? -1 : 1),
    );
    hits = scored.map((s) => s.hit);
  }
  return { status: 'ok', hits: hits.slice(0, limit) };
}
