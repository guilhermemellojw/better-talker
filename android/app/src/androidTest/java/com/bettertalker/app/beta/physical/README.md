# BETA-PHYSICAL/P9 — instrumentação (D)

**Baseline:** PR #86 `901d74fbf1f9120bd35be534a8d9ca9c3a8f32fd`
**Ownership:** D = `androidTest`/fixtures; A = injeção `ProviderSource`; C = pipeline/APK.
**Sem edição de código produtivo/build.** Nenhum ADB/connectedAndroidTest nesta rodada.

## Testes independentes (compilam sem provider fake)

| Classe | Cenário | Arquivo |
|---|---|---|
| `PhysicalCasRoomTest` | CAS legítimo, edição concorrente, exclusão, desativação, invalidação de proveniência, dois escritores, reabertura de arquivo, expected errado | `androidTest/.../beta/physical/PhysicalCasRoomTest.kt` |
| `PhysicalViewModelRestartTest` | persistência/reabertura, recriação de ViewModel, cópia/inserção, rejeitado, cache × banco, conteúdo após restart | `androidTest/.../beta/physical/PhysicalViewModelRestartTest.kt` |
| `PhysicalRoomFixture` | Room real em arquivo, seed de nota/chat/proveniência, payload, reabrir/limpar | `androidTest/.../beta/physical/PhysicalRoomFixture.kt` |

- Room **em arquivo** (`Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)`), nunca in-memory/mocks.
- Concorrência com `CountDownLatch` + `ExecutorService`; o teste de dois escritores parte simultaneamente e exige exatamente um CAS vencedor.
- `ViewModelStore.clear()` recria o ViewModel; o banco é fechado/reaberto em testes de arquivo.
- Cache é observado via `messages`/`eligibleCopyText` após edição do payload.

## Pendentes de A (não compilados de propósito)

`androidTest/.../beta/physical/pending/PhysicalProviderPendingTest.kt.pending` contém o **único** fake baseado no contrato P10 de A (`ProviderSource`). Não criar segundo fake. Renomear para `.kt` depois que A publicar:

- `ProviderSource` + `DefaultProviderSource`;
- 5º parâmetro `providerSource` em `CopilotViewModel` (e `GlobalAssistantViewModel`);
- todos os `ProviderFactory.resolveRemote/createFor/useRemoteRoute` roteados por `providerSource`.

Testes preparados: histórico capturado, fonte rejeitada antes do provider, cancelamento durante streaming, AI-09 com fonte persistida/sem fallback, continuação concorrente, conteúdo sem fundamento.

## Comandos

```bash
# compila o APK de testes (host, sem dispositivo)
./gradlew :app:assemblePublicAppDebugAndroidTest \
  --offline --no-daemon --max-workers=1 --no-build-cache

# SOMENTE com autorização física/A34:
./gradlew :app:connectedPublicAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bettertalker.app.beta.physical.PhysicalCasRoomTest
```

## Critérios

- PASS exige execução no A34, não compilação; qualquer FAIL/BLOCKED interrompe.
- Evidência: XML JUnit, logcat, hash do banco temporário, timestamps; sem dados pessoais.
- Zero chamadas pagas no plano; provider fake/local.
- Não declarar PASS físico com base em compilação/host-only.
