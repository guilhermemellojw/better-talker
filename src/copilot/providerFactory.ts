// Seleção de provider — Fase 4 (§§3,15,19).
// A UI pede um LlmProvider à factory; nunca instancia Gemini/Qwen direto.
// Política explícita: UM provider por chamada, sem fallback silencioso
// entre modelos. Seleção via config (testável) com defaults do ambiente.

import { GeminiProvider } from './geminiProvider';
import { QwenProvider } from './qwenProvider';
import type { LlmProvider } from './llmProvider';

export type CopilotProviderId = 'gemini' | 'qwen';

export interface CopilotProviderConfig {
  /** Chave Gemini (mesma das Configurações do app). */
  apiKey?: string;
  /** Força o provider; default 'gemini' (comportamento atual preservado). */
  provider?: CopilotProviderId;
  qwenEndpoint?: string;
  qwenModel?: string;
  qwenApiKey?: string;
  /** F20-E: esforço de raciocínio do transporte Groq/Qwen. */
  qwenReasoningEffort?: 'low' | 'medium' | 'high';
  /** F20-E: formato do raciocínio do Qwen3 no Groq. */
  qwenReasoningFormat?: 'parsed' | 'raw' | 'hidden';
}

function readEnv(name: string): string | undefined {
  try {
    const value = (import.meta as unknown as { env?: Record<string, string> }).env?.[name];
    return value && value.trim() ? value.trim() : undefined;
  } catch {
    return undefined;
  }
}

/** Defaults de ambiente (VITE_*); sem segredos no código (§15). */
export function providerConfigFromEnv(apiKey?: string): CopilotProviderConfig {
  const provider = readEnv('VITE_LLM_PROVIDER');
  return {
    apiKey,
    provider: provider === 'qwen' ? 'qwen' : 'gemini',
    qwenEndpoint: readEnv('VITE_QWEN_ENDPOINT'),
    qwenModel: readEnv('VITE_QWEN_MODEL'),
    qwenApiKey: readEnv('VITE_QWEN_API_KEY'),
    qwenReasoningEffort: readEnv('VITE_QWEN_REASONING_EFFORT') as
      | 'low'
      | 'medium'
      | 'high'
      | undefined,
    qwenReasoningFormat: readEnv('VITE_QWEN_REASONING_FORMAT') as
      | 'parsed'
      | 'raw'
      | 'hidden'
      | undefined,
  };
}

export function createCopilotProvider(config: CopilotProviderConfig): LlmProvider {
  if (config.provider === 'qwen') {
    return new QwenProvider({
      endpoint: config.qwenEndpoint,
      model: config.qwenModel,
      apiKey: config.qwenApiKey,
      // F20-E §10: começar em low controla tokens no plano gratuito.
      reasoningEffort: config.qwenReasoningEffort ?? 'low',
      reasoningFormat: config.qwenReasoningFormat,
    });
  }
  return new GeminiProvider(config.apiKey ?? '');
}

/** Atalho usado pela UI: chave do app + ambiente. */
export function createCopilotProviderFromEnv(apiKey?: string): LlmProvider {
  return createCopilotProvider(providerConfigFromEnv(apiKey));
}
