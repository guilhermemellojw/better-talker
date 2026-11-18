# TRUST-2B1 — Baseline determinístico (relatório experimental)

**Status:** infraestrutura experimental em testes; nenhuma integração à produção.  
**Base:** `79f9b8188ffdbaef1236638d185d02a5f565c25a` (TRUST-2A integrada).  
**Código:** `android/app/src/test/java/com/bettertalker/app/eval/`.  
**Natureza:** fixtures sintéticas; os números abaixo medem o **baseline mecânico**, não desempenho semântico real.

## 1. Inventário

| Grupo | IDs | Total |
|---|---|---:|
| Chat contextual | C01–C10 | 10 |
| Assistente global | G01–G08 | 8 |
| Explicação de fontes | S01–S06 | 6 |
| Cards/oratória | K01–K08 | 8 |
| Draft/dossiê | D01–D06 | 6 |
| Verificadores | V01–V08 | 8 |
| SourceGate | SG01–SG06 | 6 |
| RAG | R01–R04 | 4 |
| TRUST-1A | T1A-01–T1A-08 | 8 |
| **Subtotal preservado** | | **64** |
| Extras experimentais | X-* | 8 |
| **Inventário total** | | **72** |

- Executáveis: **24**.
- Pendentes de anotação: **48**.
- Não avaliáveis: **0**.
- Lacuna registrada: **T2-01…T2-15** não possuem especificação verificável na `main`; não foram inventados.

## 2. Cobertura executável

| Rota | Casos executáveis |
|---|---:|
| CHAT | 5 |
| GLOBAL | 2 |
| SOURCE | 1 |
| CARD | 2 |
| DRAFT | 1 |
| VERIFIER | 1 |
| TRUST1A | 4 |
| EXPERIMENTAL | 8 |

| Tipo de claim | Casos |
|---|---:|
| FACTUAL_BIBLICAL | 21 |
| USER_HYPOTHESIS | 2 |
| LITERAL_CITATION | 1 |

## 3. Baseline determinístico

Regras implementadas:

1. Fidelidade literal via `checkDocumentCitation`, `checkUserCitation`, `checkS34Citation`.
2. Elegibilidade via `EvidenceUnit.isFactualEligible()`/`isMethodEligible()`.
3. Correspondência textual direta apenas quando o claim normalizado está contido em **uma** unidade documental elegível.
4. `SUPPORTED` somente com evidência examinada identificável.
5. Tipos não factuais permanecem `NOT_EVALUATED`.
6. Fonte ausente/parcial/truncada/dispensada/não exposta/sem unidade → `UNVERIFIABLE`.
7. Edição divergente → `UNVERIFIABLE`.
8. Contradição e paráfrase não são inferidas.

Resultados (24 executáveis):

| Estado | Casos |
|---|---:|
| SUPPORTED | 5 (C01, G01, K01, D01, X-EXTRA-EVIDENCE-01) |
| INSUFFICIENT | 5 (C03, G06, K02, X-CONTRA-01, X-PARAPHRASE-01) |
| UNVERIFIABLE | 13 |
| NOT_EVALUATED | 3 (C04, V01, T1A-05) |
| CONTRADICTED | 0 |

- Fidelidade documental: **1/1** (V01, NORMALIZED).
- Falsos positivos: **0**.
- Falsos negativos: **1** (`X-CONTRA-01`, esperado CONTRADICTED, baseline INSUFFICIENT).
- Macro-F1: **0,7778** — calculado apenas sobre os 24 casos sintéticos rotulados; **não é desempenho real**.
- Runtime local medido: **0 ms** para 24 casos (sem rede, sem LLM).
- Custo externo: **US$ 0** (nenhuma chamada de API).

## 4. Casos positivos

- C01, G01, K01, D01: correspondência textual direta em fonte elegível.
- X-EXTRA-EVIDENCE-01: evidência extra não examinada não altera o veredito.
- V01: fidelidade NORMALIZED, sem autorização factual.

## 5. Casos negativos cobertos

- Corpus vazio: C05, T1A-07.
- Fonte sem texto: T1A-08.
- Fonte não exposta: X-UNEXPOSED-01.
- Fonte parcial: X-PARTIAL-01.
- Fonte truncada: X-TRUNCATED-01.
- Fonte dispensada: C09.
- Unidade desconhecida: X-NO-UNIT-01.
- Edição diferente: X-EDITION-01.
- Hipótese do usuário: C04, T1A-05.
- be/th como prova factual: T1A-06.
- Citação fiel sem suporte: V01.
- Paráfrase sem correspondência: C03, X-PARAPHRASE-01.
- Contradição: X-CONTRA-01 (falso negativo registrado).
- S-34 estrutural: T1A-08.
- Referência inexistente no corpus: C05, S03.

## 6. Limitações

- 48 cenários permanecem sem fixture/anotação completa.
- T2-01…T2-15 não têm especificação na `main`; lacuna registrada.
- Fixtures são sintéticas; não representam citações reais da TNM/jw.org.
- O baseline não detecta paráfrase, implicação, contradição ou interpretação.
- Macro-F1 de 0,7778 é mecânico e não deve ser apresentado como desempenho semântico.
- Sem anotação humana real; nenhum rótulo é reivindicado como revisão humana.
- Sem medição de memória no A34 nesta fase (teste puro JVM).

## 7. Próxima recomendação

1. Anotar humanamente os 48 casos pendentes, começando por C02/C06/C07/C08/C10 e G02–G05/G07/G08.
2. Definir formalmente T2-01…T2-15 antes de usá-los.
3. Medir recall@k de candidatos (embeddings) sobre as fixtures anotadas.
4. Só então avaliar NLI local; não integrar verificador às rotas.
5. Manter o baseline determinístico como primeira camada e никогда não tratar `SUPPORTED` como certificado de verdade.

## 8. Reprodução

```bash
cd android
./gradlew :app:testPublicAppDebugUnitTest \
  --tests "com.bettertalker.app.eval.Trust2B1HarnessTest"
```

O relatório completo é impresso pelo teste `relatorioReproduzivel`.
