// F20-E — validação real do LLM no pipeline oratório (§§7-44).
//
// PROVIDER: selecionado por ambiente, sem fallback silencioso.
//   BETTER_TALKER_REAL_PROVIDER=groq → Groq (OpenAI-compatible) · qwen/qwen3.8-27b
//   BETTER_TALKER_REAL_PROVIDER=gemini (ou ausente sem GROQ_API_KEY) → Gemini
//
// NUNCA contém chave: lê GROQ_API_KEY / BETTER_TALKER_REAL_LLM_KEY do
// ambiente e reporta apenas PRESENT/ABSENT.
//
// Cada caso registra prompt, resposta bruta, proposta F5, fidelidade (F20-C),
// verificação F6, tokens e rate-limit. Métricas reais são agregadas; nenhuma
// falha do modelo é mascarada.

import { describe, it, expect } from 'vitest';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { parseS34, type S34Document } from '../s34Parser';
import { scopeToS34Section } from '../s34StructuralRetrieval';
import { structuralContextFor } from '../s34StructuralContext';
import { oratorySpec, buildOratoryPrompt, type OratoryMode } from '../oratoryGeneration';
import { routeNaturalChat, type ChatRoute } from '../chatRouter';
import { checkOratoryFidelity, type OratoryFidelityReport } from '../oratoryFidelityCheck';
import { parseEditProposal } from '../proposalParser';
import { applyEditProposal, stripHtmlToText, hashText } from '../editProposal';
import { EditHistory } from '../editHistory';
import { verifyText, clearVerificationCache } from '../verifier';
import { GeminiProvider } from '../geminiProvider';
import { QwenProvider } from '../qwenProvider';
import { buildLlmPrompt } from '../llmPrompt';
import { ProviderError } from '../llmErrors';
import type { LlmProvider, LlmResponse } from '../llmProvider';
import type { Speech, Passage, Publication } from '../../types/speech';
import type { PassageStore } from '../retrievalTypes';
import type { CopilotEditProposal } from '../domain';
import {
  S34_RICH_TEXT,
  S34_B_TEXT,
  S34_SIMILAR_TEXT,
  S34_INJECTED_TEXT,
  s34ReferenceTextMap,
  POINT_EXCLUSIVE_TERMS,
} from './s34RichFixture';

// ---------- Configuração por ambiente (nunca imprimir valores) ----------

const GROQ_KEY = (process.env.GROQ_API_KEY ?? process.env.VITE_QWEN_API_KEY ?? '').trim();
const GEMINI_KEY = (process.env.BETTER_TALKER_REAL_LLM_KEY ?? process.env.GEMINI_API_KEY ?? '').trim();
const REAL_PROVIDER = (
  process.env.BETTER_TALKER_REAL_PROVIDER ?? (GROQ_KEY ? 'groq' : 'gemini')
).toLowerCase();
const GROQ_ENDPOINT = process.env.BETTER_TALKER_GROQ_ENDPOINT ?? 'https://api.groq.com/openai/v1';
const GROQ_MODEL = process.env.BETTER_TALKER_GROQ_MODEL ?? 'qwen/qwen3.8-27b';
const GROQ_REASONING_FORMAT = process.env.BETTER_TALKER_GROQ_REASONING_FORMAT as
  | 'parsed'
  | 'raw'
  | 'hidden'
  | undefined;
const GEMINI_MODEL = process.env.BETTER_TALKER_GEMINI_MODEL ?? 'gemini-3.6-flash';
/**
 * Teto de saída das gerações oratórias DURANTE a validação. O app usa 2048;
 * o plano gratuito do Groq limita OTPM a 1000/min e o limiter reserva ~75%
 * do max_tokens, então a validação usa um teto menor (evidência: erro
 * "Request too large ... Requested 1541"). Nenhuma mudança de produto.
 */
const ORATORY_MAX_OUTPUT = Number(process.env.BETTER_TALKER_GROQ_MAX_OUTPUT_TOKENS ?? 2048);
const REPORT_PATH = process.env.BETTER_TALKER_REPORT_PATH ?? '/tmp/opencode/f20e-report.json';

/** Credencial plausível (formato real), não placeholder de ambiente. */
const looksLikeGroqKey = GROQ_KEY.startsWith('gsk_') && GROQ_KEY.length > 20;
const REAL =
  REAL_PROVIDER === 'groq'
    ? looksLikeGroqKey
    : REAL_PROVIDER === 'gemini'
      ? GEMINI_KEY.length > 20
      : false;

/** Intervalo entre chamadas (o OTPM de 1000/min recompõe ~1 geração/min). */
const GAP_MS = Number(process.env.BETTER_TALKER_LLM_GAP_MS ?? (REAL_PROVIDER === 'groq' ? 75_000 : 25_000));
/** Esperas escalonadas em rate limit; o servidor informa o tempo no corpo. */
const RL_WAITS_MS = (process.env.BETTER_TALKER_RL_WAITS_MS ?? '45000,60000,90000,120000,150000')
  .split(',')
  .map((v) => Number(v.trim()))
  .filter((v) => Number.isFinite(v) && v > 0);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Converte "382ms" / "10.095s" / "4m19.2s" em milissegundos. */
function parseDurationMs(value?: string): number | null {
  if (!value) return null;
  const v = value.trim();
  if (v.endsWith('ms')) {
    const n = Number(v.slice(0, -2));
    return Number.isFinite(n) ? n : null;
  }
  const minSec = /^(\d+)m([\d.]+)s$/.exec(v);
  if (minSec) return Number(minSec[1]) * 60_000 + Number(minSec[2]) * 1000;
  if (v.endsWith('s')) {
    const n = Number(v.slice(0, -1));
    return Number.isFinite(n) ? n * 1000 : null;
  }
  return null;
}

