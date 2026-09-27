// Contexto estrutural do S-34 para o prompt — Fase 19-B.5 (consome a B.4).
//
// Espelho conceitual de android/.../data/copilot/StructuralContext.kt:
// mesma estrutura, mesmos rótulos, mesma política de limite. Regras:
// a ORDEM vem do S-34; o ponto atual é explícito ou declarado ausente;
// referências ficam junto do dono; provenance por rótulo textual.

import type { S34Document, S34RefType } from './s34Parser';
import {
  scopeS34,
  s34SectionRefs,
  type S34StructuralResult,
  type S34ScopedView,
} from './s34StructuralRetrieval';

export type S34FocusState = 'section' | 'document' | 'unknown-section' | 'unmatched-hint';

export interface S34OrderedSection {
  id: string;
  order: number;
  title: string;
  minutes: number | null;
  isCurrent: boolean;
}

export interface S34StructuralReference {
  type: S34RefType;
  rawText: string;
  /** Dono real: id da seção ou da subseção. */
  ownerId: string;
  sourceLine: number;
}

export interface S34CurrentSubsection {
  id: string;
  order: number;
  content: string;
  references: S34StructuralReference[];
}

export interface S34CurrentSection {
  id: string;
  order: number;
  title: string;
  content: string;
  subsections: S34CurrentSubsection[];
  references: S34StructuralReference[];
}

export interface S34StructureContext {
  outlineId: string;
  title: string;
  objective: string | null;
  orderedSections: S34OrderedSection[];
  currentSection: S34CurrentSection | null;
  focusState: S34FocusState;
}

/**
 * Monta o contexto estrutural. A LISTA ORDENADA COMPLETA vem do documento
 * (mesmo com foco em um ponto: "o ponto 2 vem depois do 1 e antes do 3").
 */
export function structuralContextOf(
  result: S34StructuralResult,
  document: S34Document | null = null,
): S34StructureContext | null {
  if (result.kind === 'no-outline') return null;
  const views: S34ScopedView[] =
    result.kind === 'section-focus' ? [result.view] : result.kind === 'document-scope' ? result.sections : [];
  const currentId = result.kind === 'section-focus' ? result.view.sectionId : null;
  const focus: S34FocusState =
    result.kind === 'section-focus'
      ? 'section'
      : result.kind === 'document-scope'
        ? 'document'
        : result.kind === 'unknown-section'
          ? 'unknown-section'
          : 'unmatched-hint';

  const ordered: S34OrderedSection[] = document
    ? s34SectionRefs(document).map((ref) => ({
        id: ref.id,
        order: ref.order,
        title: ref.title,
        minutes: document.sections.find((s) => s.id === ref.id)?.minutes ?? null,
        isCurrent: ref.id === currentId,
      }))
    : [...views]
        .sort((a, b) => a.documentOrder - b.documentOrder)
        .map((v) => ({
          id: v.sectionId,
          order: v.documentOrder,
          title: v.title,
          minutes: v.minutes,
          isCurrent: v.sectionId === currentId,
        }));

  const current = views.find((v) => v.sectionId === currentId);
  return {
    outlineId:
      result.kind === 'section-focus' ? result.view.outlineId : (result as { outlineId: string }).outlineId,
    title: document?.title ?? '',
    objective: document?.objective?.trim() ? document.objective : null,
    orderedSections: ordered,
    currentSection: current ? currentFrom(current) : null,
    focusState: focus,
  };
}

function currentFrom(v: S34ScopedView): S34CurrentSection {
  const subs = [...v.entries]
    .filter((e) => e.kind === 'subsection')
    .sort((a, b) => a.sourceLine - b.sourceLine);
  return {
    id: v.sectionId,
    order: v.documentOrder,
    title: v.title,
    content: v.entries.find((e) => e.kind === 'section')?.text ?? '',
    subsections: subs.map((e, idx) => ({
      id: e.ownerId,
      order: idx + 1,
      content: e.text,
      references: refsOf(v, e.ownerId),
    })),
    references: refsOf(v, v.sectionId),
  };
}

function refsOf(v: S34ScopedView, ownerId: string): S34StructuralReference[] {
  return v.entries
    .filter((e) => e.kind === 'reference' && e.ownerId === ownerId)
    .map((e) => ({
      type: (e.refType ?? 'publication') as S34RefType,
      rawText: e.text,
      ownerId: e.ownerId,
      sourceLine: e.sourceLine,
    }));
}

const S34_TAG = '[S34]';

function label(t: S34RefType): string {
  return t === 'bible' ? '[BIBLE]' : '[PUBLICATION]';
}

/**
 * Renderiza o bloco estrutural (explícito, nunca texto amorfo).
 * Limites: pergunta geral = objetivo + lista ordenada; foco de seção =
 * lista ordenada + ponto atual completo (corpo, subpontos, referências).
 */
