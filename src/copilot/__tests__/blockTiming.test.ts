// Timing por bloco — Fase 14 (§§18,19). Função pura, determinística.
import { describe, expect, it } from 'vitest';
import {
  calculateBlockTiming,
  countPauseSeconds,
  estimateBlockSeconds,
  formatDelta,
  formatDuration,
  normalizeTargetSeconds,
  parseDurationInput,
  stripStageBadges,
  summarizeSpeechTiming,
  TIMING_TOLERANCE_SECONDS,
} from '../blockTiming';
import { applyBlockPatch } from '../../services/speechBlocks';
import type { Speech } from '../../types/speech';

const words = (n: number) => Array.from({ length: n }, (_, i) => `w${i}`).join(' ');
const html = (text: string) => `<p>${text}</p>`;

describe('calculateBlockTiming', () => {
  it('1. bloco sem meta', () => {
    const r = calculateBlockTiming({ contentHtml: html(words(130)) }, 130, undefined);
    expect(r.estimatedSeconds).toBe(60);
    expect(r.status).toBe('no_target');
    expect(r.targetSeconds).toBeUndefined();
  });

  it('2. meta válida com estimativa', () => {
    const r = calculateBlockTiming({ contentHtml: html(words(130)) }, 130, 90);
    expect(r.estimatedSeconds).toBe(60);
    expect(r.targetSeconds).toBe(90);
  });

  it('3. estimado igual à meta', () => {
    const r = calculateBlockTiming({ contentHtml: html(words(130)) }, 130, 60);
    expect(r.status).toBe('within_target');
    expect(r.deltaSeconds).toBe(0);
  });

  it('4. estimado acima', () => {
    const r = calculateBlockTiming({ contentHtml: html(words(260)) }, 130, 60);
    expect(r.status).toBe('over_target');
    expect(r.deltaSeconds).toBe(60);
  });

  it('5. estimado abaixo', () => {
    const r = calculateBlockTiming({ contentHtml: html(words(65)) }, 130, 120);
    expect(r.status).toBe('under_target');
    expect(r.deltaSeconds).toBe(-90);
  });

  it('6. tolerância de ±10 segundos', () => {
    expect(TIMING_TOLERANCE_SECONDS).toBe(10);
    // 130 palavras a 130ppm = 60s; meta 70 => delta -10 => dentro.
    expect(calculateBlockTiming({ contentHtml: html(words(130)) }, 130, 70).status).toBe('within_target');
    // meta 71 => delta -11 => abaixo.
    expect(calculateBlockTiming({ contentHtml: html(words(130)) }, 130, 71).status).toBe('under_target');
    // 143 palavras ≈ 66s; meta 55 => delta +11 => acima.
    expect(calculateBlockTiming({ contentHtml: html(words(143)) }, 130, 55).status).toBe('over_target');
  });

  it('7. valores negativos inválidos', () => {
    expect(normalizeTargetSeconds(-30)).toBeUndefined();
    expect(normalizeTargetSeconds(Number.NaN)).toBeUndefined();
  });

  it('8. meta 0', () => {
    expect(normalizeTargetSeconds(0)).toBeUndefined();
    expect(calculateBlockTiming({ contentHtml: html(words(10)) }, 130, 0).status).toBe('no_target');
  });

  it('9. alteração do conteúdo muda o estimado', () => {
    const before = calculateBlockTiming({ contentHtml: html(words(65)) }, 130, 60);
    const after = calculateBlockTiming({ contentHtml: html(words(65) + ' ' + words(65)) }, 130, 60);
    expect(after.estimatedSeconds).toBeGreaterThan(before.estimatedSeconds);
  });

  it('10. alteração do WPM muda o estimado', () => {
    const block = { contentHtml: html(words(120)) };
    expect(calculateBlockTiming(block, 120).estimatedSeconds).toBe(60);
    expect(calculateBlockTiming(block, 60).estimatedSeconds).toBe(120);
  });
});

describe('pausas e badges', () => {
  it('pausas somam ao estimado; texto do badge não conta palavra', () => {
    const htmlWithBadge =
      `<p>${words(130)}</p><span class="stage-cue-badge cue-pause" data-cue-type="pause-2s">pausa</span>`;
    expect(countPauseSeconds(htmlWithBadge)).toBe(2);
    expect(stripStageBadges(htmlWithBadge)).not.toContain('pausa');
    expect(estimateBlockSeconds(htmlWithBadge, 130)).toBe(62);
  });
});

