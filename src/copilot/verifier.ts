// Verificador de fidelidade — Fase 6: suporte documental conservador.
// Local-first: avaliação lexical/metadata sobre o escopo autorizado.
// Nunca marca supported por opinião do modelo; dúvida => partial/insufficient.
// Juiz LLM é segunda etapa OPCIONAL e separada (verificationJudge.ts).

import { detectCitations } from '../services/citationDetector';
import { extractClaims, extractNumbers } from './claimExtractor';
import { hashText } from './editProposal';
import { HybridRetriever } from './hybridRetriever';
import { uniqueTokens, tokenize, normalizeTokenText } from './tokenize';
import type {
  ClaimEvidence,
  ExtractedClaim,
  SupportStatus,
  TextVerification,
  VerifiedClaim,
} from './domain';
import type { PassageStore, RetrievalCandidate, RetrievalScope } from './retrievalTypes';
import type { ClaimJudge } from './verificationJudge';

export interface VerifyTextInput {
  text: string;
  blockId?: string;
  scope: RetrievalScope;
  store: PassageStore;
  limitPerClaim?: number;
  /** Ignora o cache (padrão usa). */
  force?: boolean;
}

const SUPPORT_LEXICAL_MIN = 0.45;
const SUPPORT_COVERAGE_MIN = 0.5;

function scopeIds(scope: RetrievalScope): string[] {
  return [...scope.contentSourceIds, ...scope.trainingSourceIds].sort();
}

/** Chave p/ stale+cache (§§28,29): bloco + conteúdo + escopo. */
export function verificationKeyFor(blockId: string | undefined, contentHtml: string, scope: RetrievalScope): string {
  return hashText(`${blockId ?? ''}|${contentHtml}|${scopeIds(scope).join(',')}`);
}

const verifyCache = new Map<string, TextVerification>();
const CACHE_CAP = 50;

function cacheGet(key: string): TextVerification | null {
  return verifyCache.get(key) ?? null;
}

function cachePut(key: string, value: TextVerification): void {
  verifyCache.set(key, value);
  if (verifyCache.size > CACHE_CAP) {
    const oldest = verifyCache.keys().next();
    if (!oldest.done) verifyCache.delete(oldest.value);
  }
}

export function clearVerificationCache(): void {
  verifyCache.clear();
}

function toEvidence(claimId: string, hit: RetrievalCandidate, support: ClaimEvidence['support'], reason?: string): ClaimEvidence {
  const p = hit.passage;
  return {
    claimId,
    evidenceId: p.id,
    support,
    score: Math.round(hit.finalScore * 100) / 100,
    reason,
    snippet: p.text.slice(0, 600),
    provenance: {
      passageId: p.id,
      pubId: p.pubId,
      sourceType: p.source_type,
      publication: p.symbol || p.pubId,
      title: hit.publicationTitle,
      section: p.section,
      symbol: p.symbol,
      page: p.page,
      paragraph: p.paragraph,
      ref: p.ref || undefined,
    },
  };
}

function claimTokens(claim: ExtractedClaim): string[] {
  return uniqueTokens(tokenize(claim.text));
}

interface Assessment {
  status: SupportStatus;
  evidence: ClaimEvidence[];
  reason: string;
}

function numbersOk(claimText: string, hits: RetrievalCandidate[]): { ok: boolean; missing: string[] } {
  // Dígitos dentro de referências (ex: "w24.01") não são dados a conferir (§15×§16).
  let scrubbed = claimText;
  try {
    for (const ref of detectCitations(claimText, 'verify')) {
      scrubbed = scrubbed.split(ref.raw).join(' ');
    }
  } catch {
    // detector indisponível: usa o texto integral.
  }
  const numbers = extractNumbers(scrubbed);
  if (numbers.length === 0) return { ok: true, missing: [] };
  const missing = numbers.filter((n) => {
    const needle = n.replace(/%/g, '');
    return !hits.some((h) => {
      const hay = `${h.passage.normalizedText} ${normalizeTokenText(h.passage.ref || '')}`;
      return hay.includes(needle) || (n.includes('%') && hay.includes(needle));
    });
  });
  return { ok: missing.length === 0, missing };
}

