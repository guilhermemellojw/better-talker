// Retrieval estrutural por sectionId + ponto atual — Fase 19-B.4.
//
// Espelho conceitual de android/.../S34StructuralRetrieval.kt: mesmo
// contrato, mesmas regras. Domínio puro: recebe o S34Document (B.2) e
// devolve view escoppada. Escopo é fronteira dura (sem fallback global),
// ordem é estrutural (sourceLine), query só marca `matched`, referência
// permanece vinculada ao dono. Sem LLM, sem rede.

import { normalizeTokenText } from './tokenize';
import type { S34Document, S34Reference, S34Section } from './s34Parser';

export type S34EntryKind = 'section' | 'subsection' | 'reference';

export interface S34ScopedEntry {
  kind: S34EntryKind;
  id: string;
  /** Dono estrutural (seção ou subseção). Nunca null. */
  ownerId: string;
  text: string;
  /** Linha 1-based no S-34 original — a ordem vem daqui. */
  sourceLine: number;
  refType?: 'bible' | 'publication';
  matched: boolean;
}

export interface S34ScopedView {
  outlineId: string;
  sectionId: string;
  /** order explícito da seção (1-based). */
  documentOrder: number;
  title: string;
  minutes: number | null;
  entries: S34ScopedEntry[];
}

export interface S34SectionRef {
  id: string;
  order: number;
  title: string;
}

export type S34Resolution =
  | { kind: 'resolved'; outlineId: string; sectionId: string; title: string }
  | { kind: 'no-hint' }
  | { kind: 'unmatched'; hint: string };

export type S34StructuralResult =
  | { kind: 'section-focus'; view: S34ScopedView }
  | { kind: 'document-scope'; outlineId: string; sections: S34ScopedView[] }
  | { kind: 'no-outline' }
  | { kind: 'unknown-section'; outlineId: string; requested: string }
  | { kind: 'unmatched-section'; outlineId: string; hint: string };

/** Tokens de conteúdo (sem os curtos) para casamento. Puro/testável. */
export function s34QueryTerms(query: string): string[] {
  const seen = new Set<string>();
  for (const t of normalizeTokenText(query).split(' ')) {
    if (t.length >= 3) seen.add(t);
  }
  return [...seen];
}

/** igualdade normalizada primeiro; depois contenção ÚNICA (ambígua => unmatched). */
export function resolveS34Section(doc: S34Document, hint?: string | null): S34Resolution {
  const h = normalizeTokenText(hint ?? '');
  if (!h) return { kind: 'no-hint' };
  for (const s of doc.sections) {
    if (normalizeTokenText(s.title) === h) {
      return { kind: 'resolved', outlineId: doc.id, sectionId: s.id, title: s.title };
    }
  }
  const contained = doc.sections.filter((s) => {
    const t = normalizeTokenText(s.title);
    return t.length > 0 && (h.includes(t) || t.includes(h));
  });
  if (contained.length === 1) {
    return {
      kind: 'resolved',
      outlineId: doc.id,
      sectionId: contained[0].id,
      title: contained[0].title,
    };
  }
  return { kind: 'unmatched', hint: hint ?? '' };
}

export function s34SectionRefs(doc: S34Document): S34SectionRef[] {
  return [...doc.sections]
    .sort((a, b) => a.order - b.order)
    .map((s) => ({ id: s.id, order: s.order, title: s.title }));
}

/** null = seção inexistente NESTE outline (nunca resolve para outra). */
export function scopeToS34Section(doc: S34Document, sectionId: string): S34ScopedView | null {
  const section = doc.sections.find((s) => s.id === sectionId);
  if (!section) return null;
  const entries: S34ScopedEntry[] = [];
  if (section.content.trim()) {
    entries.push({
      kind: 'section',
      id: section.id,
      ownerId: section.id,
      text: section.content,
      sourceLine: section.sourceLine,
      matched: false,
    });
  }
  for (const r of section.references) entries.push(refEntry(r, section.id));
  for (const sub of [...section.subsections].sort((a, b) => a.order - b.order)) {
    entries.push({
      kind: 'subsection',
      id: sub.id,
      ownerId: sub.id,
      text: sub.content,
      sourceLine: sub.sourceLine,
      matched: false,
    });
    for (const r of sub.references) entries.push(refEntry(r, sub.id));
  }
  entries.sort((a, b) => a.sourceLine - b.sourceLine || a.id.localeCompare(b.id));
  return {
    outlineId: doc.id,
    sectionId: section.id,
    documentOrder: section.order,
    title: section.title,
    minutes: section.minutes,
    entries,
  };
}

/** Marca casados com a query; NUNCA reordena, NUNCA remove. */
export function markS34Matches(view: S34ScopedView, query: string): S34ScopedView {
  const terms = s34QueryTerms(query);
  if (terms.length === 0) return view;
  return {
    ...view,
    entries: view.entries.map((e) => ({
      ...e,
      matched: terms.some((t) => normalizeTokenText(e.text).includes(t)),
    })),
  };
}

export function scopeToS34Document(doc: S34Document): S34ScopedView[] {
  return [...doc.sections]
    .sort((a, b) => a.order - b.order)
    .map((s) => scopeToS34Section(doc, s.id)!);
}

/**
 * Verifica se um ponto pode ser resolvido sem outline persistido: devolve
 * o outline correto ou um estado explícito. `docs` = S-34 disponíveis
 * (o chamador passa a fonte certa; aqui nunca se varre outra).
 */
export function scopeS34(
  doc: S34Document,
  opts: { sectionId?: string | null; sectionHint?: string | null; query?: string } = {},
): S34StructuralResult {
  const query = opts.query ?? '';
  if (opts.sectionId) {
    const view = scopeToS34Section(doc, opts.sectionId);
    if (!view) {
      return { kind: 'unknown-section', outlineId: doc.id, requested: opts.sectionId };
    }
    return { kind: 'section-focus', view: markS34Matches(view, query) };
  }
  const r = resolveS34Section(doc, opts.sectionHint);
  if (r.kind === 'resolved') {
    const view = scopeToS34Section(doc, r.sectionId)!;
    return { kind: 'section-focus', view: markS34Matches(view, query) };
  }
  if (r.kind === 'unmatched') {
    return { kind: 'unmatched-section', outlineId: doc.id, hint: r.hint };
  }
  return {
    kind: 'document-scope',
    outlineId: doc.id,
    sections: scopeToS34Document(doc).map((v) => markS34Matches(v, query)),
  };
}

function refEntry(r: S34Reference, ownerId: string): S34ScopedEntry {
  return {
    kind: 'reference',
    id: `${ownerId}-ref-${r.order}`,
    ownerId,
    text: r.rawText,
    sourceLine: r.sourceLine,
    refType: r.type,
    matched: false,
  };
}

/** Seções do documento em ordem, para UI de "ponto atual". */
export function s34SectionTitleOf(doc: S34Document, sectionId: string): string | null {
  const s: S34Section | undefined = doc.sections.find((x) => x.id === sectionId);
  return s?.title ?? null;
}
