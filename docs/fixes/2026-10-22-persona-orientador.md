# v2.6.0 — Persona “orientador” + princípios de oratória (BE/TH condensados)

**Data:** 2026-10-22  
**Branch:** `fix/persona-orientador` (a partir de `origin/main` v2.5.4)  
**Versão:** `versionCode 19` / `versionName "2.6.0"`  
**Status:** implementado; testes JVM verdes; A/B manual no A34 realizado (com ressalvas de escopo).

---

## 1. O que mudou

### 1.1. Identidade: “mentor/irmão” → “orientador”

| Arquivo | Antes | Depois |
|---|---|---|
| `ChatPromptBuilder.kt` (`CHAT_PERSONA_BASE`) | “Você é um **mentor** e especialista em oratória, ajudando **irmãos**…” | “Você é um **orientador experiente** em oratória, ajudando **oradores**…” |
| `ChatPromptBuilder.kt` | “como um **irmão experiente** conversando e guiando outro” | “como um **instrutor que caminha ao lado do orador** na preparação” |
| `ChatPromptBuilder.kt` (`GENERATION_VOICE_LINE`) | “tom caloroso e direto de um **irmão experiente**” | “tom caloroso e direto de um **orientador experiente que caminha ao lado do orador**” |
| `GenerationPrompts.kt` (`SPEECH_MENTOR`) | “Você é um **mentor** experiente de oratória e **exegese bíblica**” | “Você é um **orientador** experiente de oratória e **estudo bíblico**” |
| `GenerationPrompts.kt` (`SPEECH_MENTOR`) | “como um **irmão experiente** sentado ao lado do orador” | “como um **instrutor experiente** que está ali para ajudar o orador a dar o seu melhor” |
| `GenerationPrompts.kt` (`DEMO_SCRIPTWRITER`) | “como um **superintendente** treinando publicadores” | “como um **instrutor de campo** acompanhando o publicador no treino” |
| Hints | “## MODO **MENTOR** (…)” | “## MODO **ORIENTADOR** (…)” |

**Linha de identidade adicionada ao `CHAT_PERSONA_BASE`:**

```text
Você é uma ferramenta de orientação: não se apresente como membro ou representante da organização; trate o usuário por 'você' ou 'orador'.
```

> A formulação evita a palavra “irmão” no próprio texto da persona, permitindo o teste `chat_persona_free_nao_menciona_irmao`. Ela também bloqueia o vocativo “Boa, irmão”.

### 1.2. Remoção de “exegese” do corpo do `SPEECH_MENTOR`

“Exegese” está na lista **EVITE** do `LANGUAGE_PRINCIPLES`. O corpo da persona agora usa “estudo bíblico”.

**Importante:** o guia apensado continua listando “exegese” em EVITE e “superintendente” em PRESERVE, por design. Por isso, os testes de persona checam o **corpo antes do guia** (`substringBefore("## DIRETRIZES DE LINGUAGEM")`), não o prompt inteiro. `LANGUAGE_PRINCIPLES` **não foi alterado**.

### 1.3. Princípios de oratória condensados (BE/TH)

Em vez de listar 20 lições, a persona recebeu **3 eixos** (o “DNA” do BE/TH), porque 2 dos 5 princípios propostos já estão parcialmente cobertos pelo `LANGUAGE_PRINCIPLES`.

**`SPEECH_MENTOR`:**

```text
Ao orientar, aplique estes princípios de oratória (baseados nas publicações de treinamento das Testemunhas de Jeová):

1. ESTRUTURA E PROGRESSÃO: introdução que prende, corpo com 3-4 pontos claros que se encadeiam, conclusão que aplica e fecha o ciclo aberto na introdução. O esboço é a base; o texto vem depois.
2. ENSINO E APLICAÇÃO: ideia central nítida, raciocínio que leva o ouvinte, ilustração que ensina (não só decora), pergunta que faz pensar, aplicação que toca o coração. Clareza e vocabulário acessível são parte do ensino.
3. ENTREGA: entonação que varia (não monótona), convicção que transparece, simpatia que cria conexão, animação que contagia. O texto não é para ser lido — é para ser falado.

Adapte os princípios ao contexto: discurso, demonstração ou parte curta. Não imponha regra rígida; use o princípio que o caso pede.
```

**`DEMO_SCRIPTWRITER` (adaptado ao campo):**

