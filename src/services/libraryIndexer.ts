import JSZip from 'jszip';
import { db } from './db';
import { classifyTraining } from '../copilot/trainingClassifier';
import { onDocumentExtracted } from '../copilot/s34ImportHook';
import type { CorpusSourceType, Passage, Publication } from '../types/speech';

const MAX_EPUB_FILES = 200;
const MAX_PDF_PAGES = 500;
const MAX_PASSAGES_PER_PUB = 5000;
const CHUNK_SENTENCES = 3;
const CHUNK_OVERLAP = 1;

/** Símbolos de treinamento (metodologia de oratória, não conteúdo). */
const TRAINING_SYMBOLS = new Set(['be', 'th']);

export function detectSymbol(fileName: string): string {
  const base = fileName.toLowerCase().replace(/\.(epub|pdf)$/i, '').trim();
  // Prefixo tipo "be - Beneficie-se", "th_capitulo", "w24.01"
  const prefix = base.match(/^([a-z]{1,4})[\s._-]+/)?.[1];
  if (prefix) return prefix;
  if (/^beneficie/.test(base)) return 'be';
  if (/^melhore/.test(base)) return 'th';
  return base.slice(0, 24);
}

export function detectSourceType(fileName: string, symbol: string): CorpusSourceType {
  const lower = fileName.toLowerCase();
  if (TRAINING_SYMBOLS.has(symbol)) return 'speech_training';
  if (/(biblia|bible|nwt|traducao-do-novo-mundo|tradução-do-novo-mundo|\bbi\b)/.test(lower)) return 'bible';
  return 'publication';
}

export async function indexPublication(
  file: File,
  kind: 'epub' | 'pdf',
  onProgress?: (stage: string) => void,
): Promise<Publication> {
  const arrayBuffer = await file.arrayBuffer();
  const id = `pub-${Date.now()}`;
  const title = file.name.replace(/\.(epub|pdf)$/i, '');
  const localPath = URL.createObjectURL(file);
  const symbol = detectSymbol(file.name);
  const source_type = detectSourceType(file.name, symbol);

  const publication: Publication = {
    id,
    fileName: file.name,
    title,
    kind,
    localPath,
    addedAt: Date.now(),
    indexed: false,
    symbol,
    source_type,
    language: 'pt-BR',
    fileSize: file.size,
  };

  await db.publications.put(publication);

  // Indexação aguardada (antes era fire-and-forget e a UI ficava presa em "Indexando...").
  try {
    onProgress?.('extraindo texto…');
    const { passages, rawText } = await indexPassages(arrayBuffer, publication, kind, onProgress);
    onProgress?.(`salvando ${passages.length} trechos…`);
    if (passages.length > 0) {
      await db.passages.bulkPut(passages);
    }
    await db.publications.update(id, { indexed: true, totalPassages: passages.length });
    // F19-B.6: S-34 importado vira estrutura persistida automaticamente,
    // ligada ao id da publicação. Documento comum não muda de fluxo.
    await onDocumentExtracted(db, id, rawText);
    return { ...publication, indexed: true, totalPassages: passages.length };
  } catch (err) {
    console.warn('Failed to index passages:', err);
    return publication;
  }
}

async function indexPassages(
  buffer: ArrayBuffer,
  pub: Publication,
  kind: 'epub' | 'pdf',
  onProgress?: (stage: string) => void,
): Promise<{ passages: Passage[]; rawText: string }> {
  const units: Array<{
    section?: string;
    page?: number;
    text: string;
    order: number;
    /** F19-B.6: versão que preserva quebras de linha, só para o hook S-34. */
    raw?: string;
  }> = kind === 'epub' ? await extractEpubUnits(buffer, onProgress) : await extractPdfUnits(buffer, onProgress);

  const passages: Passage[] = [];
  let order = 0;
  for (const unit of units) {
    const sentences = splitSentencesOriginal(unit.text);
    // Chunks de N frases com overlap de 1 — preserva texto original (acentos/caixa).
    for (let i = 0; i < sentences.length && passages.length < MAX_PASSAGES_PER_PUB; i += CHUNK_SENTENCES - CHUNK_OVERLAP) {
      const slice = sentences.slice(i, i + CHUNK_SENTENCES);
      const text = slice.join(' ').trim();
      if (text.length < 20) continue;
      const paragraph = Math.floor(i / (CHUNK_SENTENCES - CHUNK_OVERLAP)) + 1;
      const ref = buildRef(pub.symbol || pub.title, unit.section, unit.page, paragraph);
      passages.push({
        id: `pass-${pub.id}-${order}`,
        pubId: pub.id,
        ref,
        text,
        normalizedText: normalizeText(text),
        source_type: pub.source_type,
        symbol: pub.symbol,
        section: unit.section,
        paragraph,
        page: unit.page,
        order: order++,
        language: pub.language ?? 'pt-BR',
        // Fase 7: categoria gravada no índice (backfill classifica on-the-fly).
        training_category:
          pub.source_type === 'speech_training'
            ? classifyTraining({ symbol: pub.symbol, title: pub.title, section: unit.section, text })
            : undefined,
      });
    }
  }
  // F19-B.6: texto bruto dos MESMOS units, para o detector de S-34 —
  // sem reler o arquivo nem duplicar extração.
  const rawText = units.map((u) => u.raw ?? u.text).join('\n').slice(0, 400_000);
  return { passages, rawText };
}

