// Testes da inteligência estrutural — Fase 9 (§§29,30,31).

import { describe, expect, it } from 'vitest';
import type { Speech, SpeechBlock } from '../../types/speech';
import {
  analysisCache,
  analysisKeyFor,
  analyzeSpeech,
  speechContentHash,
} from '../speechAnalyzer';
import { ANALYSIS_VERSION } from '../speechAnalysis';
import { answerStructuralIntent, intentForQuestion } from '../structuralIntents';
import { buildLlmPrompt } from '../llmPrompt';
import { verifyText } from '../verifier';
import { emptyScope } from '../retrievalTypes';
import { fixtureStore } from './fixtures';

function block(id: string, title: string, text: string, minutes = 5): SpeechBlock {
  const html = `<p>${text}</p>`;
  return { id, speechId: 's1', order: 0, minutes, title, contentHtml: html, plainText: text };
}

function speechOf(title: string, blocks: SpeechBlock[]): Speech {
  return {
    id: 's1', title, contentHtml: '', plainText: '', targetDurationMinutes: 10,
    targetWpm: 130, category: 'geral', tags: [], createdAt: 1, updatedAt: 1, blocks,
  };
}

const INTRO = 'O que significa confiar em Jeová hoje? Hoje vamos ver o objetivo deste discurso sobre confiança e como aplicá-lo.';
const BODY = (t: string) => `A confiança sincera em Jeová fortalece a coragem dos servos leais em tempos de provação e aflição. ${t}`;
const OUTRO = 'Portanto, em resumo, a confiança em Jeová nos sustenta. Apliquemos isso nesta semana. Obrigado.';

function obsOf(analysis: ReturnType<typeof analyzeSpeech>, type: string) {
  return analysis.observations.filter((o) => o.type === type);
}

describe('introduction', () => {
  it('clara: sinais geram papel + INFO', () => {
    const a = analyzeSpeech(speechOf('Confiança em Jeová', [block('b1', 'Abertura', INTRO), block('b2', 'Ponto', BODY('Estudo.'))]), { force: true });
    expect(a.structure[0].roles).toContain('INTRODUCTION');
    expect(obsOf(a, 'INTRODUCTION').some((o) => o.severity === 'INFO')).toBe(true);
  });

  it('ausente: ? sem afirmar', () => {
    const a = analyzeSpeech(speechOf('Tema X', [block('b1', 'Um', 'Texto corrido sobre vários assuntos cotidianos sem rumo.'), block('b2', 'Dois', BODY('Mais conteúdo aqui.'))]), { force: true });
    const struct = obsOf(a, 'STRUCTURE');
    expect(struct.length).toBe(1);
    expect(struct[0].message).toMatch(/Não encontrei estrutura suficiente/);
    expect(a.structure[0].roles).not.toContain('INTRODUCTION');
  });

  it('ambígua (1 sinal): silêncio, sem papel', () => {
    const a = analyzeSpeech(speechOf('Tema Y', [block('b1', 'Um', 'Você já pensou sobre a vida e suas escolhas difíceis?'), block('b2', 'Dois', BODY('Conteúdo.'))]), { force: true });
    expect(obsOf(a, 'INTRODUCTION')).toHaveLength(0);
    expect(obsOf(a, 'STRUCTURE')).toHaveLength(0);
    expect(a.structure[0].roles).not.toContain('INTRODUCTION');
  });
});

describe('development', () => {
  it('pontos identificáveis com contagem', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', INTRO), block('b2', 'P1', BODY('Detalhe um com conteúdo adicional relevante.')), block('b3', 'P2', BODY('Detalhe dois com conteúdo adicional relevante.')),
    ]), { force: true });
    expect(a.structure[1].roles).toContain('POINT');
    expect(a.structure[2].roles).toContain('POINT');
    expect(obsOf(a, 'POINT')[0]?.message).toMatch(/2 ponto/);
  });

  it('bloco curto não vira ponto nem gera alerta', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', INTRO), block('b2', 'Curto', 'Nota breve.')]), { force: true });
    expect(a.structure[1].roles).not.toContain('POINT');
    expect(obsOf(a, 'POINT')).toHaveLength(0);
  });
});

