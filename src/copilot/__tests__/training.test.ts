// Testes do trilho de treinamento — Fase 7 (§26).

import { describe, expect, it } from 'vitest';
import { classifyTraining, trainingCategoryOf } from '../trainingClassifier';
import { retrieveTraining, passageTrainingCategory } from '../trainingRetriever';
import { trainingCategoryForAction, trainingCategoryForEditMode } from '../trainingIntent';
import { buildContextPackFromCandidates, type BuildInput } from '../contextPack';
import { candidatesToMeta } from '../retrieval';
import { buildLlmPrompt } from '../llmPrompt';
import { verifyText } from '../verifier';
import { FIX_SCOPE, FIX_PASSAGES, fixtureStore } from './fixtures';
import type { TrainingCategory } from '../domain';

const INPUT: BuildInput = { task: 'research', speechTitle: 'T', blockText: 'x' };

describe('classificação de treinamento', () => {
  it('seções reais do corpus classificam corretamente', () => {
    expect(classifyTraining({ section: 'Ilustrações', text: 'Use ilustrações simples.' })).toBe('illustration');
    expect(classifyTraining({ section: 'Transições', text: 'Ligue os pontos.' })).toBe('transition');
    expect(classifyTraining({ section: 'Entrega', text: 'Module a voz.' })).toBe('delivery');
  });

  it('cobre todas as categorias da taxonomia', () => {
    const cases: Array<[string, TrainingCategory]> = [
      ['Como fazer uma boa introdução e abertura', 'introduction'],
      ['Desenvolvimento dos pontos principais e estrutura', 'development'],
      ['Explicação clara para expor o ensino', 'explanation'],
      ['Uma ilustração com exemplo e analogia', 'illustration'],
      ['Aplicação prática da lição', 'application'],
      ['Transição com ponte para o próximo ponto', 'transition'],
      ['Conclusão para terminar e recapitular', 'conclusion'],
      ['Faça uma pergunta de reflexão', 'questions'],
      ['Clareza simples e direta', 'clarity'],
      ['Naturalidade com modéstia e sinceridade', 'naturalness'],
      ['Entrega com gestos e ritmo no palco', 'delivery'],
    ];
    for (const [text, expected] of cases) {
      expect(classifyTraining({ text })).toBe(expected);
    }
  });

  it('inseguro => unknown; símbolo sozinho não classifica', () => {
    expect(classifyTraining({ text: 'Texto genérico sobre variados assuntos.' })).toBe('unknown');
    expect(classifyTraining({ symbol: 'be' })).toBe('unknown');
    expect(classifyTraining({})).toBe('unknown');
  });

  it('metadata gravada prevalece sobre classificação', () => {
    expect(trainingCategoryOf('conclusion', { text: 'ilustrações' })).toBe('conclusion');
    expect(trainingCategoryOf(undefined, { section: 'Ilustrações', text: 'x' })).toBe('illustration');
  });
});

describe('training retrieval', () => {
  const train = (query: string, category?: TrainingCategory, limit = 4) =>
    retrieveTraining({ query, category, scope: FIX_SCOPE, store: fixtureStore(), limit });

  it('busca por intenção: illustration, transition, delivery', async () => {
    const ill = await train('ilustrações simples do cotidiano', 'illustration');
    expect(ill.status).toBe('ok');
    expect(ill.hits[0]?.passage.id).toBe('p-be-1');
    expect(passageTrainingCategory(ill.hits[0]!.passage)).toBe('illustration');

    const tra = await train('transições breves entre pontos', 'transition');
    expect(tra.hits[0]?.passage.id).toBe('p-be-2');

    const del = await train('modular a voz e pausas', 'delivery');
    expect(del.hits[0]?.passage.id).toBe('p-th-1');
  });

  it('sem categoria retorna trilho training sem filtro', async () => {
    const res = await train('voz pausas ilustrações');
    expect(res.status).toBe('ok');
    expect(res.hits.length).toBeGreaterThan(0);
    for (const h of res.hits) {
      expect(['pub-be', 'pub-th']).toContain(h.passage.pubId);
    }
  });

  it('escopo training vazio => insufficient_scope', async () => {
    const res = await train('ilustrações', 'illustration', 4).then((r) => r);
    expect(res.status).toBe('ok');
    const empty = await retrieveTraining({
      query: 'ilustrações', scope: { contentSourceIds: ['pub-w24'], trainingSourceIds: [] }, store: fixtureStore(),
    });
    expect(empty.status).toBe('insufficient_scope');
    expect(empty.hits).toHaveLength(0);
  });

  it('nunca retorna fonte de conteúdo', async () => {
    const res = await train('oração Jeová confiança');
    for (const h of res.hits) {
      expect(h.passage.source_type).toBe('speech_training');
    }
  });
});

