# Discovery — persona "formada no BE/TH" com fontes hierarquizadas (2026-10-22)

Read-only. Fontes conferidas no **wol.jw.org** (edição atual em português).

> **Estado (reconciliado, v2.7.0):** o **v2.6.0** (`dc45efd`) já implementou a identidade
> "orientador" e 3 eixos condensados; o **v2.7.0** implementa os **5 eixos com fontes
> BE/TH** descritos aqui. A verificação deste documento é sobre as **fontes**
> (wol.jw.org), não sobre o estado do código. A nota anterior ("persona não
> implementada") foi escrita no worktree `-release-main`, que estava 1 commit atrás;
> ver `docs/fixes/2026-10-22-persona-orientador.md` para o histórico.

> **Nota (23/10):** a proposta de 5 eixos foi **deliberadamente não implementada**.
> O v2.7.0 adotou a **Opção B** — persona enxuta (~1.300 chars) + fontes BE/TH no
> **dossiê** (`ORATORY_SOURCES_BLOCK`). A verificação de fontes deste documento
> continua válida e foi incorporada ao bloco de contexto.

---

## 1. Tabela de fontes (5 eixos)

| Eixo | Fonte primária | Trecho literal (verificado) | O que NÃO citar |
|---|---|---|---|
| **Estrutura de discurso** | **BE 52-55** + **BE 166-169** | BE 52: "Antes de fazer qualquer pesquisa, leia o esboço e medite na matéria… O esboço fornecido é uma ferramenta para preparar o discurso e **não se destina a servir como notas** que usará para proferi-lo. É como um esqueleto…" · BE 166 (estudo 25): "Uso de esboço" | BE 47-51 e 39-42 **só com aviso** (ver §3 — o TH não cobre) |
| **Ensino** | **TH 3-19** | TH 4 "Prepare as pessoas para entender o texto" · TH 8 "Ensine com ilustrações" · TH 14 "Chame atenção para os pontos principais" · TH 17 "Fale de modo fácil de entender" | BE 56-70 e 230-271 (superados) — exceto BE 251/263 (ver §3) |
| **Aplicação** | **TH 13** (e TH 19) | TH 13 "Mostre como colocar o assunto em prática" | BE 56-70, 258-271 (superados) |
| **Conclusão** | **TH 20** | "Relembre as ideias principais e incentive as pessoas a colocar em prática o que aprenderam." | BE 220-222 (estudo 39 "Conclusão eficaz") — **100% superado** |
| **Entrega** | **TH 10-12, 15** (5-9 p/ leitura) | TH 10 "Varie o volume… a emoção… o ritmo" · TH 15 "Fale com convicção" · TH 12 "Seja simpático e mostre que se importa" | BE 83-114 e 111 (leitura/modulação) — superados |

---

## 2. Verificação: o TH cobre o BE antigo?

| BE antigo | TH cobre? | Evidência |
|---|---|---|
| 39-42 **Como elaborar um esboço** | ❌ **NÃO** | o TH não tem lição de "montar esboço"; a lição 1 ("Comece bem") é sobre preparar a **apresentação**, não o esboço |
| 47-51 **Discursos dirigidos à congregação** | ❌ **NÃO** | o TH é sobre leitura/ensino/entrega; não trata do formato do discurso dirigido à congregação |
| **52-55 Como preparar discursos públicos** | ❌ **NÃO** | exclusivo do BE ✓ |
| 56-70 Ensino/conversa/responder | ✅ **parcial** (ensino sim; "conversar"/"responder" não) | TH 3-19 cobre o ensino; "Como melhorar a habilidade de conversar" e "Saiba responder" seguem no BE |
| 83-114 Leitura | ✅ **parcial** | TH 5-6 ("Leia de modo correto", "Explique por que leu") |
| **166-169 Uso de esboço** | ❌ **NÃO** | exclusivo do BE ✓ |
| 230-271 Ensino (estudos 42-53) | ✅ **~80%** | TH 3-19; **não** cobre "Argumentação que estimula o raciocínio" (BE 251) nem "Controle e boa distribuição do tempo" (BE 263) |
| 220-222 Conclusão eficaz | ✅ **100%** | TH 20 cobre integralmente |

**Conclusão:** o TH cobre **conclusão (100%)**, **ensino/entrega (~80%)** e **leitura (parcial)** — mas **NÃO** cobre a **elaboração do esboço** (BE 39-42), o **discurso dirigido à congregação** (BE 47-51), o **uso de esboço** (BE 166-169) nem 2 pontos de ensino (BE 251/263). A afirmação "só BE 52-55 + 166-169" vale para **preparar/proferir discurso público**, não para "elaborar esboço" e "discurso dirigido à congregação".

## 3. O que é exclusivo do BE

| Exclusivo do BE | Página | Por quê |
|---|---|---|
| Como preparar discursos públicos | 52-55 | o TH não tem preparação de discurso público |
| Uso de esboço | 166-169 | o TH não ensina a usar o esboço em vez do manuscrito |
| Como elaborar um esboço | 39-42 | **não coberto** (lacuna do TH) |
| Discursos dirigidos à congregação | 47-51 | **não coberto** |
| Argumentação que estimula o raciocínio | 251 | não coberto |
| Controle e boa distribuição do tempo | 263 | não coberto |

> Pela sua regra ("**exceto se o TH não cobrir**"), esses 4 últimos podem ser
> citados **com o aviso** `(fonte: BE — a brochura TH não cobre este ponto)`.

---

## 4. Persona corrigida — diff vs a sua proposta

Mantida a sua estrutura de 5 eixos e o tom. **3 ajustes:**

1. **Eixo 1 (estrutura)** passa a citar **BE 39-42 e 47-51 com aviso** (o TH não cobre):
   > "Estrutura de discurso (BE 52-55 + 166-169; **para elaborar o esboço, BE 39-42 — o TH não cobre**): …"
2. **Vocabulário**: "modulação" é termo do **BE (estudo 9, p. 111)**; a **TH lição 10** ensina o mesmo como *"varie o volume, a emoção e o ritmo"*. A persona deve dizer **"modulação (variação de volume, emoção e ritmo)"** — e **nunca "entonação"** ✓.
3. **Teste 3.4 corrigido**: a **TH tem 20 lições** — "TH lição 42" **não existe** (o estudo 42 é do **BE**, p. 230, "Apresentação instrutiva"). O teste de não-invenção deve usar uma lição inexistente (ex.: "TH lição 25") para o modelo recusar ✓.

**Tamanho da persona corrigida:** ~2.750 chars ✓ (< 5.000).

Trecho final do eixo 1 proposto:

```
1. ESTRUTURA DE DISCURSO (fonte: BE 52-55 + BE 166-169; para elaborar o
   esboço, BE 39-42 — a brochura TH não cobre):
   Todo discurso tem introdução (que prende), pontos principais (3-4, claros)
   e conclusão (que recapitula e incentiva). O esboço é a base; o texto vem
   depois. Use o esboço para falar de coração — não leia manuscrito.
```

## 5. Ajuste no `LANGUAGE_PRINCIPLES`

`data/copilot/LanguageGuide.kt:42`:

```diff
-- Aplicação prática no fim.
+- Aplicação prática ao longo do discurso (não só no fim).
```

Motivo: "no fim" confunde **aplicação** com **conclusão**; a TH lição 13 trata a
aplicação como algo que **permeia** a apresentação, e a TH 20 trata da conclusão.

---

## 6. Os 4 testes (2 automatizados como conteúdo de prompt + 2 comportamentais)

| # | Pergunta | Esperado |
|---|---|---|
| 1 | "Me ajude a escrever a conclusão do meu discurso." | Recapitular + incentivar + rematar; **não** introduz ideias novas; **não** vira "aplicação do ponto 2" |
| 2 | "Onde aprendo a preparar discursos públicos?" | "BE pp. 52-55 (e, para o esboço, BE 39-42); as técnicas de entrega estão na TH" — **não** cita BE 56-70/83-114/230-271 como fonte atual |
| 3 | "O que é modulação?" | "Variação de volume, emoção e ritmo" (TH lição 10 / BE estudo 9) — **não** "entonação" |
| 4 | "O que a TH lição 25 ensina?" | "A TH tem 20 lições — não existe lição 25" (não inventa) |

---

## 7. Decisões em aberto

| # | Decisão | Recomendação |
|---|---|---|
| 1 | Citar BE 39-42/47-51 (TH não cobre) ou omiti-los? | **citar com aviso** — são a lacuna real do TH |
| 2 | "Discurso dirigido à congregação" é escopo da persona? | **sim** (o app faz S-34 e Tesouros, que são partes da reunião) |
| 3 | BE 251/263 (raciocínio/tempo) entram? | **sim, com aviso** — o TH não cobre e o app usa tempo ("10 min ≈ 1300 palavras") |
| 4 | Onde a persona vive? | `GenerationPrompts` (`SPEECH_MENTOR`/`DEMO_SCRIPTWRITER`) para os 5 eixos + `ChatPromptBuilder` para a identidade do chat — implementado no v2.7.0 |
| 5 | Vocabulário "modulação" vs "varie volume/emoção/ritmo" | usar **"modulação"** com o glossário entre parênteses (a Escola usa o termo) |
