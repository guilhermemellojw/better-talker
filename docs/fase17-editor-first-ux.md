# Fase 17 — Editor-First UX Reset

## Resumo

| Campo | Valor |
|---|---|
| Commit de baseline | `b564771` |
| Commit da F17 | *(por fazer)* |
| Estado final | `EDITOR_FIRST_UX_STABLE_WITH_BACKLOG` |
| Testes web | **231/231** (224 baseline + 7 novos) |
| Build web | **VERDE** (`npm run build`) |
| Lint web | 6 warnings, 0 erros — **todos pré-existentes** |
| E2E web | **32/32 ✓** (`tools/e2e-fase17.cjs`) |
| Testes Android | não executados nesta fase (sem alteração compartilhada) |

## 1. Visão do produto

> **O Better-Talker é primeiro um editor de discursos. O Copilot é uma
> ferramenta inteligente disponível sob demanda, acionada por um botão
> flutuante e contextual.**

A F15 melhorou o chat. A F16 criou contratos nativos de paridade. Mas a
validação de produto confirmou que o app estava chat-first: o Copilot abria
por padrão e dominava a primeira impressão.

## 2. Decisão editor-first

A hierarquia passa a ser:

```text
1. EDITOR
2. COPILOT
3. FERRAMENTAS AUXILIARES
```

Nunca `CHAT → EDITOR`.

## 3. Antes / depois

| Aspecto | Antes (F15/F16) | Depois (F17) |
|---|---|---|
| Copilot ao abrir o app | **Aberto por padrão** (`isCopilotOpen = true`) | **Fechado** (`isCopilotOpen = false`) |
| Primeira impressão | Painel do Copilot | **Editor** |
| Entrada do Copilot | Botão no header (toggle) | **Botão flutuante (FAB)** no editor + toggle no header |
| Desktop | Painel lateral fixo 380px, sempre visível | **Overlay** — fechado, editor ocupa a tela inteira |
| Mobile | Drawer deslizante | Drawer deslizante (mantido) |
| Contexto | `contextLabel(blockTitle, speechTitle)` | `contextLabel(blockTitle, speechTitle, selectedText)` — **seleção > bloco > discurso** |
| Editor sem Copilot | Comprimido pelo painel | **Tela inteira** |

## 4. Mudanças

### 4.1 `src/App.tsx`

- `isCopilotOpen` default `true` → **`false`**
- Novo estado `selectedText` para a seleção do editor
- `onSelectionChange={setSelectedText}` passado ao `BlockEditorTabs`
- `selectedText={selectedText}` passado ao `CopilotDrawer`
- Novo botão flutuante `.copilot-fab` renderizado quando `!isCopilotOpen`

### 4.2 `src/components/Editor/BlockEditor.tsx`

- Nova prop opcional `onSelectionChange?: (selectedText: string) => void`
- `saveCurrentSelection` agora expõe o texto selecionado (sem mudar o
  comportamento de salvar/restaurar `Range`)

### 4.3 `src/components/Copilot/CopilotDrawer.tsx`

- Nova prop opcional `selectedText?: string`
- Cabeçalho mostra `Contexto: trecho selecionado` quando há seleção,
  senão `Contexto: <bloco>` — via `contextLabel`
- Nada de ids técnicos (blockId/speechId/ContextPack/sourceType/scores)

### 4.4 `src/copilot/chatEngine.ts`

- `contextLabel` estendido com terceiro parâmetro `selectedText`,
  prioridade **seleção > bloco > discurso** (§11). Retrocompatível: as
  chamadas antigas de 2 argumentos continuam funcionando.

### 4.5 `src/styles/copilot.css`

- `.copilot-panel` agora é **overlay em todas as larguras**:
  `position: fixed; right: 0; top: 64px; bottom: 44px; z-index: 40;`
  `transform: translateX(100%)` fechado, `.open` → `translateX(0)`
- Removido o comportamento desktop que mantinha o painel sempre visível
- Novo `.copilot-fab`: `position: fixed; right: 1.25rem; bottom: 3.75rem;`
  `z-index: 35`, `border-radius: var(--radius-full)`, rótulo "Copilot",
  `:focus-visible` para teclado
- Em 390px: rótulo do FAB vira `visually-hidden` (só o ícone), posição
  ajustada para não cobrir texto

## 5. Botão flutuante

```text
fechado →  ● Copilot   (FAB visível, canto inferior direito)
aberto  →  FAB some    (o painel ocupa o lugar, sem duplicação de controle)
```

Características: sempre acessível, discreto, não bloqueia texto, não cobre
controles essenciais, acessível por teclado (`<button>` nativo), `aria-label`
"Abrir Copilot", `title` "Copilot — ajuda contextual".