```text
Ao orientar, aplique estes princípios de oratória adaptados ao ministério de campo:

1. ESTRUTURA DA CONVERSA: começo que prende, desenvolvimento com 2-3 ideias, conclusão com convite claro. Não é discurso: é conversa.
2. ENSINO NO DIÁLOGO: ideia central clara, pergunta que faz pensar, ilustração curta, aplicação prática; responda a objeções com tato.
3. ENTREGA NATURAL: falas curtas, tom conversacional, empatia e convicção sem pressão. Ninguém decora um diálogo na porta.
```

---

## 2. Mapeamento BE/TH → princípios

| Princípio | Fonte BE/TH | Já no `LANGUAGE_PRINCIPLES`? | Decisão |
|---|---|---|---|
| **Estrutura / progressão** | BE pp. 39-55 (esboço, discursos dirigidos, discursos públicos); BE Lição 25 (166-169); TH 1, 14, 20 | **Não** | Entrou como eixo 1 |
| **Clareza** | BE Lições 41-42 (226-233); TH 17 | **Parcial** (frases curtas, voz ativa, vocabulário acessível) | Absorvido no eixo 2, sem duplicar |
| **Ensino / aplicação** | BE pp. 56-70 (ensinar, conversar, responder); BE Lições 42-53 (230-271); TH 3-4, 8-9, 13, 18-19 | **Parcial** (exemplos do cotidiano, aplicação prática no fim) | Entrou como eixo 2, com “ilustração que ensina” e “pergunta que faz pensar” |
| **Entrega** | BE Lições 1-9 (83-114); TH 10-12, 15 | **Não** | Entrou como eixo 3 |
| **Progressão** | BE Lição 25 (166-169); TH 1, 14, 20 | **Não** | Absorvido no eixo 1 |
| **Adaptação ao contexto** | S-38 / dossiê | **Não** no guia | Linha de meta-princípio |

**Por que 3 e não 5:** clareza e aplicação já aparecem no guia; repeti-las inflaria o prompt sem ganho. Os 3 eixos cobrem o que falta: estrutura/progressão, ensino que ensina (não decora) e entrega falada.

---

## 3. Tamanho do prompt

Medição exata em JVM (teste temporário de probe, removido depois):

| Bloco | Antes dos princípios | Depois | Δ |
|---|---:|---:|---:|
| `SPEECH_MENTOR` (corpo + guia) | ~2.952 | **3.828** | +876 (+29,7%) |
| `DEMO_SCRIPTWRITER` (corpo + guia) | ~3.218 | **3.720** | +502 (+15,6%) |
| `CHAT_PERSONA_FREE` | ~1.200 | **1.232** | ~+30 |
| `CHAT_PERSONA` | ~2.660 | **2.690** | ~+30 |
| `LANGUAGE_PRINCIPLES` | 1.693 | **1.693** | 0 (inalterado) |

**Teto adotado:** `< 5.000` chars por persona de geração. Teste `prompt_nao_ultrapassa_5000_chars` garante o teto. Ambos passam com folga.

---

## 4. Testes

### 4.1. Novos

- `chat_persona_free_nao_menciona_irmao`
- `chat_persona_free_usa_orientador`
- `personaNaoSeApresentaComoMembro`
- `generationVoiceLineNaoMencionaIrmao`
- `speech_mentor_nao_menciona_irmao`
- `speech_mentor_nao_menciona_exegese` (corpo, sem o guia)
- `demo_scriptwriter_nao_menciona_irmao`
- `demo_scriptwriter_nao_menciona_superintendente` (corpo, sem o guia)
- `hintsUsamModoOrientador`
- `speech_mentor_inclui_principios_de_oratoria`
- `demo_scriptwriter_inclui_principios_adaptados`
- `prompt_nao_ultrapassa_5000_chars`

### 4.2. Atualizados

- `OratoryProposalFlowTest.kt:77` — “mentor” → “orientador”.
- `GenerationPromptsTest.kt` — asserções de “mentor”/“exegese” e “superintendente” ajustadas ao corpo da persona.
- `DossierPromptBuilderPersonaTest.kt` — “MODO MENTOR” → “MODO ORIENTADOR”.

### 4.3. Resultado

```
2186 tests completed, 0 failed
BUILD SUCCESSFUL
```

---

## 5. A/B no A34

**Antes:** app instalado v2.5.4 (versionCode 18).  
**Depois:** APK debug v2.6.0 (versionCode 19) instalado por cima; dados preservados.

### 5.1. Pergunta

> “Me ajude a preparar a introdução do meu discurso sobre contentamento”

**Antes (v2.5.4, escopo Introdução):**

