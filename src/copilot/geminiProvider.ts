// GeminiProvider — Fase 4: timeout, retry controlado, cancelamento,
// erros de domínio e observabilidade. Sem chave => motor offline explícito.
// Erro remoto NÃO cai mais em fallback offline silencioso (§§4,12,19).

import { buildLlmPrompt } from './llmPrompt';
import { postJsonWithRetry } from './llmHttp';
import { ProviderError } from './llmErrors';
import {
  DEFAULT_LLM_MAX_ATTEMPTS,
  DEFAULT_LLM_TIMEOUT_MS,
  type LlmProvider,
  type ChatLlmAction,
  type LlmRequest,
  type LlmResponse,
} from './llmProvider';

function getOfflineSimulatedResponse(action: ChatLlmAction, text: string, tone: string): string {
  switch (action) {
    case 'hook':
      return `### 🎙️ 3 Ganchos Sugeridos pelo Copilot (Modo Offline):\n\n1. **Provocação Direta:** "Se tudo o que você aprendeu sobre esse tema estivesse errado nos últimos 5 anos... por onde você recomeçaria?"\n2. **Paradoxo Humano:** "Nós vivemos na era com maior volume de comunicação da história da humanidade, mas nunca nos sentimos tão pouco escutados."\n3. **Ponto de Tensão:** "O maior erro não é falar em público. O maior erro é ter algo valioso a dizer e escolher o conforto do silêncio."`;
    case 'rewrite':
      return `### ✍️ Versão Polida para Palco (Tom: ${tone.toUpperCase()})\n\n"${text.trim()} <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span> Não é sobre dizer mais palavras; é sobre fazer cada palavra ecoar na memória de quem escuta. <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase</span>"`;
    case 'critique':
      return `### 🔍 Raio-X de Oratória (Treinador de Palco):\n\n- **Ponto Forte:** O tema toca diretamente na emoção da plateia e tem excelente potencial de identificação imediata.\n- **Ponto de Atenção:** Frases longas podem acelerar seus batimentos cardíacos. Coloque pausas de 2 segundos antes de mudar de assunto.\n- **Dica de Ouro de Palco:** Ao chegar na frase principal, dê dois passos lentos para a frente no palco, faça contato visual direto e fale 20% mais baixo para forçar a atenção total.`;
    case 'cues':
      return `### 🎭 Sugestão com Marcadores de Palco Inseridos:\n\n"${text.slice(0, 80)} <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span> ${text.slice(80, 160)} <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase Máxima</span> ${text.slice(160)} <span class="stage-cue-badge cue-eye" data-cue-type="eye-contact" contenteditable="false">👁 Olhar Plateia</span>"`;
    case 'shorten':
      return `### ⚡ Versão Concisa e Direta:\n\n"${text.split('. ')[0] || text}. Seja direto. Seja autêntico. A plateia agradece a objetividade."`;
    case 'chat':
      // Fase 15: offline local mantém resposta útil e honesta sobre a limitação.
      return `Modo offline: não tenho acesso ao assistente remoto agora, mas posso ajudar localmente.\n\nSobre "${text.slice(0, 120)}${text.length > 120 ? '…' : ''}": uma técnica de apresentação que costuma ajudar é reescrever em frases curtas, com uma ideia por frase, e marcar onde fazer pausa. Use as Dicas Retóricas abaixo e a Análise do discurso — tudo funciona sem conexão.`;
    default:
      return `Texto analisado com sucesso pelo Copilot de Oratória.`;
  }
}

interface GeminiResponse {
  candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
}

export class GeminiProvider implements LlmProvider {
  readonly id = 'gemini';
  readonly model: string;
  private apiKey: string;
  private timeoutMs: number;
  private maxAttempts: number;

  constructor(apiKey: string, model = 'gemini-2.5-flash', timeoutMs = DEFAULT_LLM_TIMEOUT_MS, maxAttempts = DEFAULT_LLM_MAX_ATTEMPTS) {
    this.apiKey = apiKey;
    this.model = model;
    this.timeoutMs = timeoutMs;
    this.maxAttempts = maxAttempts;
  }

  async generate(request: LlmRequest): Promise<LlmResponse> {
    const tone = request.tone ?? 'ted';
    const started = Date.now();

    // Modo offline explícito: só quando NÃO há chave (§12).
    if (!this.apiKey || this.apiKey.trim() === '') {
      // Fase 15: proposta de edição offline é determinística e honesta —
      // reescrita com o texto preservado; conteúdo novo exige remoto.
      if (request.responseFormat === 'edit-proposal' && request.editMode) {
        if (request.editMode === 'insert') {
          throw new ProviderError(
            'unavailable',
            'Sugestões de conteúdo novo precisam do assistente remoto. Configure a chave de IA nas Configurações.',
            this.id,
            0,
          );
        }
        const safe = request.text.slice(0, 4000).replace(/\s+/g, ' ').trim();
        const offlineProposalJson = JSON.stringify({
          explanation: 'Modo offline: nenhuma nova versão foi criada — o texto atual é mantido como está. Configure a chave de IA para sugestões de reescrita.',
          operations: [{ type: 'replace', content: `<p>${safe}</p>` }],
        });
        const offlineProposal = '```json\n' + offlineProposalJson + '\n```';
        return {
          text: offlineProposal,
          meta: { providerId: this.id, model: `${this.model}+offline`, durationMs: Date.now() - started, attempts: 0, offline: true },
        };
      }
      return {
        text: getOfflineSimulatedResponse(request.action, request.text, tone),
        meta: { providerId: this.id, model: `${this.model}+offline`, durationMs: Date.now() - started, attempts: 0, offline: true },
      };
    }

    const prompt = buildLlmPrompt(request);
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${this.model}:generateContent?key=${this.apiKey}`;
    const { data, attempts } = await postJsonWithRetry(
      url,
      {
        contents: [{ role: 'user', parts: [{ text: `${prompt.system}\n\n${prompt.user}` }] }],
        generationConfig: { temperature: 0.2, maxOutputTokens: 1000 },
      },
      {
        providerId: this.id,
        model: this.model,
        timeoutMs: request.timeoutMs ?? this.timeoutMs,
        maxAttempts: request.maxAttempts ?? this.maxAttempts,
        signal: request.signal,
      },
    );

    // §5: valida antes de entregar ao Copilot; inválido => invalid_response.
    const candidateText = (data as GeminiResponse)?.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!candidateText || typeof candidateText !== 'string') {
      throw new ProviderError('invalid_response', 'Resposta do Gemini em formato inesperado.', this.id, attempts);
    }
    return {
      text: candidateText,
      meta: { providerId: this.id, model: this.model, durationMs: Date.now() - started, attempts, offline: false },
    };
  }
}
