// F20-E §8 — smoke test real do transporte Groq/Qwen (uma única chamada).
//
// Só roda com GROQ_API_KEY no ambiente (ou VITE_QWEN_API_KEY).
// Nunca imprime a chave: reporta apenas provider/modelo/latência/tokens.

import { describe, it, expect } from 'vitest';
import { parseS34 } from '../s34Parser';
import { scopeToS34Section } from '../s34StructuralRetrieval';
import { oratorySpec, buildOratoryPrompt } from '../oratoryGeneration';
import { parseEditProposal } from '../proposalParser';
import { QwenProvider } from '../qwenProvider';
import type { Speech } from '../../types/speech';
import { S34_RICH_TEXT, s34ReferenceTextMap } from './s34RichFixture';

const KEY = (process.env.GROQ_API_KEY ?? process.env.VITE_QWEN_API_KEY ?? '').trim();
const ENDPOINT = process.env.BETTER_TALKER_GROQ_ENDPOINT ?? 'https://api.groq.com/openai/v1';
const MODEL = process.env.BETTER_TALKER_GROQ_MODEL ?? 'qwen/qwen3.8-27b';
/** Só com credencial plausível (formato Groq), nunca placeholder. */
const REAL = KEY.startsWith('gsk_') && KEY.length > 20;

function speechOfSections(doc: ReturnType<typeof parseS34>): Speech {
  return {
    id: 'sp-smoke',
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
      speechId: 'sp-smoke',
      order: s.order,
      minutes: s.minutes ?? 5,
      title: s.title,
      contentHtml: `<p>${s.content || s.title}</p>`,
      plainText: s.content || s.title,
    })),
  };
}

describe.skipIf(!REAL)('F20-E smoke Groq/Qwen', () => {
  it(
    'HTTP → resposta → parse → JSON → EditProposal',
    { timeout: 120_000 },
    async () => {
      const provider = new QwenProvider({
        endpoint: ENDPOINT,
        model: MODEL,
        apiKey: KEY,
        reasoningEffort: 'low',
        reasoningFormat: (process.env.BETTER_TALKER_GROQ_REASONING_FORMAT as 'parsed' | undefined) ?? undefined,
        structuredOutput: 'json_schema',
        maxAttempts: 1,
        timeoutMs: 60_000,
      });
      expect(provider.model).toBe('qwen/qwen3.8-27b');

      const doc = parseS34(S34_RICH_TEXT);
      const view = scopeToS34Section(doc, 'sec-1')!;
      const spec = oratorySpec('introduction', doc, view, 'insert', s34ReferenceTextMap());
      expect(spec.kind).toBe('ready');
      if (spec.kind !== 'ready') return;
      const prompt = buildOratoryPrompt(spec.spec, 'Crie uma introdução para este discurso.');
      expect(prompt).toContain('ESTRUTURA DO S-34');

      const res = await provider.generate({
        text: '',
        action: 'chat',
        chat: { message: 'Crie uma introdução para este discurso.', history: [], isFirstMessage: false },
        responseFormat: 'edit-proposal',
        editMode: 'insert',
        oratorySpec: spec.spec,
        maxOutputTokens: 2048,
        maxAttempts: 1,
      });

      const speech = speechOfSections(doc);
      const parsed = parseEditProposal(res.text, speech, 'sec-1', 'insert');
      const summary = {
        provider: provider.id,
        transport: 'groq',
        endpointHost: new URL(ENDPOINT).host,
        model: res.meta.model,
        status: 'ok',
        latencyMs: res.meta.durationMs,
        attempts: res.meta.attempts,
        offline: res.meta.offline,
        usage: res.meta.usage ?? null,
        rateLimit: res.meta.rateLimit ?? null,
        structuredOutput: 'json_schema',
        reasoningEffort: 'low',
        parsed: parsed.ok ? 'EditProposal' : 'invalid_proposal',
        operations: parsed.ok ? parsed.proposal.operations.length : 0,
      };
      // Diagnóstico sem segredo nem texto integral.
      console.info(`[F20-E smoke] ${JSON.stringify(summary)}`);

      expect(res.meta.offline).toBe(false);
      expect(parsed.ok).toBe(true);
      if (parsed.ok) {
        expect(parsed.proposal.operations.length).toBeGreaterThan(0);
        expect(parsed.proposal.baseHashes['sec-1']).toBeTruthy();
      }
    },
  );
});
