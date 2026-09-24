# Fase 9 — Inteligência estrutural do discurso (documentação §32)

## 1. Arquitetura

```text
Speech (blocos)
  ↓  speechAnalyzer.analyzeSpeech (determinístico, local, cacheado)
SpeechAnalysis { flow, structure, observations, estimatedTime }
  ↓  UI (painel) / intents locais
  ↓  [Gerar sugestão] → Fase 5 (preview) → Fase 6 (verify) → usuário
```

## 2. Modelo

`BlockAnalysis{blockId,title,roles,wordCount,seconds,longestSentenceWords}`;
`Observation{id,type,severity,blockIds,message,reason,suggestion?}`;
14 tipos, severidade INFO/ATTENTION/SUGGESTION (sem nota global);
`EstimatedTime` com disclaimer; `ANALYSIS_VERSION=1`.

## 3. Heurísticas (conservadoras, com limiares documentados)

- Introdução: bloco 0 com ≥2 sinais (pergunta, tema do título ≥2 termos,
  objetivo, abertura); 0 sinais + ≥2 blocos => ? insuficiente; 1 => silêncio.
- Conclusão: último bloco com ≥2 sinais (resumo, tema, chamada); 0 => 💡.
- Pontos: blocos ≥20 palavras entre abertura/encerramento.
- Transições: categoria `transition` da F7; fronteira fraca = sem conector
  + Jaccard < 0.08 + ambos ≥20 palavras.
- Repetição: frase literal repetida; Jaccard ≥0.5 com ambos ≥30 palavras.
- Clareza: frase >35 ATTENTION, 26–35 SUGGESTION.
- Naturalidade: ≥2 de {média >28, 3+ aberturas iguais, formalidade}.
- Equilíbrio: ≥3 pontos e max/min ≥2.5 (relativo, pode ser intencional).
- Tempo: palavras/wpm + pausas de palco (espelha `calculateSpeechMetrics`).

## 4. Categorias F7 reutilizadas

`transition` (papel), tipos do `extractClaims` (EXPLANATION/ILLUSTRATION/
APPLICATION), `trainingCategoryForEditMode` (sugestão). Nada paralelo.

## 5. Relações

- CONTENT/TRAINING/STRUCTURE separados: análise lê texto+estrutura,
  nunca retrieval; sugestões factuais passam pela F6.
- F5: sugestão vira proposta com preview/verify/accept; nada automático.
- LLM: só via Fase 4/5 no botão "Gerar sugestão"; painel e intents são locais.

## 6. Offline, cache, versionamento

Tudo síncrono e local. Cache `speechId|hash|v1` (cap 50); UI marca
"obsoleta" quando o conteúdo muda.

## 7. Testes (`npm test`)

27 novos (115 totais; Fases 3–8 intactas): 3+2+2+3+3+2+3+2+2+5 por grupo,
incluindo anti-falsos-positivos (§30) e segurança semântica (§31).

## 8. Correções reveladas pelos testes

Edição que removeu `}` do `llmPrompt.ts` (parse quebrou; restaurado);
thresholds calibrados contra fixtures (POINT ≥20 palavras, fronteira ≥20).

## 9. Limitações

- Sem pronome-antecedente (frágil; documentado como não feito).
- Agrupamento semântico de pontos não existe (blocos = unidades).
- Android: só domínio/modelo conceitual; UI nativa fora do escopo (§26).
- Sem áudio/telemetria real (fora de escopo por definição).

## 10. Próximos passos

Agrupar blocos em pontos semânticos; diff por palavra no preview;
seletor de WPM na UI de análise; portar analyzer puro para Kotlin.
