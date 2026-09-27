# F20-B — Geração oratória guiada por estrutura + BE/TH

> **CRIATIVIDADE NA FORMA; FIDELIDADE NO CONTEÚDO.**

## 1. Modos de geração

```text
INTRODUCTION   abertura
DEVELOPMENT    desenvolvimento de um ponto
TRANSITION     ponte entre dois pontos
CONCLUSION     fechamento
```

Implementação espelhada: `OratoryGeneration.kt` / `oratoryGeneration.ts`
(`OratoryGeneration` / `oratoryGeneration`), pura: sem rede, LLM, banco, UI.
Detecção a partir da linguagem natural (`detectMode` / `detectOratoryMode`) —
sem formulário obrigatório.

## 2. Fontes por modo

| Modo | Conteúdo (O QUE FALAR) | Treinamento (COMO APRESENTAR) |
|---|---|---|
| INTRODUCTION | `objective` + **primeiro** ponto | introduction, questions, clarity, naturalness |
| DEVELOPMENT | ponto **atual** (corpo, subpontos, refs) + anterior/seguinte como posição | development, explanation, illustration, application |
| TRANSITION | ponto atual + ponto **seguinte** (as duas ideias reais) | transition, clarity, naturalness |
| CONCLUSION | `objective` + **último** ponto | conclusion, application, clarity, naturalness |

A ordem completa do S-34 (`1→2→3`) acompanha todos os modos.

## 3. Papel do S-34

Fornece a **estrutura**: objetivo, pontos, subpontos, ordem, referências
vinculadas. O LLM nunca precisa adivinhar — a estrutura chega pronta
(F20-A/B.4) e é imutável: a geração **não** altera o `OutlineDocument`.

## 4. Papel do BE/TH

Entra **apenas** como `[TRAINING]` (categorias da taxonomia existente), com
o cabeçalho `--- TREINAMENTO (COMO APRESENTAR, NÃO É FATO) ---`. Nenhuma
categoria vira fonte de conteúdo, e nenhuma técnica é escolhida como
"melhor".

## 5. Criatividade

Permitida para formulações, perguntas, conexões e ilustrações — sempre
apresentada como sugestão, **nunca** como fato vindo das fontes. O prompt
diz isso explicitamente (regra 7).

## 6. EditProposal (F5)

A geração entra no pipeline existente: o provider recebe
`LlmRequest.oratorySpec` e usa o **prompt especializado**; a resposta
(JSON em cerca) passa pelo `parseEditProposal` de sempre → proposta com
`baseHashes` → preview ANTES/DEPOIS → aceitar/rejeitar. Nunca há escrita
direta no editor.

Alvo resolvido por modo:

| Modo | INSERT | REPLACE |
|---|---|---|
| INTRODUCTION | depois de `sec-1` | `sec-1` |
| DEVELOPMENT | depois do ponto atual | ponto atual |
| TRANSITION | depois do ponto atual | ponto atual |
| CONCLUSION | depois do último | último |

`ReplaceSection` só quando o pedido é de melhoria ("melhore a introdução");
"crie uma introdução" é sempre `insert` — nunca substitui conteúdo existente.

## 7. F6

Inalterado. A proposta gerada passa pelo verificador existente quando o
usuário pede verificação; nada é promovido a `supported` automaticamente e
`creative` continua distinto de afirmação factual sem apoio.

## 8. Insuficiência

- Sem S-34 → caminho legado (nada de estrutura nem de geração oratória).
- Sem ponto atual em DEVELOPMENT/TRANSITION → estado explícito com mensagem
  amigável (`no-current-section`).
- TRANSITION no primeiro ponto → `no-previous-section`; no último →
  `no-next-section`.
- Referência presente sem texto no acervo → o prompt diz
  `texto NÃO disponível; não invente`, e a regra 6 manda usar a frase de
  insuficiência em vez de preencher a lacuna.

## 9. Referências sem texto

O `rawText` do parser é a LINHA inteira do S-34, então o texto autorizado é
casado por chave contida (`"Tiago 2:17"` dentro de `"Leia Tiago 2:17."`).
Sem correspondência → `text = null` e o aviso explícito no prompt.

## 10. Limites

Limite de palavras por modo (decisão registrada, não arbitrária):
introduction 140 · development 320 · transition 70 · conclusion 140.
Abertura e fechamento são curtos; o desenvolvimento é o corpo do ponto; a
transição é uma ponte.

## 11. Blindagem contra injeção

O conteúdo do S-34 é **dado**, nunca instrução. A regra 10 diz isso, e
`looksLikeInjection` / `looksLikeOratoryInjection` detecta tentativas
("ignore as regras", "crie um ponto 4") para teste — a estrutura permanece
a do S-34.

## 12. Isolamento

- DEVELOPMENT recebe **só** o ponto atual: o ponto seguinte não é renderizado
  no prompt (isso vazaria referências do ponto 3 para dentro do ponto 2).
  Só TRANSITION recebe o seguinte.
- `S34-A/sec-2` nunca traz conteúdo de `S34-B` nem de outro ponto.

## 13. Exemplo (fixture)

Modo DEVELOPMENT no ponto 2:

```text
REGRAS DE GERAÇÃO ORATÓRIA ...
--- ESTRUTURA DO S-34 (INFERIDA, NÃO MUDA) ---
[S34] Objetivo: Mostrar como a fé pode ser fortalecida...
[S34] Pontos na ordem: 1. ... 2. ... <= FOCO  3. ...
[S34] Ponto em foco (2) "A fé cresce..."
[S34]   Subponto 1: a) Estudar regularmente
[S34]   Subponto 2: b) Aplicar o que aprendemos
[BIBLE] Leia Tiago 2:17. — texto NÃO disponível; não invente.
[PUBLICATION] w24.02 — texto NÃO disponível; não invente.
--- TREINAMENTO (COMO APRESENTAR, NÃO É FATO) ---
[TRAINING] development, explanation, illustration, application
MODO: DESENVOLVIMENTO DO PONTO. ...
Limite aproximado: 320 palavras.
PEDIDO DO USUÁRIO: "Desenvolva o ponto 2"
```

## 14. Nota sobre o caminho legado de proposta

O pipeline de proposta por `editMode` (F18) continua funcionando quando não
há `oratorySpec`. As duas rotas coexistem no mesmo provider.

## 15. Testes LLM reais

**NOT_EXECUTED** nesta fase: nenhuma chave de API foi configurada no
ambiente e a regra da fase proíbe criar/inserir segredo. Toda a validação
foi determinística (fixture sintética + provider fake capturando o corpo
HTTP).

## 16. Limitações

- Não gera o discurso inteiro automaticamente: a geração é por parte.
- Sem streaming, sem memória nova, sem novo Content Guard.
- O alvo da proposta é resolvido por `sectionId` (B.4) e pela estrutura
  (F20-A); sem alvo determinável, a fase devolve estado amigável em vez de
  proposta inválida.
- `contentSources` (Bíblia/publicações com texto) fica vazio quando o acervo
  não tem o trecho — o insumo real dessas fontes continua no ContextPack
  montado pelo retrieval.
