// Modelo de análise estrutural — Fase 9. Observações explicáveis, sem nota global.

export const ANALYSIS_VERSION = 1;

export type StructureRole =
  | 'INTRODUCTION'
  | 'POINT'
  | 'EXPLANATION'
  | 'ILLUSTRATION'
  | 'APPLICATION'
  | 'TRANSITION'
  | 'CONCLUSION';

export type ObservationType =
  | 'INTRODUCTION'
  | 'POINT'
  | 'EXPLANATION'
  | 'ILLUSTRATION'
  | 'APPLICATION'
  | 'TRANSITION'
  | 'CONCLUSION'
  | 'REPETITION'
  | 'CLARITY'
  | 'NATURALNESS'
  | 'BALANCE'
  | 'TIME'
  | 'STRUCTURE'
  | 'QUESTIONS';

/** Prioridade operacional — nunca avaliação de qualidade. */
export type ObservationSeverity = 'INFO' | 'ATTENTION' | 'SUGGESTION';

export interface BlockAnalysis {
  blockId: string;
  title: string;
  roles: StructureRole[];
  wordCount: number;
  seconds: number;
  longestSentenceWords: number;
}

export interface BlockTime {
  blockId: string;
  seconds: number;
}

export interface Observation {
  id: string;
  type: ObservationType;
  severity: ObservationSeverity;
  blockIds: string[];
  message: string;
  reason: string;
  suggestion?: string;
}

export interface EstimatedTime {
  totalSeconds: number;
  formattedTotal: string;
  perBlock: BlockTime[];
  wpm: number;
  disclaimer: string;
}

export interface SpeechAnalysis {
  speechId: string;
  version: number;
  contentHash: string;
  /** Sequência de papéis por bloco (grafo linear INTRO → … → CONCLUSION). */
  flow: StructureRole[];
  structure: BlockAnalysis[];
  observations: Observation[];
  estimatedTime: EstimatedTime;
}

export const TIME_DISCLAIMER =
  'Estimativa baseada no texto; o tempo real depende da velocidade, pausas e interação durante a apresentação.';

export const DEFAULT_ANALYSIS_WPM = 130;
