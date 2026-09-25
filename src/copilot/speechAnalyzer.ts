// Analisador estrutural determinístico — Fase 9. 100% local, sem LLM, sem rede.
// Reutiliza: stripHtmlToText/hashText (Fase 5), extractClaims (Fase 6),
// classifyTraining (Fase 7). Fórmula de tempo espelha calculateSpeechMetrics
// (palavras/wpm + pausas de palco), sem depender de DOM (testes em Node).

import type { Speech } from '../types/speech';
import { extractClaims } from './claimExtractor';
import { hashText, stripHtmlToText } from './editProposal';
import { classifyTraining } from './trainingClassifier';
import { tokenize, uniqueTokens } from './tokenize';
import {
  ANALYSIS_VERSION,
  DEFAULT_ANALYSIS_WPM,
  TIME_DISCLAIMER,
  type BlockAnalysis,
  type Observation,
  type ObservationType,
  type ObservationSeverity,
  type SpeechAnalysis,
  type StructureRole,
} from './speechAnalysis';

export interface AnalyzeOptions {
  wpm?: number;
  force?: boolean;
}

const PAUSE_SECONDS: Record<string, number> = {
  'pause-2s': 2,
  'pause-3s': 3,
  'pause-5s': 5,
  applause: 4,
};

const CONNECTORS = [
  'portanto', 'além disso', 'alem disso', 'agora', 'em seguida', 'por outro lado',
  'assim', 'então', 'entao', 'passando para', 'vamos ver', 'em resumo', 'por fim',
  'além do mais', 'alem do mais', 'dito isso', 'com isso',
];

const INTRO_MARKERS = [
  'hoje vamos', 'hoje veremos', 'nesta palestra', 'neste discurso', 'objetivo',
  'vamos ver', 'vamos aprender', 'vamos considerar', 'tema de hoje', 'assunto de hoje',
];

const CONCLUSION_MARKERS = [
  'portanto', 'em resumo', 'resumindo', 'conclu', 'para concluir', 'recapitul',
  'termino', 'finalizo',
];

const CALL_MARKERS = [
  'apliquemos', 'vamos aplicar', 'decida', 'escolha', 'coloque em prática',
  'coloque em pratica', 'que possamos', 'faça', 'faca',
];

const FORMAL_WORDS = ['outrossim', 'doravante', 'não obstante', 'nao obstante', 'ademais', 'destarte'];

function countPauses(contentHtml: string): number {
  let total = 0;
  const re = /data-cue-type="([^"]+)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(contentHtml)) !== null) {
    total += PAUSE_SECONDS[m[1]] ?? 0;
  }
  return total;
}

function contentTokens(text: string): string[] {
  return uniqueTokens(tokenize(text)).filter((t) => t.length >= 4);
}

