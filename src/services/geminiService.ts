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

export async function queryGeminiOratoryCoach(options: AICallOptions): Promise<string> {
  const { apiKey, text, action, tone = 'ted', contextPassages = [], blockTitle, blockMinutes } = options;
  const provider = new GeminiProvider(apiKey ?? '');
  return provider.query({ text, action, tone, contextPassages, blockTitle, blockMinutes });
}

export { GeminiProvider };
