// Juiz de verificação via LLM — Fase 6 (§§13,19,20).
// Contrato SEPARADO do gerador: avalia suporte documental, não verdade.
// Só recebe claim + evidências fornecidas; proibido inventar ou completar.

import type { ExtractedClaim } from './domain';
import { ProviderError } from './llmErrors';
import type { LlmProvider } from './llmProvider';

export interface JudgeEvidenceInput {
  id: string;
  ref: string;
  text: string;
}

export interface JudgeInput {
  claim: ExtractedClaim;
  evidence: JudgeEvidenceInput[];
}

export interface JudgeVerdict {
  verdict: 'supports' | 'partial' | 'insufficient';
  evidenceIds: string[];
  reason: string;
}

export interface ClaimJudge {
  readonly id: string;
  judge(input: JudgeInput): Promise<JudgeVerdict>;
}

const JUDGE_SYSTEM = `Você está verificando SUPORTE DOCUMENTAL, não decidindo a verdade absoluta de uma afirmação.
Regras: use SOMENTE as evidências fornecidas; não invente evidências; não complete lacunas com conhecimento externo; ausência de evidência NÃO é prova de falsidade; diferencie fato de aplicação/criatividade; cite os IDs das evidências que sustentam o veredito.`;

export class LlmClaimJudge implements ClaimJudge {
  readonly id = 'llm-judge';
  private provider: LlmProvider;
  constructor(provider: LlmProvider) {
    this.provider = provider;
  }

  async judge(input: JudgeInput): Promise<JudgeVerdict> {
    const evidenceBlock =
      input.evidence.length > 0
        ? input.evidence.map((e) => `[${e.id} | ${e.ref}] ${e.text.slice(0, 600)}`).join('\n\n')
        : '(nenhuma evidência fornecida)';
    const res = await this.provider.generate({
      text: `${JUDGE_SYSTEM}\n\nAfirmação: "${input.claim.text}" (tipo: ${input.claim.type})\n\nEvidências:\n${evidenceBlock}\n\nResponda SOMENTE com JSON em cerca \`\`\`json: {"verdict": "supports"|"partial"|"insufficient", "evidenceIds": ["id"], "reason": "1 frase"}`,
      action: 'critique',
      responseFormat: 'text',
    });
    const fence = res.text.match(/```json\s*([\s\S]*?)```/i)?.[1]?.trim() ?? res.text.trim();
    let parsed: { verdict?: unknown; evidenceIds?: unknown; reason?: unknown };
    try {
      if (!fence.startsWith('{')) throw new Error('not-json');
      parsed = JSON.parse(fence) as { verdict?: unknown; evidenceIds?: unknown; reason?: unknown };
    } catch {
      throw new ProviderError('invalid_response', 'Juiz retornou veredito inválido.', this.id, 1);
    }
    const verdict = parsed.verdict === 'supports' || parsed.verdict === 'partial' ? parsed.verdict : 'insufficient';
    const evidenceIds = Array.isArray(parsed.evidenceIds)
      ? parsed.evidenceIds.filter((id): id is string => typeof id === 'string')
      : [];
    const reason = typeof parsed.reason === 'string' ? parsed.reason.slice(0, 500) : 'Sem justificativa.';
    return { verdict, evidenceIds, reason };
  }
}

/** Juiz determinístico para testes e modo offline. */
export class StaticClaimJudge implements ClaimJudge {
  readonly id = 'static-judge';
  private verdict: JudgeVerdict;
  constructor(verdict: JudgeVerdict) {
    this.verdict = verdict;
  }
  async judge(): Promise<JudgeVerdict> {
    return this.verdict;
  }
}
