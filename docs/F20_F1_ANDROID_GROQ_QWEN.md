# F20-F1 — Transporte Groq/Qwen no Android + validação física

> **Status: transporte implementado, validado com o modelo real (6+6 runs) e
> com regressão verde. Critério físico (§47): PENDENTE — o SM-A346M
> desconectou o ADB wireless no meio da fase e não reconectou; nenhuma
> etapa física além de instalação/boot foi executada. Nada foi simulado.**

## 1. Provider, modelo e contrato

| Item | Valor |
|---|---|
| Classe nova | `QwenProvider` (Android, `data/llm`) — id `qwen`, sem segundo provider |
| Transporte | `https://api.groq.com/openai/v1/chat/completions` (OpenAI-compatible) |
| Modelo | `qwen/qwen3.8-27b` (constante; sem fallback silencioso) |
| `reasoning_effort` | `low` (mesmo valor do web) |
| Structured output | `response_format: json_schema` com `strict: true` (só no caminho de proposta) |
| Seleção | `ProviderFactory.resolveRemote/createFor` + pref `llm_provider` (default `gemini`) |
| Credencial | DataStore `groq_api_key` (BYOD, tela Modelo IA) — nunca em log/erro/repo |
| Gemini | mantido, compilando, testes passando, default preservado |

Paridade Web × Android (§10): mesmo `LlmRequest` gera o mesmo prompt
(`oratorySpec` → especializado; edit-proposal → proposta; senão chat com
structural+oratory); só o transporte muda. `LlmResponseMeta` ganhou
`usage` + `rateLimit` (opcionais, retrocompatíveis); `LlmHttpClient`
ganhou `headers` no POST + captura de headers de rate-limit no
`UrlConnectionHttpClient`. Sem chave, o Qwen lança `UNAVAILABLE` honesto
(nunca texto fake).

## 2. Arquivos (transporte, sem tocar inteligência)

- `data/llm/QwenProvider.kt` (novo) — transporte + schema + parse + retry
- `data/llm/LlmProvider.kt` — `LlmUsage`, `usage`/`rateLimit` no meta, `headers` no HTTP
- `data/llm/GeminiProvider.kt` — `UrlConnectionHttpClient` aceita headers + captura rate-limit (comportamento Gemini inalterado)
- `data/llm/ProviderFactory.kt` — `resolveRemote`/`createFor`/`createSelected`
- `data/prefs/SettingsStore.kt` — `groq_api_key` + `llm_provider`
- `ui/aimodel/ModelViewModel.kt` + `ModelScreen.kt` — seletor de provider + chave Groq (settings, não Copilot)
- `ui/copilot/CopilotViewModel.kt` — 3 call sites resolvem provider+chave (oratória, chat remoto, proposta)
- `data/copilot/OratoryFidelityCheck.kt` — **correção de bug real** (ver §5)

Nada em S34Detector/S34Parser/persistência/retrieval/ContextPack/
InferredOratoryStructure/ChatRouter/OratorySession/prompts/F5/F6/EditHistory/
Firebase foi alterado (só o check de fidelidade, com causa e regressão).

## 3. Validação real na JVM (HTTPS real, modelo real)

### Run 1 (antes do fix do check)
```text
introduction      ops=1 invented=[] leaked=[] unsupported=[]  1834 tok
development-sec2  ops=1 invented=[] leaked=[] unsupported=[34] 1898 tok
transition-2→3    ops=1 invented=[Hebreus 10:23] leaked=[] unsupported=[10,23] 1920 tok
conclusion        ops=1 invented=[] leaked=[] unsupported=[] 1778 tok
iteration-natural ops=1 invented=[] leaked=[] unsupported=[] 1833 tok
F5 (regen intro)  accept=APPLIED reject=intacto stale=STALE_PROPOSAL
```

### Run 2 (após o fix; textos capturados para julgamento)
```text
realRuns=6 wrongPoint=0 wrongReference=0 inventedReference=0 crossPointLeak=0
crossOutlineLeak=0 unsupportedFact=0 iterationWrongTarget=0 jsonParseFailure=0
```

