import type { SpeechMetrics, CopilotSuggestion, StageCueDefinition } from '../types/speech';

export const STAGE_CUES: StageCueDefinition[] = [
  {
    id: 'pause-2s',
    label: 'Pausa 2s',
    icon: '⏸️',
    durationSeconds: 2,
    badgeClass: 'cue-pause',
    tooltip: 'Pausa curta para absorção da ideia',
  },
  {
    id: 'pause-3s',
    label: 'Pausa 3s',
    icon: '⏳',
    durationSeconds: 3,
    badgeClass: 'cue-pause-long',
    tooltip: 'Pausa reflexiva antes ou após uma revelação',
  },
  {
    id: 'pause-5s',
    label: 'Pausa 5s (Palco)',
    icon: '🛑',
    durationSeconds: 5,
    badgeClass: 'cue-pause-deep',
    tooltip: 'Silêncio teatral absoluto para suspense ou clímax',
  },
  {
    id: 'emphasis',
    label: 'Ênfase Máxima',
    icon: '⚡',
    badgeClass: 'cue-emphasis',
    tooltip: 'Aumentar intensidade e firmeza vocal',
  },
  {
    id: 'eye-contact',
    label: 'Olhar Plateia',
    icon: '👁️',
    badgeClass: 'cue-eye',
    tooltip: 'Conectar visualmente com o fundo ou uma pessoa específica',
  },
  {
    id: 'whisper',
    label: 'Voz Baixa / Segredo',
    icon: '🤫',
    badgeClass: 'cue-whisper',
    tooltip: 'Falar mais baixo para criar intimidade e aproximação',
  },
  {
    id: 'applause',
    label: 'Pausa p/ Aplausos / Risos',
    icon: '👏',
    durationSeconds: 4,
    badgeClass: 'cue-applause',
    tooltip: 'Aguardar a reação do público sem atropelar',
  },
  {
    id: 'gesture',
    label: 'Gesto Aberto',
    icon: '👐',
    badgeClass: 'cue-gesture',
    tooltip: 'Abrir braços ou sinalizar com as mãos',
  },
  {
    id: 'pace-up',
    label: 'Acelerar Ritmo',
    icon: '⏩',
    badgeClass: 'cue-pace-up',
    tooltip: 'Aumentar cadência para transmitir urgência ou entusiasmo',
  },
  {
    id: 'pace-down',
    label: 'Desacelerar Ritmo',
    icon: '🐌',
    badgeClass: 'cue-pace-down',
    tooltip: 'Cadência lenta e solene para mensagens profundas',
  },
];

const COMMON_FILLERS = [
  'tipo',
  'né',
  'então',
  'daí',
  'quer dizer',
  'com certeza',
  'basicamente',
  'literalmente',
  'a nível de',
  'enfim',
  'tá ligado',
  'ééé',
  'na verdade',
];

export function extractPlainTextFromHtml(html: string): string {
  const tempDiv = document.createElement('div');
  tempDiv.innerHTML = html;
  
  // Remove stage cue badges from plain text count so they don't corrupt word metrics
  const badges = tempDiv.querySelectorAll('.stage-cue-badge');
  badges.forEach((b) => b.remove());

  return tempDiv.innerText || tempDiv.textContent || '';
}

export function calculateSpeechMetrics(
  contentHtml: string,
  targetWpm: number = 130,
  targetMinutes: number = 5
): SpeechMetrics {
  const plainText = extractPlainTextFromHtml(contentHtml);
  
  // Words
  const words = plainText
    .trim()
    .split(/\s+/)
    .filter((w) => w.length > 0);
  const wordCount = words.length;

  // Characters
  const characterCount = plainText.length;

  // Paragraphs
  const paragraphs = plainText
    .split(/\n+/)
    .filter((p) => p.trim().length > 0);
  const paragraphCount = Math.max(1, paragraphs.length);

  // Parse stage cue badges directly from html
  const pauseMatches = contentHtml.match(/data-cue-type="([^"]+)"/g) || [];
  let pauseCount = 0;
  let totalPauseDurationSeconds = 0;

  pauseMatches.forEach((m) => {
    const type = m.replace('data-cue-type="', '').replace('"', '');
    if (type.startsWith('pause-') || type === 'applause') {
      pauseCount++;
      if (type === 'pause-2s') totalPauseDurationSeconds += 2;
      else if (type === 'pause-3s') totalPauseDurationSeconds += 3;
      else if (type === 'pause-5s') totalPauseDurationSeconds += 5;
      else if (type === 'applause') totalPauseDurationSeconds += 4;
    }
  });

  // Estimated speaking time
  const speakingSecondsFromWords = (wordCount / (targetWpm || 130)) * 60;
  const estimatedTimeSeconds = Math.round(speakingSecondsFromWords + totalPauseDurationSeconds);

  const minutes = Math.floor(estimatedTimeSeconds / 60);
  const seconds = estimatedTimeSeconds % 60;
  const formattedEstimatedTime = `${minutes}m ${seconds.toString().padStart(2, '0')}s`;

  // Filler words detector
  const lowerText = plainText.toLowerCase();
  const fillerWordsFound: { word: string; count: number }[] = [];
  let totalFillers = 0;

  COMMON_FILLERS.forEach((filler) => {
    const regex = new RegExp(`\\b${filler}\\b`, 'gi');
    const matches = lowerText.match(regex);
    if (matches && matches.length > 0) {
      fillerWordsFound.push({ word: filler, count: matches.length });
      totalFillers += matches.length;
    }
  });

  // Longest sentence
  const sentences = plainText
    .split(/[.!?]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  
  let longestSentenceWordCount = 0;
  sentences.forEach((s) => {
    const count = s.split(/\s+/).filter(Boolean).length;
    if (count > longestSentenceWordCount) longestSentenceWordCount = count;
  });

  let readingEase: SpeechMetrics['readingEase'] = 'Fluida e Acessível';
  if (longestSentenceWordCount > 35 || (wordCount > 100 && totalFillers > 10)) {
    readingEase = 'Muito Complexa / Frases Longas';
  } else if (longestSentenceWordCount > 22) {
    readingEase = 'Moderada';
  }

  const differenceToTargetMinutes = +(estimatedTimeSeconds / 60 - targetMinutes).toFixed(1);

  return {
    wordCount,
    characterCount,
    paragraphCount,
    pauseCount,
    totalPauseDurationSeconds,
    estimatedTimeSeconds,
    formattedEstimatedTime,
    differenceToTargetMinutes,
    readingEase,
    fillerWordsFound,
    totalFillers,
    longestSentenceWordCount,
  };
}