describe('formatos', () => {
  it('formatDuration', () => {
    expect(formatDuration(30)).toBe('0:30');
    expect(formatDuration(60)).toBe('1:00');
    expect(formatDuration(90)).toBe('1:30');
    expect(formatDuration(345)).toBe('5:45');
    expect(formatDuration(0)).toBe('0:00');
  });

  it('formatDelta', () => {
    expect(formatDelta(18)).toBe('+0:18');
    expect(formatDelta(-18)).toBe('-0:18');
    expect(formatDelta(0)).toBe('±0:00');
  });

  it('parseDurationInput', () => {
    expect(parseDurationInput('00:30')).toEqual({ ok: true, seconds: 30 });
    expect(parseDurationInput('01:00')).toEqual({ ok: true, seconds: 60 });
    expect(parseDurationInput('01:30')).toEqual({ ok: true, seconds: 90 });
    expect(parseDurationInput('05:45')).toEqual({ ok: true, seconds: 345 });
    expect(parseDurationInput(' 02:05 ')).toEqual({ ok: true, seconds: 125 });
    expect(parseDurationInput('-01:00').ok).toBe(false);
    expect(parseDurationInput('abc').ok).toBe(false);
    expect(parseDurationInput('1:99').ok).toBe(false);
    expect(parseDurationInput('').ok).toBe(false);
    expect(parseDurationInput('00:00').ok).toBe(false);
  });
});

describe('resumo do discurso (§11)', () => {
  const mk = (text: string, target?: number) => ({ contentHtml: html(text), targetDurationSeconds: target });

  it('alvo total soma SÓ blocos com meta', () => {
    const r = summarizeSpeechTiming(
      [mk(words(130), 120), mk(words(130), undefined), mk(words(130), 60)],
      130,
    );
    expect(r.estimatedTotalSeconds).toBe(180);
    expect(r.targetTotalSeconds).toBe(180);
    expect(r.deltaSeconds).toBe(0);
  });

  it('sem metas: sem falsa meta total', () => {
    const r = summarizeSpeechTiming([mk(words(130)), mk(words(65))], 130);
    expect(r.estimatedTotalSeconds).toBe(90);
    expect(r.targetTotalSeconds).toBeUndefined();
    expect(r.deltaSeconds).toBeUndefined();
  });
});

describe('persistência da meta (§§14,15,19)', () => {
  const speech = (): Speech => ({
    id: 's1', title: 'T', contentHtml: '', plainText: '', targetDurationMinutes: 10,
    targetWpm: 130, category: 'geral', tags: [], createdAt: 1, updatedAt: 1,
    blocks: [
      { id: 'b1', speechId: 's1', order: 0, minutes: 5, title: 'A', contentHtml: '<p>a</p>', plainText: 'a' },
    ],
  });

  it('definir meta sobrevive a merge + round-trip JSON (reload)', () => {
    const withTarget = applyBlockPatch(speech(), 'b1', { targetDurationSeconds: 90 });
    expect(withTarget.blocks[0].targetDurationSeconds).toBe(90);
    const revived = JSON.parse(JSON.stringify({ blocks: withTarget.blocks })) as Speech;
    expect(revived.blocks[0].targetDurationSeconds).toBe(90);
  });

  it('alterar meta persiste novo valor', () => {
    const once = applyBlockPatch(speech(), 'b1', { targetDurationSeconds: 90 });
    const twice = applyBlockPatch({ ...speech(), blocks: once.blocks }, 'b1', { targetDurationSeconds: 120 });
    expect(twice.blocks[0].targetDurationSeconds).toBe(120);
  });

  it('remover meta (undefined) persiste ausência', () => {
    const once = applyBlockPatch(speech(), 'b1', { targetDurationSeconds: 90 });
    const cleared = applyBlockPatch(
      { ...speech(), blocks: once.blocks }, 'b1', { targetDurationSeconds: undefined },
    );
    expect(cleared.blocks[0].targetDurationSeconds).toBeUndefined();
    expect(JSON.parse(JSON.stringify(cleared.blocks[0]))).not.toHaveProperty('targetDurationSeconds');
  });

  it('editar conteúdo preserva a meta', () => {
    const once = applyBlockPatch(speech(), 'b1', { targetDurationSeconds: 90 });
    const edited = applyBlockPatch(
      { ...speech(), blocks: once.blocks }, 'b1', { contentHtml: '<p>novo</p>', plainText: 'novo' },
    );
    expect(edited.blocks[0].targetDurationSeconds).toBe(90);
    expect(edited.blocks[0].plainText).toBe('novo');
  });

  it('bloco antigo sem o campo continua válido (sem meta)', () => {
    const legacy = speech();
    delete (legacy.blocks[0] as Partial<typeof legacy.blocks[0]>).targetDurationSeconds;
    const r = calculateBlockTiming(legacy.blocks[0], 130, legacy.blocks[0].targetDurationSeconds);
    expect(r.status).toBe('no_target');
  });
});
