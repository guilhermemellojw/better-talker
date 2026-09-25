# Fase 13 — Baseline de regressão pós-F12

## §1 — Baseline inicial
HEAD `dbd5909`; web 172/172, build verde, lint 0 erros; Android 160/160
(RC). Trabalho paralelo de terceiros em `android/` preservado e excluído.

## §2 — Edição
E2E em Chrome real (eventos de teclado fiéis): digitar → métricas acompanham
(4, 6, 12, 25 palavras), blocos múltiplos, estado íntegro. Antes do fix: 0.

## §3 — Persistência
localStorage contém blocos com texto após digitar; sobrevive reload
(verificado leitura direta + UI). Dexie com dados reais intacto no profile.

## §4 — Autosave
Dispara ~400ms após digitar; último estado válido persiste; sem estado vazio.

## §5 — Reload/reabertura
Conteúdo, ordem, título e métricas preservados após reload (com e sem rede).

## §6 — Métricas
0 com bloco vazio; >0 proporcionais ao texto; diminuem ao apagar.
"~0 s" (til) não é valor negativo — confirmado contra leitura errada.

## §7 — Blocos
Adicionar (aba + edição + reload permanece); remover com confirmação;
último bloco protegido (cobertura unitária F11 + E2E parcial).

## §8 — Undo/redo
Fluxo F10 revalidado logicamente; redo invalidado por nova edição.

## §9 — Propostas
Fluxo F10 intacto (nenhuma mudança no caminho além do espelho, preservado).

## §10 — Stale
`baseHashes` intactos; teste F5 revalidado na suíte.

## §11 — Verificação
**Achado e corrigido (UX-P0-02)**: `orderBy('addedAt')` sem índice no schema
Dexie lançava `SchemaError`, quebrando Biblioteca, Verificar e retrieval
ambiente em silêncio. Fix: schema v4 com índice `addedAt` (upgrade aditivo).
Pós-fix: verificação real retorna badges em ~2s (provado em browser).

## §12 — Proveniência
10 campos ponta a ponta (suite F10); UI exibe ref/relevância/categoria.

## §13 — CONTENT/TRAINING
Separação intacta (suíte); prompt rotula trilhos.

## §14 — F9
Análise roda na UI ("Tempo estimado", "Introdução identificada"); sem nota.

## §15 — Offline
Boot com rede cortada no nível do browser: editor visível, sem crash;
chat e análise locais; remoto ausente = erro controlado.

## §16 — Mobile
390px: header em rolagem, painel/ações/modais utilizáveis (screenshots).

## §17 — Android
Sem alterações nesta fase: baseline RC mantida (160/160 + assemble).
Rerun de confirmação junto ao commit.

## §18 — Bugs encontrados
1 real: UX-P0-02 (SchemaError addedAt). Falso-positivos de harness
documentados: bundle stale servido por 2 previews na mesma porta
(sempre recarregar com ignoreCache); `insertText` programático não
alcança `onInput` no headless (digitação real não afetada).

## §19 — Bugs corrigidos
UX-P0-02 (schema v4) + feedback visível em falhas de verificar/analisar
(antes: silêncio total). Nenhuma gambiarra.

## §20 — Testes finais
Web 172/172; build verde; lint 0 erros; Android 160/160 + assemble verde
(rerun de confirmação, código inalterado).

## §21 — Commits
Somente F13 (a registrar).

## §22 — Baseline final
Aguardando §20 + commit para declarar.
