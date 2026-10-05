// Verificação objetiva da fidelidade da geração — Fase 20-C (§§37-39).
//
// Espelho conceitual de android/.../OratoryFidelityCheck.kt.
// Contagens, não julgamento: referências citadas fora do contexto
// autorizado, vazamento do ponto seguinte e números sem apoio literal.
// Sem score global. Complementa o F6 (que analisa claims).
// Puro: sem rede, LLM, banco.

import type { S34RefType } from './s34Parser';
import type { OratorySpec } from './oratoryGeneration';
import { isInsideSuggestion, suggestionSpans } from './claimExtractor';

export interface OratoryFidelityReport {
  /** Referências citadas que não pertencem ao contexto autorizado. */
  inventedReferences: string[];
  /** Referências do ponto SEGUINTE que vazaram para este. */
  leakedReferences: string[];
  /** Números citados sem apoio literal no conteúdo autorizado. */
  unsupportedNumbers: string[];
  ok: boolean;
}

const VERSE_RE = /\b((?:[1-3]\s+)?[A-Za-zÀ-ÿ]+)\s+(\d{1,3})\s*:\s*(\d{1,3})\b/g;
const PUB_RE = /\b((?:w|wp|g|gn|be|th|lff)\s*\d{2}(?:[./]\d{1,2})?)\b/gi;
const NUMBER_RE = /\b(\d{1,4}(?:[.,]\d{1,2})?)\b/g;

/** Só alfanuméricos, minúsculos: "Leia Tiago 2:17." → "leiatiago217". */
function normalizeRef(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '');
}

export function checkOratoryFidelity(generatedText: string, spec: OratorySpec): OratoryFidelityReport {
  const allowedLabels = (spec.current?.references ?? []).map((r) => normalizeRef(r.label));

  const otherLabels = (spec.next?.references ?? []).map((r) => normalizeRef(r.label));

  const invented: string[] = [];
  const leaked: string[] = [];

  // T2 — vazamento do ponto seguinte DENTRO de 〈sugestão〉 não é falha factual
  // (criação pode conectar pontos), mas referência inventada e número sem
  // apoio continuam contando (regra de ouro: prosa isenta, nunca dado/ref).
  const spans = suggestionSpans(generatedText);

  const collect = (cited: string, pos: number) => {
    const key = normalizeRef(cited);
    if (allowedLabels.some((l) => l.includes(key))) return;
    if (otherLabels.some((l) => l.includes(key) || key.includes(l))) {
      if (isInsideSuggestion(pos, spans)) return;
      leaked.push(cited);
      return;
    }
    invented.push(cited);
  };

  for (const m of generatedText.matchAll(VERSE_RE)) collect(m[0], m.index ?? 0);
  for (const m of generatedText.matchAll(PUB_RE)) collect(m[0], m.index ?? 0);

  const authorized = [
    spec.current?.content ?? '',
    ...(spec.current?.subsections ?? []),
    ...(spec.current?.references ?? []).map((r) => r.text ?? ''),
    ...spec.contentSources.map((c) => c.text),
  ].join(' ');

  const unsupported = [
    ...new Set(
      [...generatedText.matchAll(NUMBER_RE)]
        .map((m) => m[1])
        .filter((n) => n.length > 1 && !authorized.includes(n)),
    ),
  ];

  const dedupe = (xs: string[]) => [...new Set(xs)];
  const inventedReferences = dedupe(invented);
  const leakedReferences = dedupe(leaked);
  return {
    inventedReferences,
    leakedReferences,
    unsupportedNumbers: unsupported,
    ok: inventedReferences.length === 0 && leakedReferences.length === 0 && unsupported.length === 0,
  };
}

/** Tipos de referência citados — útil para o relatório de aceitação. */
export function citedOratoryTypes(generatedText: string): Set<S34RefType> {
  const out = new Set<S34RefType>();
  if (new RegExp(VERSE_RE.source).test(generatedText)) out.add('bible');
  if (new RegExp(PUB_RE.source, 'i').test(generatedText)) out.add('publication');
  return out;
}
