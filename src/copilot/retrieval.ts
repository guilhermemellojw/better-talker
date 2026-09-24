// Fachada do retrieval — Fase 3: delega ao HybridRetriever com escopo.
// Preserva as assinaturas legadas usadas por App.tsx/CopilotDrawer.
// ragRetriever.ts permanece intocado (legado puro, sem consumidores ativos).
// Sem fallback global: escopo vazio => insufficient_scope => [].

import { db, speechStorage } from '../services/db';
import type { Passage, Publication } from '../types/speech';
import { HybridRetriever } from './hybridRetriever';
import { trainingCategoryOf } from './trainingClassifier';
import type { TrainingCategory } from './domain';
import {
  type PassageStore,
  type RetrievalCandidate,
  type RetrievalResult,
  type RetrievalScope,
  type RetrievalTrack,
} from './retrievalTypes';

/** Store Dexie com carregamento restrito aos ids do escopo (§3). */
export const dexiePassageStore: PassageStore = {
  async getPassagesByIds(ids: string[], maxCandidates: number): Promise<Passage[]> {
    const out: Passage[] = [];
    // Ids ordenados + passages ordenados por `order`: conjunto determinístico.
    for (const id of [...ids].sort()) {
      if (out.length >= maxCandidates) break;
      const list = await speechStorage.getPassagesByPubId(id);
      out.push(...list);
    }
    return out.slice(0, maxCandidates);
  },
  async getPublicationsByIds(ids: string[]): Promise<Publication[]> {
    if (ids.length === 0) return [];
    return db.publications.where('id').anyOf(ids).toArray();
  },
};

export function createRetriever(store: PassageStore = dexiePassageStore): HybridRetriever {
  return new HybridRetriever(store);
}

/**
 * Escopo = biblioteca local dividida por tipo.
 * Registros sem `source_type` (v2) vão para content — training exige
 * classificação explícita como speech_training.
 */
export async function buildScopeFromLibrary(): Promise<RetrievalScope> {
  const pubs = await speechStorage.getAllPublications();
  const contentSourceIds: string[] = [];
  const trainingSourceIds: string[] = [];
  for (const p of pubs) {
    if (p.source_type === 'speech_training') trainingSourceIds.push(p.id);
    else contentSourceIds.push(p.id);
  }
  return { contentSourceIds, trainingSourceIds };
}

export async function retrieveScoped(
  query: string,
  scope: RetrievalScope,
  track: RetrievalTrack = 'content',
  limit = 5,
): Promise<RetrievalResult> {
  if (!query || !query.trim()) return { status: 'insufficient_scope', hits: [] };
  return createRetriever().retrieve(query, scope, { track, limit });
}

export interface RelevantCorpus {
  content: Passage[];
  training: Passage[];
  all: Passage[];
}

export interface RelevantEvidence {
  status: RetrievalResult['status'];
  content: RetrievalCandidate[];
  training: RetrievalCandidate[];
}

/** Candidatos ranqueados dos dois trilhos (para UI + ContextPack Fase 5). */
export async function getRelevantEvidence(blockText: string, limit = 5): Promise<RelevantEvidence> {
  const scope = await buildScopeFromLibrary();
  const retriever = createRetriever();
  const [contentRes, trainingRes] = await Promise.all([
    retriever.retrieve(blockText, scope, { track: 'content', limit }),
    retriever.retrieve(blockText, scope, { track: 'training', limit: 2 }),
  ]);
  const status =
    contentRes.status === 'ok' || trainingRes.status === 'ok'
      ? 'ok'
      : contentRes.status;
  return { status, content: contentRes.hits, training: trainingRes.hits };
}

export async function getRelevantCorpus(blockText: string, limit = 5): Promise<RelevantCorpus> {
  const ev = await getRelevantEvidence(blockText, limit);
  const content = ev.content.map((c) => c.passage);
  const training = ev.training.map((c) => c.passage);
  return { content, training, all: [...content, ...training].slice(0, limit) };
}

export async function getRelevantPassages(blockText: string, limit = 5): Promise<Passage[]> {
  const ev = await getRelevantEvidence(blockText, limit);
  return ev.content.map((c) => c.passage);
}

export interface EvidenceMeta {
  reference: string;
  relevance: number;
  track: RetrievalTrack;
  /** Fase 7: categoria efetiva (só no trilho training). */
  category?: TrainingCategory;
}

/** Metadados mínimos para a UI mostrar "Fonte · Relevância" (§19). */
export function candidatesToMeta(candidates: RetrievalCandidate[], track: RetrievalTrack): EvidenceMeta[] {
  return candidates.map((c) => {
    const meta: EvidenceMeta = {
      reference: formatProvenance(c.passage),
      relevance: Math.round(c.finalScore * 100) / 100,
      track,
    };
    if (track === 'training') {
      meta.category = trainingCategoryOf(c.passage.training_category, {
        symbol: c.passage.symbol,
        title: c.publicationTitle,
        section: c.passage.section,
        text: c.passage.text,
      });
    }
    return meta;
  });
}

export function formatProvenance(p: Passage): string {
  if (p.ref) return p.ref;
  const bits: string[] = [p.symbol || p.pubId];
  if (p.section) bits.push(p.section.slice(0, 40));
  else if (p.page) bits.push(`p. ${p.page}`);
  if (p.paragraph) bits.push(`§${p.paragraph}`);
  return bits.join(' ');
}

export function passagesToContextStrings(passages: Passage[]): string[] {
  return passages.slice(0, 5).map((p) => `[${formatProvenance(p)}] ${p.text}`.slice(0, 800));
}
