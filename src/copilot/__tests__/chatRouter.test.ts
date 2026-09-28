import { describe, it, expect } from 'vitest';
import { parseS34 } from '../s34Parser';
import { scopeToS34Section } from '../s34StructuralRetrieval';
import { oratorySpec, type OratoryMode } from '../oratoryGeneration';
import { type LastOratoryGeneration } from '../oratorySession';
import {
  routeNaturalChat,
  normalizeOratoryPhrasing,
  describeChatRoute,
  type ChatRoute,
} from '../chatRouter';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 20-D — roteamento natural do chat (Web).
 * Espelho conceitual de android/.../ChatRouterTest.kt.
 */
describe('chatRouter', () => {
  const doc = parseS34(S34_FIXTURE_TEXT);

  const route = (
    text: string,
    document = doc as typeof doc | null,
    current: string | null = 'sec-2',
    last: LastOratoryGeneration | null = null,
  ): ChatRoute => routeNaturalChat(text, document, current, last);

  const oratory = (r: ChatRoute) => {
    if (r.type !== 'oratory') throw new Error(`esperado ORATORY, veio ${describeChatRoute(r)}`);
    return r;
  };

  // ---------- Comandos de geração ----------

  it('comandos de introdução', () => {
    for (const t of ['Crie uma introdução.', 'Faça uma abertura.', 'Como começo esse discurso?']) {
      const r = oratory(route(t));
      expect(r.mode, t).toBe('introduction');
      expect(r.sectionId, t).toBe('sec-1');
    }
  });

  it('comandos de desenvolvimento', () => {
    expect(oratory(route('Desenvolva o ponto 2.')).sectionId).toBe('sec-2');
    expect(oratory(route('Me ajude a desenvolver esse ponto.')).sectionId).toBe('sec-2');
    const explicar = oratory(route('Explique melhor o ponto 2.'));
    expect(explicar.mode).toBe('development');
    expect(explicar.sectionId).toBe('sec-2');
  });

  it('transição explícita e natural', () => {
    const t3 = oratory(route('Faça uma transição para o ponto 3.'));
    expect(t3.mode).toBe('transition');
    // F20-E: a âncora da transição é a ORIGEM (2→3), nunca o destino.
    expect(t3.sectionId).toBe('sec-2');
    const natural = oratory(route('Como passo para o próximo ponto?'));
    expect(natural.mode).toBe('transition');
    expect(natural.sectionId).toBe('sec-2');
  });

  it('comandos de conclusão', () => {
    for (const t of ['Faça uma conclusão.', 'Como posso concluir?']) {
      const r = oratory(route(t));
      expect(r.mode, t).toBe('conclusion');
      expect(r.sectionId, t).toBe('sec-3');
    }
  });

  // ---------- Iteração ----------

  it('iteração permanece na introdução', () => {
    const last: LastOratoryGeneration = { mode: 'introduction', sectionId: 'sec-1' };
    for (const t of ['Melhore.', 'Deixe mais natural.', 'Encurte.']) {
      const r = oratory(route(t, doc, 'sec-2', last));
      expect(r.mode, t).toBe('introduction');
      expect(r.sectionId, t).toBe('sec-1');
      expect(r.inherited, t).toBe(true);
    }
  });

  it('"melhore isso" usa o alvo anterior', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    const r = oratory(route('Melhore isso.', doc, 'sec-2', last));
    expect(r.sectionId).toBe('sec-2');
    expect(r.action).toBe('replace');
  });

  it('troca de modo em cadeia', () => {
    const intro = oratory(route('Crie uma introdução.'));
    const dev = oratory(route('Agora desenvolva o ponto 2.', doc, 'sec-1', { mode: intro.mode, sectionId: intro.sectionId }));
    expect(dev.mode).toBe('development');
    expect(dev.sectionId).toBe('sec-2');
    expect(dev.inherited).toBe(false);
  });

  it('troca de ponto atualiza a seção', () => {
    const last: LastOratoryGeneration = { mode: 'development', sectionId: 'sec-2' };
    const r = oratory(route('Agora desenvolva o ponto 3.', doc, 'sec-2', last));
    expect(r.sectionId).toBe('sec-3');
  });

  // ---------- Perguntas estruturais ----------

  it('perguntas estruturais não geram proposta', () => {
    const casos: Array<[string, string]> = [
      ['Qual é o objetivo desse discurso?', 'objective'],
      ['Quais são os pontos principais?', 'points'],
      ['Quais textos estão ligados ao ponto 2?', 'references'],
      ['Qual publicação está ligada ao ponto 3?', 'references'],
      ['Qual é a sequência dos pontos?', 'sequence'],
    ];
    for (const [texto, topico] of casos) {
      const r = route(texto);
      expect(r.type, texto).toBe('structural-query');
      if (r.type === 'structural-query') expect(r.topic, texto).toBe(topico);
    }
  });

  // ---------- Falsos positivos ----------

  it('perguntas sobre o papel da introdução não geram', () => {
    for (const t of [
      'O que o S-34 diz sobre a introdução?',
      'Qual é a conclusão do esboço?',
      'Existe uma introdução nesse esboço?',
    ]) {
      const r = route(t);
      expect(r.type, t).not.toBe('oratory');
      expect(['structural-query', 'general'], t).toContain(r.type);
    }
  });

  it('"explique o termo" não é geração', () => {
    const r = route('Explique o termo desenvolvimento.');
    expect(r.type).toBe('general');
  });

  it('"como importar arquivo" não é oratório', () => {
    expect(route('Como posso importar um arquivo?').type).not.toBe('oratory');
  });

  // ---------- Ausência de contexto ----------

  it('refinamento sem proposta não inventa alvo', () => {
    const r = route('Melhore.');
    expect(r.type).toBe('nothing-to-refine');
  });

  it('sem S-34 a rota oratória bloqueia com estado honesto', () => {
    const r = oratory(route('Crie uma introdução.', null, null, null));
    expect(r.mode).toBe('introduction');
    expect(r.sectionId).toBeNull();
    const g = oratorySpec(r.mode, parseS34(''), null, r.action);
    expect(g.kind).toBe('cannot-generate');
    if (g.kind === 'cannot-generate') expect(g.blocked.kind).toBe('no-structure');
  });

  it('transição do último ponto bloqueia com estado explícito', () => {
    const last: LastOratoryGeneration = { mode: 'transition', sectionId: 'sec-2' };
    const r = oratory(route('Faça uma transição para o próximo.', doc, 'sec-3', last));
    expect(r.sectionId).toBe('sec-3');
    const v = scopeToS34Section(doc, 'sec-3')!;
    const g = oratorySpec(r.mode, doc, v, r.action);
    expect(g.kind).toBe('cannot-generate');
    if (g.kind === 'cannot-generate') expect(g.blocked.kind).toBe('no-next-section');
  });

  // ---------- Réplica de proposta ----------

  it('aceitar e rejeitar vão para o fluxo existente', () => {
    const a = route('Aceitar');
    expect(a.type).toBe('proposal-reply');
    if (a.type === 'proposal-reply') expect(a.accept).toBe(true);
    const r = route('Rejeitar');
    expect(r.type).toBe('proposal-reply');
    if (r.type === 'proposal-reply') expect(r.accept).toBe(false);
  });

  // ---------- Fora do escopo estrutural ----------

  it('criar ponto 4 é recusado sem alterar a estrutura', () => {
    const r = route('Crie um ponto 4 para este discurso.');
    expect(r.type).toBe('out-of-scope');
    if (r.type === 'out-of-scope') expect(r.requestedPoint).toBe(4);
    expect(doc.sections.length).toBe(3);
  });

  // ---------- Chat geral ----------

  it('chat geral continua existindo', () => {
    for (const t of ['Bom dia!', 'Obrigado pela ajuda.', 'Me explique esse assunto.']) {
      expect(route(t).type, t).toBe('general');
    }
  });

  // ---------- Sem score e diagnóstico ----------

  it('rota não tem score nem confiança', () => {
    const keys = Object.keys(oratory(route('Desenvolva o ponto 2.'))).map((k) => k.toLowerCase());
    expect(keys.some((k) => k.includes('score'))).toBe(false);
    expect(keys.some((k) => k.includes('confidence'))).toBe(false);
  });

  it('diagnóstico tem rota, modo e seção sem texto privado', () => {
    const d = describeChatRoute(oratory(route('Desenvolva o ponto 2.')));
    expect(d).toContain('route=ORATORY');
    expect(d).toContain('mode=development');
    expect(d).toContain('section=sec-2');
    expect(d).not.toContain(doc.objective ?? '###');
  });

  it('normalização de phráse natural', () => {
    expect(normalizeOratoryPhrasing('Como posso começar?')).toBe('Crie uma introdução');
    expect(normalizeOratoryPhrasing('Como passo para o próximo ponto?')).toBe('Crie uma transição');
    expect(normalizeOratoryPhrasing('Pode explicar melhor o ponto 2?')).toBe('Desenvolva o ponto 2');
    expect(normalizeOratoryPhrasing('Bom dia')).toBeNull();
  });

  it('modos cobertos pelo roteador são os quatro da geração', () => {
    const modos = new Set<OratoryMode>();
    modos.add(oratory(route('Crie uma introdução.')).mode);
    modos.add(oratory(route('Desenvolva o ponto 2.')).mode);
    modos.add(oratory(route('Faça uma transição para o ponto 3.')).mode);
    modos.add(oratory(route('Faça uma conclusão.')).mode);
    expect([...modos].sort()).toEqual(['conclusion', 'development', 'introduction', 'transition']);
  });
});
