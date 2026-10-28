# Validação A34 — card-first fase 1 (2026-10-27)

- Build: `cf12` (debug assinado do worktree `feat/card-first-fase1`, base `6039e40`).
- Aparelho: Samsung Galaxy A34 (SM-A346M), Android 16 / API 36.
- App: 2.8.2 (sem bump; a tag `v2.9.0` continua bloqueada pelo tablet + keystore).
- Suíte: **2.601 testes / 0 falhas**.
- `lintRelease`: **verde** (Java 17, `-XX:-TieredCompilation`, `-Xmx1800m`).

## Fluxos validados

### Alvo capturado na geração

- Card gerado em `msg-f92885b9-72d0-4322-adec-5c0fecde33ca`.
- Payload persistido com `cardSchema=1`, `cardTargetKind=SECTION`,
  `cardTargetId=c987ef29-4995-487a-98ee-c0ecc8d8a9e0`,
  `cardCapturedFrom=PUSH`, `cardCapturedAt`, `cardTurnId`,
  `cardOrigin=CHAT`, `cardSummary` (≤160), `cardGateMessageId` e
  `cardStatus=AVAILABLE`.
- O alvo/hash foram capturados antes da resposta; o clique não escolhe alvo.

### UI

- O preview do `<texto-do-card>` nasce recolhido (texto longo) e o rodapé
  de ações fica imediatamente abaixo do preview, antes da conversa.
- Ações primárias visíveis: `Usar este texto`/`Adicionar ao final` e
  `Substituir`; `Verificar`, `Copiar`, `Desfazer`, `Editar texto` e
  `Dispensar` ficam no menu `⋯`.
- O rodapé não fica atrás do bottomBar; o auto-scroll ancora no item do
  turno e o preview recolhido mantém as ações alcançáveis.

### Append / replace / undo

- Alvo vazio (0 chars) → **Adicionar ao final** → `APPLIED`, conteúdo
  `3989` chars, snackbar “Texto inserido” com ação “Desfazer”.
- **Desfazer** restaurou `0` chars e `AVAILABLE`, sem mensagem nova no chat.
- **Substituir** abriu confirmação explícita; cancelar não altera; confirmar
  gravou o texto e `APPLIED`.
- Após fechar/reabrir o app, `APPLIED` persistiu e o menu mostrou
  “Desfazer (sessão encerrada)”, sem snapshot em memória.

### Edição e verificação

- `Editar texto` trocou somente o bloco `<texto-do-card>`, preservando a
  conversa; o append seguinte inseriu o texto editado (marcador
  `TESTE_EDIT` no `contentHtml`).
- `Verificar` rodou warn-only: `✓ 1 · ⚠ 17 · ? 0 · 💡 2 (avaliação local)`,
  com os avisos dentro do card. Nenhum texto foi removido e o insert
  continuou permitido.

### Gate “Sim”

- Com o card como última fala, o envio de “Sim” executou **append**:
  `3.989 → 7.981` chars, `cardStatus=APPLIED`, log
  `action=GATE_YES|gate=true|stale=false|undo=true`.
- Nenhuma resposta de LLM foi gerada; o turno terminou `DONE final=-`.
- O gate usa `cardGateMessageId` e o escopo atual; cards antigos não aceitam.

### Stale

- Depois de editar a seção no editor, o card passou a `STALE` na hora
  (a UI observa as seções/nota, não só as mensagens): “O texto mudou”,
  aviso “Você pode adicionar ao final, sem substituir”.
- `Substituir` ficou indisponível.
- **Adicionar ao final** em stale funcionou com aviso:
  `5.922 → 9.925` chars, log `action=APPEND|stale=true`.
- Depois do undo do append stale, o card continua `STALE` (o `baseHash`
  é o da geração; o conteúdo restaurado não é o original). O append
  continua permitido, como manda §5.3.

### Múltiplos cards / SUPERSEDED

- Dois cards novos no mesmo alvo (mesmo `baseHash`):
  - o mais antigo mostrou **“Sugestão anterior”** e manteve
    `Adicionar ao final`/`Substituir` (hash confere);
  - o mais recente mostrou **“Pronto para inserir”**.
- Cards de alvos diferentes não se supersedem (teste puro).

### Transformar seleção em card

- Ação no menu da toolbar de formatação do editor, habilitada apenas com
  seleção não vazia (ícone de lápis).
- Clicar criou o card e abriu o chat **no escopo da seção**, com
  `cardOrigin=TRANSFORM`, `cardCapturedFrom=TRANSFORM`,
  `cardTargetKind=SECTION`, `cardTargetId=c987ef29-...`,
  `cardStatus=AVAILABLE`.
- Exemplos: seleção “perfeito” → `msg-3199bcd5...`; seleção “Esse” →
  `msg-59dd7977...`. O `contentHtml` da nota não mudou até o insert.
- O card antigo virou “Sugestão anterior” e o novo ficou “Pronto para
  inserir”; ambos com revisão/edição/insert.

### M1.1 / histórico / skeleton

- `CANCELLED` persistido e marcado como “Copilot (interrompido)” no
  histórico (já validado no M1.1; coberto por teste).
- Skeleton de escopo vazio continua no chat durante a troca de escopo.

## Não exercitado no A34

- `NO_TARGET`/`USER_PICK` (o alvo sempre foi inequívoco nesta rodada);
- `TARGET_MISSING`, `FAILED` e alvo `NOTE`; cobertos por
  `CardFirstTest`/revisão de código.
- Tablet de referência: ADB instável; validação pendente (gate da v2.9.0).

## Observações

- Telemetria do card ficou em metadados (`CopilotCard`, sem texto do
  usuário): `action`, `status`, `target`, `source`, `stale`, `undo`,
  `gate`, `chars`.
- A suíte e o lint foram rodados no worktree do card-first; nenhuma tag
  foi criada e `v2.8.2` continua draft.