## 6. Desktop

```text
┌───────────────────────────┬──────────────────────┐
│                           │ Copilot          ×  │
│       EDITOR              │ Contexto: Ponto 1   │
│                           │ resposta...         │
│                           │ Digite...       ↑   │
└───────────────────────────┴──────────────────────┘
```

O painel desliza por cima do editor (overlay). Fechado, o editor ocupa a
tela inteira — o `main-content` volta a ter só o `BlockEditorTabs`.

## 7. Mobile

Drawer deslizante pela direita (mantido da F15). O fluxo é:

```text
Editor → toca Copilot → painel aparece → interage → fecha
       → Editor exatamente onde estava
```

Em 390px confirmado: sem overflow horizontal, FAB dentro da tela, painel
abre/fecha, editor recuperável.

## 8. Contexto do Copilot

Prioridade (§11):

```text
seleção explícita  →  Contexto: trecho selecionado
bloco ativo        →  Contexto: Introdução
contexto do discurso → Contexto: Meu Discurso
nada               →  (sem rótulo, sem inventar)
```

A seleção real é capturada por `saveCurrentSelection` no editor
(`window.getSelection()`), que já existia para `restoreSelection` — a F17
apenas a expõe. Nada de seleção inventada.

## 9. O que foi preservado

### F15 (§16) — não removido
- `chatEngine` (`inferIntent`, `recentHistory`, `chatBriefToText`,
  `validateOutgoingMessage`, `QUICK_ACTIONS`, `friendlyChatError`)
- `llmPrompt` (`SYSTEM_PROMPT`, `buildLlmPrompt`, `serializePack`)
- Pipeline único (`sendChatMessage`) — quick actions passam por ele
- Erro amigável no fio da conversa, dispensável, nunca trava o composer
- Offline notice, proveniência, thread, continuidade, auto-scroll

### F14 (§42) — não regredido
- `SpeechMetricsBar` continua renderizado e visível
- Métricas, tempo, metas, WPM, persistência
- Confirmado no E2E: "F14 métricas/tempo visíveis (Copilot não esconde)"

### F13 (§43) — não regredido
- Autosave (400ms debounce), reload, múltiplos blocos, undo/redo, stale
- `EditHistory` intocado

### F16 (§45) — não regredido
- Contratos nativos Kotlin preservados: `ChatEngine`, `ChatPromptBuilder`,
  `ChatContext`, `ConnectivityObserver`
- Nenhum arquivo Android foi alterado nesta fase

## 10. Proposta / verificação / stale

- **Proposta** (F5): fluxo preservado. O painel abre a proposta sem perder o
  contexto do editor. `[Verificar]` `[Aceitar]` `[Rejeitar]` mantidos.
- **Aceite** (§18): `applyEditProposal` aplica atomicamente; `EditHistory.push`
  registra; undo disponível no header.
- **Rejeição** (§19): editor permanece intacto; Copilot continua disponível.
- **Stale** (§20): `validateEditProposal` bloqueia aplicação de proposta
  desatualizada ("stale_proposal").

## 11. CONTENT/TRAINING (§23)

Preservado integralmente. Nada mudou no prompt nem na lógica:

```text
factual → CONTENT
apresentação → TRAINING
```

Anti-atribuição mantida. A UI não pede ao usuário para escolher o trilho.

## 12. Testes

### Unitários web — 231/231

| Arquivo | Testes | Observação |
|---|---|---|
| `editorFirstUx.test.ts` (novo) | **7** | contexto (seleção > bloco > discurso, sem ids técnicos), quick actions preservadas |
| 21 arquivos existentes | 224 | baseline F15 intacto |

### E2E web — 32/32 (`tools/e2e-fase17.cjs`)

Fluxo coberto:

```text
app carrega
✓ editor presente na tela principal
✓ Copilot FECHADO por padrão (editor-first)
✓ botão flutuante Copilot visível
✓ FAB rotulado "Copilot"
✓ FAB acessível (aria-label)
✓ editor utilizável sem abrir Copilot
✓ F14 métricas/tempo visíveis
✓ editor aceita digitação direta
✓ painel Copilot abre pelo FAB
✓ FAB some quando o painel está aberto
✓ contexto do bloco aparece no painel
✓ nenhum id técnico exposto
✓ empty state presente / convida
✓ placeholder "Digite uma mensagem..."
✓ quick actions opcionais presentes
✓ quick action usa o mesmo pipeline
✓ mensagem natural + Enter (teclado real)
✓ resposta do Copilot aparece
✓ painel fecha / FAB volta / editor presente / conversa preservada
✓ painel reabre / thread continua / histórico preservado
✓ 390px: painel fecha, sem overflow, FAB dentro da tela
```

