// Busca por metadados — Fase 3 (§6).
// Score SEPARADO do lexical: usa símbolo, título, seção, referência,
// página/parágrafo e tipo de fonte. Sinais binários/sobreposição, em [0,1].

import type { Passage } from '../types/speech';
import { normalizeTokenText, tokenize, uniqueTokens } from './tokenize';
import { clampScore } from './retrievalTypes';

export interface MetadataContext {
  /** Título da publicação do passage (mapa pubId -> título). */
  publicationTitle?: string;
}

const SIGNAL_SYMBOL = 0.45;
const SIGNAL_TITLE = 0.2;
const SIGNAL_SECTION = 0.2;
const SIGNAL_REF = 0.15;

function overlapRatio(queryTerms: string[], fieldTerms: string[]): number {
  if (queryTerms.length === 0 || fieldTerms.length === 0) return 0;
  const field = new Set(fieldTerms);
  let hits = 0;
  for (const t of queryTerms) if (field.has(t)) hits++;
  return hits / queryTerms.length;
}

export function scoreMetadata(query: string, passage: Passage, ctx: MetadataContext = {}): number {
  const queryTerms = uniqueTokens(tokenize(query));
  const normalizedQuery = normalizeTokenText(query);
  if (queryTerms.length === 0) return 0;

  let score = 0;

  // Símbolo (ex: "be", "w24") mencionado na query.
  const symbol = (passage.symbol || '').toLowerCase();
  if (symbol && normalizedQuery.split(' ').includes(symbol)) {
    score += SIGNAL_SYMBOL;
  }

  // Título da publicação: sobreposição de tokens.
  const titleTerms = uniqueTokens(tokenize(ctx.publicationTitle || ''));
  score += SIGNAL_TITLE * overlapRatio(queryTerms, titleTerms);

  // Seção do trecho: sobreposição de tokens.
  const sectionTerms = uniqueTokens(tokenize(passage.section || ''));
  score += SIGNAL_SECTION * overlapRatio(queryTerms, sectionTerms);

  // Referência citada literalmente na query (ex: "be §3", "p. 12").
  const ref = normalizeTokenText(passage.ref || '');
  if (ref) {
    const refTerms = uniqueTokens(tokenize(ref));
    const refOverlap = overlapRatio(refTerms, queryTerms);
    if (refOverlap >= 0.5) score += SIGNAL_REF;
    else score += SIGNAL_REF * refOverlap * 0.5;
  }

  return clampScore(score);
}