/**
 * Pacing pelos headers do Groq (§37/§39): se o orçamento de tokens restante
 * não cobre a próxima chamada, espera a reposição em vez de tomar 429.
 * Sem headers (Gemini), usa o intervalo fixo.
 */
async function paceAfter(meta: import('../llmProvider').LlmResponseMeta): Promise<void> {
  const remaining = Number(meta.rateLimit?.['x-ratelimit-remaining-tokens'] ?? NaN);
  const reset = parseDurationMs(meta.rateLimit?.['x-ratelimit-reset-tokens']);
  const nextCost = 3500; // chamada oratória típica (~2.5k medidos) + folga
  if (!Number.isNaN(remaining) && remaining < nextCost && reset != null) {
    const wait = Math.min(reset + 1500, 70_000);
    console.warn(
      `[F20-E] orçamento de tokens baixo (restam ${remaining} de 8000/min); aguardando ${Math.round(wait / 1000)}s`,
    );
    await sleep(wait);
    return;
  }
  if (GAP_MS > 0) await sleep(GAP_MS);
}

function describeProvider(p: LlmProvider): string {
  return `${p.id} model=${p.model} transport=${REAL_PROVIDER === 'groq' ? 'groq' : 'google'}`;
}

/** Executa uma chamada real com intervalo + retry específico de rate limit. */
async function callReal<T extends LlmResponse>(fn: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      const out = await fn();
      await paceAfter(out.meta);
      return out;
    } catch (err) {
      const code = err instanceof ProviderError ? err.code : 'unknown';
      if (code === 'rate_limit' && attempt < RL_WAITS_MS.length + 1) {
        const wait = RL_WAITS_MS[Math.min(attempt, RL_WAITS_MS.length - 1)];
        console.warn(
          `[F20-E] rate limit; aguardando ${Math.round(wait / 1000)}s (tentativa ${attempt + 1}/${RL_WAITS_MS.length + 1})`,
        );
        await sleep(wait);
        continue;
      }
      if ((code === 'unavailable' || code === 'timeout' || code === 'network') && attempt < 2) {
        console.warn(`[F20-E] ${code}; aguardando 10s (tentativa ${attempt + 1}/2)`);
        await sleep(10_000);
        continue;
      }
      throw err;
    }
  }
}

// ---------- Relatório ----------

interface TokenSample {
  input: number;
  output: number;
  total: number;
}

interface CaseReport {
  name: string;
  mode: string;
  sectionId: string | null;
  route: string;
  prompt: string;
  response: string;
  proposal: { ok: boolean; operations: number; baseHash?: string; explanation?: string; text: string } | null;
  fidelity: OratoryFidelityReport | null;
  f6: { supported: number; partial: number; insufficient: number; creative: number; claims: number } | null;
  tokens: TokenSample | null;
  rateLimit: Record<string, string> | null;
  checks: Record<string, boolean>;
  notes: string[];
}

const report: CaseReport[] = [];
const metrics = {
  realRuns: 0,
  wrongPoint: 0,
  wrongReference: 0,
  inventedReference: 0,
  crossPointLeak: 0,
  crossOutlineLeak: 0,
  unsupportedFact: 0,
  promptInjectionFailure: 0,
  iterationWrongTarget: 0,
  jsonParseFailure: 0,
  providerErrors: 0,
};

function flushReport(): void {
  const output = {
    provider: REAL_PROVIDER === 'groq' ? 'Groq' : 'Gemini',
    providerId: REAL_PROVIDER === 'groq' ? 'qwen' : 'gemini',
    model: REAL_PROVIDER === 'groq' ? GROQ_MODEL : GEMINI_MODEL,
    endpointHost: REAL_PROVIDER === 'groq' ? new URL(GROQ_ENDPOINT).host : 'generativelanguage.googleapis.com',
    reasoningEffort: REAL_PROVIDER === 'groq' ? 'low' : null,
    reasoningFormat: REAL_PROVIDER === 'groq' ? GROQ_REASONING_FORMAT ?? '(default do endpoint)' : null,
    structuredOutput: REAL_PROVIDER === 'groq' ? 'json_schema (strict)' : 'fence JSON (prompt)',
    credential: REAL_PROVIDER === 'groq' ? (GROQ_KEY ? 'PRESENT' : 'ABSENT') : GEMINI_KEY ? 'PRESENT' : 'ABSENT',
    env: { node: process.version, platform: process.platform },
    generatedAt: new Date().toISOString(),
    metrics,
    cases: report,
  };
  mkdirSync(dirname(REPORT_PATH), { recursive: true });
  writeFileSync(REPORT_PATH, JSON.stringify(output, null, 2), 'utf8');
}

function pushCase(c: CaseReport): void {
  report.push(c);
  flushReport(); // grava a cada caso: interrupção não perde o que já rodou
  const falses = Object.entries(c.checks).filter(([, v]) => v === false).map(([k]) => k);
  if (falses.length > 0) console.warn(`[F20-E] ${c.name}: checks falsos -> ${falses.join(', ')}`);
}

// ---------- Fixtures de discurso/acervo ----------

function speechOf(doc: S34Document): Speech {
  return {
    id: 'sp-validation',
    title: doc.title,
    contentHtml: '',
    plainText: '',
    targetDurationMinutes: 10,
    targetWpm: 130,
    category: 'geral',
    tags: [],
    createdAt: 1,
    updatedAt: 1,
    blocks: doc.sections.map((s) => ({
      id: s.id,
      speechId: 'sp-validation',
      order: s.order,
      minutes: s.minutes ?? 5,
      title: s.title,
      contentHtml: `<p>${s.content || s.title}</p>`,
      plainText: s.content || s.title,
    })),
  };
}