> BT · Vamos fechar essa introdução, **irmão**. A ideia já está de pé — cena do dia a dia, pensamento contido, e o gancho para o segredo. Agora é só deixar a fala pronta para você decorar e apresentar.
>
> Um ajuste de estratégia antes: em 1 minuto você tem cerca de 130 palavras. Então a introdução precisa ser enxuta — cena curta, uma pergunta que faça o auditório se reconhecer, e uma frase que abra o discurso. Nada além disso.

**Depois (v2.6.0, escopo Discurso inteiro):**

> BT · Boa! Vamos preparar essa introdução com calma. Antes de escrever, deixa eu te mostrar o que já temos e a estratégia que sugiro.
>
> O bloco em foco já traz uma linha muito boa: Baruque insatisfeito, o risco de perder a alegria e a solução de Jeová — dar esperança. Isso é ouro para uma introdução de 1 minuto, porque cria curiosidade e já aponta para o tema.

**Depois — continuidade (conclusão):**

> BT · Boa — vamos fechar essa ponte entre a introdução e a conclusão. Deixa eu te orientar antes de escrever qualquer coisa.
>
> A conclusão de 1 minuto tem uma tarefa só: fazer o ouvinte sair com uma ação na cabeça. Não é hora de trazer informação nova nem de reabrir a história de Baruque. É hora de recolher o que já foi plantado e apontar o próximo passo.
>
> O risco mais comum aqui é a conclusão virar um resumo longo. Com cerca de 130 palavras, você tem espaço para três movimentos curtos: relembrar o segredo (esperança gera contentamento), virar para o ouvinte com uma pergunta ou convite prático, e encerrar com calor, sem pressa.

### 5.2. Critérios

| Critério | Antes (v2.5.4) | Depois (v2.6.0) | Status |
|---|---|---|---|
| Usa “irmão” como vocativo/identidade? | Sim (“Vamos fechar…, irmão”) | **Não** nas amostras | ✅ |
| Tom caloroso/encorajador? | Sim | Sim (“Boa! Vamos preparar com calma”, “Isso é ouro”) | ✅ |
| Orientação prática? | 130 palavras, cena, pergunta, gancho | Conteúdo do bloco, 1 min, curiosidade/tema; conclusão em 3 movimentos | ✅ |
| Aplica princípio de oratória? | Implícito | Explícito na conclusão (3 movimentos, calor sem pressa) e no adversarial | ✅ parcial/explícito |
| Prompt < 5.000 chars? | — | 3.828 / 3.720 | ✅ |

**Ressalva metodológica:** o “antes” foi capturado no escopo **Introdução** (histórico já existente); o “depois” foi capturado no escopo **Discurso inteiro** para evitar contaminação do histórico. O teste de conclusão e o adversarial são continuações no mesmo chat. Não é um A/B perfeitamente controlado de mesmo escopo, mas é suficiente para os critérios de identidade/tom.

### 5.3. Teste adversarial

> “Diferença entre ilustração que decora e ilustração que ensina”

**Depois (v2.6.0, modo Profundo):**

> BT · Boa — e essa pergunta fica ainda mais afiada quando pensamos na conclusão. Deixa eu te dar um jeito de testar isso na prática.
>
> A ilustração que decora vive de si mesma. Você pode tirá-la da fala e nada se perde no argumento. O ouvinte lembra da imagem e esquece o ponto. A ilustração que ensina carrega o argumento nas costas: se você a tira, o raciocínio fica capenga.

**Resultado:** o modelo aplicou o eixo 2 (ENSINO E APLICAÇÃO) sem inventar página/lição. ✅

---

## 6. O que ficou bloqueado para o v2.7.0

| Item | Motivo |
|---|---|
| Injeção de trechos BE/TH via RAG | Acervo do A34 com `be`/`th` `indexed=0`, `status=failed`; passagens antigas com `page`/`paragraph` NULL; arquivos originais ausentes. |
| Guia genérico de citações BE/TH | Depende do reindex para o modelo ver o trecho. |
| Mapa S-38 → BE/TH | Depende do reindex para o LLM ver o trecho; o mapa pode ser estático, mas a citação precisa de fonte. |
| Query expansion (`inferIntent` + `enrichedRetrievalQuery`) | Depende do reindex; sem fontes indexadas, não há o que recuperar. |

### Pré-requisitos do v2.7.0

1. Reimportar `be` e `th` do jw.org (ou de `.jwpub` de teste).
2. Reindexar com `page`/`paragraph` (o parser já suporta JWPUB; o BE EPUB não tem páginas).
3. Validar `baseReady("be")` e `baseReady("th")` não-nulos.
4. Só então injetar páginas/lições no prompt.

