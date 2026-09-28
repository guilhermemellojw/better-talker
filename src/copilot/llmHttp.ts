// HTTP compartilhado dos providers — Fase 4.
// Timeout + retry controlado + AbortSignal + mapeamento para ProviderError.
// Nunca registra URL (pode conter chave), corpo ou conteúdo do corpus.

import { ProviderError, type ProviderErrorCode } from './llmErrors';

export interface HttpCallOptions {
  providerId: string;
  model: string;
  timeoutMs: number;
  maxAttempts: number;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

export interface HttpCallResult {
  data: unknown;
  attempts: number;
  /** F20-E (§37): headers de rate-limit relevantes (só nomes conhecidos). */
  rateLimit?: Record<string, string>;
}

const RATE_LIMIT_HEADERS = [
  'x-ratelimit-remaining-requests',
  'x-ratelimit-remaining-tokens',
  'x-ratelimit-reset-requests',
  'x-ratelimit-reset-tokens',
  'retry-after',
];

function readRateLimitHeaders(headers: Headers): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const name of RATE_LIMIT_HEADERS) {
    const value = headers.get(name);
    if (value != null) out[name] = value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      cleanup();
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      cleanup();
      reject(new DOMException('Aborted', 'AbortError'));
    };
    const cleanup = () => signal?.removeEventListener('abort', onAbort);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function mapStatusToCode(status: number): { code: ProviderErrorCode; retryable: boolean; message: string } {
  if (status === 401 || status === 403) {
    return { code: 'authentication', retryable: false, message: 'Chave de API inválida ou sem autorização.' };
  }
  if (status === 429) {
    return { code: 'rate_limit', retryable: true, message: 'Limite de requisições da API atingido.' };
  }
  if (status >= 500) {
    return { code: 'unavailable', retryable: true, message: 'Modelo remoto indisponível no momento.' };
  }
  return { code: 'invalid_request', retryable: false, message: 'Requisição rejeitada pelo provedor.' };
}

export function logProviderCall(
  providerId: string,
  model: string,
  attempts: number,
  durationMs: number,
  outcome: string,
): void {
  // Sem conteúdo, sem URL, sem credenciais — só diagnóstico.
  console.debug(`[llm] provider=${providerId} model=${model} attempts=${attempts} durationMs=${durationMs} outcome=${outcome}`);
}

/** POST JSON com timeout, retry de transitórios e cancelamento. */
export async function postJsonWithRetry(
  url: string,
  body: unknown,
  options: HttpCallOptions,
): Promise<HttpCallResult> {
  const { providerId, model, timeoutMs, maxAttempts, signal } = options;
  const started = Date.now();
  let attempts = 0;
  let lastError: unknown = null;

  const fail = (code: ProviderErrorCode, message: string, status?: number): ProviderError =>
    new ProviderError(code, message, providerId, attempts, status);

  for (let attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
    attempts = attempt;
    if (signal?.aborted) {
      logProviderCall(providerId, model, attempts, Date.now() - started, 'cancelled');
      throw fail('cancelled', 'Geração cancelada pelo usuário.');
    }
    const ctrl = new AbortController();
    const onUserAbort = () => ctrl.abort();
    signal?.addEventListener('abort', onUserAbort, { once: true });
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      if (!response.ok) {
        const mapped = mapStatusToCode(response.status);
        if (!mapped.retryable || attempt >= maxAttempts) {
          logProviderCall(providerId, model, attempts, Date.now() - started, mapped.code);
          throw fail(mapped.code, `${mapped.message} (HTTP ${response.status})`, response.status);
        }
        lastError = fail(mapped.code, mapped.message, response.status);
        try {
          await sleep(400 * attempt, signal);
        } catch {
          logProviderCall(providerId, model, attempts, Date.now() - started, 'cancelled');
          throw fail('cancelled', 'Geração cancelada pelo usuário.');
        }
      } else {
        try {
          const data: unknown = await response.json();
          logProviderCall(providerId, model, attempts, Date.now() - started, 'ok');
          return { data, attempts, rateLimit: readRateLimitHeaders(response.headers) };
        } catch {
          logProviderCall(providerId, model, attempts, Date.now() - started, 'invalid_response');
          throw fail('invalid_response', 'Resposta do provedor em formato inesperado.', response.status);
        }
      }
    } catch (e) {
      // Erro de domínio já classificado (auth, inválido, cancelado): final.
      if (e instanceof ProviderError) throw e;
      if (isAbortError(e)) {
        if (signal?.aborted) {
          logProviderCall(providerId, model, attempts, Date.now() - started, 'cancelled');
          throw fail('cancelled', 'Geração cancelada pelo usuário.');
        }
        lastError = fail('timeout', `Tempo esgotado após ${timeoutMs}ms.`);
      } else {
        lastError = fail('network', 'Falha de rede ao alcançar o provedor.');
      }
      if (attempt < maxAttempts) {
        try {
          await sleep(400 * attempt, signal);
        } catch {
          logProviderCall(providerId, model, attempts, Date.now() - started, 'cancelled');
          throw fail('cancelled', 'Geração cancelada pelo usuário.');
        }
        continue;
      }
      logProviderCall(providerId, model, attempts, Date.now() - started, (lastError as ProviderError).code);
      throw lastError;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onUserAbort);
    }
  }
  // Inalcançável na prática; garante retorno para o type-checker.
  throw lastError instanceof ProviderError
    ? lastError
    : fail('unavailable', 'Provedor indisponível.');
}
