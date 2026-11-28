# BETA-TESTABILITY/P10 — injeção mínima de provider

**Baseline:** PR #86 @ `901d74fbf1f9120bd35be534a8d9ca9c3a8f32fd` (posteriormente integrado na `main`).
**Contrato/ownership:** publicado em `7241269649`; A edita os componentes produtivos, D os testes instrumentados, C APK/assinatura.

## Solução menor
Não foi criado `LlmProviderFactory.kt`. `ProviderFactory` já concentrava as decisões; foi adicionada apenas a interface `ProviderSource` com `DefaultProviderSource` delegando exatamente a ela.

- `ProviderSource.resolveRemote/createFor/useRemoteRoute` — assinaturas publicadas a D.
- `CopilotViewModel(..., providerSource: ProviderSource = DefaultProviderSource)`.
- `GlobalAssistantViewModel(..., providerSource: ProviderSource = DefaultProviderSource)`.
- Todos os call sites desses ViewModels passaram a usar `providerSource`; os tipos `ProviderFactory.RemoteConfig` permaneceram.

## Fake (somente testes)
`ProviderSourceTest` define um fake no source set de teste: captura integral de `LlmRequest.history`, resposta determinística, `CompletableDeferred` como barreira, `CancellationException`/falha controlada e contador de chamadas (zero antes do `generate`). Nada disso é compilado no APK.

## Verificação
- `ProviderSourceTest`: **5/5** (equivalência da rota padrão, zero chamadas, histórico integral, cancelamento, falha).
- Suíte JVM integral: **4702 testes, 0 falhas, 0 erros, 0 skipped** (471 suítes) — 4697 anteriores + 5 novos.

## Impacto no APK de distribuição
Nenhum: default é o comportamento atual; sem flag, preferência, BuildConfig ou fake empacotado. Nenhuma alteração de versão, assinatura, LLM pago, Room, CAS, proveniência, TNM/jw.org ou funcionalidade nova.

## Handoff D
Interface e exemplo mínimo de fake em `7241269649`. D pode escrever os testes instrumentados em paralelo sem editar estes arquivos.
