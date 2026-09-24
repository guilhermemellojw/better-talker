// Tipos do retrieval híbrido — Fase 3.
// Scores em [0,1] significam RELEVÂNCIA para recuperação, nunca verdade factual.
// Todo o ranking é determinístico: mesma query + scope + corpus => mesma ordem.

import type { Passage, Publication } from '../types/speech';

/** De qual trilho o chamador quer evidência (Fase 2: content vs training). */
export type RetrievalTrack = 'content' | 'training';

/**
 * Fontes autorizadas. O retrieval só carrega candidatos destes ids —
 * nunca varre a biblioteca inteira para filtrar depois (§3).
 */
export interface RetrievalScope {
  contentSourceIds: string[];
  trainingSourceIds: string[];
}

export interface RetrievalWeights {
  lexical: number;
  metadata: number;
  semantic: number;
  source: number;
}

/** Ponto de partida (§9). Ajustar só com evidência do benchmark. */
export const DEFAULT_RETRIEVAL_WEIGHTS: RetrievalWeights = {
  lexical: 0.4,
  metadata: 0.2,
  semantic: 0.3,
  source: 0.1,
};

export interface RetrievalOptions {
  track?: RetrievalTrack;
  limit?: number;
  weights?: Partial<RetrievalWeights>;
  /** Teto de candidatos carregados do escopo (protege memória). */
  maxCandidates?: number;
}

export const DEFAULT_RETRIEVAL_LIMIT = 5;
export const DEFAULT_MAX_CANDIDATES = 2000;

export type RetrievalStatus = 'ok' | 'insufficient_scope' | 'empty_corpus';

export type RetrievalStrategy = 'lexical' | 'metadata' | 'semantic';

/** Um passage candidato com scores separados por estratégia + proveniência. */
export interface RetrievalCandidate {
  passage: Passage;
  publicationTitle?: string;
  lexicalScore: number;
  metadataScore: number;
  semanticScore: number;
  finalScore: number;
  matchedTerms: string[];
  foundBy: RetrievalStrategy[];
}

export interface RetrievalResult {
  status: RetrievalStatus;
  hits: RetrievalCandidate[];
}

/** Fonte de dados desacoplada do Dexie — permite testes com fixtures. */
export interface PassageStore {
  getPassagesByIds(ids: string[], maxCandidates: number): Promise<Passage[]>;
  getPublicationsByIds(ids: string[]): Promise<Publication[]>;
}

export interface Retriever {
  retrieve(query: string, scope: RetrievalScope, options?: RetrievalOptions): Promise<RetrievalResult>;
}

export function clampScore(v: number): number {
  if (!Number.isFinite(v)) return 0;
  return Math.min(1, Math.max(0, v));
}

export function emptyScope(): RetrievalScope {
  return { contentSourceIds: [], trainingSourceIds: [] };
}
