// Retrieval plugado ao Dexie — Fase 2 (metadata + split conteúdo/treinamento).
// Fase 3 vai trocar por hybrid retrieval (FTS + metadata + vetor + reranker).
// Mantém ragRetriever puro; aqui faz IO + ranking ingênuo com limite.

import { speechStorage } from '../services/db';
import { findPassagesByQuery } from '../services/ragRetriever';
import type { Passage } from '../types/speech';

function extractKeywords(text: string, maxWords = 6): string {
  const stop = new Set([
    'de', 'da', 'do', 'das', 'dos', 'em', 'no', 'na', 'nos', 'nas', 'que', 'com',
    'para', 'por', 'uma', 'um', 'os', 'as', 'o', 'a', 'e', 'se', 'não', 'nao',
    'como', 'mais', 'mas', 'foi', 'são', 'sao', 'tem', 'ter', 'ser', 'este',
    'esta', 'esse', 'essa', 'isso', 'isto', 'você', 'voce', 'ele', 'ela',
  ]);
  const words = text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^\w\s]/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length > 3 && !stop.has(w));
  const freq = new Map<string, number>();
  for (const w of words) freq.set(w, (freq.get(w) ?? 0) + 1);
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, maxWords)
    .map(([w]) => w)
    .join(' ');
}

export interface RelevantCorpus {
  content: Passage[];
  training: Passage[];
  all: Passage[];
}

export async function getRelevantCorpus(blockText: string, limit = 5): Promise<RelevantCorpus> {
  const passages = await getRelevantPassages(blockText, limit * 2);
  const training = passages.filter((p) => p.source_type === 'speech_training').slice(0, 2);
  const content = passages.filter((p) => p.source_type !== 'speech_training').slice(0, limit);
  return { content, training, all: passages.slice(0, limit) };
}

export async function getRelevantPassages(blockText: string, limit = 5): Promise<Passage[]> {
  const query = extractKeywords(blockText || '');
  if (!query) return [];
  try {
    const all = await speechStorage.getAllPassages(2000);
    if (all.length === 0) return [];
    // Tenta query completa; se vazia, tenta palavra a palavra (substring ingênuo atual).
    let hits = await findPassagesByQuery(query, all, limit);
    if (hits.length === 0) {
      for (const word of query.split(' ').slice(0, 3)) {
        hits = await findPassagesByQuery(word, all, limit);
        if (hits.length > 0) break;
      }
    }
    return hits;
  } catch {
    return [];
  }
}

export function formatProvenance(p: Passage): string {
  if (p.ref) return p.ref;
  const bits: string[] = [p.symbol || p.pubId];
  if (p.section) bits.push(p.section.slice(0, 40));
  else if (p.page) bits.push(`p. ${p.page}`);
  if (p.paragraph) bits.push(`§${p.paragraph}`);
  return bits.join(' ');
}

export function passagesToContextStrings(passages: Passage[]): string[] {
  return passages.slice(0, 5).map((p) => `[${formatProvenance(p)}] ${p.text}`.slice(0, 800));
}
