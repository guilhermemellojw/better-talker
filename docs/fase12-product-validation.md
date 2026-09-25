# Fase 12 — Validação de produto (simulação de primeiro uso via Chrome CDP)

## §1 — Ambiente
Chrome 154 headless + CDP (cliques, foco, digitação, screenshots),
desktop 1440px e mobile 390px, perfil limpo. Sem aparelho físico desta vez
(smoke físico já feito na v1.0). Walkthrough é simulado pelo avaliador
(doc scripts em /tmp, não commitados) — limitação registrada.

## §2 — Perfil
Usuário que sabe preparar apresentação e editar texto; não conhece o app.

## §3 — Fluxo principal
Criar (botão Novo) → título/body por placeholder → Copilot → proposta →
verificar → aceitar → F9 → teleprompter: todos descobertos sem ajuda,
com screenshots em `/tmp` (não commitados).

## §4 — Descoberta (T5 salvo indicação)
Tudo descoberto sem ajuda: Novo, placeholders, tone pills, ações rápidas,
Verificar, Análise, Edição assistida, Modo Palco, undo/redo no header.

## §5 — Ajuda externa
Necessária em 0 passos do fluxo principal.

## §6 — Copilot
Ações descobertas; resultado offline aparece; fonte/trecho explicado;
modos de edição com preview; Aceitar/Rejeitar claros.

## §7 — Proveniência
Linhas 📖/🎤 com referência + relevância + tooltip; detalhes expansíveis
na verificação. Transmite "de onde veio".

## §8 — Verificação
Badges ✓/⚠/?/💡 com motivos; `insufficient` redigido como falta de
suporte, não como falsidade.

## §9 — Edição
Add/remover blocos (F11) descobertos nas abas; undo/redo no header.

## §10 — F9
Painel com contadores, tempo, observações expansíveis com ir-para-bloco
e "Gerar sugestão". Sem nota global.

## §11 — Ensaio
Modo Palco abre/fecha (teclado Esc e botão); controles com labels.

## §12 — Mobile
Header em rolagem só-ícones; painel e modais utilizáveis em 390px.

## §13 — Achados

| ID | Sev | Fluxo | Problema | Evidência | Ação |
|----|-----|-------|----------|-----------|------|
| UX-P0-01 | P0 | Edição | Digitar no bloco nunca persistia: `handleInput` fazia 2 `setState` com a mesma base obsoleta; o segundo anulava os blocos no batch do React (métricas sempre 0, autosave salvava vazio, reload perdia o texto). Título/inserir/propostas usavam chamada única e funcionavam — por isso passou despercebido. | fiber-probe + métrica 0 + localStorage vazio; pós-fix: métrica 5 + autosave correto | **Corrigido**: `applyBlockPatch` (fusão pura em chamada única) + teste |
| UX-OBS-01 | — | Harness | `insertText` via CDP/execCommand não alcança `onInput` do React no headless; digitação real não é afetada. Documentado para não confundir futuras sessões. | probes | Nenhuma (harness) |
| UX-OBS-02 | — | Cache | Browser CDP persistente serviu bundle antigo e invalidou uma rodada de probes. Sempre `Page.reload(ignoreCache)` antes de validar build novo. | probes | Nenhuma (harness) |

## §14 — Correções
Somente UX-P0-01: `src/services/speechBlocks.ts` (novo) + `App.handleBlockChange`
+ `BlockEditor.handleInput` (remove 2ª chamada) + `speechBlocks.test.ts`.

## §15 — Regressão
Web 172/172 (169 + 3 novos); build verde; lint 0 erros (6 warnings pré-existentes).
Android intocado nesta fase.

## §16 — Backlog (inalterado + 1 item)
usual pós-v1.0 (semântica, DOCX, WPM na UI, analyzer Kotlin, diff por palavra).

## §17 — Conclusão
**PRODUCT_VALIDATED_WITH_P1** → após correção do P0, sem P1s restantes:
uma pessoa consegue preparar uma apresentação completa sem conhecer o app;
o único bloqueador encontrado foi corrigido e provado end-to-end.
Estado final efetivo: `PRODUCT_VALIDATED`.
