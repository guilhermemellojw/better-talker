// Fluxo completo F9 → F5 → F6 — Fase 10 (§§3,4,5,6,26).
// Lógico (sem DOM): FakeLlmProvider + fixture store + histórico puro.

import { describe, expect, it } from 'vitest';
import type { Speech, SpeechBlock } from '../../types/speech';
import { analyzeSpeech } from '../speechAnalyzer';
import { FakeLlmProvider } from '../fakeProvider';
import { parseEditProposal } from '../proposalParser';
import { applyEditProposal } from '../editProposal';
import { EditHistory } from '../editHistory';
import { verifyText, clearVerificationCache } from '../verifier';
import { FIX_SCOPE, fixtureStore } from './fixtures';

function block(id: string, title: string, text: string): SpeechBlock {
  return { id, speechId: 's1', order: 0, minutes: 5, title, contentHtml: `<p>${text}</p>`, plainText: text };
}

function speechOf(blocks: SpeechBlock[]): Speech {
  return {
    id: 's1', title: 'Confiança em Jeová', contentHtml: '', plainText: '',
    targetDurationMinutes: 10, targetWpm: 130, category: 'geral', tags: [],
    createdAt: 1, updatedAt: 1, blocks,
  };
}

const INTRO = 'O que significa confiar em Jeová hoje? Hoje vamos ver o objetivo deste discurso sobre confiança.';
const BODY_A = 'A confiança sincera em Jeová fortalece a coragem dos servos leais em tempos de provação e aflição constante.';
const BODY_B = 'A oração sincera fortalece a amizade com Deus todos os dias sem exceção.';

