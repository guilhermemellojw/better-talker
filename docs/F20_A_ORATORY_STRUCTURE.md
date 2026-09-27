# F20-A — Estrutura oratória inferida

> **CRIAÇÃO NA FORMA; FIDELIDADE NO CONTEÚDO.**

## 1. Estrutura original × estrutura inferida

```text
OutlineDocument           → estrutura REAL do S-34      (não muda aqui)
InferredOratoryStructure  → interpretação oratória derivada
```

A segunda é **derivada**: não altera a primeira, não é persistida (pode ser
reconstruída do `OutlineDocument` a qualquer momento) e **nunca gera
texto**. Esta fase planeja; a geração textual é a F20-B.

Implementação (espelhada): `OratoryStructure.kt` / `oratoryStructure.ts`,
funções puras `inferOratoryStructure(document, currentSectionId)` — sem
rede, LLM, Firebase ou UI.

## 2. Introdução — INFERIDA

```text
INTRODUCTION
  purpose  = objective (source S34) | MISSING
  section  = primeiro ponto do S-34
  training = [introduction, questions, clarity, naturalness]
```

O S-34 **não precisa** ter uma seção chamada "INTRODUÇÃO". A introdução é a
interpretação oratória de `objetivo + primeiro ponto`.

## 3. Desenvolvimento — PROJEÇÃO DIRETA (não inferência)

```text
DEVELOPMENT = seções do S-34, na ordem documental exata
  (sectionId, order, title, nº de subpontos, nº de referências)
```

Nunca reordena, funde, cria ou antecipa pontos. É a única parte que não é
"inferida" — é a sequência real.

## 4. Conclusão — INFERIDA

```text
CONCLUSION
  purpose  = objective (source S34) | MISSING
  section  = último ponto do S-34
  training = [conclusion, application, clarity, naturalness]
```

## 5. Papel do objetivo

`objective` é a base conceitual de abertura e fechamento. Quando o S-34 não
declara objetivo, `purpose.source = MISSING` e `text = null` — nunca
inventado. A estrutura continua válida usando os pontos disponíveis.

## 6. Papel do primeiro/último ponto

- primeiro ponto → `introduction.section`
- último ponto → `conclusion.section`
- com **um único** ponto, ele é os dois ao mesmo tempo (estrutura válida,
  sem exceção e sem estrutura falsa).

## 7. Papel do BE/TH

BE/TH entra **apenas** como `trainingSources` (categorias da taxonomia
existente). Nenhuma técnica é escolhida como "melhor": não há score,
ranking, `bestTechnique` ou `winner`. Não há cópia de texto de treinamento —
só a categoria.

## 8. current / previous / next

Quando a B.4 resolve um ponto, a estrutura expõe:

```text
focus.previousSectionId / currentSectionId / nextSectionId
```

Extremos ficam `null`. Quando a B.4 devolve `UnknownSection`,
`UnmatchedHint` ou `DocumentScope`, **não há foco** — nunca inventado.

## 9. Casos sem objetivo

`purpose.source = MISSING`, `text = null`. Nada de objetivo fabricado.

## 10. Casos sem estrutura

`sections.isEmpty()` → `Result.InsufficientStructure`. Nenhuma
introdução/conclusão artificial. `NoOutline` nem chega aqui: sem
`OutlineDocument` a estrutura não é criada (caminho legado intacto).

## 11. Limitações

- Não gera texto: nenhuma frase de abertura, transição ou fechamento (por
  decisão).
- Não persiste: é derivada em memória (evita duplicar interpretação no
  banco).
- Transições são apenas *possíveis* (`current/previous/next` + categoria
  `transition`); a frase fica para a F20-B.
- BE/TH ainda não é recuperado do acervo aqui: a estrutura aponta a
  **categoria**, e a seleção concreta de trechos segue no retrieval de
  treinamento já existente.
- Introdução/conclusão não substituem o que o S-34 diz: o conteúdo factual
  permanece S-34/Bíblia/publicações.

## 12. Integração mínima

- ContextPack/prompt: bloco `--- ESTRUTURA ORATÓRIA INFERIDA (INFERIDA A
  PARTIR DO S-34) ---` logo após a estrutura do S-34, com aviso de que é
  organização derivada.
- Prompt: uma regra (`ORATORY_STRUCTURE_RULES`) — derivada, não adiciona
  conteúdo factual; BE/TH só para COMO apresentar.
- Campos opcionais e retrocompatíveis: `LlmRequest.oratory`,
  `buildChatPrompt(..., oratory)`; sem estrutura, o prompt é idêntico ao
  anterior.
