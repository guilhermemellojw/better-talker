# Card-first fase 2 — prompt (2026-10-28)

- Branch: `feat/card-first-fase2-prompt` (base `23df2fd`, sem merge).
- Commits: `01627fa` (regra única + telemetria) e `d3be364` (segundo bloco).
- Suíte: **2.602 testes / 0 falhas**. `lintRelease`: **verde**.
- CI: **verde** (runs `40663383795` e `40664021658`).

## O que mudou

- `CARD_FIRST_RULE` é a regra única:
  - texto aplicável dentro de `<texto-do-card>`;
  - explicações/análise/conversa fora;
  - um card por resposta.
- A regra vive **uma única vez** em `CHAT_TAIL_INSTRUCTIONS`; a persona
  (`CHAT_PERSONA_FREE`) não a repete. Teste puro conta exatamente 1
  ocorrência no system do chat.
- `SPEECH_MENTOR` (prompt de geração) também declara “um card por resposta”,
  sem usar a cauda do chat.
- `SPEECH_MENTOR_CHAT_HINT` deixou de repetir o marcador; continua
  orientando o “gera/escreve/entrega” e a prioridade do bloco atual.
- Se o modelo devolver um segundo `<texto-do-card>`, ele não vira um segundo
  card acionável: é renderizado como texto normal com o aviso
  “Um card por resposta — este trecho extra não foi incluído.”
- Telemetria `EMIT` no `post`: registra card emitido (turno, alvo, origem,
  status, chars), sem texto do usuário.

## Pendente

- Smoke no A34 para confirmar que a nova regra efetivamente emite **um**
  card por resposta e mantém a conversa fora; o A34 ficou offline nesta
  rodada. Sem esse smoke, a branch não foi mergeada.
- Fase 3 (medir cards emitidos/inseridos vs. propostas) fica depois do
  merge da fase 2.
