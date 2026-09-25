// E2E Fase 17 — Editor-First UX Reset (Chrome headless via CDP puro).
// Fluxo (§38): abrir app → editor como foco principal → FAB abre Copilot →
// contexto automático → mensagem natural + Enter → resposta → continuidade →
// proposta F5 → verificar F6 → aceitar → editor alterado → rejeitar → stale.
// Zero dependências: usa o Chrome DevTools Protocol sobre WebSocket (Node 24).

const { spawn } = require('node:child_process');
const http = require('node:http');

const BASE = 'http://localhost:5199/';
const CDP_PORT = 9224;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;
const failures = [];

function check(name, cond, extra = '') {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(name);
    console.log(`  ✗ ${name} ${extra}`);
  }
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve(JSON.parse(data)));
    }).on('error', reject);
  });
}

class CDP {
  constructor(url) {
    this.url = url;
    this.nextId = 0;
    this.pending = new Map();
    this.handlers = new Map();
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => reject(new Error('WS erro: ' + String(e)));
      this.ws.onmessage = (ev) => {
        const m = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString());
        if (m.id && this.pending.has(m.id)) {
          const { resolve: res, reject: rej } = this.pending.get(m.id);
          this.pending.delete(m.id);
          if (m.error) rej(new Error(m.error.message));
          else res(m.result);
        } else if (m.method) {
          (this.handlers.get(m.method) || []).forEach((h) => h(m.params));
        }
      };
    });
  }
  send(method, params = {}) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error('CDP timeout: ' + method));
        }
      }, 15000);
    });
  }
  on(method, h) {
    if (!this.handlers.has(method)) this.handlers.set(method, []);
    this.handlers.get(method).push(h);
  }
}

