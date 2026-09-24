// Classificação de treinamento — Fase 7 (§§7,8,9).
// Determinística por metadata/regras; 'unknown' quando inseguro (nunca inventa).
// Importável pelo indexer (pura, sem IO) e pelo retrieval (backfill de v2).

import type { TrainingCategory } from './domain';
import { normalizeTokenText } from './tokenize';

export interface TrainingClassifiable {
  symbol?: string;
  title?: string;
  section?: string;
  text?: string;
}

// Padrões SEMPRE sem acentos: a entrada é normalizada (NFD sem diacríticos).
const RULES: Array<{ category: Exclude<TrainingCategory, 'unknown'>; patterns: RegExp }> = [
  { category: 'introduction', patterns: /(introducao|introduzir|abertura|comeco|comecar|iniciar|inicio|primeira impressao)/ },
  { category: 'questions', patterns: /(pergunta|questionar|perguntas de)/ },
  { category: 'development', patterns: /(desenvolvimento|desenvolver|pontos principais|estrutura|esboco|corpo do discurso)/ },
  { category: 'explanation', patterns: /(explicacao|explicar|expor|ensino|ensinar)/ },
  { category: 'illustration', patterns: /(ilustra|exemplo|analogia|historia|experiencia)/ },
  { category: 'application', patterns: /(aplicacao|aplicar|pratica|licao pratica)/ },
  { category: 'transition', patterns: /(transicao|transicoes|ligar|ponte|passar para)/ },
  { category: 'conclusion', patterns: /(conclusao|concluir|terminar|encerramento|recapitular|final)/ },
  { category: 'clarity', patterns: /(clareza|claro|simples|simplicidade|direto|conciso)/ },
  { category: 'naturalness', patterns: /(naturalidade|natural|modestia|sinceridade|calma)/ },
  { category: 'delivery', patterns: /(entrega|voz|gestos?|pausas?|ritmo|palco|volume|diccao|leitura|contato visual)/ },
];

/** Primeira regra que casa, na ordem acima; símbolo sozinho não classifica. */
export function classifyTraining(input: TrainingClassifiable): TrainingCategory {
  // Seção/título primeiro (sinal forte), corpo depois (sinal fraco).
  const heading = normalizeTokenText(`${input.section ?? ''} ${input.title ?? ''}`.slice(0, 300));
  if (heading.trim()) {
    for (const rule of RULES) {
      if (rule.patterns.test(heading)) return rule.category;
    }
  }
  const body = normalizeTokenText((input.text ?? '').slice(0, 2000));
  if (!body.trim()) return 'unknown';
  for (const rule of RULES) {
    if (rule.patterns.test(body)) return rule.category;
  }
  return 'unknown';
}

/** Categoria efetiva: metadata gravada ou classificação on-the-fly (backfill v2). */
export function trainingCategoryOf(stored: string | undefined, fallback: TrainingClassifiable): TrainingCategory {
  if (stored) return stored as TrainingCategory;
  return classifyTraining(fallback);
}
