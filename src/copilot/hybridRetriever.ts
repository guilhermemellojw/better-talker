// HybridRetriever — Fase 3 (§§3,4,8,9,10,11,12).
//
// Sequência obrigatória: SourceScope -> candidatos permitidos -> retrieval
// -> ranking. Escopo vazio => `insufficient_scope`, NUNCA varredura global.
// Score = relevância de recuperação em [0,1], nunca verdade factual.

import type { Passage } from '../types/speech';
import { LexicalIndex, scoreLexical } from './lexicalRetriever';
import { scoreMetadata } from './metadataRetriever';
import { NullSemanticScorer, type SemanticScorer } from './semanticRetriever';
import {
  DEFAULT_MAX_CANDIDATES,
  DEFAULT_RETRIEVAL_LIMIT,
  DEFAULT_RETRIEVAL_WEIGHTS,
  clampScore,
  type PassageStore,
  type RetrievalCandidate,
  type RetrievalOptions,
  type RetrievalResult,
  type RetrievalScope,
  type Retriever,
  type RetrievalWeights,
} from './retrievalTypes';

/**
 * Prioridade por tipo de fonte (componente `source` do rerank).
 * Registros v2 sem `source_type` recebem 0.85 (neutro, abaixo de publication).
 */
export const SOURCE_PRIORITY: Record<string, number> = {
  bible: 1.0,
  publication: 0.9,
  speech_training: 0.9,
  user: 0.7,
};

export function sourcePriorityOf(passage: Passage): number {
  if (!passage.source_type) return 0.85;
  return SOURCE_PRIORITY[passage.source_type] ?? 0.5;
}

function resolveWeights(partial?: Partial<RetrievalWeights>): RetrievalWeights {
  return { ...DEFAULT_RETRIEVAL_WEIGHTS, ...(partial ?? {}) };
}

export class HybridRetriever implements Retriever {
  private store: PassageStore;
  private semantic: SemanticScorer;

  constructor(store: PassageStore, semantic?: SemanticScorer) {
    this.store = store;
    this.semantic = semantic ?? new NullSemanticScorer();
  }

  async retrieve(
    query: string,
    scope: RetrievalScope,
    options: RetrievalOptions = {},
  ): Promise<RetrievalResult> {
    const track = options.track ?? 'content';
    const limit = options.limit ?? DEFAULT_RETRIEVAL_LIMIT;
    const maxCandidates = options.maxCandidates ?? DEFAULT_MAX_CANDIDATES;
    const weights = resolveWeights(options.weights);

    const allowedIds =
      track === 'content' ? scope.contentSourceIds : scope.trainingSourceIds;

    // §4: sem fontes autorizadas => insufficient_scope, sem fallback global.
    if (allowedIds.length === 0) {
      return { status: 'insufficient_scope', hits: [] };
    }

    // §3: carrega SOMENTE candidatos do escopo.
    const candidates = await this.store.getPassagesByIds(allowedIds, maxCandidates);
    if (candidates.length === 0) {
      return { status: 'empty_corpus', hits: [] };
    }

    const publications = await this.store.getPublicationsByIds(allowedIds);
    const titles = new Map(publications.map((p) => [p.id, p.title]));

    const index = new LexicalIndex(candidates);
    const hits: RetrievalCandidate[] = [];

    for (const passage of candidates) {
      const lex = scoreLexical(query, passage, index);
      const meta = scoreMetadata(query, passage, {
        publicationTitle: titles.get(passage.pubId),
      });
      const sem = clampScore(this.semantic.score(query, passage));

      const finalScore = clampScore(
        weights.lexical * lex.score +
          weights.metadata * meta +
          weights.semantic * sem +
          weights.source * sourcePriorityOf(passage),
      );

      const foundBy: RetrievalCandidate['foundBy'] = [];
      if (lex.score > 0) foundBy.push('lexical');
      if (meta > 0) foundBy.push('metadata');
      if (sem > 0) foundBy.push('semantic');

      // Prioridade de fonte só impulsiona candidatos já sinalizados —
      // nunca admite um passage sem nenhum match (evita foundBy vazio).
      if (foundBy.length === 0) continue;
      if (finalScore <= 0) continue;

      hits.push({
        passage,
        publicationTitle: titles.get(passage.pubId),
        lexicalScore: lex.score,
        metadataScore: meta,
        semanticScore: sem,
        finalScore,
        matchedTerms: lex.matchedTerms,
        foundBy,
      });
    }

    // §10: ordenação determinística com desempate estável.
    hits.sort(
      (a, b) =>
        b.finalScore - a.finalScore ||
        b.lexicalScore - a.lexicalScore ||
        b.semanticScore - a.semanticScore ||
        b.metadataScore - a.metadataScore ||
        (a.passage.id < b.passage.id ? -1 : a.passage.id > b.passage.id ? 1 : 0),
    );

    return { status: 'ok', hits: hits.slice(0, Math.max(0, limit)) };
  }
}
