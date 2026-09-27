import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import Dexie from 'dexie';
import { BetterTalkerDB, deletePublicationCascade } from '../../services/db';
import { parseS34, collectS34References } from '../s34Parser';
import {
  saveS34Outline,
  getS34Outline,
  getS34BySource,
  deleteS34BySource,
} from '../s34Repository';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 19-B.3 — persistência do OutlineDocument (§22).
 * Dexie REAL sobre fake-indexeddb (mesmo schema de produção, sem mocks).
 * Instância fresca por teste (delete()+reuso quebra no fake-indexeddb);
 * upgrade v4→v5 com dados legados; reload via close()+reopen.
 */
describe('s34Persistence', () => {
  let tdb!: BetterTalkerDB;

  beforeEach(async () => {
    tdb = new BetterTalkerDB();
    await tdb.open();
  });

  afterEach(async () => {
    await tdb.delete();
  });

  const doc = () => parseS34(S34_FIXTURE_TEXT);

  // ---------- CRUD (§22.1-5) ----------

  it('salvar e recuperar por ID', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const got = await getS34Outline(tdb, d.id);
    expect(got?.title).toBe(d.title);
  });

  it('recuperar por source, ausência dá null', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    expect((await getS34BySource(tdb, 'att-1'))?.id).toBe(d.id);
    expect(await getS34BySource(tdb, 'att-99')).toBeNull();
    expect(await getS34Outline(tdb, 'inexistente')).toBeNull();
  });

  it('excluir remove tudo (sem lixo)', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    await deleteS34BySource(tdb, 'att-1');
    expect(await getS34Outline(tdb, d.id)).toBeNull();
    expect(await tdb.s34sections.where('outlineId').equals(d.id).count()).toBe(0);
    expect(await tdb.s34subsections.toCollection().count()).toBe(0);
    expect(await tdb.s34references.where('outlineId').equals(d.id).count()).toBe(0);
  });

  // ---------- Estrutura (§22.6-12) ----------

  it('round-trip preserva estrutura', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const got = (await getS34Outline(tdb, d.id))!;
    expect(got.objective).toBe(d.objective);
    expect(got.title).toBe(d.title);
    expect(got.sections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(got.sections.map((s) => s.minutes)).toEqual([4, 5, 3]);
    expect(got.sections[1].subsections.map((s) => s.order)).toEqual([1, 2]);
    expect(got.headerLines).toEqual(d.headerLines);
  });

  // ---------- Referências (§22.13-20) ----------

  it('referências sobrevivem com vínculo', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const got = (await getS34Outline(tdb, d.id))!;
    const b0 = got.sections[0].references.filter((r) => r.type === 'bible');
    expect(b0.some((r) => r.normalizedReference === 'João|17|17')).toBe(true);
    const p1 = got.sections[1].references.filter((r) => r.type === 'publication');
    expect(p1.some((r) => r.rawText.includes('w24.02'))).toBe(true);
    const w = collectS34References(got).find((r) => r.rawText.includes('w24.01'))!;
    expect(w.publication).toBeTruthy();
  });

  it('vínculo subseção sobrevive', async () => {
    const text = S34_FIXTURE_TEXT.replace(
      '   b) Aplicar o que aprendemos',
      '   b) Aplicar o que aprendemos. Leia João 3:16.',
    );
    const d = parseS34(text);
    await saveS34Outline(tdb, d, 'att-1');
    const got = (await getS34Outline(tdb, d.id))!;
    expect(
      got.sections[1].subsections[1].references.some(
        (r) => r.normalizedReference === 'João|3|16',
      ),
    ).toBe(true);
  });

  it('referência sem seção permanece sem seção', async () => {
    await tdb.s34references.put({
      id: 'orphan',
      outlineId: 'nope',
      sectionId: null,
      subsectionId: null,
      position: 1,
      type: 'bible',
      rawText: 'João 1:1',
      normalizedReference: 'João|1|1',
      sourceLine: 1,
      book: 'João',
      chapter: 1,
      verse: 1,
      pubKey: null,
      pubLabel: null,
      editionKey: null,
    });
    const row = await tdb.s34references.get('orphan');
    expect(row?.sectionId).toBeNull();
  });

  // ---------- Provenance (§22.21-22) ----------

  it('provenance sobrevive', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const got = (await getS34Outline(tdb, d.id))!;
    expect(got.sections.every((s) => s.sourceLine > 0)).toBe(true);
    expect(collectS34References(got).every((r) => r.sourceLine > 0)).toBe(true);
  });

  // ---------- Idempotência (§22.23-26) ----------

  it('salvar duas vezes não duplica', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    await saveS34Outline(tdb, d, 'att-1');
    expect(await tdb.s34outlines.toCollection().count()).toBe(1);
    expect(await tdb.s34sections.toCollection().count()).toBe(3);
    expect(await tdb.s34references.toCollection().count()).toBe(
      collectS34References(d).length,
    );
  });

  it('versão atualizada substitui sem restos', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const v2 = parseS34(
      S34_FIXTURE_TEXT.replace(
        '3. Continue fortalecendo sua fé (3 min)',
        '3. Continue fortalecendo sua fé (3 min)\n\n4. Persevere até o fim (2 min)\n   Leia Judas 25.',
      ),
    );
    await saveS34Outline(tdb, v2, 'att-1');
    const got = (await getS34BySource(tdb, 'att-1'))!;
    expect(got.sections.length).toBe(4);
    expect(got.id).toBe(v2.id);
    expect(await tdb.s34sections.toCollection().count()).toBe(4);
    const orphans = await tdb.s34references.where('outlineId').equals(d.id).count();
    expect(orphans).toBe(0);
  });

  it('remover seção remove filhos', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    await saveS34Outline(tdb, { ...d, sections: [d.sections[0], d.sections[2]] }, 'att-1');
    const got = (await getS34BySource(tdb, 'att-1'))!;
    expect(got.sections.length).toBe(2);
    expect(await tdb.s34subsections.toCollection().count()).toBe(0);
    const remaining = await tdb.s34references.toCollection().toArray();
    expect(remaining.some((r) => r.rawText.includes('w24.02'))).toBe(false);
  });

  // ---------- Restart (§22.30) ----------

  it('recarrega após fechar e reabrir', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    tdb.close();
    // Nova instância sobre o mesmo nome (close()+reuso quebra no
    // fake-indexeddb; instância fresca é o padrão deste arquivo).
    const tdb2 = new BetterTalkerDB();
    try {
      const got = await getS34BySource(tdb2, 'att-1');
      expect(got?.title).toBe(d.title);
      expect(got?.sections.length).toBe(3);
      expect(collectS34References(got!).length).toBe(collectS34References(d).length);
    } finally {
      await tdb2.delete();
    }
  });

  // ---------- Upgrade v4→v5 com dados legados ----------

  it('upgrade preserva dados antigos e libera s34', async () => {
    await tdb.delete();
    class V4DB extends Dexie {
      speeches!: Dexie.Table<{ id: string; title: string }, string>;
      publications!: Dexie.Table<{ id: string; fileName: string }, string>;
      constructor() {
        super('BetterTalkerDB');
        this.version(2).stores({
          speeches: 'id, updatedAt, sourceFileName',
          publications: 'id, fileName, indexed',
        });
        this.version(3).stores({
          speeches: 'id, updatedAt, sourceFileName',
          publications: 'id, fileName, indexed, symbol, source_type',
        });
        this.version(4).stores({
          speeches: 'id, updatedAt, sourceFileName',
          publications: 'id, fileName, indexed, symbol, source_type, addedAt',
        });
      }
    }
    const v4 = new V4DB();
    await v4.speeches.put({ id: 'sp1', title: 'Legado' } as never);
    await v4.publications.put({ id: 'pub1', fileName: 'w.pdf' } as never);
    v4.close();
    // Reabre no schema de produção (v5): upgrade automático.
    const tdb2 = new BetterTalkerDB();
    try {
      const speech = await tdb2.table('speeches').get('sp1');
      expect(speech?.title).toBe('Legado');
      const d = doc();
      await saveS34Outline(tdb2, d, 'pub1');
      expect((await getS34BySource(tdb2, 'pub1'))?.sections.length).toBe(3);
    } finally {
      await tdb2.delete();
    }
  });

  // ---------- Invariantes (§23) ----------

  it('invariantes de integridade', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const secs = await tdb.s34sections.where('outlineId').equals(d.id).toArray();
    expect(secs.every((s) => s.outlineId === d.id)).toBe(true);
    const subs = await tdb.s34subsections.toCollection().toArray();
    const secIds = new Set(secs.map((s) => s.id));
    expect(subs.every((s) => secIds.has(s.sectionId))).toBe(true);
    const refs = await tdb.s34references.where('outlineId').equals(d.id).toArray();
    for (const r of refs) {
      if (r.sectionId) {
        const s = secs.find((x) => x.id === r.sectionId);
        expect(s?.outlineId).toBe(d.id);
      }
      if (r.subsectionId) {
        const sub = subs.find((x) => x.id === r.subsectionId);
        expect(sub?.sectionId).toBe(r.sectionId);
      }
    }
    const ids = [...secs.map((s) => s.id), ...subs.map((s) => s.id), ...refs.map((r) => r.id)];
    expect(new Set(ids).size).toBe(ids.length);
    const got = (await getS34Outline(tdb, d.id))!;
    expect(got.sections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(collectS34References(got).length).toBe(collectS34References(d).length);
  });

  // ---------- Cascade via deletePublication (§20) ----------

  it('remover attachment remove o outline (sem órfãos)', async () => {
    await tdb.table('publications').put({ id: 'att-1', fileName: 's34.pdf' });
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    await deletePublicationCascade(tdb, 'att-1');
    expect(await getS34BySource(tdb, 'att-1')).toBeNull();
    expect(await tdb.s34sections.toCollection().count()).toBe(0);
    expect(await tdb.s34subsections.toCollection().count()).toBe(0);
    expect(await tdb.s34references.toCollection().count()).toBe(0);
  });

  it('dois S-34 coexistem sem colisão de chaves (isolamento)', async () => {
    const a = parseS34(
      'S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado com palavras suficientes.\n\n' +
        '1. CONFIANÇA (2 min)\n   Conteúdo A de confiança.\n\n' +
        '2. ORAÇÃO (2 min)\n   Conteúdo A de oração.',
    );
    const b = parseS34(
      'S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B detalhado com palavras suficientes.\n\n' +
        '1. CONFIANÇA (2 min)\n   Conteúdo B de confiança.\n\n' +
        '2. ESPERANÇA (2 min)\n   Conteúdo B de esperança.',
    );
    await saveS34Outline(tdb, a, 'att-A');
    await saveS34Outline(tdb, b, 'att-B');
    const ga = (await getS34BySource(tdb, 'att-A'))!;
    const gb = (await getS34BySource(tdb, 'att-B'))!;
    // Ambos mantêm os idS de domínio intactos e o conteúdo certo.
    expect(ga.sections.map((s) => s.id)).toEqual(['sec-1', 'sec-2']);
    expect(gb.sections.map((s) => s.id)).toEqual(['sec-1', 'sec-2']);
    expect(ga.sections[1].content).toContain('oração');
    expect(gb.sections[1].content).toContain('esperança');
    expect(ga.sections[0].content).toContain('Conteúdo A de confiança');
    expect(gb.sections[0].content).toContain('Conteúdo B de confiança');
    // Leitura de A não traz nada de B.
    const textA = ga.sections.flatMap((s) => [s.content, ...s.references.map((r) => r.rawText)]).join('\n');
    expect(textA).not.toContain('Conteúdo B');
  });

  // ---------- Web × Android (projeção equivalente) ----------

  it('projeção estrutural é igual entre plataformas (contrato)', async () => {
    const d = doc();
    await saveS34Outline(tdb, d, 'att-1');
    const got = (await getS34Outline(tdb, d.id))!;
    // Projeção comparável com o S34PersistenceTest.kt (mesmos campos).
    const projection = (x: typeof d) => ({
      title: x.title,
      objective: x.objective,
      sections: x.sections.map((s) => ({
        order: s.order,
        minutes: s.minutes,
        subs: s.subsections.map((sub) => sub.order),
        bible: s.references
          .filter((r) => r.type === 'bible')
          .map((r) => r.normalizedReference),
        pubs: s.references
          .filter((r) => r.type === 'publication')
          .map((r) => r.rawText),
      })),
    });
    expect(projection(got)).toEqual(projection(d));
  });
});
