// QwenProvider — Fase 4 (§§6,7): implementação real sobre endpoint remoto
// compatível com Chat Completions (formato OpenAI). SEM endpoint configurado,
// generate() lança `unavailable` — nunca finge suporte (§7).
// Execução local no web não existe (on-device é papel do app nativo).
//
// F20-E: o MESMO provider atende o transporte Groq (OpenAI-compatible) com
// structured output nativo (json_schema estrito), `reasoning_effort` e
// métricas de tokens/rate-limit. Nenhum provider novo foi criado.

import { buildLlmPrompt, SYSTEM_PROMPT } from './llmPrompt';
import { buildOratoryPrompt } from './oratoryGeneration';
import { postJsonWithRetry } from './llmHttp';
import { ProviderError } from './llmErrors';
import {
  DEFAULT_LLM_MAX_ATTEMPTS,
  DEFAULT_LLM_MAX_OUTPUT_TOKENS,
  DEFAULT_LLM_TIMEOUT_MS,
  type LlmProvider,
  type LlmRequest,
  type LlmResponse,
  type LlmResponseMeta,
} from './llmProvider';

export type ReasoningEffort = 'low' | 'medium' | 'high';
export type StructuredOutputMode = 'json_schema' | 'json_object' | 'off';

export interface QwenConfig {
  /** Base do endpoint, ex: https://dashscope.../compatible-mode/v1 */
  endpoint?: string;
  model?: string;
  apiKey?: string;
  timeoutMs?: number;
  maxAttempts?: number;
  /** F20-E: esforço de raciocínio (Groq/Qwen). Baixo por padrão no app. */
  reasoningEffort?: ReasoningEffort;
  /** F20-E: structured output do Groq (json_schema estrito é o preferido). */
  structuredOutput?: StructuredOutputMode;
  /**
   * F20-E: onde o raciocínio do Qwen3 aparece (Groq). 'parsed' mantém o
   * conteúdo limpo para o parser; omitido usa o default do endpoint.
   */
  reasoningFormat?: 'parsed' | 'raw' | 'hidden';
}

export const DEFAULT_QWEN_MODEL = 'qwen-plus';

/**
 * Schema estrito da proposta de edição (§9). Reflete o contrato aceito por
 * `parseEditProposal`: insert/replace + conteúdo HTML.
 */
export const EDIT_PROPOSAL_JSON_SCHEMA = {
  name: 'edit_proposal',
  strict: true,
  schema: {
    type: 'object',
    properties: {
      explanation: { type: 'string' },
      operations: {
        type: 'array',
        minItems: 1,
        items: {
          type: 'object',
          properties: {
            type: { type: 'string', enum: ['insert', 'replace'] },
            position: { type: 'string', enum: ['before', 'after'] },
            content: { type: 'string' },
          },
          required: ['type', 'position', 'content'],
          additionalProperties: false,
        },
      },
    },
    required: ['explanation', 'operations'],
    additionalProperties: false,
  },
} as const;

interface ChatCompletionsResponse {
  choices?: Array<{ message?: { content?: string } }>;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
}

export class QwenProvider implements LlmProvider {
  readonly id = 'qwen';
  readonly model: string;
  private endpoint?: string;
  private apiKey?: string;
  private timeoutMs: number;
  private maxAttempts: number;
  private reasoningEffort?: ReasoningEffort;
  private structuredOutput: StructuredOutputMode;
  private reasoningFormat?: 'parsed' | 'raw' | 'hidden';

  constructor(config: QwenConfig = {}) {
    this.endpoint = config.endpoint?.replace(/\/+$/, '');
    this.model = config.model ?? DEFAULT_QWEN_MODEL;
    this.apiKey = config.apiKey;
    this.timeoutMs = config.timeoutMs ?? DEFAULT_LLM_TIMEOUT_MS;
    this.maxAttempts = config.maxAttempts ?? DEFAULT_LLM_MAX_ATTEMPTS;
    this.reasoningEffort = config.reasoningEffort;
    this.structuredOutput = config.structuredOutput ?? 'json_schema';
    this.reasoningFormat = config.reasoningFormat;
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
    // F20-B/F20-E: pedido oratório usa o prompt especializado (mesmo contrato
    // do GeminiProvider) — o transporte não altera a inteligência do prompt.
    const prompt = request.oratorySpec
      ? {
          system: SYSTEM_PROMPT,
          user: buildOratoryPrompt(request.oratorySpec, request.chat?.message ?? request.brief ?? ''),
        }
      : buildLlmPrompt(request);
    const headers: Record<string, string> = {};
    if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;

    const body: Record<string, unknown> = {
      model: this.model,
      messages: [
        { role: 'system', content: prompt.system },
        { role: 'user', content: prompt.user },
      ],
      temperature: 0.2,
      max_tokens: request.maxOutputTokens ?? DEFAULT_LLM_MAX_OUTPUT_TOKENS,
    };
    if (this.reasoningEffort) body.reasoning_effort = this.reasoningEffort;
    if (this.reasoningFormat) body.reasoning_format = this.reasoningFormat;
    // Structured output só no caminho de proposta: chat/consulta continua texto.
    if (request.responseFormat === 'edit-proposal' && this.structuredOutput !== 'off') {
      body.response_format =
        this.structuredOutput === 'json_schema'
          ? { type: 'json_schema', json_schema: EDIT_PROPOSAL_JSON_SCHEMA }
          : { type: 'json_object' };
    }

    const { data, attempts, rateLimit } = await postJsonWithRetry(
      `${this.endpoint}/chat/completions`,
      body,
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
      meta: withUsage(
        { providerId: this.id, model: this.model, durationMs: Date.now() - started, attempts, offline: false },
        (data as ChatCompletionsResponse)?.usage,
        rateLimit,
      ),
    };
  }
}

/** F20-E §37: tokens e rate-limit observáveis, quando o endpoint informar. */
function withUsage(
  meta: LlmResponseMeta,
  usage: ChatCompletionsResponse['usage'],
  rateLimit?: Record<string, string>,
): LlmResponseMeta {
  const out: LlmResponseMeta = { ...meta };
  if (usage && typeof usage.prompt_tokens === 'number') {
    out.usage = {
      inputTokens: usage.prompt_tokens,
      outputTokens: usage.completion_tokens ?? 0,
      totalTokens: usage.total_tokens ?? 0,
    };
  }
  if (rateLimit && Object.keys(rateLimit).length > 0) out.rateLimit = rateLimit;
  return out;
}
