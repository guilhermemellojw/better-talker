# Spec — M2: motion e polimento do chat

Status: recorte aprovado para spec/protótipo. **Nenhuma implementação de M2
nesta branch.** Base: `main` em `084eba3` (M1/M1.1 + card-first 1–4).
Implementação só depois da tag `v2.9.0/28` e dos merges de Treino/Apresentar.
Versão final de M2 ainda a definir (`v2.12` ou `v3.0`).

## 1. Objetivo

M2 é a camada de polimento do chat depois do M1. Não altera IA, rotas,
card-first, Treino ou Apresentar. O objetivo é eliminar os “sumiços” restantes
e deixar as transições fluidas:

- typing → reasoning → streaming → final no **mesmo turno**, sem salto de
  altura e sem texto duplicado;
- raciocínio que colapsa/expande e permanece;
- cards e erros com entrada/saída animadas;
- avisos que não reflowam a resposta;
- troca de escopo com skeleton/crossfade;
- auto-scroll que não puxa quem está lendo o histórico.

## 2. Princípios

- **Não mudar a state machine do M1.** `TurnPhase`
  (`WAITING/REASONING/STREAMING/DONE/CANCELLED/ERROR`) e `TurnUiState`
  continuam a fonte da verdade.
- **Um item por turno.** A key `turn-<turnId>` continua sendo a âncora; o
  parcial, o raciocínio, o erro e o final são conteúdo do mesmo item.
- **Motion percebido, não decorativo.** 150–250 ms; nada acima de 300 ms no
  chat.
- **Sem animação por token.** O texto que cresce continuamente não pode
  animar altura a cada emissão.
- **Reduced motion:** escala de animação do sistema 0 = tudo instantâneo.
- **Nenhuma chamada nova ao LLM**, nenhuma mudança de payload, gate ou
  card-first.

## 3. Baseline atual (M1) e lacunas

Já existe em `ChatScreen.kt` / `CopilotViewModel.kt`:

- item único `turn-<turnId>`, com handoff do final persistido;
- `TurnLiveItem` com `animateContentSize(250)` no container;
- `ReasoningSection` com pulso durante REASONING, colapso por toque e
  “Pensou por Xs” persistido;
- `PersistedCancelledItem` com parcial congelado + “Geração parada”;
- `ChatErrorRow` com “Tentar novamente”/“Fechar”;
- `ScopeSkeleton` quando `scopeLoading`;
- `followEnd`, `unseenCount` e pílula “N novas”;
- `animateItem(fadeIn/outSpec = 300ms)` em mensagens;
- `STREAM_CROSSFADE_MS = 300`.

Lacunas que M2 ataca:

- `animateContentSize(250)` no container do turno anima também o texto que
  cresce no streaming (risco de jank);
- não há crossfade explícito entre fases dentro do item;
- raciocínio persistido não expande (texto é transitório, mas o cabeçalho
  pode ter affordance consistente);
- erro pode aparecer como `ChatErrorRow` dentro do turno **e** no fallback
  `runState` (duplicação em fluxos legados);
- cards entram/saem sem spec de item (só fade de 300 ms nas mensagens);
- escopo: skeleton sim, mas chip e índice `N/M` sem crossfade;
- auto-scroll usa `lastItemIndex` (pode ser proposta/card), não âncora de
  turno; a pílula existe mas sem animação de entrada/saída;
- durações de 300 ms acima do alvo de 150–250 ms.

## 4. M2a — núcleo

### 4.1 Handoff do turno

- WAITING → REASONING → STREAMING → DONE no mesmo item (`turn-<turnId>`).
- Crossfade entre os corpos de fase com **180 ms** (faixa 150–220 ms).
- Sem salto de altura: o slot do turno reserva a altura do conteúdo atual;
  o crossfade usa `AnimatedContent`/`Crossfade` com `SizeTransform` sem
  animar o texto que cresce.
- Sem texto duplicado: ao chegar o final persistido, o item sintético usa a
  mesma key e o mesmo slot; o parcial anterior sai no mesmo frame.
