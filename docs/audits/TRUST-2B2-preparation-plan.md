# TRUST-2B2 — Preparação para avaliação semântica com evidências reais

**Status:** infraestrutura experimental de preparação. Nenhuma verificação semântica em produção.  
**Base:** `e822b83c1597c7e89cb8db5a7eb8d9a66057b15f` (TRUST-2B1 integrada).  
**Código:** `android/app/src/test/java/com/bettertalker/app/eval/` (`Trust2B2*.kt`).  
**Escopo:** preparar os 48 casos pendentes, protocolo de anotação, validador e piloto. Não executar NLI/embeddings/LLM.

## 1. Inventário preparado

- 48 casos `NEEDS_ANNOTATION` do TRUST-2B1, com IDs originais preservados.
- 24 casos classificados semanticamente; 24 permanecem com classificação pendente.
- 11 casos-piloto prioritários: C02, C06, C07, C08, C10, G02, G03, G04, G05, G07, G08.
- 0 casos verificados com fonte real.
- 0 anotações humanas reais.
- 0 casos com segunda anotação.
- Lacuna T2-01…T2-15 mantida (15 IDs sem especificação verificável na `main`).

Cobertura por rota: CHAT 5, GLOBAL 6, SOURCE 5, CARD 6, DRAFT 5, VERIFIER 7, SOURCE_GATE 6, RAG 4, TRUST1A 4.

Cobertura por tipo classificado: FACTUAL_BIBLICAL 11, LITERAL_CITATION 6, FACTUAL_DOCTRINAL 2, USER_HYPOTHESIS 3, STRUCTURAL_EDITORIAL 2. Os 24 restantes são cenários técnicos com classificação PENDING, não claims bíblicas.

## 2. Campos de preparação

Cada caso preparado registra:

- `caseId`, rota e descrição;
- tipo de claim esperado;
- fonte de verdade exigida (`FACTUAL_DOCUMENT`, `METHOD`, `USER_CONTEXT`, `STRUCTURE`, `NONE`, `PENDING`);
- campos de evidência exigidos e campos ausentes;
- estado de preparação (`NEEDS_SOURCE`/`NEEDS_CLAIM`/`NEEDS_ANNOTATION`);
- disponibilidade da fonte (`NOT_AVAILABLE_IN_REPO`);
- critério de avaliação;
- necessidade de anotação humana;
- riscos de falso positivo/negativo;
- indicação de piloto.

Nenhum `documentId` fictício, texto bíblico ou parágrafo de publicação foi inventado. Sem fonte legítima local, o caso permanece pendente. `LOCAL_SOURCE_AVAILABLE` não equivale a `VERIFIED_LOCAL`.

## 3. Protocolo de anotação humana

Rótulos semânticos: `SUPPORTED`, `INSUFFICIENT`, `CONTRADICTED`, `UNVERIFIABLE`, `NOT_EVALUATED`.

Rótulos de fidelidade literal: `EXACT`, `NORMALIZED`, `NOT_FOUND`, `UNVERIFIABLE`, `NOT_EVALUATED`.

Regras:

- todo rótulo de suporte factual deve referenciar `premiseIds` e justificativa;
- anotação humana exige `annotatorId` real;
- dois anotadores independentes por amostra;
- Cohen's κ usa as distribuições marginais de cada anotador: `Pe = Σ P_A(c) × P_B(c)`;
- apenas um par consistente de revisores é usado por cálculo; pares adicionais são excluídos e documentados;
- divergências exigem terceiro anotador;
- anotação sintética/script nunca conta como humana;
- citação literal fiel não aprova interpretação factual;
- fonte disponível não prova que o Mentor a utilizou.

## 4. Validador experimental

`Trust2B2Validator` verifica:

