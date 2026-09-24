// Parser proposta estruturada — Fase 5 (§§6,18).
// Extrai ```json de resposta do modelo, valida forma e COAGE o alvo para o
// bloco ativo (o modelo nunca escolhe alvo). JSON inválido => invalid_proposal.

import type { CopilotEditProposal, EditOperation, EditProposalMode } from './domain';
import { captureBaseHashes, MAX_OP_CONTENT_CHARS, MAX_PROPOSAL_OPS } from './editProposal';
import type { Speech } from '../types/speech';

export type ParseResult =
  | { ok: true; proposal: CopilotEditProposal }
  | { ok: false; error: 'invalid_proposal' };

interface RawOperation {
  type?: unknown;
  position?: unknown;
  content?: unknown;
  contentHtml?: unknown;
}

function extractJsonFence(text: string): string | null {
  const fenced = text.match(/```json\s*([\s\S]*?)```/i);
  if (fenced) return fenced[1].trim();
  const generic = text.match(/```\s*([\s\S]*?)```/);
  if (generic) return generic[1].trim();
  const trimmed = text.trim();
  if (trimmed.startsWith('{')) return trimmed;
  return null;
}

function coerceOperation(raw: RawOperation): EditOperation | null {
  if (!raw || typeof raw !== 'object') return null;
  // targetId sempre coagido depois; aqui só forma. Marcador temporário.
  if (raw.type === 'replace') {
    const content = typeof raw.contentHtml === 'string' ? raw.contentHtml : typeof raw.content === 'string' ? raw.content : '';
    if (!content.trim() || content.length > MAX_OP_CONTENT_CHARS) return null;
    return { type: 'replace', targetId: '', contentHtml: content };
  }
  if (raw.type === 'insert') {
    const content = typeof raw.contentHtml === 'string' ? raw.contentHtml : typeof raw.content === 'string' ? raw.content : '';
    const position = raw.position === 'before' ? 'before' : 'after';
    if (!content.trim() || content.length > MAX_OP_CONTENT_CHARS) return null;
    return { type: 'insert', targetId: '', position, contentHtml: content };
  }
  if (raw.type === 'delete') {
    return { type: 'delete', targetId: '' };
  }
  return null;
}

/**
 * Parseia a resposta do modelo para o bloco-alvo dado. O alvo vem do app
 * (bloco ativo), nunca do modelo (§18).
 */
export function parseEditProposal(
  rawText: string,
  speech: Speech,
  targetId: string,
  mode: EditProposalMode,
  proposalId?: string,
): ParseResult {
  const target = speech.blocks.find((b) => b.id === targetId);
  if (!target) return { ok: false, error: 'invalid_proposal' };

  if (mode === 'delete') {
    // Delete não precisa de LLM: proposta local determinística.
    return {
      ok: true,
      proposal: {
        id: proposalId ?? `prop-${Date.now()}`,
        mode,
        explanation: `Remover o bloco "${target.title}".`,
        operations: [{ type: 'delete', targetId }],
        baseHashes: captureBaseHashes(speech, [targetId]),
        createdAt: Date.now(),
      },
    };
  }

  const jsonText = extractJsonFence(rawText);
  if (!jsonText) return { ok: false, error: 'invalid_proposal' };
  let parsed: { explanation?: unknown; operations?: unknown };
  try {
    parsed = JSON.parse(jsonText) as { explanation?: unknown; operations?: unknown };
  } catch {
    return { ok: false, error: 'invalid_proposal' };
  }
  if (!Array.isArray(parsed.operations) || parsed.operations.length === 0) {
    return { ok: false, error: 'invalid_proposal' };
  }
  if (parsed.operations.length > MAX_PROPOSAL_OPS) return { ok: false, error: 'invalid_proposal' };

  const operations: EditOperation[] = [];
  for (const raw of parsed.operations) {
    const op = coerceOperation(raw as RawOperation);
    if (!op) return { ok: false, error: 'invalid_proposal' };
    // Coage o alvo: modelo não escolhe bloco (§18). Delete via LLM é vetado.
    if (op.type === 'delete') return { ok: false, error: 'invalid_proposal' };
    operations.push({ ...op, targetId });
  }

  return {
    ok: true,
    proposal: {
      id: proposalId ?? `prop-${Date.now()}`,
      mode,
      explanation: typeof parsed.explanation === 'string' ? parsed.explanation.slice(0, 500) : undefined,
      operations,
      baseHashes: captureBaseHashes(speech, [targetId]),
      createdAt: Date.now(),
    },
  };
}