function buildRef(symbol: string, section?: string, page?: number, paragraph?: number): string {
  const parts = [symbol];
  if (section) parts.push(section.slice(0, 60));
  else if (page) parts.push(`p. ${page}`);
  if (paragraph) parts.push(`§${paragraph}`);
  return parts.join(' ');
}

async function extractEpubUnits(
  buffer: ArrayBuffer,
  onProgress?: (stage: string) => void,
): Promise<Array<{ section?: string; text: string; order: number; raw?: string }>> {
  try {
    const zip = await JSZip.loadAsync(buffer);
    // content.xml (FB2-like) ou XHTML soltos — ordenados pelo nome como aproximação do spine.
    const contentFiles = zip.file(/.*\/?content\.xml$/i);
    if (contentFiles.length > 0) {
      const raw = await contentFiles[0].async('string');
      return [{ text: stripHtmlTags(raw), order: 0, raw: stripHtmlToLines(raw) }];
    }
    const all = zip.file(/\.(x?html?|xml)$/i).filter((f) => !/toc|nav|ncx|opf|container/i.test(f.name));
    all.sort((a, b) => a.name.localeCompare(b.name));
    const picked = all.slice(0, MAX_EPUB_FILES);
    const out: Array<{ section?: string; text: string; order: number; raw?: string }> = [];
    let order = 0;
    for (const [idx, f] of picked.entries()) {
      if (idx % 20 === 0) onProgress?.(`lendo seção ${idx + 1}/${picked.length}…`);
      const html: string = await f.async('string');
      const section = extractHeading(html) || f.name.split('/').pop()?.replace(/\.(x?html?|xml)$/i, '');
      const text = stripHtmlTags(html);
      // F19-B.6: o índice continua com o texto achatado; o hook recebe a
      // versão com quebras de linha (o S-34 é orientado a linhas).
      if (text.length > 20) out.push({ section, text, order: order++, raw: stripHtmlToLines(html) });
    }
    return out;
  } catch {
    return [];
  }
}

async function extractPdfUnits(
  buffer: ArrayBuffer,
  onProgress?: (stage: string) => void,
): Promise<Array<{ page: number; text: string; order: number; raw?: string }>> {
  try {
    const { getDocument } = await import('pdfjs-dist/legacy/build/pdf.mjs');
    const pdf = await getDocument({ data: buffer }).promise;
    const total = Math.min(pdf.numPages, MAX_PDF_PAGES);
    const out: Array<{ page: number; text: string; order: number }> = [];
    for (let i = 1; i <= total; i++) {
      if (i % 25 === 0) onProgress?.(`lendo página ${i}/${total}…`);
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const text = (content.items as any[]).map((item) => item.str).join(' ').replace(/\s+/g, ' ').trim();
      if (text.length > 20) out.push({ page: i, text, order: i - 1 });
    }
    return out;
  } catch {
    return [];
  }
}

function extractHeading(html: string): string | undefined {
  const m = html.match(/<(h1|h2|h3)[^>]*>([\s\S]{1,200}?)<\/\1>/i);
  if (!m) return undefined;
  return stripHtmlTags(m[2]).slice(0, 80) || undefined;
}

/**
 * F19-B.6: como stripHtmlTags, mas converte tags de bloco em QUEBRA DE LINHA
 * antes de achatar espaços. Usado somente como texto bruto do hook S-34 — o
 * índice de passages continua com stripHtmlTags (nenhuma mudança de busca).
 */
function stripHtmlToLines(html: string): string {
  return html
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|h[1-6]|li|tr|section|article)>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#\d+;/g, ' ')
    .split('\n')
    .map((l) => l.replace(/[ \t\f\r]+/g, ' ').trim())
    .filter((l) => l.length > 0)
    .join('\n');
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

export function normalizeText(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Divide o texto ORIGINAL em frases (preserva acentos e caixa). */
export function splitSentencesOriginal(text: string): string[] {
  return text
    .replace(/\s+/g, ' ')
    .split(/(?<=[.!?…])\s+(?=[A-ZÀ-Ú0-9"“])/)
    .map((s) => s.trim())
    .filter((s) => s.length > 5);
}

export async function getPublications(): Promise<Publication[]> {
  return db.publications.orderBy('addedAt').reverse().toArray();
}

export async function getPassages(pubId: string): Promise<Passage[]> {
  return db.passages.where('pubId').equals(pubId).toArray();
}

export async function getPassageCount(pubId: string): Promise<number> {
  return db.passages.where('pubId').equals(pubId).count();
}

export async function getCorpusStats(): Promise<{ publications: number; passages: number; training: number }> {
  const publications = await db.publications.count();
  const passages = await db.passages.count();
  const training = await db.passages.where('source_type').equals('speech_training').count().catch(() => 0);
  return { publications, passages, training };
}
