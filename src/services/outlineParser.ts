import type { Speech, SpeechBlock } from '../types/speech';

const TIME_REGEX = /\(\s*(\d+)\s*min(utos)?\s*\)/gi;
const TITLE_LINE_REGEX = /^[A-ZÀ-ÛÇ]\S+(\s+[A-ZÀ-ÛÇ]\S+)*$/;



export function parseOutline(buffer: ArrayBuffer): { speech: Partial<Speech>; blocks: SpeechBlock[] } {
  const text = new TextDecoder().decode(buffer);
  const timeMatches = [...text.matchAll(TIME_REGEX)];

  if (timeMatches.length === 0) {
    const block = createDefaultBlock(text);
    return { speech: { title: 'Discurso Importado', blocks: [block] }, blocks: [block] };
  }

  const blocks: SpeechBlock[] = [];
  let lastIndex = 0;
  let order = 0;

  for (let idx = 0; idx < timeMatches.length; idx++) {
    const match = timeMatches[idx];
    const startIndex = match.index!;
    const minutes = parseInt(match[1], 10);
    const beforeText = text.slice(lastIndex, startIndex).trim();

    const nextMatch = timeMatches[idx + 1];
    const endIndex = nextMatch ? nextMatch.index! : text.length;
    const body = text.slice(startIndex + match[0].length, endIndex).trim();

    const title = extractTitle(beforeText, body) || `Bloco ${minutes} min`;

    blocks.push({
      id: `block-import-${Date.now()}-${order}`,
      speechId: '',
      order: order++,
      minutes,
      title,
      contentHtml: formatBlockToHtml(title, body),
      plainText: body,
    });

    lastIndex = startIndex + match[0].length;
  }

  const totalMinutes = blocks.reduce((acc, b) => acc + b.minutes, 0);
  const mainTitle = blocks[0]?.title || 'Discurso Importado';

  const speech: Partial<Speech> = {
    title: mainTitle,
    contentHtml: blocks.map((b) => `<h2>${b.title}</h2><p>${b.plainText}</p>`).join('<hr/>'),
    plainText: blocks.map((b) => b.plainText).join('\n'),
    targetDurationMinutes: totalMinutes,
    blocks,
  };

  return { speech, blocks };
}

function extractTitle(beforeText: string, body: string): string {
  const lines = beforeText.split('\n').map((l) => l.trim()).filter(Boolean);
  for (const line of lines) {
    if (TITLE_LINE_REGEX.test(line) && line.length > 3) {
      return line;
    }
  }
  const firstSentence = body.split('\n')[0]?.trim().split(/[.!?]+/)[0]?.trim();
  return firstSentence || '';
}

function formatBlockToHtml(title: string, body: string): string {
  const html = body
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/\n/g, '</p><p>');
  return `<h2>${title}</h2><p>${html}</p>`;
}

function createDefaultBlock(text: string): SpeechBlock {
  return {
    id: `block-default-${Date.now()}`,
    speechId: '',
    order: 0,
    minutes: 5,
    title: 'Discurso',
    contentHtml: `<p>${text}</p>`,
    plainText: text,
  };
}
