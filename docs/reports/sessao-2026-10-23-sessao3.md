# Sessão 3 — post-citation-injection + post-C6/C8/Stage

**Build:** v2.7.4 (versionCode 24) · `feat/copilot-citations` @ `cbe8e13`
**Device:** tablet 25040RP0AL · **Nota:** “Ter esperança é o segredo para o contentamento (10 min)”
**Data:** 2026-10-23

> Esta ficha **não soma** com as Sessões 1–2. C1 reinicia a janela ≥2/7.
> C6/C8 entram como **sanity check** (já validados na Sessão 2); se falharem,
> é **regressão do cherry-pick**, não ❌ de comportamento.

## Antes de rodar (checklist)

- [x] TTS: o fix TTS/ANR do v2.7.2 (`28883ff`) está na base e foi validado no tablet
      (mensagem longa com travessões, sem SIGSEGV/ANR). **Nenhum TTS novo** no v2.7.4.
- [x] Cherry-pick `574f343` (C6/C8) + `c514d24` (Stage) na `feat/copilot-citations`.
- [x] Bump v2.7.4 / versionCode 24; **2.204 testes / 0 falhas**.
- [x] Tag `v2.7.4` na branch limpa; push da branch + tag.
- [x] Tablet: versão 2.7.4/24; **“Mais opções → Limpar conversa”** no topo funciona;
      **“Gera”** entrega “Texto para o card / Usar no card”; C6 sanity responde.
- [x] **BE 251 validado na WOL**: p. 251 = estudo 48 “Argumentação que estimula o
      raciocínio”. No acervo, `be_T.epub` tem 2.956 passagens, **`page` nulo**;
      o “251 48…” aparece só em um trecho de **sumário**. C7 = lacuna de metadados
      do BE (acervo), não da persona.

## Criar

| # | Prompt | Escopo S3 | Resultado | Evidência |
|---|---|---|---|---|
| C1 | Título da TH | **completo** (janela 0/7) |  |  |
| C2 | Páginas BE | completo |  |  |
| C3 | Anti-framework | completo |  |  |
| C4 | Nervosismo | completo |  |  |
| C5 | Só a fala | completo |  |  |
| C6 | Entonação | **sanity** (regressão? s/n) |  |  |
| C7 | BE 251 | completo — **WOL validado** |  | (se não achar: acervo/page map) |
| C8 | Marcadores | **sanity** (card? s/n) |  |  |
| C9 | Citação genérica | **formal — 3 sub-casos** |  |  |

### C9 — 3 sub-casos (referências validadas na WOL)

| Sub-caso | Referência | Fonte | Resultado |
|---|---|---|---|
| C9a | `w25.07 22 §§ 8-9` | A Sentinela jul/2025 (JWPUB) |  |
| C9b | `it "Aicão" §1` | Estudo Perspicaz (JWPUB) |  |
| C9c | `S-38 TESOUROS DA PALAVRA DE DEUS` | Instruções da reunião (JWPUB, parte) |  |

**Regra:** citar a referência **validada na WOL**; não usar “S-38 lição 3” (lições são do TH).

## Treinar (Stage)

| Item | Observação |
|---|---|
| Teleprompter |  |
| Timer |  |
| TTS (“Ouvir”) |  |
| Modo Dupla |  |
| Saiu do app? |  |
| Faltou medir fala? |  |

> TTS: o fix v2.7.2 está no build; se ainda falhar, registrar como **em andamento**,
> não como regressão da Sessão 3.

## Apresentar (Tribuna) — 2 cenários

| Cenário | Cápsulas bastaram? | TTS âncora | Export | Recorreu a papel/celular? | Tela cheia? |
|---|---|---|---|---|---|
| (a) nota com texto real |  |  |  |  |  |
| (b) nota com texto + S-34 importado |  |  |  |  |  |

> O “não gerada” da Sessão 2 rodou em nota vazia; sem os 2 cenários o diagnóstico
> A/B/C fica em aberto. **Tribuna não pode sumir.**

## Gatilhos

- C1: 0–1 erro de título em 7 sessões → cosmético; ≥2 → variante B do bloco.
- C6/C8: falha = regressão do cherry-pick (não ❌ de comportamento).
- C7: se o texto do estudo 48 não estiver mapeado, é **acervo/page map** (P3), não persona.
- C9: 3/3 = P1 formalmente fechado.
- Treinar “saiu do app” ≥2 → UX; Tribuna “papel/celular” na 1ª real → prioridade sobre v2.8.0.
