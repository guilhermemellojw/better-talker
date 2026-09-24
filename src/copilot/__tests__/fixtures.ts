// Fixtures determinísticas para testes do retrieval — Fase 3 (§17/§18).
// Corpus pequeno e fixo; nada de IndexedDB aqui (store em memória).

import type { Passage, Publication } from '../../types/speech';
import type { PassageStore } from '../retrievalTypes';

export const FIX_PUBS: Publication[] = [
  {
    id: 'pub-be', fileName: 'be - Beneficie-se.epub', title: 'Beneficie-se',
    kind: 'epub', localPath: '', addedAt: 1, indexed: true,
    symbol: 'be', source_type: 'speech_training', language: 'pt-BR',
  },
  {
    id: 'pub-th', fileName: 'th - Melhore.pdf', title: 'Melhore',
    kind: 'pdf', localPath: '', addedAt: 2, indexed: true,
    symbol: 'th', source_type: 'speech_training', language: 'pt-BR',
  },
  {
    id: 'pub-w24', fileName: 'w24.01.epub', title: 'A Sentinela Janeiro de 2024',
    kind: 'epub', localPath: '', addedAt: 3, indexed: true,
    symbol: 'w24', source_type: 'publication', language: 'pt-BR',
  },
  {
    id: 'pub-g', fileName: 'g24.01.epub', title: 'Despertai Janeiro de 2024',
    kind: 'epub', localPath: '', addedAt: 4, indexed: true,
    symbol: 'g24', source_type: 'publication', language: 'pt-BR',
  },
];

function norm(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim();
}

function mk(
  id: string, pubId: string, text: string,
  extra: Partial<Passage> = {},
): Passage {
  return {
    id, pubId, ref: '', text, normalizedText: norm(text),
    source_type: FIX_PUBS.find((p) => p.id === pubId)?.source_type,
    symbol: FIX_PUBS.find((p) => p.id === pubId)?.symbol,
    language: 'pt-BR', ...extra,
  };
}

export const FIX_PASSAGES: Passage[] = [
  mk('p-be-1', 'pub-be', 'Use ilustrações simples do cotidiano para tornar o ensino claro e memorável.', {
    ref: 'be Ilustrações §1', section: 'Ilustrações', paragraph: 1, order: 0,
  }),
  mk('p-be-2', 'pub-be', 'Faça transições breves entre os pontos principais para manter a atenção.', {
    ref: 'be Transições §2', section: 'Transições', paragraph: 2, order: 1,
  }),
  mk('p-th-1', 'pub-th', 'Module a voz e faça pausas estratégicas antes das frases de impacto.', {
    ref: 'th Entrega §1', section: 'Entrega', paragraph: 1, order: 0,
  }),
  mk('p-w24-1', 'pub-w24', 'Confiar em Jeová nos ajuda a enfrentar problemas graves com coragem.', {
    ref: 'w24 Confiança §3', section: 'Confiança', paragraph: 3, order: 0,
  }),
  mk('p-w24-2', 'pub-w24', 'A oração sincera fortalece nossa amizade com Deus todos os dias.', {
    ref: 'w24 Oração §5', section: 'Oração', paragraph: 5, page: 12, order: 1,
  }),
  mk('p-w24-3', 'pub-w24', 'Estudar a Bíblia em família cria momentos de alegria e união.', {
    ref: 'w24 Família §2', section: 'Família', paragraph: 2, order: 2,
  }),
  // Altamente relevante para "confiança", mas FORA do escopo padrão dos testes.
  mk('p-g-1', 'pub-g', 'A confiança em Deus nos sustenta em tempos de ansiedade e medo.', {
    ref: 'g24 Ansiedade §1', section: 'Ansiedade', paragraph: 1, order: 0,
  }),
];

/** Escopo padrão: tudo exceto pub-g (fora de escopo de propósito). */
export const FIX_SCOPE = {
  contentSourceIds: ['pub-w24'],
  trainingSourceIds: ['pub-be', 'pub-th'],
};

export function fixtureStore(passages: Passage[] = FIX_PASSAGES): PassageStore {
  return {
    async getPassagesByIds(ids: string[], max: number): Promise<Passage[]> {
      return passages.filter((p) => ids.includes(p.pubId)).slice(0, max);
    },
    async getPublicationsByIds(ids: string[]): Promise<Publication[]> {
      return FIX_PUBS.filter((p) => ids.includes(p.id));
    },
  };
}
