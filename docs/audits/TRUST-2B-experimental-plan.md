# TRUST-2B — Plano experimental (preparação, sem execução)

**Status:** especificação. Nenhum experimento remoto, modelo ou dependência foi adicionado.  
**Base:** matriz de 56 casos do TRUST-0 (PR #11) + extensões TRUST-1A (T1A-01…T1A-08).

## 1. Objetivo

Medir se um verificador consegue associar afirmações do Mentor a trechos documentais e distinguir:

- apoio direto;
- paráfrase legítima;
- evidência insuficiente;
- contradição;
- ausência de evidência;
- extrapolação interpretativa.

## 2. Reuso da matriz

Preservar os IDs originais (C01–C10, G01–G08, S01–S06, K01–K08, D01–D06, V01–V08, SG01–SG06, R01–R04, T1A-01–T1A-08). Para cada caso, anotar:

- `claim_id`, `claim_text`, `claim_type`;
- `premise_ids` (EvidenceUnit.documentId);
- `expected_state` (SUPPORTED/INSUFFICIENT/CONTRADICTED/UNVERIFIABLE/NOT_EVALUATED);
- `expected_fidelity` (DOCUMENT/USER_MESSAGE/S34/NONE);
- `route` e política esperada (PASS/WARN/BLOCK/REFUSE);
- `notes` de anotação humana.

Adicionar T2-01…T2-15 do Discovery TRUST-2.

## 3. Fixtures necessárias

- Bíblia TNM: versículo com texto literal, versículo sem texto, referência inexistente, versículo truncado.
- Publicação jw.org: parágrafo literal, edição ausente, unidade parcial, referência ambígua.
- S-34: trecho literal, referência sem texto, estrutura/objetivo.
- Usuário: hipótese falsa, hipótese correta não comprovada, citação literal fiel, citação inventada.
- be/th: princípio de oratória, uso doutrinal indevido.
- Criativo: sugestão marcada, referência inventada dentro de sugestão.
- Conflito: duas fontes com afirmações incompatíveis.

Licenciamento: usar apenas trechos efetivamente consultados e permitidos; não redistribuir publicações.

## 4. Anotação humana

- Dois anotadores independentes por amostra.
- Concordância mínima: Cohen's κ ≥ 0,70; divergências resolvidas por terceiro.
- Rótulo de suporte: `SUPPORTED`, `INSUFFICIENT`, `CONTRADICTED`, `UNVERIFIABLE`.
- Rótulo de fidelidade separado: `EXACT`, `NORMALIZED`, `NOT_FOUND`, `UNVERIFIABLE`.
- Justificativa obrigatória com `premise_id` e trecho.

## 5. Comparação de abordagens

| Abordagem | O que medir | Critério de comparação |
|---|---|---|
| Regras determinísticas | fidelidade literal, refs, números | baseline; precisão alta, recall baixo em paráfrase |
| Similaridade/embeddings | recall@k de premise correta | Top-3 ≥ 80%; não decide suporte sozinho |
| NLI local | macro-F1 semântico, calibração | F1 ≥ 0,80; ECE ≤ 0,10 |
| LLM avaliador | F1, custo, latência, consistência | não pode ser único; comparar com humano |
| Híbrida | F1, falsos positivos/negativos, custo | deve superar baseline determinístico |

## 6. Métricas

- Cobertura de claims avaliados ≥90%.
- Precisão/recall de extração de claims ≥85%/90%.
- Macro-F1 semântico ≥0,80.
- Falsos negativos ≤5% (meta ≤2% doutrinal).
- Falsos positivos ≤5% (meta ≤2% citações literais).
- Fidelidade documental/usuário/S-34 ≥98%.
- Recusa apropriada ≥90%; recusa indevida ≤5%.
- Custo ≤10% do turno; latência p95 ≤1,5 s local.
- Concordância humana κ ≥0,70.

## 7. Protocolo experimental

1. Rodar baseline determinístico nos 56 + T2.
2. Rodar matcher/embeddings e medir recall@k.
3. Rodar NLI local em subconjunto rotulado.
4. Rodar LLM avaliador apenas em casos ambíguos, com limite de custo.
5. Comparar com anotação humana; gerar matriz de confusão.
6. Registrar falsos positivos/negativos e causas.
7. Congelar decisão: deterministic-only, determinístico+NLI, ou híbrido com LLM opcional.

## 8. Galaxy A34

Obrigatório nas fases de integração:

- 20–30 prompts reais;
- latência p50/p95;
- memória adicional;
- bateria/aquecimento;
- offline e rede instável;
- corpus vazio, fonte ausente, citação do usuário e S-34;
- legibilidade dos avisos/recusas;
- SHA do APK e baseline.

## 9. Critérios de parada

- Se NLI local não atingir F1 ≥0,80, não ativar por padrão.
- Se LLM avaliador aumentar custo >10% sem ganho ≥5 p.p., não usar.
- Se falsos positivos >5%, manter warn-only e não bloquear.
- Se corpus vazio produzir `SUPPORTED`, falha bloqueante.
