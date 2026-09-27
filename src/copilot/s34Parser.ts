// Parser determinístico S-34 → S34Document — Fase 19-B.2.
//
// Espelho conceitual de android/.../data/s34/S34Parser.kt: mesmo algoritmo,
// mesmo contrato, mesmos IDs. Puro (sem Room/Dexie/IO/rede). Mesma entrada
// => mesma saída. Regras documentadas em docs/F19_B2_S34_PARSER.md.

import { hashText } from './editProposal';
import { detectCitations } from '../services/citationDetector';
import { normalizeTokenText } from './tokenize';
import { detectBibleVerseRefs } from './s34Detector';

export type S34RefType = 'bible' | 'publication';

export interface S34Reference {
  type: S34RefType;
  rawText: string;
  normalizedReference: string;
  /** Sequência global do documento (ordem de aparição). */
  order: number;
  /** Linha 1-based no texto original. */
  sourceLine: number;
  source: 'S34';
  bible?: { book: string; label: string; chapter: number; verse: number };
  publication?: { raw: string };
}

export interface S34Subsection {
  id: string;
  order: number;
  content: string;
  references: S34Reference[];
  sourceLine: number;
  source: 'S34';
}

export interface S34Section {
  id: string;
  /** 1-based, ordem de encontro — nunca por score. */
  order: number;
  title: string;
  minutes: number | null;
  content: string;
  subsections: S34Subsection[];
  references: S34Reference[];
  sourceLine: number;
  source: 'S34';
}

export interface S34Document {
  id: string;
  symbol: string;
  title: string;
  objective: string | null;
  sections: S34Section[];
  headerLines: string[];
  source: 'S34';
}

/** Índice global DERIVADO: fonte de verdade nas seções/subseções. */
export function collectS34References(doc: S34Document): S34Reference[] {
  return doc.sections
    .flatMap((s) => [...s.references, ...s.subsections.flatMap((sub) => sub.references)])
    .sort((a, b) => a.order - b.order);
}

const THEME_RE = /^\s*tema\s*:\s*(.+)$/i;
const POINT_RE = /^\s*\d{1,2}[.)]\s*(\S.*)$/;
const SUBPOINT_RE = /^\s*[a-z]\)\s*(.+)$/;
const TRAILING_MIN_RE = /\s*[(]\s*\d+\s*(min\.?|minutos?)\s*[)\]]\s*$/i;
const MIN_VALUE_RE = /[(]\s*(\d+)\s*(?:min\.?|minutos?)\s*[)\]]/i;
const MARKER_RE = /\bS[-\s]?34\b/i;
const OBJECTIVE_RE = /^\s*objetivo\s*:/i;

interface MutableSub {
  content: string;
  sourceLine: number;
}

interface MutableSection {
  order: number;
  title: string;
  minutes: number | null;
  sourceLine: number;
  body: Array<{ lineNo: number; text: string }>;
  subs: MutableSub[];
}

export function parseS34(rawText: string): S34Document {
  const lines = rawText.split('\n');
  let refOrder = 0;

  function scanRefs(text: string, lineNo: number): S34Reference[] {
    const out: S34Reference[] = [];
    for (const b of detectBibleVerseRefs(text)) {
      out.push({
        type: 'bible',
        rawText: text.trim(),
        normalizedReference: `${b.label}|${b.chapter}|${b.verse}`,
        order: ++refOrder,
        sourceLine: lineNo,
        source: 'S34',
        bible: { book: b.book, label: b.label, chapter: b.chapter, verse: b.verse },
      });
    }
    for (const c of detectCitations(text, 's34')) {
      out.push({
        type: 'publication',
        rawText: c.raw,
        normalizedReference: normalizeTokenText(c.raw),
        order: ++refOrder,
        sourceLine: lineNo,
        source: 'S34',
        publication: { raw: c.raw },
      });
    }
    return out;
  }

  let title = '';
  let objective: string | null = null;
  const headerLines: string[] = [];
  const sections: MutableSection[] = [];
  let inObjective = false;
  const objectiveLines: string[] = [];
  let current: MutableSection | null = null;

  function closeObjective(): void {
    if (!inObjective) return;
    inObjective = false;
    const text = objectiveLines.join('\n').trim();
    if (text) objective = text;
    objectiveLines.length = 0;
  }

  lines.forEach((raw, idx) => {
    const lineNo = idx + 1;
    const line = raw.trim();
    if (!line) {
      closeObjective();
      return;
    }
    const point = POINT_RE.exec(line);
    if (point) {
      closeObjective();
      const order = sections.length + 1;
      let rest = point[1].trim();
      let minutes: number | null = null;
      if (TRAILING_MIN_RE.test(rest)) {
        const m = MIN_VALUE_RE.exec(rest);
        minutes = m ? parseInt(m[1], 10) : null;
        rest = rest.replace(TRAILING_MIN_RE, '').trim();
      }
      const sec: MutableSection = {
        order,
        title: rest || `Ponto ${order}`,
        minutes,
        sourceLine: lineNo,
        body: [],
        subs: [],
      };
      sections.push(sec);
      current = sec;
      return;
    }
    if (inObjective) {
      objectiveLines.push(line);
      return;
    }
    const sub = SUBPOINT_RE.exec(line);
    if (sub) {
      if (current) current.subs.push({ content: line, sourceLine: lineNo });
      else headerLines.push(line);
      return;
    }
    const theme = THEME_RE.exec(line);
    if (theme) {
      if (!title) title = theme[1].trim();
      return;
    }
    if (OBJECTIVE_RE.test(line)) {
      const rest = line.split(':').slice(1).join(':').trim();
      inObjective = true;
      if (rest && rest.toLowerCase() !== 'objetivo') objectiveLines.push(rest);
      return;
    }
    if (current) current.body.push({ lineNo, text: line });
    else if (!title && !MARKER_RE.test(line)) title = line;
    else headerLines.push(line);
  });
  closeObjective();

  const docSections: S34Section[] = sections.map((s) => ({
    id: `sec-${s.order}`,
    order: s.order,
    title: s.title,
    minutes: s.minutes,
    content: s.body.map((b) => b.text).join('\n'),
    subsections: s.subs.map((sub, si) => ({
      id: `sec-${s.order}-${si + 1}`,
      order: si + 1,
      content: sub.content,
      references: scanRefs(sub.content, sub.sourceLine),
      sourceLine: sub.sourceLine,
      source: 'S34' as const,
    })),
    references: s.body.flatMap((bl) => scanRefs(bl.text, bl.lineNo)),
    sourceLine: s.sourceLine,
    source: 'S34' as const,
  }));

  return {
    id: `s34-${hashText(rawText)}`,
    symbol: 'S-34',
    title,
    objective,
    sections: docSections,
    headerLines,
    source: 'S34',
  };
}