- Parcial → markdown final com crossfade no mesmo slot; o cursor desaparece
  por fade, não por remoção seca.
- O cabeçalho “Pensou por Xs” permanece no topo do item durante todas as
  fases; não é recriado a cada transição.
- `DONE` com `finalMessageId` nulo (fallback) não pode piscar uma bolha vazia.

Critérios técnicos:

- key estável do item: `turn-<turnId>`;
- `animateItem` do LazyColumn só no item do turno, não em cada texto;
- `AnimatedContent` com `contentKey = { it.phase.visualGroup() }` (WAITING e
  REASONING podem compartilhar o mesmo corpo de status);
- `LocalMotionDurationScale` aplicado a todas as durações.

### 4.2 Raciocínio

- Linha **“Pensou por Xs ▸”** (ou “Pensando…” durante REASONING) com ícone e
  affordance de expandir.
- Expandir/colapsar com `animateContentSize(200 ms)` **somente no bloco de
  raciocínio**, nunca no texto do streaming.
- Não desaparece sozinho: o cabeçalho permanece depois de DONE/CANCELLED/
  ERROR.
- Segundos persistem (`reasoningSeconds` no `ChatItem`); o texto do
  raciocínio continua transitório e pode sumir depois de persistido.
- Estado inicial: colapsado. Toque no cabeçalho alterna; `TalkBack` anuncia
  “Expandir raciocínio”/“Recolher raciocínio”.
- Durante REASONING, o pulso fica no ícone (texto sempre legível, contraste
  já corrigido no M1).
- O header não deve entrar/sair com o partial; ele é estável.

### 4.3 Cards

**Dois tipos de card, duas técnicas diferentes:**

- **Top-level do LazyColumn** — `ProposalCard`/`ProposalUi` (edição
  cirúrgica), `StructureProposalCard`, `NoteStructureProposalCard`,
  `MultiProposalCard`, `ScopeSuggestionCard`/fontes do ponto. São itens
  próprios da lista (`item(key = ...)`) e usam **`animateItem`**.
- **Aninhados no turno** — o card-first de `<texto-do-card>` (principal),
  fontes/verificação dentro de `turn-<turnId>` e o erro/bloco de status.
  Não são itens do LazyColumn; usam **`AnimatedVisibility`/
  `AnimatedContent`** no slot do turno. `animateItem` não se aplica a filho.

Aplicar `animateItem` com key estável aos top-level:

- `ProposalCard` (proposta de texto/ProposalUi);
- `StructureProposalCard`;
- `NoteStructureProposalCard`;
- `MultiProposalCard`;
- `ScopeSuggestionCard`/proveniência quando renderizados como item.

Spec de movimento (top-level):

- entrada: fade + deslocamento de **8 dp** para cima, 150–200 ms;
- saída: fade + shrink (scale 0.98) 150 ms;
- substituição de card no mesmo slot: crossfade;
- “Sugestão anterior” (card superseded/antigo) com peso visual menor
  (alpha/surface reduzidos), sem animação de destaque;
- sem animar a altura do preview de texto que está sendo digitado.

Spec de movimento (aninhado no turno):

- card-first entra/sai com `AnimatedVisibility` (fade + expand/shrink
  150–200 ms) dentro do turno; a key do turno não muda;
- fontes/verificação seguem o mesmo padrão;
- troca de variante do card (ex.: parcial → aplicado) com crossfade no
  mesmo slot;
- `animateItem` nunca é usado no card-first.

### 4.4 Erro único

- Um único bloco animado **dentro do turno**, com:
  - “Não consegui responder”;
  - motivo humano já sanitizado;
  - **Tentar novamente** e **Fechar**.
- Regra de fallback sem turno ativo:
  - `activeTurn.phase == ERROR` → erro **somente** dentro do turno; o
    `ChatErrorRow` do `runState` fica escondido;
  - `activeTurn == null` e `runState` em `Error`/`Cancelled` (fluxos
    legados, erro antes de criar o turno) → `ChatErrorRow` normal;
  - **nunca os dois ao mesmo tempo**; extrair
    `shouldShowStandaloneError(activeTurn, runState)` puro/testável.
