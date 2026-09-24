// QwenProvider — Fase 4 (§§6,7): implementação real sobre endpoint remoto
// compatível com Chat Completions (formato OpenAI). SEM endpoint configurado,
// generate() lança `unavailable` — nunca finge suporte (§7).
// Execução local no web não existe (on-device é papel do app nativo).

import { buildLlmPrompt } from './llmPrompt';
import { postJsonWithRetry } from './llmHttp';
import { ProviderError } from './llmErrors';
import {
  DEFAULT_LLM_MAX_ATTEMPTS,
  DEFAULT_LLM_TIMEOUT_MS,
  type LlmProvider,
  type LlmRequest,
  type LlmResponse,
} from './llmProvider';

export interface QwenConfig {
  /** Base do endpoint, ex: https://dashscope.../compatible-mode/v1 */
  endpoint?: string;
  model?: string;
  apiKey?: string;
  timeoutMs?: number;
  maxAttempts?: number;
}

export const DEFAULT_QWEN_MODEL = 'qwen-plus';

interface ChatCompletionsResponse {
  choices?: Array<{ message?: { content?: string } }>;
}

export class QwenProvider implements LlmProvider {
  readonly id = 'qwen';
  readonly model: string;
  private endpoint?: string;
  private apiKey?: string;
  private timeoutMs: number;
  private maxAttempts: number;

  constructor(config: QwenConfig = {}) {
    this.endpoint = config.endpoint?.replace(/\/+$/, '');
    this.model = config.model ?? DEFAULT_QWEN_MODEL;
    this.apiKey = config.apiKey;
    this.timeoutMs = config.timeoutMs ?? DEFAULT_LLM_TIMEOUT_MS;
    this.maxAttempts = config.maxAttempts ?? DEFAULT_LLM_MAX_ATTEMPTS;
  }

  /** false = provider preparado mas inoperante (endpoint ausente). */
  isConfigured(): boolean {
    return !!this.endpoint;
  }

  async generate(request: LlmRequest): Promise<LlmResponse> {
    const started = Date.now();
    if (!this.endpoint) {
      throw new ProviderError(
        'unavailable',
        'Qwen não configurado: defina o endpoint remoto (VITE_QWEN_ENDPOINT).',
        this.id,
        0,
      );
    }
    const prompt = buildLlmPrompt(request);
    const headers: Record<string, string> = {};
    if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;

    const { data, attempts } = await postJsonWithRetry(
      `${this.endpoint}/chat/completions`,
      {
        model: this.model,
        messages: [
          { role: 'system', content: prompt.system },
          { role: 'user', content: prompt.user },
        ],
        temperature: 0.2,
        max_tokens: 1000,
      },
      {
        providerId: this.id,
        model: this.model,
        timeoutMs: request.timeoutMs ?? this.timeoutMs,
        maxAttempts: request.maxAttempts ?? this.maxAttempts,
        signal: request.signal,
        headers,
      },
    );

    const content = (data as ChatCompletionsResponse)?.choices?.[0]?.message?.content;
    if (!content || typeof content !== 'string') {
      throw new ProviderError('invalid_response', 'Resposta do Qwen em formato inesperado.', this.id, attempts);
    }
    return {
      text: content,
      meta: { providerId: this.id, model: this.model, durationMs: Date.now() - started, attempts, offline: false },
    };
  }
}