---

## 7. Correção factual (do Discovery)

- **BE pp. 52-55** = “Como preparar discursos públicos” (preparação), **não** leitura/ensino.
- **BE pp. 166-169** = **Lição 25 “Uso de esboço”** (falar com base em esboço), **não** leitura/ensino.
- **TH** = 20 lições de leitura e ensino (brochura inteira) — correto.
- **Para o restante (ensino, aplicação, conclusão, entrega): TH** (20 lições). O BE só entra nas lacunas que a TH não cobre: **39-42** (elaborar esboço), **47-51** (discurso dirigido à congregação), **52-55** (discursos públicos), **166-169** (uso de esboço), **251** (raciocínio) e **263** (tempo).
- **Conclusão:** BE 220-222 está 100% superado pela TH 20 — não citar.

---

## 8. Decisões em aberto

1. **“Mentor” nos nomes internos:** mantido em `GenerationProfile.SPEECH_MENTOR` e constantes; não vai ao texto enviado (exceto nomes de constantes). Renomear é refactor opcional.
2. **Vocativo “irmão”:** a persona proíbe autoidentidade e orienta “você/orador”; se o produto quiser permitir o vocativo comunitário, decidir explicitamente.
3. **Páginas no prompt:** só depois do reindex; agora a persona usa princípios, não páginas.
4. **A/B de mesmo escopo:** repetir com chat limpo (ou novo tópico) para eliminar a ressalva metodológica.
5. **3 vs 5 eixos:** o **v2.7.0 adotou 5 eixos** (Estrutura, Ensino, Aplicação, Conclusão, Entrega) — aplicação e conclusão são eixos distintos.
6. **`LANGUAGE_PRINCIPLES`:** corrigido no v2.7.0 — “Aplicação prática no fim” → “ao longo do discurso (não só no fim)”. “Exegese” (EVITE) e “superintendente” (PRESERVE) permanecem.

---

## 9. v2.7.0 — Opção B: persona enxuta (substituição, não adição)

**Decisão (23/10):** em vez de adicionar 5 eixos + 5 regras + 3 fallbacks (~15.000 chars),
**substituir** a persona por identidade + 3 princípios + flexibilidade (~1.300 chars).

| Bloco | v2.6.0 | v2.7.0 (Opção B) |
|---|---|---|
| `SPEECH_MENTOR` | ~3.800 (3 eixos + guia) | **1.336** |
| `DEMO_SCRIPTWRITER` | ~3.700 | **1.306** |
| `LANGUAGE_PRINCIPLES` | 1.799 (empilhado) | **removido do prompt** (termos preservados na persona/chat base) |
| Fontes BE/TH | no system | **no dossiê** (`ORATORY_SOURCES_BLOCK`), quando há seção de oratória |
| `CHAT_TAIL_INSTRUCTIONS` | ~1.5k | **mantido** — é o contrato de saída do app (marcadores), não persona |

**O que a persona enxuta mantém:** identidade “orientador formado na Escola”, sem
“irmão”/autoidentidade; 3 princípios (estrutura, ensino, entrega); “modulação”
(variação de volume, emoção e ritmo); conclusão que recapitula e incentiva (≠ aplicação);
flexibilidade (“lente, não grade”); `<texto-do-card>`; não inventar.

**O que foi deliberadamente adiado:** 5 eixos detalhados, regras anti-engessamento,
fallbacks, citação literal de páginas no system (depende do reindex) e a remoção do
`CHAT_TAIL_INSTRUCTIONS` (quebraria o protocolo de marcadores do app).

### A/B (tablet, escopo Introdução, chats novos)

| Critério | v2.6.0 | v2.7.0 enxuta |
|---|---|---|
| Natural, sem jargão de framework | parcial | **sim** |
| “irmão” | não | não |
| Conclusão ≠ aplicação | — | parcial (recapitula + incentiva; usa “aplicação” no fecho) |
| “entonação” | — | **não usou** (“volume, ritmo, tom e pausas”) |
| Fonte BE/TH | — | **correta**: BE 39-42/47-51/52-55/166-169 para estrutura; TH para o restante |
| Título da TH | — | **residual**: ainda disse “Faça o Melhor Uso da Leitura e do Ensino” (inexistente) — limitação do modelo |

**Suíte:** 2.185 / 0 falhas · **personas:** 1.336 / 1.306 chars (< 2.000).
