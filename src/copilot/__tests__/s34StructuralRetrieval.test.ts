import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { parseS34 } from '../s34Parser';
import {
  scopeToS34Section,
  scopeToS34Document,
  scopeS34,
  resolveS34Section,
  s34SectionRefs,
  markS34Matches,
} from '../s34StructuralRetrieval';
import { S34_FIXTURE_TEXT } from './s34Fixture';
import { BetterTalkerDB } from '../../services/db';
import {
  saveS34Outline,
  retrieveS34Structural,
} from '../s34Repository';

/**
 * Fase 19-B.4 — retrieval estrutural por sectionId + ponto atual.
 * Espelho conceitual de android/.../S34StructuralRetrievalTest.kt.
 */
describe('s34StructuralRetrieval', () => {
  const doc = parseS34(
    S34_FIXTURE_TEXT.replace('A fé precisa de uma base sólida', 'A CONFIANÇA precisa de uma base sólida')
      .replace(
        'A fé cresce quando colocamos em prática o que aprendemos',
        'A ORAÇÃO cresce quando colocamos em prática o que aprendemos',
      )
      .replace('Continue fortalecendo sua fé', 'Continue fortalecendo sua CONFIANÇA'),
  );

  // ---------- Escopo por sectionId ----------

  it('escopo por sectionId traz só o ponto', () => {
    const v = scopeToS34Section(doc, 'sec-2')!;
    expect(v.sectionId).toBe('sec-2');
    expect(v.documentOrder).toBe(2);
    expect(v.entries.some((e) => e.text.includes('CONFIANÇA'))).toBe(false);
    expect(v.entries.some((e) => e.text.includes('Aplicar o que aprendemos'))).toBe(true);
  });

  it('sectionId desconhecido não vira outra seção', () => {
    expect(scopeToS34Section(doc, 'sec-99')).toBeNull();
    expect(scopeToS34Section(doc, 'sec-1-1')).toBeNull();
  });

  it('escopo não vaza entre outlines', () => {
    const a = parseS34(
      'S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado com palavras suficientes.\n\n' +
        '1. CONFIANÇA (2 min)\n   Corpo A sobre confiança.\n\n' +
        '2. ORAÇÃO (2 min)\n   Corpo A sobre oração.',
    );
    const b = parseS34(
      'S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B detalhado com palavras suficientes.\n\n' +
        '1. CONFIANÇA (2 min)\n   Corpo B sobre confiança.\n\n' +
        '2. ESPERANÇA (2 min)\n   Corpo B sobre esperança.',
    );
    const va = scopeToS34Section(a, 'sec-2')!;
    const vb = scopeToS34Section(b, 'sec-2')!;
    expect(va.entries.some((e) => e.text.includes('oração'))).toBe(true);
    expect(vb.entries.some((e) => e.text.includes('esperança'))).toBe(true);
    expect(va.entries.some((e) => e.text.includes('Corpo B'))).toBe(false);
    expect(vb.entries.some((e) => e.text.includes('Corpo A'))).toBe(false);
    expect(va.outlineId).not.toBe(vb.outlineId);
  });

  // ---------- Teste de isolamento do enunciado ----------

  it('isolamento: S34-A/sec-2 não vaza de nenhuma outra origem', () => {
    const s34a = parseS34(
      'S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A com detalhes suficientes para o teste.\n\n' +
        '1. CONFIANÇA (2 min)\n   Conteúdo A de confiança.\n\n' +
        '2. ORAÇÃO (2 min)\n   Conteúdo A de oração.',
    );
    const s34b = parseS34(
      'S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B com detalhes suficientes para o teste.\n\n' +
        '1. CONFIANÇA (2 min)\n   Conteúdo B de confiança.\n\n' +
        '2. ESPERANÇA (2 min)\n   Conteúdo B de esperança.',
    );
    const r = scopeS34(s34a, { sectionId: 'sec-2', query: 'confiança' });
    expect(r.kind).toBe('section-focus');
    if (r.kind !== 'section-focus') throw new Error('esperado section-focus');
    const text = r.view.entries.map((e) => e.text).join('\n');
    // Não pode conter nenhuma das outras três origens.
    expect(text).not.toContain('Conteúdo A de confiança'); // S34-A/sec-1
    expect(text).not.toContain('Conteúdo B de confiança'); // S34-B/sec-1
    expect(text).not.toContain('Conteúdo B de esperança'); // S34-B/sec-2
    // E contém o ponto pedido.
    expect(text).toContain('Conteúdo A de oração');
    void s34b;
  });

  // ---------- Ordem (nunca score) ----------

  it('seção com maior score não sobe na ordem', () => {
    const refs = s34SectionRefs(doc);
    expect(refs.map((r) => r.order)).toEqual([1, 2, 3]);
    expect(refs.map((r) => r.id)).toEqual(['sec-1', 'sec-2', 'sec-3']);
    expect(scopeToS34Document(doc).map((v) => v.documentOrder)).toEqual([1, 2, 3]);
  });

  it('ordem dos pontos não depende da query', () => {
    const a = scopeToS34Document(doc).map((v) => v.sectionId);
    const b = scopeToS34Document(doc)
      .map((v) => markS34Matches(v, 'CONFIANÇA'))
      .map((v) => v.sectionId);
    expect(a).toEqual(b);
  });

  it('ordem interna do ponto é estrutural', () => {
    const v = scopeToS34Section(doc, 'sec-2')!;
    const lines = v.entries.map((e) => e.sourceLine);
    expect([...lines].sort((x, y) => x - y)).toEqual(lines);
    expect(v.entries.every((e) => e.ownerId === 'sec-2' || e.ownerId.startsWith('sec-2-'))).toBe(true);
  });

  // ---------- Query só marca ----------

  it('query marca sem reordenar nem remover', () => {
    const base = scopeToS34Section(doc, 'sec-2')!;
    const marked = markS34Matches(base, 'CONFIANÇA');
    expect(marked.entries.map((e) => e.id)).toEqual(base.entries.map((e) => e.id));
    expect(marked.entries.length).toBe(base.entries.length);
  });

  it('query vazia não marca nada', () => {
    const base = scopeToS34Section(doc, 'sec-2')!;
    expect(markS34Matches(base, '  ').entries.every((e) => !e.matched)).toBe(true);
  });

  // ---------- Ponto atual (resolução) ----------

  it('resolve por título exato', () => {
    const r = resolveS34Section(doc, 'A ORAÇÃO cresce quando colocamos em prática o que aprendemos');
    expect(r.kind).toBe('resolved');
    if (r.kind === 'resolved') expect(r.sectionId).toBe('sec-2');
  });

  it('resolve por contenção única', () => {
    const r = resolveS34Section(doc, 'Ponto 3: Continue fortalecendo sua CONFIANÇA agora');
    expect(r.kind).toBe('resolved');
    if (r.kind === 'resolved') expect(r.sectionId).toBe('sec-3');
  });

  it('dica ausente é no-hint', () => {
    expect(resolveS34Section(doc, null).kind).toBe('no-hint');
    expect(resolveS34Section(doc, '   ').kind).toBe('no-hint');
  });

  it('dica ambígua não chuta', () => {
    const r = resolveS34Section(doc, 'assunto completamente diferente');
    expect(r.kind).toBe('unmatched');
  });

  it('hint não encontrado nunca cai no documento inteiro', () => {
    const r = scopeS34(doc, { sectionHint: 'assunto completamente diferente' });
    expect(r.kind).toBe('unmatched-section');
  });

  it('sectionId inexistente nunca cai no documento inteiro', () => {
    const r = scopeS34(doc, { sectionId: 'sec-99' });
    expect(r.kind).toBe('unknown-section');
  });

  // ---------- Referências vinculadas ----------

  it('referências ficam vinculadas ao dono', () => {
    const v = scopeToS34Section(doc, 'sec-2')!;
    const refs = v.entries.filter((e) => e.kind === 'reference');
    expect(refs.length).toBeGreaterThan(0);
    expect(refs.every((e) => e.ownerId === 'sec-2' || e.ownerId.startsWith('sec-2-'))).toBe(true);
    expect(refs.some((e) => e.text.includes('Tiago'))).toBe(true);
    expect(refs.some((e) => e.text.includes('João 17:17'))).toBe(false);
    expect(refs.some((e) => e.text.includes('Hebreus'))).toBe(false);
  });

  it('referência de subseção permanece na subseção', () => {
    const text = S34_FIXTURE_TEXT.replace(
      '   b) Aplicar o que aprendemos',
      '   b) Aplicar o que aprendemos. Leia João 3:16.',
    );
    const d = parseS34(text);
    const v = scopeToS34Section(d, 'sec-2')!;
    const subRef = v.entries.find((e) => e.kind === 'reference' && e.text.includes('João 3:16'))!;
    expect(subRef.ownerId).toBe('sec-2-2');
    expect(subRef.sourceLine).toBeGreaterThan(0);
  });

  it('tipo de referência preservado', () => {
    const v = scopeToS34Section(doc, 'sec-1')!;
    const refs = v.entries.filter((e) => e.kind === 'reference');
    expect(refs.some((r) => r.refType === 'bible')).toBe(true);
    expect(refs.some((r) => r.refType === 'publication')).toBe(true);
  });

  // ---------- Provenance ----------

  it('provenance é mantida em toda entrada', () => {
    const v = scopeToS34Section(doc, 'sec-2')!;
    expect(v.entries.every((e) => e.sourceLine > 0 && e.ownerId && e.id)).toBe(true);
  });

  // ---------- Determinismo ----------

  it('mesma entrada, mesmo resultado', () => {
    expect(scopeToS34Section(doc, 'sec-2')).toEqual(scopeToS34Section(doc, 'sec-2'));
  });

  it('documento vazio não quebra', () => {
    const empty = parseS34('');
    expect(s34SectionRefs(empty)).toEqual([]);
    expect(scopeToS34Document(empty)).toEqual([]);
    expect(resolveS34Section(empty, null).kind).toBe('no-hint');
    expect(resolveS34Section(empty, 'qualquer').kind).toBe('unmatched');
    expect(scopeToS34Section(empty, 'sec-1')).toBeNull();
  });

  it('retorna título e minutos do ponto', () => {
    const v = scopeToS34Section(doc, 'sec-2')!;
    expect(v.title).toContain('ORAÇÃO');
    expect(v.minutes).toBe(5);
    expect(v.entries.some((e) => e.kind === 'section')).toBe(true);
  });

  // ---------- Escopo de documento ----------

  it('document-scope traz todos os pontos em ordem e nada de fora', () => {
    const r = scopeS34(doc, { query: 'confiança' });
    expect(r.kind).toBe('document-scope');
    if (r.kind !== 'document-scope') throw new Error('esperado document-scope');
    expect(r.sections.map((s) => s.documentOrder)).toEqual([1, 2, 3]);
    expect(r.sections.every((s) => s.outlineId === doc.id)).toBe(true);
  });
});

