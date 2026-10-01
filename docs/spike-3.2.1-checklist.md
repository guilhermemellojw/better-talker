# Spike 3.2.1 — Checklist de Validação (Modelo C)

## Acesso
- [ ] Botão DEBUG visível no TopAppBar da Home
- [ ] Toca no botão abre a tela de protótipo
- [ ] Botão back na tela de protótipo volta à Home

## Estados básicos
- [ ] Ao abrir, nenhuma seção ativa (anotar se preferiria a primeira ativa)
- [ ] Clicar em INTRO → vira ativa, editor rico visível, borda primary
- [ ] Clicar em BODY → INTRO vira preview, BODY vira ativa
- [ ] Clicar em CONCLUSION → BODY vira preview, CONCLUSION vira ativa
- [ ] Clicar na seção já ativa → continua ativa (sem toggle)

## Animação
- [ ] Transição preview→editor suave (não pula instantâneo)
- [ ] Transição editor→preview suave
- [ ] Sem flicker, sem conteúdo cortado durante animação

## Foco / IME
- [ ] Ao ativar uma seção, cursor aparece no editor
- [ ] Teclado abre corretamente
- [ ] Seção ativa não fica escondida atrás do teclado
- [ ] Teclado fecha ao voltar para Home

## Isolamento (crítico)
- [ ] Escrever "INTRO-abc" na INTRO
- [ ] Ativar BODY, escrever "BODY-xyz"
- [ ] Ativar CONCLUSION, escrever "CONCLUSION-123"
- [ ] Voltar à INTRO → "INTRO-abc" preservado
- [ ] Botão HTML prova que cada seção tem HTML distinto
- [ ] Nenhum texto vaza de uma seção para outra

## Toolbar
- [ ] Botão "B" aplica negrito APENAS na seção ativa
- [ ] Botão "I" aplica itálico APENAS na seção ativa
- [ ] Toolbar desabilitada quando nenhuma seção ativa

## Scroll
- [ ] Column externa rola entre seções
- [ ] Seção com conteúdo longo não vira altura infinita
- [ ] Scroll funciona com teclado aberto
- [ ] Voltar à seção ativa após scroll funciona

## Performance
- [ ] Digitação fluida na seção ativa
- [ ] Trocar de seção rapidamente não crasha
- [ ] Sem lag perceptível ao ativar/desativar

## Decisão
- [ ] Modelo C aprovado → seguir para 3.2.2 (wiring real)
- [ ] Problemas encontrados → descrever abaixo

## Regressão do bug 3.2.1 (load entre seções)
- [ ] Fluxo limpo (app recém-aberto): INTRO→BODY→CONCLUSION cada um mostra seu próprio conteúdo
- [ ] Digitar em INTRO, trocar para BODY, trocar para CONCLUSION, voltar: nada contamina
- [ ] Alternar rapidamente entre seções 5x: sem crash, sem contaminação
- [ ] Dialog HTML: cada seção com HTML distinto

## IME cobrindo seção ativa
- [ ] Ativar CONCLUSION (seção no fundo) com teclado aberto: card sobe e fica visível
- [ ] Teclado não cobre o cursor na seção ativa
- [ ] Column externa rola se a seção ativa sobe

## Notas do spike (preencher durante o teste)
- Preview usado: Opção 3 (RichTextState read-only por seção com BasicRichText)
- _Suas observações aqui_
