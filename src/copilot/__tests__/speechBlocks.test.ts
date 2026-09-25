// Regressão P0 Fase 12: fusão de patch em chamada única (sem clobber no batch).
import { describe, expect, it } from 'vitest';
import type { Speech } from '../../types/speech';
import { applyBlockPatch } from '../../services/speechBlocks';

function speech(): Speech {
  return {
    id: 's1', title: 'T', contentHtml: '<h2>A</h2><p>um</p><hr/><h2>B</h2><p>dois</p>',
    plainText: 'um dois', targetDurationMinutes: 10, targetWpm: 130,
    category: 'geral', tags: [], createdAt: 1, updatedAt: 1,
    blocks: [
      { id: 'b1', speechId: 's1', order: 0, minutes: 5, title: 'A', contentHtml: '<p>um</p>', plainText: 'um' },
      { id: 'b2', speechId: 's1', order: 1, minutes: 5, title: 'B', contentHtml: '<p>dois</p>', plainText: 'dois' },
    ],
  };
}

describe('applyBlockPatch (P0 digitação)', () => {
  it('aplica patch no bloco certo preservando os demais + ordem', () => {
    const { blocks, contentHtml } = applyBlockPatch(speech(), 'b2', {
      contentHtml: '<p>dois editado</p>', plainText: 'dois editado',
    });
    expect(blocks.map((b) => b.id)).toEqual(['b1', 'b2']);
    expect(blocks[0]).toMatchObject({ contentHtml: '<p>um</p>', plainText: 'um' });
    expect(blocks[1]).toMatchObject({ contentHtml: '<p>dois editado</p>', plainText: 'dois editado' });
    // Espelho: bloco editado como h2/p, demais com HTML original (badges preservados).
    expect(contentHtml).toBe('<p>um</p><hr/><h2>B</h2><p>dois editado</p>');
  });

  it('alvo inexistente: nada muda, espelho original', () => {
    const sp = speech();
    const { blocks, contentHtml } = applyBlockPatch(sp, 'ghost', { plainText: 'x' });
    expect(blocks).toEqual(sp.blocks);
    expect(contentHtml).toBe(sp.contentHtml);
  });

  it('patch parcial preserva campos não tocados', () => {
    const { blocks } = applyBlockPatch(speech(), 'b1', { minutes: 10 });
    expect(blocks[0].minutes).toBe(10);
    expect(blocks[0].title).toBe('A');
    expect(blocks[0].contentHtml).toBe('<p>um</p>');
  });
});
