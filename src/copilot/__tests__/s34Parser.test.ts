import { describe, it, expect } from 'vitest';
import {
  parseS34,
  collectS34References,
} from '../s34Parser';
import { detectBibleVerseRefs } from '../s34Detector';
import { detectCitations } from '../../services/citationDetector';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 19-B.2 — testes do parser S-34 → S34Document (§§20-22).
 * Espelho conceitual de android/.../S34ParserTest.kt (mesmo contrato).
 * Fixture intacta; variantes inline mínimas só onde ela não cobre o caso.
 */
describe('s34Parser', () => {
  const doc = parseS34(S34_FIXTURE_TEXT);

  // ---------- Documento básico (§20.1-4) ----------

  it('reconhece título', () => {
    expect(doc.title).toBe('Como fortalecer a fé');
  });

  it('reconhece objetivo', () => {
    expect(doc.objective).toBe('Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.');
  });

  it('reconhece 3 pontos', () => {
    expect(doc.sections.length).toBe(3);
    expect(doc.sections.map((s) => s.title)).toEqual([
      'A fé precisa de uma base sólida',
      'A fé cresce quando colocamos em prática o que aprendemos',
      'Continue fortalecendo sua fé',
    ]);
  });

  it('preserva ordem 1 → 2 → 3', () => {
    expect(doc.sections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(doc.sections.map((s) => s.id)).toEqual(['sec-1', 'sec-2', 'sec-3']);
  });

  it('preserva minutos e tem id estável', () => {
    expect(doc.sections.map((s) => s.minutes)).toEqual([4, 5, 3]);
    expect(doc.id.startsWith('s34-')).toBe(true);
    expect(doc.symbol).toBe('S-34');
    expect(parseS34(S34_FIXTURE_TEXT).id).toBe(doc.id);
  });

  // ---------- Hierarquia (§20.5-7) ----------

  it('reconhece subpontos a) e b)', () => {
    const subs = doc.sections[1].subsections;
    expect(subs.length).toBe(2);
    expect(subs[0].content).toContain('Estudar regularmente');
    expect(subs[1].content).toContain('Aplicar o que aprendemos');
  });

  it('mantém subponto dentro do ponto correto', () => {
    expect(doc.sections[0].subsections.length).toBe(0);
    expect(doc.sections[2].subsections.length).toBe(0);
    expect(doc.sections[1].subsections.length).toBe(2);
    expect(doc.sections[1].subsections[0].id).toBe('sec-2-1');
  });

  it('não mistura subpontos entre pontos', () => {
    const all = doc.sections.flatMap((s) => s.subsections);
    expect(all.length).toBe(2);
    expect(all.every((s) => s.id.startsWith('sec-2-'))).toBe(true);
  });

  // ---------- Bíblia (§20.8-11) ----------

  it('detecta João 17:17', () => {
    const refs = doc.sections[0].references.filter((r) => r.type === 'bible');
    expect(refs.some((r) => r.normalizedReference === 'João|17|17')).toBe(true);
  });

  it('detecta Tiago 2:17', () => {
    const refs = doc.sections[1].references.filter((r) => r.type === 'bible');
    expect(refs.some((r) => r.normalizedReference === 'Tiago|2|17')).toBe(true);
  });

  it('detecta Hebreus 10:23', () => {
    const refs = doc.sections[2].references.filter((r) => r.type === 'bible');
    expect(refs.some((r) => r.normalizedReference === 'Hebreus|10|23')).toBe(true);
  });

  it('associa cada referência à seção correta', () => {
    const bySec = doc.sections.map((s) =>
      s.references.filter((r) => r.type === 'bible').map((r) => r.normalizedReference),
    );
    expect(bySec[0].some((r) => r.startsWith('João|'))).toBe(true);
    expect(bySec[1].some((r) => r.startsWith('Tiago|'))).toBe(true);
    expect(bySec[2].some((r) => r.startsWith('Hebreus|'))).toBe(true);
    expect(bySec[0].some((r) => r.startsWith('Tiago|') || r.startsWith('Hebreus|'))).toBe(false);
  });

  it('referência em subponto vai para a subseção', () => {
    const text = S34_FIXTURE_TEXT.replace(
      '   b) Aplicar o que aprendemos',
      '   b) Aplicar o que aprendemos. Leia João 3:16.',
    );
    const d = parseS34(text);
    const sub = d.sections[1].subsections[1];
    expect(
      sub.references.some((r) => r.type === 'bible' && r.normalizedReference === 'João|3|16'),
    ).toBe(true);
    expect(
      d.sections[1].references.some(
        (r) => r.type === 'bible' && r.normalizedReference === 'João|3|16',
      ),
    ).toBe(false);
  });

  // ---------- Publicações (§20.12-15) ----------

  it('detecta w24.01 §3', () => {
    const refs = doc.sections[0].references.filter((r) => r.type === 'publication');
    expect(refs.some((r) => r.rawText.includes('w24.01'))).toBe(true);
  });

  it('detecta w24.02 §5', () => {
    const refs = doc.sections[1].references.filter((r) => r.type === 'publication');
    expect(refs.some((r) => r.rawText.includes('w24.02'))).toBe(true);
  });

  it('classifica como publicação', () => {
    const pubs = collectS34References(doc).filter((r) => r.type === 'publication');
    expect(pubs.length).toBeGreaterThanOrEqual(2);
    expect(pubs.every((r) => r.publication && !r.bible)).toBe(true);
  });

  it('associa pub à seção correta', () => {
    expect(
      doc.sections[0].references.some((r) => r.type === 'publication' && r.rawText.includes('w24.01')),
    ).toBe(true);
    expect(
      doc.sections[1].references.some((r) => r.type === 'publication' && r.rawText.includes('w24.02')),
    ).toBe(true);
    expect(doc.sections[2].references.some((r) => r.type === 'publication')).toBe(false);
  });

  // ---------- Ordem (§20.16-17) ----------

  it('ordem das referências é preservada', () => {
    expect(
      collectS34References(doc)
        .filter((r) => r.type === 'bible')
        .map((r) => r.normalizedReference),
    ).toEqual(['João|17|17', 'Tiago|2|17', 'Hebreus|10|23']);
  });

  it('ordem não depende de retrieval', () => {
    const orders = doc.sections.map((s) => s.order);
    expect([...orders].sort((a, b) => a - b)).toEqual(orders);
    expect(new Set(orders).size).toBe(orders.length);
  });

  // ---------- Ausência (§20.18-21) ----------

  it('sem objetivo dá null', () => {
    const text = S34_FIXTURE_TEXT.replace(
      'Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n',
      '',
    );
    const d = parseS34(text);
    expect(d.objective).toBeNull();
    expect(d.sections.length).toBe(3);
  });

  it('sem referências não falha', () => {
    const text =
      'S-34\n\nTema: Um tema\n\n1. Primeiro ponto (2 min)\n   Texto simples.\n\n2. Segundo ponto (3 min)\n   Mais texto.';
    const d = parseS34(text);
    expect(d.sections.length).toBe(2);
    expect(collectS34References(d).length).toBe(0);
  });

  it('sem subpontos não falha', () => {
    expect(doc.sections[0].subsections.length).toBe(0);
    expect(doc.sections[2].subsections.length).toBe(0);
  });

  it('texto vazio não lança exceção', () => {
    const d = parseS34('');
    expect(d.title).toBe('');
    expect(d.objective).toBeNull();
    expect(d.sections.length).toBe(0);
    expect(parseS34('   \n  ').sections.length).toBe(0);
  });

  // ---------- Conservadorismo (§20.22-25) ----------

  it('estrutura ambígua não gera ponto falso', () => {
    const text =
      'S-34 — rascunho (texto sintético de teste)\n\n' +
      'Tema: Um tema qualquer\n\n' +
      'Objetivo:\nRefletir sobre algo importante para a assistência presente.\n\n' +
      'Este parágrafo fala de um assunto sem número e sem tempo marcado.\n' +
      'Outro parágrafo continua a reflexão com calma e ordem.\n';
    const d = parseS34(text);
    expect(d.sections.length).toBe(0);
    expect(d.objective).toBe('Refletir sobre algo importante para a assistência presente.');
  });

  it('texto não reconhecido é preservado', () => {
    expect(doc.headerLines.some((l) => l.includes('S-34'))).toBe(true);
    const s0 = doc.sections[0];
    expect(s0.content.includes('base sólida') || s0.title.includes('base sólida')).toBe(true);
  });

  it('parser não cria introdução nem conclusão', () => {
    const names = new Set<string>();
    function collect(o: unknown): void {
      if (!o || typeof o !== 'object') return;
      for (const k of Object.keys(o)) {
        names.add(k);
        const v = (o as Record<string, unknown>)[k];
        if (Array.isArray(v)) v.forEach(collect);
      }
    }
    collect(doc);
    expect([...names].some((k) => /intro/i.test(k))).toBe(false);
    expect([...names].some((k) => /conclu/i.test(k))).toBe(false);
  });

  // ---------- Provenance (§20.26-27) ----------

  it('seções mantêm origem', () => {
    const lines = S34_FIXTURE_TEXT.split('\n');
    for (const s of doc.sections) {
      expect(s.source).toBe('S34');
      expect(s.sourceLine).toBeGreaterThan(0);
      expect(lines[s.sourceLine - 1]).toContain(s.title.slice(0, 10));
    }
  });

  it('referências mantêm origem', () => {
    const lines = S34_FIXTURE_TEXT.split('\n');
    for (const r of collectS34References(doc)) {
      expect(r.source).toBe('S34');
      expect(r.sourceLine).toBeGreaterThan(0);
      const line = lines[r.sourceLine - 1];
      expect(line.includes(':') || line.includes(r.rawText.slice(0, 10))).toBe(true);
    }
  });

  // ---------- Invariantes (§21) ----------

  it('invariante 1: ordem única e crescente', () => {
    const orders = doc.sections.map((s) => s.order);
    expect([...orders].sort((a, b) => a - b)).toEqual(orders);
    expect(new Set(orders).size).toBe(orders.length);
    for (const s of doc.sections) {
      const sub = s.subsections.map((x) => x.order);
      expect([...sub].sort((a, b) => a - b)).toEqual(sub);
      expect(new Set(sub).size).toBe(sub.length);
    }
  });

  it('invariante 2+5: acordo por linha e contenção estrutural', () => {
    const lines = S34_FIXTURE_TEXT.split('\n');
    expect(
      collectS34References(doc)
        .filter((r) => r.type === 'bible')
        .map((r) => r.normalizedReference),
    ).toEqual(['João|17|17', 'Tiago|2|17', 'Hebreus|10|23']);
    for (const r of collectS34References(doc)) {
      const line = lines[r.sourceLine - 1];
      if (r.type === 'bible') {
        expect(
          detectBibleVerseRefs(line).some((b) => `${b.label}|${b.chapter}|${b.verse}` === r.normalizedReference),
          `sumiu: ${r.normalizedReference}`,
        ).toBe(true);
      } else {
        expect(
          detectCitations(line, 's34').some((c) => c.raw === r.rawText),
          `sumiu: ${r.rawText}`,
        ).toBe(true);
      }
    }
    const ownerOf = (ref: (typeof doc.sections)[number]['references'][number]): string =>
      doc.sections.find(
        (s) => s.references.includes(ref) || s.subsections.some((sub) => sub.references.includes(ref)),
      )!.id;
    const spans = new Map(
      doc.sections.map((s, i) => {
        const end = doc.sections[i + 1]?.sourceLine ?? Number.MAX_SAFE_INTEGER;
        return [s.id, { start: s.sourceLine, end }] as const;
      }),
    );
    for (const r of collectS34References(doc)) {
      const span = spans.get(ownerOf(r))!;
      expect(r.sourceLine >= span.start && r.sourceLine < span.end).toBe(true);
    }
  });

  it('invariante 3: ordem dentro da seção é documental', () => {
    for (const s of doc.sections) {
      const all = [...s.references, ...s.subsections.flatMap((sub) => sub.references)];
      const byLine = all.map((r) => r.sourceLine);
      expect([...byLine].sort((a, b) => a - b)).toEqual(byLine);
    }
  });

  it('invariante 4: nada inventado (round-trip)', () => {
    const t = S34_FIXTURE_TEXT;
    expect(t).toContain(doc.title);
    if (doc.objective) expect(t).toContain(doc.objective);
    for (const s of doc.sections) {
      expect(t).toContain(s.title);
      for (const bl of s.content.split('\n').filter((l) => l.trim())) {
        expect(t).toContain(bl);
      }
      for (const sub of s.subsections) expect(t).toContain(sub.content);
    }
    for (const r of collectS34References(doc)) expect(t).toContain(r.rawText);
    for (const h of doc.headerLines) expect(t).toContain(h);
  });

  it('documento vazio tem forma válida', () => {
    const d = parseS34('');
    expect(d.symbol).toBe('S-34');
    expect(collectS34References(d).length).toBe(0);
  });
});
