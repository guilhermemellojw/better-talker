// Núcleo de edição assistida — Fase 5: validação + aplicação atômica.
// Puro e determinístico (sem DOM, sem IO): tudo testável em Node.
// O LLM nunca toca aqui; só CopilotEditProposal já parseada.

import type { Speech, SpeechBlock } from '../types/speech';
import type {
  CopilotEditProposal,
  ProposalApplyStatus,
  ProposalValidationError,
} from './domain';

export const MAX_PROPOSAL_OPS = 10;
export const MAX_OP_CONTENT_CHARS = 20000;

export type ValidationResult = { ok: true } | { ok: false; error: ProposalValidationError };

/** Hash determinístico (FNV-1a) para detecção de stale_proposal (§§12,13). */
export function hashText(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/** Texto puro sem DOM (testes rodam em Node). */
export function stripHtmlToText(html: string): string {
  return (html || '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|h[1-6]|li|tr)>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#\d+;/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n')
    .trim();
}

function blockById(blocks: SpeechBlock[], id: string): SpeechBlock | undefined {
  return blocks.find((b) => b.id === id);
}

function targetsOf(proposal: CopilotEditProposal): string[] {
  return [...new Set(proposal.operations.map((op) => op.targetId))];
}

/** Valida sem aplicar: alvos, posições, invariantes e stale (§12). */
export function validateEditProposal(speech: Speech, proposal: CopilotEditProposal): ValidationResult {
  if (proposal.operations.length === 0 || proposal.operations.length > MAX_PROPOSAL_OPS) {
    return { ok: false, error: 'invalid_position' };
  }
  const targets = targetsOf(proposal);
  for (const id of targets) {
    if (!blockById(speech.blocks, id)) return { ok: false, error: 'unknown_target' };
  }
  for (const op of proposal.operations) {
    if (op.type === 'insert') {
      if (op.position !== 'before' && op.position !== 'after') {
        return { ok: false, error: 'invalid_position' };
      }
      if (!op.contentHtml || !op.contentHtml.trim() || op.contentHtml.length > MAX_OP_CONTENT_CHARS) {
        return { ok: false, error: 'empty_content' };
      }
    }
    if (op.type === 'replace') {
      if (!op.contentHtml || !op.contentHtml.trim() || op.contentHtml.length > MAX_OP_CONTENT_CHARS) {
        return { ok: false, error: 'empty_content' };
      }
    }
    if (op.type === 'delete') {
      // Invariante do editor: sempre ao menos 1 bloco.
      const remaining = speech.blocks.filter(
        (b) => !(proposal.operations.some((o) => o.type === 'delete' && o.targetId === b.id)),
      );
      if (remaining.length < 1) return { ok: false, error: 'last_block' };
    }
  }
  // Stale: qualquer alvo cujo conteúdo mudou desde a geração.
  for (const id of targets) {
    const current = blockById(speech.blocks, id);
    const base = proposal.baseHashes[id];
    if (base !== undefined && current && hashText(current.contentHtml) !== base) {
      return { ok: false, error: 'stale_proposal' };
    }
  }
  return { ok: true };
}

export interface ApplyResult {
  status: ProposalApplyStatus;
  speech?: Speech;
  error?: ProposalValidationError;
}

let insertCounter = 0;

/**
 * Aplica atomicamente: valida tudo antes; qualquer falha => nada muda (§11).
 * Retorna novo Speech (não muta o original).
 */
export function applyEditProposal(speech: Speech, proposal: CopilotEditProposal): ApplyResult {
  const validation = validateEditProposal(speech, proposal);
  if (!validation.ok) {
    return {
      status: validation.error === 'stale_proposal' ? 'stale_proposal' : 'invalid',
      error: validation.error,
    };
  }
  let blocks: SpeechBlock[] = speech.blocks.map((b) => ({ ...b }));

  for (const op of proposal.operations) {
    if (op.type === 'replace') {
      blocks = blocks.map((b) =>
        b.id === op.targetId
          ? { ...b, contentHtml: op.contentHtml, plainText: stripHtmlToText(op.contentHtml) }
          : b,
      );
    } else if (op.type === 'delete') {
      blocks = blocks.filter((b) => b.id !== op.targetId);
    } else {
      const idx = blocks.findIndex((b) => b.id === op.targetId);
      if (idx < 0) return { status: 'invalid', error: 'unknown_target' };
      insertCounter += 1;
      const sibling = blocks[idx];
      const created: SpeechBlock = {
        id: `block-${speech.id}-copilot-${Date.now()}-${insertCounter}`,
        speechId: speech.id,
        order: 0,
        minutes: Math.max(1, Math.round(sibling.minutes / 2)),
        title: sibling.title ? `${sibling.title} (continuação)` : 'Bloco inserido',
        contentHtml: op.contentHtml,
        plainText: stripHtmlToText(op.contentHtml),
      };
      const at = op.position === 'before' ? idx : idx + 1;
      blocks = [...blocks.slice(0, at), created, ...blocks.slice(at)];
    }
  }
  blocks = blocks.map((b, i) => ({ ...b, order: i }));

  return {
    status: 'applied',
    speech: { ...speech, blocks, updatedAt: Date.now() },
  };
}

/** Captura os hashes-base do alvo no momento da geração (§13). */
export function captureBaseHashes(speech: Speech, targetIds: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const id of targetIds) {
    const block = blockById(speech.blocks, id);
    if (block) out[id] = hashText(block.contentHtml);
  }
  return out;
}
