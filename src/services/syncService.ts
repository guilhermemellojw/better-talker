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
    const { getFirestore } = await import('firebase/firestore');
    const app = initializeApp(config);
    getFirestore(app);
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
  await postToFirestore('speeches', speech.id, payload);
}

export async function syncSettingsToCloud(settings: AppSettings): Promise<void> {
  if (!initialized) return;
  const payload: SyncSettingsPayload = {
    defaultWpm: settings.defaultWpm,
    teleprompterFontSize: settings.teleprompterFontSize,
    teleprompterMirrored: settings.teleprompterMirrored,
  };
  await postToFirestore('settings', 'app', payload);
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
    await postToFirestore('publications', p.id, meta);
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

async function postToFirestore(collection: string, docId: string, data: unknown): Promise<void> {
  if (!initialized) return;
  const config = loadFirebaseConfig();
  if (!config) return;
  try {
    const { doc, setDoc } = await import('firebase/firestore');
    const { getFirestore } = await import('firebase/firestore');
    const { getApps, getApp } = await import('firebase/app');
    const app = getApps().length > 0 ? getApp() : null;
    if (!app) return;
    await setDoc(doc(getFirestore(app), collection, docId), data as object, { merge: true });
  } catch (e) {
    console.warn('[Sync] Falha ao gravar no Firestore:', e);
  }
}