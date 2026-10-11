# Validação — Construção em Conjunto (Fase 2) no A34

**Data:** 11/10/2026
**Device:** SM-A346M (A34 de Guilherme), Android, adb Wi-Fi (`adb-RQCX209WR1Z`)
**APK:** build debug do HEAD `db90477` (instalado via `adb install -r`)
**Chave DeepSeek:** presente
**Regra:** sem alteração de código; bugs apenas registrados.

---

## 1. Bloco 1 — Memória da construção (Modo Colar e Conversar)

**Roteiro:** esboço livre colado (507 chars, tema "esperança", 3 pontos), seguido de 7 turnos
(construir introdução com "3 animais imortais" → desenvolver 1º/2º/3º → "volte à introdução, ela
está coerente com os 3?" → "mantenha o tom pastoral" → "você lembra das decisões?").

**Resultado:**

| Verificação | Resultado | Evidência |
|---|---|---|
| Copilot referenciou decisões antigas | **Parcial** | Turno 7 listou "decisões" — mas todas extraíveis do **esboço** (sempre no prompt: tom caloroso, 3 pontos, ilustrações, conclusão prática) + "tom pastoral" (turno 6, recente). **Zero menção** aos "3 animais imortais"/fênix construídos nos turnos 1-2. |
| Fio preservado | **Não** | Na pergunta crítica (T5) e no recall (T7), o copilot reinterpretou "os 3" como os 3 pontos do esboço e admitiu: *"Sobre a sua introdução original, continuo sem o texto exato dela no esboço; por isso, não posso avaliar a redação original."* |
| Bloco `## MEMÓRIA DA CONSTRUÇÃO` no log | **Não** | Não existe log do bloco; e no modo colar o bloco **não é montado** (ver causa 1). |

**Causa (código):** o modo colar usa `answerMinimal`, que chama `buildMinimalPastePrompts` e **ignora**
o `effectiveContextBlock` (`DeepSeekProvider` faz early-return em `request.minimalPaste != null`).
A memória da construção (`renderMemoryBlock`) só é injetada em `answerRemote`. → **T1 não chega ao
modo colar.**

---

## 2. Bloco 2 — "Insira tudo na nota"

### 2a. No Modo Colar e Conversar — **FALHA**

- Comando "Insira tudo na nota." foi tratado como **mensagem normal de chat** (resposta em texto:
  "Tom: pastoral... Introdução pastoral..."), **sem prévia e sem inserção na nota**.
- **Causa (código):** em `CopilotViewModel.send()`, o ramo `if (pasteBuffer != null) { answerMinimal(...); return }`
  (linha ~1050) **precede** o check `isApplyStructureCommand` (linha ~1056). → T2 inacessível no modo colar.

### 2b. No caminho estruturado — **OK**

Nota nova ("Nova nota"), conversa curta sobre "gratidão em 3 pontos" (2 turnos + comando):

| Verificação | Resultado |
|---|---|
| Comando reconhecido | **Sim** |
| Estrutura canônica | **Sim** — "4 partes detectadas" (INTRODUÇÃO + 3 pontos) |
| Prévia apareceu | **Sim** — sheet "Inserir estrutura na nota" com títulos + primeiras linhas + botões |
| Nota recebeu as seções | **Sim** — INTRODUÇÃO (com texto) + "Bênçãos do dia a dia" + "Gratidão nas dificuldades" + "Ajudar os outros" (conteúdo em `sub_points.outlineText`) |
| Substituir/adicionar | **Sim** — 2º comando com a nota preenchida avisou: *"A nota já tem seções. Substituir tudo ou adicionar ao final?"*. "Adicionar" **preservou** as 4 originais e anexou 3 novas (banco: 7 seções, ordem 0-6) |

**Evidência de consenso (T1) no caminho normal:** o payload bruto da resposta ao turno 2 contém
`〈decisão〉Fechado: mensagem em três pontos — bênçãos do dia a dia, gratidão nas dificuldades e ajudar os outros.〈/decisão〉`
→ o modelo emite a tag; o resumo rolante é montado dessa história (bloco ativo a partir do 3º turno).

Obs.: `agreedApproach` das seções ficou `NULL` — `recordConsensus` exige seção em foco
(`_pushedContext.sectionId`); a conversa ocorreu na nota vazia, sem seção. Decisões seguem
disponíveis via resumo rolante (histórico), mas não persistidas por seção nesse cenário.

---

## 3. Bloco 3 — Estabilidade

- ~11 turnos de IA no total (7 no modo colar + 4 no estruturado) + 2 fluxos de prévia/inserção.
- **FATAL/ANR: 0** (`adb logcat` grep `FATAL EXCEPTION|ANR in` = 0, antes e após force-stop).
- **Force-stop + reabrir:** estado preservado — nota com as 7 seções intacta e conversa do copilot
  completa no histórico (inclusive as duas confirmações "Inserido na nota").

---

## 4. Bugs e observações registrados

| # | Severidade | Item | Evidência |
|---|---|---|---|
| 1 | **Alta** | Memória da construção ausente no Modo Colar e Conversar (T1 não injetado em `answerMinimal`) | T5/T7 + código |
| 2 | **Alta** | "Insira tudo na nota" sequestrado pelo modo colar (ramo `pasteBuffer` precede `isApplyStructureCommand`) | resposta em chat, sem prévia; código |
| 3 | Média | `〈sugestão〉` persistido literalmente na nota (2ª inserção: `sub_points` e prévia com marcadores) | DB `sub_points.outlineText` |
| 4 | Média | 4ª parte da 2ª prévia virou seção de título lixo "SE" (sem conteúdo) | DB `speech_sections` ordem 6 |
| 5 | Média | DeepSeek "vazio por length" muito frequente: maioria dos turnos do modo colar exigiu retry; alguns 3+ tentativas; latência 60-120 s/turno | `W/CopilotLLM: deepseek vazio por length; retry com budget=2000` |
| 6 | Baixa | "Adicionar ao final" duplica tópicos idênticos (sem dedup) | 7 seções com "Bênçãos do dia a dia" ×2 |
| 7 | Ambiente | Gaveta de notificações abriu espontaneamente em 2-3 ocasiões durante esperas (provavelmente gestos/telefone em uso); causou falsos positivos de dump; não é bug do app | dumps com `mCurrentFocus=NotificationShade` |

Obs. de ambiente: `screen_off_timeout` foi elevado para 30 min durante o teste (não revertido para
não sobrescrever preferência do usuário — ajustar manualmente se desejar).

---

## 5. Estado final

- **Construção em conjunto (Fase 2): com ressalvas.**
  - T2 (materializar estrutura) **validado ponta a ponta no caminho estruturado**, incluindo
    substituir/adicionar.
  - T1 (memória) tem mecanismo funcionando no caminho normal (tag real emitida + resumo rolante),
    mas **não existe no Modo Colar e Conversar** — que é o fluxo principal de construção.

**Próximo passo sugerido:** Fase 2.1 — (a) reconhecer "insira tudo" antes do ramo do modo colar e
(b) injetar o bloco de memória no `answerMinimal`; depois revalidar o roteiro do Bloco 1 no A34.
