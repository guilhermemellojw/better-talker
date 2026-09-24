// Tokenização PT compartilhada pelos retrievers — determinística, sem dependências.

const STOPWORDS = new Set([
  'de', 'da', 'do', 'das', 'dos', 'em', 'no', 'na', 'nos', 'nas', 'que', 'com',
  'para', 'por', 'uma', 'um', 'uns', 'umas', 'os', 'as', 'o', 'a', 'e', 'se',
  'nao', 'como', 'mais', 'mas', 'foi', 'sao', 'tem', 'ter', 'ser', 'este',
  'esta', 'esse', 'essa', 'isso', 'isto', 'voce', 'ele', 'ela', 'eles', 'elas',
  'seu', 'sua', 'meu', 'minha', 'nosso', 'nossa', 'quando', 'onde', 'qual',
  'quais', 'porque', 'pois', 'entre', 'sobre', 'ate', 'muito', 'pela', 'pelo',
  'algo', 'tudo', 'cada', 'outro', 'outra', 'estar',
]);

export function normalizeTokenText(text: string): string {
  return (text || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Tokens normalizados sem stopwords e com tamanho mínimo 3. */
export function tokenize(text: string): string[] {
  return normalizeTokenText(text)
    .split(' ')
    .filter((w) => w.length >= 3 && !STOPWORDS.has(w));
}

/** Tokens únicos preservando ordem de primeira aparição (estável). */
export function uniqueTokens(tokens: string[]): string[] {
  return [...new Set(tokens)];
}