describe('transition', () => {
  it('conector explícito: sem alerta', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', BODY('Primeira parte do assunto.')),
      block('b2', 'B', `Portanto, além disso vamos ver a segunda parte do mesmo assunto com coragem e fé, para desenvolver a ideia com calma e atenção.`),
    ]), { force: true });
    expect(obsOf(a, 'TRANSITION')).toHaveLength(0);
  });

  it('mudança de assunto sem conexão: ATTENTION com sugestão', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', BODY('Primeira parte do assunto sobre coragem com detalhes suficientes aqui.')),
      block('b2', 'B', 'Receitas de bolo de cenoura com cobertura crocante, forno pré-aquecido e fermento em pó. Misture tudo com calma e leve para assar por quarenta minutos.'),
    ]), { force: true });
    const t = obsOf(a, 'TRANSITION');
    expect(t.length).toBe(1);
    expect(t[0].severity).toBe('ATTENTION');
    expect(t[0].suggestion).toBeTruthy();
    expect(t[0].blockIds).toEqual(['b1', 'b2']);
  });
});

describe('conclusion', () => {
  it('clara: papel + INFO', () => {
    const a = analyzeSpeech(speechOf('Confiança em Jeová', [block('b1', 'A', INTRO), block('b2', 'Fim', OUTRO)]), { force: true });
    expect(a.structure[1].roles).toContain('CONCLUSION');
    expect(obsOf(a, 'CONCLUSION').some((o) => o.severity === 'INFO')).toBe(true);
  });

  it('sem retomada: SUGGESTION gentil, não erro', () => {
    const a = analyzeSpeech(speechOf('Confiança em Jeová', [block('b1', 'A', INTRO), block('b2', 'Fim', 'E foi isso que eu queria dizer hoje.')]), { force: true });
    const c = obsOf(a, 'CONCLUSION');
    expect(c.length).toBe(1);
    expect(c[0].severity).toBe('SUGGESTION');
    expect(c[0].message).toMatch(/pode ficar mais conectado/);
  });

  it('ambígua (1 sinal): silêncio', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', INTRO), block('b2', 'Fim', 'Portanto vamos considerar esse assunto com bastante atenção.')]), { force: true });
    expect(obsOf(a, 'CONCLUSION')).toHaveLength(0);
  });
});

describe('repetition', () => {
  it('frase literal repetida: ATTENTION com blocos', () => {
    const rep = 'A oração sincera feita com fé move montanhas de preocupação.';
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', `${rep} Mais texto aqui.`), block('b2', 'B', `Outro início. ${rep}`)]), { force: true });
    const r = obsOf(a, 'REPETITION');
    expect(r.length).toBeGreaterThanOrEqual(1);
    expect(r[0].severity).toBe('ATTENTION');
    expect(r[0].blockIds).toContain('b1');
    expect(r[0].blockIds).toContain('b2');
  });

  it('vocabulário próximo em textos longos: ATTENTION', () => {
    const mk = (extra: string) => `Confiar em Jeová fortalece a coragem e a fé dos servos leais em tempos de provação e aflição constante na vida cristã diária. ${extra}`;
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', mk('Primeira abordagem do tema.')), block('b2', 'B', mk('Segunda abordagem do tema.'))]), { force: true });
    expect(obsOf(a, 'REPETITION').some((o) => o.severity === 'ATTENTION')).toBe(true);
  });

  it('mesmo tema, frases distintas: sem alerta (anti falso-positivo)', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', 'A oração nos aproxima de Deus nas manhãs tranquilas de estudo.'),
      block('b2', 'B', 'Cantar louvores alegra o coração da família reunida.'),
    ]), { force: true });
    expect(obsOf(a, 'REPETITION')).toHaveLength(0);
  });
});

