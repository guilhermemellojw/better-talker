// Testes de prompt e integração do chat — Fase 15 (§41, §42, §40.11–12, §40.15, §40.17).
// Casos A–D do §41 + verificação de que o chat reusa o motor existente
// (F5/F6 intocados) e que regeneração reaproveita contexto.

import { describe, expect, it } from 'vitest';
import { buildLlmPrompt, INSUFFICIENT_EVIDENCE_MESSAGE } from '../llmPrompt';
import { FakeLlmProvider } from '../fakeProvider';
import { inferIntent, type ChatMessage } from '../chatEngine';
import type { LlmRequest } from '../llmProvider';

function msg(role: ChatMessage['role'], text: string): ChatMessage {
  return { id: `${role}-${text}`, role, text, createdAt: 0 };
}

/** Monta request de chat igual ao que o CopilotDrawer envia (§14). */
function chatRequest(message: string, history: ChatMessage[] = []): LlmRequest {
  const isFirstMessage = history.length === 0;
  return {
    action: 'chat',
    text: 'Texto do bloco em foco.',
    chat: { message, history, isFirstMessage },
    blockTitle: 'Ponto 1',
    blockMinutes: 5,
  };
}

describe('§41 Caso A — “Quero melhorar essa introdução.”', () => {
  it('contexto do bloco presente, intent compatível, sem prompt técnico exigido', () => {
    expect(inferIntent('Quero melhorar essa introdução.', true).action).toBe('hook');
    const { system, user } = buildLlmPrompt(chatRequest('Quero melhorar essa introdução.'));
    expect(user).toContain('Quero melhorar essa introdução.');
    expect(user).toContain('Ponto 1'); // bloco ativo entra no prompt (§9)
    expect(system).toContain(INSUFFICIENT_EVIDENCE_MESSAGE); // regras preservadas
    // §16: a instrução de resposta é conversacional; usuário não vê nada disso.
    expect(user).not.toContain('Escolha uma intenção');
  });
});

describe('§41 Caso B — “Agora deixe mais natural.” (continuação)', () => {
  it('contexto da conversa anterior preservado + intent de estilo', () => {
    const history = [msg('user', 'Melhore esse ponto.'), msg('assistant', 'Uma forma é...')];
    const { user } = buildLlmPrompt(chatRequest('Agora deixe mais natural.', history));
    expect(user).toContain('CONVERSA ANTERIOR');
    expect(user).toContain('Melhore esse ponto.');
    expect(user).toContain('Uma forma é...');
    expect(user).toContain('Agora deixe mais natural.');
    expect(user).toContain('Continuidade'); // "isso/essa parte" referem-se ao anterior
  });
});

describe('§41 Caso C — “Crie uma ilustração para isso.”', () => {
  it('trilho de training adequado, sem tratar BE/TH como fonte factual', () => {
    const intent = inferIntent('Crie uma ilustração para isso.', true);
    expect(intent.trainingCategory).toBe('illustration');
    // As regras anti-atribuição continuam no system (§36).
    const { system } = buildLlmPrompt(chatRequest('Crie uma ilustração para isso.'));
    expect(system).toContain('sugestão do modelo');
    expect(system).toContain('nunca as use como fonte de fatos');
  });
});

describe('§41 Caso D — “Isso realmente está na publicação?”', () => {
  it('fluxo factual/verificação com CONTENT priorizado', () => {
    const intent = inferIntent('Isso realmente está na publicação?', true);
    expect(intent.verification).toBe(true);
    expect(intent.trainingCategory).toBeNull(); // training não vira prova factual
    const { user } = buildLlmPrompt(chatRequest('Isso realmente está na publicação?'));
    expect(user).toContain('FONTES DE CONTEÚDO');
  });
});

describe('§40.8–9 prompt nunca transforma training em conteúdo', () => {
  it('system mantém as regras de fidelidade da Fase 4/7 (§35)', () => {
    const { system } = buildLlmPrompt(chatRequest('Melhore isso.'));
    expect(system).toMatch(/NUNCA invente/i);
    expect(system).toMatch(/COMO apresentar/);
    expect(system).toMatch(/nunca as use como fonte de fatos/i);
  });
});

describe('§40.15 quick action usa a mesma lógica do chat livre', () => {
  it('quick action e mensagem digitada produzem o mesmo prompt', () => {
    const typed = buildLlmPrompt(chatRequest('Deixe mais natural.'));
    const quick = buildLlmPrompt(chatRequest('Deixe mais natural.'));
    expect(quick.user).toBe(typed.user);
    expect(quick.system).toBe(typed.system);
  });
});

describe('§40.17 regeneração reaproveita contexto', () => {
  it('“outra versão” vai como mensagem de chat com o histórico intacto', async () => {
    const fake = new FakeLlmProvider({ chat: 'Outra versão conversacional.' });
    const history = [msg('user', 'Melhore esse ponto.'), msg('assistant', 'Primeira versão.')];
    const req = chatRequest('Gere outra versão da resposta anterior.', history);
    const res = await fake.generate(req);
    expect(res.text).toBe('Outra versão conversacional.');
    const { user } = buildLlmPrompt(req);
    expect(user).toContain('Primeira versão.'); // mesmo contexto
  });
});

describe('§48 sem streaming falso — resposta única e limpa', () => {
  it('provider entrega resposta completa (uma chamada, sem simulação)', async () => {
    const fake = new FakeLlmProvider({ chat: 'Resposta completa de uma vez.' });
    const res = await fake.generate(chatRequest('Melhore isso.'));
    expect(res.text).toBe('Resposta completa de uma vez.');
    expect(res.meta.attempts).toBeLessThanOrEqual(1);
  });
});
