import type { Speech, AppSettings, Publication } from '../types/speech';
import { loadFirebaseConfig } from './firebaseConfig';

export interface SyncPayload {
  speechId: string;
  title: string;
  blocks: Array<{ id: string; order: number; minutes: number; title: string }>;
  targetDurationMinutes: number;
  category: Speech['category'];
  tags: string[];
  updatedAt: number;
}

export interface SyncSettingsPayload {
  defaultWpm: number;
  teleprompterFontSize: number;
  teleprompterMirrored: boolean;
}

export interface SyncPublicationMeta {
  id: string;
  fileName: string;
  title: string;
  kind: Publication['kind'];
  indexed: boolean;
  addedAt: number;
}

let initialized = false;

export async function initFirebaseSync(): Promise<boolean> {
  const config = loadFirebaseConfig();
  if (!config) return false;
  try {
    const { initializeApp } = await import('firebase/app');
    const { getDatabase } = await import('firebase/database');
    const app = initializeApp(config);
    getDatabase(app);
    initialized = true;
    return true;
  } catch (e) {
    console.warn('[Sync] Falha ao inicializar Firebase:', e);
    initialized = false;
    return false;
  }
}

export async function syncSpeechToCloud(speech: Speech): Promise<void> {
  if (!initialized) return;
  if (!validateNoPublicationBinaryInSync(speech)) return;
  const payload: SyncPayload = {
    speechId: speech.id,
    title: speech.title,
    blocks: speech.blocks.map((b) => ({ id: b.id, order: b.order, minutes: b.minutes, title: b.title })),
    targetDurationMinutes: speech.targetDurationMinutes,
    category: speech.category,
    tags: speech.tags,
    updatedAt: speech.updatedAt,
  };
  await writeToRtdb(`speeches/${speech.id}`, payload);
}

export async function syncSettingsToCloud(settings: AppSettings): Promise<void> {
  if (!initialized) return;
  const payload: SyncSettingsPayload = {
    defaultWpm: settings.defaultWpm,
    teleprompterFontSize: settings.teleprompterFontSize,
    teleprompterMirrored: settings.teleprompterMirrored,
  };
  await writeToRtdb('settings/app', payload);
}

export async function syncPublicationMetasToCloud(publications: Publication[]): Promise<void> {
  if (!initialized) return;
  for (const p of publications) {
    const meta: SyncPublicationMeta = {
      id: p.id,
      fileName: p.fileName,
      title: p.title,
      kind: p.kind,
      indexed: p.indexed,
      addedAt: p.addedAt,
    };
    await writeToRtdb(`publications/${p.id}`, meta);
  }
}

export function validateNoPublicationBinaryInSync(speech: Speech): boolean {
  const forbiddenPatterns = [/\.epub/, /\.pdf/, /base64/, /blob:/, /binary/];
  const hasBinary = speech.blocks.some((b) =>
    forbiddenPatterns.some((p) => p.test(b.contentHtml || b.plainText))
  );
  if (hasBinary) {
    console.error('[BYOD Guardrail] Tentativa de sincronizar dados binários de publicação!');
    return false;
  }
  return true;
}

async function writeToRtdb(path: string, data: unknown): Promise<void> {
  if (!initialized) return;
  const config = loadFirebaseConfig();
  if (!config) return;
  try {
    const { ref, set, getDatabase } = await import('firebase/database');
    const { getApp } = await import('firebase/app');
    const app = getApp();
    await set(ref(getDatabase(app), path), data);
  } catch (e) {
    console.warn('[Sync] Falha ao gravar no Realtime Database:', e);
  }
}