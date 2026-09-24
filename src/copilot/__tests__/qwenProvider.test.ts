// Testes do QwenProvider — Fase 4 (§17): sucesso, indisponível,
// endpoint inválido, timeout, resposta inválida.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { QwenProvider } from '../qwenProvider';
import { ProviderError } from '../llmErrors';

afterEach(() => {
  vi.unstubAllGlobals();
});

function chatOk(content: string): Response {
  return new Response(JSON.stringify({ choices: [{ message: { content } }] }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

const CFG = { endpoint: 'https://qwen.example/v1', model: 'qwen-plus', apiKey: 'k' };
const BASE_REQ = { text: 'Texto do bloco para oratória', action: 'rewrite' as const };

describe('QwenProvider', () => {
  it('sucesso retorna texto + meta', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => chatOk('reescrita qwen')));
    const res = await new QwenProvider(CFG).generate(BASE_REQ);
    expect(res.text).toBe('reescrita qwen');
    expect(res.meta.providerId).toBe('qwen');
    expect(res.meta.model).toBe('qwen-plus');
    expect(res.meta.offline).toBe(false);
  });

  it('envia system+user para /chat/completions', async () => {
    let url = '';
    let body = '';
    vi.stubGlobal('fetch', vi.fn(async (u: string, init: RequestInit) => {
      url = u;
      body = String(init.body);
      return chatOk('ok');
    }));
    await new QwenProvider(CFG).generate(BASE_REQ);
    expect(url).toBe('https://qwen.example/v1/chat/completions');
    const parsed = JSON.parse(body) as { model: string; messages: Array<{ role: string }> };
    expect(parsed.model).toBe('qwen-plus');
    expect(parsed.messages.map((m) => m.role)).toEqual(['system', 'user']);
  });

  it('sem endpoint: unavailable + isConfigured false (não finge suporte)', async () => {
    const fetchMock = vi.fn(async () => chatOk('nunca'));
    vi.stubGlobal('fetch', fetchMock);
    const provider = new QwenProvider({});
    expect(provider.isConfigured()).toBe(false);
    const err = await provider.generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('unavailable');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('endpoint inalcançável vira network', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    const err = await new QwenProvider({ ...CFG, maxAttempts: 1 }).generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('network');
  });

  it('timeout vira timeout', async () => {
    vi.stubGlobal('fetch', vi.fn((_u: string, init: RequestInit) => new Promise<Response>((_res, rej) => {
      init.signal?.addEventListener('abort', () => rej(new DOMException('Aborted', 'AbortError')), { once: true });
    })));
    const err = await new QwenProvider({ ...CFG, timeoutMs: 50, maxAttempts: 1 }).generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('timeout');
  });

  it('choices vazio vira invalid_response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ choices: [] }), { status: 200 })));
    const err = await new QwenProvider(CFG).generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('invalid_response');
  });

  it('401 vira authentication sem retry', async () => {
    const fetchMock = vi.fn(async () => new Response('denied', { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);
    const err = await new QwenProvider(CFG).generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('authentication');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
