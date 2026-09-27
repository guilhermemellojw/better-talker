// Detector determinístico de esboço S-34 — Fase 19-B.1.
//
// Domínio puro: texto entra, classificação sai. Sem LLM, sem rede, sem
// retrieval. Não é chamado por ninguém ainda (integração na próxima fatia).
//
// Estratégia (multi-sinal, mesma do Android S34Detector — mesmo contrato):
// - `S-34` como marcador obrigatório;
// - + pelo menos 2 sinais entre: seções temporizadas (convenção `(N min)`,
//   mesmo padrão de outlineParser TIME_REGEX), pontos numerados,
//   referências bíblicas por extenso, refs de publicação (detectCitations)
//   e bloco de objetivo.
// A tabela de livros é porte mecânico de Android
// RefDetector.BIBLE_BOOKS (F19-B.1) — mesma fonte, sem Rust/TS compartilhado.

import { detectCitations } from '../services/citationDetector';
import { normalizeTokenText } from './tokenize';

export type S34Signal = 'timed' | 'numbered' | 'bible' | 'pub' | 'objective';

/** Piso anti-fragmento (documentado em S34Detector.kt). */
export const MIN_S34_CHARS = 200;

const MARKER_RE = /\bS[-\s]?34\b/i;
// Mesmo padrão de services/outlineParser.ts TIME_REGEX.
const TIMED_RE = /\(\s*(\d+)\s*min(utos)?\s*\)/gi;
const NUMBERED_RE = /^\s*\d{1,2}[.)]\s+\S/gm;
const OBJECTIVE_RE = /^\s*objetivo\s*:/gim;
const VERSE_SHAPE_RE = /\b((?:[1-3]\s+)?[A-Za-zÀ-ÿ]+)\s+(\d{1,3})\s*:\s*(\d{1,3})\b/g;

/** Porte de Android RefDetector.BIBLE_BOOKS (chave normalizada → rótulo). */
const BIBLE_BOOKS_PT: Record<string, string> = {
  gen: 'Gênesis', genesis: 'Gênesis',
  ex: 'Êxodo', exodo: 'Êxodo',
  lev: 'Levítico', levitico: 'Levítico',
  num: 'Números', numeros: 'Números',
  deut: 'Deuteronômio', deuteronomio: 'Deuteronômio',
  jos: 'Josué', josue: 'Josué',
  jz: 'Juízes', juizes: 'Juízes',
  rt: 'Rute', rute: 'Rute',
  '1sm': '1 Samuel', '1samuel': '1 Samuel',
  '2sm': '2 Samuel', '2samuel': '2 Samuel',
  '1rs': '1 Reis', '1reis': '1 Reis',
  '2rs': '2 Reis', '2reis': '2 Reis',
  '1cr': '1 Crônicas', '1cronicas': '1 Crônicas',
  '2cr': '2 Crônicas', '2cronicas': '2 Crônicas',
  ed: 'Esdras', esdras: 'Esdras',
  ne: 'Neemias', neemias: 'Neemias',
  est: 'Ester', ester: 'Ester',
  jo: 'João', joao: 'João',
  sal: 'Salmos', salmos: 'Salmos', salmo: 'Salmos',
  pro: 'Provérbios', proverbios: 'Provérbios',
  ecl: 'Eclesiastes', eclesiastes: 'Eclesiastes',
  cant: 'Cânticos', cantares: 'Cânticos',
  is: 'Isaías', isaias: 'Isaías',
  jr: 'Jeremias', jeremias: 'Jeremias',
  lam: 'Lamentações', lamentacoes: 'Lamentações',
  ez: 'Ezequiel', ezequiel: 'Ezequiel',
  dn: 'Daniel', daniel: 'Daniel',
  os: 'Oseias', oseias: 'Oseias',
  jl: 'Joel', joel: 'Joel',
  am: 'Amós', amos: 'Amós',
  ob: 'Obadias', obadias: 'Obadias',
  jn: 'Jonas', jonas: 'Jonas',
  mq: 'Miqueias', miqueias: 'Miqueias',
  hc: 'Habacuque', habacuque: 'Habacuque',
  sof: 'Sofonias', sofonias: 'Sofonias',
  ag: 'Ageu', ageu: 'Ageu',
  zc: 'Zacarias', zacarias: 'Zacarias',
  ml: 'Malaquias', malaquias: 'Malaquias',
  mt: 'Mateus', mateus: 'Mateus',
  mc: 'Marcos', marcos: 'Marcos',
  lc: 'Lucas', lucas: 'Lucas',
  at: 'Atos', atos: 'Atos',
  rm: 'Romanos', romanos: 'Romanos',
  '1co': '1 Coríntios', '1corintios': '1 Coríntios',
  '2co': '2 Coríntios', '2corintios': '2 Coríntios',
  gl: 'Gálatas', galatas: 'Gálatas',
  ef: 'Efésios', efesios: 'Efésios',
  fp: 'Filipenses', filipenses: 'Filipenses',
  cl: 'Colossenses', colossenses: 'Colossenses',
  '1ts': '1 Tessalonicenses', '1tessalonicenses': '1 Tessalonicenses',
  '2ts': '2 Tessalonicenses', '2tessalonicenses': '2 Tessalonicenses',
  '1tm': '1 Timóteo', '1timoteo': '1 Timóteo',
  '2tm': '2 Timóteo', '2timoteo': '2 Timóteo',
  tt: 'Tito', tito: 'Tito',
  fm: 'Filemom', filemom: 'Filemom',
  hb: 'Hebreus', hebreus: 'Hebreus',
  tg: 'Tiago', tiago: 'Tiago',
  '1pe': '1 Pedro', '1pedro': '1 Pedro',
  '2pe': '2 Pedro', '2pedro': '2 Pedro',
  '1jo': '1 João', '1joao': '1 João',
  '2jo': '2 João', '2joao': '2 João',
  '3jo': '3 João', '3joao': '3 João',
  jd: 'Judas', judas: 'Judas',
  ap: 'Apocalipse', apocalipse: 'Apocalipse',
};

