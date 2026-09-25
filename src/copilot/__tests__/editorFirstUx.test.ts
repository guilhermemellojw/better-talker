import { describe, it, expect } from 'vitest';
import { contextLabel, QUICK_ACTIONS } from '../chatEngine';

/**
 * Fase 17 — Editor-First UX Reset.
 *
 * Testes que travam o comportamento do produto como EDITOR-FIRST:
 * - contexto prioriza seleção > bloco > discurso;
 * - nada de ids técnicos no rótulo;
 * - quick actions preservadas;
 * - contratos F15 mantidos.
 */
describe('Fase 17 — Editor-First UX Reset', () => {
  describe('contexto do Copilot (§11)', () => {
    it('seleção tem prioridade sobre bloco e discurso', () => {
      const lbl = contextLabel('Ponto 1', 'Meu Discurso', 'texto selecionado');
      expect(lbl).toBe('Contexto: trecho selecionado');
    });

    it('ausência de seleção usa o bloco ativo', () => {
      const lbl = contextLabel('Ponto 2', 'Meu Discurso', '');
      expect(lbl).toBe('Contexto: Ponto 2');
    });

    it('ausência de seleção e bloco usa o discurso', () => {
      const lbl = contextLabel('', 'Meu Discurso', '');
      expect(lbl).toBe('Contexto: Meu Discurso');
    });

    it('nada em foco devolve null (sem inventar contexto)', () => {
      const lbl = contextLabel('', '', '');
      expect(lbl).toBeNull();
    });

    it('ignora seleção só com espaços', () => {
      const lbl = contextLabel('Introdução', 'Discurso', '   ');
      expect(lbl).toBe('Contexto: Introdução');
    });
  });

  describe('quick actions preservadas da F15 (§14)', () => {
    it('mantém os 4 atalhos essenciais', () => {
      expect(QUICK_ACTIONS.map((q) => q.label)).toEqual([
        'Melhorar este ponto',
        'Deixar mais natural',
        'Criar ilustração',
        'Verificar',
      ]);
    });

    it('cada atalho é uma mensagem livre válida para o pipeline', () => {
      for (const qa of QUICK_ACTIONS) {
        expect(qa.message.trim().length).toBeGreaterThan(0);
        expect(qa.id).toBeTruthy();
      }
    });
  });
});
