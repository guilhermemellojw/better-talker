// Testes do QwenProvider — Fase 4 (§17): sucesso, indisponível,
// endpoint inválido, timeout, resposta inválida.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { QwenProvider } from '../qwenProvider';
import { ProviderError } from '../llmErrors';
import { parseS34 } from '../s34Parser';
import { scopeToS34Section } from '../s34StructuralRetrieval';
import { oratorySpec } from '../oratoryGeneration';
import { S34_RICH_TEXT } from './s34RichFixture';

function parseS34Rich() {
  return parseS34(S34_RICH_TEXT);
}

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

// ---------- F20-E: transporte Groq (Qwen 3.8 27B) ----------

describe('QwenProvider — Groq (F20-E)', () => {
  const GROQ = {
    endpoint: 'https://api.groq.com/openai/v1',
    model: 'qwen/qwen3.8-27b',
    apiKey: 'k-teste',
    reasoningEffort: 'low' as const,
  };

  function chatWithUsage(): Response {
    return new Response(
      JSON.stringify({
        choices: [{ message: { content: '{"ok":true}' } }],
        usage: { prompt_tokens: 1200, completion_tokens: 300, total_tokens: 1500 },
      }),
      {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'x-ratelimit-remaining-requests': '42',
          'x-ratelimit-remaining-tokens': '9000',
        },
      },
    );
  }

  async function capture(body: unknown): Promise<{ url: string; headers: Record<string, string>; body: Record<string, unknown>; meta: import('../llmProvider').LlmResponseMeta }> {
    let url = '';
    let headers: Record<string, string> = {};
    let sent: Record<string, unknown> = {};
    vi.stubGlobal('fetch', vi.fn(async (u: string, init: RequestInit) => {
      url = String(u);
      headers = (init.headers ?? {}) as Record<string, string>;
      sent = JSON.parse(String(init.body)) as Record<string, unknown>;
      void body;
      return chatWithUsage();
    }));
    const res = await new QwenProvider(GROQ).generate(body as import('../llmProvider').LlmRequest);
    return { url, headers, body: sent, meta: res.meta };
  }

  it('modelo exato, reasoning low e authorization — sem fallback silencioso', async () => {
    const { url, headers, body } = await capture({ text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert' });
    expect(url).toBe('https://api.groq.com/openai/v1/chat/completions');
    expect(body.model).toBe('qwen/qwen3.8-27b');
    expect(body.reasoning_effort).toBe('low');
    expect(headers.Authorization).toBe('Bearer k-teste');
  });

  it('structured output estrito no caminho de proposta', async () => {
    const { body } = await capture({ text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert' });
    const rf = body.response_format as { type: string; json_schema?: { strict?: boolean } };
    expect(rf.type).toBe('json_schema');
    expect(rf.json_schema?.strict).toBe(true);
  });

  it('chat/consulta não pede structured output', async () => {
    const { body } = await capture({ text: 'x', action: 'chat' });
    expect(body.response_format).toBeUndefined();
  });

  it('json_object como modo alternativo', async () => {
    let sent: Record<string, unknown> = {};
    vi.stubGlobal('fetch', vi.fn(async (_u: string, init: RequestInit) => {
      sent = JSON.parse(String(init.body)) as Record<string, unknown>;
      return chatWithUsage();
    }));
    await new QwenProvider({ ...GROQ, structuredOutput: 'json_object' }).generate({
      text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert',
    });
    expect(sent.response_format).toEqual({ type: 'json_object' });
  });

  it('max_tokens acompanha o pedido (oratório usa 2048)', async () => {
    const { body } = await capture({ text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert', maxOutputTokens: 2048 });
    expect(body.max_tokens).toBe(2048);
  });

  it('pedido oratório usa o prompt especializado (estrutura + modo)', async () => {
    const doc = parseS34Rich();
    const view = scopeToS34Section(doc, 'sec-2')!;
    const spec = oratorySpec('development', doc, view, 'insert', new Map());
    expect(spec.kind).toBe('ready');
    if (spec.kind !== 'ready') return;
    const { body } = await capture({
      text: '', action: 'chat', chat: { message: 'Desenvolva o ponto 2.', history: [], isFirstMessage: false },
      responseFormat: 'edit-proposal', editMode: 'insert', oratorySpec: spec.spec,
    });
    const messages = body.messages as Array<{ role: string; content: string }>;
    const user = messages.find((m) => m.role === 'user')?.content ?? '';
    expect(user).toContain('ESTRUTURA DO S-34');
    expect(user).toContain('MODO: DESENVOLVIMENTO DO PONTO');
    expect(user).toContain('Atos 4:29');
    expect(user).not.toContain('Salmo 27:1');
  });

  it('tokens e rate-limit observáveis (§37)', async () => {
    const { meta } = await capture({ text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert' });
    expect(meta.usage).toEqual({ inputTokens: 1200, outputTokens: 300, totalTokens: 1500 });
    expect(meta.rateLimit?.['x-ratelimit-remaining-requests']).toBe('42');
  });

  it('reasoning_format entra no corpo quando configurado (Qwen3 no Groq)', async () => {
    let sent: Record<string, unknown> = {};
    vi.stubGlobal('fetch', vi.fn(async (_u: string, init: RequestInit) => {
      sent = JSON.parse(String(init.body)) as Record<string, unknown>;
      return chatWithUsage();
    }));
    await new QwenProvider({ ...GROQ, reasoningFormat: 'parsed' }).generate({
      text: 'x', action: 'chat', responseFormat: 'edit-proposal', editMode: 'insert',
    });
    expect(sent.reasoning_format).toBe('parsed');
  });
});
