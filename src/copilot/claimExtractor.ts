// Extração de claims por regras locais — Fase 6 (§§7,8,16,17,18).
// Determinístico, sem rede, sem DOM. Não reescreve o texto.

import { detectCitations } from '../services/citationDetector';
import type { ClaimType, ExtractedClaim } from './domain';

export const MAX_CLAIMS_PER_TEXT = 20;

const QUESTION_START = /^(quem|o que|qual|quais|como|quando|onde|por ?que|será|e se|acaso)\b/i;
const IMAGINE = /^(imagine|considere|suponha|feche os olhos|pense em|visualize)\b/i;
const APPLICATION = /(isso (pode )?nos ajud|podemos aplicar|devemos|que possamos|nos ajuda a|vamos aplicar|ponha em prática|coloque em prática)/i;
const TRAINING = /(ilustra|transi|introdução|conclusão|tom de voz|contato visual|oratória|no palco|discurso deve|pausa estrat|module a voz)/i;
const INTERPRETIVE = /(isso significa|portanto|logo,|ou seja|isso mostra|podemos concluir|a lição|isso nos ensina)/i;
const NUMBER = /\d[\d.,]*\s?%?/;

function splitSentencesWithOffsets(text: string): Array<{ text: string; start: number }> {
  const out: Array<{ text: string; start: number }> = [];
  const src = text || '';
  let start = 0;
  const pushUpTo = (end: number) => {
    const raw = src.slice(start, end);
    const trimmedStart = start + (raw.length - raw.trimStart().length);
    const t = raw.trim();
    if (t.length > 3) out.push({ text: t, start: trimmedStart });
    start = end;
  };
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    if (ch === '.' || ch === '!' || ch === '?' || ch === '…') {
      let j = i;
      while (j + 1 < src.length && '.!?…'.includes(src[j + 1])) j++;
      let k = j + 1;
      while (src[k] === '"' || src[k] === '“' || src[k] === '”' || src[k] === ')') k++;
      const prev = src[i - 1] ?? '';
      const next = src[k] ?? '';
      // Nunca partir "24.01", "§3" etc.: ponto entre dígitos.
      const betweenDigits = /\d/.test(prev) && /\d/.test(next);
      const rest = src.slice(k);
      const boundary = k >= src.length || /^\s*["“(]*[A-ZÀ-Ú]/.test(rest);
      if (!betweenDigits && boundary) {
        pushUpTo(k);
        i = k;
        continue;
      }
      i = j + 1;
      continue;
    }
    i++;
  }
  pushUpTo(src.length);
  return out;
}

/** Divide compostas (§8): ';' e 'e/mas' + nova oração. */
function splitCompound(sentence: string, base: number): Array<{ text: string; start: number }> {
  const parts: Array<{ text: string; start: number }> = [];
  const chunks = sentence.split(/(;\s+|\s+e\s+(?=[A-ZÀ-Ú])|\s+mas\s+(?=[A-ZÀ-Ú]))/);
  let cursor = base;
  let current = '';
  let currentStart = base;
  for (const chunk of chunks) {
    if (/^(;\s+|\s+e\s+|\s+mas\s+)$/.test(chunk) && current.trim()) {
      parts.push({ text: current.trim(), start: currentStart });
      current = '';
      cursor += chunk.length;
      currentStart = cursor;
      continue;
    }
    if (!current) currentStart = cursor;
    current += chunk;
    cursor += chunk.length;
  }
  if (current.trim()) parts.push({ text: current.trim(), start: currentStart });
  return parts.length > 0 ? parts : [{ text: sentence, start: base }];
}

function classifyType(sentence: string): ClaimType {
  const t = sentence.trim();
  if (/\?$/.test(t) || QUESTION_START.test(t)) return 'rhetorical';
  if (IMAGINE.test(t)) return 'creative';
  if (APPLICATION.test(t)) return 'application';
  if (TRAINING.test(t)) return 'training';
  try {
    if (detectCitations(t, 'tmp').length > 0) return 'biblical';
  } catch {
    // detector indisponível: segue heurística textual.
  }
  if (/(versículo|texto bíblico|a bíblia (diz|ensina)|escrituras)/i.test(t)) return 'biblical';
  if (INTERPRETIVE.test(t)) return 'interpretive';
  return 'factual';
}

/** Números que exigem presença literal na evidência (§16). */
export function extractNumbers(text: string): string[] {
  const matches = text.match(/\d[\d.,]*\s?%?|\b(19|20)\d{2}\b/g);
  return [...new Set((matches ?? []).map((n) => n.replace(/\s+/g, '').toLowerCase()))];
}

function hasNumber(s: string): boolean {
  return NUMBER.test(s);
}

export function extractClaims(text: string, blockId?: string): ExtractedClaim[] {
  const claims: ExtractedClaim[] = [];
  let counter = 0;
  const push = (t: string, start: number, type: ClaimType) => {
    if (claims.length >= MAX_CLAIMS_PER_TEXT) return;
    counter += 1;
    claims.push({
      id: `claim-${counter}`,
      text: t,
      type,
      blockId,
      start,
      end: start + t.length,
    });
  };

  for (const s of splitSentencesWithOffsets(text || '')) {
    for (const part of splitCompound(s.text, s.start)) {
      const type = classifyType(part.text);
      push(part.text, part.start, type);
      // §18: aplicação com dado específico gera sub-claim factual do número.
      if (type === 'application' && hasNumber(part.text)) {
        const m = part.text.match(/[^.!?;]*\d[\d.,]*\s?%[^.!?;]*/);
        if (m && m[0].trim().length > 5) {
          push(m[0].trim(), part.start + (m.index ?? 0), 'factual');
        }
      }
    }
  }
  return claims;
}
