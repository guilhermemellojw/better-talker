import { GeminiProvider } from '../copilot/geminiProvider';
import type { LlmAction, LlmTone } from '../copilot/llmProvider';

export interface AICallOptions {
  apiKey?: string;
  tone?: LlmTone;
  text: string;
  action: LlmAction;
  contextPassages?: string[];
  blockTitle?: string;
  blockMinutes?: number;
}

/**
 * Compat: mantém a assinatura legada retornando texto.
 * Erros remotos agora propagam ProviderError (sem fallback silencioso).
 */
export async function queryGeminiOratoryCoach(options: AICallOptions): Promise<string> {
  const { apiKey, text, action, tone = 'ted', contextPassages = [], blockTitle, blockMinutes } = options;
  const provider = new GeminiProvider(apiKey ?? '');
  const res = await provider.generate({ text, action, tone, contextPassages, blockTitle, blockMinutes });
  return res.text;
}

export { GeminiProvider };
