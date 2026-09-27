import { describe, it, expect } from 'vitest';
import { parseS34 } from '../s34Parser';
import {
  inferOratoryStructure,
  oratorySectionIds,
  serializeOratoryStructure,
  ORATORY_STRUCTURE_RULES,
  currentSectionOf,
} from '../oratoryStructure';
import { buildLlmPrompt } from '../llmPrompt';
import { emptyContextPack } from '../domain';
import type { LlmRequest } from '../llmProvider';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 20-A — estrutura oratória inferida (Web).
 * Espelho conceitual de android/.../OratoryStructureTest.kt.
 */
describe('oratoryStructure', () => {
  const doc = parseS34(S34_FIXTURE_TEXT);

  const inferred = (current: string | null = null) => {
    const r = inferOratoryStructure(doc, current);
    if (r.kind !== 'ok') throw new Error('esperado ok');
    return r.structure;
  };

  // ---------- Modelo (§24.1-6) ----------

  it('3 pontos produzem as 3 partes', () => {
    const s = inferred();
    expect(s.outlineId).toBe(doc.id);
    expect(s.development.length).toBe(3);
    expect(s.introduction).toBeTruthy();
    expect(s.conclusion).toBeTruthy();
  });

  it('introdução aponta para objetivo e primeiro ponto', () => {
    const s = inferred();
    expect(s.introduction.purpose.source).toBe('S34');
    expect(s.introduction.purpose.text).toBe(doc.objective);
    expect(s.introduction.section!.sectionId).toBe('sec-1');
    expect(s.introduction.section!.order).toBe(1);
  });

  it('desenvolvimento contém 1→2→3', () => {
    expect(inferred().development.map((d) => d.section.order)).toEqual([1, 2, 3]);
  });

  it('conclusão aponta para objetivo e último ponto', () => {
    const s = inferred();
    expect(s.conclusion.purpose.source).toBe('S34');
    expect(s.conclusion.purpose.text).toBe(doc.objective);
    expect(s.conclusion.section!.sectionId).toBe('sec-3');
    expect(s.conclusion.section!.order).toBe(3);
  });

  // ---------- Conteúdo × treinamento (§24.7-9) ----------

  it('fontes de conteúdo e treinamento são distintas', () => {
    const s = inferred();
    for (const part of [s.introduction, s.conclusion]) {
      expect(part.purpose.source).toBe('S34');
      expect(part.section!.source).toBe('S34');
      expect(part.trainingSources.length).toBeGreaterThan(0);
      expect(part.trainingSources.every((t) => t.source === 'TRAINING')).toBe(true);
    }
  });

  it('BE/TH não aparece como content source e S-34 não vira treino', () => {
    const s = inferred();
    const content = [
      s.introduction.purpose.source,
      s.introduction.section!.source,
      s.conclusion.purpose.source,
      s.conclusion.section!.source,
    ];
    expect(content.includes('TRAINING')).toBe(false);
    const training = [...s.introduction.trainingSources, ...s.conclusion.trainingSources];
    expect(training.every((t) => t.source === 'TRAINING')).toBe(true);
  });

  it('categorias de treino são da taxonomia existente', () => {
    const s = inferred();
    expect(s.introduction.trainingSources.map((t) => t.category)).toEqual([
      'introduction',
      'questions',
      'clarity',
      'naturalness',
    ]);
    expect(s.conclusion.trainingSources.map((t) => t.category)).toEqual([
      'conclusion',
      'application',
      'clarity',
      'naturalness',
    ]);
  });

  // ---------- Ordem (§24.10-12) ----------

  it('ordem preservada e foco respeita vizinhos', () => {
    expect(oratorySectionIds(inferred())).toEqual(['sec-1', 'sec-2', 'sec-3']);
    const f = inferred('sec-2').focus!;
    expect(f.previousSectionId).toBe('sec-1');
    expect(f.currentSectionId).toBe('sec-2');
    expect(f.nextSectionId).toBe('sec-3');
  });

  it('extremos do foco são null', () => {
    expect(inferred('sec-1').focus!.previousSectionId).toBeNull();
    expect(inferred('sec-3').focus!.nextSectionId).toBeNull();
  });

  // ---------- Ausência (§24.13-15) ----------

  it('objective null não gera objetivo', () => {
    const text = S34_FIXTURE_TEXT.replace(
      'Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n',
      '',
    );
    const d = parseS34(text);
    const r = inferOratoryStructure(d);
    if (r.kind !== 'ok') throw new Error('esperado ok');
    expect(r.structure.introduction.purpose.source).toBe('MISSING');
    expect(r.structure.introduction.purpose.text).toBeNull();
    expect(r.structure.development.length).toBe(3);
  });

  it('sections vazias produzem estado insuficiente', () => {
    expect(inferOratoryStructure(parseS34('')).kind).toBe('insufficient-structure');
  });

  it('foco não é inventado quando não resolvido', () => {
    expect(inferred(null).focus).toBeNull();
    expect(inferred('sec-99').focus).toBeNull();
    expect(currentSectionOf({ kind: 'unknown-section', outlineId: doc.id, requested: 'x' })).toBeNull();
    expect(currentSectionOf({ kind: 'unmatched-section', outlineId: doc.id, hint: 'x' })).toBeNull();
    expect(currentSectionOf({ kind: 'document-scope', outlineId: doc.id, sections: [] })).toBeNull();
  });

  // ---------- Casos menores e mudança estrutural (§24.16-18, §26-27) ----------

  const docComPontos = (n: number) =>
    parseS34(
      'S-34\n\nTema: T\n\nObjetivo:\nObjetivo com detalhes suficientes para o teste.\n\n' +
        Array.from({ length: n }, (_, i) => {
          const k = i + 1;
          return `${k}. Ponto ${k} (2 min)\n   Leia João 17:${k}.\n   Consulte a publicação de estudo w24.01, §${k}.`;
        }).join('\n\n'),
    );

  it('um, dois e cinco pontos funcionam', () => {
    for (const n of [1, 2, 5]) {
      const r = inferOratoryStructure(docComPontos(n));
      if (r.kind !== 'ok') throw new Error('esperado ok');
      expect(r.structure.development.length).toBe(n);
      expect(r.structure.introduction.section!.sectionId).toBe('sec-1');
      expect(r.structure.conclusion.section!.sectionId).toBe(`sec-${n}`);
    }
  });

  it('estrutura acompanha a versão do S-34', () => {
    const v1 = inferOratoryStructure(docComPontos(3));
    const v2 = inferOratoryStructure(docComPontos(4));
    if (v1.kind !== 'ok' || v2.kind !== 'ok') throw new Error('esperado ok');
    expect(v1.structure.development.map((d) => d.section.order)).toEqual([1, 2, 3]);
    expect(v1.structure.conclusion.section!.sectionId).toBe('sec-3');
    expect(v2.structure.development.map((d) => d.section.order)).toEqual([1, 2, 3, 4]);
    expect(v2.structure.conclusion.section!.sectionId).toBe('sec-4');
  });

  it('estrutura acompanha a ordem documental (troca de ordem)', () => {
    const mk = (titulos: string[]) =>
      parseS34(
        'S-34\n\nTema: T\n\nObjetivo:\nObjetivo com detalhes suficientes.\n\n' +
          titulos
            .map(
              (t, i) =>
                `${i + 1}. ${t} (2 min)\n   Corpo do ponto ${t} com detalhes.\n   Consulte a publicação de estudo w24.01, §${i + 1}.`,
            )
            .join('\n\n'),
      );
    const a = inferOratoryStructure(mk(['A', 'B', 'C']));
    const b = inferOratoryStructure(mk(['C', 'A', 'B']));
    if (a.kind !== 'ok' || b.kind !== 'ok') throw new Error('esperado ok');
    expect(a.structure.development.map((d) => d.section.title)).toEqual(['A', 'B', 'C']);
    expect(b.structure.development.map((d) => d.section.title)).toEqual(['C', 'A', 'B']);
    expect(b.structure.introduction.section!.title).toBe('C');
    expect(b.structure.conclusion.section!.title).toBe('B');
  });

  // ---------- Serialização e prompt (§22-23) ----------

  it('serialização identifica a estrutura como inferida', () => {
    const text = serializeOratoryStructure(inferred('sec-2'));
    expect(text).toContain('--- ESTRUTURA ORATÓRIA INFERIDA');
    expect(text).toContain('INFERIDA A PARTIR DO S-34');
    expect(text).toContain('não adiciona conteúdo factual');
    expect(text).toContain('PARTE 1 — ABERTURA');
    expect(text).toContain('DESENVOLVIMENTO — sequência exata do S-34');
    expect(text).toContain('PARTE FINAL — CONCLUSÃO');
    expect(text).toContain('FOCO ATUAL: sec-2');
    expect(text).toContain('anterior: sec-1');
    expect(text).toContain('próximo: sec-3');
    expect(text).toContain('[TRAINING]');
    expect(text).not.toContain('Imagine');
    expect(text).not.toContain('Para concluir');
  });

  it('prompt tem bloco e regra só com estrutura', () => {
    const base = {
      text: 'corpo',
      action: 'chat' as const,
      contextPack: emptyContextPack(),
      chat: { message: 'como desenvolvimento?', history: [], isFirstMessage: true },
    };
    const com = buildLlmPrompt({ ...base, oratory: inferred('sec-2') } as LlmRequest);
    const sem = buildLlmPrompt({ ...base } as LlmRequest);
    expect(com.user).toContain('ESTRUTURA ORATÓRIA INFERIDA');
    expect(com.user).toContain('DERIVADA do S-34');
    expect(sem.user).not.toContain('ESTRUTURA ORATÓRIA INFERIDA');
  });

  it('regra da estrutura inferida é contrato', () => {
    const r = ORATORY_STRUCTURE_RULES.toLowerCase().replace(/\s+/g, ' ');
    expect(r).toContain('derivada do s-34');
    expect(r).toContain('não adiciona conteúdo factual');
    expect(r).toContain('preserve a sequência');
    expect(r).toContain('be/th somente para decidir como apresentar');
    expect(r).toContain('nunca para inventar conteúdo factual');
  });

  it('não cria pontuação nem ranking global', () => {
    const keys = Object.keys(inferred()).map((k) => k.toLowerCase());
    expect(keys.some((k) => k.includes('score'))).toBe(false);
    expect(keys.some((k) => k.includes('best'))).toBe(false);
    expect(keys.some((k) => k.includes('rank'))).toBe(false);
  });
});
