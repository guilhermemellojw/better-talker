# TRUST-2B2 — Preparação para avaliação semântica com evidências reais

**Status:** infraestrutura experimental de preparação. Nenhuma verificação semântica em produção.  
**Base:** `e822b83c1597c7e89cb8db5a7eb8d9a66057b15f` (TRUST-2B1 integrada).  
**Código:** `android/app/src/test/java/com/bettertalker/app/eval/` (`Trust2B2*.kt`).  
**Escopo:** preparar os 48 casos pendentes, protocolo de anotação, validador e piloto. Não executar NLI/embeddings/LLM.

## 1. Inventário preparado

- 48 casos `NEEDS_ANNOTATION` do TRUST-2B1, com IDs originais preservados.
- 11 casos-piloto prioritários: C02, C06, C07, C08, C10, G02, G03, G04, G05, G07, G08.
- 0 casos verificados com fonte real.
- 0 anotações humanas reais.
- 0 casos com segunda anotação.
- Lacuna T2-01…T2-15 mantida (15 IDs sem especificação verificável na `main`).

Cobertura por rota: CHAT 5, GLOBAL 6, SOURCE 5, CARD 6, DRAFT 5, VERIFIER 7, SOURCE_GATE 6, RAG 4, TRUST1A 4.

Cobertura por tipo esperado: FACTUAL_BIBLICAL 27, ORATORY_TECHNIQUE 4, LITERAL_CITATION 6, FACTUAL_DOCTRINAL 2, USER_HYPOTHESIS 3, STRUCTURAL_EDITORIAL 6.

## 2. Campos de preparação

Cada caso preparado registra:

- `caseId`, rota e descrição;
- tipo de claim esperado;
- fonte de verdade exigida (`FACTUAL_DOCUMENT`, `METHOD`, `USER_CONTEXT`, `STRUCTURE`, `NONE`);
- campos de evidência exigidos e campos ausentes;
- estado de preparação (`NEEDS_SOURCE`/`NEEDS_ANNOTATION`);
- disponibilidade da fonte (`NOT_AVAILABLE_IN_REPO`);
- critério de avaliação;
- necessidade de anotação humana;
- riscos de falso positivo/negativo;
- indicação de piloto.

Nenhum `documentId` fictício, texto bíblico ou parágrafo de publicação foi inventado. Sem fonte legítima local, o caso permanece pendente.

## 3. Protocolo de anotação humana

Rótulos semânticos: `SUPPORTED`, `INSUFFICIENT`, `CONTRADICTED`, `UNVERIFIABLE`, `NOT_EVALUATED`.

Rótulos de fidelidade literal: `EXACT`, `NORMALIZED`, `NOT_FOUND`, `UNVERIFIABLE`, `NOT_EVALUATED`.

Regras:

- todo rótulo de suporte factual deve referenciar `premiseIds` e justificativa;
- anotação humana exige `annotatorId` real;
- dois anotadores independentes por amostra;
- Cohen's κ só é calculado com casos suficientes e dois anotadores independentes;
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
- separação entre anotação humana e sintética.

## 5. Piloto

O piloto tem 11 casos e 11 objetivos, todos ainda pendentes de fonte real:

1. Citação bíblica literal — C02.
2. Paráfrase correta — G02.
3. Paráfrase sem apoio — C10.
4. Afirmação contradita — C08.
5. Fonte ausente — G03.
6. Edição incorreta — G04.
7. Citação inventada — G05.
8. Interpretação que extrapola — C06.
9. be/th como prova doutrinal — G07.
10. Hipótese do usuário — G08.
11. Número/técnica em sugestão — C07.

## 6. Licenciamento

- Nenhuma publicação jw.org foi copiada para o repositório.
- Nenhuma fixture real de TNM foi redistribuída.
- O conteúdo integral deve permanecer no acervo local legítimo do usuário.
- Casos sem acesso legítimo permanecem pendentes.

## 7. Limitações

- 0 casos verificados e 0 anotações humanas reais.
- Nenhuma métrica semântica real pode ser calculada.
- Macro-F1 0,7895 continua sendo o baseline sintético B2B1, não desempenho real.
- T2-01…T2-15 permanecem sem especificação.
- Nenhuma integração com rotas, NLI, embeddings ou LLM.
