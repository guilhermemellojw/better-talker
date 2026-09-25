import Dexie, { type Table } from 'dexie';
import type { Speech, AppSettings, SpeechBlock, Publication, Passage } from '../types/speech';

const DEFAULT_SETTINGS: AppSettings = {
  geminiApiKey: '',
  defaultWpm: 130,
  teleprompterFontSize: 36,
  teleprompterMirrored: false,
};

const INITIAL_DEMO_SPEECH: Speech = {
  id: 'speech-demo-1',
  title: 'O Poder da Palavra Falada: Como Cativar Qualquer Plateia',
  category: 'ted',
  targetDurationMinutes: 5,
  targetWpm: 130,
  tags: ['Inspiração', 'Oratória', 'Palco'],
  createdAt: Date.now() - 3600000 * 24,
  updatedAt: Date.now(),
  contentHtml: `
<h2>🎯 <strong>O Gancho de Abertura (The Hook)</strong></h2>
<p>Vocês já se perguntaram por que lembramos de certas frases por décadas, enquanto esquecemos reuniões inteiras de duas horas minutos depois de sair da sala? <span class="stage-cue-badge cue-pause" data-cue-type="pause-3s" contenteditable="false">⏸ 3s Pausa Reflexiva</span></p>
<p>A verdade incômoda é esta: <mark class="hl-amber">as pessoas não compram apenas as suas ideias, elas compram a convicção com que você as expressa.</mark> <span class="stage-cue-badge cue-eye" data-cue-type="eye-contact" contenteditable="false">👁 Olhar Fixamente na Plateia</span></p>

<hr/>

<h2>📖 <strong>A História e Conexão Humana</strong></h2>
<p>Eu me lembro da minha primeira palestra como se fosse ontem. As mãos suavam frio. O coração batia a 160 por minuto. <span class="stage-cue-badge cue-whisper" data-cue-type="whisper" contenteditable="false">🤫 Baixar o Tom de Voz</span> Eu cheguei a pensar em fingir uma tosse e abandonar o palco. Mas ali aprendi uma lição de ouro: <mark class="hl-emerald">a vulnerabilidade não é fraqueza no palco; é a ponte mais veloz para a empatia.</mark></p>

<hr/>

<h2>💡 <strong>Os Três Pilares da Oratória Magnética</strong></h2>
<p>Para dominar o palco, todo orador precisa cultivar apenas três regras simples:</p>
<ol>
  <li><strong>A Regra de Três:</strong> Nossos cérebros adoram padrões triádicos. Início, meio e fim. Passado, presente e futuro. Sangue, suor e lágrimas.</li>
  <li><strong>O Silêncio Estratégico:</strong> O silêncio antes de uma frase gera suspense; <span class="stage-cue-badge cue-pause" data-cue-type="pause-2s" contenteditable="false">⏸ 2s Silêncio</span> o silêncio logo depois, gera peso.</li>
  <li><strong>Voz de Peito, não de Garganta:</strong> Projete sua mensagem até a última fileira do auditório.</li>
</ol>

<hr/>

<h2>🔥 <strong>O Clímax e Chamada para Ação</strong></h2>
<p><mark class="hl-rose">Não deixe suas melhores ideias presas na garganta.</mark> <span class="stage-cue-badge cue-emphasis" data-cue-type="emphasis" contenteditable="false">⚡ Ênfase Vocal Máxima</span> O mundo não precisa de oradores perfeitos e artificiais. O mundo precisa de vozes autênticas com a coragem de se fazer ouvir.</p>
<p>Muito obrigado! <span class="stage-cue-badge cue-applause" data-cue-type="applause" contenteditable="false">👏 Pausa para Aplausos</span></p>
  `.trim(),
  plainText: `Vocês já se perguntaram por que lembramos de certas frases por décadas, enquanto esquecemos reuniões inteiras de duas horas minutos depois de sair da sala? A verdade incômoda é esta: as pessoas não compram apenas as suas ideias, elas compram a convicção com que você as expressa. Eu me lembro da minha primeira palestra como se fosse ontem. As mãos suavam frio. O coração batia a 160 por minuto. Eu cheguei a pensar em fingir uma tosse e abandonar o palco. Mas ali aprendi uma lição de ouro: a vulnerabilidade não é fraqueza no palco; é a ponte mais veloz para a empatia. Para dominar o palco, todo orador precisa cultivar apenas três regras simples: A Regra de Três, O Silêncio Estratégico e Voz de Peito. Não deixe suas melhores ideias presas na garganta. O mundo precisa de vozes autênticas com a coragem de se fazer ouvir. Muito obrigado!`,
  blocks: [],
};

