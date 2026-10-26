# Validação no tablet — contexto fiel (Fase 1)

**Data:** 2026-10-26
**Aparelho:** Samsung Galaxy Tab (25040RP0AL), Android 16 (SDK 36), 1600×2560
**APK:** debug 2.7.4 (versionCode 24), build de 2026-10-26 (working tree sobre `275709a`)
**Testes JVM:** **2.467 / 0 falhas** (289 relatórios) no working tree;
**2.383 / 0** no commit isolado da correção de refs (`5a9b2fe`, CI verde).
**ADB:** `adb-ij8tq8wojnhe7pem-y9MiYf._adb-tls-connect._tcp`

> A A34 estava indisponível; a validação foi feita no tablet, com a chave
> DeepSeek já configurada no aparelho.

## 1. Resultado executivo

| # | Critério | Resultado |
|---|---|---|
| 1 | V&M com `lmd lição 7 ponto 4` no BODY → lista lmd, não “não encontrei” | ✅ `NoteRefs.publications` contém **`lmd lição 7 ponto 4`** (capítulo `lição 7`, §4); a genérica `lmd § 4` foi removida no dedup |
| 2 | S-34 com refs em subponto → mesma resposta | ✅ card listou publicações dos subpontos (`w09 1/8 p.3`, `w15 1/5 p.10`) |
| 3 | Resposta longa + “Sim” → continua | ✅ gerou “Texto para o card” (1.148 chars) sem “sim o quê?” |
| 4 | Trocar “Discurso inteiro” → “Tópico” + “Sim” → continua | ✅ gerou a conclusão (~130 palavras) a partir da pergunta do escopo anterior |
| 5 | Card/proposta anterior no histórico como texto | 🟡 `ideas/refs/sections/draft` viram texto legível; a **proposta pendente** ainda vive só na UI (persistência do summary não entrou nesta rodada) |
| 6 | `w25.07 § 999` com edição presente e trecho ausente | ✅ regra pura + prompt distinguem “edição no acervo; trecho não encontrado” × “edição ausente” (teste) |
| 7 | `th` vira orientação, não conteúdo | ✅ `citedSources` ignora `be/th`; inventário os coloca em “Orientação de oratória” (teste) |
| 8 | TNM 66 livros/abreviações | ✅ `BibleNormalization66Test` |
| 9 | Custo típico 8–12k, cache no prefixo | ✅ turno corrigido: `in_tokens=4072`, `cached_tokens=640`, `history_turns=12` (janela efetiva), `history_chars=8607` |
| 10 | 2.375+ testes + validação manual | ✅ 2.467 testes; tablet validado |

## 2. Evidências

### 2.1 V&M — lmd no BODY

**Caso A — “Jesus não é o Deus Todo-poderoso”** (`note-84e39e04…`), BODY
“Jesus não é o Deus Todo-poderoso · lmd apêndice A ponto 20”.

Pergunta: `quais referencias esta parte usa`

Card retornado:

```text
Referências da nota
⬇️ Ame as Pessoas — Apêndice A, ponto 20
✅ Melhore
No arquivo: th_T.jwpub
```

O lmd aparece como “Ame as Pessoas — Apêndice A, ponto 20”; o `th` aparece
como guia (Melhore), não como matéria.

**Caso B — “Cultivando o interesse (4 min)”** (`note-0cf3a4ee…`), BODY
“Fale obre as verdades do apêndice A · Testemunho INFORMAL · lmd lição 7 ponto 4”.

Lista exata de `NoteRefs.publications` (inspeção pura):

```text
lmd lição 7 ponto 4  (chapter=lição 7, paragraph=4)
lmd § 4              (chapter=null, paragraph=4)   ← genérica da seção
```

A unidade correta **está na lista**. A linha genérica `lmd § 4` vinha do
`publicationRefsJson` da seção (`[{"symbol":"lmd","paragraph":4}]`) e foi
removida pelo dedup genérica × específica. O teste
`genericaSemCapituloNaoDuplicaUnidadeEspecifica` trava isso.

**Caso C — exemplo do usuário (2 unidades da mesma publicação):**

```text
Cultivando o interesse (4 min) TESTEMUNHO INFORMAL. Fale sobre uma das
verdades do apêndice A da brochura Ame as Pessoas. (lmd lição 9 ponto 5)
```

No tablet, o card final ficou:

