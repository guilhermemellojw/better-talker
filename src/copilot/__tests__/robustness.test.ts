// Robustez: vazios, extremos, acentos, cache, segurança — Fase 10 (§§7,19,20,21,25,27).

import { describe, expect, it } from 'vitest';
import type { Speech, SpeechBlock } from '../../types/speech';
import { analyzeSpeech, analysisCache, analysisKeyFor, speechContentHash } from '../speechAnalyzer';
import { ANALYSIS_VERSION } from '../speechAnalysis';
import { verifyText, clearVerificationCache, verificationCacheSize } from '../verifier';
import { parseEditProposal } from '../proposalParser';
import { applyEditProposal, hashText, stripHtmlToText } from '../editProposal';
import { retrieveTraining } from '../trainingRetriever';
import { HybridRetriever } from '../hybridRetriever';
import { classifyTraining } from '../trainingClassifier';
import { uniqueTokens, tokenize } from '../tokenize';
import { emptyScope } from '../retrievalTypes';
import { FIX_SCOPE, fixtureStore } from './fixtures';

function block(id: string, title: string, text: string): SpeechBlock {
  return { id, speechId: 's1', order: 0, minutes: 5, title, contentHtml: `<p>${text}</p>`, plainText: text };
}

function speechOf(title: string, blocks: SpeechBlock[]): Speech {
  return {
    id: 's1', title, contentHtml: '', plainText: '', targetDurationMinutes: 10,
    targetWpm: 130, category: 'geral', tags: [], createdAt: 1, updatedAt: 1, blocks,
  };
}

const store = () => fixtureStore();

describe('vazios não quebram (§19)', () => {
  it('discurso sem blocos: análise sem crash, tempo zero', () => {
    const a = analyzeSpeech(speechOf('Vazio', []), { force: true });
    expect(a.structure).toHaveLength(0);
    expect(a.observations.map((o) => o.type)).toContain('TIME');
    expect(a.estimatedTime.totalSeconds).toBe(0);
    expect(a.flow).toHaveLength(0);
  });

  it('bloco único vazio: sem crash, sem papéis inventados', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', '')]), { force: true });
    expect(a.structure[0].roles).toHaveLength(0);
    expect(a.structure[0].wordCount).toBe(0);
  });

  it('texto vazio verifica com zero claims', async () => {
    clearVerificationCache();
    const res = await verifyText({ text: '   ', blockId: 'b1', scope: FIX_SCOPE, store: store(), force: true });
    expect(res.claims).toHaveLength(0);
    expect(res.summary).toEqual({ supported: 0, partial: 0, insufficient: 0, creative: 0 });
  });

  it('query vazia é insufficient sem tocar no corpus', async () => {
    const retriever = new HybridRetriever(store());
    // retrieve direto exige escopo; vazio => insufficient.
    const res = await retriever.retrieve('', { contentSourceIds: [], trainingSourceIds: [] }, { track: 'content' });
    expect(res.status).toBe('insufficient_scope');
    expect(res.hits).toHaveLength(0);
  });

  it('proposta delete sem alvo => inválida', () => {
    const sp = speechOf('T', [block('b1', 'A', 'Texto.')]);
    expect(parseEditProposal('', sp, 'fantasma', 'delete').ok).toBe(false);
  });

  it('biblioteca vazia (escopo vazio) => insufficient, sem evidência', async () => {
    clearVerificationCache();
    const res = await verifyText({
      text: 'Confiar em Jeová nos ajuda sempre.', blockId: 'b1',
      scope: emptyScope(), store: store(), force: true,
    });
    expect(res.claims.every((c) => c.status === 'insufficient' || c.status === 'creative')).toBe(true);
    expect(res.claims.flatMap((c) => c.evidence)).toHaveLength(0);
  });
});

describe('texto extremo (§20)', () => {
  const monster = `${'Palavra '.repeat(2000)}! Emojis 🎤📖🔥 e "aspas" e <b>HTML</b> e **markdown** e w24.01 e 99% e Jeremias 31:3? Fim.`;

  it('análise de bloco gigante não quebra e conta palavras', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', monster)]), { force: true });
    expect(a.structure[0].wordCount).toBeGreaterThan(1900);
    expect(a.estimatedTime.totalSeconds).toBeGreaterThan(0);
  });

  it('hash determinístico com astral/acentos', () => {
    expect(hashText('oração 🎤')).toBe(hashText('oração 🎤'));
    expect(hashText('oração 🎤')).not.toBe(hashText('oracao 📖'));
  });

  it('stripHtml preserva texto e remove tags', () => {
    expect(stripHtmlToText('<p>Olá <strong>mundo</strong></p>')).toBe('Olá mundo');
    expect(stripHtmlToText('')).toBe('');
  });

  it('retrieval com query extrema é determinístico e limitado', async () => {
    const retriever = new HybridRetriever(store());
    const scope = { contentSourceIds: ['pub-w24'], trainingSourceIds: [] };
    const a = await retriever.retrieve(monster, scope, { track: 'content', limit: 5 });
    const b = await retriever.retrieve(monster, scope, { track: 'content', limit: 5 });
    expect(a.hits.map((h) => h.passage.id)).toEqual(b.hits.map((h) => h.passage.id));
    expect(a.hits.length).toBeLessThanOrEqual(5);
    // Sem ids duplicados.
    expect(new Set(a.hits.map((h) => h.passage.id)).size).toBe(a.hits.length);
  });

  it('verificação de texto extremo não quebra', async () => {
    clearVerificationCache();
    const res = await verifyText({ text: monster, blockId: 'b1', scope: FIX_SCOPE, store: store(), force: true });
    expect(res.claims.length).toBeGreaterThan(0);
    expect(res.claims.length).toBeLessThanOrEqual(20);
  });
});

