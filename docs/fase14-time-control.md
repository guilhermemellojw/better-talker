# Fase 14 — Controle de tempo por bloco (documentação §26)

## Objetivo
Ver por bloco: estimado, meta opcional, diferença e totais — sem julgar.

## Modelo
`SpeechBlock.targetDurationSeconds?: number`. Opcional; ausente = sem meta;
`0`/negativo = inválido (nunca vira meta). Só a meta persiste; o estimado é
sempre derivado do conteúdo + WPM. Blocos antigos: compatíveis, sem migração
(Dexie schemaless; campo ausente = sem meta).

## Cálculo (reutilizado, não duplicado)
Mesma fórmula de `calculateSpeechMetrics` (palavras/wpm + pausas de palco),
com extração sem DOM (badges removidos antes da conta, como a versão DOM).
WPM: `speech.targetWpm || defaultWpm` (seletor 110/130/160 existente).

## Tolerância e estados (§9)
±10s = dentro; `+` acima; `-` abaixo. Sempre texto + valor
("Acima da meta: +0:18"), nunca só cor. Sem nota global/ranking.

## UI
- Rodapé do bloco: `Meta [mm:ss] [×]` + `Meta/Estimado/status` (compacto,
  quebra em mobile; `role=status`, `aria-label`, erro textual).
- Barra de métricas: pill de totais só quando há metas
  (`Total ~X • Metas Y (Δ)`); sem metas, nada muda.
- Entrada aceita `mm:ss` (ex. 01:30); vazio = limpar; Enter confirma,
  Escape restaura.

## Persistência
Via `handleBlockChange` → autosave existente (sem mudanças no mecanismo).
Definir/alterar/remover sobrevivem a reload (provado em browser + testes).

## Mobile (390px)
Sem overflow-x; painel, proposta, análise e footer utilizáveis (screenshots).

## Testes
21 unitários (10 casos §18 + pausas/badges + formatos + resumo + 5 de
persistência/compatibilidade). E2E em Chrome real com teclado fiel:
meta, reatividade, reload, add/remove, offline, mobile.

## Integração F9
Intocada; tempos do analyzer seguem independentes e funcionando.

## Copilot / CONTENT / TRAINING
Intocados (nenhuma alteração; verificado por diff).

## Limitações
- Estimado puro pode divergir ~1s da barra (badge como palavra); documentado.
- Nativo: sem port (OutlineSection.minutes em minutos; meta em segundos
  exigiria schema — backlog).
- WPM avançado, semântica, diff: backlog, não implementados.

> O tempo estimado é derivado do conteúdo atual e do WPM. A duração-alvo é
> persistida; o tempo estimado não é.