- O bloco entra com `AnimatedVisibility` fade + expand 150–200 ms; o texto
  anterior/parcial fica congelado acima, sem sumir.
- `CANCELLED`: mesmo bloco, mensagem “Geração parada.”, parcial congelado e
  retry; o estado persistido mantém o mesmo visual.
- `liveRegion = Polite` uma única vez; não anunciar o mesmo erro duas vezes.

### 4.5 Avisos e verificação

- Aviso do verifier, dica de escopo e “Continuar gerando” aparecem como
  **status abaixo do texto**, dentro do turno, nunca anexados ao markdown
  final.
- `AnimatedVisibility` fade + expand 150–200 ms; altura reservada para não
  reflowar a resposta.
- “Continuar gerando” mantém a ação e o texto; não vira bolha separada.
- Avisos não entram no texto copiado/inserido.
- Em reduced motion, aparecem instantaneamente.

### 4.6 Escopo

- Skeleton no carregamento (`scopeLoading`), sem piscar lista vazia.
- Crossfade de **180 ms** entre escopos (Tópico ↔ Discurso inteiro).
- Chip de escopo (`ScopeChip`) e índice `N/M` com `AnimatedContent` (fade/
  slide curto) quando o escopo/seção muda.
- O skeleton ocupa o mesmo slot do estado vazio; não cria item extra que
  depois desaparece.
- A troca de escopo não deve reposicionar a lista para o topo se o usuário
  estava no fim; preservar `followEnd`.

### 4.7 Auto-scroll

- `followEnd` só permanece verdadeiro se o usuário está no fim; arrastar para
  cima desliga; voltar ao fim religa.
- Âncora no **início ou fim do turno** (`turn-<turnId>`), não no “último
  item” genérico (proposta/card não deve ser o alvo).
- Enquanto o usuário lê o histórico, novas mensagens não puxam a lista;
  acumulam a pílula **“N novas ↓”**.
- Pílula com fade/slide 150–200 ms; clique rola até a âncora do turno novo.
- Ao enviar uma mensagem, rolar para o início do turno novo; durante o
  streaming, se `followEnd`, acompanhar o **fim do texto** sem saltos.
- IME: se `followEnd`, manter o fim visível quando o teclado abre/fecha.
- Não usar `lastItemIndex` como única âncora; extrair função pura
  `scrollAnchorFor(...)` testável.

## 5. M2b — polimento

### 5.1 Streaming com formatação

- Markdown incremental com throttle (~100 ms) **ou** crossfade de blocos;
  decisão: crossfade primeiro, incremental depois.
- Cursor com fade out no DONE/CANCELLED/ERROR.
- Shell do `<texto-do-card>` durante o streaming, com o parcial dentro; o
  card não “nasce pronto” no fim.
- Não animar altura a cada token; o texto cresce no slot sem
  `animateContentSize`.

### 5.2 Microinterações

- Enviar ↔ Parar com morph de ícone 150 ms.
- Haptics leves somente em: enviar, parar, inserir card e aceitar card.
- “Continuar gerando” com fade/expand.
- Pílula “N novas” com slide.

### 5.3 Acessibilidade e performance

- Respeitar escala de animação do sistema (0 = instantâneo).
- Não animar altura do texto que cresce.
- 60 fps; sem jank em A34 e tablet.
- TalkBack: “Pensando”, “Gerando”, “Interrompido”, “Resultado pronto”,
  “N novas mensagens”.
- Nenhum texto de erro/verifier repetido para o leitor de tela.

## 6. Fora de escopo

- mudar state machine/rotas/IA/card-first;
- Treino, Apresentar, 45 min, voz/STT, sync;
- novas chamadas ao LLM;
- animação por token;
- reescrever o renderizador markdown.

## 6.1 Protótipo M2a — definição de pronto

Protótipo isolado, com **estado fake** (sem LLM, rotas, card-first ou
persistência). Deve provar:

1. WAITING → REASONING → STREAMING → DONE no mesmo `turn-<turnId>`;
2. sem salto de altura e sem texto duplicado;
3. stop/retry mantendo o parcial;
4. raciocínio colapsa/expande e permanece;
5. card entra/sai animado (top-level com `animateItem`; aninhado com
   `AnimatedVisibility`);
6. erro aparece uma vez (inline com turno; fallback sem turno);
7. aviso/“Continuar gerando” não reflowa o texto;
8. troca de escopo com skeleton/crossfade;
9. pílula “N novas” e âncora de scroll;
10. reduced motion instantâneo.

Validação: A34 + tablet, screenshots/vídeo, sem merge. Nada de LLM, rotas ou
card-first no protótipo.

Ordem interna do M2a:

1. **M2a.1** — handoff, raciocínio, erro, reduced motion;
2. **M2a.2** — cards, avisos, escopo, auto-scroll;
3. **M2b** — markdown/card shell, microinterações, haptics, TalkBack.

## 7. Decisões fechadas

1. Markdown no streaming: **crossfade primeiro**, incremental depois.
2. Card shell durante streaming: **M2b**, não M2a.
3. Durações: 150–250 ms; máximo 300 ms em qualquer animação do chat.
4. Haptics: leves, só em ações.
5. Testes: sem infra Compose instrumentada; validação manual + screenshots;
   extrair redutores puros (`scrollAnchorFor`, `phaseVisualGroup`,
   `motionDuration`, `shouldShowUnseen`) para testes JVM.

## 8. Critérios de aceite

M2a:

- typing → streaming → final sem duplicação e sem salto de layout;
- stop congela e mantém o parcial; retry não apaga o anterior;
- raciocínio colapsa/expande e permanece;
- cards entram/saem animados sem jank;
- erro aparece uma vez: inline com turno ativo, fallback `ChatErrorRow` sem
  turno, nunca ambos;
- avisos/“Continuar gerando” não reflowam o texto;
- troca de escopo mostra skeleton/crossfade e não pisca vazio;
- auto-scroll não puxa quem está lendo; pílula “N novas” funciona;
- reduced motion funciona;
- A34 + tablet validados; suíte + lintRelease verdes.

M2b:

- shell do card durante streaming;
- markdown incremental/throttle sem jank;
- cursor com fade;
- morph Enviar/Parar e haptics;
- TalkBack dos estados;
- 60 fps.

## 9. Implicações de código

- `android/app/src/main/java/com/bettertalker/app/ui/copilot/ChatScreen.kt`
  - `TurnLiveItem`, `ReasoningSection`, `TurnReasoningHeader`,
    `PersistedReasoningHeader`, `StreamingCursorText`;
  - `ScopeChip`, `ScopeSkeleton`, `unseenCount`, `messagesEffect`,
    `chatLastItemIndex`, `followEnd`.
- `android/app/src/main/java/com/bettertalker/app/ui/copilot/ChatCards.kt`
  - `ProposalCard`, `StructureProposalCard`, `NoteStructureProposalCard`,
    `MultiProposalCard`, `ScopeSuggestionCard`.
- `android/app/src/main/java/com/bettertalker/app/ui/copilot/ChatPrompt.kt`
  - `ChatErrorRow` (reuso no turno).
- `android/app/src/main/java/com/bettertalker/app/data/copilot/TurnUiState.kt`
  - `TurnPhase`/`TurnUiState` (sem mudar a state machine).
- Testes puros novos em `app/src/test/.../ui/copilot/` para âncoras,
  agrupamento visual de fases, duração com reduced motion e pílula.
- Sem dependências novas; usar Compose Animation/LazyColumn existentes e
  `LocalMotionDurationScale`/`LocalHapticFeedback`.

## 10. Sequência

- Base: `main` `084eba3` (M1/M1.1 + card-first 1–4).
- Implementação **depois** da tag `v2.9.0/28` e dos merges de Treino
  (`v2.10.0`) e Apresentar (`v2.11.0`).
- M2a primeiro; M2b depois.
- Branch isolada; nada de merge antes da v2.9.0.
- Versão de M2 (`v2.12` ou `v3.0`) a definir antes do merge.