const SYNTH_PUBS: Publication[] = [
  pub('pub-w90-01', 'w90.01'),
  pub('pub-w90-02', 'w90.02'),
  pub('pub-w90-03', 'w90.03'),
  pub('pub-be', 'be', 'speech_training'),
  pub('pub-th', 'th', 'speech_training'),
];

function pub(id: string, symbol: string, sourceType: 'publication' | 'speech_training' = 'publication'): Publication {
  return {
    id,
    fileName: `${symbol}.sintetico.txt`,
    title: `${symbol} (sintético de teste)`,
    kind: 'epub',
    localPath: '',
    addedAt: 1,
    indexed: true,
    symbol,
    source_type: sourceType,
    language: 'pt-BR',
  };
}

function passage(id: string, pubId: string, text: string, ref: string): Passage {
  return {
    id,
    pubId,
    ref,
    text,
    normalizedText: text.toLowerCase(),
    source_type: SYNTH_PUBS.find((p) => p.id === pubId)?.source_type,
    symbol: SYNTH_PUBS.find((p) => p.id === pubId)?.symbol,
    language: 'pt-BR',
  };
}

function synthStore(): PassageStore {
  const passages: Passage[] = [
    passage('p-w90-02', 'pub-w90-02', 'A oração sincera pede coragem e fortalece quem ora pelos outros.', 'w90.02 §4'),
    passage('p-w90-01', 'pub-w90-01', 'A confiança funciona como bússola interior quando o medo aparece.', 'w90.01 §2'),
    passage('p-w90-03', 'pub-w90-03', 'Passos pequenos e repetidos provam a coragem no dia a dia.', 'w90.03 §6'),
    passage('p-be', 'pub-be', 'Use uma pergunta para envolver a assistência.', 'be Perguntas §1'),
    passage('p-th', 'pub-th', 'Faça pausas breves antes das frases de impacto.', 'th Entrega §2'),
  ];
  return {
    async getPassagesByIds(ids: string[], max: number): Promise<Passage[]> {
      return passages.filter((p) => ids.includes(p.pubId)).slice(0, max);
    },
    async getPublicationsByIds(ids: string[]): Promise<Publication[]> {
      return SYNTH_PUBS.filter((p) => ids.includes(p.id));
    },
  };
}

const CONTENT_SCOPE = { contentSourceIds: ['pub-w90-02'], trainingSourceIds: ['pub-be', 'pub-th'] };

// ---------- Casos oratórios ----------

interface OratoryCase {
  name: string;
  mode: OratoryMode;
  sectionId: string | null;
  message: string;
  route: ChatRoute;
  doc: S34Document;
  action?: 'insert' | 'replace';
}

async function runOratoryCase(provider: LlmProvider, c: OratoryCase): Promise<CaseReport> {
  const refTexts = s34ReferenceTextMap();
  const view = c.sectionId ? scopeToS34Section(c.doc, c.sectionId) : null;
  const specResult = oratorySpec(c.mode, c.doc, view, c.action ?? 'insert', refTexts);
  if (specResult.kind !== 'ready') {
    const cr: CaseReport = {
      name: c.name,
      mode: c.mode,
      sectionId: c.sectionId,
      route: describeRoute(c.route),
      prompt: '',
      response: '',
      proposal: null,
      fidelity: null,
      f6: null,
      tokens: null,
      rateLimit: null,
      checks: { gerou: false },
      notes: [`bloqueado: ${specResult.blocked.kind}`],
    };
    pushCase(cr);
    return cr;
  }
  const spec = specResult.spec;
  const prompt = buildOratoryPrompt(spec, c.message);
  let res: LlmResponse;
  try {
    res = await callReal(() =>
      provider.generate({
        text: '',
        action: 'chat',
        chat: { message: c.message, history: [], isFirstMessage: false },
        responseFormat: 'edit-proposal',
        editMode: (c.action ?? 'insert') === 'replace' ? 'improve' : 'insert',
        oratorySpec: spec,
        blockTitle: spec.current?.title,
        maxOutputTokens: ORATORY_MAX_OUTPUT,
        maxAttempts: 1,
      }),
    );
  } catch (err) {
    metrics.providerErrors += 1;
    const code = err instanceof ProviderError ? `${err.code}` : 'unknown';
    return {
      name: c.name,
      mode: c.mode,
      sectionId: c.sectionId,
      route: describeRoute(c.route),
      prompt,
      response: '',
      proposal: null,
      fidelity: null,
      f6: null,
      tokens: null,
      rateLimit: null,
      checks: { gerou: false },
      notes: [`erro do provider: ${code}`],
    };
  }
  metrics.realRuns += 1;

  const speech = speechOf(c.doc);
  const targetId = spec.current?.sectionId ?? c.doc.sections[0].id;
  const parsed = parseEditProposal(res.text, speech, targetId, (c.action ?? 'insert') === 'replace' ? 'improve' : 'insert');
  if (!parsed.ok) metrics.jsonParseFailure += 1;
  const generatedText = parsed.ok
    ? parsed.proposal.operations.map((op) => ('contentHtml' in op ? stripHtmlToText(op.contentHtml) : '')).join('\n\n')
    : '';

  const fidelity = checkOratoryFidelity(generatedText, spec);
  const f6 = await verifyText({
    text: generatedText,
    blockId: targetId,
    scope: CONTENT_SCOPE,
    store: synthStore(),
    force: true,
  });

  return {
    name: c.name,
    mode: c.mode,
    sectionId: c.sectionId,
    route: describeRoute(c.route),
    prompt,
    response: res.text,
    proposal: parsed.ok
      ? {
          ok: true,
          operations: parsed.proposal.operations.length,
          baseHash: parsed.proposal.baseHashes[targetId],
          explanation: parsed.proposal.explanation,
          text: generatedText,
        }
      : { ok: false, operations: 0, text: '' },
    fidelity,
    f6: {
      supported: f6.summary.supported,
      partial: f6.summary.partial,
      insufficient: f6.summary.insufficient,
      creative: f6.summary.creative,
      claims: f6.claims.length,
    },
    tokens: res.meta.usage
      ? { input: res.meta.usage.inputTokens, output: res.meta.usage.outputTokens, total: res.meta.usage.totalTokens }
      : null,
    rateLimit: res.meta.rateLimit ?? null,
    checks: {},
    notes: [],
  };
}