describe('fluxo end-to-end F9 → F5 → F6', () => {
  it('análise → observação → sugestão → proposta → verificação → aceitar → persistir', async () => {
    clearVerificationCache();
    // Abrir discurso + carregar blocos.
    const bolo = 'Receitas de bolo de cenoura com cobertura crocante, forno pré-aquecido e fermento em pó. Misture tudo com calma e leve para assar por quarenta minutos.';
    let speech = speechOf([
      block('b1', 'Abertura', INTRO),
      block('b2', 'Ponto', `${BODY_A} Detalhes adicionais para estender o bloco com conteúdo relevante.`),
      block('b3', 'Receita', bolo),
    ]);
    expect(speech.blocks).toHaveLength(3);

    // Análise F9 encontra transição/estrutura (blocos distintos, sem conector).
    const analysis = analyzeSpeech(speech, { force: true });
    expect(analysis.observations.length).toBeGreaterThan(0);

    // Selecionar observação com sugestão (transição fraca ou dica).
    const actionable =
      analysis.observations.find((o) => o.suggestion) ??
      analysis.observations.find((o) => o.severity !== 'INFO');
    expect(actionable).toBeDefined();

    // Gerar sugestão via provider fake (JSON estruturado determinístico).
    const fake = new FakeLlmProvider({
      rewrite: '```json\n{"explanation": "Transição suavizada", "operations": [{"type": "replace", "content": "<p>Texto melhorado com ligação clara.</p>"}]}\n```',
    });
    const targetId = actionable!.blockIds[0] ?? 'b2';
    const target = speech.blocks.find((b) => b.id === targetId)!;
    const res = await fake.generate({
      text: target.plainText, action: 'rewrite', responseFormat: 'edit-proposal',
      editMode: 'rewrite', brief: actionable!.suggestion ?? 'Melhorar a ligação.',
    });

    // Criar proposta F5 (parse + validação implícita no apply).
    const parsed = parseEditProposal(res.text, speech, target.id, 'rewrite');
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;

    // Verificar F6 o conteúdo proposto antes do aceite.
    const proposedText = 'Texto melhorado com ligação clara.';
    const verification = await verifyText({
      text: proposedText, blockId: target.id, scope: FIX_SCOPE, store: fixtureStore(), force: true,
    });
    expect(verification.claims.length).toBeGreaterThan(0);

    // Aceitar: aplicar + registrar histórico (persistência simulada = novo objeto).
    const history = new EditHistory();
    history.push(speech.blocks);
    const applied = applyEditProposal(speech, parsed.proposal);
    expect(applied.status).toBe('applied');
    speech = applied.speech!;
    expect(speech.blocks.find((b) => b.id === target.id)?.plainText).toContain('Texto melhorado');

    // Reabrir (reler) confirma a alteração.
    const reopened = JSON.parse(JSON.stringify(speech)) as Speech;
    expect(reopened.blocks.find((b) => b.id === target.id)?.plainText).toContain('Texto melhorado');

    // Undo/redo funcionam sobre o fluxo.
    const undone = history.undo(speech.blocks);
    expect(undone?.find((b) => b.id === target.id)?.plainText).toContain(target.plainText.slice(0, 20));
    const redone = history.redo(undone!);
    expect(redone?.find((b) => b.id === target.id)?.plainText).toContain('Texto melhorado');
  });

  it('rejeição: texto intacto, sem histórico, sem resíduo', async () => {
    const speech = speechOf([block('b1', 'A', BODY_A)]);
    const before = JSON.stringify(speech.blocks);
    const fake = new FakeLlmProvider({
      rewrite: '```json\n{"operations": [{"type": "replace", "content": "<p>Outro.</p>"}]}\n```',
    });
    const res = await fake.generate({ text: BODY_A, action: 'rewrite', responseFormat: 'edit-proposal', editMode: 'rewrite' });
    const parsed = parseEditProposal(res.text, speech, 'b1', 'rewrite');
    expect(parsed.ok).toBe(true);
    // Rejeitar = simplesmente não aplicar.
    const history = new EditHistory();
    expect(history.depth).toBe(0);
    expect(JSON.stringify(speech.blocks)).toBe(before);
    expect(history.canUndo).toBe(false);
  });

  it('stale: edição manual entre gerar e aceitar bloqueia', async () => {
    const speech = speechOf([block('b1', 'A', BODY_A)]);
    const fake = new FakeLlmProvider({
      rewrite: '```json\n{"operations": [{"type": "replace", "content": "<p>Novo.</p>"}]}\n```',
    });
    const res = await fake.generate({ text: BODY_A, action: 'rewrite', responseFormat: 'edit-proposal', editMode: 'rewrite' });
    const parsed = parseEditProposal(res.text, speech, 'b1', 'rewrite');
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    // Usuário edita manualmente antes de aceitar.
    const edited: Speech = {
      ...speech,
      blocks: speech.blocks.map((b) => (b.id === 'b1' ? { ...b, contentHtml: '<p>Editado à mão.</p>', plainText: 'Editado à mão.' } : b)),
    };
    const result = applyEditProposal(edited, parsed.proposal);
    expect(result.status).toBe('stale_proposal');
    expect(edited.blocks[0].plainText).toBe('Editado à mão.');
  });

  it('nova edição após undo invalida o redo', () => {
    const history = new EditHistory();
    const a = [block('b1', 'A', 'Versão A.')];
    const b = [block('b1', 'A', 'Versão B.')];
    const c = [block('b1', 'A', 'Versão C.')];
    history.push(a);
    expect(history.undo(b)?.[0].plainText).toBe('Versão A.');
    history.push(b); // nova edição
    expect(history.canRedo).toBe(false);
    history.push(c);
    expect(history.depth).toBe(2);
  });

  it('múltiplas operações atômicas desfazem juntas', () => {
    const speech = speechOf([block('b1', 'A', BODY_A), block('b2', 'B', BODY_B)]);
    const history = new EditHistory();
    history.push(speech.blocks);
    const res = applyEditProposal(speech, {
      id: 'p', mode: 'rewrite', operations: [
        { type: 'replace', targetId: 'b1', contentHtml: '<p>Novo A.</p>' },
        { type: 'insert', targetId: 'b1', position: 'after', contentHtml: '<p>Inserido.</p>' },
      ],
      baseHashes: {},
      createdAt: 1,
    });
    expect(res.status).toBe('applied');
    // baseHashes vazio = sem trava stale (compatível com propostas legadas).
    expect(res.speech?.blocks).toHaveLength(3);
    const undone = history.undo(res.speech!.blocks);
    expect(undone).toHaveLength(2);
  });
});
