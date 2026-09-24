// QwenProvider — stub Fase 1. Interface pronta, sem implementação remota/local ainda.
// Decisão atual: manter Gemini; Qwen entra na Fase 4 sem alterar domínio/UI.

import type { LlmProvider, LlmQueryOptions } from './llmProvider';

export class QwenProvider implements LlmProvider {
  readonly id = 'qwen';
  private endpoint?: string;
  private apiKey?: string;
  constructor(endpoint?: string, apiKey?: string) {
    this.endpoint = endpoint;
    this.apiKey = apiKey;
  }

  async query(_options: LlmQueryOptions): Promise<string> {
    void this.endpoint;
    void this.apiKey;
    throw new Error(
      'QwenProvider ainda não configurado (Fase 4). Use GeminiProvider ou motor offline.',
    );
  }
}
