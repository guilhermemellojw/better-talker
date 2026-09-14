export interface AICallOptions {
  apiKey?: string;
  tone?: 'ted' | 'pitch' | 'motivational' | 'academic' | 'humorous';
  text: string;
  action: 'hook' | 'rewrite' | 'critique' | 'cues' | 'shorten';
  contextPassages?: string[];
  blockTitle?: string;
  blockMinutes?: number;
}

export async function queryGeminiOratoryCoach(options: AICallOptions): Promise<string> {
  const { apiKey, text, action, tone = 'ted', contextPassages = [], blockTitle, blockMinutes } = options;

  if (!apiKey || apiKey.trim() === '') {
    return getOfflineSimulatedResponse(action, text, tone);
  }

  const systemPrompt = `Você é o "Better Talker Copilot", o mais experiente preparador de oratória e discursos para palestrantes do TED, líderes executivos e oradores de grande palco.
Sua função é sugerir melhorias de tom, perguntas de raciocínio, ilustrações e aplicações práticas baseadas estritamente nos princípios de ensino presentes no acervo de oratória importado pelo usuário. Mantenha um tom instrutivo, modesto e focado em clareza.
Regra fundamental: NUNCA invente ou adivinhe o conteúdo de uma citação. Se o usuário perguntar sobre uma publicação ou citação que não esteja no acervo local, informe explicitamente que a citação não foi encontrada e oriente-o a baixar a publicação via navegador oficial. Não tente fornecer texto que não exista no acervo.`;

  let contextSection = '';
  if (contextPassages.length > 0) {
    contextSection = `\n\n--- INFORMAÇÕES DO ACERVO LOCAL ---\n${contextPassages.map((p, i) => `Trecho ${i + 1}: ${p}`).join('\n\n')}\n--- FIM DO ACERVO ---\n`;
  }

  let blockContext = '';
  if (blockTitle || blockMinutes) {
    blockContext = `\n\n--- BLOCO ATUAL ---\nTítulo: ${blockTitle || 'Sem título'}\nDuração: ${blockMinutes || '?'} minutos\n--- FIM DO BLOCO ---\n`;
  }

  let userPrompt = '';
  switch (action) {
    case 'hook':
      userPrompt = `Crie 3 opções poderosas de ganchos de abertura (primeiros 30 segundos) para este bloco de discurso:
"${text}"
${contextSection}${blockContext}
Opção 1: Pergunta retórica provocativa e incômoda.
Opção 2: História breve ou paradoxo visual.
Opção 3: Estatística ou afirmação contraintuitiva.`;
      break;

    case 'rewrite':
      userPrompt = `Reescreva o trecho a seguir no tom "${tone}". Otimize o ritmo para fala ao vivo (evite períodos excessivamente longos, use ritmo cadenciado, tricolon e clareza). Baseie-se apenas nos princípios do acervo local fornecidos.
Texto original:
"${text}"
${contextSection}${blockContext}`;
      break;

    case 'critique':
      userPrompt = `Faça uma análise crítica de oratória deste bloco de discurso:
"${text}"
${contextSection}${blockContext}
Destaque:
1. Ponto mais forte (o que cativa).
2. Ponto de vulnerabilidade (onde a plateia pode dispersar ou se cansar).
3. Uma mudança prática que tornará a fala 2x mais memorável.`;
      break;

    case 'cues':
      userPrompt = `Analise este trecho de discurso e insira marcadores de palco no texto como [Pausa 2s], [Ênfase Máxima] e [Olhar Plateia] nos momentos de maior carga dramática:
"${text}"
${contextSection}${blockContext}`;
      break;

    case 'shorten':
      userPrompt = `Corte o excesso de palavras deste bloco de discurso sem perder a alma da mensagem. Torne-o direto, veloz e afiado para palco. Baseie-se apenas nos princípios do acervo local.
"${text}"
${contextSection}${blockContext}`;
      break;
  }

  try {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [
          {
            role: 'user',
            parts: [{ text: `${systemPrompt}\n\n${userPrompt}` }],
          },
        ],
        generationConfig: {
          temperature: 0.2,
          maxOutputTokens: 1000,
        },
      }),
    });

    if (!response.ok) {
      throw new Error(`Erro na API Gemini: ${response.statusText}`);
    }

    const data = await response.json();
    const candidateText = data?.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!candidateText) {
      throw new Error('Nenhuma resposta retornada pelo modelo');
    }

    return candidateText;
  } catch (error) {
    console.warn('Erro ao chamar Gemini API online, acionando motor offline:', error);
    return getOfflineSimulatedResponse(action, text, tone);
  }
}

function getOfflineSimulatedResponse(action: string, text: string, tone: string): string {
  switch (action) {
    case 'hook':
      return `### 🎙️ 3 Ganchos Sugeridos pelo Copilot (Modo Offline):

1. **Provocação Direta:** "Se tudo o que você aprendeu sobre esse tema estivesse errado nos últimos 5 anos... por onde você recomeçaria?"
2. **Paradoxo Humano:** "Nós vivemos na era com maior volume de comunicação da história da humanidade, mas nunca nos sentimos tão pouco escutados."
3. **Ponto de Tensão:** "O maior erro não é falar em público. O maior erro é ter algo valioso a dizer e escolher o conforto do silêncio."`;

    case 'rewrite':
      return `### ✍️ Versão Polida para Palco (Tom: ${tone.toUpperCase()})

"${text.trim()} <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span> Não é sobre dizer mais palavras; é sobre fazer cada palavra ecoar na memória de quem escuta. <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase</span>"`;

    case 'critique':
      return `### 🔍 Raio-X de Oratória (Treinador de Palco):

- **Ponto Forte:** O tema toca diretamente na emoção da plateia e tem excelente potencial de identificação imediata.
- **Ponto de Atenção:** Frases longas podem acelerar seus batimentos cardíacos. Coloque pausas de 2 segundos antes de mudar de assunto.
- **Dica de Ouro de Palco:** Ao chegar na frase principal, dê dois passos lentos para a frente no palco, faça contato visual direto e fale 20% mais baixo para forçar a atenção total.`;

    case 'cues':
      return `### 🎭 Sugestão com Marcadores de Palco Inseridos:

"${text.slice(0, 80)} <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Pausa</span> ${text.slice(80, 160)} <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase Máxima</span> ${text.slice(160)} <span class="stage-cue-badge cue-eye" data-cue-type="eye-contact" contenteditable="false">👁 Olhar Plateia</span>"`;

    case 'shorten':
      return `### ⚡ Versão Concisa e Direta:

"${text.split('. ')[0] || text}. Seja direto. Seja autêntico. A plateia agradece a objetividade."`;

    default:
      return `Texto analisado com sucesso pelo Copilot de Oratória.`;
  }
}
