export type SpeechCategory =
  | 'ted'
  | 'pitch'
  | 'keynote'
  | 'debate'
  | 'cerimonia'
  | 'geral';

export type StageCueType =
  | 'pause-2s'
  | 'pause-3s'
  | 'pause-5s'
  | 'emphasis'
  | 'eye-contact'
  | 'whisper'
  | 'applause'
  | 'gesture'
  | 'pace-up'
  | 'pace-down';

export interface StageCueDefinition {
  id: StageCueType;
  label: string;
  icon: string;
  durationSeconds?: number;
  badgeClass: string;
  tooltip: string;
}

export type PublicationKind = 'epub' | 'pdf';
export type CitationStatus = 'missing' | 'resolving' | 'resolved';

/** Origem lógica do corpus. BE/TH = metodologia de oratória, não conteúdo. */
export type CorpusSourceType = 'publication' | 'speech_training' | 'bible' | 'user';

export interface SpeechBlock {
  id: string;
  speechId: string;
  order: number;
  minutes: number;
  title: string;
  contentHtml: string;
  plainText: string;
  sourceExcerpt?: string;
}

export interface Speech {
  id: string;
  title: string;
  contentHtml: string;
  plainText: string;
  targetDurationMinutes: number;
  targetWpm: number;
  category: SpeechCategory;
  tags: string[];
  createdAt: number;
  updatedAt: number;
  blocks: SpeechBlock[];
  sourceFileName?: string;
}

export interface Publication {
  id: string;
  fileName: string;
  title: string;
  kind: PublicationKind;
  localPath: string;
  addedAt: number;
  indexed: boolean;
  /** Fase 2: metadata rica (opcional p/ compat com registros v2). */
  symbol?: string;
  source_type?: CorpusSourceType;
  language?: string;
  year?: number;
  totalPassages?: number;
  fileSize?: number;
}

export interface Passage {
  id: string;
  pubId: string;
  ref: string;
  text: string;
  normalizedText: string;
  /** Fase 2: proveniência (opcional p/ compat com registros v2). */
  source_type?: CorpusSourceType;
  symbol?: string;
  section?: string;
  paragraph?: number;
  page?: number;
  order?: number;
  language?: string;
  /** Fase 7: categoria de treinamento (só quando source_type = speech_training). */
  training_category?: string;
}

export interface MissingCitation {
  raw: string;
  blockId: string;
  status: CitationStatus;
}

export interface SpeechMetrics {
  wordCount: number;
  characterCount: number;
  paragraphCount: number;
  pauseCount: number;
  totalPauseDurationSeconds: number;
  estimatedTimeSeconds: number;
  formattedEstimatedTime: string;
  differenceToTargetMinutes: number;
  readingEase: 'Fluida e Acessível' | 'Moderada' | 'Muito Complexa / Frases Longas';
  fillerWordsFound: { word: string; count: number }[];
  totalFillers: number;
  longestSentenceWordCount: number;
}

export interface CopilotSuggestion {
  id: string;
  type: 'hook' | 'analogy' | 'trim' | 'cadence' | 'rewrite' | 'climax' | 'critique' | 'quote';
  title: string;
  content: string;
  replacementText?: string;
  categoryLabel: string;
}

export interface AppSettings {
  geminiApiKey: string;
  defaultWpm: number;
  teleprompterFontSize: number;
  teleprompterMirrored: boolean;
}
