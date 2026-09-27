import { describe, it, expect } from 'vitest';
import { parseS34 } from '../s34Parser';
import { scopeToS34Section } from '../s34StructuralRetrieval';
import {
  oratorySpec,
  buildOratoryPrompt,
  type OratoryMode,
} from '../oratoryGeneration';
import {
  decideOratoryRequest,
  isOratoryRefinement,
  requestedPoint,
  triesToCreateStructure,
  NOTHING_TO_REFINE_MESSAGE,
  type LastOratoryGeneration,
} from '../oratorySession';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 20-C — continuidade da geração (iteração + troca de alvo).
 * Espelho conceitual de android/.../OratorySessionTest.kt.
 */
describe('oratorySession', () => {
  const doc = parseS34(S34_FIXTURE_TEXT);
  const view2 = scopeToS34Section(doc, 'sec-2')!;
  const view3 = scopeToS34Section(doc, 'sec-3')!;

  const decide = (
    text: string,
    current: string | null = 'sec-2',
    last: LastOratoryGeneration | null = null,
  ) => decideOratoryRequest(text, doc, current, last);

  const generated = (d: ReturnType<typeof decide>) => {
    if (d.kind !== 'generate') throw new Error(`esperado generate, veio ${d.kind}`);
    return d;
  };

  // ---------- Pedido explícito ----------

  it('pedido explícito tem precedência', () => {
    const d = generated(decide('Crie uma introdução para este discurso'));
    expect(d.mode).toBe('introduction');
    expect(d.sectionId).toBe('sec-1');
    expect(d.action).toBe('insert');
    expect(d.inherited).toBe(false);
  });

  it('ponto explícito escolhe a seção certa', () => {
    const d = generated(decide('Desenvolva o ponto 3'));
    expect(d.mode).toBe('development');
    expect(d.sectionId).toBe('sec-3');
  });

  it('pedido sem ponto usa o foco atual', () => {
    expect(generated(decide('Desenvolva este ponto')).sectionId).toBe('sec-2');
  });

  // ---------- Iteração: herda modo + ponto ----------

  it('"melhore" herda modo e ponto', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    const d = generated(decide('Melhore.', 'sec-2', last));
    expect(d.mode).toBe('development');
    expect(d.sectionId).toBe('sec-2');
    expect(d.action).toBe('replace');
    expect(d.inherited).toBe(true);
  });

  it('"deixe mais natural" e "encurte" mantêm o ponto', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    for (const t of ['Deixe mais natural.', 'Encurte.', 'Explique melhor.']) {
      const d = generated(decide(t, 'sec-2', last));
      expect(d.sectionId).toBe('sec-2');
      expect(d.sectionId).not.toBe('sec-3');
      expect(d.mode).toBe('development');
    }
  });

  it('iteração da introdução continua introdução', () => {
    const last: LastOratoryGeneration = { mode: 'introduction', sectionId: 'sec-1' };
    for (const t of ['Melhore.', 'Deixe mais natural.', 'Encurte.']) {
      const d = generated(decide(t, 'sec-2', last));
      expect(d.mode).toBe('introduction');
      expect(d.sectionId).toBe('sec-1');
    }
  });

  // ---------- Mudança de modo/ponto ----------

  it('mudança de modo substitui a herança', () => {
    const last: LastOratoryGeneration = { mode: 'introduction', sectionId: 'sec-1' };
    const d = generated(decide('Agora desenvolva o ponto 2', 'sec-1', last));
    expect(d.mode).toBe('development');
    expect(d.sectionId).toBe('sec-2');
    expect(d.inherited).toBe(false);
  });

  it('mudança de ponto acompanha a referência', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    const d = generated(decide('Agora desenvolva o ponto 3', 'sec-2', last));
    expect(d.sectionId).toBe('sec-3');
    const r = oratorySpec(d.mode, doc, view3, d.action);
    if (r.kind !== 'ready') throw new Error('esperado ready');
    const labels = r.spec.current!.references.map((x) => x.label).join(' ');
    expect(labels).toContain('Hebreus 10:23');
    expect(labels).not.toContain('Tiago 2:17');
  });

  // ---------- Ambíguo e fora de escopo ----------

  it('refinamento sem geração anterior não inventa alvo', () => {
    const d = decide('Melhore.');
    expect(d.kind).toBe('nothing-to-refine');
    if (d.kind === 'nothing-to-refine') expect(d.message).toBe(NOTHING_TO_REFINE_MESSAGE);
  });

  it('pergunta informativa não vira geração', () => {
    expect(decide('Qual é o objetivo?').kind).toBe('nothing-to-refine');
  });

  it('criar ponto 4 é recusado sem alterar a estrutura', () => {
    const d = decide('Crie um ponto 4 para este discurso');
    expect(d.kind).toBe('out-of-structural-scope');
    if (d.kind === 'out-of-structural-scope') {
      expect(d.requestedPoint).toBe(4);
      expect(d.message).toContain('estrutura vem do S-34');
    }
    expect(doc.sections.length).toBe(3);
    expect(doc.sections.map((s) => s.order)).toEqual([1, 2, 3]);
  });

  it('ponto existente não é fora de escopo', () => {
    const d = decide('Adicione conteúdo ao ponto 2');
    expect(d.kind).not.toBe('out-of-structural-scope');
  });

  // ---------- Iteração mantém as fontes estruturais ----------

  it('iteração da introdução mantém objetivo e primeiro ponto', () => {
    const last: LastOratoryGeneration = { mode: 'introduction', sectionId: 'sec-1' };
    const d = generated(decide('Deixe mais natural.', 'sec-2', last));
    const r = oratorySpec(d.mode, doc, scopeToS34Section(doc, d.sectionId!)!, d.action);
    if (r.kind !== 'ready') throw new Error('esperado ready');
    expect(r.spec.objective).toBe(doc.objective);
    expect(r.spec.current!.sectionId).toBe('sec-1');
    expect(r.spec.orderedSections.map((x) => x.order)).toEqual([1, 2, 3]);
  });

  it('iteração do desenvolvimento preserva subpontos e não puxa o ponto 3', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    const d = generated(decide('Encurte.', 'sec-2', last));
    const r = oratorySpec(d.mode, doc, view2, d.action);
    if (r.kind !== 'ready') throw new Error('esperado ready');
    expect(r.spec.current!.subsections.length).toBe(2);
    expect(r.spec.current!.references.some((x) => x.label.includes('Tiago 2:17'))).toBe(true);
    const p = buildOratoryPrompt(r.spec, 'Encurte.');
    expect(p).not.toContain('Hebreus 10:23');
    expect(p).toContain('MODO: DESENVOLVIMENTO DO PONTO');
  });

  // ---------- Sequência completa ----------

  it('sequência completa mantém continuidade', () => {
    const passos: Array<[string, OratoryMode, string]> = [
      ['Crie uma introdução', 'introduction', 'sec-1'],
      ['Desenvolva o ponto 1', 'development', 'sec-1'],
      ['Crie uma transição do ponto 1 para o 2', 'transition', 'sec-1'],
      ['Desenvolva o ponto 2', 'development', 'sec-2'],
      ['Crie uma transição do ponto 2 para o 3', 'transition', 'sec-2'],
      ['Desenvolva o ponto 3', 'development', 'sec-3'],
      ['Crie uma conclusão', 'conclusion', 'sec-3'],
    ];
    let last: LastOratoryGeneration | null = null;
    for (const [texto, modo, secao] of passos) {
      const d = generated(decideOratoryRequest(texto, doc, null, last));
      expect(d.mode, texto).toBe(modo);
      expect(d.sectionId, texto).toBe(secao);
      const r = oratorySpec(d.mode, doc, scopeToS34Section(doc, d.sectionId!)!, d.action);
      expect(r.kind, texto).toBe('ready');
      last = { mode: d.mode, sectionId: d.sectionId };
    }
    expect(last!.mode).toBe('conclusion');
  });

  // ---------- Helpers ----------

  it('detectores auxiliares', () => {
    expect(isOratoryRefinement('Deixe mais natural.')).toBe(true);
    expect(isOratoryRefinement('Qual é o objetivo?')).toBe(false);
    expect(requestedPoint('Desenvolva o ponto 3')).toBe(3);
    expect(triesToCreateStructure('Crie um ponto 4', 3)).toBe(4);
    expect(triesToCreateStructure('Desenvolva o ponto 2', 3)).toBeNull();
  });
});
