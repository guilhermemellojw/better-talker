# Validação — Fase 2.1 + T6 (A34)

**Data:** 12/10/2026
**Device:** SM-A346M (A34), adb Wi-Fi (`adb-RQCX209WR1Z`)
**APK:** HEAD `548760f` (Fase 2.1 T1–T5 + T6)
**Chave DeepSeek:** presente
**Regra:** sem alteração de código durante a validação; bugs apenas registrados.

---

## T6 — Botão "ferramentas" (verificação no device)

- Composer: apenas `+` (Anexar esboço), voz (Falar) e enviar — **sem "Ferramentas"**.
- "Limpar conversa" no menu ⋯ de cada resposta: exercitado no device
  (⋯ → Limpar conversa → diálogo "Limpar conversa?" → histórico apagado). ✓
- Atalhos remanescentes nos chips da conversa vazia (contrato travado em `ChatActionsTest`).

## Bloco 1 — Memória no modo colar ✓

**Roteiro:** esboço colado (507 chars, "esperança", 3 pontos) + 7 turnos
(decisão dos "3 animais imortais" → confirmar fênix → 2º/3º pontos → coerência → recall).

**Resultado:**
- No turno da coerência ("volte à introdução, ela está coerente com os 3?") a resposta
  manteve **"os três animais"** no fio — e já no turno anterior citou **"o pássaro"**.
- No turno de recall ("você lembra das decisões?") listou, sem titubear:
  **"fênix, tartaruga e pássaro"** — a decisão do turno 2, que já estava **fora** da janela
  de 6 mensagens do prompt minimalista. Memória funcionando no modo colar. ✓
- `## MEMÓRIA DA CONSTRUÇÃO` no log: **não há log do bloco** (não implementado);
  a verificação foi comportamental.
- Retries durante o Bloco 1: **0**.

**Observação de comportamento:** o copilot reconheceu as decisões mas **recusou materializar**
a criação (animais) mesmo com autorização explícita e com o marcador `〈sugestão〉` disponível —
sempre empurrou de volta com "cole uma fonte". A construção co-criativa no modo colar fica
limitada pela regra "esboço é a única fonte".

## Bloco 2 — "Insira tudo na nota" no modo colar ✓

- **Comando reconhecido:** sim — abriu a **prévia** (não virou resposta de chat).
- **Prévia:** "Inserir estrutura na nota — **5 partes detectadas**" (INTRODUÇÃO + 3 pontos + CONCLUSÃO).
- **Nota recebeu as seções:** sim — 5 seções gravadas (INTRO/BODY×3/CONCLUSÃO, orders 0–4),
  com o conteúdo do banco **sem** `〈sugestão〉` e **sem** seção-lixo "SE".
- **2ª rodada** ("insira tudo na nota de novo"): aviso
  *"A nota já tem seções. Substituir tudo ou adicionar ao final?"* → **"Adicionar"** →
  8 seções (3 novas anexadas, orders 0–7). Nesta rodada os títulos gerados **não coincidiram**
  com os existentes — dedup não acionado (coberto por JVM: `StructureParserTest`).

**Débitos confirmados no conteúdo:**
1. A estrutura veio com **placeholders** (`[desenvolvimento]`, títulos `[…]`) — Fase 2.2
   (geração no modo colar não recebe o esboço colado).
2. **1 "vazio por length"** na 2ª geração de estrutura — rota de chat normal (esforço `low`),
   fora do escopo do T4 (que cobre colar + estruturado).

## Bloco 3 — Estabilidade ✓

- ~9 interações de modelo + 2 fluxos de prévia/inserção + limpeza de conversa:
  **FATAL/ANR = 0** (antes e depois do force-stop).
- **Force-stop + reabrir:** nota (8 seções) e conversa íntegras.
- **Retry reduzido:** **1 ocorrência total** de "deepseek vazio por length" na sessão
  (vs "maioria dos turnos" na validação anterior). T4 validado no device.

## Bugs / observações

| # | Item | Evidência |
|---|---|---|
| 1 | (Fase 2.2, já registrado) estrutura no modo colar sem o esboço no contexto de geração → placeholders | prévia com `[desenvolvimento]` |
| 2 | (Novo) `apply-structure` (chat normal) ainda pode dar vazio por length (1× na sessão) — T4 não cobre a rota | `W/CopilotLLM: deepseek vazio por length; retry...` + `INVALID_RESPONSE` |
| 3 | (Novo, comportamento) no modo colar, o copilot resiste a criar/adaptar conteúdo fora do esboço mesmo autorizado | turnos pedindo a fênix como sugestão marcada |

**Higiene:** as 2 notas de teste ("Nova nota") foram excluídas (Lixeira);
`screen_off_timeout` ficou em 30 min (ajustável pelo usuário).

## Estado final

**Fase 2.1: VALIDADA** — memória no modo colar (T1), "insira tudo" com precedência (T2),
sanitização sem tags/"SE" (T3), retry drasticamente reduzido (T4) e dedup coberto (T5) + T6 aplicado.

**Próximo passo sugerido:** Fase 2.2 — incluir o esboço colado no contexto da geração de estrutura.