```text
Referências da nota
Ame as Pessoas — 2 referências
⬇️ Apêndice A
⬇️ lição 9, ponto 5
```

O grupo aparece uma vez e as duas unidades abaixo. ✅

> **Trecho resolvido:** o tablet tem `lmd_T.jwpub` em estado `failed`
> (não indexado). Por isso o card mostra “Baixar no site”, não um trecho.
> Isso é ausência de edição no acervo — não falha de detecção/resolução.
> Quando a edição estiver indexada, o mesmo `sourceKey` alimenta o
> `citedSources`/checklist.

### 2.2 S-34 — refs de subponto

Nota **“# 192. Você está no caminho para a vida eterna?”**
(`note-f21f19a7…`), seção **“O QUE É O ‘CAMINHO PARA A VIDA’?”** (2/6).

Pergunta: `quais referencias esta parte usa`

Card listou, entre outras:

```text
⬇️ A Sentinela, 1º de agosto de 2009 (pública) • pág. 3
⬇️ A Sentinela, 1º de maio de 2015 (pública) • pág. 10
```

Essas refs vêm dos subpontos da seção — o coletor único
(`collectNoteRefs`) passou a alimentar o `answerCheckRefs`. ✅

### 2.3 “Sim” após pergunta do Copilot

Nota “Jesus não é o Deus Todo-poderoso”, escopo Tópico.

Pergunta: `o que este ponto ensina sobre Jesus`

Resposta terminou com:

> “Quer que eu escreva o bloco nessa linha?”

Resposta ao `Sim`:

```text
Texto para o card  [Usar no card]
Muitos hoje dizem que Jesus é o próprio Deus Todo-poderoso...
```

Log:

```text
generation|route=chat|budget=2048|finish_reason=stop|chars=1148|words=202|truncated=false|hasCard=true|thinking=false
```

✅ Continuidade sem “sim o quê?”.

### 2.4 Troca de escopo + “Sim”

1. Escopo **Discurso inteiro**: “o que devo priorizar neste discurso?”
   → resposta terminou com “Quer que eu escreva agora a conclusão de 1 minuto
   nessa linha…?”
2. Troca para **Tópico** e envio de **“Sim”**
   → o Copilot gerou a conclusão (~130 palavras), em card, referindo-se à
   pergunta do escopo anterior.

✅ O histórico passou a ser **da nota**, com rótulo de foco por turno
(`[Tópico: …]` / `[Discurso inteiro]`); trocar de escopo não apaga a conversa.

### 2.5 Telemetria e cache

Dois turnos seguidos na mesma nota/escopo:

```text
1º: in_tokens=3741  out_tokens=327  cached_tokens=128
    history_turns=12 history_chars=6192 refs=2 inventory_chars=251
2º: in_tokens=3857  out_tokens=433  cached_tokens=2176
    history_turns=14 history_chars=7471 refs=2 inventory_chars=251
```

O `history_turns=14` era o **histórico bruto** do banco, não o que foi enviado.
A telemetria foi corrigida para `effectiveHistoryTurns` (janela + teto): o
turno seguinte, no mesmo chat, passou a reportar:

```text
generation|route=chat|budget=2048|finish_reason=stop|chars=1254|words=211|
truncated=false|hasCard=false|thinking=false|in_tokens=4072|out_tokens=376|
cached_tokens=640|history_turns=12|history_chars=8607|refs=2|inventory_chars=251
```

- Entrada típica ~3,7–4,1k tokens (bem dentro do alvo 8–12k).
- O prefixo estável foi cacheado (640–2.176 tokens conforme o turno).
- O inventário tem 251 chars e 2 refs nesta nota.

## 3. O que foi implementado

- `NoteRefsInventory` (puro): publicações, orientação (`be/th`), Bíblia,
  status (`resolvido`, `trecho não encontrado`, `edição ausente`,
  `baixando`, `indexando`), teto de 4.000 chars com prioridade para foco,
  não resolvidas e Bíblia.
- `ChatEngine.recentHistory`: 12 mensagens / teto total, cabeça+cauda
  (200+300), última pergunta do Copilot preservada, resposta curta recente
  nunca descartada; `chatBriefToText` inclui a última pergunta e a instrução
  de resposta curta.
- `ChatHistoryText`: `ideas/refs/sections/draft/proposal` viram texto legível;
  histórico no nível da nota com rótulo de foco.
