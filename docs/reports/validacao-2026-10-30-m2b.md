# M2b — streaming com markdown e microinterações (2026-10-30)

**Status:** implementação em branch, **sem merge** (por decisão do produto).
A34 e streaming real continuam como gates antes de qualquer merge.

- Branch: `feat/m2b-streaming` (base `6a455c8`, `main` 2.8.2/27).
- Testes: **2.666 JVM / 0 falhas** (+9).
- `lintRelease`: **verde**.
- Sem bump/tag; release intocado.

## O que mudou

### Streaming com markdown

- `StreamingMarkdown` parseia o parcial em blocos a cada **~100 ms**
  (`STREAM_THROTTLE_MS`), não a cada token.
- Primeiro token entra imediatamente; updates seguintes são coalescidos.
- **Fases terminais** (DONE/CANCELLED/ERROR) usam `streamThrottleFor(phase) = 0`:
  o efeito cancela a espera pendente e congela o parcial no mesmo frame do
  stop/erro.
- O texto cresce no slot sem `animateContentSize` (sem animar altura por token).
- `<texto-do-card>` durante o streaming já renderiza o **shell** “Texto para o
  card” com o parcial dentro; as ações só aparecem no card final.
- Markdown parcial usa o mesmo `parseBlocks`/`RenderBlock` do renderizador
  final (títulos, listas, citações, negrito etc.).

### Cursor

- Cursor em slot fixo de 18 dp para o texto não reflowar quando ele some.
- Fade de 150 ms no DONE/CANCELLED/ERROR.
- Reduced motion: cursor estático, sem animação infinita.

### Enviar ↔ Parar

- Um único `FilledIconButton` com `AnimatedContent` (fade + scale 150 ms)
  entre Enviar e Parar.
- Cores de erro no estado “Parar”; `enabled` continua bloqueando envio vazio.
- Haptic leve (`KeyboardTap`) no envio e no stop.

### Haptics

- Enviar, parar, “Adicionar ao final”/“Usar este texto” e confirmar
  “Substituir”.
- Nada de haptic no streaming nem em reflow.

### TalkBack

- Anúncio por fase: “Pensando”, “Gerando”, “Interrompido”, “Resultado pronto”
  (`liveRegion = Polite`, 1 dp; nó observado no uiautomator com
  `focusable="false"`, sem foco separado).
- ERROR fica só com o `ChatErrorRow` (já Polite).
- CANCELLED: o status anuncia “Interrompido” e o `ChatErrorRow` é renderizado
  com `announce = false` — sem leitura duplicada.
- Pílula “N novas” com `contentDescription` “1 nova mensagem”/“N novas
  mensagens” (o texto visual não é lido duas vezes).

## Testes puros novos

`ChatStreamingTest` (9):
- markdown com título/lista/card;
- card aberto sem fechar ainda vira shell (1 bloco `TextForCard`, sem a tag);
- card aberto multilinha mantém o parcial dentro do shell;
- card fechado + texto restante separa card e parágrafo;
- texto vazio não renderiza;
- throttle = 100 ms em fases vivas e **0** em DONE/CANCELLED/ERROR;
- mapeamento das fases para TalkBack;
- singular/plural da pílula.

## Validação no tablet

- Build debug instalado no Xiaomi `25040RP0AL` (dados preservados).
- Turno local WAITING → DONE: sem crash; `content-desc` “Pensando” presente
  durante a espera e botão “Parar geração” no lugar de “Enviar”.
- Após DONE, botão volta a “Enviar” e a resposta local aparece.
- Sem `FATAL EXCEPTION`.
- Mensagens de teste (“oi” + resposta) removidas; #192 e o histórico anterior
  preservados.

## Limitações / gates

- **Sem chave de IA no tablet**, não houve STREAMING/REASONING real; o
  markdown incremental, o cursor e o card shell foram cobertos por testes
  puros e compilação, não por vídeo de tokens reais.
- **A34 não conectado** ao ADB; repetir o roteiro (incl. stop/retry) nele.
- Sem medição automatizada de fps; throttle reduz parse/recomposição, mas o
  “60 fps” ainda é inspeção visual.
- **Não mergear** antes de A34 + streaming real + stop/retry sem regressão.
