// Prompt compartilhado Gemini/Qwen — Fase 4 (§§8,9,10,11).
// Regras: criatividade na forma, fidelidade no conteúdo; ContextPack é a
// evidência autorizada; training (BE/TH) orienta técnica, não fatos.

import { emptyContextPack, type ContextPack } from './domain';
import type { LlmAction, LlmRequest, LlmTone } from './llmProvider';

export const INSUFFICIENT_EVIDENCE_MESSAGE = 'Não encontrei suporte suficiente nas fontes disponíveis.';

const SYSTEM_PROMPT = `Você é o "Better Talker Copilot", preparador de oratória e discursos.
Sua função é sugerir melhorias de tom, perguntas de raciocínio, ilustrações e aplicações práticas.

CRIATIVIDADE NA FORMA, FIDELIDADE NO CONTEÚDO:
- Você pode reorganizar ideias, melhorar clareza, sugerir transições, aplicações,
  ilustrações, introduções e conclusões.
- FONTES DE CONTEÚDO são a base para qualquer afirmação factual. ORIENTAÇÕES DE
  ORATÓRIA (BE/TH) ensinam TÉCNICA de apresentação — nunca as use como fonte de fatos.
- NUNCA invente ou adivinhe: fatos, estatísticas, citações, referências, títulos,
  páginas ou parágrafos. Não atribua ideias às publicações sem suporte no contexto.
- Não preencha lacunas com conhecimento geral apresentado como se viesse das fontes;
  pode explicar ou reformular o que as fontes sustentam.
- Se as fontes não sustentarem a resposta, diga exatamente: "${INSUFFICIENT_EVIDENCE_MESSAGE}"
  e oriente a importar a publicação pelo navegador oficial. Mantenha tom instrutivo e modesto.`;

export interface BuiltPrompt {
  system: string;
  user: string;
}

function serializePack(pack: ContextPack, legacyPassages: string[]): string {
  const content = pack.content_sources.slice(0, 8);
  const training = pack.training_sources.slice(0, 4);
  const legacy = legacyPassages.slice(0, 12 - content.length - training.length);

  let section = '';
  if (content.length > 0 || legacy.length > 0) {
    const lines = [
      ...content.map((s, i) => `[Fonte ${i + 1}: ${s.reference}] ${s.text}`),
      ...legacy.map((p, i) => `[Fonte ${content.length + i + 1}] ${p}`),
    ];
    section += `\n\n--- FONTES DE CONTEÚDO (base para afirmações factuais) ---\n${lines.join('\n\n')}\n--- FIM DAS FONTES DE CONTEÚDO ---\n`;
  }
  if (training.length > 0) {
    section += `\n\n--- ORIENTAÇÕES DE ORATÓRIA (técnica de apresentação; NÃO usar como fatos) ---\n${training.map((s, i) => `[Orientação ${i + 1}: ${s.reference}] ${s.text}`).join('\n\n')}\n--- FIM DAS ORIENTAÇÕES ---\n`;
  }
  if (!section) {
    section = `\n\n(Nenhuma fonte do acervo local foi recuperada para este bloco. Responda apenas com técnica geral de oratória ou diga que não há suporte suficiente.)\n`;
  }
  return section;
}

function blockSection(blockTitle?: string, blockMinutes?: number): string {
  if (!blockTitle && !blockMinutes) return '';
  return `\n\n--- BLOCO ATUAL ---\nTítulo: ${blockTitle || 'Sem título'}\nDuração: ${blockMinutes || '?'} minutos\n--- FIM DO BLOCO ---\n`;
}

function actionPrompt(action: LlmAction, text: string, tone: LlmTone, context: string, block: string): string {
  switch (action) {
    case 'hook':
      return `Crie 3 opções poderosas de ganchos de abertura (primeiros 30 segundos) para este bloco de discurso:\n"${text}"\n${context}${block}\nOpção 1: Pergunta retórica provocativa e incômoda.\nOpção 2: História breve ou paradoxo visual.\nOpção 3: Estatística ou afirmação contraintuitiva.`;
    case 'rewrite':
      return `Reescreva o trecho a seguir no tom "${tone}". Otimize o ritmo para fala ao vivo (evite períodos excessivamente longos, use ritmo cadenciado, tricolon e clareza). Baseie-se apenas nos princípios do acervo local fornecidos.\nTexto original:\n"${text}"\n${context}${block}`;
    case 'critique':
      return `Faça uma análise crítica de oratória deste bloco de discurso:\n"${text}"\n${context}${block}\nDestaque:\n1. Ponto mais forte (o que cativa).\n2. Ponto de vulnerabilidade (onde a plateia pode dispersar ou se cansar).\n3. Uma mudança prática que tornará a fala 2x mais memorável.`;
    case 'cues':
      return `Analise este trecho de discurso e insira marcadores de palco no texto como [Pausa 2s], [Ênfase Máxima] e [Olhar Plateia] nos momentos de maior carga dramática:\n"${text}"\n${context}${block}`;
    case 'shorten':
      return `Corte o excesso de palavras deste bloco de discurso sem perder a alma da mensagem. Torne-o direto, veloz e afiado para palco. Baseie-se apenas nos princípios do acervo local.\n"${text}"\n${context}${block}`;
    default:
      return `Analise o trecho de discurso a seguir com base apenas no acervo local fornecido:\n"${text}"\n${context}${block}`;
  }
}

export function buildLlmPrompt(request: LlmRequest): BuiltPrompt {
  const tone = request.tone ?? 'ted';
  const context = request.contextPack
    ? serializePack(request.contextPack, request.contextPassages ?? [])
    : serializePack(emptyContextPack(), request.contextPassages ?? []);
  const block = blockSection(request.blockTitle, request.blockMinutes);
  return {
    system: SYSTEM_PROMPT,
    user: actionPrompt(request.action, request.text, tone, context, block),
  };
}