export function serializeStructuralContext(ctx: S34StructureContext): string {
  let out = '';
  out += '\n\n--- S-34 (ESTRUTURA DO DISCURSO) ---\n';
  out += `${S34_TAG} Título: ${ctx.title || '(sem título)'}\n`;
  if (ctx.objective) out += `${S34_TAG} Objetivo: ${ctx.objective}\n`;

  if (ctx.orderedSections.length > 0) {
    out += `${S34_TAG} Pontos, na ordem do esboço:\n`;
    for (const s of ctx.orderedSections) {
      const min = s.minutes != null ? ` (${s.minutes} min)` : '';
      const mark = s.isCurrent ? '  <= PONTO ATUAL' : '';
      out += `${S34_TAG}   ${s.order}. ${s.title}${min}${mark}\n`;
    }
  } else {
    out += `${S34_TAG} (lista de pontos indisponível neste estado)\n`;
  }

  if (ctx.focusState === 'section') {
    const c = ctx.currentSection;
    if (c) {
      out += `${S34_TAG} PONTO ATUAL (${c.order}): ${c.title}\n`;
      if (c.content.trim()) out += `${S34_TAG}   Corpo do ponto: ${c.content}\n`;
      for (const sub of c.subsections) {
        out += `${S34_TAG}   Subponto ${sub.order}: ${sub.content}\n`;
        for (const r of sub.references) {
          out += `${S34_TAG}     ${label(r.type)} ${r.rawText} (vinculada ao subponto ${sub.order}; linha ${r.sourceLine})\n`;
        }
      }
      for (const r of c.references) {
        out += `${S34_TAG}   ${label(r.type)} ${r.rawText} (vinculada ao ponto ${c.order}; linha ${r.sourceLine})\n`;
      }
      if (c.references.length === 0 && c.subsections.every((s) => s.references.length === 0)) {
        out += `${S34_TAG}   (nenhuma referência vinculada a este ponto)\n`;
      }
    }
  } else if (ctx.focusState === 'document') {
    out += `${S34_TAG} Ponto atual: nenhum selecionado (pergunta sobre o discurso)\n`;
  } else if (ctx.focusState === 'unknown-section') {
    out += `${S34_TAG} Ponto atual: NÃO IDENTIFICADO — a seção pedida não pertence a este esboço. Não presuma um ponto.\n`;
  } else {
    out += `${S34_TAG} Ponto atual: NÃO IDENTIFICADO — nenhum ponto corresponde à referência fornecida. Não presuma um ponto.\n`;
  }
  out += `${S34_TAG} Fim da estrutura. A ordem acima é a ordem do discurso.\n`;
  out += '--- FIM DA ESTRUTURA DO S-34 ---\n';
  return out;
}

/**
 * Cláusula de comportamento do S-34. Regras de contrato (testadas como
 * regras, não como texto decorativo).
 */
export const S34_PROMPT_RULES = `REGRAS DO S-34 (quando houver estrutura de S-34 no contexto):
1. O S-34 é a fonte estrutural deste discurso.
2. Preserve a ordem dos pontos fornecida pelo S-34 — não reorganize, não antecipe
   pontos posteriores e não volte a pontos anteriores sem pedido explícito.
3. Não invente novos pontos, subpontos ou referências.
4. Trate as referências como pertencentes ao ponto/subponto indicado; não as mova.
5. Use o S-34, a Bíblia e as publicações referenciadas como fontes de CONTEÚDO do
   que falar. Use BE/TH apenas para orientar COMO apresentar.
6. Nunca use BE/TH como fonte factual.
7. Criatividade serve para formular exemplos, aplicações, transições e maneiras de
   apresentar — nunca apresente criação sua como informação factual vinda do S-34,
   da Bíblia ou de uma publicação.
8. Quando as fontes não sustentarem uma afirmação factual, não preencha a lacuna
   inventando conteúdo; diga exatamente: "Não encontrei suporte suficiente nas fontes disponíveis."`;

/** Atalho: escopo + contexto estrutural a partir do documento. */
export function structuralContextFor(
  doc: S34Document,
  opts: { sectionId?: string | null; sectionHint?: string | null; query?: string } = {},
): S34StructureContext | null {
  return structuralContextOf(scopeS34(doc, opts), doc);
}

/**
 * Localiza o S-34 de um discurso na Library.
 *
 * Fronteira Web documentada (F19-B.5): o web NÃO tem vínculo explícito
 * discurso↔attachment (as publicações são globais), ao contrário do Android
 * (noteId). A associação é conservadora: casa o TÍTULO do discurso com o
 * título do S-34 persistido. Sem correspondência ⇒ null (caminho legado),
 * nunca um chute.
 */
export function findS34OutlineIdForSpeech(
  rows: Array<{ id: string; title: string }>,
  speechTitle: string,
): string | null {
  const target = speechTitle.trim();
  if (!target) return null;
  const exact = rows.filter((r) => r.title.trim() === target);
  return exact.length === 1 ? exact[0].id : null;
}
