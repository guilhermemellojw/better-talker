// Motor de conversa do Copilot — Fase 15 (chat experience & prompt UX).
// Princípio: o usuário conversa; o sistema monta contexto/intenção/brief.
// O prompt final (fontes, regras CONTENT/TRAINING) é montado pelo
// llmPrompt.ts compartilhado — nenhuma regra de fidelidade é duplicada.
// Lógica pura (sem DOM) para testes; a UI decide quando chamar o provider.
//
// Preserva (§38): retrieval, hybridRetriever, ContextPack, verification,
// proposal parser, edit history e provider factory intocados.

import { normalizeTokenText } from './tokenize';
import type { TrainingCategory } from './domain';
import type { LlmAction, LlmTone } from './llmProvider';

/** Papel de cada mensagem na thread. */
export type ChatRole = 'user' | 'assistant';

/** Mensagem da conversa (modelo de thread — §19: mínimo para continuidade). */
export interface ChatMessage {
  id: string;
  role: ChatRole;
  text: string;
  /** Título do bloco ativo quando a mensagem foi escrita (contexto visual). */
  blockTitle?: string;
  createdAt: number;
}

/** Ação LLM + trilho de treinamento inferidos da mensagem livre. */
export interface InferredIntent {
  action: LlmAction;
  /** Categoria BE/TH a consultar, ou null = só conteúdo (§15). */
  trainingCategory: TrainingCategory | null;
  /** true quando a mensagem pede verificação factual (prioriza CONTENT). */
  verification: boolean;
}

/** Roteamento interno de intenção (§12). Nunca exposto como formulário. */
export function inferIntent(message: string, isFirstMessage: boolean): InferredIntent {
  const norm = ` ${normalizeTokenText(message)} `;
  const has = (patterns: RegExp) => patterns.test(norm);

  // Verificação factual pede trilho CONTENT (nunca training como fonte).
  if (
    has(
      /(esta correta|esta certo|esta mesmo|realmente esta|realmente esta na|conferir|confira|verificar|verifique|tem apoio|suporte)/,
    )
  ) {
    return { action: 'critique', trainingCategory: null, verification: true };
  }
  if (has(/(introducao|abertura|comeco|inicio|ganchos?|hook)/)) {
    return { action: 'hook', trainingCategory: 'introduction', verification: false };
  }
  if (has(/(ilustracao|ilustra|analogia|exemplo|metafora|historia)/)) {
    return { action: 'cues', trainingCategory: 'illustration', verification: false };
  }
  if (has(/(aplicacao|aplicar|pratica|como aplico)/)) {
    return { action: 'rewrite', trainingCategory: 'application', verification: false };
  }
  if (has(/(transicao|ligacao|ponte)/)) {
    return { action: 'rewrite', trainingCategory: 'transition', verification: false };
  }
  if (has(/(natural|naturalidade|seca|artificial|espontane|solto|roboti)/)) {
    return { action: 'rewrite', trainingCategory: 'naturalness', verification: false };
  }
  if (has(/(mais curta|curta|resumir|resumo|encurtar|enxugar|cortar|conciso|direto ao ponto|mais simples|simplificar|simplific)/)) {
    return { action: 'shorten', trainingCategory: 'clarity', verification: false };
  }
  if (has(/(melhor|melhore|refinar|aprimorar|polir)/)) {
    return {
      action: 'rewrite',
      trainingCategory: isFirstMessage ? 'clarity' : 'naturalness',
      verification: false,
    };
  }
  if (has(/(apresentar|falar|dizer|entrega|palco|oral)/)) {
    return { action: 'critique', trainingCategory: 'delivery', verification: false };
  }
  // Perguntas factuais/genéricas: só conteúdo (§15).
  return { action: 'critique', trainingCategory: null, verification: false };
}

/** Contexto de conversa encaminhado ao prompt — limite razoável (§18). */
export const MAX_HISTORY_MESSAGES = 6;
/** Caracteres máximos por mensagem ao serializar o histórico. */
const MAX_HISTORY_CHARS = 500;

/**
 * Serializa as últimas mensagens para continuidade (§18: sem histórico
 * ilimitado, sem memória global permanente). Consumido pelo llmPrompt.
 */
