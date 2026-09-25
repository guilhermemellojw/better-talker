// Providers: 5xx, contrato de erro — Fase 10 (§16).
// (timeout/rede/401/429/cancelamento cobertos na Fase 4.)

import { afterEach, describe, expect, it, vi } from 'vitest';
import { GeminiProvider } from '../geminiProvider';
import { QwenProvider } from '../qwenProvider';
import { ProviderError } from '../llmErrors';

afterEach(() => {
  vi.unstubAllGlobals();
});

const REQ = { text: 'Texto do bloco para oratória', action: 'hook' as const };

function geminiOk(text: string): Response {
  return new Response(
    JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }),
    { status: 200 },
  );
}

describe('gemini 5xx', () => {
  it('500 tenta de novo (transitório) e registra tentativas', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('boom', { status: 500 }))
      .mockResolvedValueOnce(geminiOk('ok'));
    vi.stubGlobal('fetch', fetchMock);
    const res = await new GeminiProvider('key').generate(REQ);
    expect(res.text).toBe('ok');
    expect(res.meta.attempts).toBe(2);
  });

  it('500 persistente vira unavailable, sem texto fictício', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
    const err = await new GeminiProvider('key', 'm', 30000, 2).generate(REQ).catch((e) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as ProviderError).code).toBe('unavailable');
    expect((err as ProviderError).attempts).toBe(2);
    expect((err as ProviderError).status).toBe(500);
  });

  it('400 não repete (permanente)', async () => {
    const fetchMock = vi.fn(async () => new Response('bad', { status: 400 }));
    vi.stubGlobal('fetch', fetchMock);
    const err = await new GeminiProvider('key').generate(REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('invalid_request');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('qwen erros', () => {
  const CFG = { endpoint: 'https://qwen.example/v1', model: 'qwen-plus', apiKey: 'k' };

  it('503 vira unavailable com retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('down', { status: 503 }))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 },
      ));
    vi.stubGlobal('fetch', fetchMock);
    const res = await new QwenProvider(CFG).generate(REQ);
    expect(res.text).toBe('ok');
    expect(res.meta.attempts).toBe(2);
  });

  it('endpoint com barra final é normalizado', async () => {
    let url = '';
    vi.stubGlobal('fetch', vi.fn(async (u: string) => {
      url = u;
      return new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 });
    }));
    await new QwenProvider({ ...CFG, endpoint: 'https://qwen.example/v1//' }).generate(REQ);
    expect(url).toBe('https://qwen.example/v1/chat/completions');
  });

  it('erros carregam contrato: código, provider, tentativas', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('down'); }));
    const err = await new QwenProvider({ ...CFG, maxAttempts: 1 }).generate(REQ).catch((e) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as ProviderError).providerId).toBe('qwen');
    expect((err as ProviderError).attempts).toBe(1);
  });
});