// ---------- Retriever sobre a persistência (espelho Android) ----------

describe('s34StructuralRetriever (persistência)', () => {
  let tdb: BetterTalkerDB;

  beforeEach(async () => {
    tdb = new BetterTalkerDB();
    await tdb.open();
  });
  afterEach(async () => {
    await tdb.delete();
  });

  const mk = (surface: string, second: string, extra?: string) =>
    parseS34(
      `S-34 ${surface}\n\nTema: ${surface}\n\n` +
        `Objetivo:\nObjetivo ${surface} com detalhes suficientes.\n\n` +
        `1. CONFIANÇA (2 min)\n   Conteúdo ${surface} de confiança.\n\n` +
        `2. ${second.toUpperCase()} (2 min)\n   Conteúdo ${surface} de ${second}.${extra ?? ''}`,
    );

  it('sem outline persistido devolve no-outline (legado segue intocado)', async () => {
    const r = await retrieveS34Structural(tdb, 'att-sem-s34', { sectionId: 'sec-2' });
    expect(r.kind).toBe('no-outline');
  });

  it('isolamento: S34-A/sec-2 não vaza as outras três origens', async () => {
    const a = mk('A', 'oração');
    const b = mk('B', 'esperança');
    await saveS34Outline(tdb, a, 'att-A');
    await saveS34Outline(tdb, b, 'att-B');
    const r = await retrieveS34Structural(tdb, 'att-A', {
      sectionId: 'sec-2',
      query: 'confiança',
    });
    expect(r.kind).toBe('section-focus');
    if (r.kind !== 'section-focus') throw new Error('esperado section-focus');
    const text = r.view.entries.map((e) => e.text).join('\n');
    expect(text).not.toContain('Conteúdo A de confiança'); // A/sec-1
    expect(text).not.toContain('Conteúdo B de confiança'); // B/sec-1
    expect(text).not.toContain('Conteúdo B de esperança'); // B/sec-2
    expect(text).toContain('Conteúdo A de oração');
    expect(r.view.outlineId).toBe(a.id);
  });

  it('ordem documental sobrevive com score maior na sec-2', async () => {
    const d = parseS34(
      'S-34\n\nTema: T\n\nObjetivo:\nObjetivo detalhado o bastante para o teste.\n\n' +
        '1. Primeiro ponto (2 min)\n   corpo neutro.\n\n' +
        '2. CONFIANÇA CONFIANÇA CONFIANÇA (2 min)\n   confiança confiança confiança.\n\n' +
        '3. Terceiro ponto (2 min)\n   corpo neutro.',
    );
    await saveS34Outline(tdb, d, 'att-1');
    const r = await retrieveS34Structural(tdb, 'att-1', { query: 'confiança' });
    expect(r.kind).toBe('document-scope');
    if (r.kind !== 'document-scope') throw new Error('esperado document-scope');
    expect(r.sections.map((s) => s.documentOrder)).toEqual([1, 2, 3]);
    expect(r.sections.map((s) => s.sectionId)).toEqual(['sec-1', 'sec-2', 'sec-3']);
  });

  it('sectionId inexistente nunca cai no documento inteiro', async () => {
    const a = mk('A', 'oração');
    await saveS34Outline(tdb, a, 'att-A');
    const r = await retrieveS34Structural(tdb, 'att-A', { sectionId: 'sec-99' });
    expect(r.kind).toBe('unknown-section');
  });

  it('dica ambígua nunca cai no documento inteiro', async () => {
    const a = mk('A', 'oração');
    await saveS34Outline(tdb, a, 'att-A');
    const r = await retrieveS34Structural(tdb, 'att-A', {
      sectionHint: 'nenhum ponto corresponde a isto',
    });
    expect(r.kind).toBe('unmatched-section');
  });

  it('ponto atual por dica chega na view certa', async () => {
    const a = mk('A', 'oração');
    await saveS34Outline(tdb, a, 'att-A');
    const r = await retrieveS34Structural(tdb, 'att-A', { sectionHint: 'ORAÇÃO' });
    expect(r.kind).toBe('section-focus');
    if (r.kind === 'section-focus') expect(r.view.sectionId).toBe('sec-2');
  });
});
