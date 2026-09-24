// Testes de histórico e parser — Fase 5 (§21: histórico, modos, provider).

import { describe, expect, it } from 'vitest';
import type { Speech, SpeechBlock } from '../../types/speech';
import { EditHistory } from '../editHistory';
import { applyEditProposal, captureBaseHashes, stripHtmlToText } from '../editProposal';
import { parseEditProposal } from '../proposalParser';
import { buildLlmPrompt } from '../llmPrompt';
import { FakeLlmProvider } from '../fakeProvider';
import { buildContextPack } from '../contextPack';
import { FIX_PASSAGES } from './fixtures';
import type { CopilotEditProposal } from '../domain';

function blocks(htmls: string[]): SpeechBlock[] {
  return htmls.map((h, i) => ({
    id: `b${i + 1}`, speechId: 's1', order: i, minutes: 5,
    title: `Bloco b${i + 1}`, contentHtml: h, plainText: stripHtmlToText(h),
  }));
}

function speechWith(htmls: string[]): Speech {
  return {
    id: 's1', title: 'T', contentHtml: '', plainText: '', targetDurationMinutes: 5,
    targetWpm: 130, category: 'geral', tags: [], createdAt: 1, updatedAt: 1, blocks: blocks(htmls),
  };
}

describe('edit history', () => {
  it('accept → undo restaura o estado anterior', () => {
    const sp = speechWith(['<p>A.</p>']);
    const p: CopilotEditProposal = {
      id: 'p1', mode: 'rewrite',
      operations: [{ type: 'replace', targetId: 'b1', contentHtml: '<p>B.</p>' }],
      baseHashes: captureBaseHashes(sp, ['b1']), createdAt: 1,
    };
    const applied = applyEditProposal(sp, p);
    expect(applied.status).toBe('applied');
    const history = new EditHistory();
    history.push(sp.blocks);
    const undone = history.undo(applied.speech!.blocks);
    expect(undone?.[0].contentHtml).toBe('<p>A.</p>');
    expect(history.canRedo).toBe(true);
  });

  it('accept → undo → redo volta ao aplicado', () => {
    const sp = speechWith(['<p>A.</p>']);
    const history = new EditHistory();
    history.push(sp.blocks);
    const after: SpeechBlock[] = [{ ...sp.blocks[0], contentHtml: '<p>B.</p>' }];
    const undone = history.undo(after);
    expect(undone?.[0].contentHtml).toBe('<p>A.</p>');
    const redone = history.redo(undone!);
    expect(redone?.[0].contentHtml).toBe('<p>B.</p>');
    expect(history.canRedo).toBe(false);
  });

  it('proposta com 2 ops é 1 ação: undo desfaz tudo', () => {
    const sp = speechWith(['<p>A.</p>', '<p>B.</p>']);
    const p: CopilotEditProposal = {
      id: 'p1', mode: 'rewrite',
      operations: [
        { type: 'replace', targetId: 'b1', contentHtml: '<p>A2.</p>' },
        { type: 'insert', targetId: 'b1', position: 'after', contentHtml: '<p>Novo.</p>' },
      ],
      baseHashes: captureBaseHashes(sp, ['b1']), createdAt: 1,
    };
    const applied = applyEditProposal(sp, p);
    expect(applied.status).toBe('applied');
    expect(applied.speech?.blocks).toHaveLength(3);
    const history = new EditHistory();
    history.push(sp.blocks);
    const undone = history.undo(applied.speech!.blocks);
    expect(undone?.map((b) => b.plainText)).toEqual(['A.', 'B.']);
  });

  it('reject não cria histórico (nada registrado sem push)', () => {
    const history = new EditHistory();
    expect(history.depth).toBe(0);
    expect(history.canUndo).toBe(false);
    expect(history.undo(blocks(['<p>A.</p>']))).toBeNull();
  });

  it('nova edição após undo descarta o redo', () => {
    const history = new EditHistory();
    const a = blocks(['<p>A.</p>']);
    const b = blocks(['<p>B.</p>']);
    history.push(a);
    history.undo(b);
    expect(history.canRedo).toBe(true);
    history.push(b); // nova alteração
    expect(history.canRedo).toBe(false);
  });
});

