import { describe, it, expect } from 'vitest';
import { extractClaims, suggestionSpans, isInsideSuggestion } from '../claimExtractor';

/**
 * T2 — whitelist do marcador 〈sugestão〉 no gate (web).
 * Prosa criativa marcada é isenta; versículo/número nunca são.
 */
describe('suggestionGate (web)', () => {
  it('spans e posição dentro do marcador', () => {
    const t = 'antes 〈sugestão〉criação aqui〈/sugestão〉 depois';
    const spans = suggestionSpans(t);
    expect(spans).toHaveLength(1);
    expect(isInsideSuggestion(t.indexOf('criação'), spans)).toBe(true);
    expect(isInsideSuggestion(0, spans)).toBe(false);
    expect(isInsideSuggestion(t.indexOf('depois'), spans)).toBe(false);
  });

  it('prosa marcada vira creative (isenta de retrieval)', () => {
    const claims = extractClaims('〈sugestão〉Imagine um rio que nunca seca atravessando o deserto.〈/sugestão〉');
    expect(claims.length).toBeGreaterThan(0);
    expect(claims.every((c) => c.type === 'creative')).toBe(true);
  });

  it('referência de publicação marcada continua biblical (nunca isenta)', () => {
    const claims = extractClaims('〈sugestão〉Veja w24.01 para complementar a ideia.〈/sugestão〉');
    expect(claims.some((c) => c.type === 'biblical')).toBe(true);
  });

  it('número marcado continua factual (nunca isento)', () => {
    const claims = extractClaims('〈sugestão〉Na aldeia de Betel, 73% das pessoas já ouviram isso.〈/sugestão〉');
    expect(claims.some((c) => c.type === 'factual')).toBe(true);
    expect(claims.every((c) => c.type !== 'creative')).toBe(true);
  });

  it('fato sem marcador continua factual (regressão)', () => {
    const claims = extractClaims('A aldeia fica no vale ao norte.');
    expect(claims.some((c) => c.type === 'factual')).toBe(true);
  });
});
