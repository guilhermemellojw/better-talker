# M2b — streaming com markdown e microinterações (2026-10-30)

**Status:** implementação em branch, **sem merge** (por decisão do produto).
Streaming/REASONING/stop/retry reais validados no tablet com chave; **A34
continua como gate** de layout/performance antes do merge.

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
- **Parcial cancelado** também usa `ChatMessageText`: o markdown/shell que
  estava no streaming não volta a mostrar `**` cru depois do stop.
  `cancelledPartialBlocks` filtra blocos vazios (só marcadores internos) e é o
  predicado usado pelo `PersistedCancelledItem`.

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

`ChatStreamingTest` (11):
- markdown com título/lista/card;
- card aberto sem fechar ainda vira shell (1 bloco `TextForCard`, sem a tag);
- card aberto multilinha mantém o parcial dentro do shell;
- card fechado + texto restante separa card e parágrafo;
- texto vazio não renderiza;
- throttle = 100 ms em fases vivas e **0** em DONE/CANCELLED/ERROR;
- CANCELLED com markdown → blocos de título/negrito/lista (não texto cru);
- CANCELLED só com marcadores internos → não renderiza;
- mapeamento das fases para TalkBack;
- singular/plural da pílula.

## Validação real no tablet (com chave)

Tablet Xiaomi `25040RP0AL`, Android 16/API 36, build debug; chave DeepSeek
“Online” na tela IA. Prompt em modo **Profundo**.

- **Turno `turn-dd72aa62…`** (introdução longa + raciocínio):
  - `WAITING` → `REASONING` → `STREAMING` → `DONE` no **mesmo turnId**;
  - “Pensou por 5s ▸” persistido no topo do item;
  - resposta longa com título, parágrafos e card-first;
  - “pensou 6,3s” sob a bolha; `final=msg-5f69…`.
- **Card/markdown** (`turn-2d1df902…`): card “Texto para o card” com título
  “Introdução”, subtítulo e lista numerada; ações (Adicionar/Substituir) só
  no final.
- **Stop real** (`turn-ae04bedf…`):
  - `phase=CANCELLED`, parcial de **2115 chars** persistido;
  - “Geração parada.” + Tentar novamente/Fechar;
  - screenshot mostra o parcial congelado (antes do ajuste, com `**` cru).
- **Retry** (`turn-fe493019…`): novo turno abaixo, turno cancelado anterior
  preservado; sem reativar o antigo.
- **TalkBack**: nó “Pensando” (`focusable="false"`), botão “Parar geração”
  durante a geração, “Geração parada” no stop; CANCELLED sem anúncio
  duplicado (`announce = false`).
- Sem `FATAL EXCEPTION`; vídeos/frames em `/tmp/opencode/m2b-*.mp4`.
- O tablet caiu do ADB durante a limpeza final. DB limpa já preparada em
  `/tmp/opencode/m2b-192-clean/better-talker.db`: 9 mensagens de teste da #192
  removidas, histórico anterior intacto, `integrity_check=ok`. Falta só
  push/restart quando o tablet reconectar.

## Limitações / gates

- **Merge aceito com tablet + streaming real + confirmação visual** do parcial
  cancelado com markdown. Falta apenas essa confirmação (tablet offline) e o
  push da DB limpa.
- **A34** fica como **gate da release final 3.0.0** (layout menor/performance);
  não bloqueia o merge do M2b, desde que registrado.
- Sem medição automatizada de fps; throttle reduz parse/recomposição, mas o
  “60 fps” ainda é inspeção visual.
- O frame de STREAMING é curto (2–4 s); o vídeo pegou o card final e o
  parcial congelado, não todos os frames de markdown incremental.
- **Não mergear** antes da confirmação visual + limpeza da #192.
