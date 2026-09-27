import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { parseS34 } from '../s34Parser';
import {
  structuralContextOf,
  structuralContextFor,
  serializeStructuralContext,
  S34_PROMPT_RULES,
} from '../s34StructuralContext';
import { buildLlmPrompt, INSUFFICIENT_EVIDENCE_MESSAGE } from '../llmPrompt';
import { emptyContextPack, type EvidenceSource } from '../domain';
import type { LlmRequest } from '../llmProvider';
import { S34_FIXTURE_TEXT } from './s34Fixture';
import { scopeS34 } from '../s34StructuralRetrieval';
import { BetterTalkerDB } from '../../services/db';
import { saveS34Outline, getS34BySource, retrieveS34Structural } from '../s34Repository';

/**
 * Fase 19-B.5 — ContextPack estruturado + cláusula S-34 no prompt.
 * Espelho conceitual de android/.../S34ContextPackTest.kt.
 */

const trainingSource: EvidenceSource = {
  id: 't1',
  reference: 'Beneficie-se lição 5',
  text: 'fale com contato visual',
  source_type: 'speech_training',
  publication: 'be',
  training_category: 'delivery',
  score: 0.9,
  matchedTerms: [],
  foundBy: [],
};

function ctxFixture(
  opts: { sectionId?: string; sectionHint?: string; query?: string } = {},
  text: string = S34_FIXTURE_TEXT,
) {
  const doc = parseS34(text);
  return { doc, ctx: structuralContextFor(doc, opts) };
}

describe('s34StructuralContext (ponta a ponta pela persistência)', () => {
  let tdb: BetterTalkerDB;

  beforeEach(async () => {
    tdb = new BetterTalkerDB();
    await tdb.open();
  });
  afterEach(async () => {
    await tdb.delete();
  });

  it('persistência → retrieval → contexto → prompt', async () => {
    const doc = parseS34(S34_FIXTURE_TEXT);
    await saveS34Outline(tdb, doc, 'att-A');
    const persisted = (await getS34BySource(tdb, 'att-A'))!;
    const result = await retrieveS34Structural(tdb, 'att-A', {
      sectionHint: 'colocamos em prática',
      query: 'deixe mais natural',
    });
    const structural = structuralContextOf(result, persisted)!;
    const prompt = buildLlmPrompt({
      text: 'texto do bloco',
      action: 'chat',
      contextPack: emptyContextPack(),
      structural,
      chat: {
        message: 'O que preciso falar no ponto 2?',
        history: [],
        isFirstMessage: true,
      },
    } as LlmRequest);
    expect(prompt.user).toContain('Objetivo: Mostrar como a fé pode ser fortalecida');
    expect(prompt.user).toContain('2. A fé cresce quando colocamos em prática');
    expect(prompt.user).toContain('<= PONTO ATUAL');
    expect(prompt.user).toContain('Subponto 2:');
    expect(prompt.user).toContain('Tiago 2:17');
    expect(prompt.user).toContain('w24.02');
    expect(prompt.user).toContain('REGRAS DO S-34');
    // Sem S-34 persistido: legado, nada estrutural.
    const legacy = await retrieveS34Structural(tdb, 'att-sem-s34');
    expect(structuralContextOf(legacy, null)).toBeNull();
  });
});

