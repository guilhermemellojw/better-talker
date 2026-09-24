// Testes do retrieval híbrido — Fase 3 (§17, casos 1–10).

import { describe, expect, it } from 'vitest';
import { HybridRetriever } from '../hybridRetriever';
import { FIX_SCOPE, fixtureStore } from './fixtures';

const retrieve = (query: string, scope = FIX_SCOPE, track: 'content' | 'training' = 'content', limit = 5) =>
  new HybridRetriever(fixtureStore()).retrieve(query, scope, { track, limit });

describe('hybrid retrieval', () => {
  it('caso 1 — correspondência exata recupera o passage em posição alta', async () => {
    const res = await retrieve('Confiar em Jeová nos ajuda a enfrentar problemas graves com coragem.');
    expect(res.status).toBe('ok');
    expect(res.hits[0]?.passage.id).toBe('p-w24-1');
  });

  it('caso 2 — variação lexical (caixa/acentos) recupera conteúdo relevante', async () => {
    const res = await retrieve('CONFIANDO em jeova durante problemas');
    expect(res.status).toBe('ok');
    const ids = res.hits.map((h) => h.passage.id);
    expect(ids).toContain('p-w24-1');
  });

  it('caso 3 — metadata prioriza o passage da referência citada', async () => {
    const res = await retrieve('oração w24');
    expect(res.status).toBe('ok');
    expect(res.hits[0]?.passage.id).toBe('p-w24-2');
    expect(res.hits[0]?.metadataScore).toBeGreaterThan(0);
  });

  it('caso 4 — passage relevante fora do escopo NÃO aparece', async () => {
    const res = await retrieve('confiança Deus ansiedade medo');
    const ids = res.hits.map((h) => h.passage.id);
    expect(ids).not.toContain('p-g-1');
  });

  it('caso 5 — escopo vazio retorna insufficient_scope sem buscar', async () => {
    const res = await retrieve('oração', { contentSourceIds: [], trainingSourceIds: [] });
    expect(res.status).toBe('insufficient_scope');
    expect(res.hits).toHaveLength(0);
  });

  it('caso 6 — trilho content exclui BE/TH', async () => {
    const res = await retrieve('ilustrações transições voz pausas', FIX_SCOPE, 'content');
    for (const h of res.hits) {
      expect(h.passage.source_type).not.toBe('speech_training');
    }
  });

  it('caso 7 — trilho training retorna BE/TH', async () => {
    const res = await retrieve('ilustrações simples cotidiano', FIX_SCOPE, 'training');
    expect(res.status).toBe('ok');
    expect(res.hits[0]?.passage.id).toBe('p-be-1');
  });

  it('caso 8 — mesma query + scope + corpus => mesmo ranking', async () => {
    const a = await retrieve('oração Jeová Deus');
    const b = await retrieve('oração Jeová Deus');
    expect(b.hits.map((h) => h.passage.id)).toEqual(a.hits.map((h) => h.passage.id));
    expect(b.hits.map((h) => h.finalScore)).toEqual(a.hits.map((h) => h.finalScore));
  });

  it('caso 9 — todo resultado preserva proveniência', async () => {
    const res = await retrieve('oração');
    expect(res.hits.length).toBeGreaterThan(0);
    for (const h of res.hits) {
      expect(h.passage.id).toBeTruthy();
      expect(h.passage.pubId).toBeTruthy();
      expect(h.passage.ref).toBeTruthy();
      expect(h.foundBy.length).toBeGreaterThan(0);
      expect(h.matchedTerms.length).toBeGreaterThan(0);
    }
    const oracao = res.hits.find((h) => h.passage.id === 'p-w24-2');
    expect(oracao?.passage.page).toBe(12);
    expect(oracao?.passage.section).toBe('Oração');
    expect(oracao?.publicationTitle).toBe('A Sentinela Janeiro de 2024');
  });

  it('caso 10 — scores dentro de [0,1]', async () => {
    const res = await retrieve('Jeová oração confiança família ilustrações');
    expect(res.hits.length).toBeGreaterThan(0);
    for (const h of res.hits) {
      for (const s of [h.finalScore, h.lexicalScore, h.metadataScore, h.semanticScore]) {
        expect(s).toBeGreaterThanOrEqual(0);
        expect(s).toBeLessThanOrEqual(1);
      }
    }
  });
});
