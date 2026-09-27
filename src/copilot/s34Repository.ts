// Repositório estrutural do OutlineDocument — Fase 19-B.3 (§19).
//
// Contrato: save/get/getBySource/deleteBySource (+ deleteBySource no
// deletePublication). Recebe o S34Document JÁ parseado; aqui só
// mapeamento + idempotência + cascade. Sem retrieval, sem prompt.
// Reconstrução em 4 queries (sem N+1); ordenação em JS por `position`
// (nenhum orderBy em campo sem índice — lição da F13).

import type { BetterTalkerDB } from '../services/db';
import {
  collectS34References,
  type S34Document,
} from './s34Parser';

export interface S34OutlineRow {
  id: string;
  sourceAttachmentId: string;
  symbol: string;
  title: string;
  objective: string | null;
  headerLines: string[];
  createdAt: number;
  updatedAt: number;
  parserVersion: number;
}

export interface S34SectionRow {
  id: string;
  outlineId: string;
  position: number;
  title: string;
  content: string;
  minutes: number | null;
  sourceLine: number;
}

export interface S34SubsectionRow {
  id: string;
  sectionId: string;
  position: number;
  content: string;
  sourceLine: number;
}

export interface S34ReferenceRow {
  id: string;
  outlineId: string;
  sectionId: string | null;
  subsectionId: string | null;
  position: number;
  type: 'bible' | 'publication';
  rawText: string;
  normalizedReference: string;
  sourceLine: number;
  book: string | null;
  chapter: number | null;
  verse: number | null;
  pubKey: string | null;
  pubLabel: string | null;
  editionKey: string | null;
}

export const S34_PARSER_VERSION = 2;

/**
 * F19-B.4: as chaves de seção/subseção do parser ("sec-N", "sec-N-M") são
 * determinísticas POR DOCUMENTO — dois S-34 colidiriam no store. O
 * armazenamento prefixa com o id do outline ("<outlineId>:sec-N") e o
 * `rebuild` devolve o id de domínio intacto (contrato B.2 preservado).
 */
export function s34StorageKey(outlineId: string, localId: string): string {
  return `${outlineId}:${localId}`;
}

export function s34LocalId(storageId: string, outlineId: string): string {
  return storageId.startsWith(`${outlineId}:`)
    ? storageId.slice(outlineId.length + 1)
    : storageId;
}

/** Subconjunto do banco usado aqui (Pick do tipo real — sempre compatível). */
export type S34Db = Pick<
  BetterTalkerDB,
  's34outlines' | 's34sections' | 's34subsections' | 's34references' | 'transaction'
>;

/**
 * Idempotente: mesma source + mesmo id => replace; mesma source + id
 * novo => substitui tudo (sem restos).
 */
export async function saveS34Outline(
  db: S34Db,
  doc: S34Document,
  sourceAttachmentId: string,
): Promise<void> {
  const now = Date.now();
  const prev = await db.s34outlines.where('sourceAttachmentId').equals(sourceAttachmentId).first();
  const createdAt = prev && prev.id === doc.id ? prev.createdAt : now;
  const rows = toRows(doc, sourceAttachmentId, createdAt, now);
  await db.transaction(
    'rw',
    [db.s34outlines, db.s34sections, db.s34subsections, db.s34references],
    async () => {
      await deleteRows(db, prev?.id);
      await db.s34outlines.put(rows.outline);
      await db.s34sections.bulkPut(rows.sections);
      await db.s34subsections.bulkPut(rows.subsections);
      await db.s34references.bulkPut(rows.references);
    },
  );
}

export async function getS34Outline(db: S34Db, outlineId: string): Promise<S34Document | null> {
  const o = await db.s34outlines.get(outlineId);
  if (!o) return null;
  return rebuild(db, o);
}

export async function getS34BySource(
  db: S34Db,
  sourceAttachmentId: string,
): Promise<S34Document | null> {
  const o = await db.s34outlines.where('sourceAttachmentId').equals(sourceAttachmentId).first();
  if (!o) return null;
  return rebuild(db, o);
}

/** Cascade total. Sem lixo. */
export async function deleteS34BySource(db: S34Db, sourceAttachmentId: string): Promise<void> {
  const o = await db.s34outlines.where('sourceAttachmentId').equals(sourceAttachmentId).first();
  if (!o) return;
  await db.transaction(
    'rw',
    [db.s34outlines, db.s34sections, db.s34subsections, db.s34references],
    async () => {
      await deleteRows(db, o.id);
    },
  );
}

async function deleteRows(db: S34Db, outlineId: string | undefined): Promise<void> {
  if (!outlineId) return;
  await db.s34references.where('outlineId').equals(outlineId).delete();
  const sections = await db.s34sections.where('outlineId').equals(outlineId).toArray();
  if (sections.length > 0) {
    await db.s34subsections
      .where('sectionId')
      .anyOf(sections.map((s) => s.id))
      .delete();
  }
  await db.s34sections.where('outlineId').equals(outlineId).delete();
  await db.s34outlines.delete(outlineId);
}

