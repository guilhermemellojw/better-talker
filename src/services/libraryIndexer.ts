import JSZip from 'jszip';
import { db } from './db';
import type { Publication, Passage } from '../types/speech';

export async function indexPublication(file: File, kind: 'epub' | 'pdf'): Promise<Publication> {
  const arrayBuffer = await file.arrayBuffer();
  const id = `pub-${Date.now()}`;
  const title = file.name.replace(/\.(epub|pdf)$/i, '');
  const localPath = URL.createObjectURL(file);

  const publication: Publication = {
    id,
    fileName: file.name,
    title,
    kind,
    localPath,
    addedAt: Date.now(),
    indexed: false,
  };

  await db.publications.put(publication);

  // Index passages in background
  indexPassages(arrayBuffer, id, kind).then((passages) => {
    db.passages.bulkPut(passages).then(() => {
      db.publications.update(id, { indexed: true });
    });
  }).catch((err) => {
    console.warn('Failed to index passages:', err);
  });

  return publication;
}

async function indexPassages(buffer: ArrayBuffer, pubId: string, kind: 'epub' | 'pdf'): Promise<Passage[]> {
  let text = '';

  if (kind === 'epub') {
    text = await extractEpubText(buffer);
  } else {
    text = await extractPdfText(buffer);
  }

  if (!text.trim()) return [];

  const normalized = normalizeText(text);
  const sentences = splitSentences(normalized);
  const passages: Passage[] = sentences.map((sentence, idx) => ({
    id: `pass-${pubId}-${idx}`,
    pubId,
    ref: '',
    text: sentence,
    normalizedText: normalizeText(sentence),
  }));

  return passages;
}

async function extractEpubText(buffer: ArrayBuffer): Promise<string> {
  try {
    const zip = await JSZip.loadAsync(buffer);
    const contentFiles = zip.file(/.*\/?content\.xml$/i);
    if (!contentFiles.length) {
      const allFiles = zip.file(/\.(x?html?|xml)$/i);
      if (!allFiles.length) return '';
      let combined = '';
      for (const f of allFiles.slice(0, 20)) {
        combined += await f.async('string') + ' ';
      }
      return stripHtmlTags(combined);
    }
    const content = await contentFiles[0].async('string');
    return stripHtmlTags(content);
  } catch {
    return '';
  }
}

async function extractPdfText(buffer: ArrayBuffer): Promise<string> {
  try {
    const { getDocument } = await import('pdfjs-dist/legacy/build/pdf.mjs');
    const pdf = await getDocument({ data: buffer }).promise;
    let text = '';
    for (let i = 1; i <= pdf.numPages && i <= 50; i++) {
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      text += content.items.map((item: any) => item.str).join(' ') + ' ';
    }
    return text;
  } catch {
    return '';
  }
}

function stripHtmlTags(html: string): string {
  return html
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#\d+;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeText(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function splitSentences(text: string): string[] {
  return text
    .split(/[.!?]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 5);
}

export async function getPublications(): Promise<Publication[]> {
  return db.publications.orderBy('addedAt').reverse().toArray();
}

export async function getPassages(pubId: string): Promise<Passage[]> {
  return db.passages.where('pubId').equals(pubId).toArray();
}
