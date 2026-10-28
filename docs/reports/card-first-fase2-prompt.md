# Card-first fase 2 — prompt (2026-10-28)

- Branch: `feat/card-first-fase2-prompt` (base `23df2fd`).
- Commits: `01627fa` (regra única + telemetria) e `d3be364` (segundo bloco).
- Suíte: **2.602 testes / 0 falhas**. `lintRelease`: **verde**.
- CI: **verde** (runs `40663383795` e `40664021658`).
- A34 validado em 2026-10-28 (Samsung A34, Android 16/API 36).

## O que mudou

- `CARD_FIRST_RULE` é a regra única:
  - texto aplicável dentro de `<texto-do-card>`;
  - explicações/análise/conversa fora;
  - um card por resposta.
- A regra vive **uma única vez** em `CHAT_TAIL_INSTRUCTIONS`; a persona
  (`CHAT_PERSONA_FREE`) não a repete.
- Teste puro conta exatamente 1 ocorrência no system do chat.
- `SPEECH_MENTOR` (prompt de geração) também declara “um card por resposta”.
- `SPEECH_MENTOR_CHAT_HINT` não repete o marcador; continua orientando o
  “gera/escreve/entrega” e a prioridade do bloco atual.
- Se o modelo devolver um segundo `<texto-do-card>`, ele não vira um segundo
  card acionável: é renderizado como texto normal com o aviso
  “Um card por resposta — este trecho extra não foi incluído.”
- Telemetria `EMIT` no `post`: registra card emitido (turno, alvo, origem,
  status, chars), sem texto do usuário.

## A34

- Prompt de chat “me de uma sugestao de texto para este ponto”:
  - `route=chat`, `hasCard=true`;
  - um único `<texto-do-card>`;
  - alvo `SECTION:c987ef29...`, `cardStatus=AVAILABLE`;
  - telemetria `EMIT` com 3.191 chars de card;
  - UI mostrou o preview e as ações.
- Prompt “escreva um paragrafo...” ainda entra na rota **oratória** legada
  (`route=oratory`), que usa `ResponseFormat.EDIT_PROPOSAL`/ProposalUi e
  não o card. Isso é o escopo da fase 4 (aposentar a proposta de texto),
  não da fase 2. A fase 2 fecha o caminho de chat.
- Nenhum segundo bloco foi emitido na amostra; a demotion está coberta por
  código/teste.

## Pendente

- Fase 3 (medir cards emitidos/inseridos vs. propostas) depois do merge.
- Tablet/keystore seguem como gates da `v2.9.0`.