export function hasS34Marker(text: string): boolean {
  return MARKER_RE.test(text);
}

export interface S34VerseRef {
  book: string;
  label: string;
  chapter: number;
  verse: number;
  raw: string;
}

/** Versículos por extenso com estrutura (o parser S-34 consome este). */
export function detectBibleVerseRefs(text: string): S34VerseRef[] {
  const out: S34VerseRef[] = [];
  const seen = new Set<string>();
  VERSE_SHAPE_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = VERSE_SHAPE_RE.exec(text)) !== null) {
    const book = normalizeTokenText(m[1]).replace(/ /g, '');
    const label = BIBLE_BOOKS_PT[book];
    if (!label) continue;
    const chapter = parseInt(m[2], 10);
    const verse = parseInt(m[3], 10);
    const key = `${label}|${chapter}|${verse}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ book, label, chapter, verse, raw: m[0] });
  }
  return out;
}

/** Versículos por extenso ("João 17:17"); chaves validadas na tabela. */
export function detectBibleVerses(text: string): string[] {
  return detectBibleVerseRefs(text).map((r) => `${r.label}|${r.chapter}|${r.verse}`);
}

export function s34Signals(text: string): Set<S34Signal> {
  const out = new Set<S34Signal>();
  TIMED_RE.lastIndex = 0;
  let timed = 0;
  while (TIMED_RE.exec(text) !== null) timed++;
  if (timed >= 2) out.add('timed');
  NUMBERED_RE.lastIndex = 0;
  let numbered = 0;
  while (NUMBERED_RE.exec(text) !== null) numbered++;
  if (numbered >= 2) out.add('numbered');
  if (detectBibleVerses(text).length > 0) out.add('bible');
  if (detectCitations(text, 's34').length > 0) out.add('pub');
  OBJECTIVE_RE.lastIndex = 0;
  if (OBJECTIVE_RE.test(text)) out.add('objective');
  return out;
}

/** Verdadeiro somente para esboço S-34 completo o bastante para analisar. */
export function isS34(text: string): boolean {
  if (text.length < MIN_S34_CHARS) return false;
  if (!hasS34Marker(text)) return false;
  return s34Signals(text).size >= 2;
}
