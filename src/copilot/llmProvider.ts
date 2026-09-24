// Abstração de LLM — o app conversa com LlmProvider, nunca direto com Gemini/Qwen.
// Permite trocar Qwen local/remoto, Gemini, ou mocks sem alterar domínio/UI.

import type { ContextPack } from './domain';

export type LlmAction = 'hook' | 'rewrite' | 'critique' | 'cues' | 'shorten';

export type LlmTone = 'ted' | 'pitch' | 'motivational' | 'academic' | 'humorous';

export interface LlmQueryOptions {
  text: string;
  action: LlmAction;
  tone?: LlmTone;
  contextPack?: ContextPack;
  /** Legado: lista plana de trechos. Preferir contextPack. */
  contextPassages?: string[];
  blockTitle?: string;
  blockMinutes?: number;
}

export interface LlmProvider {
  readonly id: string;
  /** Retorna markdown/texto para exibição. Nunca deve lançar de forma a corromper o doc — UI trata erro. */
  query(options: LlmQueryOptions): Promise<string>;
}