function toRows(
  doc: S34Document,
  sourceAttachmentId: string,
  createdAt: number,
  updatedAt: number,
) {
  return {
    outline: {
      id: doc.id,
      sourceAttachmentId,
      symbol: doc.symbol,
      title: doc.title,
      objective: doc.objective,
      headerLines: doc.headerLines,
      createdAt,
      updatedAt,
      parserVersion: S34_PARSER_VERSION,
    },
    sections: doc.sections.map(
      (s): S34SectionRow => ({
        id: s34StorageKey(doc.id, s.id),
        outlineId: doc.id,
        position: s.order,
        title: s.title,
        content: s.content,
        minutes: s.minutes,
        sourceLine: s.sourceLine,
      }),
    ),
    subsections: doc.sections.flatMap((s) =>
      s.subsections.map(
        (sub): S34SubsectionRow => ({
          id: s34StorageKey(doc.id, sub.id),
          sectionId: s34StorageKey(doc.id, s.id),
          position: sub.order,
          content: sub.content,
          sourceLine: sub.sourceLine,
        }),
      ),
    ),
    references: collectS34References(doc).map((r): S34ReferenceRow => {
      const owner = doc.sections.find(
        (s) => s.references.includes(r) || s.subsections.some((sub) => sub.references.includes(r)),
      );
      const sub = owner?.subsections.find((x) => x.references.includes(r));
      return {
        id: `${doc.id}-ref-${r.order}`,
        outlineId: doc.id,
        sectionId: owner ? s34StorageKey(doc.id, owner.id) : null,
        subsectionId: sub ? s34StorageKey(doc.id, sub.id) : null,
        position: r.order,
        type: r.type,
        rawText: r.rawText,
        normalizedReference: r.normalizedReference,
        sourceLine: r.sourceLine,
        book: r.bible?.label ?? null,
        chapter: r.bible?.chapter ?? null,
        verse: r.bible?.verse ?? null,
        pubKey: r.publication?.raw ? pubKeyOf(r) : null,
        pubLabel: r.publication?.raw ?? null,
        editionKey: null,
      };
    }),
  };
}

// pubKey não viaja no CitationCard; fica null (B.4 resolve via raw).
function pubKeyOf(r: { rawText: string }): string | null {
  const m = /\bw(\d{2})[./](\d{1,2})\b/i.exec(r.rawText);
  return m ? `w|20${m[1]}|${m[2]}` : null;
}

async function rebuild(db: S34Db, o: S34OutlineRow): Promise<S34Document> {
  const sections = (
    await db.s34sections.where('outlineId').equals(o.id).toArray()
  ).sort((a, b) => a.position - b.position);
  const subs = await db.s34subsections
    .where('sectionId')
    .anyOf(sections.map((s) => s.id))
    .toArray();
  const refs = (await db.s34references.where('outlineId').equals(o.id).toArray()).sort(
    (a, b) => a.position - b.position,
  );
  const subsBySection = new Map<string, S34SubsectionRow[]>();
  for (const sub of subs) {
    const list = subsBySection.get(sub.sectionId) ?? [];
    list.push(sub);
    subsBySection.set(sub.sectionId, list);
  }
  for (const list of subsBySection.values()) list.sort((a, b) => a.position - b.position);
  return {
    id: o.id,
    symbol: o.symbol,
    title: o.title,
    objective: o.objective,
    headerLines: o.headerLines,
    source: 'S34',
    sections: sections.map((s) => ({
      id: s34LocalId(s.id, o.id),
      order: s.position,
      title: s.title,
      minutes: s.minutes,
      content: s.content,
      sourceLine: s.sourceLine,
      source: 'S34' as const,
      subsections: (subsBySection.get(s.id) ?? []).map((sub) => ({
        id: s34LocalId(sub.id, o.id),
        order: sub.position,
        content: sub.content,
        sourceLine: sub.sourceLine,
        source: 'S34' as const,
        references: refs
          .filter((r) => r.subsectionId === sub.id)
          .map(toReference),
      })),
      references: refs.filter((r) => r.sectionId === s.id && !r.subsectionId).map(toReference),
    })),
  };
}

function toReference(r: S34ReferenceRow) {
  return {
    type: r.type,
    rawText: r.rawText,
    normalizedReference: r.normalizedReference,
    order: r.position,
    sourceLine: r.sourceLine,
    source: 'S34' as const,
    ...(r.book != null && r.chapter != null && r.verse != null
      ? { bible: { book: '', label: r.book, chapter: r.chapter, verse: r.verse } }
      : {}),
    ...(r.pubKey != null || r.pubLabel != null
      ? { publication: { raw: r.pubLabel ?? r.rawText } }
      : {}),
  };
}

/**
 * F19-B.4 — retrieval estrutural sobre a persistência. Espelho de
 * Android `S34StructuralRetriever`. Só orquestra: resolve o outline pela
 * source e delega a lógica pura. Nunca devolve outro outline/seção.
 */
export type S34RetrievalResult =
  | { kind: 'section-focus'; view: import('./s34StructuralRetrieval').S34ScopedView }
  | {
      kind: 'document-scope';
      outlineId: string;
      sections: import('./s34StructuralRetrieval').S34ScopedView[];
    }
  | { kind: 'no-outline' }
  | { kind: 'unknown-section'; outlineId: string; requested: string }
  | { kind: 'unmatched-section'; outlineId: string; hint: string };

export async function retrieveS34Structural(
  db: S34Db,
  sourceAttachmentId: string,
  opts: { sectionId?: string | null; sectionHint?: string | null; query?: string } = {},
): Promise<S34RetrievalResult> {
  const doc = await getS34BySource(db, sourceAttachmentId);
  if (!doc) return { kind: 'no-outline' };
  const { scopeS34 } = await import('./s34StructuralRetrieval');
  return scopeS34(doc, opts) as S34RetrievalResult;
}
