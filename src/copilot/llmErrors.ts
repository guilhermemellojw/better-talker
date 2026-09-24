// Erros de domínio dos providers — Fase 4.
// A UI trata ProviderError sem conhecer detalhes HTTP de cada API.

export type ProviderErrorCode =
  | 'timeout'
  | 'network'
  | 'authentication'
  | 'rate_limit'
  | 'invalid_request'
  | 'invalid_response'
  | 'unavailable'
  | 'cancelled';

export class ProviderError extends Error {
  code: ProviderErrorCode;
  providerId: string;
  attempts: number;
  status?: number;

  constructor(
    code: ProviderErrorCode,
    message: string,
    providerId: string,
    attempts = 0,
    status?: number,
  ) {
    super(message);
    this.name = 'ProviderError';
    this.code = code;
    this.providerId = providerId;
    this.attempts = attempts;
    this.status = status;
  }
}

export function isProviderError(e: unknown): e is ProviderError {
  return e instanceof ProviderError;
}
