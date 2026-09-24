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

export type EditOperationType =
  | 'insert_text'
  | 'replace_text'
  | 'delete_text'
  | 'insert_section'
  | 'move_section'
  | 'add_claim'
  | 'add_citation';

export interface EditOperation {
  operation: EditOperationType;
  block_id: string;
  start?: number;
  end?: number;
  replacement?: string;
  payload?: unknown;
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