describe('proposal parser e modos', () => {
  const sp = speechWith(['<p>Original.</p>']);

  it('cerca json válida vira proposta com alvo coagido', () => {
    const raw = '```json\n{"explanation": "Nova versão", "operations": [{"type": "replace", "targetId": "outro-bloco", "content": "<p>Novo.</p>"}]}\n```';
    const res = parseEditProposal(raw, sp, 'b1', 'rewrite');
    expect(res.ok).toBe(true);
    if (res.ok) {
      expect(res.proposal.operations).toHaveLength(1);
      // Alvo do app, não do modelo (§18).
      expect(res.proposal.operations[0].targetId).toBe('b1');
      expect(res.proposal.explanation).toBe('Nova versão');
    }
  });

  it('JSON inválido ou sem operations => invalid_proposal', () => {
    expect(parseEditProposal('texto livre sem json', sp, 'b1', 'rewrite').ok).toBe(false);
    expect(parseEditProposal('```json\n{"explanation":"x"}\n```', sp, 'b1', 'rewrite').ok).toBe(false);
    expect(parseEditProposal('```json\n{quebrado\n```', sp, 'b1', 'rewrite').ok).toBe(false);
  });

  it('delete via LLM é vetado; modo delete é local', () => {
    const raw = '```json\n{"operations": [{"type": "delete"}]}\n```';
    expect(parseEditProposal(raw, sp, 'b1', 'rewrite').ok).toBe(false);
    const local = parseEditProposal('', sp, 'b1', 'delete');
    expect(local.ok).toBe(true);
  });

  it('alvo inexistente => invalid_proposal', () => {
    const raw = '```json\n{"operations": [{"type": "replace", "content": "<p>X.</p>"}]}\n```';
    expect(parseEditProposal(raw, sp, 'ghost', 'rewrite').ok).toBe(false);
  });

  it('todos os modos de edição geram instrução JSON', () => {
    for (const mode of ['rewrite', 'improve', 'insert'] as const) {
      const { user } = buildLlmPrompt({
        text: 'bloco', action: 'rewrite', responseFormat: 'edit-proposal', editMode: mode,
      });
      expect(user).toContain('```json');
      expect(user).toContain('"operations"');
    }
  });

  it('provider retorna proposta estruturada sem mutar o editor', async () => {
    const fake = new FakeLlmProvider({
      rewrite: '```json\n{"explanation": "ok", "operations": [{"type": "replace", "content": "<p>Novo.</p>"}]}\n```',
    });
    const before = JSON.stringify(sp.blocks);
    const res = await fake.generate({ text: 'x', action: 'rewrite', responseFormat: 'edit-proposal', editMode: 'rewrite' });
    const parsed = parseEditProposal(res.text, sp, 'b1', 'rewrite');
    expect(parsed.ok).toBe(true);
    // O discurso não foi tocado pela chamada ao provider.
    expect(JSON.stringify(sp.blocks)).toBe(before);
  });

  it('proposta recebe só o contexto autorizado do ContextPack', () => {
    const content = FIX_PASSAGES.filter((p) => p.pubId === 'pub-w24').slice(0, 1);
    const pack = buildContextPack({ task: 'research', speechTitle: 'T', blockText: 'x' }, content, []);
    const { user } = buildLlmPrompt({
      text: 'bloco', action: 'rewrite', contextPack: pack,
      responseFormat: 'edit-proposal', editMode: 'rewrite',
    });
    expect(user).toContain('FONTES DE CONTEÚDO');
    expect(user).toContain('w24 Confiança');
    expect(user).not.toContain('be Ilustrações');
  });

  it('sem evidência, edição também carrega a frase de insuficiência', () => {
    const { system, user } = buildLlmPrompt({
      text: 'bloco', action: 'rewrite', responseFormat: 'edit-proposal', editMode: 'insert',
    });
    expect(system).toContain('Não encontrei suporte suficiente nas fontes disponíveis.');
    expect(user).toContain('Nenhuma fonte do acervo local foi recuperada');
    expect(user).toContain('Nunca invente fatos');
  });
});
