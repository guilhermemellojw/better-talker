// Verifier — Fase 6 (stub tipado na Fase 1 para impedir atribuição falsa desde já).
// Nunca marcar SUPPORTED só porque a LLM acha correto: exigir evidência.

import type { Claim, ClaimStatus, EvidenceSource } from './domain';

export interface VerificationResult {
  claimId: string;
  status: ClaimStatus;
  reason: string;
  matchedSources: string[];
}

/** Heurística conservadora inicial: sem evidência textual => UNVERIFIED. */
export function verifyClaimAgainstSources(
  claim: Claim,
  sources: EvidenceSource[],
): VerificationResult {
  if (claim.status === 'GENERATED') {
    return {
      claimId: claim.id,
      status: 'GENERATED',
      reason: 'Conteúdo criativo (ilustração, pergunta, transição) — não é afirmação factual.',
      matchedSources: [],
    };
  }
  if (sources.length === 0 || claim.sources.length === 0) {
    return {
      claimId: claim.id,
      status: 'UNVERIFIED',
      reason: 'Nenhuma fonte relevante encontrada no corpus local.',
      matchedSources: [],
    };
  }
  const matched = claim.sources.filter((id) => sources.some((s) => s.id === id));
  if (matched.length === 0) {
    return {
      claimId: claim.id,
      status: 'UNVERIFIED',
      reason: 'Fontes citadas não correspondem a trechos recuperados.',
      matchedSources: [],
    };
  }
  // Fase 6 completa fará NLI/score; por ora marca INFERENCE para exigir revisão humana.
  return {
    claimId: claim.id,
    status: 'INFERENCE',
    reason: 'Trecho recuperado é candidato a evidência; requer confirmação humana antes de SUPPORTED.',
    matchedSources: matched,
  };
}