describe('balance', () => {
  const pad = (n: number) => Array.from({ length: n }, (_, i) => `palavra${i}`).join(' ');
  it('ponto 3x maior: ATTENTION relativo, sem dizer errado', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', INTRO), block('b2', 'P1', BODY(pad(10))), block('b3', 'P2', BODY(pad(120))), block('b4', 'P3', BODY(pad(10))),
    ]), { force: true });
    const b = obsOf(a, 'BALANCE');
    expect(b.length).toBe(1);
    expect(b[0].severity).toBe('ATTENTION');
    expect(b[0].message).toMatch(/ocupa ~.*x mais texto/);
    expect(b[0].suggestion).toMatch(/intencional/);
  });

  it('diferença moderada (1.5x): silêncio', () => {
    const a = analyzeSpeech(speechOf('T', [
      block('b1', 'A', INTRO), block('b2', 'P1', BODY(pad(20))), block('b3', 'P2', BODY(pad(45))), block('b4', 'P3', BODY(pad(25))),
    ]), { force: true });
    expect(obsOf(a, 'BALANCE')).toHaveLength(0);
  });
});

describe('time', () => {
  it('cálculo com pausas e arredondamento', () => {
    const words = Array.from({ length: 120 }, (_, i) => `w${i}`).join(' ');
    const blk: SpeechBlock = {
      id: 'b1', speechId: 's1', order: 0, minutes: 2, title: 'A',
      contentHtml: `<p>${words}</p><span class="stage-cue-badge" data-cue-type="pause-2s"></span>`,
      plainText: words,
    };
    const sp = speechOf('T', [blk]);
    const a = analyzeSpeech(sp, { wpm: 120, force: true });
    // 120 palavras a 120ppm = 60s + 2s de pausa = 62s.
    expect(a.estimatedTime.totalSeconds).toBe(62);
    expect(a.estimatedTime.perBlock).toHaveLength(1);
    expect(a.estimatedTime.perBlock[0].seconds).toBe(62);
    expect(a.estimatedTime.formattedTotal).toBe('~1 min 02 s');
  });

  it('wpm configurável altera a estimativa', () => {
    const words = Array.from({ length: 120 }, (_, i) => `w${i}`).join(' ');
    const sp = speechOf('T', [block('b1', 'A', words)]);
    const fast = analyzeSpeech(sp, { wpm: 120, force: true });
    const slow = analyzeSpeech(sp, { wpm: 60, force: true });
    expect(slow.estimatedTime.totalSeconds).toBe(fast.estimatedTime.totalSeconds * 2);
  });

  it('disclaimer sempre presente + observação TIME', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', 'Texto simples.')]), { force: true });
    expect(a.estimatedTime.disclaimer).toMatch(/tempo real depende/);
    expect(obsOf(a, 'TIME').length).toBe(1);
  });
});

describe('clareza e naturalidade', () => {
  it('frase >35: ATTENTION; 20 palavras: silêncio', () => {
    const long = Array.from({ length: 40 }, (_, i) => `palavra${i}`).join(' ') + '.';
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', `${long} Curto.`) ]), { force: true });
    expect(obsOf(a, 'CLARITY').some((o) => o.severity === 'ATTENTION')).toBe(true);
    const ok = analyzeSpeech(speechOf('T', [block('b1', 'A', `${Array.from({ length: 20 }, (_, i) => `p${i}`).join(' ')}.`) ]), { force: true });
    expect(obsOf(ok, 'CLARITY')).toHaveLength(0);
  });

  it('naturalidade exige ≥2 sinais e sugere sem afirmar', () => {
    const long = (open: string, extra: string) =>
      `${open} ${extra} consideramos detidamente todos os aspectos fundamentais da matéria em questão nesta ocasião solene diante da assistência presente e atenta com profunda reverência e sincero apreço pela verdade revelada`;
    const sents = [`Outrossim nós ${long('', '')}.`, `Ademais também ${long('', '')}.`, `Destarte assim ${long('', '')}.`].join(' ');
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', sents)]), { force: true });
    const n = obsOf(a, 'NATURALNESS');
    expect(n.length).toBe(1);
    expect(n[0].severity).toBe('SUGGESTION');
    expect(n[0].suggestion).toMatch(/Pode soar mais natural/);
  });
});