## 13. Limitações

- **Seleção como foco do prompt**: a seleção é capturada e exibida no rótulo,
  mas o `sendChatMessage` ainda usa `activeBlock.plainText` como texto do
  bloco. Enviar o trecho selecionado como foco do prompt exigiria mudar o
  pipeline — **registrado no backlog** para não expandir escopo nesta fase.
- **Android**: a F16 criou os contratos, mas o runtime completo não existe
  (`LlmModelConfig.DOWNLOAD_URL`/`SHA256` vazios). O port nativo do
  editor-first continuará como fase posterior.
- **E2E Android**: não executado nesta fase (sem alteração compartilhada e
  sem dispositivo/emulador executável).

## 14. Backlog

- enviar o trecho selecionado como foco do prompt (não só o rótulo)
- port nativo completo do Copilot (Android)
- streaming real, memória entre sessões, diff palavra-a-palavra, DOCX 2.0,
  semântica, WPM avançado
- testes de UI com testing-library (não disponível no projeto)

## 15. Decisão final

**`EDITOR_FIRST_UX_STABLE_WITH_BACKLOG`**

A experiência editor-first foi implementada e validada: o editor é a tela
principal, o Copilot é um botão flutuante contextual, o painel é um overlay
que não remove o editor da tela, e o contexto automático prioriza
seleção > bloco > discurso. F14, F13, F15 e F16 preservados.

Registra-se como backlog enviar o trecho selecionado como foco do prompt —
a mudança de UX não reduziu o contexto do motor, mas o pipeline de envio
ainda usa o bloco inteiro.

---

## Apêndice: números reais para o relatório final

1. **Commit inicial:** `b564771`
2. **Commit final:** *(por fazer)*
3. **Mudanças paralelas:** nenhuma — working tree limpo no baseline
4. **Auditoria:** `isCopilotOpen = true` era o problema central; `.copilot-panel` já era side panel/drawer; `FloatingFormatToolbar` já existia (padrão de botão flutuante); seleção já era capturada no editor
5. **Editor:** `BlockEditorTabs` — `contentEditable`, blocos, tabs, título, meta, undo/redo; intocado funcionalmente
6. **Botão flutuante:** `.copilot-fab` novo, canto inferior direito, `aria-label`, `title`, some quando o painel abre
7. **Desktop:** overlay deslizante (antes: painel fixo sempre visível)
8. **Mobile:** drawer deslizante mantido; 390px sem overflow, FAB dentro da tela
9. **Contexto:** `contextLabel(blockTitle, speechTitle, selectedText)` com prioridade seleção > bloco > discurso
10. **Seleção:** `saveCurrentSelection` expõe `window.getSelection().toString()`; rótulo "Contexto: trecho selecionado"
11. **Quick actions:** 4 da F15 preservadas, mesmo pipeline
12. **Chat:** thread, continuidade, contexto, erros, offline, proveniência, proposta — tudo preservado; mudou só onde vive
13. **Propostas:** F5 preservada (`applyEditProposal`, aceitar/rejeitar)
14. **Verification:** F6 preservada (4 estados, `SupportStatus`)
15. **Stale:** F5 preservada (`validateEditProposal` → `stale_proposal`)
16. **Proveniência:** "Fontes e apoio ▸" preservada dentro do painel
17. **CONTENT/TRAINING:** F7/F8/F15 preservada, automático, anti-atribuição
18. **F14:** preservado (métricas, tempo, metas, WPM, persistência)
19. **F13:** preservado (autosave, reload, blocos, undo/redo, stale, offline)
20. **F15:** preservado (thread, continuidade, intent, contexto, quick actions, erro, proveniência)
21. **F16:** preservado (contratos Kotlin intactos, nenhum arquivo Android alterado)
22. **Testes novos:** **7** (231 total)
23. **Regressão:** **231/231** ✓
24. **Build:** **VERDE** (`npm run build`)
25. **Lint:** 6 warnings, 0 erros — todos pré-existentes
26. **E2E:** **32/32 ✓** (`tools/e2e-fase17.cjs`)
27. **Descoberta humana:** FAB rotulado "Copilot" + `title` "ajuda contextual"; retorno ao editor imediato (painel fecha, FAB volta)
28. **Bugs encontrados:** 2 (seletor do botão fechar sem texto; ordem do teste 390px com painel aberto)
29. **Bugs corrigidos:** 2
30. **Limitações:** seleção no rótulo mas não no prompt do pipeline; Android sem runtime
31. **Backlog:** trecho selecionado como foco do prompt; port nativo; streaming, memória, diff, DOCX 2.0, semântica, WPM avançado
32. **Decisão final:** `EDITOR_FIRST_UX_STABLE_WITH_BACKLOG`