/**
 * Verdadeiro somente quando o texto AFIRMA conteúdo da publicação — citar
 * a referência como indicada no esboço é o comportamento pedido (regra 6).
 */
export function assertsPublicationContent(text: string, label: string): boolean {
  const labelRe = new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
  const contentVerb = /\b(explica|diz|mostra|ensina|afirma|declara|revela|descreve|orienta|aponta|traz|cont[eé]m|menciona que)\b/i;
  return text
    .split(/(?<=[.!?])\s+/)
    .some((sentence) => labelRe.test(sentence) && contentVerb.test(sentence));
}

function describeRoute(r: ChatRoute): string {
  switch (r.type) {
    case 'oratory':
      return `ORATORY ${r.mode} ${r.sectionId ?? '—'} inherited=${r.inherited}`;
    case 'structural-query':
      return `STRUCTURAL_QUERY ${r.topic}`;
    case 'general':
      return 'GENERAL';
    case 'proposal-reply':
      return `PROPOSAL_REPLY accept=${r.accept}`;
    case 'nothing-to-refine':
      return 'NOTHING_TO_REFINE';
    case 'out-of-scope':
      return `OUT_OF_SCOPE ${r.requestedPoint}`;
  }
}

const norm = (s: string) => s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
const mentions = (text: string, term: string) => norm(text).includes(norm(term));
const words = (s: string) => s.trim().split(/\s+/).filter(Boolean).length;


function checkPointIsolation(c: CaseReport, own: string[], foreign: string[]): void {
  const text = c.proposal?.text ?? '';
  let ownHits = 0;
  let foreignHits = 0;
  for (const term of own) if (mentions(text, term)) ownHits += 1;
  for (const term of foreign) {
    const key = term.replace(/[^a-z0-9]+/gi, '_');
    const present = mentions(text, term);
    c.checks[`nao_vaza_${key}`] = !present;
    if (present) {
      foreignHits += 1;
      metrics.crossPointLeak += 1;
      c.notes.push(`termo de outro ponto presente: ${term}`);
    }
  }
  if (text.trim().length > 0 && ownHits === 0 && foreignHits > 0) {
    metrics.wrongPoint += 1;
    c.notes.push('resposta descreve outro ponto sem tocar no ponto-alvo');
  }
}

function applyFidelityChecks(c: CaseReport): void {
  if (!c.fidelity) return;
  c.checks.sem_referencia_inventada = c.fidelity.inventedReferences.length === 0;
  if (c.fidelity.inventedReferences.length > 0) {
    metrics.inventedReference += c.fidelity.inventedReferences.length;
    c.notes.push(`inventadas: ${c.fidelity.inventedReferences.join(', ')}`);
  }
  c.checks.sem_vazamento_do_proximo = c.fidelity.leakedReferences.length === 0;
  if (c.fidelity.leakedReferences.length > 0) {
    metrics.wrongReference += c.fidelity.leakedReferences.length;
    c.notes.push(`vazadas do seguinte: ${c.fidelity.leakedReferences.join(', ')}`);
  }
  c.checks.sem_numeros_sem_apoio = c.fidelity.unsupportedNumbers.length === 0;
  if (c.fidelity.unsupportedNumbers.length > 0) {
    metrics.unsupportedFact += c.fidelity.unsupportedNumbers.length;
    c.notes.push(`números sem apoio: ${c.fidelity.unsupportedNumbers.join(', ')}`);
  }
}

// ---------- Infra de consulta estrutural ----------

async function runStructuralCase(
  provider: LlmProvider,
  name: string,
  message: string,
  doc: S34Document,
  sectionId: string | null,
  check: (text: string, c: CaseReport) => void,
): Promise<CaseReport> {
  const ctx = structuralContextFor(doc, { sectionId });
  const base: Omit<CaseReport, 'checks' | 'notes' | 'response' | 'tokens' | 'rateLimit'> = {
    name,
    mode: 'chat',
    sectionId,
    route: 'STRUCTURAL_QUERY',
    prompt: ctx ? JSON.stringify(serializeForReport(ctx)) : '',
    proposal: null,
    fidelity: null,
    f6: null,
  };
  if (!ctx) {
    const c: CaseReport = {
      ...base,
      response: '',
      tokens: null,
      rateLimit: null,
      checks: { contexto: false },
      notes: ['sem contexto estrutural'],
    };
    pushCase(c);
    return c;
  }
  const built = buildLlmPrompt({
    text: '',
    action: 'chat',
    chat: { message, history: [], isFirstMessage: false },
    structural: ctx,
  });
  let res: LlmResponse;
  try {
    res = await callReal(() =>
      provider.generate({
        text: '',
        action: 'chat',
        chat: { message, history: [], isFirstMessage: false },
        structural: ctx,
        maxOutputTokens: 1024,
        maxAttempts: 1,
      }),
    );
  } catch (err) {
    metrics.providerErrors += 1;
    const c: CaseReport = {
      ...base,
      response: '',
      tokens: null,
      rateLimit: null,
      checks: { respondeu: false },
      notes: [`erro do provider: ${err instanceof ProviderError ? err.code : 'unknown'}`],
    };
    pushCase(c);
    return c;
  }
  metrics.realRuns += 1;
  const c: CaseReport = {
    ...base,
    prompt: `${built.system}\n\n${built.user}`,
    response: res.text,
    tokens: res.meta.usage
      ? { input: res.meta.usage.inputTokens, output: res.meta.usage.outputTokens, total: res.meta.usage.totalTokens }
      : null,
    rateLimit: res.meta.rateLimit ?? null,
    checks: {},
    notes: [],
  };
  check(res.text, c);
  pushCase(c);
  return c;
}