describe('não-alertas (§30)', () => {
  it('sem ilustração/aplicação em ponto: nenhum alerta por ausência', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', INTRO), block('b2', 'P', BODY('Seco e direto.'))]), { force: true });
    const types = new Set(a.observations.map((o) => o.type));
    expect(types.has('ILLUSTRATION')).toBe(false);
    expect(types.has('APPLICATION')).toBe(false);
  });

  it('ponto sem todas as categorias BE/TH: sem alerta', () => {
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', INTRO), block('b2', 'P', BODY('Só explicação direta.'))]), { force: true });
    expect(a.observations.every((o) => o.severity !== 'ATTENTION' || o.type !== 'POINT')).toBe(true);
  });
});

describe('contexto, F5, F6 e segurança', () => {
  it('análise é pura: não muta o discurso, roda síncrona sem rede', () => {
    const sp = speechOf('T', [block('b1', 'A', INTRO)]);
    const before = JSON.stringify(sp);
    const a = analyzeSpeech(sp, { force: true });
    expect(JSON.stringify(sp)).toBe(before);
    expect(a.speechId).toBe('s1');
    expect(a.version).toBe(ANALYSIS_VERSION);
  });

  it('F6: sugestão com estatística inventada continua insuficiente', async () => {
    const res = await verifyText({
      text: 'Pesquisas mostram que 70% das pessoas preferem introduções com perguntas.',
      blockId: 'b1', scope: emptyScope(), store: fixtureStore(), force: true,
    });
    expect(res.claims.some((c) => c.status === 'insufficient')).toBe(true);
  });

  it('criação do modelo não é atribuída à publicação', () => {
    const prompt = buildLlmPrompt({
      text: 'bloco', action: 'rewrite', responseFormat: 'edit-proposal', editMode: 'insert',
      brief: 'Adicionar uma frase curta de ligação.',
    });
    expect(prompt.user).toContain('Adicionar uma frase curta de ligação.');
    expect(prompt.user).toMatch(/sugestão do modelo|apresente como sugestão/i);
  });

  it('intents mapeiam perguntas e respondem localmente', () => {
    expect(intentForQuestion('Minha introdução está boa?')).toBe('analyze_introduction');
    expect(intentForQuestion('Quanto tempo isso leva?')).toBe('estimate_time');
    expect(intentForQuestion('Estou repetindo a mesma ideia?')).toBe('analyze_repetition');
    expect(intentForQuestion('fale sobre ornitorrincos')).toBeNull();
    const a = analyzeSpeech(speechOf('T', [block('b1', 'A', INTRO)]), { force: true });
    const ans = answerStructuralIntent('estimate_time', a, (id) => id);
    expect(ans).toMatch(/Tempo estimado/);
  });

  it('cache versionado: mesma entrada repete objeto; edição invalida', () => {
    analysisCache.clear();
    const sp = speechOf('T', [block('b1', 'A', INTRO)]);
    const a = analyzeSpeech(sp);
    const b = analyzeSpeech(sp);
    expect(a).toBe(b);
    expect(a.contentHash).toBe(speechContentHash(sp));
    const key1 = analysisKeyFor(sp.id, a.contentHash);
    const edited: Speech = { ...sp, blocks: [{ ...sp.blocks[0], contentHtml: '<p>Mudou.</p>', plainText: 'Mudou.' }] };
    expect(analysisKeyFor(edited.id, speechContentHash(edited))).not.toBe(key1);
  });
});