describe('s34StructuralContext', () => {
  // ---------- Estrutura (§25.1-7) ----------

  it('tem id, título, objetivo e ordem', () => {
    const { doc, ctx } = ctxFixture({ sectionHint: 'colocamos em prática' });
    expect(ctx!.outlineId).toBe(doc.id);
    expect(ctx!.title).toBe('Como fortalecer a fé');
    expect(ctx!.objective).toBe('Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.');
    expect(ctx!.orderedSections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(ctx!.orderedSections.map((s) => s.title)).toEqual([
      'A fé precisa de uma base sólida',
      'A fé cresce quando colocamos em prática o que aprendemos',
      'Continue fortalecendo sua fé',
    ]);
  });

  it('ponto atual com subpontos e referências', () => {
    const { ctx } = ctxFixture({ sectionHint: 'colocamos em prática' });
    const cur = ctx!.currentSection!;
    expect(cur.id).toBe('sec-2');
    expect(cur.order).toBe(2);
    expect(cur.subsections.map((s) => s.order)).toEqual([1, 2]);
    expect(cur.references.some((r) => r.rawText.includes('Tiago'))).toBe(true);
    // A lista completa continua marcando o atual.
    expect(ctx!.orderedSections.filter((s) => s.isCurrent).map((s) => s.id)).toEqual(['sec-2']);
  });

  // ---------- Referências (§25.10-12) ----------

  it('referências pertencem ao ponto certo', () => {
    const { ctx } = ctxFixture({ sectionHint: 'colocamos em prática' });
    const refs = ctx!.currentSection!.references;
    expect(refs.some((r) => r.rawText.includes('Tiago 2:17') && r.type === 'bible')).toBe(true);
    expect(refs.some((r) => r.rawText.includes('w24.02') && r.type === 'publication')).toBe(true);
    expect(refs.some((r) => r.rawText.includes('João 17:17'))).toBe(false);
    expect(refs.some((r) => r.rawText.includes('Hebreus'))).toBe(false);
    expect(refs.some((r) => r.rawText.includes('w24.01'))).toBe(false);
    expect(refs.every((r) => r.ownerId === 'sec-2' || r.ownerId.startsWith('sec-2-'))).toBe(true);
  });

  it('referência de subponto aparece no subponto', () => {
    const text = S34_FIXTURE_TEXT.replace(
      '   b) Aplicar o que aprendemos',
      '   b) Aplicar o que aprendemos. Leia João 3:16.',
    );
    const { ctx } = ctxFixture({ sectionHint: 'colocamos em prática' }, text);
    expect(ctx!.currentSection!.subsections[1].references.some((r) => r.rawText.includes('João 3:16'))).toBe(
      true,
    );
    expect(ctx!.currentSection!.references.some((r) => r.rawText.includes('João 3:16'))).toBe(false);
  });

  // ---------- Isolamento (§25.13-14) ----------

  it('não mistura outlines nem pontos', () => {
    const a = parseS34(
      'S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado o suficiente.\n\n' +
        '1. CONFIANÇA (2 min)\n   Conteúdo A de confiança.\n\n' +
        '2. ORAÇÃO (2 min)\n   Conteúdo A de oração.',
    );
    const ctx = structuralContextOf(scopeS34(a, { sectionId: 'sec-2' }), a)!;
    const text = serializeStructuralContext(ctx);
    expect(ctx.outlineId).toBe(a.id);
    expect(text).not.toContain('Conteúdo A de confiança');
    expect(text).not.toContain('Conteúdo B de confiança');
    expect(text).toContain('Conteúdo A de oração');
  });

  // ---------- Ausência / estado explícito (§25.18-20, §31-32) ----------

  it('sem S-34 não gera bloco estrutural', () => {
    const r = { kind: 'no-outline' } as const;
    expect(structuralContextOf(r, null)).toBeNull();
  });

  it('objetivo ausente não vira objetivo falso', () => {
    const text = S34_FIXTURE_TEXT.replace(
      'Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n',
      '',
    );
    const { ctx } = ctxFixture({ sectionHint: 'colocamos em prática' }, text);
    expect(ctx!.objective).toBeNull();
    expect(serializeStructuralContext(ctx!)).not.toContain('Objetivo:');
  });

  it('section desconhecida não finge foco', () => {
    const { ctx } = ctxFixture({ sectionId: 'sec-99' });
    expect(ctx!.currentSection).toBeNull();
    expect(ctx!.focusState).toBe('unknown-section');
    const text = serializeStructuralContext(ctx!);
    expect(text).toContain('NÃO IDENTIFICADO');
    expect(text).not.toContain('<= PONTO ATUAL');
  });

  it('dica ambígua não finge foco', () => {
    const { ctx } = ctxFixture({ sectionHint: 'assunto nenhum corresponde' });
    expect(ctx!.currentSection).toBeNull();
    expect(ctx!.focusState).toBe('unmatched-hint');
  });

  // ---------- Ordem (§25.8-9) ----------

  it('score não altera a ordem estrutural', () => {
    const doc = parseS34(
      'S-34\n\nTema: T\n\nObjetivo:\nObjetivo detalhado o suficiente.\n\n' +
        '1. Primeiro (2 min)\n   neutro.\n\n' +
        '2. CONFIANÇA CONFIANÇA (2 min)\n   confiança confiança.\n\n' +
        '3. Terceiro (2 min)\n   neutro.',
    );
    const ctx = structuralContextFor(doc, { query: 'confiança' })!;
    expect(ctx.orderedSections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(ctx.orderedSections.map((s) => s.id)).toEqual(['sec-1', 'sec-2', 'sec-3']);
  });

  // ---------- CONTENT × TRAINING (§25.15-17) ----------

  it('training fica em bloco separado e o S-34 tem o seu', () => {
    const { ctx } = ctxFixture({ sectionHint: 'base sólida' });
    const pack = {
      ...emptyContextPack(),
      content_sources: [
        {
          ...trainingSource,
          id: 'c1',
          source_type: 'publication' as const,
          text: 'fato de conteúdo',
        },
      ],
      training_sources: [trainingSource],
    };
    const prompt = buildLlmPrompt({
      text: 'corpo',
      action: 'chat',
      contextPack: pack,
      structural: ctx,
      chat: { message: 'quantos pontos?', history: [], isFirstMessage: true },
    } as LlmRequest);
    expect(prompt.user).toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(prompt.user).toContain('NÃO usar como fatos');
    expect(prompt.user).toContain('--- S-34 (ESTRUTURA DO DISCURSO) ---');
    const s34Part = prompt.user.split('--- S-34')[1].split('--- FIM DA ESTRUTURA')[0];
    expect(s34Part).not.toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(s34Part).not.toContain(trainingSource.text);
    const contentPart = prompt.user.split('FONTES DE CONTEÚDO')[1].split('FIM DAS FONTES')[0];
    expect(contentPart).not.toContain(trainingSource.text);
    expect(prompt.user.indexOf('ESTRUTURA DO DISCURSO')).toBeLessThan(
      prompt.user.indexOf('FONTES DE CONTEÚDO'),
    );
  });

  // ---------- Prompt: regras como contrato (§26) ----------

  it('regras do S-34 são regras, não decoração', () => {
    const rules = S34_PROMPT_RULES.toLowerCase();
    expect(rules).toContain('preserve a ordem dos pontos');
    expect(rules).toContain('não invente novos pontos');
    expect(rules).toContain('be/th apenas para orientar como apresentar');
    expect(rules).toContain('nunca use be/th como fonte factual');
    expect(rules).toContain(INSUFFICIENT_EVIDENCE_MESSAGE.toLowerCase());
    expect(rules).toContain('não antecipe');
    expect(rules).toContain('criatividade');
  });

  it('cláusula só entra quando há estrutura', () => {
    const { ctx } = ctxFixture({ sectionHint: 'base sólida' });
    const base = {
      text: 'corpo',
      action: 'chat' as const,
      contextPack: emptyContextPack(),
      chat: { message: 'x', history: [], isFirstMessage: true },
    };
    const comEstrutura = buildLlmPrompt({ ...base, structural: ctx } as LlmRequest);
    const semEstrutura = buildLlmPrompt({ ...base } as LlmRequest);
    expect(comEstrutura.user).toContain('REGRAS DO S-34');
    expect(semEstrutura.user).not.toContain('REGRAS DO S-34');
    expect(semEstrutura.user).not.toContain('ESTRUTURA DO DISCURSO');
  });

  // ---------- Ponta a ponta (§28) ----------

  it('fixture → parser → escopo → contexto → prompt', () => {
    const doc = parseS34(S34_FIXTURE_TEXT);
    const ctx = structuralContextFor(doc, {
      sectionHint: 'colocamos em prática',
      query: 'deixe mais natural',
    })!;
    const prompt = buildLlmPrompt({
      text: 'texto do bloco',
      action: 'chat',
      contextPack: {
        ...emptyContextPack(),
        content_sources: [{ ...trainingSource, id: 'c1', source_type: 'publication' as const, text: 'complementar' }],
        training_sources: [trainingSource],
      },
      structural: ctx,
      chat: {
        message: 'Quais textos bíblicos estão ligados ao ponto 2?',
        history: [],
        isFirstMessage: true,
      },
    } as LlmRequest);
    const u = prompt.user;
    expect(u).toContain('Objetivo: Mostrar como a fé pode ser fortalecida');
    const p1 = u.indexOf('1. A fé precisa de uma base sólida');
    const p2 = u.indexOf('2. A fé cresce quando colocamos em prática');
    const p3 = u.indexOf('3. Continue fortalecendo sua fé');
    expect(p1).toBeGreaterThanOrEqual(0);
    expect(p2).toBeGreaterThan(p1);
    expect(p3).toBeGreaterThan(p2);
    expect(u).toContain('<= PONTO ATUAL');
    expect(u).toContain('PONTO ATUAL (2):');
    expect(u).toContain('Subponto 1:');
    expect(u).toContain('Subponto 2:');
    expect(u).toContain('[BIBLE]');
    expect(u).toContain('Tiago 2:17');
    expect(u).toContain('[PUBLICATION]');
    expect(u).toContain('w24.02');
    expect(u).toContain('vinculada ao ponto 2');
    expect(u).not.toContain('João 17:17');
    expect(u).not.toContain('Hebreus 10:23');
    expect(u).not.toContain('w24.01');
    expect(u).toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(u).toContain('REGRAS DO S-34');
  });

  // ---------- Legado (§29) ----------

  it('prompt legado continua igual sem S-34', () => {
    const prompt = buildLlmPrompt({
      text: 'corpo',
      action: 'chat',
      contextPack: {
        ...emptyContextPack(),
        content_sources: [{ ...trainingSource, id: 'c1', source_type: 'publication' as const }],
        training_sources: [trainingSource],
      },
      chat: { message: 'pergunta', history: [], isFirstMessage: true },
    } as LlmRequest);
    expect(prompt.user).toContain('FONTES DE CONTEÚDO');
    expect(prompt.user).toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(prompt.user).not.toContain('S-34');
    expect(prompt.user).toContain('Mensagem do usuário: "pergunta"');
  });

  // ---------- Web × Android (projeção §30) ----------

  it('projeção estrutural é estável (contrato entre plataformas)', () => {
    const { ctx } = ctxFixture({ sectionHint: 'colocamos em prática' });
    const projection = {
      outlineIdPrefix: ctx!.outlineId.startsWith('s34-'),
      title: ctx!.title,
      objective: ctx!.objective,
      ordered: ctx!.orderedSections.map((s) => ({ order: s.order, id: s.id, current: s.isCurrent })),
      current: ctx!.currentSection && {
        id: ctx!.currentSection.id,
        order: ctx!.currentSection.order,
        subs: ctx!.currentSection.subsections.map((s) => s.order),
        bible: ctx!.currentSection.references.filter((r) => r.type === 'bible').map((r) => r.rawText),
        pubs: ctx!.currentSection.references
          .filter((r) => r.type === 'publication')
          .map((r) => r.rawText),
      },
      focus: ctx!.focusState,
      separation: 'S-34 | FONTES DE CONTEÚDO | ORIENTAÇÕES DE ORATÓRIA',
    };
    expect(projection).toMatchObject({
      outlineIdPrefix: true,
      title: 'Como fortalecer a fé',
      ordered: [
        { order: 1, id: 'sec-1', current: false },
        { order: 2, id: 'sec-2', current: true },
        { order: 3, id: 'sec-3', current: false },
      ],
      current: { id: 'sec-2', order: 2, subs: [1, 2] },
      focus: 'section',
      separation: 'S-34 | FONTES DE CONTEÚDO | ORIENTAÇÕES DE ORATÓRIA',
    });
    expect(projection.current!.bible.some((t) => t.includes('Tiago'))).toBe(true);
    expect(projection.current!.pubs.some((t) => t.includes('w24.02'))).toBe(true);
  });
});
