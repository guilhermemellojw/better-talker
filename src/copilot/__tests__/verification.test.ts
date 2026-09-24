// Testes de verificação — Fase 6 (§30).

import { describe, expect, it } from 'vitest';
import { extractClaims } from '../claimExtractor';
import {
  clearVerificationCache,
  verificationKeyFor,
  verifyText,
  verifyTextWithJudge,
} from '../verifier';
import { LlmClaimJudge, StaticClaimJudge } from '../verificationJudge';
import { FakeLlmProvider } from '../fakeProvider';
import { FIX_SCOPE, fixtureStore } from './fixtures';
import type { RetrievalScope } from '../retrievalTypes';

const STORE = () => fixtureStore();

async function verify(text: string, scope: RetrievalScope = FIX_SCOPE) {
  clearVerificationCache();
  return verifyText({ text, blockId: 'b1', scope, store: STORE(), force: true });
}

describe('claim extraction', () => {
  it('factual, bíblica, aplicação, criatividade, training', () => {
    expect(extractClaims('Confiar em Jeová nos ajuda em problemas graves.')[0]?.type).toBe('factual');
    expect(extractClaims('A Sentinela w24.01 explica a criação.')[0]?.type).toBe('biblical');
    expect(extractClaims('Isso pode nos ajudar a confiar mais em Jeová.')[0]?.type).toBe('application');
    expect(extractClaims('Imagine que você atravessa uma tempestade.')[0]?.type).toBe('creative');
    expect(extractClaims('Use ilustrações simples e transições breves.')[0]?.type).toBe('training');
    expect(extractClaims('O que isso nos ensina hoje?')[0]?.type).toBe('rhetorical');
  });

  it('compostas dividem em múltiplas claims com offsets', () => {
    const text = 'Moisés cresceu no palácio e Depois passou 40 anos em Midiã.';
    const claims = extractClaims(text, 'b1');
    expect(claims.length).toBe(2);
    for (const c of claims) {
      expect(text.slice(c.start, c.end)).toBe(c.text);
      expect(c.blockId).toBe('b1');
    }
  });

  it('aplicação com número gera sub-claim factual', () => {
    const claims = extractClaims('Isso pode nos ajudar, pois 70% das pessoas oram.');
    expect(claims.some((c) => c.type === 'application')).toBe(true);
    expect(claims.some((c) => c.type === 'factual' && c.text.includes('70%'))).toBe(true);
  });
});

describe('evidence e verificação', () => {
  it('supported com evidência e proveniência', async () => {
    const res = await verify('Confiar em Jeová ajuda a enfrentar problemas graves com coragem.');
    const vc = res.claims.find((c) => c.claim.id === 'claim-1');
    expect(vc?.status).toBe('supported');
    expect(vc?.evidence.length).toBeGreaterThan(0);
    const prov = vc!.evidence[0].provenance;
    expect(prov.passageId).toBe('p-w24-1');
    expect(prov.ref).toBe('w24 Confiança §3');
    expect(prov.section).toBe('Confiança');
    expect(res.summary.supported).toBeGreaterThanOrEqual(1);
  });

  it('número sem evidência => partial, nunca supported', async () => {
    const res = await verify('Estudos mostram que 70% das pessoas oram todos os dias.');
    const factual = res.claims.filter((c) => c.claim.type === 'factual');
    expect(factual.length).toBeGreaterThan(0);
    for (const vc of factual) expect(vc.status).not.toBe('supported');
  });

  it('número presente na evidência => supported', async () => {
    const res = await verify('O parágrafo 5 fala da oração sincera que fortalece a amizade com Deus.');
    expect(res.claims[0]?.status).toBe('supported');
  });

  it('ausência total => insufficient sem evidência', async () => {
    const res = await verify('Xeretas quânticos de marte cantam em coro.');
    expect(res.claims[0]?.status).toBe('insufficient');
    expect(res.claims[0]?.evidence).toHaveLength(0);
  });

  it('criativo não exige fonte', async () => {
    const res = await verify('Imagine que você atravessa uma tempestade escura.');
    expect(res.claims[0]?.status).toBe('creative');
    expect(res.summary.creative).toBe(1);
  });
});

describe('referências', () => {
  it('referência existente com suporte => supported', async () => {
    const res = await verify('A oração sincera fortalece nossa amizade com Deus todos os dias, w24.01.');
    expect(res.claims[0]?.status).toBe('supported');
  });

  it('referência sem correspondência => insufficient', async () => {
    const res = await verify('Conforme xyz99.99, tudo muda de repente.');
    expect(res.claims[0]?.status).toBe('insufficient');
  });
});

