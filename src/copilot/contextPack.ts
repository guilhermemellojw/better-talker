// Constrói ContextPack pequeno e relevante — nunca o corpus inteiro.
// Separa content_sources (publicações) de training_sources (BE/TH).

import type { Passage } from '../types/speech';
import type { ContextPack, CopilotMode, CopilotTask, EvidenceSource, SourceType, TrainingCategory } from './domain';
import type { RetrievalCandidate } from './retrievalTypes';
import { trainingCategoryOf } from './trainingClassifier';

export interface BuildInput {
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

/** Fase 3 (§13): converte candidato ranqueado preservando score/proveniência. */
export function candidateToEvidence(c: RetrievalCandidate, fallbackRef: string): EvidenceSource {
  const base = passageToEvidence(c.passage, fallbackRef);
  const ev: EvidenceSource = {
    ...base,
    score: c.finalScore,
    matchedTerms: c.matchedTerms,
    foundBy: [...c.foundBy],
  };
  // Fase 7: categoria efetiva (gravada ou classificada) chega à UI/prompt.
  if (base.source_type === 'speech_training') {
    const category: TrainingCategory = trainingCategoryOf(c.passage.training_category, {
      symbol: c.passage.symbol,
      title: c.publicationTitle,
      section: c.passage.section,
      text: c.passage.text,
    });
    ev.training_category = category;
  }
  return ev;
}

/** Fase 3 (§13): mesma forma do buildContextPack, a partir de candidatos. */
export function buildContextPackFromCandidates(
  input: BuildInput,
  content: RetrievalCandidate[],
  training: RetrievalCandidate[] = [],
): ContextPack {
  const pack = buildContextPack(input, [], []);
  pack.content_sources = content
    .slice(0, 8)
    .map((c, i) => candidateToEvidence(c, `Fonte ${i + 1}`));
  pack.training_sources = training
    .slice(0, 4)
    .map((c, i) => candidateToEvidence(c, `Treinamento ${i + 1}`));
  return pack;
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