### Julgamento humano das observações
1. **Transição citou `Hebreus 10:23` (run 1)**: com o fix, classifica como
   `leaked` (é a ref do ponto seguinte, exposta no prompt da transição).
   Leitura da função da transição (conectar 2→3): ponte aceitável, sem
   desenvolvimento do ponto 3 — o run 2 veio limpo. Registrado, não mascarado.
2. **`unsupported=[34]` no dev run 1**: origem não capturada naquele run
   (captura de texto foi adicionada depois); run 2 limpo. O dev run 2 ainda
   mostrou o caminho honesto de insuficiência ("Não encontrei suporte
   suficiente…") seguido de estrutura oral dos subpontos — comportamento pedido.
3. **Conclusão citou `Hebreus 10:23`**: correto — é a ref do último ponto,
   âncora da conclusão.
4. **Iteração** permaneceu introduction/sec-1 com evolução do texto.

Tokens observados (§37): ~1,8–1,9k por geração; OTPM 1000/min exige pacing de
~75s (6 chamadas ≈ 7 min). `x-ratelimit-*` capturados no meta.

## 4. Testes (§12)

`QwenGroqProviderTest` (17 testes, fake HTTP): modelo exato, endpoint,
Authorization, schema estrito, chat sem schema, reasoning low, prompt
oratório com isolamento (Tiago sim / Hebreus não), timeout+retry 4xx/5xx/429,
resposta inválida, cancelamento, sem-chave honesto, tokens/rate-limit,
factory qwen/gemini, chave nunca em erro. `QwenGroqRealSmokeTest` e
`QwenGroqRealValidationTest` guardados por `Assume` (pulam sem `gsk_…`).

## 5. Bug real encontrado e corrigido (§§45-46)

- **Reprodução**: validação real (transição 2→3 citando `Hebreus 10:23`) +
  reprodução pura sem rede.
- **Causa**: `belongsToOtherSection` comparava a chave normalizada
  (`hebreus1023`) com rótulos crus (`Leia Hebreus 10:23.`) — `leaked` era
  inalcançável; tudo caía em `invented`.
- **Correção mínima**: normalizar os rótulos do ponto seguinte antes de comparar.
- **Teste**: `referenciaDoPontoSeguinteEVazamentoNaoInvencao` +
  `referenciaDesconhecidaContinuaInvencao` em `OratoryFidelityTest`.
- O web não tinha o bug (normaliza os dois lados).

## 6. Validação física (§§23-36)

| Item | Resultado |
|---|---|
| APK debug instalado | PASS (`Success`, 148 MB) |
| App boot, sem crash (logcat) | PASS (processo vivo, sem `FATAL EXCEPTION`) |
| Import S-34, gerações, accept/reject, iteração, force-stop | **PENDENTE** — ADB wireless caiu (`no devices`) e não voltou |
| Offline físico | NOT_EXECUTED (§36, sem canal seguro) |

Para retomar: reconectar o SM-A346M, digitar a chave Groq na tela Modelo IA
(seletor Qwen), importar a fixture (`s34-f20f1.txt`) pela Biblioteca e seguir
o roteiro §§26-35 (harness de taps em `/tmp/opencode/uih.py`).

## 7. Segurança (§§40-41)

`git diff` varrido: nenhuma chave, nenhum `Authorization` logado, nenhum
conteúdo oficial, nenhum temporário com credencial. A chave real viveu só em
`/tmp/opencode/groq.key` (chmod 600, removida ao final). **DEV-ONLY**: a
chave Groq no DataStore do aparelho é BYOD local; nunca embutir segredo em
APK distribuído (extraível).

## 8. Regressão (§§42-43)

Web 470 passed / 2 skipped · build ✓ · oxlint 6 warnings/0 erros.
Android 555 testes / 0 falhas · assembleDebug SUCCESS · lint nos 2 erros
pré-existentes intocados, 0 issues nos arquivos da fase.
