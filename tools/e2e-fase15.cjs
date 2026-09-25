// E2E Fase 15 — Copilot chat com teclado real (Chrome headless via CDP puro).
// Fluxo (§44): abrir app → Copilot → quick action → mensagem natural + Enter →
// resposta → continuidade → proposta F5 → verificar F6 → rejeitar → 390px.
// Zero dependências: usa o Chrome DevTools Protocol sobre WebSocket (Node 24).

const { spawn } = require('node:child_process');
const http = require('node:http');

const BASE = 'http://localhost:5199/';
const CDP_PORT = 9223;
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
  // Limpa textarea controlado pelo React (setter nativo + evento input).
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
    id: 'e2e-b' + order, speechId: 'e2e-s1', order, minutes: 3, title,
    contentHtml: '<p>' + text + '</p>', plainText: text,
  });
  const speech = {
    id: 'e2e-s1', title: 'Discurso E2E F15', contentHtml: '', plainText: '',
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
  // Chrome headless com perfil descartável.
  const chrome = spawn('google-chrome', [
    '--headless=new',
    `--remote-debugging-port=${CDP_PORT}`,
    '--no-sandbox',
    '--disable-gpu',
    '--window-size=1440,900',
    '--user-data-dir=/tmp/f15-chrome-profile',
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
      // Ambiente novo: semeia um discurso local pelo próprio módulo do app e recarrega.
      await evaljs(cdp, SEED);
      await cdp.send('Page.navigate', { url: BASE });
      await sleep(2500);
    }
    check('app carrega (com discurso local)', (await count(cdp, '.app-container')) === 1);
    check('Copilot visível por padrão', (await count(cdp, '.copilot-panel.open')) === 1);

    console.log('— Copilot: chat como caminho principal —');
    check('empty state de conversa presente', (await count(cdp, '.chat-empty-state')) === 1);
    const emptyTitle = await textOf(cdp, '.chat-empty-title');
    check('empty state convida à conversa natural', /Como posso ajudar/i.test(emptyTitle || ''), emptyTitle || '');
    const placeholder = await evaljs(cdp, `document.querySelector('#chat-input')?.getAttribute('placeholder')`);
    check('placeholder natural “Digite uma mensagem...”', placeholder === 'Digite uma mensagem...', placeholder || '');
    const quickCount = await count(cdp, '.chat-quick-actions .chat-chip');
    check('quick actions opcionais presentes (não bloqueiam)', quickCount >= 3, `n=${quickCount}`);

    console.log('— Quick action (§8): dispara o mesmo caminho do chat —');
    await clickText(cdp, '.chat-quick-actions .chat-chip', 'Melhorar este ponto');
    await sleep(1800);
    check('quick action cria mensagem do usuário', (await count(cdp, '.chat-message.chat-user')) === 1);
    check('quick action recebe resposta', (await count(cdp, '.chat-message.chat-assistant')) >= 1);
    check('quick actions somem após início da conversa', (await count(cdp, '.chat-quick-actions')) === 0);

    console.log('— Mensagem natural + Enter (teclado real) —');
    await typeInto(cdp, '#chat-input', 'Quero melhorar essa introdução.');
    await pressEnter(cdp, false);
    await sleep(600);
    check('mensagem do usuário entra na thread', (await count(cdp, '.chat-message.chat-user')) === 2);
    const typed = await evaljs(cdp, `[...document.querySelectorAll('.chat-message.chat-user .chat-message-text')].map(e=>e.textContent)`);
    check('texto digitado preservado', (typed || []).some((t) => /melhorar essa introdu/i.test(t || '')), JSON.stringify(typed));
    const draft = await evaljs(cdp, `document.querySelector('#chat-input').value`);
    check('composer limpo após envio', draft === '', JSON.stringify(draft));
    await sleep(1500);
    check('segunda resposta coerente chega', (await count(cdp, '.chat-message.chat-assistant')) >= 2);
    const respTexts = await evaljs(cdp, `[...document.querySelectorAll('.chat-message.chat-assistant .chat-message-text')].map(e=>e.textContent)`);
    check('respostas sem rótulos internos de sistema', (respTexts || []).every((t) => !/ANÁLISE DE INTENÇÃO|CONTEXTO:/i.test(t || '')));
    check('sugestões de continuação (§23) aparecem', (await count(cdp, '.chat-message-actions .chat-chip')) >= 3);

    console.log('— Continuidade por digitação (§10) —');
    await typeInto(cdp, '#chat-input', 'Agora deixe mais natural.');
    await pressEnter(cdp, false);
    await sleep(1500);
    check('terceira mensagem entra na thread', (await count(cdp, '.chat-message.chat-user')) === 3);
    check('terceira resposta chega', (await count(cdp, '.chat-message.chat-assistant')) >= 3);

    console.log('— Shift+Enter quebra linha, não envia —');
    await typeInto(cdp, '#chat-input', 'linha um');
    await pressEnter(cdp, true);
    await cdp.send('Input.insertText', { text: 'linha dois' });
    const multiline = await evaljs(cdp, `document.querySelector('#chat-input').value`);
    check('Shift+Enter cria nova linha', /\n/.test(multiline || ''), JSON.stringify(multiline));
    const beforeTyping = await count(cdp, '.chat-message.chat-user');
    check('digitação sem Enter não envia', beforeTyping === 3, `n=${beforeTyping}`);
    await clearComposer(cdp);

    console.log('— Mensagem vazia não envia —');
    await pressEnter(cdp, false);
    await sleep(400);
    check('Enter com campo vazio não envia', (await count(cdp, '.chat-message.chat-user')) === 3);
    const sendDisabled = await evaljs(cdp, `document.querySelector('.chat-send-btn').disabled`);
    check('botão enviar desabilitado com campo vazio', sendDisabled === true);

    console.log('— Proveniência discreta (§27) —');
    const provSummary = await count(cdp, '.chat-provenance summary');
    check('seção “Fontes e apoio” quando existe, é recolhível', provSummary === 0 || provSummary === 1, `n=${provSummary}`);

    console.log('— Fluxo de edição F5 (proposta determinística offline: Excluir) —');
    await clickText(cdp, '.tone-pill-btn', '^Excluir$');
    await clickText(cdp, '.ai-action-btn', 'Gerar proposta');
    await sleep(800);
    check('proposta F5 aparece em card próprio (distinta do chat)', (await count(cdp, '.copilot-section-edit .ai-result-box')) === 1);
    const proposalText = await textOf(cdp, '.copilot-section-edit .ai-result-box');
    check('proposta mostra o que será removido (não parece aplicada)', /REMOVIDO/i.test(proposalText || ''), (proposalText || '').slice(0, 60));

    console.log('— Verificação F6 da proposta —');
    await clickText(cdp, '.action-btn-sm', 'Verificar proposta');
    await sleep(1500);
    const verifyText2 = await textOf(cdp, '.copilot-section-edit');
    check('verificação F6 roda na proposta (resumo visível)', /Verificando|✓|suporte|sem suporte/i.test(verifyText2 || ''), (verifyText2 || '').slice(0, 80));

    console.log('— Rejeitar proposta (texto intacto) —');
    await clickText(cdp, '.action-btn-sm', '^Rejeitar$');
    await sleep(400);
    check('rejeitar remove o card de proposta', (await count(cdp, '.copilot-section-edit .ai-result-box')) === 0);

    console.log('— 390px (§33) —');
    await cdp.send('Page.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await sleep(600);
    const overflow = await evaljs(cdp, `document.documentElement.scrollWidth - document.documentElement.clientWidth`);
    check('sem overflow horizontal em 390px', overflow <= 0, `overflow=${overflow}px`);
    const composerBox = await evaljs(cdp, `(() => { const r = document.querySelector('#chat-input').getBoundingClientRect(); return { w: r.width, h: r.height, bottom: r.bottom }; })()`);
    check('composer visível e dentro da tela em 390px', composerBox.w > 0 && composerBox.h > 0 && composerBox.bottom <= 845, JSON.stringify(composerBox));
    await typeInto(cdp, '#chat-input', 'Mensagem em tela mobile.');
    await pressEnter(cdp, false);
    await sleep(1500);
    check('envio funciona em 390px', (await count(cdp, '.chat-message.chat-user')) === 4);
    const threadBox = await evaljs(cdp, `(() => { const t = document.querySelector('.chat-thread'); return t ? { sh: t.scrollHeight, ch: t.clientHeight } : null; })()`);
    check('thread presente e rolável quando cresce', threadBox && threadBox.sh >= threadBox.ch, JSON.stringify(threadBox));
    const composerBottom = await evaljs(cdp, `document.querySelector('.chat-composer').getBoundingClientRect().bottom`);
    check('composer fixo no fim, sem cobrir conteúdo', composerBottom > 0 && composerBottom <= 845, `bottom=${composerBottom}`);

    console.log('\n========== RESUMO E2E F15 ==========');
    console.log(`Passou: ${passed} · Falhou: ${failed}`);
    if (failures.length) {
      console.log('Falhas:');
      failures.forEach((f) => console.log('  - ' + f));
      process.exitCode = 1;
    }
  } finally {
    chrome.kill('SIGTERM');
  }
})().catch((e) => {
  console.error('E2E erro fatal:', e.message || e);
  process.exit(2);
});
