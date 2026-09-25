// Performance com corpus maior — Fase 10 (§§30,31). Mede e registra;
// limites folgados só contra patologia (não otimizar prematuramente).

import { describe, expect, it } from 'vitest';
import type { Passage } from '../../types/speech';
import { HybridRetriever } from '../hybridRetriever';
import { analyzeSpeech } from '../speechAnalyzer';
import { verifyText, clearVerificationCache } from '../verifier';
import { fixtureStore } from './fixtures';
import type { SpeechBlock } from '../../types/speech';

function norm(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim();
}

const TOPICS = ['oração', 'confiança', 'família', 'estudo', 'coragem', 'fé', 'amor', 'paz'];

function bigCorpus(pubs: number, perPub: number): Passage[] {
  const out: Passage[] = [];
  for (let p = 0; p < pubs; p++) {
    for (let i = 0; i < perPub; i++) {
      const topic = TOPICS[(p + i) % TOPICS.length];
      const text = `Trecho ${i} sobre ${topic} com desenvolvimento número ${p * perPub + i} para teste de desempenho.`;
      out.push({
        id: `big-p${p}-${i}`, pubId: `big-pub-${p}`, ref: `ref §${i}`,
        text, normalizedText: norm(text), source_type: 'publication',
        symbol: `s${p}`, section: `Seção ${i % 10}`, paragraph: i, order: i, language: 'pt-BR',
      });
    }
  }
  return out;
}

function bigStore() {
  const passages = bigCorpus(6, 100); // 600 passages
  return {
    passages,
    store: {
      getPassagesByIds: async (ids: string[], max: number) =>
        passages.filter((p) => ids.includes(p.pubId)).slice(0, max),
      getPublicationsByIds: async (ids: string[]) =>
        ids.map((id) => ({
          id, fileName: `${id}.epub`, title: `Pub ${id}`, kind: 'epub' as const,
          localPath: '', addedAt: 1, indexed: true,
        })),
    },
  };
}

describe('performance', () => {
  it('retrieval em 600 passages: rápido, limitado, com proveniência', async () => {
    const { store } = bigStore();
    const retriever = new HybridRetriever(store);
    const scope = { contentSourceIds: ['big-pub-0', 'big-pub-1', 'big-pub-2'], trainingSourceIds: [] };
    const t0 = Date.now();
    const res = await retriever.retrieve('oração confiança família', scope, { track: 'content', limit: 5 });
    const ms = Date.now() - t0;
    console.log(`retrieval 600 passages: ${ms}ms, hits=${res.hits.length}`);
    expect(ms).toBeLessThan(5000);
    expect(res.hits.length).toBeLessThanOrEqual(5);
    expect(new Set(res.hits.map((h) => h.passage.id)).size).toBe(res.hits.length);
    for (const h of res.hits) {
      expect(h.passage.ref).toBeTruthy();
      expect(h.passage.section).toBeTruthy();
    }
  });

  it('análise de discurso com 150 blocos: rápida', () => {
    const blocks: SpeechBlock[] = Array.from({ length: 150 }, (_, i) => ({
      id: `b${i}`, speechId: 's', order: i, minutes: 2, title: `Bloco ${i}`,
      contentHtml: `<p>Texto do bloco número ${i} sobre oração e confiança com conteúdo suficiente para análise.</p>`,
      plainText: `Texto do bloco número ${i} sobre oração e confiança com conteúdo suficiente para análise.`,
    }));
    const speech = {
      id: 's', title: 'Grande', contentHtml: '', plainText: '', targetDurationMinutes: 300,
      targetWpm: 130, category: 'geral' as const, tags: [], createdAt: 1, updatedAt: 1, blocks,
    };
    const t0 = Date.now();
    const a = analyzeSpeech(speech, { force: true });
    const ms = Date.now() - t0;
    console.log(`análise 150 blocos: ${ms}ms, obs=${a.observations.length}`);
    expect(ms).toBeLessThan(10000);
    expect(a.structure).toHaveLength(150);
  });

  it('verificação de texto com 20 claims: limitada e rápida', async () => {
    clearVerificationCache();
    const text = Array.from({ length: 20 }, (_, i) => `Afirmação factual número ${i} sobre oração e fé.`).join(' ');
    const t0 = Date.now();
    const res = await verifyText({
      text, blockId: 'b1',
      scope: { contentSourceIds: ['pub-w24'], trainingSourceIds: [] },
      store: fixtureStore(), force: true,
    });
    const ms = Date.now() - t0;
    console.log(`verificação 20 claims: ${ms}ms`);
    expect(ms).toBeLessThan(15000);
    expect(res.claims.length).toBeLessThanOrEqual(20);
  });
});