describe('intenção, pack, proveniência e UI', () => {
  it('mapa de intenção por ação/modo', () => {
    expect(trainingCategoryForAction('hook')).toBe('introduction');
    expect(trainingCategoryForAction('rewrite')).toBe('clarity');
    expect(trainingCategoryForAction('cues')).toBe('delivery');
    expect(trainingCategoryForEditMode('insert')).toBe('illustration');
    expect(trainingCategoryForEditMode('rewrite')).toBe('development');
    expect(trainingCategoryForEditMode('delete')).toBeNull();
    expect(trainingCategoryForEditMode('suggest')).toBeNull();
  });

  it('ContextPack preserva trilhos e categoria chega à UI', async () => {
    const res = await retrieveTraining({
      query: 'ilustrações simples', scope: FIX_SCOPE, store: fixtureStore(), limit: 2,
    });
    const pack = buildContextPackFromCandidates(INPUT, [], res.hits);
    expect(pack.content_sources).toHaveLength(0);
    expect(pack.training_sources.length).toBeGreaterThan(0);
    for (const s of pack.training_sources) {
      expect(s.source_type).toBe('speech_training');
      expect(s.training_category).toBeDefined();
      expect(s.training_category).not.toBe('unknown');
    }
    const meta = candidatesToMeta(res.hits, 'training');
    expect(meta[0]?.category).toBe('illustration');
    expect(meta[0]?.reference).toBeTruthy();
  });

  it('prompt rotula técnica e proíbe atribuição de criação', () => {
    const content = FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 1);
    const training = FIX_PASSAGES.filter((p) => p.pubId === 'pub-be').slice(0, 1);
    const { system, user } = buildLlmPrompt({
      text: 'bloco', action: 'hook', contextPassages: [],
      contextPack: {
        task: 'research', mode: 'GROUNDED', speech: { title: '' }, current_section: {},
        user_preferences: { language: 'pt-BR' },
        content_sources: content.map((p) => ({
          id: p.id, reference: p.ref, text: p.text, source_type: 'publication' as const,
        })),
        training_sources: training.map((p) => ({
          id: p.id, reference: p.ref, text: p.text, source_type: 'speech_training' as const,
          training_category: 'illustration' as const,
        })),
      },
    });
    expect(user).toContain('técnica: illustration');
    expect(system).toContain('COMO apresentar');
    expect(system).toContain('sugestão do modelo');
  });

  it('Fase 6: texto gerado após sugestão de training ainda é verificado', async () => {
    const creative = await verifyText({
      text: 'Imagine uma ponte firme sobre um rio: assim é uma boa transição.',
      blockId: 'b1', scope: FIX_SCOPE, store: fixtureStore(), force: true,
    });
    expect(creative.claims[0]?.status).toBe('creative');

    const factual = await verifyText({
      text: 'A oração sincera fortalece a amizade com Deus.',
      blockId: 'b1', scope: FIX_SCOPE, store: fixtureStore(), force: true,
    });
    expect(factual.claims[0]?.status).toBe('supported');
    for (const vc of factual.claims) {
      for (const e of vc.evidence) {
        expect(e.provenance.sourceType).not.toBe('speech_training');
      }
    }
  });
});