- `answerCheckRefs` e `missingMatterText`: usam o coletor único, não só
  `_noteText` + `outlines.refsJson`; distinguem edição ausente de trecho ausente.
- `citedSources`: usa `RoomReferenceResolver` (aceita `PARTIAL`), ignora
  `be/th` como conteúdo e não descarta mais refs em silêncio; `unavailableDetails`
  leva o motivo ao prompt.
- `GenerationTelemetry`: `in_tokens`, `out_tokens`, `cached_tokens`,
  `history_turns`, `history_chars`, `refs`, `inventory_chars`; a janela vem
  de `effectiveHistoryTurns` (o que foi enviado, não o banco bruto).
- `NWT_NAME_TO_ABBREV`: abreviações canônicas também normalizam.
- **Referências = publicação + unidade:** `RefDetector` deduplica por unidade
  (não por edição) e reconhece `apêndice A da brochura Ame as Pessoas`;
  `NoteRefsCollector` remove a genérica quando há específica; `RefDisplay`
  monta “Ame as Pessoas — lição 9, ponto 5” e o card agrupa
  “Ame as Pessoas — 2 referências” com as duas unidades.

## 4. Pendências/limites

- O card determinístico de “Referências da nota” lista **publicações**; refs
  bíblicas aparecem no checklist “Fontes” e no inventário do prompt, não nesse
  card. Se o critério #2 exigir Bíblia no card, é uma mudança de UI (Fase 1
  não prevê UI).
- **Proposta pendente:** `_proposal` continua sendo estado de UI; para o
  histórico fiel falta persistir um summary legível (kind `proposal`) e
  atualizá-lo quando o card muda. O mapeamento de leitura já existe
  (`ChatHistoryText`), mas não há linha gravada ainda.
- O caso `w25.07 § 999` foi coberto pela regra pura e pelo prompt, não por uma
  nota real no tablet (exigiria inserir a ref numa nota — evitei alterar dados
  do usuário após um incidente de input; ver §5).
- **`lmd` não indexado no tablet:** o arquivo `lmd_T.jwpub` está `failed`;
  o card mostra a unidade e “Baixar no site”, mas não o trecho. Para validar
  o trecho resolvido de `lmd lição 7 ponto 4` / `lição 9 ponto 5` é preciso
  importar uma edição lmd válida na Biblioteca.
- O cache do 1º turno foi baixo (128 tokens) e subiu para 2.176 no 2º; o
  prefixo é cacheável, mas o primeiro turno de uma sessão paga o custo cheio.
- `recentHistory` usa ~4 chars/token como estimativa; o teto real de tokens do
  endpoint ainda não é medido por tokenizer.

## 5. Incidente de método (registro honesto)

Ao tentar digitar uma pergunta no chat, o app estava no **editor** (o toque
anterior não abriu o chat). O `input text` caiu no corpo da Introdução da nota
“# 192…”. O texto foi removido e a seção foi restaurada a partir de um
checkpoint do próprio banco; a nota foi conferida (“duas estradas parecem
iguais”) e o banco passou `PRAGMA integrity_check = ok`. Nenhuma outra nota foi
alterada. O trecho pré-existente “Jeov de de deá” já estava assim antes e não
foi tocado.

## 6. Reprodução

```bash
# APK
cd android && ./gradlew assembleDebug
adb -s adb-ij8tq8wojnhe7pem-y9MiYf._adb-tls-connect._tcp install -r -g \
  app/build/outputs/apk/debug/app-debug.apk

# log de contexto/custo (sem conteúdo do usuário)
adb -s adb-ij8tq8wojnhe7pem-y9MiYf._adb-tls-connect._tcp logcat -s CopilotLLM:I ChatRouter:I
```

**Commit de refs:** `5a9b2fe` (`fix(copilot): referências como publicação + unidade`),
CI [40349022999](https://github.com/guilhermemellojw/better-talker/actions/runs/40349022999) verde.

**Arquivos principais:** `NoteRefsInventory.kt`, `ChatHistoryText.kt`,
`RefDisplay.kt`, `ChatEngine.kt`, `CopilotRepository.kt` (`citedSources`),
`CopilotViewModel.kt` (`answerRemote`, `conversationTurnsScoped`),
`GenerationTelemetry.kt`, `ChatPromptBuilder.kt`, `RefDetector.kt`,
`NoteRefsCollector.kt`, `ChatScreen.kt` (`RefsBody`/`RefFullRow`).
