// Intenção do Copilot → trilho/categoria (§13).
// Heurística documentada: qual trilha consultar por ação/modo.
// null = só conteúdo (ex: pedir base bíblica nunca toca BE/TH).

import type { EditProposalMode, TrainingCategory } from './domain';
import type { LlmAction } from './llmProvider';

export function trainingCategoryForAction(action: LlmAction): TrainingCategory | null {
  switch (action) {
    case 'hook':
      return 'introduction';
    case 'rewrite':
      return 'clarity';
    case 'critique':
      return 'delivery';
    case 'cues':
      return 'delivery';
    case 'shorten':
      return 'clarity';
  }
}

export function trainingCategoryForEditMode(mode: EditProposalMode): TrainingCategory | null {
  switch (mode) {
    case 'suggest':
      return null;
    case 'rewrite':
      return 'development';
    case 'improve':
      return 'clarity';
    case 'insert':
      return 'illustration';
    case 'delete':
      return null;
  }
}
