import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import JSZip from 'jszip';
import { BetterTalkerDB } from '../../services/db';
import { indexPublication } from '../../services/libraryIndexer';
import { getS34BySource } from '../s34Repository';
import { retrieveS34Structural } from '../s34Repository';
import { structuralContextOf } from '../s34StructuralContext';
import { buildLlmPrompt } from '../llmPrompt';
import { emptyContextPack } from '../domain';
import type { LlmRequest } from '../llmProvider';
import { onDocumentExtracted } from '../s34ImportHook';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 19-B.6 — hook de importação + aceitação A–H (Web).
 * O teste principal usa a pipeline REAL: EPUB sintético → indexPublication
 * (mesma função da Biblioteca) → detector/parser/persistência → retrieval →
 * ContextPack → prompt. Nada é inserido direto no Dexie.
 */

/** EPUB mínimo (zip com xhtml): o texto da fixture vive em parágrafos. */
async function makeEpub(text: string, fileName = 's34-teste.epub'): Promise<File> {
  const zip = new JSZip();
  zip.file(
    'OEBPS/text.xhtml',
    '<?xml version="1.0" encoding="utf-8"?><html><body>' +
      text
        .split('\n')
        .map((l) => (l.trim() ? `<p>${l.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</p>` : ''))
        .join('') +
      '</body></html>',
  );
  const blob = await zip.generateAsync({ type: 'blob' });
  return new File([blob], fileName, { type: 'application/epub+zip' });
}

async function importEpub(file: File) {
  return indexPublication(file, 'epub');
}

