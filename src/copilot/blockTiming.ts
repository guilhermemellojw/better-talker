// Temporização por bloco — Fase 14. Função pura e determinística.
// Reutiliza a FÓRMULA de calculateSpeechMetrics (palavras/wpm + pausas),
// com extração de texto sem DOM (badges de palco removidos antes da conta,
// como a versão DOM). UI e testes usam esta função; nada duplicado em lógica.

import { stripHtmlToText } from './editProposal';
import type { SpeechBlock } from '../types/speech';

/** Tolerância documentada (§9): dentro de ±10s = na meta. */
export const TIMING_TOLERANCE_SECONDS = 10;

export type TimingStatus = 'no_target' | 'within_target' | 'over_target' | 'under_target';

export interface BlockTiming {
  estimatedSeconds: number;
  targetSeconds?: number;
  deltaSeconds?: number;
  status: TimingStatus;
}

const PAUSE_SECONDS: Record<string, number> = {
  'pause-2s': 2,
  'pause-3s': 3,
  'pause-5s': 5,
  applause: 4,
};

/** Remove badges de palco antes de contar palavras (espelha a versão DOM). */
export function stripStageBadges(html: string): string {
  return (html || '').replace(
    /<span[^>]*class="[^"]*stage-cue-badge[^"]*"[^>]*>[\s\S]*?<\/span>/gi,
    ' ',
  );
}

export function countPauseSeconds(contentHtml: string): number {
  let total = 0;
  const re = /data-cue-type="([^"]+)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(contentHtml || '')) !== null) {
    total += PAUSE_SECONDS[m[1]] ?? 0;
  }
  return total;
}

export function estimateBlockSeconds(contentHtml: string, wpm: number): number {
  const words = stripHtmlToText(stripStageBadges(contentHtml))
    .split(/\s+/)
    .filter(Boolean).length;
  const safeWpm = wpm > 0 ? wpm : 130;
  return Math.round((words / safeWpm) * 60 + countPauseSeconds(contentHtml));
}

/** Meta válida: inteiro positivo em segundos. 0/negativo = sem meta. */
export function normalizeTargetSeconds(value: number | undefined): number | undefined {
  if (value === undefined || value === null) return undefined;
  if (!Number.isFinite(value)) return undefined;
  const floored = Math.floor(value);
  return floored > 0 ? floored : undefined;
}

export function calculateBlockTiming(
  block: Pick<SpeechBlock, 'contentHtml'>,
  wpm: number,
  targetSeconds?: number,
): BlockTiming {
  const estimatedSeconds = estimateBlockSeconds(block.contentHtml, wpm);
  const target = normalizeTargetSeconds(targetSeconds);
  if (target === undefined) {
    return { estimatedSeconds, status: 'no_target' };
  }
  const deltaSeconds = estimatedSeconds - target;
  const status: TimingStatus =
    Math.abs(deltaSeconds) <= TIMING_TOLERANCE_SECONDS ? 'within_target' : deltaSeconds > 0 ? 'over_target' : 'under_target';
  return { estimatedSeconds, targetSeconds: target, deltaSeconds, status };
}

/** "90" → "1:30". Segundos sempre com 2 dígitos. */
export function formatDuration(totalSeconds: number): string {
  const sign = totalSeconds < 0 ? '-' : '';
  const abs = Math.abs(Math.round(totalSeconds));
  const m = Math.floor(abs / 60);
  const s = abs % 60;
  return `${sign}${m}:${s.toString().padStart(2, '0')}`;
}

/** "+0:18" / "-0:18" / "±0:00" para deltas. */
export function formatDelta(deltaSeconds: number): string {
  if (deltaSeconds === 0) return '±0:00';
  const prefix = deltaSeconds > 0 ? '+' : '-';
  return prefix + formatDuration(Math.abs(deltaSeconds));
}

/**
 * Entrada "mm:ss" → segundos (§6). Erro curto em PT para a UI.
 */
export function parseDurationInput(
  raw: string,
): { ok: true; seconds: number } | { ok: false; error: string } {
  const text = (raw || '').trim();
  if (!text) return { ok: false, error: 'Digite a meta como mm:ss.' };
  const match = /^(\d{1,3}):([0-5]\d)$/.exec(text);
  if (!match) return { ok: false, error: 'Use mm:ss, ex. 01:30.' };
  const seconds = parseInt(match[1], 10) * 60 + parseInt(match[2], 10);
  if (seconds <= 0) return { ok: false, error: 'Meta deve ser maior que 00:00.' };
  return { ok: true, seconds };
}

/**
 * Totais do discurso (§11): estimado soma todos; alvo soma SÓ blocos com meta.
 * Sem meta em nenhum bloco => targetTotalSeconds undefined (sem falsa meta).
 */
export interface SpeechTimingSummary {
  estimatedTotalSeconds: number;
  targetTotalSeconds?: number;
  deltaSeconds?: number;
}

export function summarizeSpeechTiming(
  blocks: Pick<SpeechBlock, 'contentHtml' | 'targetDurationSeconds'>[],
  wpm: number,
): SpeechTimingSummary {
  const estimatedTotalSeconds = blocks.reduce(
    (acc, b) => acc + estimateBlockSeconds(b.contentHtml, wpm),
    0,
  );
  const targets = blocks.map((b) => normalizeTargetSeconds(b.targetDurationSeconds));
  let targetTotalSeconds: number | undefined;
  for (const t of targets) {
    if (t !== undefined) targetTotalSeconds = (targetTotalSeconds ?? 0) + t;
  }
  if (targetTotalSeconds === undefined) {
    return { estimatedTotalSeconds };
  }
  return {
    estimatedTotalSeconds,
    targetTotalSeconds,
    deltaSeconds: estimatedTotalSeconds - targetTotalSeconds,
  };
}
