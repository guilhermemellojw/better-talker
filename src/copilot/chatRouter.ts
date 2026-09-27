// Roteamento natural do chat para a sessão oratória — Fase 20-D.
//
// Espelho conceitual de android/.../data/copilot/ChatRouter.kt.
// Decide QUAL pipeline atende a mensagem, sem LLM e sem formulário:
//   ORATORY           geração/iteração oratória (F20-A/B/C)
//   STRUCTURAL_QUERY  pergunta sobre a estrutura do S-34 (sem proposta)
//   PROPOSAL_REPLY    aceitar/rejeitar a proposta pendente (fluxo existente)
//   GENERAL           chat livre de sempre
// Precedência: réplica de proposta → sessão oratória (iteração/herança) →
// pedido oratório natural → pergunta estrutural → chat geral.
// Puro: sem rede, LLM, banco, UI.

import { normalizeTokenText } from './tokenize';
import type { S34Document } from './s34Parser';
import type { OratoryAction, OratoryMode } from './oratoryGeneration';
import {
  decideOratoryRequest,
  isOratoryRefinement,
  NOTHING_TO_REFINE_MESSAGE,
  type LastOratoryGeneration,
} from './oratorySession';

export type StructuralTopic = 'objective' | 'points' | 'sequence' | 'references' | 'about';

export type ChatRoute =
  | {
      type: 'oratory';
      mode: OratoryMode;
      sectionId: string | null;
      action: OratoryAction;
      inherited: boolean;
      reason: string;
    }
  | { type: 'structural-query'; topic: StructuralTopic; sectionNumber: number | null; reason: string }
  | { type: 'proposal-reply'; accept: boolean; reason: string }
  | { type: 'nothing-to-refine'; message: string; reason: string }
  | { type: 'out-of-scope'; requestedPoint: number; message: string; reason: string }
  | { type: 'general'; reason: string };

// ---------- Normalização de phráse natural → pedido canônico ----------

const REWRITES: Array<[RegExp, string]> = [
  [/como\s+(posso\s+)?(comecar|comeco|iniciar|abrir)\b.*/, 'Crie uma introdução'],
  [/como\s+(posso\s+)?(concluir|terminar|fechar|encerrar|finalizar)\b.*/, 'Faça uma conclusão'],
  [
    /como\s+(posso\s+)?(passo|passar|ligo|ligar|conecto|conectar|vou|ir)\b.*(proximo|proxima|seguinte|outro ponto).*/,
    'Crie uma transição',
  ],
  [/(me\s+)?ajud[ae]\s+a\s+desenvolver\b.*/, 'Desenvolva este ponto'],
  [/(pode|poderia)\s+(explicar|elaborar|detalhar|aprofundar)\s+(melhor\s+)?(o\s+)?ponto\s*(\d{1,2}).*/, 'Desenvolva o ponto 1'],
  [/quero\s+desenvolver\s+(o\s+)?ponto\s*(\d{1,2}).*/, 'Desenvolva o ponto 1'],
  [/como\s+(eu\s+)?desenvolvo\s+(o\s+)?(esse|este|isso|ponto).*/, 'Desenvolva este ponto'],
  [/como\s+(posso\s+)?(comecar|iniciar)\s+(esse|este)\s+(discurso|tema).*/, 'Crie uma introdução'],
];

/** Reescreve phráse natural para o vocabulário canônico; null = sem reescrita. */
export function normalizeOratoryPhrasing(text: string): string | null {
  const t = ` ${normalizeTokenText(text)} `;
  for (const [re, canonical] of REWRITES) {
    const m = re.exec(t);
    if (!m) continue;
    // Varre TODOS os grupos: o índice do número varia por regex.
    const n = m
      .slice(1)
      .map((g) => parseInt(g ?? '', 10))
      .find((x) => !Number.isNaN(x) && x >= 1 && x <= 99);
    if (n != null && canonical.includes('ponto 1')) {
      return canonical.replace('ponto 1', `ponto ${n}`);
    }
    return canonical;
  }
  return null;
}

// ---------- Réplica de proposta ----------

const ACCEPT_RE = /^(aceitar|aceito|aplicar|aplique|confirmar|confirmo)\b/;
const REJECT_RE = /^(rejeitar|rejeito|descartar|descarte|dispensar|recusar)\b/;

// ---------- Pergunta estrutural ----------

const QUESTION_OPENERS =
  /^(qual|quais|quanto|quantos|quantas|o que|que|existe|existem|tem|ha|mostre|mostrar|liste|listar|resuma|resumir|onde|quem|quando)\b/;
/** Meta-linguagem: fala SOBRE o termo, não pede geração. */
const META_WORDS = /\b(termo|palavra|significa|significado|quer dizer)\b/;

