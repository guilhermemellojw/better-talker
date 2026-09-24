// Domínio do Copilot — Fase 1
// Separação: raciocínio (criativo) vs fundamentação (rastreável).
// Preserva compatibilidade com types/speech.ts existentes.

export type SourceType = 'publication' | 'speech_training' | 'bible' | 'user';

export type ClaimStatus =
  | 'SUPPORTED'
  | 'PARTIALLY_SUPPORTED'
  | 'INFERENCE'
  | 'UNVERIFIED'
  | 'CONTRADICTED'
  | 'GENERATED';

export type CopilotMode =
  | 'GENERATIVE'
  | 'GROUNDED'
  | 'VERIFICATION'
  | 'RESEARCH'
  | 'EDITING'
  | 'PLANNING';

export type CopilotTask =
  | 'develop_speech_point'
  | 'hook'
  | 'rewrite'
  | 'critique'
  | 'cues'
  | 'shorten'
  | 'research'
  | 'verify'
  | 'illustration'
  | 'application'
  | 'transition';

export interface Claim {
  id: string;
  text: string;
  status: ClaimStatus;
  sources: string[];
}

export interface EvidenceSource {
  id: string;
  reference: string;
  text: string;
  source_type: SourceType;
  publication?: string;
  section?: string;
  paragraph?: number;
  page?: number;
  language?: string;
  whyRelevant?: string;
  /** Fase 3 (§13): relevância de recuperação em [0,1] — não é certeza factual. */
  score?: number;
  matchedTerms?: string[];
  foundBy?: string[];
  /** Fase 7: categoria de treinamento (só em training_sources). */
  training_category?: TrainingCategory;
}

export interface ContextPack {
  task: CopilotTask;
  mode: CopilotMode;
  speech: {
    title: string;
    objective?: string;
    time_limit_minutes?: number;
  };
  current_section: {
    title?: string;
    text?: string;
    minutes?: number;
  };
  content_sources: EvidenceSource[];
  training_sources: EvidenceSource[];
  user_preferences: {
    language: string;
    tone?: string;
  };
}

/** Fase 5: modos de edição do Copilot (intenção declarada da proposta). */
export type EditProposalMode = 'suggest' | 'rewrite' | 'improve' | 'insert' | 'delete';

/** Fase 5: operações estruturadas por ID de bloco (sem offsets frágeis). */
export type EditOperation =
  | { type: 'insert'; targetId: string; position: 'before' | 'after'; contentHtml: string }
  | { type: 'replace'; targetId: string; contentHtml: string }
  | { type: 'delete'; targetId: string };

/** Proposta validável, atômica e reversível. baseHashes detecta stale. */
export interface CopilotEditProposal {
  id: string;
  mode: EditProposalMode;
  explanation?: string;
  operations: EditOperation[];
  baseHashes: Record<string, string>;
  createdAt: number;
}

export type ProposalApplyStatus = 'applied' | 'stale_proposal' | 'invalid';

export type ProposalValidationError =
  | 'unknown_target'
  | 'invalid_position'
  | 'empty_content'
  | 'last_block'
  | 'stale_proposal';

/** Fase 7: taxonomia de conhecimento de treinamento (BE/TH = COMO dizer). */
export type TrainingCategory =
  | 'introduction'
  | 'development'
  | 'explanation'
  | 'illustration'
  | 'application'
  | 'transition'
  | 'conclusion'
  | 'questions'
  | 'clarity'
  | 'naturalness'
  | 'delivery'
  | 'unknown';

/** Fase 6: tipo da claim — nem toda frase é afirmação factual. */
export type ClaimType =
  | 'factual'
  | 'biblical'
  | 'interpretive'
  | 'application'
  | 'creative'
  | 'rhetorical'
  | 'training';

export interface ExtractedClaim {
  id: string;
  text: string;
  type: ClaimType;
  blockId?: string;
  /** Offsets no texto original, quando conhecidos. */
  start?: number;
  end?: number;
}

/** Fase 6: suporte documental — relevância avaliada, nunca verdade absoluta. */
export type SupportStatus = 'supported' | 'partially_supported' | 'insufficient' | 'creative';

export type EvidenceSupport = 'supports' | 'partial' | 'contradicts';

export interface ClaimEvidenceProvenance {
  passageId: string;
  pubId: string;
  sourceType?: string;
  publication?: string;
  title?: string;
  section?: string;
  symbol?: string;
  page?: number;
  paragraph?: number;
  ref?: string;
}

export interface ClaimEvidence {
  claimId: string;
  evidenceId: string;
  support: EvidenceSupport;
  score?: number;
  reason?: string;
  /** Trecho exibido/encaminhado ao juiz — cópia do passage, não metadado. */
  snippet?: string;
  provenance: ClaimEvidenceProvenance;
}

export interface VerifiedClaim {
  claim: ExtractedClaim;
  status: SupportStatus;
  evidence: ClaimEvidence[];
  reason: string;
}

export interface VerificationSummary {
  supported: number;
  partial: number;
  insufficient: number;
  creative: number;
}

export interface TextVerification {
  claims: VerifiedClaim[];
  summary: VerificationSummary;
  /** true = só verificação local (sem avaliador remoto). */
  limited: boolean;
  /** Chave p/ stale: bloco + conteúdo + escopo. */
  key: string;
}

export function emptyContextPack(task: CopilotTask = 'research'): ContextPack {
  return {
    task,
    mode: 'GROUNDED',
    speech: { title: '' },
    current_section: {},
    content_sources: [],
    training_sources: [],
    user_preferences: { language: 'pt-BR' },
  };
}
