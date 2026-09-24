# Fase 7 — Trilho de treinamento BE/TH (documentação §29)

## 1. Conceito

```text
CONTENT  = O QUE dizer (Bíblia, publicações, notas) → afirmações
TRAINING = COMO dizer (BE/TH por categoria)          → técnica
```

Regra inegociável: BE/TH nunca prova ideia; só ensina a apresentá-la.

## 2. Taxonomia (`TrainingCategory`)

introduction, development, explanation, illustration, application,
transition, conclusion, questions, clarity, naturalness, delivery,
unknown. Uma categoria por trecho; `unknown` quando inseguro.

## 3. Classificação (`trainingClassifier.ts`)

Determinística: seção/título primeiro (sinal forte), corpo depois;
primeira regra na ordem fixa; símbolo sozinho não classifica.
**Padrões sempre sem acento** (entrada normalizada) — bug encontrado
pelos testes e corrigido. Metadata gravada no índice prevalece;
registros v2 ganham categoria on-the-fly (backfill).

## 4. Retrieval especializado (`trainingRetriever.ts`)

`retrieveTraining({query, category?, scope, store})`: só
`trainingSourceIds` (vazio => `insufficient_scope`); Hybrid por baixo;
intenção aplica bônus +0.15 e reordena sem tocar no `finalScore`.
`trainingIntent.ts` mapeia ação/modo → categoria (null = só conteúdo).

## 5. ContextPack e prompt

Trilhos separados preservados; `training_sources` carregam
`training_category`; prompt rotula `[Orientação i: ref | técnica: X]` e
proíbe apresentar técnica como mandamento ou atribuir criação ao BE/TH.
Ilustrações criadas => "sugestão do modelo".

## 6. UI e proveniência

Evidências training exibidas com 🎤 + categoria (vs 📖 conteúdo);
proveniência completa (pub, seção, passageId, categoria) até a UI.
Geração passa a incluir pack de training por intenção (falha => segue
só com conteúdo, sem quebrar).

## 7. Integração Fase 6

Verificador intocado: factual nunca recebe training (garantido pelo
trilho); texto gerado após sugestão de training continua verificado.
Juiz e thresholds sem mudança.

## 8. Offline

Tudo local e determinístico (regras + índice existente). Sem rede nova.

## 9. Testes (`npm test`)

12 novos (88 totais; Fases 3–6 intactas): classificação (4),
retrieval por intenção (4), intent/pack/UI/prompt/Fase-6 (4).

## 10. Limitações

- Classificação por keywords: seção ambígua pode miscategorizar
  (mitigado pela prioridade seção > corpo).
- Intenção ação→categoria é heurística default, sem override na UI.
- Sem reindexação automática do acervo antigo (backfill on-the-fly).

## 11. Impacto futuro

- **Fase 8**: trilhos e categorias reutilizáveis no app nativo.
- **Fase 9**: categorias `introduction/conclusion/transition` alimentam
  análise estrutural do discurso.