class BetterTalkerDB extends Dexie {
  speeches!: Table<Speech, string>;
  blocks!: Table<SpeechBlock, string>;
  publications!: Table<Publication, string>;
  passages!: Table<Passage, string>;
  settings!: Table<{ key: string; value: AppSettings }, string>;

  constructor() {
    super('BetterTalkerDB');
    this.version(2).stores({
      speeches: 'id, updatedAt, sourceFileName',
      blocks: 'id, speechId, order',
      publications: 'id, fileName, indexed',
      passages: 'id, pubId, normalizedText',
      settings: 'key',
    });
    // Fase 2: metadata rica do corpus (source_type/symbol) + ordenação estável.
    this.version(3).stores({
      speeches: 'id, updatedAt, sourceFileName',
      blocks: 'id, speechId, order',
      publications: 'id, fileName, indexed, symbol, source_type',
      passages: 'id, pubId, normalizedText, source_type, symbol, order',
      settings: 'key',
    });
    // Fase 12 (UX-P0-02): getAllPublications ordena por addedAt — o índice
    // não existia e o Dexie lançava SchemaError, quebrando Biblioteca,
    // verificação e retrieval ambiente. Upgrade aditivo, sem perda.
    this.version(4).stores({
      speeches: 'id, updatedAt, sourceFileName',
      blocks: 'id, speechId, order',
      publications: 'id, fileName, indexed, symbol, source_type, addedAt',
      passages: 'id, pubId, normalizedText, source_type, symbol, order',
      settings: 'key',
    });
  }
}

const db = new BetterTalkerDB();

function toSpeechWithBlocks(speech: Speech): Speech {
  if (speech.blocks && speech.blocks.length > 0) return speech;
  return {
    ...speech,
    blocks: [
      {
        id: `block-${speech.id}`,
        speechId: speech.id,
        order: 0,
        minutes: speech.targetDurationMinutes,
        title: speech.title,
        contentHtml: speech.contentHtml,
        plainText: speech.plainText,
      },
    ],
  };
}

/**
 * RC: nenhuma operação Dexie pode pendurar a UI para sempre (IndexedDB pode
 * travar em perfil corrompido, pressão de armazenamento etc.). Expirado o
 * prazo, a promise rejeita e os fallbacks locais existentes assumem.
 */
export const STORAGE_TIMEOUT_MS = 8000;

export function withStorageTimeout<T>(
  promise: Promise<T>,
  label: string,
  timeoutMs: number = STORAGE_TIMEOUT_MS,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`[db] timeout em ${label}`)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => {
    if (timer !== undefined) clearTimeout(timer);
  });
}

