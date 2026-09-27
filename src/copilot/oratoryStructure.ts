// Estrutura oratória INFERIDA a partir do S-34 — Fase 20-A.
//
// Espelho conceitual de android/.../data/copilot/OratoryStructure.kt.
//
// Distinção inegociável:
//   OutlineDocument          → estrutura REAL do S-34 (não muda aqui)
//   InferredOratoryStructure → interpretação oratória derivada
// Esta camada NUNCA altera o S-34, nunca gera texto e não é persistida:
// planeja. Pura (sem rede, LLM, Firebase, UI).

import type { TrainingCategory } from './domain';
import type { S34Document, S34Section } from './s34Parser';
import type { S34StructuralResult } from './s34StructuralRetrieval';

export type OratorySource = 'S34' | 'TRAINING' | 'MISSING';

export interface OratoryPurpose {
  source: OratorySource;
  text: string | null;
}

export interface OratorySectionRef {
  sectionId: string;
  order: number;
  title: string;
  source: OratorySource;
}

export interface OratoryTrainingSource {
  category: TrainingCategory;
  source: OratorySource;
}

export interface OratoryPart {
  purpose: OratoryPurpose;
  section: OratorySectionRef | null;
  trainingSources: OratoryTrainingSource[];
}

export interface OratoryDevelopmentStep {
  section: OratorySectionRef;
  subsectionCount: number;
  referenceCount: number;
}

export interface OratoryFocus {
  previousSectionId: string | null;
  currentSectionId: string;
  nextSectionId: string | null;
}

export interface InferredOratoryStructure {
  outlineId: string;
  title: string;
  introduction: OratoryPart;
  development: OratoryDevelopmentStep[];
  conclusion: OratoryPart;
  focus: OratoryFocus | null;
}

export type OratoryResult =
  | { kind: 'ok'; structure: InferredOratoryStructure }
  | { kind: 'insufficient-structure' };

export type OratoryPartKind = 'introduction' | 'development' | 'conclusion';

/**
 * Categorias de treinamento relevantes por parte. Usa SÓ a taxonomia
 * existente; nenhuma é "melhor" que outra (sem ranking).
 */
export function oratoryTrainingFor(part: OratoryPartKind): OratoryTrainingSource[] {
  const categories: TrainingCategory[] =
    part === 'introduction'
      ? ['introduction', 'questions', 'clarity', 'naturalness']
      : part === 'development'
        ? ['development', 'transition', 'explanation']
        : ['conclusion', 'application', 'clarity', 'naturalness'];
  return categories.map((category) => ({ category, source: 'TRAINING' as const }));
}

function sectionRef(s: S34Section): OratorySectionRef {
  return { sectionId: s.id, order: s.order, title: s.title, source: 'S34' };
}

/** Estado de foco a partir do resultado da B.4 (nunca inventa ponto atual). */
export function currentSectionOf(result: S34StructuralResult): string | null {
  return result.kind === 'section-focus' ? result.view.sectionId : null;
}

/**
 * Infere a estrutura. `currentSectionId` vem da B.4; quando desconhecido o
 * foco fica ausente.
 */
export function inferOratoryStructure(
  document: S34Document,
  currentSectionId: string | null = null,
): OratoryResult {
  if (document.sections.length === 0) return { kind: 'insufficient-structure' };
  const ordered = [...document.sections].sort((a, b) => a.order - b.order);
  const purpose: OratoryPurpose = {
    source: document.objective && document.objective.trim() ? 'S34' : 'MISSING',
    text: document.objective && document.objective.trim() ? document.objective : null,
  };
  const first = sectionRef(ordered[0]);
  const last = sectionRef(ordered[ordered.length - 1]);
  const development: OratoryDevelopmentStep[] = ordered.map((s) => ({
    section: sectionRef(s),
    subsectionCount: s.subsections.length,
    referenceCount:
      s.references.length + s.subsections.reduce((n, sub) => n + sub.references.length, 0),
  }));

  let focus: OratoryFocus | null = null;
  if (currentSectionId != null) {
    const idx = ordered.findIndex((s) => s.id === currentSectionId);
    if (idx >= 0) {
      focus = {
        previousSectionId: idx > 0 ? ordered[idx - 1].id : null,
        currentSectionId: ordered[idx].id,
        nextSectionId: idx < ordered.length - 1 ? ordered[idx + 1].id : null,
      };
    }
  }

  return {
    kind: 'ok',
    structure: {
      outlineId: document.id,
      title: document.title,
      introduction: { purpose, section: first, trainingSources: oratoryTrainingFor('introduction') },
      development,
      conclusion: { purpose, section: last, trainingSources: oratoryTrainingFor('conclusion') },
      focus,
    },
  };
}


