// Intents estruturais do Copilot — Fase 9 (§22).
// Mapeia perguntas para a análise determinística ANTES de recorrer ao LLM.
// Respostas locais (local first); LLM só para reformular/gerar via Fase 5.

import { normalizeTokenText } from './tokenize';
import type { SpeechAnalysis } from './speechAnalysis';

export type StructuralIntent =
  | 'analyze_introduction'
  | 'analyze_structure'
  | 'analyze_transition'
  | 'analyze_conclusion'
  | 'analyze_balance'
  | 'estimate_time'
  | 'analyze_repetition'
  | 'analyze_clarity'
  | 'analyze_naturalness';

const INTENT_KEYWORDS: Array<{ intent: StructuralIntent; patterns: RegExp }> = [
  { intent: 'analyze_introduction', patterns: /(introducao|introduzir|abertura|comeco|inicio)/ },
  { intent: 'analyze_conclusion', patterns: /(conclusao|concluir|encerramento|final|termino|terminar)/ },
  { intent: 'analyze_transition', patterns: /(transicao|transicoes|ligacao|ligar|ponte|passagem|fluidez entre)/ },
  { intent: 'analyze_balance', patterns: /(equilibrio|equilibr|balance|longo demais|muito longo|tamanho|proporcao|desbalance)/ },
  { intent: 'estimate_time', patterns: /(tempo|duracao|minutos|quanto tempo|demora|durar)/ },
  { intent: 'analyze_repetition', patterns: /(repeti|repete|repetindo|mesma ideia|redundan)/ },
  { intent: 'analyze_clarity', patterns: /(clareza|claro|clara|confuso|compreens|entender|objetivo)/ },
  { intent: 'analyze_naturalness', patterns: /(natural|naturalidade|artificial|formal demais|soa|sonoridade)/ },
  { intent: 'analyze_structure', patterns: /(estrutura|organiz|pontos|esboco|esqueleto|ordem|sequencia)/ },
];

export function intentForQuestion(question: string): StructuralIntent | null {
  const norm = ` ${normalizeTokenText(question)} `;
  for (const { intent, patterns } of INTENT_KEYWORDS) {
    if (patterns.test(norm)) return intent;
  }
  return null;
}

/** Tipos de observação relevantes por intent. */
export function observationTypesFor(intent: StructuralIntent): string[] {
  switch (intent) {
    case 'analyze_introduction':
      return ['INTRODUCTION', 'STRUCTURE'];
    case 'analyze_conclusion':
      return ['CONCLUSION'];
    case 'analyze_transition':
      return ['TRANSITION'];
    case 'analyze_balance':
      return ['BALANCE', 'POINT'];
    case 'estimate_time':
      return ['TIME'];
    case 'analyze_repetition':
      return ['REPETITION'];
    case 'analyze_clarity':
      return ['CLARITY'];
    case 'analyze_naturalness':
      return ['NATURALNESS'];
    case 'analyze_structure':
      return ['INTRODUCTION', 'POINT', 'CONCLUSION', 'STRUCTURE', 'BALANCE', 'TIME'];
  }
}

/** Resposta local a partir da análise (sem LLM). */
export function answerStructuralIntent(
  intent: StructuralIntent,
  analysis: SpeechAnalysis,
  blockTitleOf: (blockId: string) => string,
): string {
  if (intent === 'estimate_time') {
    const lines = analysis.estimatedTime.perBlock.map(
      (b) => `• ${blockTitleOf(b.blockId)} → ~${b.seconds}s`,
    );
    return `Tempo estimado: ${analysis.estimatedTime.formattedTotal} (ritmo ${analysis.estimatedTime.wpm} ppm).\n${lines.join('\n')}\n${analysis.estimatedTime.disclaimer}`;
  }
  const types = new Set(observationTypesFor(intent));
  const relevant = analysis.observations.filter((o) => types.has(o.type));
  if (relevant.length === 0) {
    return 'Nada a observar nesse aspecto: a análise não encontrou pontos de atenção.';
  }
  return relevant
    .map((o) => {
      const glyph = o.severity === 'ATTENTION' ? '⚠' : o.severity === 'SUGGESTION' ? '💡' : '✓';
      const blocks = o.blockIds.map((id) => `“${blockTitleOf(id)}”`).join(', ');
      return `${glyph} ${o.message}${blocks ? ` [${blocks}]` : ''}\nPor quê: ${o.reason}${o.suggestion ? `\nSugestão: ${o.suggestion}` : ''}`;
    })
    .join('\n\n');
}
