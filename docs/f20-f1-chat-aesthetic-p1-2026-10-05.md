# Refinamento Estético P1 (2026-10-05)

## Commits
- **T1 — AppTypography:** `5951765` — Build ✅ + Deploy ✅
- **T2 — identidade do assistente:** `2bc1b36` — Build ✅ + Deploy ✅
- **T3 — citação + links:** `98dcea1` — Build ✅ + Deploy ✅

## Testes JVM
- **1336 passed / 0 failed** (T1: +3; T3: +3).

## T1 — AppTypography custom
- `ui/theme/Type.kt` com `AppTypography` sobre a base Material 3 (Roboto do
  sistema — **nenhuma fonte baixada**): `bodyLarge` 26sp, `bodyMedium` 22sp,
  `titleLarge` 22sp SemiBold, `titleMedium` 16sp SemiBold, `titleSmall` 14sp.
- `MaterialTheme` passa `typography = AppTypography`; overrides locais do chat
  (lineHeights/pesos) **consolidados** na escala.
- **Device:** tipografia visível e consistente (light/dark pela mesma escala).

## T2 — Identidade do assistente
- Respostas de texto do Copilot ganham **avatar 24dp** (monograma "BT" em
  `primaryContainer`/CircleShape) à esquerda, com 8dp de espaçamento e
  alinhamento ao topo.
- Usuário **sem avatar**; "Resposta local" preservado; cards (📖/📝) mantêm
  identidade própria.
- Chip de origem: o badge **💡 Sugestão criativa** já é o chip de criação;
  **📖 por mensagem não existe** (a proveniência é global — "Fontes e apoio").
- **Device:** avatar "BT" visível nas respostas ✅.

## T3 — Citação com fundo + links explícitos
- Citação com fundo `surfaceVariant` 35% + barra em altura total: **já
  entregue nos quick wins** (T4 anterior) — confirmada.
- Links jw.org ganham `TextLinkStyles` explícito (cor `tertiary` +
  sublinhado); sem cor informada, mantém o estilo padrão do Compose.
- Cores expostas: `LinkBlueLight #1A6FEB` / `LinkBlueDark #8AB4F8`.
- **Contraste WCAG AA verificado por teste puro** (`contrastRatio`):
  - light: **4.56** (AA ≥ 4.5) ✅
  - dark: **8.09** (AAA) ✅
- **Device:** link jw.org azul + sublinhado; link externo sublinhado na cor
  padrão (inerte) ✅.

## Fora de escopo preservado
- Sem biblioteca/fonte externa; identidade amarela intacta; badge 💡,
  avisos ⚠️ e cards 📖 inalterados.

## Estado
- P1 estético: **fechado** (código + testes + device).
- CI: verde nos 3 commits (Build + Deploy).