function refsOk(claimText: string, hits: RetrievalCandidate[]): { ok: boolean; reason?: string } {
  let refs: Array<{ raw: string }>;
  try {
    refs = detectCitations(claimText, 'verify');
  } catch {
    return { ok: true };
  }
  if (refs.length === 0) return { ok: true };
  for (const ref of refs) {
    const normRef = normalizeTokenText(ref.raw);
    const found = hits.some((h) => {
      const hay = normalizeTokenText(
        `${h.passage.ref} ${h.passage.symbol ?? ''} ${h.publicationTitle ?? ''} ${h.passage.section ?? ''}`,
      );
      const tokens = uniqueTokens(tokenize(normRef)).filter((t) => t.length >= 2);
      return tokens.length > 0 && tokens.some((t) => hay.includes(t));
    });
    if (!found) {
      return { ok: false, reason: `Referência "${ref.raw}" reconhecida, mas sem correspondência nas fontes autorizadas.` };
    }
  }
  return { ok: true };
}

function assessClaim(claim: ExtractedClaim, hits: RetrievalCandidate[]): Assessment {
  if (hits.length === 0) {
    return {
      status: 'insufficient',
      evidence: [],
      reason: 'Nenhuma evidência encontrada nas fontes autorizadas para esta afirmação.',
    };
  }
  const best = hits[0];
  const tokens = claimTokens(claim);
  const coverage = tokens.length > 0 ? best.matchedTerms.length / tokens.length : 0;
  const numbers = numbersOk(claim.text, hits.slice(0, 3));
  const refs = refsOk(claim.text, hits.slice(0, 3));

  if (!refs.ok) {
    return { status: 'insufficient', evidence: [], reason: refs.reason ?? 'Referência sem suporte.' };
  }
  if (!numbers.ok) {
    return {
      status: 'partially_supported',
      evidence: hits.slice(0, 2).map((h) => toEvidence(claim.id, h, 'partial', 'Evidência relacionada, mas sem o dado específico.')),
      reason: `A fonte sustenta parte da afirmação, mas não confirma: ${numbers.missing.join(', ')}.`,
    };
  }
  const strong = best.lexicalScore >= SUPPORT_LEXICAL_MIN && coverage >= SUPPORT_COVERAGE_MIN;
  if (strong) {
    return {
      status: 'supported',
      evidence: hits.slice(0, 2).map((h) => toEvidence(claim.id, h, 'supports')),
      reason: `Sustentado por: ${best.passage.ref || best.publicationTitle || best.passage.pubId}.`,
    };
  }
  return {
    status: 'partially_supported',
    evidence: hits.slice(0, 2).map((h) => toEvidence(claim.id, h, 'partial', 'Correspondência parcial de termos.')),
    reason: 'Há correspondência parcial, mas insuficiente para suporte integral.',
  };
}

function creativeNote(kind: string): Assessment {
  return {
    status: 'creative',
    evidence: [],
    reason: `${kind} — não exige comprovação factual.`,
  };
}

async function assessOne(
  claim: ExtractedClaim,
  scope: RetrievalScope,
  store: PassageStore,
  limitPerClaim: number,
): Promise<VerifiedClaim> {
  // Tipos não-factuais nunca passam por retrieval (§§17,18).
  if (claim.type === 'creative') return { claim, ...creativeNote('Construção criativa/retórica') };
  if (claim.type === 'rhetorical') return { claim, ...creativeNote('Pergunta retórica') };
  if (claim.type === 'application') return { claim, ...creativeNote('Aplicação pessoal') };

  const retriever = new HybridRetriever(store);
  const track = claim.type === 'training' ? 'training' : 'content';
  const res = await retriever.retrieve(claim.text, scope, { track, limit: limitPerClaim });
  if (claim.type === 'training') {
    // Training orienta técnica: melhor hit = suporte metodológico (cap parcial? não —
    // suporte a técnica pode ser integral; fatos continuam fora do trilho).
    if (res.hits.length === 0) {
      return { claim, status: 'insufficient', evidence: [], reason: 'Sem orientação correspondente em BE/TH.' };
    }
    const best = res.hits[0];
    const strong = best.lexicalScore >= SUPPORT_LEXICAL_MIN;
    return {
      claim,
      status: strong ? 'supported' : 'partially_supported',
      evidence: res.hits.slice(0, 2).map((h) =>
        toEvidence(claim.id, h, strong ? 'supports' : 'partial', strong ? undefined : 'Correspondência parcial em BE/TH.'),
      ),
      reason: strong
        ? `Orientação encontrada em: ${best.passage.ref || best.publicationTitle || best.passage.pubId}.`
        : 'Orientação parcialmente correspondente em BE/TH.',
    };
  }
  let assessment = assessClaim(claim, res.hits);
  // §14: interpretive é derivada — teto parcial, nunca supported direto.
  if (claim.type === 'interpretive' && assessment.status === 'supported') {
    assessment = {
      status: 'partially_supported',
      evidence: assessment.evidence.map((e) => ({ ...e, support: 'partial' as const })),
      reason: `${assessment.reason} (Interpretação: teto parcial por regra conservadora.)`,
    };
  }
  return { claim, ...assessment };
}

