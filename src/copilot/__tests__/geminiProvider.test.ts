// Testes do GeminiProvider — Fase 4 (§17): sucesso, timeout, rede,
// autenticação, rate limit, resposta inválida, offline e prompt.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { GeminiProvider } from '../geminiProvider';
import { ProviderError } from '../llmErrors';
import { buildContextPack } from '../contextPack';
import { FIX_PASSAGES } from './fixtures';

afterEach(() => {
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function geminiOk(text: string): Response {
  return jsonResponse({ candidates: [{ content: { parts: [{ text }] } }] });
}

function hangingFetch(): (url: string, init: RequestInit) => Promise<Response> {
  return (_url, init) =>
    new Promise((_resolve, reject) => {
      if (init.signal?.aborted) {
        reject(new DOMException('Aborted', 'AbortError'));
        return;
      }
      init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    });
}

const BASE_REQ = { text: 'Texto do bloco para oratória', action: 'hook' as const };

describe('GeminiProvider', () => {
  it('sucesso retorna texto + meta observável', async () => {
    const fetchMock = vi.fn(async () => geminiOk('gancho 1'));
    vi.stubGlobal('fetch', fetchMock);
    const res = await new GeminiProvider('key').generate(BASE_REQ);
    expect(res.text).toBe('gancho 1');
    expect(res.meta.providerId).toBe('gemini');
    expect(res.meta.model).toBe('gemini-2.5-flash');
    expect(res.meta.attempts).toBe(1);
    expect(res.meta.offline).toBe(false);
    expect(res.meta.durationMs).toBeGreaterThanOrEqual(0);
  });

  it('timeout configurável vira ProviderError timeout', async () => {
    vi.stubGlobal('fetch', vi.fn(hangingFetch()));
    const err = await new GeminiProvider('key', 'm', 50, 1).generate(BASE_REQ).catch((e) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as ProviderError).code).toBe('timeout');
  });

  it('erro de rede vira ProviderError network', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    const err = await new GeminiProvider('key', 'm', 1000, 1).generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('network');
  });

  it('401 vira authentication SEM retry', async () => {
    const fetchMock = vi.fn(async () => new Response('unauthorized', { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);
    const err = await new GeminiProvider('key').generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('authentication');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('429 tenta de novo e sucede (rate_limit é transitório)', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('slow down', { status: 429 }))
      .mockResolvedValueOnce(geminiOk('ok após retry'));
    vi.stubGlobal('fetch', fetchMock);
    const res = await new GeminiProvider('key').generate(BASE_REQ);
    expect(res.text).toBe('ok após retry');
    expect(res.meta.attempts).toBe(2);
  });

  it('resposta sem texto vira invalid_response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ candidates: [] })));
    const err = await new GeminiProvider('key').generate(BASE_REQ).catch((e) => e);
    expect((err as ProviderError).code).toBe('invalid_response');
  });

  it('sem chave usa motor offline explícito sem chamar rede', async () => {
    const fetchMock = vi.fn(async () => geminiOk('nunca'));
    vi.stubGlobal('fetch', fetchMock);
    const res = await new GeminiProvider('').generate(BASE_REQ);
    expect(res.text).toContain('Offline');
    expect(res.meta.offline).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('cancelamento do usuário vira cancelled, não resposta', async () => {
    vi.stubGlobal('fetch', vi.fn(hangingFetch()));
    const ctrl = new AbortController();
    const pending = new GeminiProvider('key', 'm', 5000, 1).generate({ ...BASE_REQ, signal: ctrl.signal });
    ctrl.abort();
    const err = await pending.catch((e) => e);
    expect((err as ProviderError).code).toBe('cancelled');
  });

  it('prompt separa content de training e cita insuficiência', async () => {
    const content = FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 1);
    const training = FIX_PASSAGES.filter((p) => p.pubId === 'pub-be').slice(0, 1);
    const pack = buildContextPack({ task: 'research', speechTitle: 'T', blockText: 'x' }, content, training);
    let capturedBody = '';
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      capturedBody = String(init.body);
      return geminiOk('ok');
    }));
    await new GeminiProvider('key').generate({ ...BASE_REQ, contextPack: pack });
    const body = JSON.parse(capturedBody) as { contents: Array<{ parts: Array<{ text: string }> }> };
    const prompt = body.contents[0].parts[0].text;
    expect(prompt).toContain('FONTES DE CONTEÚDO');
    expect(prompt).toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(prompt).toContain('Não encontrei suporte suficiente nas fontes disponíveis.');
    // Training não vaza para a seção de conteúdo factual.
    const contentSection = prompt.split('FIM DAS FONTES DE CONTEÚDO')[0];
    expect(contentSection).not.toContain('be Ilustrações');
  });

  it('F20-E: teto de saída é configurável; oratório pede 2048', async () => {
    let capturedBody = '';
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      capturedBody = String(init.body);
      return geminiOk('ok');
    }));
    await new GeminiProvider('key').generate({ ...BASE_REQ, maxOutputTokens: 2048 });
    const oratorio = JSON.parse(capturedBody) as { generationConfig: { maxOutputTokens: number } };
    expect(oratorio.generationConfig.maxOutputTokens).toBe(2048);

    await new GeminiProvider('key').generate({ ...BASE_REQ });
    const padrao = JSON.parse(capturedBody) as { generationConfig: { maxOutputTokens: number } };
    expect(padrao.generationConfig.maxOutputTokens).toBe(1000);
  });

  it('sem evidência o prompt registra ausência de fontes', async () => {
    let capturedBody = '';
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      capturedBody = String(init.body);
      return geminiOk('ok');
    }));
    await new GeminiProvider('key').generate(BASE_REQ);
    expect(capturedBody).toContain('Nenhuma fonte do acervo local foi recuperada');
  });
});
