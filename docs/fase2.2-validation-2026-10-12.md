# Validação — Fase 2.2 no A34 (esboço no contexto + modo criação)

**Data:** 12/10/2026
**Device:** SM-A346M (A34), adb Wi-Fi (`adb-RQCX209WR1Z`)
**APK:** HEAD `e47e1e9` (Fase 2.2)
**Chave DeepSeek:** presente
**Regra:** sem alteração de código durante a validação; bugs apenas registrados.

---

## Bloco 1 — Estrutura sem placeholders ✓

**Roteiro:** nota nova → esboço colado (787 chars, 3 seções com títulos explícitos) →
2 turnos curtos ("Vamos usar esse esboço." / "Desenvolva a introdução.") → **"Insira tudo na nota."**

**Resultado (prévia, 5 partes):**

| Parte | Título na prévia | Conteúdo |
|---|---|---|
| INTRO | Introdução — INTRODUÇÃO | desenvolvido (abre com pergunta à plateia) |
| 1 | Confiar nas promessas | ilustração desenvolvida (criança segurando a mão do pai) |
| 2 | Oração e Palavra | ilustração desenvolvida (casa à beira-mar, vela acesa) |
| 3 | Amor prático | ilustração desenvolvida (pessoa resgatada por presença) |
| CONC | Conclusão — CONCLUSÃO | desenvolvido ("Esperança não é fingir que está tudo bem…") |

- Placeholders apareceram: **não** (`[desenvolvimento]`/títulos `[…]` sumiram).
- Conteúdo real do esboço: **sim**.
- Títulos correspondem ao esboço: **sim** ("Confiar nas promessas", "Oração e Palavra", "Amor prático").
- Nº de seções: **5** (INTRO + 3 + CONCLUSÃO; `orders 0–4` no banco; sub_points com as ilustrações).
- Editor: "INTRODUÇÃO" + tópicos reais, "Salvo local", 114 palavras.

## Bloco 2 — Criação marcada com `〈sugestão〉` ✓

**Pedidos:** *"Crie uma ilustração sobre gratidão que não está no esboço."* / *"Sugira uma metáfora nova para o ponto 2."*

- Copilot criou: **sim** — "Ilustração curta — gratidão" (pessoa anota 3 coisas simples do dia) e
  "Metáfora — a âncora e a corda" (barco na tempestade); não recusou.
- Marcou com `〈sugestão〉`: **sim** — confirmado no payload cru das duas respostas;
  a metáfora ainda traz "Nota de transparência: esta metáfora é uma sugestão minha, não está no esboço."
- Badge 💡 na UI: **sim** — "💡 Sugestão criativa" renderizado no topo da resposta
  (marcadores removidos do texto exibido).

## Estabilidade

- **FATAL/ANR = 0**; **"vazio por length" = 0** — nenhum retry na sessão inteira.
- Sem quedas de ADB.

## Bugs / observações

| # | Item | Evidência |
|---|---|---|
| 1 | (Estilo, menor) A INTRODUÇÃO veio com uma frase-guia meta ("Abra com uma pergunta direta à plateia:") antes do texto — conteúdo real, mas com resquício de instrução | prévia + `contentHtml` |
| 2 | (Por design) A resposta de criação abre com a frase de insuficiência e depois oferece a sugestão marcada — gate em modo aviso cumprindo o contrato | payload cru |

**Higiene:** nota de teste ("Nova nota", 12/10) excluída (Lixeira);
`screen_off_timeout` ficou em 30 min (ajustável pelo usuário).

## Estado final

**Fase 2.2: VALIDADA** — estrutura com conteúdo real do esboço (sem placeholders) e
modo criação funcionando no modo colar (criação marcada + badge na UI).

**Próximo passo sugerido:** Exportação (handoff emitido) — o discurso sai do app.
