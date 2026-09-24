// Testes de operações e validação — Fase 5 (§21: operações, concorrência).

import { describe, expect, it } from 'vitest';
import type { Speech, SpeechBlock } from '../../types/speech';
import {
  applyEditProposal,
  captureBaseHashes,
  hashText,
  stripHtmlToText,
  validateEditProposal,
} from '../editProposal';
import type { CopilotEditProposal } from '../domain';

function block(id: string, html: string, order: number): SpeechBlock {
  return { id, speechId: 's1', order, minutes: 5, title: `Bloco ${id}`, contentHtml: html, plainText: stripHtmlToText(html) };
}

function speechWith(htmls: string[]): Speech {
  return {
    id: 's1', title: 'T', contentHtml: '', plainText: '', targetDurationMinutes: 5,
    targetWpm: 130, category: 'geral', tags: [], createdAt: 1, updatedAt: 1,
    blocks: htmls.map((h, i) => block(`b${i + 1}`, h, i)),
  };
}

function proposalFor(sp: Speech, mode: CopilotEditProposal['mode'], ops: CopilotEditProposal['operations']): CopilotEditProposal {
  return {
    id: 'prop-1', mode, operations: ops,
    baseHashes: captureBaseHashes(sp, ops.map((o) => o.targetId)),
    createdAt: 1,
  };
}

describe('edit operations', () => {
  it('replace válido aplica e atualiza plainText', () => {
    const sp = speechWith(['<p>Original aqui.</p>', '<p>Segundo.</p>']);
    const res = applyEditProposal(sp, proposalFor(sp, 'rewrite', [
      { type: 'replace', targetId: 'b1', contentHtml: '<p>Novo texto.</p>' },
    ]));
    expect(res.status).toBe('applied');
    expect(res.speech?.blocks[0].contentHtml).toBe('<p>Novo texto.</p>');
    expect(res.speech?.blocks[0].plainText).toBe('Novo texto.');
    expect(res.speech?.blocks[1].contentHtml).toBe('<p>Segundo.</p>');
    // Original intacto (sem mutação).
    expect(sp.blocks[0].contentHtml).toBe('<p>Original aqui.</p>');
  });

  it('insert before/after posiciona e reindexa order', () => {
    const sp = speechWith(['<p>A.</p>', '<p>B.</p>']);
    const res = applyEditProposal(sp, proposalFor(sp, 'insert', [
      { type: 'insert', targetId: 'b1', position: 'after', contentHtml: '<p>Novo.</p>' },
    ]));
    expect(res.status).toBe('applied');
    expect(res.speech?.blocks.map((b) => b.plainText)).toEqual(['A.', 'Novo.', 'B.']);
    expect(res.speech?.blocks.map((b) => b.order)).toEqual([0, 1, 2]);
  });

  it('delete válido remove o bloco', () => {
    const sp = speechWith(['<p>A.</p>', '<p>B.</p>']);
    const res = applyEditProposal(sp, proposalFor(sp, 'delete', [{ type: 'delete', targetId: 'b2' }]));
    expect(res.status).toBe('applied');
    expect(res.speech?.blocks.map((b) => b.id)).toEqual(['b1']);
  });

  it('alvo inexistente => invalid/unknown_target', () => {
    const sp = speechWith(['<p>A.</p>']);
    const p = proposalFor(sp, 'rewrite', [{ type: 'replace', targetId: 'ghost', contentHtml: '<p>X.</p>' }]);
    // baseHashes vazio para ghost; validação deve barrar pelo alvo.
    const v = validateEditProposal(sp, p);
    expect(v.ok).toBe(false);
    if (!v.ok) expect(v.error).toBe('unknown_target');
    expect(applyEditProposal(sp, p).status).toBe('invalid');
  });

  it('posição inválida => invalid', () => {
    const sp = speechWith(['<p>A.</p>']);
    const p = proposalFor(sp, 'insert', [
      { type: 'insert', targetId: 'b1', position: 'middle', contentHtml: '<p>X.</p>' } as never,
    ]);
    expect(applyEditProposal(sp, p).status).toBe('invalid');
  });

  it('conteúdo vazio => invalid', () => {
    const sp = speechWith(['<p>A.</p>']);
    const p = proposalFor(sp, 'rewrite', [{ type: 'replace', targetId: 'b1', contentHtml: '  ' }]);
    expect(applyEditProposal(sp, p).status).toBe('invalid');
  });

  it('deletar o último bloco viola invariante', () => {
    const sp = speechWith(['<p>Único.</p>']);
    const p = proposalFor(sp, 'delete', [{ type: 'delete', targetId: 'b1' }]);
    const v = validateEditProposal(sp, p);
    expect(v.ok).toBe(false);
    if (!v.ok) expect(v.error).toBe('last_block');
  });

  it('concorrência: bloco mudou após geração => stale_proposal, nada aplicado', () => {
    const sp = speechWith(['<p>Versão A.</p>', '<p>Outro.</p>']);
    const p = proposalFor(sp, 'rewrite', [
      { type: 'replace', targetId: 'b1', contentHtml: '<p>Novo.</p>' },
    ]);
    const changed: Speech = {
      ...sp,
      blocks: sp.blocks.map((b) => (b.id === 'b1' ? { ...b, contentHtml: '<p>Versão B editada.</p>' } : b)),
    };
    const res = applyEditProposal(changed, p);
    expect(res.status).toBe('stale_proposal');
    expect(changed.blocks[0].contentHtml).toBe('<p>Versão B editada.</p>');
  });

  it('múltiplas operações são atômicas: falha numa => nada muda', () => {
    const sp = speechWith(['<p>A.</p>', '<p>B.</p>']);
    const p = proposalFor(sp, 'rewrite', [
      { type: 'replace', targetId: 'b1', contentHtml: '<p>Ok.</p>' },
      { type: 'replace', targetId: 'ghost', contentHtml: '<p>X.</p>' },
    ]);
    const res = applyEditProposal(sp, p);
    expect(res.status).toBe('invalid');
    expect(sp.blocks[0].contentHtml).toBe('<p>A.</p>');
  });

  it('hashText é determinístico e sensível a mudança', () => {
    expect(hashText('abc')).toBe(hashText('abc'));
    expect(hashText('abc')).not.toBe(hashText('abd'));
  });
});