describe('s34ImportHook + aceitação A–H (pipeline real de importação)', () => {
  let tdb: BetterTalkerDB;

  beforeEach(async () => {
    tdb = new BetterTalkerDB();
    await tdb.open();
  });
  afterEach(async () => {
    await tdb.delete();
  });

  // ---------- Fluxo real: arquivo → attachment → outline ----------

  it('importar S-34 cria o outline automaticamente (sem ação manual)', async () => {
    const pub = await importEpub(await makeEpub(S34_FIXTURE_TEXT));
    const doc = await getS34BySource(tdb, pub.id);
    expect(doc).not.toBeNull();
    expect(doc!.title).toBe('Como fortalecer a fé');
    expect(doc!.objective).toContain('fortalecida por meio da Palavra de Deus');
    expect(doc!.sections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(doc!.sections[1].subsections.length).toBe(2);
  });

  it('documento comum não vira S-34 (fluxo legado intacto)', async () => {
    const comum = 'Ata da reunião de condomínio.\nPresentes: síndico e moradores.\nPauta: pintura.\n'.repeat(8);
    const pub = await importEpub(await makeEpub(comum, 'ata.epub'));
    expect(await getS34BySource(tdb, pub.id)).toBeNull();
  });

  it('associação é pelo attachmentId (não por título)', async () => {
    const a = await importEpub(await makeEpub(S34_FIXTURE_TEXT, 'a.epub'));
    const b = await importEpub(
      await makeEpub(S34_FIXTURE_TEXT.replace('Como fortalecer a fé', 'Como fortalecer a esperança'), 'b.epub'),
    );
    const da = (await getS34BySource(tdb, a.id))!;
    const db_ = (await getS34BySource(tdb, b.id))!;
    expect(da.title).toBe('Como fortalecer a fé');
    expect(db_.title).toBe('Como fortalecer a esperança');
    expect(da.id).not.toBe(db_.id);
  });

  it('idempotência: reimportar não duplica', async () => {
    const file = await makeEpub(S34_FIXTURE_TEXT);
    const pub = await importEpub(file);
    const antes = await tdb.s34sections.toCollection().count();
    // Mesma identidade: hook rodado de novo sobre o mesmo attachment.
    const raw = S34_FIXTURE_TEXT;
    await onDocumentExtracted(tdb, pub.id, raw, { info: () => {}, warn: () => {} });
    expect(await tdb.s34outlines.count()).toBe(1);
    expect(await tdb.s34sections.toCollection().count()).toBe(antes);
  });

  it('atualização: v2 substitui v1 sem resíduos', async () => {
    const pub = await importEpub(await makeEpub(S34_FIXTURE_TEXT));
    const v2 = S34_FIXTURE_TEXT.replace(
      '3. Continue fortalecendo sua fé (3 min)',
      '3. Continue fortalecendo sua fé (3 min)\n\n4. Persevere até o fim (2 min)\n   Leia Judas 25.',
    );
    await onDocumentExtracted(tdb, pub.id, v2, { info: () => {}, warn: () => {} });
    const doc = (await getS34BySource(tdb, pub.id))!;
    expect(doc.sections.length).toBe(4);
    expect(await tdb.s34outlines.count()).toBe(1);
    expect(await tdb.s34sections.toCollection().count()).toBe(4);
    // Referências da v1 não sobraram.
    const refs = await tdb.s34references.toCollection().toArray();
    expect(refs.every((r) => r.outlineId === doc.id)).toBe(true);
    expect(refs.some((r) => r.rawText.includes('w24.01'))).toBe(true);
  });

  it('remoção do attachment remove o outline', async () => {
    const pub = await importEpub(await makeEpub(S34_FIXTURE_TEXT));
    expect(await getS34BySource(tdb, pub.id)).not.toBeNull();
    const { deletePublicationCascade } = await import('../../services/db');
    await deletePublicationCascade(tdb, pub.id);
    expect(await getS34BySource(tdb, pub.id)).toBeNull();
    expect(await tdb.s34sections.toCollection().count()).toBe(0);
  });

  it('reload mantém o outline (nova instância sobre o mesmo banco)', async () => {
    const pub = await importEpub(await makeEpub(S34_FIXTURE_TEXT));
    tdb.close();
    const tdb2 = new BetterTalkerDB();
    try {
      const doc = await getS34BySource(tdb2, pub.id);
      expect(doc).not.toBeNull();
      expect(doc!.sections.length).toBe(3);
    } finally {
      await tdb2.delete();
    }
  });

  it('isolamento entre dois S-34 importados de verdade', async () => {
    const a = await importEpub(
      await makeEpub(
        'S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A com detalhes suficientes para o teste.\n\n' +
          '1. CONFIANÇA (2 min)\n   Conteúdo A de confiança, com explicação e aplicação prática.\n' +
          '   Consulte a publicação de estudo w24.01, §3.\n\n' +
          '2. ORAÇÃO (2 min)\n   Conteúdo A de oração, com explicação e aplicação prática.\n' +
          '   Consulte a publicação de estudo w24.02, §5.',
        'a.epub',
      ),
    );
    const b = await importEpub(
      await makeEpub(
        'S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B com detalhes suficientes para o teste.\n\n' +
          '1. CONFIANÇA (2 min)\n   Conteúdo B de confiança, com explicação e aplicação prática.\n' +
          '   Consulte a publicação de estudo w24.04, §1.\n\n' +
          '2. ESPERANÇA (2 min)\n   Conteúdo B de esperança, com explicação e aplicação prática.\n' +
          '   Consulte a publicação de estudo w24.05, §2.',
        'b.epub',
      ),
    );
    const r = await retrieveS34Structural(tdb, a.id, { sectionId: 'sec-2', query: 'confiança' });
    expect(r.kind).toBe('section-focus');
    if (r.kind !== 'section-focus') throw new Error('esperado section-focus');
    const text = r.view.entries.map((e) => e.text).join('\n');
    expect(text).not.toContain('Conteúdo A de confiança');
    expect(text).not.toContain('Conteúdo B de confiança');
    expect(text).not.toContain('Conteúdo B de esperança');
    expect(text).toContain('Conteúdo A de oração');
    // e o outro outline continua com o seu próprio sec-2
    const rb = await retrieveS34Structural(tdb, b.id, { sectionId: 'sec-2' });
    if (rb.kind !== 'section-focus') throw new Error('esperado section-focus B');
    expect(rb.view.entries.map((e) => e.text).join('\n')).toContain('Conteúdo B de esperança');
  });

  // ---------- Aceitação A–H (camada determinística) ----------

  async function imported() {
    const pub = await importEpub(await makeEpub(S34_FIXTURE_TEXT));
    const doc = (await getS34BySource(tdb, pub.id))!;
    const r = await retrieveS34Structural(tdb, pub.id, { sectionHint: 'colocamos em prática' });
    const ctx = structuralContextOf(r, doc)!;
    return { pub, doc, ctx };
  }

  it('A — objetivo disponível explicitamente', async () => {
    const { ctx, doc } = await imported();
    expect(doc.objective).toBe('Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.');
    expect(ctx.objective).toBe(doc.objective);
    expect(ctx.focusState).toBe('section');
  });

  it('B — pontos principais em ordem documental', async () => {
    const { ctx } = await imported();
    expect(ctx.orderedSections.map((s) => `${s.order}. ${s.title}`)).toEqual([
      '1. A fé precisa de uma base sólida',
      '2. A fé cresce quando colocamos em prática o que aprendemos',
      '3. Continue fortalecendo sua fé',
    ]);
  });

  it('C — sequência obtida da estrutura, nunca de score', async () => {
    const { ctx } = await imported();
    expect(ctx.orderedSections.map((s) => s.order)).toEqual([1, 2, 3]);
    expect(ctx.orderedSections.map((s) => s.id)).toEqual(['sec-1', 'sec-2', 'sec-3']);
    expect(ctx.orderedSections.find((s) => s.isCurrent)!.id).toBe('sec-2');
  });

  it('D — textos bíblicos do ponto 2 (e nada de outros pontos)', async () => {
    const { ctx } = await imported();
    const bible = ctx.currentSection!.references.filter((r) => r.type === 'bible');
    expect(bible.map((r) => r.rawText).join(' ')).toContain('Tiago 2:17');
    expect(bible.map((r) => r.rawText).join(' ')).not.toContain('João 17:17');
    expect(bible.map((r) => r.rawText).join(' ')).not.toContain('Hebreus 10:23');
  });

  it('E — publicações do ponto 3 (fixture sintética)', async () => {
    const text = S34_FIXTURE_TEXT.replace(
      '3. Continue fortalecendo sua fé (3 min)\n   Leia Hebreus 10:23.',
      '3. Continue fortalecendo sua fé (3 min)\n   Leia Hebreus 10:23.\n   Consulte a publicação de estudo w24.03, §7.',
    );
    const pub = await importEpub(await makeEpub(text));
    const doc = (await getS34BySource(tdb, pub.id))!;
    const r = await retrieveS34Structural(tdb, pub.id, { sectionId: 'sec-3' });
    const ctx = structuralContextOf(r, doc)!;
    const pubs = ctx.currentSection!.references.filter((r) => r.type === 'publication');
    expect(pubs.map((p) => p.rawText).join(' ')).toContain('w24.03');
    expect(pubs.every((p) => p.ownerId === 'sec-3' || p.ownerId.startsWith('sec-3-'))).toBe(true);
    // e não vazou a publicação do ponto 2
    expect(pubs.map((p) => p.rawText).join(' ')).not.toContain('w24.02');
  });

  it('F — desenvolvimento do ponto 2 tem tudo para não sair do esboço', async () => {
    const { ctx } = await imported();
    // objetivo + posição + conteúdo + subpontos + refs + vizinhos
    expect(ctx.objective).not.toBeNull();
    expect(ctx.currentSection!.order).toBe(2);
    expect(ctx.currentSection!.content).toContain('Tiago 2:17');
    expect(ctx.currentSection!.subsections.length).toBe(2);
    expect(ctx.currentSection!.references.length).toBeGreaterThan(0);
    expect(ctx.orderedSections.map((s) => s.order)).toEqual([1, 2, 3]);
  });

  it('G — introdução: objetivo + primeiro ponto + BE/TH separado', async () => {
    const { ctx } = await imported();
    expect(ctx.objective).not.toBeNull();
    expect(ctx.orderedSections[0].order).toBe(1);
    const prompt = buildLlmPrompt({
      text: 'corpo',
      action: 'chat',
      contextPack: {
        ...emptyContextPack(),
        training_sources: [
          {
            id: 't1',
            reference: 'Beneficie-se lição 1',
            text: 'abertura com pergunta',
            source_type: 'speech_training',
            publication: 'be',
            section: null,
            paragraph: null,
            page: null,
            training_category: 'introduction',
            score: 0.9,
            matchedTerms: [],
            foundBy: [],
          },
        ],
      },
      structural: ctx,
      chat: { message: 'Como posso introduzir?', history: [], isFirstMessage: true },
    } as unknown as LlmRequest);
    expect(prompt.user).toContain('--- S-34 (ESTRUTURA DO DISCURSO) ---');
    expect(prompt.user).toContain('1. A fé precisa de uma base sólida');
    expect(prompt.user).toContain('ORIENTAÇÕES DE ORATÓRIA');
    expect(prompt.user).toContain('NÃO usar como fatos');
    // sem objeto "introduction" persistido
    expect(prompt.user).not.toContain('"introduction"');
  });

  it('H — conclusão: objetivo + último ponto + BE/TH separado', async () => {
    const { ctx } = await imported();
    const last = ctx.orderedSections[ctx.orderedSections.length - 1];
    expect(last.order).toBe(3);
    expect(ctx.objective).not.toBeNull();
    expect(ctx.orderedSections.map((s) => s.order)).toEqual([1, 2, 3]);
    // sem objeto "conclusion" persistido
    const { doc } = await imported();
    expect(Object.keys(doc)).not.toContain('conclusion');
  });

  // ---------- ContextPack real (pipeline completa até o prompt) ----------

  it('prompt recebe a estrutura vinda da importação real', async () => {
    const { ctx } = await imported();
    const prompt = buildLlmPrompt({
      text: 'corpo',
      action: 'chat',
      contextPack: emptyContextPack(),
      structural: ctx,
      chat: {
        message: 'Quais textos bíblicos estão ligados ao ponto 2?',
        history: [],
        isFirstMessage: true,
      },
    } as LlmRequest);
    const u = prompt.user;
    expect(u).toContain('--- S-34 (ESTRUTURA DO DISCURSO) ---');
    expect(u).toContain('Objetivo: Mostrar como a fé pode ser fortalecida');
    expect(u).toContain('<= PONTO ATUAL');
    expect(u).toContain('PONTO ATUAL (2):');
    expect(u).toContain('Subponto 1:');
    expect(u).toContain('[BIBLE]');
    expect(u).toContain('Tiago 2:17');
    expect(u).toContain('[PUBLICATION]');
    expect(u).toContain('w24.02');
    expect(u).toContain('REGRAS DO S-34');
    expect(u).not.toContain('João 17:17');
    expect(u).not.toContain('Hebreus 10:23');
  });

  // ---------- S-34 sem objetivo (§30) ----------

  it('S-34 sem objetivo importa com objective null', async () => {
    const text = S34_FIXTURE_TEXT.replace(
      'Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n',
      '',
    );
    const pub = await importEpub(await makeEpub(text));
    const doc = (await getS34BySource(tdb, pub.id))!;
    expect(doc.objective).toBeNull();
    const r = await retrieveS34Structural(tdb, pub.id, { sectionHint: 'colocamos em prática' });
    const ctx = structuralContextOf(r, doc)!;
    expect(ctx.objective).toBeNull();
  });

  // ---------- Ambíguo (§31) ----------

  it('documento ambíguo não vira S-34 silenciosamente', async () => {
    // Tem marcador e versículos, mas 1 sinal só → detector rejeita.
    const ambiguous =
      'S-34\n' +
      'Leia a Bíblia com atenção todos os dias e ore sempre. '.repeat(8) +
      '\nLeia João 17:17. Leia Tiago 2:17.';
    const pub = await importEpub(await makeEpub(ambiguous, 'amb.epub'));
    expect(await getS34BySource(tdb, pub.id)).toBeNull();
    // E o attachment continua íntegro para o fluxo legado.
    expect(await tdb.publications.get(pub.id)).toBeTruthy();
  });

  // ---------- Falha de parse não persiste estrutura falsa (§6) ----------

  it('parse insuficiente devolve estado explícito e não persiste', async () => {
    // S-34 com marcador e 2+ sinais, mas sem nenhum ponto numerado:
    const semPontos =
      'S-34 — rascunho (texto sintético de teste)\n' +
      'Tema: Um tema\n\n' +
      'Objetivo:\nRefletir sobre algo com calma e ordem durante a reunião.\n\n' +
      'Consulte a publicação de estudo w24.01, §3.\n'.repeat(4);
    const out = await onDocumentExtracted(tdb, 'att-x', semPontos, {
      info: () => {},
      warn: () => {},
    });
    // Detectado como S-34 (marcador + objetivo + publicações), mas sem
    // pontos numerados: estado explícito, NUNCA estrutura falsa (§6).
    expect(out.kind).toBe('parse-failed');
    expect(await tdb.s34outlines.count()).toBe(0);
  });
});
