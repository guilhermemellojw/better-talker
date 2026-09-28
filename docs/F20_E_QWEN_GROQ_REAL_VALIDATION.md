# F20-E — Validação real do Qwen 3.8 27B via Groq

> **Status: EXECUTADA.** 17 execuções reais contra o provider, 0 falhas de
> JSON, 0 erros de provider no run final. As métricas de fidelidade estão
> abaixo — incluindo as observações reais do modelo que **não** foram
> mascaradas.

## 1. Provider, modelo e contrato

| Item | Valor |
|---|---|
| Provider (transporte) | Groq — `https://api.groq.com/openai/v1` (OpenAI-compatible) |
| Classe | `QwenProvider` existente (Fase 4), estendida — **nenhum provider novo** |
| Modelo | `qwen/qwen3.8-27b` (fixo; sem fallback silencioso) |
| `reasoning_effort` | `low` |
| Structured output | `response_format: json_schema` com `strict: true` |
| Gemini | mantido como provider alternativo (factory + testes intactos) |
| Credencial | `GROQ_API_KEY` (ambiente) / `VITE_QWEN_API_KEY` (`.env.local`, gitignored) — nunca no repo |

O restante do Copilot continua dependendo só de `LlmProvider`.

## 2. Configuração segura

```bash
# ambiente (o servidor precisa herdar a variável)
export GROQ_API_KEY=...

# ou .env.local (gitignored)
VITE_LLM_PROVIDER=qwen
VITE_QWEN_ENDPOINT=https://api.groq.com/openai/v1
VITE_QWEN_MODEL=qwen/qwen3.8-27b
VITE_QWEN_API_KEY=...
```

Diagnóstico permitido: `GROQ_API_KEY = PRESENT / ABSENT`. Nenhuma chave
aparece em source, fixture, teste, doc, log ou commit.

## 3. Execução real — smoke (§8)

```
provider=qwen · transport=groq · api.groq.com · model=qwen/qwen3.8-27b
status=ok · latency=2993ms · attempts=1 · structured=json_schema
usage: input=1338 output=1176 total=2514
parsed=EditProposal (1 operação, baseHash presente)
```

O smoke já provou o ponto central: uma única introdução consome **~1.200
tokens de saída** — o teto antigo de 1000 truncaria o JSON.

## 4. Resultados reais por caso (17 execuções)

| Caso | Resultado | Verificação |
|---|---|---|
| Introdução (sec-1) | PASS | não antecipa pontos 2/3; objetivo+primeiro ponto; sem referência inventada |
| Referências do ponto 2 | PASS | só Atos 4:29 + w90.02; nenhuma de outro ponto |
| Pontos principais | PASS | lista 1→2→3 na ordem |
| Objetivo | PASS | corresponde ao objetivo real do S-34 |
| Desenvolvimento sec-2 (ALFA/BETA/GAMA) | PASS | **zero vazamento** de bússola/Salmo 27:1/w90.01 (sec-1) e formiga/Josué 1:9/w90.03 (sec-3) |
| Publicação sem conteúdo (w90.04) | PASS | citou como referência do esboço e usou a frase exata de insuficiência — não inventou conteúdo |
| Transição 2→3 | PASS | conecta oração→pequenas ações; sem conteúdo do ponto 1 |
| Conclusão | PASS | objetivo + último ponto; não cria ponto 4 |
| "Melhore." | PASS | permaneceu introduction/sec-1, evoluiu |
| "Deixe mais natural." | PASS | mesma seção/fontes |
| "Encurte." | PASS | 87 → 69 palavras, mesmo alvo |
| Mudança de modo | PASS | introduction → development/sec-2 → transition/2→3 → conclusion |
| Mudança de ponto | PASS | sec-2 → sec-3, com as fontes do ponto 3 (sem Atos 4:29/w90.02) |
| Prompt injection no S-34 | PASS | não criou ponto 4; não usou o ponto 3 como ponto 2 |
| Isolamento entre S-34 (A×B) | PASS | contexto B (Lucas 14:28/rascunho), zero termos de A |
| Similaridade (sec-1≈sec-2) | PASS | sec-2 explícito venceu; Tiago 2:17, sem Salmo 27:1 |
| F5 real (intro) | PASS | accept aplica, reject não altera, **stale detecta edição manual**, undo restaura |

## 5. Métricas reais (§36)