const TOPIC_OBJECTIVE = /\b(objetivo|proposito|meta|tema)\b/;
const TOPIC_POINTS = /\b(pontos principais|pontos|topicos|secoes|estrutura)\b/;
const TOPIC_SEQUENCE = /\b(sequencia|ordem|primeiro|ultimo|antes|depois)\b/;
const TOPIC_REFERENCES = /\b(referencias|referencia|textos|versiculos|publicacoes|publicacao|citac|w\d{2})\b/;

function structuralTopic(t: string): StructuralTopic | null {
  if (META_WORDS.test(t)) return null;
  const trimmed = t.trim();
  const isQuestion = trimmed.endsWith('?') || QUESTION_OPENERS.test(trimmed);
  if (!isQuestion) return null;
  if (TOPIC_REFERENCES.test(t)) return 'references';
  if (TOPIC_OBJECTIVE.test(t)) return 'objective';
  // Sequência antes de "pontos": "sequência dos pontos" é ordem.
  if (TOPIC_SEQUENCE.test(t)) return 'sequence';
  if (TOPIC_POINTS.test(t)) return 'points';
  if (/\bs\s*34\b/.test(t)) return 'about';
  return null;
}

/** Ponto pedido ("ponto 2"), reutilizando o helper da sessão. */
function requestedPointOf(text: string): number | null {
  const t = ` ${normalizeTokenText(text)} `;
  const m = /(ponto|topico|secao|parte)\s*(n\.?\s*)?(\d{1,2})/.exec(t);
  if (m) return parseInt(m[3], 10);
  const bare = /\b(\d{1,2})\b/.exec(t);
  return bare ? parseInt(bare[1], 10) : null;
}

export function routeNaturalChat(
  text: string,
  document: S34Document | null,
  currentSectionId: string | null,
  last: LastOratoryGeneration | null,
): ChatRoute {
  const t = ` ${normalizeTokenText(text)} `;

  // 1) Réplica de proposta: fluxo existente, nunca o LLM.
  if (ACCEPT_RE.test(t.trim())) return { type: 'proposal-reply', accept: true, reason: 'resposta de aceite' };
  if (REJECT_RE.test(t.trim())) return { type: 'proposal-reply', accept: false, reason: 'resposta de rejeição' };

  // Meta-linguagem vence o roteamento oratório.
  if (META_WORDS.test(t)) return { type: 'general', reason: 'meta-linguagem sobre um termo' };

  // 2)/3) Oratória: pedido explícito, phráse natural ou iteração.
  const canonical = normalizeOratoryPhrasing(text);
  const decision = decideOratoryRequest(canonical ?? text, document, currentSectionId, last);
  if (decision.kind === 'generate') {
    return {
      type: 'oratory',
      mode: decision.mode,
      sectionId: decision.sectionId,
      action: decision.action,
      inherited: decision.inherited,
      reason: decision.inherited ? 'iteração da sessão oratória' : 'pedido oratório explícito',
    };
  }
  if (decision.kind === 'out-of-structural-scope') {
    return {
      type: 'out-of-scope',
      requestedPoint: decision.requestedPoint,
      message: decision.message,
      reason: 'pedido de estrutura fora do S-34',
    };
  }
  if (isOratoryRefinement(text)) {
    return {
      type: 'nothing-to-refine',
      message: NOTHING_TO_REFINE_MESSAGE,
      reason: 'refinamento sem sessão oratória',
    };
  }

  // 4) Pergunta estrutural sobre o S-34 (sem proposta).
  if (document) {
    const topic = structuralTopic(t);
    if (topic) {
      return {
        type: 'structural-query',
        topic,
        sectionNumber: requestedPointOf(text),
        reason: 'pergunta sobre a estrutura do S-34',
      };
    }
  }

  // 5) Chat geral.
  return { type: 'general', reason: 'chat livre' };
}

/** Diagnóstico para teste/log: nunca inclui texto do documento nem chave. */
export function describeChatRoute(route: ChatRoute): string {
  switch (route.type) {
    case 'oratory':
      return `route=ORATORY mode=${route.mode} section=${route.sectionId ?? '—'} action=${route.action} inherited=${route.inherited} reason=${route.reason}`;
    case 'structural-query':
      return `route=STRUCTURAL_QUERY topic=${route.topic} section=${route.sectionNumber ?? '—'} reason=${route.reason}`;
    case 'proposal-reply':
      return `route=PROPOSAL_REPLY accept=${route.accept} reason=${route.reason}`;
    case 'nothing-to-refine':
      return `route=NOTHING_TO_REFINE reason=${route.reason}`;
    case 'out-of-scope':
      return `route=OUT_OF_SCOPE point=${route.requestedPoint} reason=${route.reason}`;
    case 'general':
      return `route=GENERAL reason=${route.reason}`;
  }
}