function sentencesOf(text: string): string[] {
  return text
    .replace(/\s+/g, ' ')
    .split(/(?<=[.!?…])\s+(?=[A-ZÀ-Ú0-9"“])/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

function jaccard(a: string[], b: string[]): number {
  if (a.length === 0 || b.length === 0) return 0;
  const setB = new Set(b);
  const inter = a.filter((t) => setB.has(t)).length;
  return inter / (new Set([...a, ...b]).size || 1);
}

function titleOverlap(title: string, text: string): number {
  const titleTerms = contentTokens(title);
  if (titleTerms.length === 0) return 0;
  const textSet = new Set(contentTokens(text));
  return titleTerms.filter((t) => textSet.has(t)).length;
}

function startsWithConnector(text: string): boolean {
  const lower = text.trim().toLowerCase();
  return CONNECTORS.some((c) => lower.startsWith(c));
}

function endsWithConnector(text: string): boolean {
  const lower = text.trim().toLowerCase().replace(/[.!?…]+$/, '');
  return CONNECTORS.some((c) => lower.endsWith(c));
}

function containsMarker(text: string, markers: string[]): boolean {
  const lower = text.toLowerCase();
  return markers.some((m) => lower.includes(m));
}

let observationCounter = 0;

function observe(
  type: ObservationType,
  severity: ObservationSeverity,
  blockIds: string[],
  message: string,
  reason: string,
  suggestion?: string,
): Observation {
  observationCounter += 1;
  return { id: `obs-${observationCounter}`, type, severity, blockIds, message, reason, suggestion };
}

function formatTime(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  if (m === 0) return `~${s} s`;
  return `~${m} min ${s.toString().padStart(2, '0')} s`;
}

export interface AnalysisCache {
  get(key: string): SpeechAnalysis | null;
  put(key: string, value: SpeechAnalysis): void;
  clear(): void;
  size(): number;
}

function createCache(limit = 50): AnalysisCache {
  const map = new Map<string, SpeechAnalysis>();
  return {
    get: (key) => map.get(key) ?? null,
    put: (key, value) => {
      map.set(key, value);
      if (map.size > limit) {
        const oldest = map.keys().next();
        if (!oldest.done) map.delete(oldest.value);
      }
    },
    clear: () => map.clear(),
    size: () => map.size,
  };
}

export const analysisCache = createCache();

export function analysisKeyFor(speechId: string, contentHash: string): string {
  return `${speechId}|${contentHash}|v${ANALYSIS_VERSION}`;
}

export function speechContentHash(speech: Speech): string {
  return hashText(speech.blocks.map((b) => `${b.id}:${b.contentHtml}`).join('\n'));
}

function analyzeFresh(speech: Speech, wpm: number): SpeechAnalysis {
  const observations: Observation[] = [];
  const structure: BlockAnalysis[] = [];
  const perBlock: { blockId: string; seconds: number }[] = [];
  const flow: StructureRole[] = [];

  const blocks = speech.blocks;
  const texts = blocks.map((b) => b.plainText || stripHtmlToText(b.contentHtml));

  // ---- Papéis por bloco + métricas ----
  blocks.forEach((block, i) => {
    const text = texts[i];
    const words = text.split(/\s+/).filter(Boolean);
    const seconds = Math.round((words.length / wpm) * 60 + countPauses(block.contentHtml));
    const sentences = sentencesOf(text);
    const longest = sentences.reduce(
      (max, s) => Math.max(max, s.split(/\s+/).filter(Boolean).length),
      0,
    );
    const roles: StructureRole[] = [];

    // Conteúdo semântico via extrator da Fase 6 (sem duplicar regras).
    const claimTypes = new Set(extractClaims(text, block.id).map((c) => c.type));
    if (claimTypes.has('interpretive')) roles.push('EXPLANATION');
    if (claimTypes.has('application')) roles.push('APPLICATION');
    if (
      claimTypes.has('creative') &&
      /^(imagine|considere|suponha|visualize)\b/i.test(text.trim())
    ) {
      roles.push('ILLUSTRATION');
    }
    // Categoria de treinamento da Fase 7 (transição explícita).
    const trainingCat = classifyTraining({ section: block.title, text });
    if (trainingCat === 'transition') roles.push('TRANSITION');

    structure.push({
      blockId: block.id,
      title: block.title,
      roles,
      wordCount: words.length,
      seconds,
      longestSentenceWords: longest,
    });
    perBlock.push({ blockId: block.id, seconds });
  });

  const addRole = (index: number, role: StructureRole) => {
    const entry = structure[index];
    if (entry && !entry.roles.includes(role)) {
      if (role === 'INTRODUCTION' || role === 'CONCLUSION') entry.roles.unshift(role);
      else entry.roles.push(role);
    }
  };

  // ---- Introdução (bloco 0, com sinais; nunca por posição pura) ----
  if (blocks.length >= 1) {
    const first = texts[0];
    const signals: string[] = [];
    if (/\?\s*$/.test(first.trim()) || /^(quem|o que|como|por que|será|imagine|considere)\b/i.test(first.trim())) {
      signals.push('abertura com pergunta ou cena');
    }
    if (titleOverlap(speech.title, first) >= 2) signals.push('apresenta o tema do título');
    if (containsMarker(first, INTRO_MARKERS)) signals.push('objetivo/roteiro declarado');
    if (signals.length >= 2) {
      addRole(0, 'INTRODUCTION');
      observations.push(
        observe('INTRODUCTION', 'INFO', [blocks[0].id], 'Introdução identificada.', `Sinais: ${signals.join('; ')}.`, undefined),
      );
    } else if (signals.length === 0 && blocks.length >= 2) {
      observations.push(
        observe(
          'STRUCTURE', 'INFO', [blocks[0].id],
          'Não encontrei estrutura suficiente para identificar uma introdução com segurança.',
          'O primeiro bloco não apresenta pergunta, tema do título, objetivo ou texto de abertura.',
          'Considere abrir com uma pergunta ou apresentar o tema e o objetivo.',
        ),
      );
    }
    // 1 sinal = ambíguo: silêncio (sem alerta agressivo).
  }

  // ---- Conclusão (último bloco) ----
  if (blocks.length >= 2) {
    const lastIdx = blocks.length - 1;
    const last = texts[lastIdx];
    const signals: string[] = [];
    if (containsMarker(last, CONCLUSION_MARKERS)) signals.push('retomada/resumo');
    if (titleOverlap(speech.title, last) >= 2) signals.push('retoma o tema central');
    if (containsMarker(last, CALL_MARKERS)) signals.push('aplicação ou chamada final');
    if (signals.length >= 2) {
      addRole(lastIdx, 'CONCLUSION');
      observations.push(
        observe('CONCLUSION', 'INFO', [blocks[lastIdx].id], 'Conclusão identificada.', `Sinais: ${signals.join('; ')}.`, undefined),
      );
    } else if (signals.length === 0) {
      observations.push(
        observe(
          'CONCLUSION', 'SUGGESTION', [blocks[lastIdx].id],
          'O encerramento existe, mas pode ficar mais conectado ao tema central.',
          'O último bloco não retoma o tema, não resume nem traz aplicação final.',
          'Termine retomando a ideia principal em 1–2 frases.',
        ),
      );
    }
  }

  // ---- Pontos (blocos substantivos entre intro/conclusão) ----
  const pointIndices: number[] = [];
  structure.forEach((entry, i) => {
    const isEdge =
      (i === 0 && entry.roles.includes('INTRODUCTION')) ||
      (i === blocks.length - 1 && entry.roles.includes('CONCLUSION'));
    if (!isEdge && entry.wordCount >= 20) {
      pointIndices.push(i);
      addRole(i, 'POINT');
    }
  });
  if (pointIndices.length > 0) {
    observations.push(
      observe(
        'POINT', 'INFO',
        pointIndices.map((i) => blocks[i].id),
        `${pointIndices.length} ponto(s) identificado(s).`,
        'Blocos substantivos com conteúdo próprio entre abertura e encerramento.',
        undefined,
      ),
    );
  }

  // ---- Transições entre blocos consecutivos ----
  for (let i = 0; i + 1 < blocks.length; i++) {
    const a = texts[i];
    const b = texts[i + 1];
    if (!a.trim() || !b.trim()) continue;
    const linked = endsWithConnector(a) || startsWithConnector(b);
    const overlap = jaccard(contentTokens(a), contentTokens(b));
    const aWords = a.split(/\s+/).filter(Boolean).length;
    const bWords = b.split(/\s+/).filter(Boolean).length;
    if (!linked && overlap < 0.08 && aWords >= 20 && bWords >= 20) {
      observations.push(
        observe(
          'TRANSITION', 'ATTENTION', [blocks[i].id, blocks[i + 1].id],
          `Possível transição fraca entre "${blocks[i].title}" e "${blocks[i + 1].title}".`,
          'O bloco seguinte começa outro assunto sem ligação explícita (sem conector e com vocabulário quase disjunto).',
          'Adicionar uma frase curta de ligação.',
        ),
      );
    }
  }

  // ---- Repetição (conservadora: frase literal ou Jaccard alto) ----
  const sentenceOwners = new Map<string, string[]>();
  blocks.forEach((block, i) => {
    for (const s of sentencesOf(texts[i])) {
      const key = s.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, '').replace(/\s+/g, ' ').trim();
      if (key.length < 25) continue;
      const owners = sentenceOwners.get(key) ?? [];
      if (!owners.includes(block.id)) owners.push(block.id);
      sentenceOwners.set(key, owners);
    }
  });
  const reportedPairs = new Set<string>();
  for (const [, owners] of sentenceOwners) {
    if (owners.length >= 2) {
      const pairKey = [...owners].sort().join('+');
      if (reportedPairs.has(pairKey)) continue;
      reportedPairs.add(pairKey);
      const titles = owners
        .map((id) => blocks.find((b) => b.id === id)?.title ?? id)
        .join(' e ');
      observations.push(
        observe(
          'REPETITION', 'ATTENTION', owners,
          `Possível repetição entre ${titles}.`,
          'A mesma frase (ou quase) aparece em mais de um bloco.',
          undefined,
        ),
      );
    }
  }
  for (let i = 0; i < blocks.length; i++) {
    for (let j = i + 1; j < blocks.length; j++) {
      const sim = jaccard(contentTokens(texts[i]), contentTokens(texts[j]));
      const wi = texts[i].split(/\s+/).filter(Boolean).length;
      const wj = texts[j].split(/\s+/).filter(Boolean).length;
      if (sim >= 0.5 && wi >= 30 && wj >= 30) {
        observations.push(
          observe(
            'REPETITION', 'ATTENTION', [blocks[i].id, blocks[j].id],
            `Blocos "${blocks[i].title}" e "${blocks[j].title}" desenvolvem ideias muito semelhantes.`,
            `Vocabulário próximo (similaridade ${sim.toFixed(2)}).`,
            undefined,
          ),
        );
      }
    }
  }

  // ---- Clareza ----
  structure.forEach((entry, i) => {
    if (entry.longestSentenceWords > 35) {
      observations.push(
        observe(
          'CLARITY', 'ATTENTION', [entry.blockId],
          `Frase muito longa no bloco "${entry.title}" (${entry.longestSentenceWords} palavras).`,
          'Frases longas dificultam a respiração e o acompanhamento no palco.',
          'Dividir a frase em duas e inserir uma pausa de 2 segundos.',
        ),
      );
    } else if (entry.longestSentenceWords >= 26 && texts[i].trim()) {
      observations.push(
        observe(
          'CLARITY', 'SUGGESTION', [entry.blockId],
          `Frase longa no bloco "${entry.title}" (${entry.longestSentenceWords} palavras).`,
          'Acima do confortável para fala ao vivo.',
          'Considere dividir a frase.',
        ),
      );
    }
  });

  // ---- Naturalidade (≥2 sinais; nunca fato absoluto) ----
  const FORMAL = new Set(FORMAL_WORDS);
  structure.forEach((entry, i) => {
    const sentences = sentencesOf(texts[i]).filter((s) => s.split(/\s+/).filter(Boolean).length >= 5);
    if (sentences.length === 0) return;
    const signals: string[] = [];
    const avgLen = sentences.reduce((a, s) => a + s.split(/\s+/).filter(Boolean).length, 0) / sentences.length;
    if (avgLen > 28) signals.push('frases em média longas');
    const openers = sentences.map((s) => (s.match(/[\p{L}]+/u) ?? [''])[0].toLowerCase());
    const openerCounts = new Map<string, number>();
    for (const o of openers) openerCounts.set(o, (openerCounts.get(o) ?? 0) + 1);
    if ([...openerCounts.values()].some((c) => c >= 3)) signals.push('aberturas de frase repetidas');
    const lower = texts[i].toLowerCase();
    if ([...FORMAL].some((w) => lower.includes(w))) signals.push('vocabulário excessivamente formal');
    if (signals.length >= 2) {
      observations.push(
        observe(
          'NATURALNESS', 'SUGGESTION', [entry.blockId],
          `O bloco "${entry.title}" pode soar mais natural.`,
          `Sinais: ${signals.join('; ')}.`,
          'Pode soar mais natural se variar as aberturas e simplificar o vocabulário.',
        ),
      );
    }
  });

  // ---- Equilíbrio (relativo; usuário decide se é intencional) ----
  const pointEntries = pointIndices.map((i) => structure[i]);
  if (pointEntries.length >= 3) {
    const counts = pointEntries.map((e) => e.wordCount);
    const max = Math.max(...counts);
    const min = Math.min(...counts.filter((c) => c > 0));
    if (min > 0 && max / min >= 2.5) {
      const longest = pointEntries[counts.indexOf(max)];
      const shortest = pointEntries[counts.indexOf(min)];
      observations.push(
        observe(
          'BALANCE', 'ATTENTION',
          [longest.blockId, shortest.blockId],
          `"${longest.title}" ocupa ~${(max / min).toFixed(1)}x mais texto que "${shortest.title}".`,
          'Diferença significativa de volume entre pontos.',
          'Isso pode ser intencional; se não for, vale revisar o equilíbrio.',
        ),
      );
    }
  }

  // ---- Flow linear: papéis em ordem de bloco (POINTs repetidos = pontos). ----
  for (const entry of structure) {
    for (const role of entry.roles) flow.push(role);
  }

  const totalSeconds = perBlock.reduce((a, b) => a + b.seconds, 0);
  const formattedTotal = formatTime(totalSeconds);
  observations.push(
    observe(
      'TIME', 'INFO', [],
      `Tempo estimado: ${formattedTotal} (ritmo ${wpm} ppm).`,
      'Soma das estimativas por bloco (palavras + pausas de palco).',
      undefined,
    ),
  );

  return {
    speechId: speech.id,
    version: ANALYSIS_VERSION,
    contentHash: speechContentHash(speech),
    flow,
    structure,
    observations,
    estimatedTime: {
      totalSeconds,
      formattedTotal,
      perBlock,
      wpm,
      disclaimer: TIME_DISCLAIMER,
    },
  };
}

export function analyzeSpeech(speech: Speech, options: AnalyzeOptions = {}): SpeechAnalysis {
  const wpm = options.wpm ?? DEFAULT_ANALYSIS_WPM;
  const key = analysisKeyFor(speech.id, speechContentHash(speech));
  if (!options.force) {
    const cached = analysisCache.get(key);
    if (cached && cached.estimatedTime.wpm === wpm) return cached;
  }
  const result = analyzeFresh(speech, wpm);
  analysisCache.put(key, result);
  return result;
}
