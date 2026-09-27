// Abstração de LLM — o app conversa com LlmProvider, nunca direto com Gemini/Qwen.
// Fase 4: contrato generate() com resposta estruturada + metadados observáveis.
// Permite trocar Qwen local/remoto, Gemini, ou fakes sem alterar domínio/UI.

import type { ContextPack } from './domain';
import type { ChatBrief } from './chatEngine';

export type LlmAction = 'hook' | 'rewrite' | 'critique' | 'cues' | 'shorten';
/** Fase 15: chat livre — roteado internamente por intent; UI nunca pede action. */
export type ChatLlmAction = LlmAction | 'chat';

export type LlmTone = 'ted' | 'pitch' | 'motivational' | 'academic' | 'humorous';

export interface LlmRequest {
  text: string;
  action: ChatLlmAction;
  /**
   * Fase 15: contexto de conversa (histórico limitado + continuidade) do chat
   * livre. Só relevante com action 'chat'; providers apenas o encaminham ao
   * prompt builder compartilhado.
   */
  chat?: ChatBrief;
  tone?: LlmTone;
  contextPack?: ContextPack;
  /** Legado: lista plana de trechos. Preferir contextPack. */
  contextPassages?: string[];
  blockTitle?: string;
  blockMinutes?: number;
  /**
   * F19-B.5: estrutura do S-34 (quando o discurso tem esboço persistido).
   * Entra como bloco próprio no prompt, antes das fontes.
   */
  structural?: import('./s34StructuralContext').S34StructureContext | null;
  /**
   * F20-A: estrutura oratória derivada do S-34 (opcional). Planejamento
   * estrutural — nunca texto oratório gerado.
   */
  oratory?: import('./oratoryStructure').InferredOratoryStructure | null;
  /**
   * F20-B: especificação de geração oratória. Quando presente, o provider usa
   * o prompt especializado do modo em vez do prompt genérico de edição.
   */
  oratorySpec?: import('./oratoryGeneration').OratorySpec | null;
  /** Cancelamento iniciado pela UI (AbortController). */
  signal?: AbortSignal;
  timeoutMs?: number;
  maxAttempts?: number;
  /**
   * Fase 5: 'edit-proposal' pede ao modelo JSON em cerca para virar
   * CopilotEditProposal (parse + validação locais). Default 'text'.
   */
  responseFormat?: 'text' | 'edit-proposal';
  /** Fase 5: intenção de edição (só com responseFormat edit-proposal). */
  editMode?: 'rewrite' | 'improve' | 'insert';
  /** Fase 9: foco vindo de uma observação estrutural (ex: transição fraca). */
  brief?: string;
}

export interface LlmResponseMeta {
  providerId: string;
  model: string;
  durationMs: number;
  attempts: number;
  /** true = motor offline determinístico (sem chave ou sem rede remota). */
  offline: boolean;
}

export interface LlmResponse {
  text: string;
  meta: LlmResponseMeta;
}

export interface LlmProvider {
  readonly id: string;
  readonly model: string;
  generate(request: LlmRequest): Promise<LlmResponse>;
}

/** Padrões quando o chamador não especifica (§15: centralizados, não espalhados). */
export const DEFAULT_LLM_TIMEOUT_MS = 30_000;
export const DEFAULT_LLM_MAX_ATTEMPTS = 2;
