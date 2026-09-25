// Testes do motor de conversa — Fase 15 (§40).
// Cobre: validação de envio, intenção inferida, trilhos CONTENT/TRAINING,
// limite de histórico, continuidade, erros amigáveis e rótulo de contexto.

import { describe, expect, it } from 'vitest';
import {
  inferIntent,
  validateOutgoingMessage,
  friendlyChatError,
  recentHistory,
  chatBriefToText,
  contextLabel,
  OFFLINE_CHAT_NOTICE,
  FOLLOW_UP_SUGGESTIONS,
  QUICK_ACTIONS,
  MAX_HISTORY_MESSAGES,
  type ChatMessage,
} from '../chatEngine';

function msg(role: ChatMessage['role'], text: string, createdAt = 0): ChatMessage {
  return { id: `${role}-${text}`, role, text, createdAt };
}

describe('§40.1–3 validação de envio', () => {
  it('mensagem vazia rejeitada', () => {
    expect(validateOutgoingMessage('')).toEqual({ ok: false, reason: 'empty' });
  });

  it('whitespace-only rejeitado', () => {
    expect(validateOutgoingMessage('   \n\t  ')).toEqual({ ok: false, reason: 'empty' });
  });

  it('mensagem normal aceita e sem espaços nas bordas', () => {
    const r = validateOutgoingMessage('  Quero melhorar essa introdução.  ');
    expect(r).toEqual({ ok: true, text: 'Quero melhorar essa introdução.' });
  });
});

describe('§40.7 intenção inferida sem seleção manual', () => {
  it('“quero melhorar essa introdução” → hook/introduction', () => {
    const i = inferIntent('Quero melhorar essa introdução.', true);
    expect(i.action).toBe('hook');
    expect(i.trainingCategory).toBe('introduction');
    expect(i.verification).toBe(false);
  });

  it('“crie uma ilustração” → trilho training/illustration', () => {
    const i = inferIntent('Crie uma ilustração para esse ponto.', true);
    expect(i.trainingCategory).toBe('illustration');
  });

  it('“deixe mais natural” → naturalness', () => {
    expect(inferIntent('Deixe mais natural.', true).trainingCategory).toBe('naturalness');
  });

  it('“faça uma transição” → transition', () => {
    expect(inferIntent('Faça uma transição aqui.', true).trainingCategory).toBe('transition');
  });

  it('“mais curta” → shorten/clarity', () => {
    const i = inferIntent('Agora mais curta.', true);
    expect(i.action).toBe('shorten');
    expect(i.trainingCategory).toBe('clarity');
  });

  it('“como aplico isso?” → application', () => {
    expect(inferIntent('Como aplico isso?', true).trainingCategory).toBe('application');
  });
});

describe('§40.8–9 trilhos CONTENT/TRAINING por intenção', () => {
  it('intent de conteúdo/verificação NÃO recebe training', () => {
    const i = inferIntent('Essa explicação está correta?', true);
    expect(i.verification).toBe(true);
    expect(i.trainingCategory).toBeNull();
  });

  it('intent de apresentação PODE receber training', () => {
    const i = inferIntent('Como posso apresentar isso de forma mais natural?', true);
    expect(i.trainingCategory).not.toBeNull();
  });

  it('mensagem factual genérica fica só no CONTENT (null)', () => {
    expect(inferIntent('O que diz a publicação sobre oração?', true).trainingCategory).toBeNull();
  });
});

describe('§40.10 e §18 limite de histórico', () => {
  it('histórico respeita limite (só as últimas MAX_HISTORY_MESSAGES)', () => {
    const many: ChatMessage[] = [];
    for (let i = 1; i <= 20; i++) {
      many.push(msg('user', `mensagem antiga ${i}`));
      many.push(msg('assistant', `resposta antiga ${i}`));
    }
    const serialized = recentHistory(many);
    expect(serialized).not.toContain('mensagem antiga 1\n');
    expect(serialized).toContain(`mensagem antiga ${20 - MAX_HISTORY_MESSAGES / 2 + 1}`);
    expect(serialized).toContain('mensagem antiga 20');
    expect(serialized).toContain('CONVERSA ANTERIOR');
  });

  it('histórico vazio produz string vazia', () => {
    expect(recentHistory([])).toBe('');
  });

  it('mensagem gigante é truncada na serialização', () => {
    const big = 'x'.repeat(2000);
    const out = recentHistory([msg('user', big)]);
    expect(out).toContain('…');
    expect(out.length).toBeLessThan(1000);
  });
});

describe('§40.6, §40.16 continuidade da conversa', () => {
  it('continuação inclui mensagens anteriores no brief', () => {
    const history = [msg('user', 'Melhore esse ponto.'), msg('assistant', 'Uma forma é...')];
    const briefText = chatBriefToText({
      message: 'Agora deixe mais natural.',
      history,
      isFirstMessage: false,
    });
    expect(briefText).toContain('Melhore esse ponto.');
    expect(briefText).toContain('Uma forma é...');
    expect(briefText).toContain('Agora deixe mais natural.');
    expect(briefText).toContain('Continuidade');
  });

  it('primeira mensagem não carrega histórico nem aviso de continuidade', () => {
    const briefText = chatBriefToText({
      message: 'Quero melhorar essa introdução.',
      history: [],
      isFirstMessage: true,
    });
    expect(briefText).not.toContain('CONVERSA ANTERIOR');
    expect(briefText).not.toContain('Continuidade');
    expect(briefText).toContain('Quero melhorar essa introdução.');
  });
});

describe('§40.13–14 erros humanos e offline', () => {
  it('erro de provider vira mensagem amigável (sem HTTP/stack)', () => {
    const friendly = friendlyChatError('network');
    expect(friendly).not.toMatch(/HTTP|503|ProviderError|AbortError|stack/i);
    expect(friendly).toContain('conexão');
  });

  it('códigos técnicos não aparecem para o usuário', () => {
    for (const code of ['timeout', 'authentication', 'rate_limit', 'unavailable', 'invalid_response']) {
      const friendly = friendlyChatError(code);
      expect(friendly.length).toBeGreaterThan(10);
      expect(friendly).not.toContain(code);
    }
  });

  it('offline define aviso sem fabricar resposta remota', () => {
    expect(OFFLINE_CHAT_NOTICE).toContain('offline');
    expect(OFFLINE_CHAT_NOTICE).toContain('recursos locais continuam funcionando');
  });
});

describe('§23 e §8 sugestões e quick actions', () => {
  it('quick actions usam a mesma lógica do chat livre (são mensagens)', () => {
    for (const qa of QUICK_ACTIONS) {
      expect(validateOutgoingMessage(qa.message).ok).toBe(true);
      expect(inferIntent(qa.message, true)).toBeDefined();
    }
  });

  it('sugestões de continuação são mensagens válidas', () => {
    for (const label of FOLLOW_UP_SUGGESTIONS) {
      expect(validateOutgoingMessage(`${label}.`).ok).toBe(true);
    }
  });
});

describe('§37 rótulo de contexto simples', () => {
  it('bloco ativo tem prioridade, sem ids técnicos', () => {
    expect(contextLabel('Ponto 1', 'Discurso X')).toBe('Contexto: Ponto 1');
    expect(contextLabel(undefined, 'Discurso X')).toBe('Contexto: Discurso X');
    expect(contextLabel('  ', undefined)).toBeNull();
  });
});
