# Fase 4 — Providers robustos (documentação §24)

## 1. Arquitetura

```text
Copilot (Drawer)
  ↓  providerFactory (createCopilotProviderFromEnv) — única decisão Gemini/Qwen
LlmProvider.generate(request) → LlmResponse { text, meta }
  ├─ GeminiProvider  → :generateContent (chave do app; sem chave = offline)
  └─ QwenProvider    → /chat/completions (OpenAI-compatível; sem endpoint = unavailable)
Ambos: llmPrompt (system+user) → llmHttp.postJsonWithRetry → ProviderError
```

## 2. Configuração (sem segredos no código)

`VITE_LLM_PROVIDER=gemini|qwen` (padrão gemini), `VITE_QWEN_ENDPOINT`,
`VITE_QWEN_MODEL` (padrão `qwen-plus`), `VITE_QWEN_API_KEY`. Chave Gemini
continua nas Configurações do app (IndexedDB local).

## 3. Comportamento

- **Timeout**: 30s configurável (`timeoutMs`), por chamada.
- **Retry**: até 2 tentativas para transitórios (timeout, rede, 429, 5xx).
  401/403/400 e resposta inválida NÃO repetem.
- **Cancelamento**: `AbortSignal` da UI (botão Cancelar); cancelado não é
  resposta — lança `cancelled`.
- **Streaming**: NÃO implementado — contrato retorna resposta completa;
  cancelamento + timeout cobrem gerações longas sem reescrita da UI.
- **Erros**: `ProviderError{timeout,network,authentication,rate_limit,invalid_request,invalid_response,unavailable,cancelled}`
  com `providerId/attempts/status`; UI traduz para mensagens PT sem HTTP.
- **Offline**: só sem chave (templates estruturais, sem afirmações factuais).
  Erro remoto com chave NUNCA vira offline silencioso — política explícita,
  sem fallback entre modelos (§19).
- **Observabilidade**: `meta{providerId,model,durationMs,attempts,offline}` +
  `console.debug` de uma linha (sem URL, corpo ou credenciais). UI mostra
  "via gemini · modelo · 2,3s".

## 4. Qwen — limitações honestas

- Remoto OpenAI-compatível apenas; endpoint/modelo via env.
- Sem endpoint → `unavailable` com mensagem acionável (não finge suporte).
- Execução local no web não existe (on-device é papel do app nativo).

## 5. Testes (`npm test`)

23 novos (34 totais com Fase 3): Gemini (10), Qwen (7), abstração/factory/fake/
ContextPack/segurança (6). Fase 3 intacta: 11/11 continuam verdes.

## 6. Impacto futuro

- **Fase 5**: `LlmResponse.meta` + cancelamento reutilizáveis nas operações
  de edição; `queryGeminiOratoryCoach` segue como compat.
- **Fase 6**: prompt já separa content/training e exige frase de
  insuficiência — base para verificação; provider NÃO julga a própria resposta.
- **Fase 7**: trilha training rotulada no prompt como técnica, não fatos.
