// Busca lexical — Fase 3 (§5).
// Substitui o `text.includes(query)` por score TF-IDF sobre índice invertido
// em memória + bônus de frase exata. Determinístico, sem dependências.
// Campos: texto do passage (normalizado), seção e referência entram via
// sobreposição de tokens; título/símbolo pertencem ao metadataRetriever.

import type { Passage } from '../types/speech';
import { normalizeTokenText, tokenize, uniqueTokens } from './tokenize';
import { clampScore } from './retrievalTypes';

export interface LexicalScore {
  score: number;
  matchedTerms: string[];
}

/**
 * Totais de documentos por termo no conjunto candidato (para IDF).
 * Construído uma vez por chamada de retrieve.
 */
export class LexicalIndex {
  private docFreq = new Map<string, number>();
  private docCount: number;

  constructor(passages: Passage[]) {
    this.docCount = Math.max(1, passages.length);
    for (const p of passages) {
      const terms = new Set(tokenize(p.normalizedText || normalizeTokenText(p.text)));
      for (const t of terms) this.docFreq.set(t, (this.docFreq.get(t) ?? 0) + 1);
    }
  }

  idf(term: string): number {
    const df = this.docFreq.get(term) ?? 0;
    // Termo ausente do conjunto candidato não discrimina nada: contribui 0
    // (em vez de punir todos os candidatos igualmente com idf máximo).
    if (df === 0) return 0;
    // IDF suavizado em (0,1]: raro => ~1, onipresente => ~0.
    return clampScore(Math.log(1 + this.docCount / (1 + df)) / Math.log(1 + this.docCount));
  }
}

function countOccurrences(haystack: string, term: string): number {
  if (!term) return 0;
  let count = 0;
  let idx = haystack.indexOf(term);
  while (idx >= 0) {
    count++;
    idx = haystack.indexOf(term, idx + term.length);
  }
  return count;
}

export function scoreLexical(query: string, passage: Passage, index: LexicalIndex): LexicalScore {
  const queryTerms = uniqueTokens(tokenize(query));
  if (queryTerms.length === 0) return { score: 0, matchedTerms: [] };

  const text = passage.normalizedText || normalizeTokenText(passage.text);
  // Seção e referência participam do lexical como texto auxiliar (metadados
  // estruturados continuam no metadataRetriever, com score separado).
  const aux = normalizeTokenText(`${passage.section ?? ''} ${passage.ref ?? ''}`);
  const haystack = `${text} ${aux}`.trim();

  let weighted = 0;
  let weightSum = 0;
  const matchedTerms: string[] = [];
  for (const term of queryTerms) {
    const idf = index.idf(term);
    weightSum += idf;
    const tf = countOccurrences(haystack, term);
    if (tf > 0) {
      matchedTerms.push(term);
      // TF saturado: a 1ª ocorrência conta mais; repetições somam pouco.
      weighted += idf * Math.min(1, 0.7 + (0.3 * Math.min(tf, 4)) / 4);
    }
  }
  const coverage = weightSum > 0 ? weighted / weightSum : 0;

  // Bônus de frase exata (query normalizada contida no texto).
  const normalizedQuery = normalizeTokenText(query);
  const exactBonus =
    normalizedQuery.length >= 12 && text.includes(normalizedQuery) ? 0.3 : 0;

  return { score: clampScore(0.7 * coverage + exactBonus), matchedTerms };
}