export function recentHistory(messages: ChatMessage[], limit = MAX_HISTORY_MESSAGES): string {
  const recent = messages.filter((m) => m.text.trim().length > 0).slice(-limit);
  if (recent.length === 0) return '';
  const lines = recent.map((m) => {
    const who = m.role === 'user' ? 'Usuário' : 'Copilot';
    const text = m.text.length > MAX_HISTORY_CHARS ? `${m.text.slice(0, MAX_HISTORY_CHARS)}…` : m.text;
    return `${who}: ${text}`;
  });
  return `--- CONVERSA ANTERIOR (contexto; continue a partir dela) ---\n${lines.join('\n')}\n--- FIM DA CONVERSA ANTERIOR ---\n`;
}

/** Brief interno do chat — vira `brief` no LlmRequest (§37: invisível ao usuário). */
export interface ChatBrief {
  /** Mensagem literal do usuário. */
  message: string;
  /** Histórico anterior à mensagem atual (sem incluir a própria mensagem). */
  history: ChatMessage[];
  /** true quando é a primeira mensagem da conversa. */
  isFirstMessage: boolean;
}

/** Campos do brief em texto técnico (usados pelo llmPrompt apenas). */
export function chatBriefToText(brief: ChatBrief): string {
  const parts: string[] = [];
  const historySection = recentHistory(brief.history);
  if (historySection) parts.push(historySection);
  if (!brief.isFirstMessage) {
    parts.push(
      'Continuidade: esta mensagem continua a conversa anterior — "isso/essa parte" referem-se ao que já foi discutido. Não peça informações que já foram dadas.',
    );
  }
  parts.push(`Mensagem do usuário: "${brief.message}"`);
  return parts.join('\n\n');
}

/** Tom de palco equivalente (reuso dos tones existentes). */
export type ChatTone = LlmTone;

/** Validação de envio (§40.1–3): vazia/whitespace rejeitadas. */
export function validateOutgoingMessage(
  raw: string,
): { ok: true; text: string } | { ok: false; reason: 'empty' } {
  const text = raw.trim();
  if (!text) return { ok: false, reason: 'empty' };
  return { ok: true, text };
}

/** Erros de provider viram mensagem humana e acionável (§30). */
export function friendlyChatError(code: string): string {
  switch (code) {
    case 'cancelled':
      return 'Geração cancelada. Nada foi alterado no discurso.';
    case 'timeout':
      return 'Não consegui gerar a resposta a tempo. Verifique a conexão e tente novamente.';
    case 'authentication':
      return 'A chave de IA não está válida. Confira nas Configurações para usar o Copilot remoto.';
    case 'rate_limit':
      return 'Muitas perguntas seguidas. Espere um momento e tente de novo.';
    case 'network':
      return 'Não consegui gerar a resposta agora.\nVerifique a conexão ou tente novamente.';
    case 'unavailable':
      return 'O assistente remoto está indisponível agora. Tente novamente em instantes.';
    case 'invalid_request':
    case 'invalid_response':
    default:
      return 'Não consegui gerar a resposta agora. Tente novamente.';
  }
}

/** Aviso de offline — remoto indisponível, local continua (§47). */
export const OFFLINE_CHAT_NOTICE =
  'Você está offline.\nO Copilot remoto não está disponível, mas os recursos locais continuam funcionando.';

/** Sugestões pós-resposta (§23): preenchem/disparam intenção equivalente. */
export const FOLLOW_UP_SUGGESTIONS = ['Mais natural', 'Mais curta', 'Outra versão'] as const;

/** Quick actions da abertura (§8): atalhos opcionais, nunca bloqueadores. */
export interface QuickAction {
  id: string;
  label: string;
  /** Mensagem enviada como se o usuário tivesse digitado (mesma lógica do chat livre). */
  message: string;
}

export const QUICK_ACTIONS: QuickAction[] = [
  { id: 'improve-point', label: 'Melhorar este ponto', message: 'Melhore este ponto.' },
  { id: 'natural', label: 'Deixar mais natural', message: 'Deixe mais natural.' },
  { id: 'illustration', label: 'Criar ilustração', message: 'Crie uma ilustração para esse ponto.' },
  { id: 'verify', label: 'Verificar', message: 'Confira se isso tem apoio nas fontes.' },
];

/** Rótulo simples de contexto para a UI (§37): nada de ids técnicos. */
export function contextLabel(blockTitle?: string, speechTitle?: string): string | null {
  if (blockTitle && blockTitle.trim()) return `Contexto: ${blockTitle.trim()}`;
  if (speechTitle && speechTitle.trim()) return `Contexto: ${speechTitle.trim()}`;
  return null;
}
