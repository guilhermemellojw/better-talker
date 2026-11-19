# TRUST-2B2 — Relatório de cobertura e lacunas

**Base:** `e822b83c1597c7e89cb8db5a7eb8d9a66057b15f`.  
**Natureza:** preparação experimental; nenhuma anotação humana ou fonte real foi utilizada.

## A. Baseline sintético B2B1 (preservado)

- Casos executáveis: 31.
- Macro-F1 mecânico: 0,7895.
- Falsos positivos: 0.
- Falsos negativos: `X-CONTRA-01`.
- O baseline não foi alterado.

## B. Dataset real preparado

| Métrica | Valor |
|---|---:|
| Casos pendentes inventariados | 48 |
| Casos preparados estruturalmente | 48 |
| Casos-piloto | 11 |
| Casos verificados com fonte real | 0 |
| Casos com anotação humana | 0 |
| Aguardando segunda anotação | 0 |
| Divergências | 0 |
| Cohen's κ | n/d (dados insuficientes) |
| Lacuna T2 | 15 IDs |

Cobertura por rota: `{CHAT=5, GLOBAL=6, SOURCE=5, CARD=6, DRAFT=5, VERIFIER=7, SOURCE_GATE=6, RAG=4, TRUST1A=4}`.

Cobertura por tipo: `{FACTUAL_BIBLICAL=27, ORATORY_TECHNIQUE=4, LITERAL_CITATION=6, FACTUAL_DOCTRINAL=2, USER_HYPOTHESIS=3, STRUCTURAL_EDITORIAL=6}`.

Campos ausentes: `{documentId=35, publication=35, edition=35, documentUnit=35, recoveredText=35, recoveryState=35, exposedToModel=35, premiseIds=39, humanAnnotation=48, methodDocumentId=4, methodUnit=4, methodText=4, userMessageId=3, userMessageText=3, structureDocumentId=6, structureUnit=6, contextText=6}`.

## C. Piloto — 11 objetivos, 0 verificados

| Objetivo | Caso(s) | Estado |
|---|---|---|
| Citação bíblica literal | C02 | NEEDS_SOURCE |
| Paráfrase correta | G02 | NEEDS_SOURCE |
| Paráfrase sem apoio | C10 | NEEDS_SOURCE |
| Afirmação contradita | C08 | NEEDS_SOURCE |
| Fonte ausente | G03 | NEEDS_SOURCE |
| Edição incorreta | G04 | NEEDS_SOURCE |
| Citação inventada | G05 | NEEDS_SOURCE |
| Interpretação que extrapola | C06 | NEEDS_SOURCE |
| be/th como prova doutrinal | G07 | NEEDS_SOURCE |
| Hipótese do usuário | G08 | NEEDS_ANNOTATION |
| Número/técnica em sugestão | C07 | NEEDS_ANNOTATION |

Nenhum cenário foi promovido a verificado, anotado ou executável por falta de fonte legítima local e de anotação humana real.

## D. Lacunas e causas

- Fontes reais não estão no repositório; conteúdo integral permanece no acervo local legítimo.
- Sem `documentId`, `documentUnit`, `edition`, `recoveredText` e `premiseIds`, não há avaliação factual possível.
- T2-01…T2-15 não têm especificação verificável na `main`.
- Não há dois anotadores humanos reais; κ não pode ser calculado.
- Não há NLI, embeddings, LLM ou integração às rotas nesta fase.

## E. Próximas decisões

1. Definir um fluxo legítimo de importação/consulta de fontes reais pelo usuário.
2. Escolher 3–5 casos-piloto com fonte real disponível e anotar com dois revisores.
3. Formalizar T2-01…T2-15 antes de usá-los.
4. Só então medir recall@k e testar NLI local.
5. Manter o baseline sintético separado do dataset real em todos os relatórios.
