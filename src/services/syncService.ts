import type { Speech, AppSettings, Publication } from '../types/speech';

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

export function initFirebaseSync(_firebaseConfig: Record<string, string>): void {
  const forbiddenKeys = ['publicationText', 'binaryData', 'fileContent'];
  const configKeys = Object.keys(_firebaseConfig);
  const violation = forbiddenKeys.some((k) => configKeys.includes(k));
  if (violation) {
    throw new Error(
      '[BYOD Guardrail] Firebase config contains forbidden key. Publications must never be synced to the cloud.'
    );
  }
  initialized = true;
}

export async function syncSpeechToCloud(_speech: Speech): Promise<void> {
  if (!initialized) return;
  const payload: SyncPayload = {
    speechId: _speech.id,
    title: _speech.title,
    blocks: _speech.blocks.map((b) => ({ id: b.id, order: b.order, minutes: b.minutes, title: b.title })),
    targetDurationMinutes: _speech.targetDurationMinutes,
    category: _speech.category,
    tags: _speech.tags,
    updatedAt: _speech.updatedAt,
  };
  await postToFirestore('speeches', _speech.id, payload);
}

export async function syncSettingsToCloud(_settings: AppSettings): Promise<void> {
  if (!initialized) return;
  const payload: SyncSettingsPayload = {
    defaultWpm: _settings.defaultWpm,
    teleprompterFontSize: _settings.teleprompterFontSize,
    teleprompterMirrored: _settings.teleprompterMirrored,
  };
  await postToFirestore('settings', 'app', payload);
}

export async function syncPublicationMetasToCloud(_publications: Publication[]): Promise<void> {
  if (!initialized) return;
  for (const p of _publications) {
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
    console.error('[BYOD Guardrail] Attempted to sync publication binary data!');
    return false;
  }
  return true;
}

async function postToFirestore(_collection: string, _docId: string, _data: unknown): Promise<void> {
  if (!initialized) return;
  console.warn('[Sync] Firestore write simulated — credentials not configured.');
}
