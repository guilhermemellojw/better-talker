// FakeLlmProvider — Fase 4 (§18): provider determinístico para testes.
// Respostas fixas por ação; falha injetável via constructor.

import { ProviderError } from './llmErrors';
import type { LlmAction, LlmProvider, LlmRequest, LlmResponse } from './llmProvider';

export class FakeLlmProvider implements LlmProvider {
  readonly id = 'fake';
  readonly model = 'fake-1';
  private script: Partial<Record<LlmAction, string>>;
  private failWith?: ProviderError;

  constructor(script: Partial<Record<LlmAction, string>> = {}, failWith?: ProviderError) {
    this.script = script;
    this.failWith = failWith;
  }

  async generate(request: LlmRequest): Promise<LlmResponse> {
    if (this.failWith) throw this.failWith;
    const text =
      this.script[request.action] ??
      `[fake:${request.action}] ${request.text.slice(0, 60)}`;
    return {
      text,
      meta: { providerId: this.id, model: this.model, durationMs: 0, attempts: 1, offline: true },
    };
  }
}