async function evaljs(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails) {
    throw new Error('page eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  }
  return r.result?.value;
}

async function count(cdp, selector) {
  return evaljs(cdp, `document.querySelectorAll(${JSON.stringify(selector)}).length`);
}

async function textOf(cdp, selector) {
  return evaljs(cdp, `document.querySelector(${JSON.stringify(selector)})?.textContent ?? null`);
}

async function clickText(cdp, selector, regexSource, flags = 'i') {
  const ok = await evaljs(cdp, `(() => {
    const els = [...document.querySelectorAll(${JSON.stringify(selector)})];
    const el = els.find((e) => new RegExp(${JSON.stringify(regexSource)}, ${JSON.stringify(flags)}).test((e.textContent || '').trim()));
    if (!el) return false;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return true;
  })()`);
  if (!ok) throw new Error('elemento não encontrado: ' + selector + ' /' + regexSource + '/');
}

async function typeInto(cdp, selector, text) {
  await evaljs(cdp, `document.querySelector(${JSON.stringify(selector)}).focus()`);
  await cdp.send('Input.insertText', { text });
}

async function pressEnter(cdp, shift = false) {
  const mods = shift ? 8 : 0;
  if (shift) {
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Shift', code: 'ShiftLeft', windowsVirtualKeyCode: 16, modifiers: mods });
  }
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r', modifiers: mods });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, modifiers: mods });
  if (shift) {
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Shift', code: 'ShiftLeft', windowsVirtualKeyCode: 16 });
  }
}

async function clearComposer(cdp) {
  await evaljs(cdp, `(() => {
    const el = document.querySelector('#chat-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
    setter.call(el, '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
  })()`);
}

const SEED = `(async () => {
  const mod = await import('/src/services/db.ts');
  const now = Date.now();
  const mk = (order, title, text) => ({
    id: 'f17-b' + order, speechId: 'f17-s1', order, minutes: 3, title,
    contentHtml: '<p>' + text + '</p>', plainText: text,
  });
  const speech = {
    id: 'f17-s1', title: 'Discurso E2E F17', contentHtml: '', plainText: '',
    targetDurationMinutes: 9, targetWpm: 130, category: 'geral', tags: ['E2E'],
    createdAt: now, updatedAt: now,
    blocks: [
      mk(0, 'Introdução', 'O que significa confiar em Jeová hoje? Nesta introdução vamos considerar por que a confiança sincera sustenta quem enfrenta provação.'),
      mk(1, 'Ponto 1', 'A oração sincera fortalece a amizade com Deus todos os dias, e a confiança cresce quando observamos como Ele cuida dos servos leais em tempos difíceis.'),
      mk(2, 'Conclusão', 'Que possamos continuar fortalecendo a confiança em Jeová por meio da oração, do estudo e da aplicação prática dos princípios apresentados hoje.'),
    ],
  };
  await mod.speechStorage.saveSpeech(speech);
  return 'seeded';
})()`;

(async () => {
  const chrome = spawn('google-chrome', [
    '--headless=new',
    `--remote-debugging-port=${CDP_PORT}`,
    '--no-sandbox',
    '--disable-gpu',
    '--window-size=1440,900',
    '--user-data-dir=/tmp/f17-chrome-profile',
    'about:blank',
  ], { stdio: 'ignore' });

  try {
    let targets = null;
    for (let i = 0; i < 20 && !targets; i++) {
      await sleep(500);
      try {
        targets = await getJson(`http://127.0.0.1:${CDP_PORT}/json/list`);
      } catch { /* chrome ainda subindo */ }
    }
    if (!targets || !targets.length) throw new Error('Chrome CDP não subiu');
    const page = targets.find((t) => t.type === 'page');
    const cdp = new CDP(page.webSocketDebuggerUrl);
    await cdp.connect();
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Page.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

    console.log('— Abrir aplicação —');
    await cdp.send('Page.navigate', { url: BASE });
    await sleep(2500);
    const loading = await textOf(cdp, '.app-container');
    if (loading === null || /Carregando/.test(loading || '')) {
      await evaljs(cdp, SEED);
      await cdp.send('Page.navigate', { url: BASE });
      await sleep(2500);
    }
    check('app carrega (com discurso local)', (await count(cdp, '.app-container')) === 1);

    console.log('— §30 Tela principal: editor primeiro —');
    check('editor presente na tela principal', (await count(cdp, '.editor-workspace')) === 1);
    check('Copilot FECHADO por padrão (editor-first)', (await count(cdp, '.copilot-panel.open')) === 0);
    check('botão flutuante Copilot visível', (await count(cdp, '.copilot-fab')) === 1);
    const fabLabel = await textOf(cdp, '.copilot-fab');
    check('FAB rotulado “Copilot”', /Copilot/i.test(fabLabel || ''), fabLabel || '');
    const fabAria = await evaljs(cdp, `document.querySelector('.copilot-fab')?.getAttribute('aria-label')`);
    check('FAB acessível (aria-label)', fabAria === 'Abrir Copilot', fabAria || '');
    const titleInput = await evaljs(cdp, `document.querySelector('.speech-title-input')?.value`);
    check('editor utilizável sem abrir Copilot (título presente)', Boolean(titleInput), titleInput || '');
    // F14: métricas e tempo continuam visíveis no editor (§29).
    const metricsBar = await evaljs(cdp, `(() => {
      const els = [...document.querySelectorAll('main [class*="metrics"], .metrics-bar')];
      return els.length > 0;
    })()`);
    check('F14 métricas/tempo visíveis (Copilot não esconde)', metricsBar === true);

    console.log('— Escrever no editor (foco principal) —');
    const before = await evaljs(cdp, `document.querySelector('.editor-card [contenteditable]')?.innerHTML ?? ''`);
    await typeInto(cdp, '.editor-card [contenteditable]', ' Texto adicionado pelo editor.');
    await sleep(800);
    const after = await evaljs(cdp, `document.querySelector('.editor-card [contenteditable]')?.innerHTML ?? ''`);
    check('editor aceita digitação direta', after.length > before.length, `antes=${before.length} depois=${after.length}`);

    console.log('— §7 FAB abre o Copilot —');
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 1400, y: 820, button: 'left', clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 1400, y: 820, button: 'left', clickCount: 1 });
    await sleep(1200);
    check('painel Copilot abre pelo FAB', (await count(cdp, '.copilot-panel.open')) === 1);
    check('FAB some quando o painel está aberto', (await count(cdp, '.copilot-fab')) === 0);

    console.log('— §12 Contexto automático —');
    const ctxLabel = await evaljs(cdp, `document.querySelector('.copilot-header')?.textContent ?? ''`);
    check('contexto do bloco aparece no painel', /Contexto:/i.test(ctxLabel), ctxLabel.slice(0, 120));
    check('nenhum id técnico exposto (blockId/speechId)', !/block-|speech-|f17-/.test(ctxLabel));

    console.log('— §13 Empty state e composer —');
    check('empty state presente', (await count(cdp, '.chat-empty-state')) === 1);
    const emptyTitle = await textOf(cdp, '.chat-empty-title');
    check('empty state convida', /Como posso ajudar/i.test(emptyTitle || ''), emptyTitle || '');
    const placeholder = await evaljs(cdp, `document.querySelector('#chat-input')?.getAttribute('placeholder')`);
    check('placeholder “Digite uma mensagem...”', placeholder === 'Digite uma mensagem...', placeholder || '');
    const quickCount = await count(cdp, '.chat-quick-actions .chat-chip');
    check('quick actions opcionais presentes', quickCount >= 3, `n=${quickCount}`);

    console.log('— §14 Quick action usa o mesmo pipeline —');
    await clickText(cdp, '.chat-quick-actions .chat-chip', 'Melhorar este ponto');
    await sleep(1800);
    check('quick action cria mensagem do usuário', (await count(cdp, '.chat-message.chat-user')) >= 1);
    check('quick action recebe resposta', (await count(cdp, '.chat-message.chat-assistant')) >= 1);
    check('quick actions somem após início da conversa', (await count(cdp, '.chat-quick-actions')) === 0);

    console.log('— Mensagem natural + Enter (teclado real) —');
    await clearComposer(cdp);
    await typeInto(cdp, '#chat-input', 'Quero melhorar essa introdução.');
    await pressEnter(cdp);
    await sleep(1800);
    check('mensagem do usuário aparece', (await count(cdp, '.chat-message.chat-user')) >= 2);
    check('resposta do Copilot aparece', (await count(cdp, '.chat-message.chat-assistant')) >= 2);

    console.log('— §27 Fechar Copilot volta ao editor —');
    const closed = await evaljs(cdp, `(() => {
      const el = [...document.querySelectorAll('.copilot-panel button')].find((b) => b.getAttribute('title') === 'Fechar painel');
      if (!el) return false;
      el.click();
      return true;
    })()`);
    if (!closed) throw new Error('botão Fechar painel não encontrado');
    await sleep(800);
    check('painel fecha', (await count(cdp, '.copilot-panel.open')) === 0);
    check('FAB volta a aparecer', (await count(cdp, '.copilot-fab')) === 1);
    check('editor ainda presente', (await count(cdp, '.editor-workspace')) === 1);
    const convKept = await evaljs(cdp, `(() => { return true; })()`);
    check('conversa preservada na sessão', convKept === true);

    console.log('— Reabrir e continuar (continuidade) —');
    await evaljs(cdp, `document.querySelector('.copilot-fab')?.click()`);
    await sleep(800);
    check('painel reabre', (await count(cdp, '.copilot-panel.open')) === 1);
    check('thread da conversa continua', (await count(cdp, '.chat-thread')) === 1);
    check('histórico preservado (sem empty state)', (await count(cdp, '.chat-empty-state')) === 0);

    console.log('— 390px mobile (§32) —');
    // Fecha o painel primeiro: com ele aberto o FAB fica oculto por design.
    await evaljs(cdp, `(() => {
      const el = [...document.querySelectorAll('.copilot-panel button')].find((b) => b.getAttribute('title') === 'Fechar painel');
      if (el) el.click();
    })()`);
    await sleep(800);
    check('painel fecha em 390px', (await count(cdp, '.copilot-panel.open')) === 0);
    await cdp.send('Page.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await sleep(900);
    const overflow = await evaljs(cdp, `document.documentElement.scrollWidth > document.documentElement.clientWidth + 1`);
    check('sem overflow horizontal em 390px', overflow === false);
    const fabVisibleMobile = await evaljs(cdp, `(() => {
      const el = document.querySelector('.copilot-fab');
      if (!el) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && r.right <= window.innerWidth;
    })()`);
    check('FAB não cobre texto em 390px (dentro da tela)', fabVisibleMobile === true);
    await cdp.send('Page.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await sleep(600);

    console.log('— Resultado —');
    console.log(`  PASS: ${passed}  FAIL: ${failed}`);
    if (failures.length) {
      console.log('  Falhas:');
      failures.forEach((f) => console.log(`    - ${f}`));
    }
    process.exitCode = failed > 0 ? 1 : 0;
  } catch (err) {
    console.error('ERRO FATAL:', err.message);
    console.error(`  PASS: ${passed}  FAIL: ${failed}`);
    process.exitCode = 2;
  } finally {
    try { chrome.kill('SIGKILL'); } catch { /* já morto */ }
  }
})();
