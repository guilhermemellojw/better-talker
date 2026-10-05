# Re-Validação Física Final F20-F1 — A34 (2026-10-05)

## Ambiente
- **Device:** SM-A346M (USB)
- **APK:** `5426447` (install Success)
- **Chave DeepSeek:** presente e válida (`sk-`, provider `auto`)
- **Sessão:** cold start (cache de sessão do fallback zerado de propósito)

## Bloco 1 — Oratória com DeepSeek: **PASSOU**
- Import do `S-34_T_194.rtf` (re-import): `Saved(outlineId=s34-b6631d57, sections=5, references=21)`.
- Seção ativada no editor (card BODY) → foco `36` chars.
- "Desenvolva o ponto 1." → logs:
  - `deepseek 400 response_format indisponível; fallback=JSON_OBJECT` ✅ (fallback acionado)
  - `oratória: aviso de fidelidade (invented=0 leaked=0 unsupported=1)` ✅
- Cartão **Proposta/ANTES/DEPOIS · 💡 Sugestão criativa** com desenvolvimento
  coerente do ponto (Tiago 3:13-16, Provérbios 9:10, Jeremias 8:9, 1 Cor 1:19-21).
- **Qualidade: 4/5** (no tema, bem estruturado; 1 trecho sinalizado pelo verificador).
- `reasoning_effort: none`: aplicado (coberto por teste JVM; **não há linha de log
  dedicada** — sugestão: logar modo+effort em requisições estruturadas).

## Bloco 2 — Proposta F5 + Aceitar/Desfazer: **FALHOU (Bug #14)**
- "Criar proposta" (de resposta de chat): `proposal context ok focus=36` +
  `proposal generated len=761 offline=false` (cache quente, sem 400) ✅
- Cartão **Proposta/ANTES/DEPOIS · 💡 Sugestão criativa** ✅
- **Aceitar: NÃO** — "Proposta obsoleta: o bloco mudou depois da geração."
- **Desfazer: não testável** (depende do Aceitar).

### Causa raiz (Bug #14, P1)
`acceptProposal` valida o foco contra o texto da nota:
`stripHtmlToText(note.richHtml).contains(ui.focusText)`.
Com o foco vindo do **título da seção** (`_activeBlockTitle`, fix `bff1dac`), o
título vive em `speech_sections` — e o `richHtml` da nota está vazio
(28 chars de whitespace; `instr(richHtml,'FOMOS CRIADOS')=0`). Ou seja: com
foco por título, o Aceitar **sempre** cai em obsoleta. Afeta o fluxo principal
destravado pelo T2 (`bff1dac`).

## Bloco 3 — Estabilidade (5 interações): **PASSOU**
- 7+ interações na sessão (import, oratória ×2, F5, 3 envios de chat).
- `FATAL EXCEPTION | ANR`: **0** ✅
- Memória: `TOTAL PSS 274 MB` (estável; sem crash de OOM).

## Bugs novos

### #15 — P0 (REGRESSÃO de `45400da`): chat responde em JSON cru
- **Sintoma:** respostas de chat com `{"resposta": "..."}` na UI; criação
  corrompida (`\"〈/sugestão〉…`).
- **Causa raiz:** em `DeepSeekProvider.generate`, requisições **não
  estruturadas** usam `modes=[TEXT]`, e `withStructuredInstruction(TEXT)`
  injeta *"Responda APENAS com JSON válido…"* **também no chat comum** — o
  enum TEXT é compartilhado com o 3º passo do fallback estruturado.
- **Fix provável (1 linha):** aplicar a instrução só quando `structured=true`
  (ex.: `if (structured) prompts.first.withStructuredInstruction(mode) else prompts.first`)
  + teste de chat sem a instrução (o teste atual só cobre a ausência de
  `response_format`).

### #14 — P1: Aceitar sempre obsoleta com foco por título de seção
- Descrição acima (Bloco 2). Fix provável: no `acceptProposal`, aceitar foco
  de título (pular/relaxar o `contains` quando o foco veio de
  `_activeBlockTitle`, ou validar contra as seções persistidas).

## Estado
- Ciclo F20-F1: **parcial** — oratória ✅, estabilidade ✅; proposta F5
  bloqueada no Aceitar (#14); **chat degradado por regressão #15 (P0)**.
- Nenhum código alterado durante a validação.
