// Continuidade da geração oratória — Fase 20-C (iteração + troca de alvo).
//
// Espelho conceitual de android/.../data/copilot/OratorySession.kt.
// Não é arquitetura nova: decide QUAL modo e QUAL ponto um pedido em
// linguagem natural quer, lembrando a última geração da sessão.
//
// - pedido explícito de modo manda (troca de modo/ponto);
// - refinamento ("melhore", "mais natural", "encurte", "explique melhor")
//   HERDA modo + ponto da última geração;
// - refinamento sem geração anterior → estado amigável;
// - "crie um ponto 4" → fora do escopo estrutural (nunca vira S-34).
// Puro: sem rede, LLM, banco, UI.

import type { S34Document } from './s34Parser';
import { normalizeTokenText } from './tokenize';
import {
  detectOratoryMode,
  oratoryActionFor,
  type OratoryAction,
  type OratoryMode,
} from './oratoryGeneration';

export interface LastOratoryGeneration {
  mode: OratoryMode;
  sectionId: string | null;
}

export type OratoryDecision =
  | { kind: 'generate'; mode: OratoryMode; sectionId: string | null; action: OratoryAction; inherited: boolean }
  | { kind: 'nothing-to-refine'; message: string }
  | { kind: 'out-of-structural-scope'; requestedPoint: number; message: string };

export const NOTHING_TO_REFINE_MESSAGE =
  'Ainda não gerei nenhuma parte. Peça, por exemplo, “Crie uma introdução” ou “Desenvolva o ponto 2”.';

export function outOfScopeMessage(point: number): string {
  return (
    `Não posso criar um ponto ${point}: a estrutura vem do S-34. ` +
    'Posso desenvolver um dos pontos existentes ou ajustar o texto deles.'
  );
}

/** Modos pedidos explicitamente (qualquer um deles vence herança). */
export function explicitOratoryMode(text: string): OratoryMode | null {
  return detectOratoryMode(text);
}

/** Refinamento: pedido que se apoia numa geração anterior. */
export function isOratoryRefinement(text: string): boolean {
  const t = ` ${normalizeTokenText(text)} `;
  return /(melhore|melhorar|melhor|refaca|refazer|reescreva|ajuste|corrija|deixe mais|mais natural|mais curta|encurte|encurtar|resuma|mais curto|explique melhor|aprofunde|detalhe|mais simples|mais direto)/.test(
    t,
  );
}

/** Número de ponto pedido ("ponto 3") — 1-based. */
export function requestedPoint(text: string): number | null {
  const t = ` ${normalizeTokenText(text)} `;
  const m = /(ponto|topico|secao|parte)\s*(n\.?\s*)?(\d{1,2})/.exec(t);
  if (m) return parseInt(m[3], 10);
  const bare = /\b(\d{1,2})\b/.exec(t);
  return bare ? parseInt(bare[1], 10) : null;
}

/** Pedido que tenta criar estrutura nova ("crie um ponto 4"). */
export function triesToCreateStructure(text: string, existingPoints: number): number | null {
  const t = ` ${normalizeTokenText(text)} `;
  const criar = /(crie|criar|adicione|adicionar|invente|inclua|inserir)/.test(t);
  const ponto = /(ponto|topico|secao|parte)/.test(t);
  if (!criar || !ponto) return null;
  const n = requestedPoint(text);
  if (n == null) return null;
  return n > existingPoints ? n : null;
}

/**
 * F20-E (§11/§13): a transição tem DOIS pontos. Ancorar no ponto de DESTINO
 * ("transição para o ponto 3") quebrava a geração no fim do esboço e não
 * seguia o contrato "2→3". Aqui a âncora é sempre a ORIGEM:
 * - "do ponto 2 para o 3" / "transição do ponto 2" → sec-2;
 * - "transição para o ponto 3" → sec-2 (o ponto imediatamente anterior);
 * - sem ponto explícito → ponto atual da sessão (comportamento da F20-C).
 */
function transitionAnchor(
  text: string,
  ordered: S34Document['sections'],
): { matched: boolean; sectionId: string | null } {
  const t = ` ${normalizeTokenText(text)} `;
  const pointWord = '(?:ponto|topico|secao|parte)';
  const from = new RegExp(`(?:^|\\s)(?:do|de|desde)\\s+(?:o\\s+|a\\s+)?${pointWord}\\s*(\\d{1,2})`).exec(t);
  if (from) {
    const n = parseInt(from[1], 10);
    return { matched: true, sectionId: ordered[n - 1]?.id ?? null };
  }
  const to = new RegExp(`(?:^|\\s)(?:para|ate|ao|a)\\s+(?:o\\s+|a\\s+)?${pointWord}\\s*(\\d{1,2})`).exec(t);
  if (to) {
    const n = parseInt(to[1], 10);
    // Destino N: a transição sai do ponto N-1.
    return { matched: true, sectionId: ordered[n - 2]?.id ?? null };
  }
  return { matched: false, sectionId: null };
}

function sectionFor(
  text: string,
  document: S34Document | null,
  currentSectionId: string | null,
  mode: OratoryMode,
): string | null {
  if (!document) return currentSectionId;
  const ordered = [...document.sections].sort((a, b) => a.order - b.order);
  if (ordered.length === 0) return null;
  if (mode === 'transition') {
    const anchor = transitionAnchor(text, ordered);
    if (anchor.matched) return anchor.sectionId;
  }
  const n = requestedPoint(text);
  if (n != null && ordered[n - 1]) return ordered[n - 1].id;
  if (mode === 'introduction') return ordered[0].id;
  if (mode === 'conclusion') return ordered[ordered.length - 1].id;
  return currentSectionId;
}

/** Decide o que fazer com o pedido. */
export function decideOratoryRequest(
  text: string,
  document: S34Document | null,
  currentSectionId: string | null,
  last: LastOratoryGeneration | null,
): OratoryDecision {
  const points = document?.sections.length ?? 0;

  const tries = triesToCreateStructure(text, points);
  if (tries != null) {
    return { kind: 'out-of-structural-scope', requestedPoint: tries, message: outOfScopeMessage(tries) };
  }

  const explicit = explicitOratoryMode(text);
  if (explicit) {
    return {
      kind: 'generate',
      mode: explicit,
      sectionId: sectionFor(text, document, currentSectionId, explicit),
      action: oratoryActionFor(text),
      inherited: false,
    };
  }

  if (isOratoryRefinement(text)) {
    if (!last) return { kind: 'nothing-to-refine', message: NOTHING_TO_REFINE_MESSAGE };
    return {
      kind: 'generate',
      mode: last.mode,
      sectionId: last.sectionId,
      action: 'replace',
      inherited: true,
    };
  }

  return { kind: 'nothing-to-refine', message: NOTHING_TO_REFINE_MESSAGE };
}
