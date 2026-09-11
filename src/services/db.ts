import type { Speech, AppSettings } from '../types/speech';

const DB_NAME = 'BetterTalkerDB';
const DB_VERSION = 1;
const SPEECHES_STORE = 'speeches';
const SETTINGS_STORE = 'settings';

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
};

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!window.indexedDB) {
      reject(new Error('IndexedDB não suportado'));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event: IDBVersionChangeEvent) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(SPEECHES_STORE)) {
        const speechStore = db.createObjectStore(SPEECHES_STORE, { keyPath: 'id' });
        speechStore.createIndex('updatedAt', 'updatedAt', { unique: false });
      }
      if (!db.objectStoreNames.contains(SETTINGS_STORE)) {
        db.createObjectStore(SETTINGS_STORE, { keyPath: 'key' });
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export const speechStorage = {
  async getAllSpeeches(): Promise<Speech[]> {
    try {
      const db = await openDB();
      return new Promise((resolve, reject) => {
        const tx = db.transaction(SPEECHES_STORE, 'readonly');
        const store = tx.objectStore(SPEECHES_STORE);
        const request = store.getAll();

        request.onsuccess = () => {
          const list = request.result as Speech[];
          if (!list || list.length === 0) {
            // Seed initial speech
            speechStorage.saveSpeech(INITIAL_DEMO_SPEECH).then(() => resolve([INITIAL_DEMO_SPEECH]));
          } else {
            // Sort by updatedAt descending
            resolve(list.sort((a, b) => b.updatedAt - a.updatedAt));
          }
        };
        request.onerror = () => reject(request.error);
      });
    } catch (e) {
      console.warn('Fallback para localStorage', e);
      const raw = localStorage.getItem(SPEECHES_STORE);
      if (!raw) {
        localStorage.setItem(SPEECHES_STORE, JSON.stringify([INITIAL_DEMO_SPEECH]));
        return [INITIAL_DEMO_SPEECH];
      }
      return JSON.parse(raw);
    }
  },

  async getSpeechById(id: string): Promise<Speech | null> {
    try {
      const db = await openDB();
      return new Promise((resolve, reject) => {
        const tx = db.transaction(SPEECHES_STORE, 'readonly');
        const store = tx.objectStore(SPEECHES_STORE);
        const request = store.get(id);
        request.onsuccess = () => resolve((request.result as Speech) || null);
        request.onerror = () => reject(request.error);
      });
    } catch {
      const list = await this.getAllSpeeches();
      return list.find((s) => s.id === id) || null;
    }
  },

  async saveSpeech(speech: Speech): Promise<void> {
    const item = { ...speech, updatedAt: Date.now() };
    try {
      const db = await openDB();
      return new Promise((resolve, reject) => {
        const tx = db.transaction(SPEECHES_STORE, 'readwrite');
        const store = tx.objectStore(SPEECHES_STORE);
        store.put(item);
        tx.oncomplete = () => {
          // Also sync to backup localStorage
          this.backupToLocalStorage(item);
          resolve();
        };
        tx.onerror = () => reject(tx.error);
      });
    } catch (e) {
      this.backupToLocalStorage(item);
    }
  },

  async deleteSpeech(id: string): Promise<void> {
    try {
      const db = await openDB();
      return new Promise((resolve, reject) => {
        const tx = db.transaction(SPEECHES_STORE, 'readwrite');
        const store = tx.objectStore(SPEECHES_STORE);
        store.delete(id);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
    } catch {
      const list = await this.getAllSpeeches();
      const filtered = list.filter((s) => s.id !== id);
      localStorage.setItem(SPEECHES_STORE, JSON.stringify(filtered));
    }
  },

  backupToLocalStorage(speech: Speech) {
    try {
      const raw = localStorage.getItem(SPEECHES_STORE);
      let list: Speech[] = raw ? JSON.parse(raw) : [];
      const idx = list.findIndex((s) => s.id === speech.id);
      if (idx >= 0) list[idx] = speech;
      else list.unshift(speech);
      localStorage.setItem(SPEECHES_STORE, JSON.stringify(list));
    } catch {}
  },

  async getSettings(): Promise<AppSettings> {
    try {
      const db = await openDB();
      return new Promise((resolve) => {
        const tx = db.transaction(SETTINGS_STORE, 'readonly');
        const store = tx.objectStore(SETTINGS_STORE);
        const req = store.get('app_settings');
        req.onsuccess = () => {
          resolve(req.result ? req.result.value : DEFAULT_SETTINGS);
        };
        req.onerror = () => resolve(DEFAULT_SETTINGS);
      });
    } catch {
      const raw = localStorage.getItem('better_talker_settings');
      return raw ? JSON.parse(raw) : DEFAULT_SETTINGS;
    }
  },

  async saveSettings(settings: AppSettings): Promise<void> {
    try {
      const db = await openDB();
      return new Promise((resolve) => {
        const tx = db.transaction(SETTINGS_STORE, 'readwrite');
        const store = tx.objectStore(SETTINGS_STORE);
        store.put({ key: 'app_settings', value: settings });
        tx.oncomplete = () => {
          localStorage.setItem('better_talker_settings', JSON.stringify(settings));
          resolve();
        };
      });
    } catch {
      localStorage.setItem('better_talker_settings', JSON.stringify(settings));
    }
  }
};
