// Importação de esboços — Fase 10 (§18). Sem alterar parsers.
// Nota honesta: .docx binário não é extraído (mammoth sem uso); o parser
// lê o buffer como texto. Estes testes travam o comportamento real.

import { describe, expect, it } from 'vitest';
import { parseOutline } from '../../services/outlineParser';

function buf(text: string): ArrayBuffer {
  return new TextEncoder().encode(text).buffer as ArrayBuffer;
}

describe('parseOutline', () => {
  it('fatia blocos por (N min) com títulos e minutos', () => {
    const { speech, blocks } = parseOutline(buf(
      'Abertura (2 min)\nTexto da abertura aqui.\nDesenvolvimento (8 min)\nTexto longo do desenvolvimento.',
    ));
    expect(blocks).toHaveLength(2);
    expect(blocks[0].minutes).toBe(2);
    expect(blocks[1].minutes).toBe(8);
    expect(speech.targetDurationMinutes).toBe(10);
  });

  it('sem marcadores: bloco único, sem crash', () => {
    const { blocks } = parseOutline(buf('Texto corrido sem nenhuma marca de tempo.'));
    expect(blocks).toHaveLength(1);
    expect(blocks[0].minutes).toBe(5);
  });

  it('buffer vazio: bloco único vazio, sem crash', () => {
    const { blocks } = parseOutline(buf(''));
    expect(blocks).toHaveLength(1);
  });

  it('binário (docx/jwpub real) não quebra: degrada para bloco único', () => {
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0xfe, 0x00, 0x14]);
    const { blocks } = parseOutline(bytes.buffer as ArrayBuffer);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].plainText).toBeDefined();
  });

  it('múltiplos marcadores somam o tempo total', () => {
    const { speech } = parseOutline(buf('A (3 min)\nx\nB (4 min)\ny\nC (5 min)\nz'));
    expect(speech.targetDurationMinutes).toBe(12);
  });

  it('título em maiúsculas vira título do bloco', () => {
    const { blocks } = parseOutline(buf('MINHA ABERTURA\n(2 min)\nTexto aqui.'));
    expect(blocks[0].title).toBe('MINHA ABERTURA');
  });
});
