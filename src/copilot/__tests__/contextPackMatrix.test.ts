// Matriz ContextPack + proveniência — Fase 10 (§§8,9,13,14).

import { describe, expect, it } from 'vitest';
import { buildContextPack, buildContextPackFromCandidates, candidateToEvidence } from '../contextPack';
import { retrieveTraining } from '../trainingRetriever';
import { formatProvenance, passagesToContextStrings } from '../retrieval';
import { buildLlmPrompt } from '../llmPrompt';
import { FIX_SCOPE, FIX_PASSAGES, fixtureStore } from './fixtures';
import type { Passage } from '../../types/speech';

const INPUT = { task: 'research' as const, speechTitle: 'T', blockText: 'x' };
const store = () => fixtureStore();

function barePassage(): Passage {
  return {
    id: 'px', pubId: 'pub-w24', ref: '', text: 'Texto sem metadata rica.',
    normalizedText: 'texto sem metadata rica',
  };
}

describe('matriz ContextPack (§8)', () => {
  it('pedido factual: training vazio', async () => {
    const pack = buildContextPack(INPUT, FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 2), []);
    expect(pack.content_sources.length).toBeGreaterThan(0);
    expect(pack.training_sources).toHaveLength(0);
  });

  it('pedido de apresentação: ambos os trilhos, separados', async () => {
    const content = FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 2);
    const training = FIX_PASSAGES.filter((p) => p.pubId === 'pub-be').slice(0, 1);
    const pack = buildContextPack(INPUT, content, training);
    expect(pack.content_sources.length).toBe(2);
    expect(pack.training_sources.length).toBe(1);
    expect(pack.training_sources[0].source_type).toBe('speech_training');
    expect(pack.content_sources.every((s) => s.source_type !== 'speech_training')).toBe(true);
  });

  it('pedido de transição: categoria transition no topo', async () => {
    const res = await retrieveTraining({
      query: 'transições breves entre pontos', category: 'transition',
      scope: FIX_SCOPE, store: store(), limit: 3,
    });
    expect(res.status).toBe('ok');
    expect(res.hits[0]?.passage.id).toBe('p-be-2');
  });

  it('pedido de introdução: categoria introduction', async () => {
    // Fixture não tem introduction: garante ao menos trilho correto + sem crash.
    const res = await retrieveTraining({
      query: 'como começar uma explicação interessante', category: 'introduction',
      scope: FIX_SCOPE, store: store(), limit: 3,
    });
    expect(res.status).toBe('ok');
    for (const h of res.hits) {
      expect(h.passage.source_type).toBe('speech_training');
    }
  });
});

describe('proveniência ponta a ponta (§9)', () => {
  it('todos os campos sobrevivem candidato → evidência', async () => {
    const res = await retrieveTraining({
      query: 'oração', scope: FIX_SCOPE, store: store(), limit: 5,
    });
    // Training não tem oração: usa content para checar campos ricos.
    expect(res.status).toBe('ok');
    const { HybridRetriever } = await import('../hybridRetriever');
    const content = await new HybridRetriever(store()).retrieve('oração', FIX_SCOPE, { track: 'content', limit: 2 });
    expect(content.hits.length).toBeGreaterThan(0);
    const ev = candidateToEvidence(content.hits[0], 'Fonte 1');
    expect(ev.id).toBe(content.hits[0].passage.id);
    expect(ev.reference).toBeTruthy();
    expect(ev.publication).toBeTruthy();
    expect(ev.section).toBe('Oração');
    expect(ev.page).toBe(12);
    expect(ev.paragraph).toBe(5);
    expect(ev.score).toBeGreaterThan(0);
    expect(ev.matchedTerms ?? []).not.toHaveLength(0);
    expect(ev.foundBy ?? []).not.toHaveLength(0);
  });

  it('ausente vira null/undefined, nunca fictício', () => {
    const p = barePassage();
    const ev = candidateToEvidence(
      {
        passage: p, publicationTitle: undefined, lexicalScore: 0.5,
        metadataScore: 0, semanticScore: 0, finalScore: 0.2,
        matchedTerms: ['texto'], foundBy: ['lexical'],
      },
      'Fonte 1',
    );
    expect(ev.section).toBeUndefined();
    expect(ev.page).toBeUndefined();
    expect(ev.paragraph).toBeUndefined();
    expect(ev.reference).toBe('Fonte 1');
    expect(formatProvenance(p)).toBe('pub-w24');
  });

  it('strings de contexto preservam referência', () => {
    const out = passagesToContextStrings(FIX_PASSAGES.filter((p) => p.id === 'p-w24-2'));
    expect(out[0]).toContain('w24 Oração §5');
  });
});

describe('training como não-evidência + ilustração (§§13,14)', () => {
  it('prompt de técnica proíbe autoridade factual e atribuição', () => {
    const { system } = buildLlmPrompt({ text: 'x', action: 'hook' });
    expect(system).toMatch(/COMO apresentar/);
    expect(system).toMatch(/sugestão do modelo/);
    expect(system).toMatch(/nunca as use como fonte de fatos/i);
  });

  it('pack com training não contamina content_sources', () => {
    const pack = buildContextPackFromCandidates(
      INPUT,
      FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 1).map((p) => ({
        passage: p, publicationTitle: 'W', lexicalScore: 0.9, metadataScore: 0,
        semanticScore: 0, finalScore: 0.5, matchedTerms: [], foundBy: ['lexical' as const],
      })),
      FIX_PASSAGES.filter((p) => p.pubId === 'pub-be').slice(0, 1).map((p) => ({
        passage: p, publicationTitle: 'BE', lexicalScore: 0.9, metadataScore: 0,
        semanticScore: 0, finalScore: 0.5, matchedTerms: [], foundBy: ['lexical' as const],
      })),
    );
    expect(pack.content_sources.every((s) => s.source_type === 'publication')).toBe(true);
    expect(pack.training_sources.every((s) => s.source_type === 'speech_training')).toBe(true);
  });
});
