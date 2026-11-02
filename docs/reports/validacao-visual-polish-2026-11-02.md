# Validação visual — Esboços · Treino · Palco

**Data:** 2026-11-02
**Build:** 3.2.0/42 (debug)
**Branch:** `feat/visual-polish`

## Aparelhos

| Aparelho | Modelo | Android | Telas |
|---|---|---|---|
| A34 | SM-A346M | 16 | claro/escuro |
| Redmi 9 | M2004J19C | 12 | claro/escuro |
| Tablet | 25040RP0AL | 16 | claro/escuro |

## Escopo validado

- Barra inferior **Esboços · Treino · Palco**; sem “Home”.
- Esboço atual independente em Treino e Palco + **Trocar esboço** (picker
  compartilhado, badge **Atual**).
- Menu do card: **Treinar este esboço** / **Apresentar este esboço**.
- Empty states: Esboços (primeiro uso, pasta, busca), editor (nota nova ×
  nota com conteúdo sem seções), Treino/Palco sem seleção.
- Micro-polish: “Disponível”, “Anexar da Biblioteca”, ícone + **Estrutura**,
  plurais, CTAs, entidades HTML na prévia, sem setas nas abas de topo.
- **Desfazer exclusão de seção** (snackbar restaura seção/subpontos e remove
  tombstones) validado no A34.
- Grid do Redmi: título sem esmagamento; FAB não cobre o último card.
- Picker: inset da barra de navegação respeitado.
- **Demonstração no Palco:** “Roteiro pronto/incompleto”, “Falta o roteiro”,
  ações **Apresentar** / **Abrir Roteiro + Abrir editor** / **Abrir editor**;
  pré-voo “Preparar palco”; palco “Sem roteiro nesta seção”.

## Evidências

Screenshots em `docs/reports/assets/visual-polish/` (29 imagens).

Destaques:

- `a34-final-esbocos-{light,dark}.png`
- `a34-final-treino-{light,dark}.png`
- `a34-final-palco-{light,dark}.png`
- `a34-final-picker-{light,dark}.png`
- `a34-demo-palco-missing-light.png`
- `a34-demo-palco-ready-light.png`
- `a34-demo-prep-light.png`
- `redmi-esbocos-grid-dark-fix2.png`
- `redmi-esbocos-end-dark.png`
- `tablet-esbocos-{light,dark}.png`
- `tablet-treino-card-{light,dark}.png`
- `tablet-palco-card-{light,dark}.png`
- `tablet-picker-{light,dark}.png`

## Resultado

- A34 e Redmi: fluxo completo OK.
- Tablet: funcional; cartões/picker ocupam a largura toda (limitação conhecida,
  aceitável para uso pessoal).
- **2.831 testes JVM, 0 falhas.**
- **lintDebug: 0 errors**, 55 warnings, 7 hints.

## Observações

- Sem Firebase/sync neste release.
- Test notes de demonstração foram removidas permanentemente dos aparelhos.
- TalkBack/auditoria auditiva continua pendente (humano).