export async function verifyText(input: VerifyTextInput): Promise<TextVerification> {
  const key = verificationKeyFor(input.blockId, input.text, input.scope);
  if (!input.force) {
    const cached = cacheGet(key);
    if (cached) return cached;
  }
  const claims = extractClaims(input.text, input.blockId);
  const assessed: VerifiedClaim[] = [];
  for (const claim of claims) {
    assessed.push(await assessOne(claim, input.scope, input.store, input.limitPerClaim ?? 3));
  }
  const summary = {
    supported: assessed.filter((c) => c.status === 'supported').length,
    partial: assessed.filter((c) => c.status === 'partially_supported').length,
    insufficient: assessed.filter((c) => c.status === 'insufficient').length,
    creative: assessed.filter((c) => c.status === 'creative').length,
  };
  // Local-only por padrão: sem avaliador remoto (§24).
  const result: TextVerification = { claims: assessed, summary, limited: true, key };
  cachePut(key, result);
  return result;
}

/**
 * Segunda etapa opcional com juiz LLM (contrato separado, §20).
 * Conservador: o juiz só pode qualificar com evidência citada da
 * lista fornecida; nunca cria suporte do nada. Falha do juiz => mantém local.
 */
export async function verifyTextWithJudge(
  input: VerifyTextInput,
  judge: ClaimJudge,
): Promise<TextVerification> {
  const base = await verifyText(input);
  const refined: VerifiedClaim[] = [];
  for (const vc of base.claims) {
    if (
      (vc.status === 'partially_supported' || vc.status === 'insufficient') &&
      (vc.claim.type === 'factual' || vc.claim.type === 'biblical' || vc.claim.type === 'interpretive')
    ) {
      try {
        const verdict = await judge.judge({
          claim: vc.claim,
          evidence: vc.evidence.map((e) => ({
            id: e.evidenceId,
            ref: e.provenance.ref ?? e.provenance.title ?? e.evidenceId,
            text: e.snippet ?? '',
          })),
        });
        const citedValid = verdict.evidenceIds.every((id) => vc.evidence.some((e) => e.evidenceId === id));
        if (!citedValid || verdict.evidenceIds.length === 0) {
          refined.push(vc);
          continue;
        }
        if (verdict.verdict === 'supports' && vc.status === 'partially_supported') {
          refined.push({
            ...vc,
            status: 'supported',
            reason: `${vc.reason} (Juiz: ${verdict.reason})`,
          });
        } else if (verdict.verdict === 'insufficient') {
          refined.push({ ...vc, status: 'insufficient', reason: `Juiz: ${verdict.reason}` });
        } else {
          refined.push(vc);
        }
      } catch {
        refined.push(vc);
      }
    } else {
      refined.push(vc);
    }
  }
  const summary = {
    supported: refined.filter((c) => c.status === 'supported').length,
    partial: refined.filter((c) => c.status === 'partially_supported').length,
    insufficient: refined.filter((c) => c.status === 'insufficient').length,
    creative: refined.filter((c) => c.status === 'creative').length,
  };
  return { claims: refined, summary, limited: false, key: base.key };
}