export function generateOfflineCopilotSuggestions(
  _speechTitle: string,
  _plainText: string,
  metrics: SpeechMetrics
): CopilotSuggestion[] {
  const suggestions: CopilotSuggestion[] = [];

  // 1. Hook Suggestion
  suggestions.push({
    id: 'sug-hook-1',
    type: 'hook',
    categoryLabel: 'Abertura Magnética',
    title: 'Gancho Provocativo (Regra dos Primeiros 30s)',
    content: `Abra confrontando uma ilusão do público. Exemplo: "Quantos de vocês acordaram hoje acreditando que tinham o controle de suas decisões? Se eu dissesse que 80% do que você fez hoje foi puro piloto automático... você acreditaria?"`,
    replacementText: `Vocês já pararam para pensar por que tantas boas ideias morrem no silêncio de quem teve medo de falar?`,
  });

  // 2. Rhetorical Structure: Rule of Three
  suggestions.push({
    id: 'sug-cadence-1',
    type: 'cadence',
    categoryLabel: 'Ritmo & Oratória',
    title: 'Aplique a "Tricolon" (Regra de Três)',
    content: 'O cérebro humano processa padrões rítmicos de três elementos com 70% mais fixação. Agrupe seus argumentos principais em trios harmônicos: ação, impacto e legado.',
  });

  // 3. Cadence check if sentence is too long
  if (metrics.longestSentenceWordCount > 25) {
    suggestions.push({
      id: 'sug-trim-1',
      type: 'trim',
      categoryLabel: 'Respiração no Palco',
      title: 'Alerta de Frases Longas',
      content: `Você tem frases com mais de ${metrics.longestSentenceWordCount} palavras sem pontuação. No palco, isso causa falta de ar e aceleração ansiosa. Quebre frases longas em duas e adicione uma pausa de 2 segundos.`,
    });
  }

  // 4. Analogy / Metaphor
  suggestions.push({
    id: 'sug-analogy-1',
    type: 'analogy',
    categoryLabel: 'Fixação Visual',
    title: 'Transforme Conceitos Abstratos em Metáforas',
    content: 'Em vez de apenas descrever dados técnicos, use uma analogia física. "Explicar isso sem metáfora é como tentar descrever a cor vermelha para quem nunca enxergou: o público finge que entende, mas não sente nada."',
  });

  // 5. Climax & Call to action
  suggestions.push({
    id: 'sug-climax-1',
    type: 'climax',
    categoryLabel: 'Encerramento Épico',
    title: 'Desfecho em Ponto Focal',
    content: 'Termine com uma única frase curta, de alto impacto sonoro, seguida por 4 segundos de silêncio absoluto e contato visual fixo. Nunca termine dizendo "era só isso, pessoal" ou "obrigado pelas perguntas".',
    replacementText: `O palco não é lugar para hesitação. É o lugar onde a sua verdade ganha corpo. Façam-se ouvir.`,
  });

  // 6. Fillers warning
  if (metrics.totalFillers > 0) {
    const topFillers = metrics.fillerWordsFound.map((f) => `"${f.word}" (${f.count}x)`).join(', ');
    suggestions.push({
      id: 'sug-critique-fillers',
      type: 'critique',
      categoryLabel: 'Vícios de Linguagem',
      title: 'Substitua Vícios por Silêncio',
      content: `Detectamos vícios orais no seu texto: ${topFillers}. Sempre que sentir vontade de dizer essas palavras, substitua por uma pausa silenciosa. O silêncio soa como autoridade; o vício soa como dúvida.`,
    });
  }

  return suggestions;
}
