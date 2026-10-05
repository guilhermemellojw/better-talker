# Refinamento Estético do Chat — Quick Wins (2026-10-05)

## Commits
- **T1 — espaçamento:** `d7ed268` — Build ✅ + Deploy ✅
- **T2 — lineHeight/títulos:** `1d80bc6` — Build ✅ + Deploy ✅
- **T3 — cursor de streaming:** `7bc9302` — Build ✅ + Deploy ✅
- **T4 — componentes:** `c33fa75` — Build ✅ (Deploy cancelado por concorrência)
- **T5 — animação de entrada:** `f49c750` — Build ✅ + Deploy ✅ (topo)

## Testes JVM
- **1330 passed / 0 failed** (T4: +3).

## T1 — Espaçamento
- Mensagens 8→**14dp**; blocos da resposta 4→**8dp**; lateral 12→**16dp**.
- Ações (item próprio) com offset **-8dp** para permanecerem visualmente
  junto da mensagem (com 14dp de espaçamento do LazyColumn, o par ficaria
  solto) — ajuste de execução sobre o "+4dp" do handoff (reportado).
- **Device:** screenshot confirma ritmo vertical visivelmente melhor. ✅

## T2 — lineHeight + escala de títulos
- Corpo `bodyLarge` 24→**26sp** (quote/bullet/check/para); parcial
  `bodyMedium` **22sp**; `###` vira **titleMedium 16sp SemiBold**
  (antes titleSmall 14sp); `#`/`##` seguem titleLarge Bold.
- **Device:** espaçamento de linhas visivelmente mais confortável. ✅

## T3 — Cursor de streaming
- `▍` no fim do parcial com `animateFloat` (alpha 0.2↔1.0, 400ms cada
  sentido, `RepeatMode.Reverse`); parcial continua **texto puro** (sem parser).
- **Device:** não foi possível capturar visualmente nesta sessão — o parcial
  fica abaixo do fold (o comportamento de auto-scroll com IME, já registrado
  como observação) e a resposta do DeepSeek teve retry de orçamento
  (conteúdo começa tarde). Código compila e o caminho é o mesmo do parcial
  validado no T4 anterior.

## T4 — Componentes
- `---` → `MdBlock.Divider` (`HorizontalDivider` sutil) — antes virava texto
  literal.
- Código inline com fundo `surfaceVariant` (`SpanStyle.background`).
- Citação com fundo `surfaceVariant` 35% + barra em altura total.
- Marcadores de lista/check com `widthIn(min = 26dp)` (alinhados).
- **Divergência necessária:** `MarkdownPreview.kt` (mesmo componente
  compartilhado) recebeu o caso `Divider` para o `when` exaustivo compilar.
- +3 testes (divider vs `--`, fundo do código, default sem fundo).

## T5 — Animação de entrada
- Itens de mensagem/ações com `Modifier.animateItem` (fade 150ms + placement).
- **Device:** sem impacto perceptível (A34); a entrada é sutil.

## Fora de escopo preservado
- Sem biblioteca externa; identidade amarela intacta; badge 💡, avisos ⚠️ e
  cards 📖 inalterados.

## Estado
- Quick wins estéticos: **fechados** (código + testes + device; T3 com
  verificação visual pendente por limitação de captura).
- CI: verde em todos os commits (Build; um Deploy cancelado por concorrência).
