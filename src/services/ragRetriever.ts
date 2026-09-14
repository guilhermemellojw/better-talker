import type { Passage } from '../types/speech';
import type { CitationCard } from './citationDetector';

export async function findExactCitation(rawRef: string, passages: Passage[]): Promise<{ found: boolean; text?: string; passage?: Passage }> {
  const normalized = normalizeText(rawRef);

  if (!normalized || passages.length === 0) {
    return { found: false };
  }

  const exactMatch = passages.find((p) => p.normalizedText.includes(normalized) || normalized.includes(p.normalizedText));

  if (exactMatch) {
    return { found: true, text: exactMatch.text, passage: exactMatch };
  }

  return { found: false };
}

export async function findPassagesByQuery(query: string, passages: Passage[], limit: number = 5): Promise<Passage[]> {
  const normalizedQuery = normalizeText(query);
  if (!normalizedQuery || passages.length === 0) return [];

  return passages
    .filter((p) => p.normalizedText.includes(normalizedQuery) || normalizedQuery.includes(p.normalizedText))
    .slice(0, limit);
}

function normalizeText(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function buildCitationCards(rawRef: string, blockId: string): CitationCard {
  const url = buildJwUrl(rawRef);
  return {
    raw: rawRef,
    blockId,
    status: 'missing',
    jwUrl: url,
  };
}

function buildJwUrl(ref: string): string {
  const normalized = ref.toLowerCase().trim();
  if (normalized.startsWith('w') && normalized.includes('.')) {
    const parts = normalized.split('.');
    if (parts.length >= 2) {
      const year = parts[0].replace('w', '');
      const issue = parts[1];
      return `https://www.jw.org/finder?wtlocale=pt&srcid=share&wtmember=jf&wpage=1&wsession=1&wfile=${year}/${issue}`;
    }
  }
  if (normalized.startsWith('g')) {
    return `https://www.jw.org/finder?wtlocale=pt&srcid=share&wpage=1&wtmember=jf&wfile=g/${normalized.slice(1)}`;
  }
  return `https://www.jw.org/finder?wtlocale=pt&srcid=share&wpage=1&wtmember=jf&wfile=${normalized}`;
}
