# M2a — motion do chat, implementação real (2026-10-30)

**Status:** núcleo M2a implementado no `ChatScreen` real. Validado no tablet;
A34 pendente (aparelho não conectado ao ADB). Sem bump/tag.

- Branch: `feat/m2a-motion` (base `9de70e5`, `main` 2.8.2/27, já com C2.5).
- Testes: **2.657 JVM / 0 falhas** (+5).
- `lintRelease`: **verde**.
- Tablet: Xiaomi `25040RP0AL`, Android 16/API 36, debug com dados preservados.

## O que mudou

### 4.1 Handoff do turno

- `TurnLiveItem` agora usa `AnimatedContent` com `contentKey` por grupo visual
  (`WAITING/REASONING`, `STREAMING`, `DONE`, `CANCELLED/ERROR`).
- Crossfade de **180 ms** entre grupos; `EnterTransition.None`/`ExitTransition.None`
  com escala de animação 0.
- Removido `animateContentSize` do container do turno: o texto do streaming
  cresce sem animar altura por token.
- Parcial → final: o item `turn-<turnId>` troca para o synthetic persistido com
  crossfade no mesmo slot; a key continua estável.
- Cabeçalho de raciocínio fica fora do `AnimatedContent` (estável).

### 4.2 Raciocínio

- `ReasoningSection` com `animateContentSize(200 ms)` só no bloco.
- Cabeçalho “Pensou por Xs ▸/▾” com affordance; continua depois de
  DONE/CANCELLED/ERROR.
- Pulso no ícone; texto sempre legível; `TalkBack` mantém
  “Expandir/Recolher raciocínio”.
- Estado inicial colapsado; segundos persistidos no `ChatItem`.

### 4.3 Cards

- Cards top-level (`ProposalCard`, `StructureProposalCard`, `MultiProposalCard`,
  `NoteStructureProposalCard`, `ScopeSuggestionCard`) usam `animateItem` com
  fade 180 ms e key estável.
- Card-first aninhado usa `AnimatedVisibility` (fade + expand/shrink) dentro do
  turno; nunca `animateItem`.
- Verificação e avisos do card usam o mesmo padrão, guardando o último valor
  para o exit não ficar em branco.

### 4.4 Erro único

- `shouldShowStandaloneError(activeTurn, runState)` puro, em produção, com
  testes: com turno ativo o erro é só inline; sem turno, Error/Cancelled usam
  o row avulso; nunca os dois.
- `ChatErrorRow` continua `liveRegion = Polite` uma única vez.
- CANCELLED mantém o parcial congelado acima de “Geração parada.”.

### 4.5 Avisos e verificação

- “Continuar gerando” entra/sai com `AnimatedVisibility` (fade + expand/shrink).
- Avisos do verifier e resumo da verificação abaixo do texto, dentro do turno.
- Nada disso entra no markdown copiado/inserido.

### 4.6 Escopo

- `ScopeChip` e índice `N/M` com `AnimatedContent` (crossfade 180 ms).
- Skeleton e estado vazio no mesmo slot, com crossfade.
- `followEnd` preservado na troca.

### 4.7 Auto-scroll

- Novo `turnAnchorIndex(...)` puro: com turno visível ancora no item
  `turn-<turnId>`, não no último card/proposta.
- Primeiro frame do turno rola ao **início**; updates seguintes seguem o fim do
  texto (se `followEnd`).
- Pílula “N novas ↓” com fade/slide; guarda o último número durante o exit.
- IME e `followEnd` preservados.

### Reduced motion

- `rememberMotionScale()` lê `ANIMATOR_DURATION_SCALE`; `motionMillis(base, 0)`
  devolve 0.
- Todas as animações novas (turno, cards, raciocínio, pílula, escopo) usam a
  escala.
- Typing/reasoning/cursor ficam estáticos com escala 0.

## Validação no tablet

- Turno real WAITING → DONE no mesmo item, com resposta local
  (“Resposta local: …”) e sem salto/duplicação; capturado em vídeo
  (`/tmp/opencode/m2a4b.mp4`).
- Turno com rota ORATORY (`Desenvolva a introdução…`): a UI manteve o item
  único, exibiu o status e terminou com “Configure a chave de IA…” — o tablet
  **não tem chave de IA configurada**, então não houve STREAMING/REASONING real.
- Chip de escopo alternou Tópico ↔ Discurso inteiro; índice `1/6` e `2/3`
  renderizaram.
- Reduced motion: `animator_duration_scale=0` aplicado e chip/estado
  renderizaram instantaneamente, sem crash; escala restaurada para 1.
- Sem `FATAL EXCEPTION`; #192 preservada e as duas mensagens de teste do chat
  removidas (histórico anterior intacto).

## Limitações / pendências

- **Sem chave de IA no tablet**, o STREAMING/REASONING real não pôde ser
  revalidado em produção; o protótipo `proto/m2a-motion` continua sendo a
  referência visual dessas fases.
- A34 não conectado ao ADB nesta sessão; repetir o roteiro antes do release.
- Sem medição automatizada de fps/jank; inspeção visual + vídeo.
- O handoff parcial → final depende da janela em que o synthetic aparece antes
  da linha do Room; a key é a mesma, sem texto duplicado.
