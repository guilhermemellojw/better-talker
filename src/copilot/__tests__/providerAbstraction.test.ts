// Abstração, factory, fake, ContextPack e segurança de conteúdo — Fase 4 (§17).

import { afterEach, describe, expect, it, vi } from 'vitest';
import { FakeLlmProvider } from '../fakeProvider';
import { GeminiProvider } from '../geminiProvider';
import { QwenProvider } from '../qwenProvider';
import { createCopilotProvider, providerConfigFromEnv } from '../providerFactory';
import { ProviderError } from '../llmErrors';
import { buildLlmPrompt, INSUFFICIENT_EVIDENCE_MESSAGE } from '../llmPrompt';
import type { LlmProvider } from '../llmProvider';

afterEach(() => {
  vi.unstubAllGlobals();
});

function geminiOk(text: string): Response {
  return new Response(
    JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }),
    { status: 200 },
  );
}

function chatOk(content: string): Response {
  return new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });
}

describe('provider abstraction', () => {
  it('factory seleciona gemini por padrão e qwen quando forçado', () => {
    expect(createCopilotProvider({ apiKey: 'k' })).toBeInstanceOf(GeminiProvider);
    expect(createCopilotProvider({ provider: 'gemini', apiKey: 'k' })).toBeInstanceOf(GeminiProvider);
    const qwen = createCopilotProvider({ provider: 'qwen', qwenEndpoint: 'https://x/v1' });
    expect(qwen).toBeInstanceOf(QwenProvider);
    expect(qwen.id).toBe('qwen');
  });

  it('Gemini e Qwen atendem ao mesmo contrato sem lógica condicional no chamador', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('generativelanguage')) return geminiOk('via gemini');
      return chatOk('via qwen');
    }));
    const providers: LlmProvider[] = [
      new GeminiProvider('k'),
      new QwenProvider({ endpoint: 'https://qwen.example/v1' }),
    ];
    const req = { text: 'bloco de oratória', action: 'hook' as const };
    const texts: string[] = [];
    for (const p of providers) {
      const res = await p.generate(req);
      texts.push(res.text);
      expect(res.meta.providerId).toBe(p.id);
    }
    expect(texts).toEqual(['via gemini', 'via qwen']);
  });

  it('FakeLlmProvider é determinístico e injeta falha controlada', async () => {
    const fake = new FakeLlmProvider({ hook: 'gancho fixo' });
    const a = await fake.generate({ text: 'x', action: 'hook' });
    const b = await fake.generate({ text: 'y', action: 'hook' });
    expect(a.text).toBe('gancho fixo');
    expect(b.text).toBe('gancho fixo');
    const failing = new FakeLlmProvider({}, new ProviderError('timeout', 't', 'fake', 1));
    await expect(failing.generate({ text: 'x', action: 'hook' })).rejects.toBeInstanceOf(ProviderError);
  });

  it('providerConfigFromEnv não contém segredos, só lê ambiente', () => {
    const cfg = providerConfigFromEnv('k');
    expect(cfg.provider).toBe('gemini');
    expect(JSON.stringify(cfg)).not.toContain('sk-');
    expect(cfg.apiKey).toBe('k');
  });
});

describe('context pack e segurança', () => {
  it('sistema carrega a frase de insuficiência e proíbe preencher lacunas', () => {
    const { system } = buildLlmPrompt({ text: 'x', action: 'critique' });
    expect(system).toContain(INSUFFICIENT_EVIDENCE_MESSAGE);
    expect(system).toMatch(/não preencha lacunas|Não preencha lacunas/i);
    expect(system).toMatch(/nunca.*fonte de fatos|NÃO usar como fatos/i);
  });

  it('ausência de evidência não vira permissão: prompt registra o vazio', () => {
    const { user } = buildLlmPrompt({ text: 'O que diz a publicação X?', action: 'critique' });
    expect(user).toContain('Nenhuma fonte do acervo local foi recuperada');
  });
});
