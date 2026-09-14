export type CitationMatch = {
  raw: string;
  url: string;
  context?: string;
};

export type CitationCard = {
  raw: string;
  blockId: string;
  status: 'missing' | 'resolving' | 'resolved';
  jwUrl: string;
  suggestedLang?: string;
};

const BIBLICAL_REF_REGEX = /(?:w|jo|r\w|b)\d{2}\.\d+\s+\d+\s+§\s+\d+/g;
const PUBLICATION_REF_REGEX = /\b(?:g|it|lff|wg|w)\s+\d+\/\d+\b/g;
const SIMPLE_REF_REGEX = /\b(?:w\d{2}\.\d{2}|jo\d{2}\.\d{2})\b/g;

export function detectCitations(text: string, blockId: string): CitationCard[] {
  const cards: CitationCard[] = [];
  const seen = new Set<string>();

  const refs = [
    ...extractMatches(BIBLICAL_REF_REGEX, text),
    ...extractMatches(PUBLICATION_REF_REGEX, text),
    ...extractMatches(SIMPLE_REF_REGEX, text),
  ];

  for (const ref of refs) {
    if (seen.has(ref)) continue;
    seen.add(ref);

    const url = buildJwUrl(ref);
    cards.push({
      raw: ref,
      blockId,
      status: 'missing',
      jwUrl: url,
    });
  }

  return cards;
}

function extractMatches(regex: RegExp, text: string): string[] {
  const matches = text.match(regex);
  return matches ? [...new Set(matches)] : [];
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

export function getCitationLabel(ref: string): string {
  const lower = ref.toLowerCase();
  if (lower.includes('§')) return 'Estudo de Vídeo';
  if (lower.startsWith('g')) return 'Guia de Estudo';
  if (lower.startsWith('it')) return 'Itinerário';
  if (lower.startsWith('lff')) return 'Lição Fiel';
  if (lower.startsWith('w')) return 'Revista/Publicação';
  return 'Publicação';
}