/** Regra de prompt da estrutura inferida (§23): derivada, não conteúdo novo. */
export const ORATORY_STRUCTURE_RULES = `A estrutura oratória (abertura/desenvolvimento/conclusão) é uma organização
DERIVADA do S-34: ela não adiciona conteúdo factual ao esboço. Preserve a
sequência do desenvolvimento e use BE/TH somente para decidir COMO apresentar
cada parte, nunca para inventar conteúdo factual.`;

/** Atalho: documento + resultado da B.4. */
export function inferOratoryFrom(
  document: S34Document,
  retrieval: S34StructuralResult,
): OratoryResult {
  return inferOratoryStructure(document, currentSectionOf(retrieval));
}

/** Ids em ordem documental (sem proxy de score). */
export function oratorySectionIds(structure: InferredOratoryStructure): string[] {
  return structure.development.map((s) => s.section.sectionId);
}

/**
 * Serializa o planejamento estrutural para o prompt (§22).
 * NUNCA gera texto oratório (§15) — só aponta fontes e categorias.
 */
export function serializeOratoryStructure(structure: InferredOratoryStructure): string {
  let out = '\n\n--- ESTRUTURA ORATÓRIA INFERIDA (INFERIDA A PARTIR DO S-34) ---\n';
  out += 'Esta é uma organização DERIVADA do S-34; ela não adiciona conteúdo factual ao esboço.\n';
  out += `[S34] Objetivo: ${structure.introduction.purpose.text ?? 'não declarado no S-34'}\n`;

  const intro = structure.introduction;
  out += `[S34] PARTE 1 — ABERTURA\n`;
  out += `[S34]   Conteúdo: objetivo` +
    `${intro.section ? ` + ponto ${intro.section.order} "${intro.section.title}" (${intro.section.sectionId})` : ''}\n`;
  out += `[TRAINING]   Como apresentar (BE/TH): ${intro.trainingSources.map((t) => t.category).join(', ')}\n`;

  out += `[S34] DESENVOLVIMENTO — sequência exata do S-34:\n`;
  for (const step of structure.development) {
    const marker = structure.focus?.currentSectionId === step.section.sectionId ? '  <= PONTO ATUAL' : '';
    out += `[S34]   ${step.section.order}. ${step.section.title} (${step.section.sectionId})` +
      ` — ${step.subsectionCount} subponto(s), ${step.referenceCount} referência(s)${marker}\n`;
  }
  out += `[TRAINING]   Como apresentar (BE/TH): ${oratoryTrainingFor('development').map((t) => t.category).join(', ')}\n`;

  if (structure.focus) {
    const f = structure.focus;
    out += `[S34] FOCO ATUAL: ${f.currentSectionId}` +
      ` (anterior: ${f.previousSectionId ?? '—'}; próximo: ${f.nextSectionId ?? '—'})\n`;
  }

  const conc = structure.conclusion;
  out += `[S34] PARTE FINAL — CONCLUSÃO\n`;
  out += `[S34]   Conteúdo: objetivo` +
    `${conc.section ? ` + ponto ${conc.section.order} "${conc.section.title}" (${conc.section.sectionId})` : ''}\n`;
  out += `[TRAINING]   Como apresentar (BE/TH): ${conc.trainingSources.map((t) => t.category).join(', ')}\n`;

  out += '--- FIM DA ESTRUTURA ORATÓRIA INFERIDA ---\n';
  return out;
}