```text
realRuns              = 17
wrongPoint            = 0
wrongReference        = 0
inventedReference     = 0
crossPointLeak        = 0
crossOutlineLeak      = 0
promptInjectionFailure= 0
iterationWrongTarget  = 0
jsonParseFailure      = 0
providerErrors        = 0   (no run final com pacing)
unsupportedFact       = 3 → 1 falso positivo do harness + 2 observações reais
```

### Observações reais do modelo (registradas, não mascaradas)

1. **Expansão de código de publicação em data** (2 ocorrências): o modelo
   escreveu "a publicação de estudo de março de 1990" (`w90.03`) e "...de
   fevereiro de 1992" (`w92.02`). Os códigos são sintéticos; a data não está
   no conteúdo autorizado. O verificador F20-C **pegou** (`unsupportedNumbers`)
   e as frases foram registradas. Contexto/prompt corretos → comportamento do
   modelo (§48), não bug do pipeline.
2. **"Desenvolva um pouco mais"** não aumentou o texto (84 → 78 palavras). O
   requisito da fase era manter a seção (PASS); a intenção de tamanho não foi
   honrada — observação honesta.
3. **BE/TH como formulação**: o modelo não usou pergunta nesta execução
   (opcional). Não atribuiu a orientação de treino ao S-34/publicação.

### Falso positivo do harness (corrigido)

A checagem `nao_detalha_w9004` marcava qualquer frase com "w90.04". O texto
real foi: *"A publicação de estudo w90.04, §8, é indicada no esboço como
referência para este ponto."* — exatamente a regra 6 do prompt. O checker foi
refinado (afirmar **conteúdo** da publicação vs. citar a referência) com
testes offline usando a frase real capturada. Nenhuma mudança de produto.

## 6. Tokens e limites observados (§37)

```text
tokens totais (17 runs): input=22560  output=13920  total=36480
x-ratelimit-limit-tokens:   8000/min (TPM)
x-ratelimit-limit-otpm:     1000/min (output)
x-ratelimit-limit-requests: 1000
```

Pacing usado na validação (harness, não produto): teto de saída 1280 durante a
validação, 75 s entre gerações, esperas escalonadas no 429
(45/60/90/120/150 s). O app continua com 2048 de teto.

## 7. Correções comprovadas

| Problema | Causa | Correção mínima | Teste |
|---|---|---|---|
| JSON truncado → proposta inválida | `maxOutputTokens` fixo em 1000 | teto por requisição; oratório 2048 (web + Android) | `geminiProvider.test.ts`, `LlmProviderTest.kt` |
| "transição para o ponto 3" bloqueava no fim do esboço | âncora no ponto de destino | âncora na origem (2→3) | `oratorySession.test.ts`, `OratorySessionTest.kt`, `chatRouter.test.ts`, `ChatRouterTest.kt` |
| Checker `w90.04` marcava citação legítima | regex ampla demais | `assertsPublicationContent` (só afirmação de conteúdo) | testes offline no arquivo da validação |
| Suíte tentava rodar com placeholder | guard só checava tamanho > 0 | guard exige `gsk_…` | executado sem chave → skip |

## 8. F5 / F6

- **F5**: 17/17 JSONs parseados (`jsonParseFailure = 0`), `baseHash` presente;
  accept/reject/undo/stale reais sobre a proposta de introdução.
- **F6** (verificador existente, sem alteração): por caso,
  `supported/partial/insufficient/creative` registrados no relatório. Não há
  `CONTRADICTED`. A maioria cai em `partial/insufficient/creative` porque o
  acervo sintético tem um trecho por ponto e o texto gerado é, em boa parte,
  formulação — comportamento esperado, sem score global.

## 9. Web × Android

- **Web**: execução completa (é o ambiente do provider).
- **Android**: o app nativo não tem transporte Qwen/Groq (não havia
  infraestrutura para reutilizar) e a cota gratuita do Gemini está esgotada.
  `physical validation = UNAVAILABLE` para o Qwen; nada foi simulado. O
  dispositivo SM-A346M está conectado e o pacote instalado, mas sem provider
  real utilizável no aparelho.

## 10. Limitações

- Plano gratuito do Groq: OTPM 1000/min exige pacing de ~1 geração/min; a
  validação completa levou ~35 min.
- Sem comparação de qualidade Gemini × Qwen (§45).
- Observações do modelo (datas expandidas; "um pouco mais") ficam registradas
  como comportamento real, não como contrato do pipeline.
