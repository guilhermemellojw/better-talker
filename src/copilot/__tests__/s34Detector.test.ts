import { describe, it, expect } from 'vitest';
import { isS34, hasS34Marker, s34Signals, detectBibleVerses, MIN_S34_CHARS } from '../s34Detector';
import { detectCitations } from '../../services/citationDetector';
import { S34_FIXTURE_TEXT } from './s34Fixture';

/**
 * Fase 19-B.1 — testes do detector S-34 (§11) + teste da fixture (§12).
 * Espelho conceitual de android/.../S34DetectorTest.kt (mesmo contrato).
 */
describe('s34Detector', () => {
  // ---------- Positivos (§11.1-2) ----------

  it('fixture S-34 é reconhecida', () => {
    expect(isS34(S34_FIXTURE_TEXT)).toBe(true);
  });

  it('marcador tolera variações', () => {
    expect(isS34(S34_FIXTURE_TEXT.replace('S-34', 's-34'))).toBe(true);
    expect(isS34(S34_FIXTURE_TEXT.replace('S-34', 'S34'))).toBe(true);
  });

  // ---------- Negativos (§11.3-7) ----------

  it('documento genérico não é S-34', () => {
    const text =
      'Ata da reunião de condomínio do dia 12.\n' +
      'Presentes: síndico, zelador e três moradores.\n' +
      'Pauta: reforma da garagem e pintura da fachada.\n'.repeat(4);
    expect(isS34(text)).toBe(false);
  });

  it('anotação pessoal com pontos não é S-34', () => {
    const text =
      'Minhas anotações da semana.\n' +
      '1. comprar pão e leite no mercado\n' +
      '2. ligar para o banco sobre a fatura\n' +
      '3. buscar as crianças na escola\n'.repeat(4);
    expect(isS34(text)).toBe(false);
  });

  it('publicação comum não é S-34', () => {
    const text =
      'A Sentinela de estudo nos lembra de orar sem cessar. ' +
      'Ver w24.03 e meditar em João 3:16 todos os dias. '.repeat(6);
    expect(isS34(text)).toBe(false);
  });

  it('texto bíblico isolado não é S-34', () => {
    expect(
      isS34('João 3:16 Porque Deus amou tanto o mundo que deu o seu Filho unigênito. João 1:1 No princípio era a Palavra.'),
    ).toBe(false);
  });

  it('documento BE/TH não é S-34', () => {
    const text =
      ('Beneficie-se da Escola do Ministério Teocrático. ' +
        'Lição 5: leia com entusiasmo e contato visual. ').repeat(6);
    expect(isS34(text)).toBe(false);
  });

  // ---------- Robustez (§11.8-10) ----------

  it('documento vazio não é S-34', () => {
    expect(isS34('')).toBe(false);
    expect(isS34('   \n  ')).toBe(false);
  });

  it('documento muito curto não é S-34', () => {
    expect(isS34('S-34')).toBe(false);
    expect(isS34('S-34: fé')).toBe(false);
  });

  it('refs bíblicas sem estrutura não bastam', () => {
    const text =
      'S-34\n' + 'Leia a Bíblia com atenção todos os dias. '.repeat(8) + '\nLeia João 17:17. Leia Tiago 2:17.';
    expect(text.length).toBeGreaterThanOrEqual(MIN_S34_CHARS);
    expect(hasS34Marker(text)).toBe(true);
    expect(isS34(text)).toBe(false);
  });

  // ---------- Fixture (§12) ----------

  it('fixture tem sinais para F19-B.2', () => {
    const t = S34_FIXTURE_TEXT;
    expect(isS34(t)).toBe(true);
    expect(t).toContain('Objetivo:');
    const points = t.match(/^\s*\d[.)]\s+\S/gm) ?? [];
    expect(points.length).toBe(3);
    const subideas = t.match(/^\s*[a-z]\)/gm) ?? [];
    expect(subideas.length).toBeGreaterThanOrEqual(2);
    expect(detectBibleVerses(t).length).toBeGreaterThanOrEqual(3);
    expect(detectCitations(t, 's34').length).toBeGreaterThanOrEqual(2);
    const pointStarts = [...t.matchAll(/^\s*\d[.)]\s+\S/gm)].map((m) => m.index ?? -1);
    expect(pointStarts.length).toBe(3);
    expect(pointStarts[0]).toBeGreaterThanOrEqual(0);
    expect(pointStarts[1]).toBeGreaterThan(pointStarts[0]);
    expect(pointStarts[2]).toBeGreaterThan(pointStarts[1]);
    expect(/introdução/i.test(t)).toBe(false);
    expect(/conclusão/i.test(t)).toBe(false);
    expect(s34Signals(t).size).toBeGreaterThanOrEqual(2);
  });
});