describe('acentuação (§21)', () => {
  it('classificação entende acentos', () => {
    expect(classifyTraining({ text: 'Uma explicação clara do texto.' })).toBe('explanation');
    expect(classifyTraining({ text: 'Faça uma aplicação prática.' })).toBe('application');
    expect(classifyTraining({ text: 'Use uma transição suave.' })).toBe('transition');
    expect(classifyTraining({ text: 'Termine com uma conclusão breve.' })).toBe('conclusion');
    expect(classifyTraining({ text: 'Comece com uma introdução forte.' })).toBe('introduction');
    expect(classifyTraining({ text: 'Reze uma oração sincera.' })).toBe('unknown');
  });

  it('retrieval casa com e sem acento', async () => {
    const retriever = new HybridRetriever(store());
    const scope = { contentSourceIds: ['pub-w24'], trainingSourceIds: [] };
    const acc = await retriever.retrieve('oração sincera', scope, { track: 'content', limit: 3 });
    const plain = await retriever.retrieve('oracao sincera', scope, { track: 'content', limit: 3 });
    expect(acc.hits.map((h) => h.passage.id)).toEqual(plain.hits.map((h) => h.passage.id));
    expect(acc.hits[0]?.passage.id).toBe('p-w24-2');
  });

  it('tokens normalizam consistentemente', () => {
    expect(uniqueTokens(tokenize('oração ORAÇÃO oracao'))).toEqual(['oracao']);
  });
});

describe('cache (§§7,25)', () => {
  it('análise: mesmo input repete objeto; edição invalida; versão na chave', () => {
    analysisCache.clear();
    const sp = speechOf('T', [block('b1', 'A', 'Texto estável para cache.')]);
    const a = analyzeSpeech(sp);
    expect(analyzeSpeech(sp)).toBe(a);
    expect(analysisKeyFor(sp.id, speechContentHash(sp))).toContain(`v${ANALYSIS_VERSION}`);
    const edited: Speech = { ...sp, blocks: [{ ...sp.blocks[0], plainText: 'Mudou.', contentHtml: '<p>Mudou.</p>' }] };
    expect(analyzeSpeech(edited)).not.toBe(a);
  });

  it('análise respeita cap de 50 (sem crescimento infinito)', () => {
    analysisCache.clear();
    for (let i = 0; i < 55; i++) {
      analyzeSpeech(speechOf(`T${i}`, [block('b1', 'A', `Texto número ${i} para encher o cache.`)]));
    }
    expect(analysisCache.size()).toBeLessThanOrEqual(50);
  });

  it('verificação respeita cap e invalida por escopo', async () => {
    clearVerificationCache();
    const mk = (t: string, scope = FIX_SCOPE) =>
      verifyText({ text: t, blockId: 'b1', scope, store: store(), force: false });
    const a = await mk('Confiar em Jeová nos ajuda.');
    expect(await mk('Confiar em Jeová nos ajuda.')).toBe(a);
    const otherScope = { contentSourceIds: ['pub-g'], trainingSourceIds: [] };
    expect(await mk('Confiar em Jeová nos ajuda.', otherScope)).not.toBe(a);
    for (let i = 0; i < 55; i++) {
      await verifyText({ text: `Texto distinto de cache ${i}.`, blockId: 'b1', scope: FIX_SCOPE, store: store(), force: false });
    }
    expect(verificationCacheSize()).toBeLessThanOrEqual(50);
  });
});

describe('segurança de edição (§27)', () => {
  const sp = () => speechOf('T', [block('b1', 'A', 'Texto original do bloco.')]);

  it('11 operações => inválida; conteúdo gigante => parser rejeita', () => {
    const ops = Array.from({ length: 11 }, (_, i) => ({
      type: 'replace' as const, targetId: 'b1', contentHtml: `<p>V${i}.</p>`,
    }));
    const res = applyEditProposal(sp(), {
      id: 'p', mode: 'rewrite', operations: ops,
      baseHashes: {}, createdAt: 1,
    });
    expect(res.status).toBe('invalid');
    const huge = '```json\n{"operations": [{"type": "replace", "content": "<p>' + 'x'.repeat(20001) + '</p>"}]}\n```';
    expect(parseEditProposal(huge, sp(), 'b1', 'rewrite').ok).toBe(false);
  });

  it('operações duplicadas idênticas não quebram (última prevalece)', () => {
    const res = applyEditProposal(sp(), {
      id: 'p', mode: 'rewrite', operations: [
        { type: 'replace', targetId: 'b1', contentHtml: '<p>Primeira.</p>' },
        { type: 'replace', targetId: 'b1', contentHtml: '<p>Segunda.</p>' },
      ],
      baseHashes: {}, createdAt: 1,
    });
    expect(res.status).toBe('applied');
    expect(res.speech?.blocks[0].plainText).toBe('Segunda.');
  });

  it('hash incorreto => stale, nada aplicado', () => {
    const res = applyEditProposal(sp(), {
      id: 'p', mode: 'rewrite', operations: [{ type: 'replace', targetId: 'b1', contentHtml: '<p>X.</p>' }],
      baseHashes: { b1: 'hasherrado' }, createdAt: 1,
    });
    expect(res.status).toBe('stale_proposal');
  });

  it('training vazio no escopo não derruba o content', async () => {
    const res = await retrieveTraining({
      query: 'ilustrações', scope: { contentSourceIds: ['pub-w24'], trainingSourceIds: [] }, store: store(),
    });
    expect(res.status).toBe('insufficient_scope');
  });
});