export const speechStorage = {
  async getAllSpeeches(): Promise<Speech[]> {
    try {
      const list = await withStorageTimeout(
        db.speeches.orderBy('updatedAt').reverse().toArray(),
        'getAllSpeeches',
      );
      if (!list || list.length === 0) {
        await this.saveSpeech(INITIAL_DEMO_SPEECH);
        const saved = await withStorageTimeout(
          db.speeches.get(INITIAL_DEMO_SPEECH.id!),
          'getAllSpeeches:seed',
        );
        return saved ? [toSpeechWithBlocks(saved)] : [INITIAL_DEMO_SPEECH];
      }
      return list.map(toSpeechWithBlocks);
    } catch (e) {
      console.warn('Fallback para localStorage', e);
      const raw = localStorage.getItem('SPEECHES_STORE');
      if (!raw) {
        localStorage.setItem('SPEECHES_STORE', JSON.stringify([INITIAL_DEMO_SPEECH]));
        return [INITIAL_DEMO_SPEECH];
      }
      return JSON.parse(raw).map(toSpeechWithBlocks);
    }
  },

  async getSpeechById(id: string): Promise<Speech | null> {
    try {
      const speech = await withStorageTimeout(db.speeches.get(id), 'getSpeechById');
      return speech ? toSpeechWithBlocks(speech) : null;
    } catch {
      const list = await this.getAllSpeeches();
      return list.find((s) => s.id === id) || null;
    }
  },

  async saveSpeech(speech: Speech): Promise<void> {
    const item = { ...speech, updatedAt: Date.now() };
    try {
      await withStorageTimeout(db.speeches.put(item), 'saveSpeech');
      if (item.blocks?.length) {
        await withStorageTimeout(db.blocks.bulkPut(item.blocks), 'saveSpeech:blocks');
      }
      this.backupToLocalStorage(item);
    } catch (e) {
      this.backupToLocalStorage(item);
    }
  },

  async deleteSpeech(id: string): Promise<void> {
    try {
      await withStorageTimeout(
        db.transaction('rw', [db.speeches, db.blocks], async () => {
          await db.speeches.delete(id);
          await db.blocks.where('speechId').equals(id).delete();
        }),
        'deleteSpeech',
      );
    } catch {
      const list = await this.getAllSpeeches();
      const filtered = list.filter((s) => s.id !== id);
      localStorage.setItem('SPEECHES_STORE', JSON.stringify(filtered));
    }
  },

  async savePublication(publication: Publication): Promise<void> {
    await withStorageTimeout(db.publications.put(publication), 'savePublication');
  },

  async deletePublication(id: string): Promise<void> {
    await withStorageTimeout(
      db.transaction('rw', [db.publications, db.passages], async () => {
        await db.publications.delete(id);
        await db.passages.where('pubId').equals(id).delete();
      }),
      'deletePublication',
    );
  },

  async getAllPublications(): Promise<Publication[]> {
    return withStorageTimeout(
      db.publications.orderBy('addedAt').reverse().toArray(),
      'getAllPublications',
    );
  },

  async getPublicationById(id: string): Promise<Publication | undefined> {
    return withStorageTimeout(db.publications.get(id), 'getPublicationById');
  },

  async getPassagesByPubId(pubId: string): Promise<Passage[]> {
    const list = await withStorageTimeout(
      db.passages.where('pubId').equals(pubId).toArray(),
      'getPassagesByPubId',
    );
    return list.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  },

  async getAllPassages(limit = 2000): Promise<Passage[]> {
    return withStorageTimeout(
      db.passages.orderBy('id').reverse().limit(limit).toArray(),
      'getAllPassages',
    );
  },

  async getPassagesBySourceType(sourceType: string, limit = 200): Promise<Passage[]> {
    try {
      return await withStorageTimeout(
        db.passages.where('source_type').equals(sourceType).limit(limit).toArray(),
        'getPassagesBySourceType',
      );
    } catch {
      return [];
    }
  },

  backupToLocalStorage(speech: Speech) {
    try {
      const raw = localStorage.getItem('SPEECHES_STORE');
      let list: Speech[] = raw ? JSON.parse(raw) : [];
      const idx = list.findIndex((s) => s.id === speech.id);
      if (idx >= 0) list[idx] = speech;
      else list.unshift(speech);
      localStorage.setItem('SPEECHES_STORE', JSON.stringify(list));
    } catch {}
  },

  async getSettings(): Promise<AppSettings> {
    try {
      const rec = await withStorageTimeout(db.settings.get('app_settings'), 'getSettings');
      const settings = rec?.value ?? DEFAULT_SETTINGS;
      if (!settings) return DEFAULT_SETTINGS;
      return settings;
    } catch {
      const raw = localStorage.getItem('better_talker_settings');
      return raw ? JSON.parse(raw) : DEFAULT_SETTINGS;
    }
  },

  async saveSettings(settings: AppSettings): Promise<void> {
    try {
      await withStorageTimeout(
        db.settings.put({ key: 'app_settings', value: settings }),
        'saveSettings',
      );
      localStorage.setItem('better_talker_settings', JSON.stringify(settings));
    } catch {
      localStorage.setItem('better_talker_settings', JSON.stringify(settings));
    }
  },
};

export { db };
