// Busca semântica — Fase 3 (§7): SOMENTE abstração + fallback determinístico.
// Não há embeddings locais nem infraestrutura vetorial no projeto; portanto
// nenhum score semântico real é calculado aqui (inventar scores violaria §7).
// O HybridRetriever aceita um SemanticScorer opcional; sem ele, usa o nulo
// abaixo (score 0) e o ranking recai em lexical + metadata — o peso
// semântico permanece configurado, mas contribui 0 até haver scorer real.

import type { Passage } from '../types/speech';

export interface SemanticScorer {
  readonly id: string;
  score(query: string, passage: Passage): number;
}

/** Fallback determinístico: sempre 0. */
export class NullSemanticScorer implements SemanticScorer {
  readonly id = 'null';
  score(): number {
    return 0;
  }
}
