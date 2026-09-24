// Constrói ContextPack pequeno e relevante — nunca o corpus inteiro.
// Separa content_sources (publicações) de training_sources (BE/TH).

import type { Passage } from '../types/speech';
import type { ContextPack, CopilotMode, CopilotTask, EvidenceSource, SourceType } from './domain';

interface BuildInput {
  task: CopilotTask;
  mode?: CopilotMode;
  speechTitle: string;
  speechTimeLimitMinutes?: number;
  blockTitle?: string;
  blockText?: string;
  blockMinutes?: number;
  tone?: string;
  language?: string;
}

function passageToEvidence(p: Passage, fallbackRef: string): EvidenceSource {
  // Fase 2: usa metadata rica quando disponível; fallback para registros v2.
  const source_type: SourceType = p.source_type ?? guessSourceType(p.pubId);
  return {
    id: p.id,
    reference: p.ref || fallbackRef,
    text: p.text,
    source_type,
    publication: p.symbol || p.pubId,
    section: p.section,
    paragraph: p.paragraph,
    page: p.page,
    language: p.language ?? 'pt-BR',
  };
}

function guessSourceType(pubId: string): SourceType {
  const lowerId = (pubId || '').toLowerCase();
  if (lowerId.startsWith('be') || lowerId.startsWith('th') || lowerId.includes('beneficie') || lowerId.includes('melhore')) {
    return 'speech_training';
  }
  return 'publication';
}

export function buildContextPack(
  input: BuildInput,
  contentPassages: Passage[],
  trainingPassages: Passage[] = [],
): ContextPack {
  const content_sources: EvidenceSource[] = contentPassages.slice(0, 8).map((p, i) =>
    passageToEvidence(p, `Fonte ${i + 1}`),
  );
  const training_sources: EvidenceSource[] = trainingPassages.slice(0, 4).map((p, i) =>
    passageToEvidence(p, `Treinamento ${i + 1}`),
  );

  return {
    task: input.task,
    mode: input.mode ?? 'GROUNDED',
    speech: {
      title: input.speechTitle,
      time_limit_minutes: input.speechTimeLimitMinutes,
    },
    current_section: {
      title: input.blockTitle,
      text: (input.blockText || '').slice(0, 4000),
      minutes: input.blockMinutes,
    },
    content_sources,
    training_sources,
    user_preferences: {
      language: input.language ?? 'pt-BR',
      tone: input.tone,
    },
  };
}

/** Serializa ContextPack para injeção no prompt (seção ACERVO LOCAL legada). */
export function contextPackToPassageStrings(pack: ContextPack): string[] {
  const out: string[] = [];
  for (const s of pack.content_sources) {
    out.push(`[${s.reference}] ${s.text}`);
  }
  for (const s of pack.training_sources) {
    out.push(`[Orientação ${s.reference}] ${s.text}`);
  }
  return out;
}
