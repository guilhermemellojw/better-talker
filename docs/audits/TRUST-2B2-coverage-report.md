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
| Casos classificados | 24 |
| Casos com classificação pendente | 24 |
| Casos-piloto | 11 |
| Casos verificados com fonte real | 0 |
| Casos com anotação humana | 0 |
| Aguardando segunda anotação | 0 |
| Divergências | 0 |
| Cohen's κ | n/d (dados insuficientes) |
| Lacuna T2 | 15 IDs |

Cobertura por rota: `{CHAT=5, GLOBAL=6, SOURCE=5, CARD=6, DRAFT=5, VERIFIER=7, SOURCE_GATE=6, RAG=4, TRUST1A=4}`.

Cobertura por tipo classificado: `{FACTUAL_BIBLICAL=11, LITERAL_CITATION=6, FACTUAL_DOCTRINAL=2, USER_HYPOTHESIS=3, STRUCTURAL_EDITORIAL=2}`. Os 24 restantes são cenários técnicos com classificação PENDING.

Campos ausentes: `{documentId=19, publication=19, edition=19, documentUnit=19, recoveredText=19, recoveryState=19, exposedToModel=19, premiseIds=19, humanAnnotation=48, claimClassification=24, evaluationCriterion=24, userMessageId=3, userMessageText=3, structureDocumentId=2, structureUnit=2, contextText=2}`.

## C. Piloto — 11 objetivos, 0 verificados

| Objetivo | Caso(s) | Estado |
|---|---|---|
| Citação literal inexistente/entre aspas | C02 | NEEDS_SOURCE |
| Número não autorizado dentro de sugestão | C06 | NEEDS_CLAIM |
| Contagem técnica “3 passos” em sugestão | C07 | NEEDS_CLAIM |
| Versículo citado na prosa normal | C08 | NEEDS_SOURCE |
| Fonte waived em pergunta ampla | C10 | NEEDS_SOURCE |
| Fonte disponível sem marcador [Fonte N] | G02 | NEEDS_CLAIM |
| Fonte disponível com frase de falta de acesso | G03 | NEEDS_CLAIM |
| Marcador [Fonte 5] fora do intervalo | G04 | NEEDS_CLAIM |
| Sem fontes com [Fonte 1] | G05 | NEEDS_CLAIM |
| be/th usada como prova doutrinal | G07 | NEEDS_SOURCE |
| Histórico com afirmação falsa | G08 | NEEDS_ANNOTATION |

Nenhum cenário foi promovido a verificado, anotado ou executável por falta de fonte legítima local e de anotação humana real. Propostas futuras de paráfrase, contradição, edição incorreta e interpretação extrapolada não reutilizam IDs históricos.

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