- unicidade de `caseId`;
- claim factual exige `FACTUAL_DOCUMENT`;
- S-34/estrutura e be/th/método não podem ser prova factual;
- campos ausentes coerentes com os exigidos;
- `READY_FOR_REVIEW`/`VERIFIED` sem campos pendentes;
- evidência truncada ou não exposta ao modelo não elegível;
- anotação humana com identificação e justificativa;
- `SUPPORTED`/`CONTRADICTED` com premissa vinculada;
- anotações duplicadas por anotador;
- separação entre anotação humana e sintética;
- metadados de proveniência com `documentId`, `documentUnit`, `premiseId`, edição, recuperação, completude, truncamento e exposição;
- `premiseId` precisa existir nos metadados e a unidade precisa ser verificada.
- Elegibilidade segue `ClaimType.supportEvidence`: `DOCUMENTARY` para fatos, `METHOD` para oratória e `NONE` para citação literal, hipótese, criativo e estrutural.
- `SUPPORTED` não é permitido para `LITERAL_CITATION`, `USER_HYPOTHESIS`, `CREATIVE_SUGGESTION`, `STRUCTURAL_EDITORIAL` ou classificação PENDING.
- Citação literal usa apenas fidelidade (`EXACT`, `NORMALIZED`, `NOT_FOUND`, `UNVERIFIABLE`, `NOT_EVALUATED`), separada do suporte factual.
- `CONTRADICTED` só é permitido para claim factual com documento verificado; não se aplica a citação literal, hipótese, criativo, estrutural ou PENDING.

### 4.1 Cohen's κ

- Apenas `HUMAN_INDEPENDENT`.
- Um par consistente de revisores por cálculo.
- `Po = concordâncias / casos emparelhados`.
- `Pe = Σ P_A(c) × P_B(c)` usando as marginais de cada anotador.
- `κ = (Po − Pe) / (1 − Pe)`.
- `Pe = 1` → κ indefinido, sem NaN/infinito.
- Amostra < 2 casos do mesmo par → κ não calculado.
- Pares adicionais são excluídos e documentados, nunca misturados.

### 4.2 Proveniência

`Trust2B2EvidenceMetadata` registra `documentId`, `documentKind`, referência, edição, unidade, `premiseId`, recuperação, completude, truncamento, exposição, disponibilidade e origem. `SUPPORTED`/`CONTRADICTED` exigem premissa cadastrada e unidade documental verificada; flags isoladas não bastam. `LOCAL_SOURCE_AVAILABLE` não é `VERIFIED_LOCAL`.

## 5. Piloto

O piloto tem 11 casos históricos e objetivos corrigidos, todos ainda pendentes:

1. Citação literal inexistente/entre aspas — C02.
2. Número não autorizado dentro de sugestão — C06.
3. Contagem técnica “3 passos” em sugestão — C07.
4. Versículo citado na prosa normal — C08.
5. Fonte waived em pergunta ampla — C10.
6. Fonte disponível sem marcador [Fonte N] — G02.
7. Fonte disponível com frase de falta de acesso — G03.
8. Marcador [Fonte 5] fora do intervalo — G04.
9. Sem fontes com [Fonte 1] — G05.
10. be/th usada como prova doutrinal — G07.
11. Histórico com afirmação falsa — G08.

Propostas futuras sem ID histórico compatível (não reutilizam IDs): paráfrase correta, contradição explícita, edição incorreta e interpretação que extrapola.

## 6. Licenciamento

- Nenhuma publicação jw.org foi copiada para o repositório.
- Nenhuma fixture real de TNM foi redistribuída.
- O conteúdo integral deve permanecer no acervo local legítimo do usuário.
- Casos sem acesso legítimo permanecem pendentes.

## 7. Limitações

- 0 casos verificados e 0 anotações humanas reais nesta execução.
- Nenhuma métrica semântica real pode ser calculada; o relatório deriva limitações das métricas efetivas.
- Macro-F1 0,7895 continua sendo o baseline sintético B2B1, não desempenho real.
- T2-01…T2-15 permanecem sem especificação.
- Nenhuma integração com rotas, NLI, embeddings ou LLM.