describe('segurança, escopo, training, stale', () => {
  it('sem evidência não autoriza inventar: estatística absurda nunca é supported', async () => {
    const res = await verify('Pesquisas mostram que 99% dos anjos cantam em coro.');
    for (const vc of res.claims) {
      if (vc.claim.type === 'factual') expect(vc.status).not.toBe('supported');
    }
  });

  it('verificador não sai do escopo autorizado', async () => {
    const res = await verify('A oração sincera fortalece nossa amizade com Deus.', {
      contentSourceIds: ['pub-g'],
      trainingSourceIds: [],
    });
    for (const vc of res.claims) {
      for (const e of vc.evidence) {
        expect(e.provenance.pubId).toBe('pub-g');
      }
    }
  });

  it('training usa BE/TH; conteúdo factual nunca usa training', async () => {
    const training = await verify('Use ilustrações simples do cotidiano para ensinar.');
    expect(training.claims[0]?.claim.type).toBe('training');
    expect(training.claims[0]?.status).toBe('supported');
    expect(training.claims[0]?.evidence[0]?.provenance.sourceType).toBe('speech_training');

    const content = await verify('A oração sincera fortalece nossa amizade com Deus.');
    for (const vc of content.claims) {
      for (const e of vc.evidence) {
        expect(e.provenance.sourceType).not.toBe('speech_training');
      }
    }
  });

  it('stale: mudou o texto, muda a chave; cache repete objeto no mesmo input', async () => {
    const scope = FIX_SCOPE;
    const k1 = verificationKeyFor('b1', 'Texto A.', scope);
    const k2 = verificationKeyFor('b1', 'Texto B.', scope);
    expect(k1).not.toBe(k2);
    clearVerificationCache();
    const input = { text: 'Confiar em Jeová nos ajuda.', blockId: 'b1', scope, store: STORE() };
    const a = await verifyText(input);
    const b = await verifyText(input);
    expect(a).toBe(b);
    expect(a.key).toBe(verificationKeyFor('b1', input.text, scope));
  });

  it('resultado local é limitado (sem juiz remoto)', async () => {
    const res = await verify('Confiar em Jeová nos ajuda.');
    expect(res.limited).toBe(true);
  });
});

describe('juiz LLM separado', () => {
  it('juiz estático com evidência válida qualifica partial => supported', async () => {
    clearVerificationCache();
    const base = await verifyText({
      text: 'Estudos mostram que 70% das pessoas oram todos os dias.',
      blockId: 'b1', scope: FIX_SCOPE, store: STORE(), force: true,
    });
    const partial = base.claims.find((c) => c.status === 'partially_supported');
    expect(partial).toBeDefined();
    const judge = new StaticClaimJudge({
      verdict: 'supports',
      evidenceIds: partial!.evidence.map((e) => e.evidenceId),
      reason: 'confirma',
    });
    const refined = await verifyTextWithJudge(
      { text: 'Estudos mostram que 70% das pessoas oram todos os dias.', blockId: 'b1', scope: FIX_SCOPE, store: STORE(), force: true },
      judge,
    );
    expect(refined.limited).toBe(false);
    expect(refined.claims.find((c) => c.claim.id === partial!.claim.id)?.status).toBe('supported');
  });

  it('juiz citando evidência inexistente não altera nada', async () => {
    const judge = new StaticClaimJudge({ verdict: 'supports', evidenceIds: ['fantasma'], reason: 'x' });
    const refined = await verifyTextWithJudge(
      { text: 'A oração sincera fortalece a amizade com Deus.', blockId: 'b1', scope: FIX_SCOPE, store: STORE(), force: true },
      judge,
    );
    expect(refined.summary.supported).toBeGreaterThanOrEqual(1);
  });

  it('LlmClaimJudge parseia veredito em cerca json', async () => {
    const fake = new FakeLlmProvider({
      critique: '```json\n{"verdict": "partial", "evidenceIds": ["p-w24-1"], "reason": "parcial"}\n```',
    });
    const judge = new LlmClaimJudge(fake);
    const verdict = await judge.judge({
      claim: { id: 'c1', text: 'Confiar ajuda.', type: 'factual' },
      evidence: [{ id: 'p-w24-1', ref: 'w24', text: 'Confiar em Jeová ajuda.' }],
    });
    expect(verdict.verdict).toBe('partial');
    expect(verdict.evidenceIds).toEqual(['p-w24-1']);
  });

  it('juiz com resposta inválida lança invalid_response', async () => {
    const fake = new FakeLlmProvider({ critique: 'texto livre sem json' });
    const judge = new LlmClaimJudge(fake);
    await expect(judge.judge({
      claim: { id: 'c1', text: 'X.', type: 'factual' },
      evidence: [],
    })).rejects.toMatchObject({ name: 'ProviderError' });
  });
});