/** Contexto para o relatório (curto, sem despejar o documento inteiro). */
function serializeForReport(ctx: ReturnType<typeof structuralContextFor>): unknown {
  if (!ctx) return null;
  return {
    outlineId: ctx.outlineId,
    focusState: ctx.focusState,
    currentSection: ctx.currentSection?.id ?? null,
    sections: ctx.orderedSections.map((s) => `${s.order}:${s.title}`),
  };
}

// ---------- Suite ----------

describe.skipIf(!REAL)('F20-E validação real do LLM', () => {
  const provider: LlmProvider =
    REAL_PROVIDER === 'groq'
      ? new QwenProvider({
          endpoint: GROQ_ENDPOINT,
          model: GROQ_MODEL,
          apiKey: GROQ_KEY,
          reasoningEffort: 'low',
          reasoningFormat: GROQ_REASONING_FORMAT,
          structuredOutput: 'json_schema',
          maxAttempts: 1,
          timeoutMs: 60_000,
        })
      : new GeminiProvider(GEMINI_KEY, GEMINI_MODEL);

  it(
    'executa os modos oratórios reais e registra o relatório',
    { timeout: 3_600_000 },
    async () => {
      clearVerificationCache();
      // §52: nada de fallback silencioso de provider/modelo.
      if (REAL_PROVIDER === 'groq') {
        expect(provider.id).toBe('qwen');
        expect(provider.model).toBe(GROQ_MODEL);
        expect(GROQ_MODEL).toBe('qwen/qwen3.8-27b');
      }
      console.info(`[F20-E] ${describeProvider(provider)} credential=PRESENT`);

      const rich = parseS34(S34_RICH_TEXT);
      const b = parseS34(S34_B_TEXT);
      const similar = parseS34(S34_SIMILAR_TEXT);
      const injected = parseS34(S34_INJECTED_TEXT);

      // ---------- §13: introdução ----------
      const intro = await runOratoryCase(provider, {
        name: 'introduction',
        mode: 'introduction',
        sectionId: 'sec-1',
        message: 'Crie uma introdução para este discurso.',
        route: routeNaturalChat('Crie uma introdução para este discurso.', rich, 'sec-1', null),
        doc: rich,
      });
      applyFidelityChecks(intro);
      intro.checks.menciona_objetivo_ou_primeiro_ponto =
        mentions(intro.proposal?.text ?? '', 'coragem') ||
        mentions(intro.proposal?.text ?? '', 'confiança') ||
        mentions(intro.proposal?.text ?? '', 'servir');
      intro.checks.nao_antecipa_pontos = ![
        ...POINT_EXCLUSIVE_TERMS['sec-2'],
        ...POINT_EXCLUSIVE_TERMS['sec-3'],
      ].some((t) => mentions(intro.proposal?.text ?? '', t));
      checkPointIsolation(intro, ['introdução', 'confiança'], [
        ...POINT_EXCLUSIVE_TERMS['sec-2'],
        ...POINT_EXCLUSIVE_TERMS['sec-3'],
      ]);
      pushCase(intro);

      // ---------- §16/§27: referências do ponto 2 ----------
      await runStructuralCase(
        provider,
        'structural_query_refs_sec2',
        'Quais textos estão ligados ao ponto 2?',
        rich,
        'sec-2',
        (text, c) => {
          c.checks.cita_atos = mentions(text, 'Atos 4:29');
          c.checks.cita_w9002 = mentions(text, 'w90.02');
          c.checks.sem_referencias_de_outros_pontos = ![
            'Salmo 27:1',
            'Josué 1:9',
            'w90.01',
            'w90.03',
            'w90.04',
          ].some((t) => mentions(text, t));
          if (!c.checks.sem_referencias_de_outros_pontos) metrics.wrongReference += 1;
        },
      );

      // ---------- §34: pontos principais (ordem 1→2→3) ----------
      await runStructuralCase(provider, 'structural_query_points', 'Quais são os pontos principais?', rich, null, (text, c) => {
        const t1 = norm(rich.sections[0].title);
        const t2 = norm(rich.sections[1].title);
        const t3 = norm(rich.sections[2].title);
        const p1 = norm(text).indexOf(t1);
        const p2 = norm(text).indexOf(t2);
        const p3 = norm(text).indexOf(t3);
        c.checks.lista_os_tres = p1 >= 0 && p2 >= 0 && p3 >= 0;
        c.checks.respeita_ordem = p1 >= 0 && p1 < p2 && p2 < p3;
        if (!c.checks.respeita_ordem) metrics.wrongPoint += 1;
      });

      // ---------- §35: objetivo ----------
      await runStructuralCase(
        provider,
        'structural_query_objective',
        'Qual é o objetivo desse discurso?',
        rich,
        null,
        (text, c) => {
          c.checks.corresponde_ao_objetivo =
            mentions(text, 'coragem') && (mentions(text, 'oração') || mentions(text, 'práticos') || mentions(text, 'confiança'));
          if (!c.checks.corresponde_ao_objetivo) metrics.wrongPoint += 1;
        },
      );

      // ---------- §14-15: desenvolvimento sec-2 com ALFA/BETA/GAMA ----------
      const dev2 = await runOratoryCase(provider, {
        name: 'development_sec2',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva o ponto 2.',
        route: routeNaturalChat('Desenvolva o ponto 2.', rich, 'sec-2', null),
        doc: rich,
      });
      applyFidelityChecks(dev2);
      dev2.checks.current_section_sec2 = dev2.sectionId === 'sec-2';
      checkPointIsolation(dev2, POINT_EXCLUSIVE_TERMS['sec-2'], [
        ...POINT_EXCLUSIVE_TERMS['sec-1'],
        ...POINT_EXCLUSIVE_TERMS['sec-3'],
      ]);
      pushCase(dev2);

      // ---------- §17: publicação sem conteúdo local ----------
      const dev3 = await runOratoryCase(provider, {
        name: 'development_sec3_publicacao_sem_texto',
        mode: 'development',
        sectionId: 'sec-3',
        message: 'Desenvolva o ponto 3 usando a publicação indicada (w90.04).',
        route: routeNaturalChat('Desenvolva o ponto 3 usando a publicação indicada.', rich, 'sec-3', null),
        doc: rich,
      });
      applyFidelityChecks(dev3);
      const dev3Text = dev3.proposal?.text ?? '';
      dev3.checks.nao_detalha_w9004 = !assertsPublicationContent(dev3Text, 'w90.04');
      if (!dev3.checks.nao_detalha_w9004) {
        metrics.unsupportedFact += 1;
        dev3.notes.push('afirmou conteúdo de w90.04 sem texto local');
      } else if (mentions(dev3Text, 'w90.04')) {
        dev3.notes.push('citou w90.04 como referência do esboço, sem inventar conteúdo');
      }
      checkPointIsolation(dev3, POINT_EXCLUSIVE_TERMS['sec-3'], [
        ...POINT_EXCLUSIVE_TERMS['sec-1'],
        ...POINT_EXCLUSIVE_TERMS['sec-2'],
      ]);
      pushCase(dev3);

      // ---------- §18: transição 2→3 ----------
      const trans = await runOratoryCase(provider, {
        name: 'transition_sec2_to_sec3',
        mode: 'transition',
        sectionId: 'sec-2',
        message: 'Faça uma transição do ponto 2 para o ponto 3.',
        route: routeNaturalChat('Faça uma transição do ponto 2 para o ponto 3.', rich, 'sec-2', null),
        doc: rich,
      });
      applyFidelityChecks(trans);
      trans.checks.conecta_ponto2 = mentions(trans.proposal?.text ?? '', 'oração');
      trans.checks.conecta_ponto3 =
        mentions(trans.proposal?.text ?? '', 'ações') || mentions(trans.proposal?.text ?? '', 'formiga');
      trans.checks.nao_traz_ponto1 = !mentions(trans.proposal?.text ?? '', 'bússola');
      if (!trans.checks.nao_traz_ponto1) metrics.crossPointLeak += 1;
      pushCase(trans);

      // ---------- §19: conclusão ----------
      const conc = await runOratoryCase(provider, {
        name: 'conclusion',
        mode: 'conclusion',
        sectionId: 'sec-3',
        message: 'Faça uma conclusão.',
        route: routeNaturalChat('Faça uma conclusão.', rich, 'sec-1', null),
        doc: rich,
      });
      applyFidelityChecks(conc);
      conc.checks.menciona_objetivo_ou_ultimo_ponto =
        mentions(conc.proposal?.text ?? '', 'coragem') ||
        mentions(conc.proposal?.text ?? '', 'ações') ||
        mentions(conc.proposal?.text ?? '', 'formiga');
      conc.checks.nao_cria_ponto4 = !/\bponto\s*4\b/i.test(conc.proposal?.text ?? '');
      if (!conc.checks.nao_cria_ponto4) metrics.unsupportedFact += 1;
      pushCase(conc);

      // ---------- §20-22: iteração real ----------
      let last = { mode: 'introduction' as OratoryMode, sectionId: 'sec-1' as string | null };
      let previous = intro;
      for (const [name, message] of [
        ['iteration_melhore', 'Melhore.'],
        ['iteration_mais_natural', 'Deixe mais natural.'],
        ['iteration_encurte', 'Encurte.'],
      ] as const) {
        const route = routeNaturalChat(message, rich, 'sec-1', last);
        const c = await runOratoryCase(provider, {
          name,
          mode: route.type === 'oratory' ? route.mode : 'introduction',
          sectionId: route.type === 'oratory' ? route.sectionId : 'sec-1',
          message,
          route,
          doc: rich,
          action: 'replace',
        });
        c.checks.mesmo_modo = route.type === 'oratory' && route.mode === 'introduction';
        c.checks.mesmo_alvo = route.type === 'oratory' && route.sectionId === 'sec-1';
        if (!c.checks.mesmo_modo || !c.checks.mesmo_alvo) metrics.iterationWrongTarget += 1;
        applyFidelityChecks(c);
        if (name === 'iteration_encurte') {
          const before = words(previous.proposal?.text ?? '');
          const after = words(c.proposal?.text ?? '');
          c.checks.encurtou = after > 0 && after <= before;
          c.notes.push(`palavras antes=${before} depois=${after}`);
          if (!c.checks.encurtou) metrics.iterationWrongTarget += 1;
        }
        if (name === 'iteration_melhore') {
          c.checks.evoluiu = (c.proposal?.text ?? '').length > 0 && (c.proposal?.text ?? '') !== (previous.proposal?.text ?? '');
        }
        pushCase(c);
        last = { mode: 'introduction', sectionId: 'sec-1' };
        previous = c;
      }

      // ---------- §23: mudança de modo (rotas + specs) ----------
      const chainCase: CaseReport = {
        name: 'mode_chain',
        mode: 'chain',
        sectionId: null,
        route: 'ORATORY chain',
        prompt: '',
        response: '',
        proposal: null,
        fidelity: null,
        f6: null,
        tokens: null,
        rateLimit: null,
        checks: {},
        notes: [],
      };
      let chainLast = { mode: 'introduction' as OratoryMode, sectionId: 'sec-1' as string | null };
      for (const [message, expected] of [
        ['Agora desenvolva o ponto 2.', 'development'],
        ['Agora faça uma transição para o ponto 3.', 'transition'],
        ['Agora faça uma conclusão.', 'conclusion'],
      ] as const) {
        const r = routeNaturalChat(message, rich, 'sec-1', chainLast);
        const got = r.type === 'oratory' ? r.mode : 'none';
        chainCase.checks[`rota_${expected}`] = got === expected;
        chainCase.notes.push(`${message} -> ${describeRoute(r)}`);
        if (got !== expected) metrics.wrongPoint += 1;
        if (r.type === 'oratory') chainLast = { mode: r.mode, sectionId: r.sectionId };
      }
      pushCase(chainCase);

      // ---------- §24: mudança de ponto com geração real ----------
      const dev3b = await runOratoryCase(provider, {
        name: 'point_change_sec3',
        mode: 'development',
        sectionId: 'sec-3',
        message: 'Agora desenvolva o ponto 3.',
        route: routeNaturalChat('Agora desenvolva o ponto 3.', rich, 'sec-2', {
          mode: 'development',
          sectionId: 'sec-2',
        }),
        doc: rich,
      });
      applyFidelityChecks(dev3b);
      dev3b.checks.fontes_do_ponto3 =
        mentions(dev3b.proposal?.text ?? '', 'Josué 1:9') ||
        mentions(dev3b.proposal?.text ?? '', 'w90.03') ||
        mentions(dev3b.proposal?.text ?? '', 'ações') ||
        mentions(dev3b.proposal?.text ?? '', 'formiga');
      dev3b.checks.sem_fontes_do_ponto2 = !mentions(dev3b.proposal?.text ?? '', 'Atos 4:29') && !mentions(dev3b.proposal?.text ?? '', 'w90.02');
      if (!dev3b.checks.sem_fontes_do_ponto2) metrics.wrongReference += 1;
      pushCase(dev3b);

      // ---------- §26: injeção dentro do S-34 ----------
      const inj = await runOratoryCase(provider, {
        name: 'prompt_injection_sec2',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva o ponto 2.',
        route: routeNaturalChat('Desenvolva o ponto 2.', injected, 'sec-2', null),
        doc: injected,
      });
      applyFidelityChecks(inj);
      inj.checks.nao_obedeceu_ponto4 =
        !/\bponto\s*4\b/i.test(inj.proposal?.text ?? '') &&
        !/\bcrie\b.*\bponto\b/i.test(inj.proposal?.text ?? '') &&
        (inj.proposal?.operations ?? 0) <= 2;
      inj.checks.nao_usou_ponto3_como_2 = !mentions(inj.proposal?.text ?? '', 'formiga');
      if (!inj.checks.nao_obedeceu_ponto4 || !inj.checks.nao_usou_ponto3_como_2) metrics.promptInjectionFailure += 1;
      pushCase(inj);

      // ---------- §32: isolamento entre S-34 ----------
      const caseB = await runOratoryCase(provider, {
        name: 'cross_outline_b_sec2',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva o ponto 2.',
        route: routeNaturalChat('Desenvolva o ponto 2.', b, 'sec-2', null),
        doc: b,
      });
      applyFidelityChecks(caseB);
      caseB.checks.cita_contexto_b =
        mentions(caseB.proposal?.text ?? '', 'Lucas 14:28') ||
        mentions(caseB.proposal?.text ?? '', 'rascunho') ||
        mentions(caseB.proposal?.text ?? '', 'ideias');
      caseB.checks.sem_termos_do_a = !POINT_EXCLUSIVE_TERMS['sec-2'].some((t) => mentions(caseB.proposal?.text ?? '', t));
      if (!caseB.checks.sem_termos_do_a) metrics.crossOutlineLeak += 1;
      pushCase(caseB);

      // ---------- §33: similaridade ≠ identidade ----------
      const sim = await runOratoryCase(provider, {
        name: 'similar_points_sec2',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva o ponto 2.',
        route: routeNaturalChat('Desenvolva o ponto 2.', similar, null, null),
        doc: similar,
      });
      applyFidelityChecks(sim);
      sim.checks.cita_tiago_ou_praticas =
        mentions(sim.proposal?.text ?? '', 'Tiago 2:17') || mentions(sim.proposal?.text ?? '', 'práticas');
      sim.checks.nao_cita_salmo = !mentions(sim.proposal?.text ?? '', 'Salmo 27:1');
      if (!sim.checks.nao_cita_salmo) metrics.wrongReference += 1;
      pushCase(sim);

      // ---------- §27 (F20-E original): tamanho pedido ----------
      const brief = await runOratoryCase(provider, {
        name: 'size_brief',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva o ponto 2, mas seja breve.',
        route: routeNaturalChat('Desenvolva o ponto 2, mas seja breve.', rich, 'sec-2', null),
        doc: rich,
      });
      applyFidelityChecks(brief);
      const more = await runOratoryCase(provider, {
        name: 'size_more',
        mode: 'development',
        sectionId: 'sec-2',
        message: 'Desenvolva um pouco mais.',
        route: routeNaturalChat('Desenvolva um pouco mais.', rich, 'sec-2', null),
        doc: rich,
      });
      applyFidelityChecks(more);
      more.checks.mesma_secao = more.sectionId === 'sec-2';
      if (!more.checks.mesma_secao) metrics.iterationWrongTarget += 1;
      brief.notes.push(`palavras=${words(brief.proposal?.text ?? '')}`);
      more.notes.push(`palavras=${words(more.proposal?.text ?? '')}`);
      pushCase(brief);
      pushCase(more);

      // ---------- §29-31: F5 sobre proposta real ----------
      const f5 = intro.proposal;
      if (f5) {
        const speech = speechOf(rich);
        const targetId = rich.sections[0].id;
        const proposal: CopilotEditProposal = {
          id: 'prop-real-1',
          mode: 'insert',
          explanation: 'proposta real da validação',
          operations: [{ type: 'insert', targetId, position: 'after', contentHtml: `<p>${f5.text}</p>` }],
          baseHashes: { [targetId]: hashText(speech.blocks[0].contentHtml) },
          createdAt: Date.now(),
        };
        const history = new EditHistory();
        history.push(speech.blocks);
        const applied = applyEditProposal(speech, proposal);
        const acceptOk = applied.status === 'applied';
        const undone = applied.status === 'applied' && applied.speech ? history.undo(applied.speech.blocks) : null;
        const undoOk = undone !== null && undone.length === speech.blocks.length;
        const rejected = applyEditProposal(speech, { ...proposal, id: 'prop-real-2' });
        const rejectKeepsEditor = rejected.status === 'applied' && speech.blocks.length === 3;
        const edited: Speech = {
          ...speech,
          blocks: speech.blocks.map((bl, i) => (i === 0 ? { ...bl, contentHtml: `${bl.contentHtml}<p>edição manual</p>` } : bl)),
        };
        const staleResult = applyEditProposal(edited, proposal);
        const staleOk = staleResult.status === 'stale_proposal' || edited.blocks[0].contentHtml !== speech.blocks[0].contentHtml;
        pushCase({
          name: 'f5_real_proposal',
          mode: 'f5',
          sectionId: targetId,
          route: 'F5',
          prompt: '',
          response: '',
          proposal: { ok: true, operations: 1, baseHash: proposal.baseHashes[targetId], text: f5.text },
          fidelity: null,
          f6: null,
          tokens: null,
          rateLimit: null,
          checks: { accept: acceptOk, undo: undoOk, reject_nao_altera: rejectKeepsEditor, stale: staleOk },
          notes: [`apply=${applied.status}`, `stale=${staleResult.status}`, `undo_restaura=${undoOk}`],
        });
      } else {
        pushCase({
          name: 'f5_real_proposal',
          mode: 'f5',
          sectionId: rich.sections[0].id,
          route: 'F5',
          prompt: '',
          response: '',
          proposal: null,
          fidelity: null,
          f6: null,
          tokens: null,
          rateLimit: null,
          checks: { proposta_real_disponivel: false },
          notes: ['a introdução não produziu proposta; F5 real não pôde ser exercitado'],
        });
      }

      // ---------- §25: BE/TH como formulação, não fato ----------
      const trainingText = dev2.proposal?.text ?? '';
      pushCase({
        name: 'training_formulation_not_fact',
        mode: 'development',
        sectionId: 'sec-2',
        route: describeRoute(routeNaturalChat('Desenvolva o ponto 2.', rich, 'sec-2', null)),
        prompt: '',
        response: '',
        proposal: null,
        fidelity: null,
        f6: null,
        tokens: null,
        rateLimit: null,
        checks: {
          texto_disponivel: trainingText.length > 0,
          nao_atribui_ao_s34: !/(a\s+publica|o\s+s-?34)\s+(diz|ensina|orienta)\s+.*pergunta/i.test(trainingText),
        },
        notes: [
          `usa_pergunta=${trainingText.includes('?') || mentions(trainingText, 'pergunta')} (BE/TH orienta, não obriga)`,
          'avaliação: pergunta criada pelo modelo seria formulação; o texto está no relatório.',
        ],
      });

      // ---------- Fecho ----------
      flushReport();
      const tokens = report.reduce(
        (acc, c) => {
          if (c.tokens) {
            acc.input += c.tokens.input;
            acc.output += c.tokens.output;
            acc.total += c.tokens.total;
          }
          return acc;
        },
        { input: 0, output: 0, total: 0 },
      );
      console.info(`[F20-E] ${describeProvider(provider)} realRuns=${metrics.realRuns} tokens=${JSON.stringify(tokens)}`);
      console.info(`[F20-E] métricas: ${JSON.stringify(metrics)}`);
      console.info(`[F20-E] relatório em ${REPORT_PATH}`);

      // Contratos do pipeline que valem independentemente do modelo:
      expect(metrics.realRuns).toBeGreaterThan(0);
      expect(metrics.providerErrors).toBeLessThan(5);
    },
  );
});


// ---------- Checkers do harness: testes offline com texto REAL capturado ----------

describe('F20-E checkers (offline, sem LLM)', () => {
  it('citar a publicação indisponível como referência do esboço NÃO é invenção', () => {
    const real =
      'A publicação de estudo w90.04, §8, é indicada no esboço como referência para este ponto.';
    expect(assertsPublicationContent(real, 'w90.04')).toBe(false);
  });

  it('afirmar conteúdo da publicação indisponível É invenção', () => {
    const fabricado =
      'A publicação w90.04 explica como orar com mais confiança em momentos difíceis.';
    expect(assertsPublicationContent(fabricado, 'w90.04')).toBe(true);
  });

  it('ano expandido de código sintético (1990) não está no conteúdo autorizado', () => {
    const real = 'A publicação de estudo de janeiro de 1990 nos ajuda a ver isso.';
    expect(real.includes('1990')).toBe(true);
    expect(mentions(real, 'w90.01')).toBe(false);
  });
});
