# QA-CI-ANDROIDTEST / P1 — Gate obrigatório de compilação androidTest no CI

**Data:** 24/11/2026 — Branch `ci/androidtest-compile-gate` (base `origin/main` = `52e75373`).

---

## A. Branch/HEAD

- Branch: `ci/androidtest-compile-gate` (a partir de `origin/main`).
- Base: `52e75373ceafb9e4c3ce09c6225ae057b6808369`.
- HEAD final: verificado em `git rev-parse HEAD` pós-commit.
- PR: Draft, apenas `.github/workflows/android-build.yml` + este relatório. PR #43 NÃO alterado.

## B. Diff

`.github/workflows/android-build.yml` — inserido após "Upload test reports (failure)" e antes de "Run Firestore rules tests":

- Steps **"Compile androidTest (publicApp)"** e **"Compile androidTest (syncBeta)"** (bloqueantes, sem `continue-on-error`), um flavor por invocação, `--no-daemon --max-workers=1`, heap via `-Dorg.gradle.jvmargs="-Xmx3072m ..."` e `-Dkotlin.daemon.jvmargs="-Xmx2048m ..."`, logs em `gradle-androidtest-{public,sync}.log`.
- Step **"Show androidTest compile failure log"** (`if: failure()` + outcomes dos dois steps): imprime últimas 100 linhas + filtro de erros, `exit 1`.
- Nenhum step existente foi substituído (JVM/lint/APK/Firestore/Storage/Firebase preservados).
- **Ajuste após CI real:** primeira tentativa (ambos flavors juntos, 2 workers) falhou no GitHub com `OutOfMemoryError: Java heap space` no compilador Kotlin (`gradle.properties` fixa Kotlin daemon em 1 GB). Versão final: execução sequencial, 1 worker e heap ampliado — o step de gate continua bloqueante; OOM é tratado como falha legítima (não mascarada).

## C. Resultados das compilações (executadas localmente, sem dispositivo)

| Comando | Resultado | Observação |
|---|---|---|
| `compilePublicAppDebugAndroidTestKotlin` (base main) | BUILD SUCCESSFUL | ~1m58 (cold), 19s (warm) |
| `compileSyncBetaDebugAndroidTestKotlin` (base main) | BUILD SUCCESSFUL | ~2m04 (cold) |
| Ambos (arquivo temporário removido) | BUILD SUCCESSFUL | `EXIT=0` |
| PR #43 HEAD `85e189a2` — ambos flavors | BUILD SUCCESSFUL | evidência prévia na worktree do PR #43 |

Nenhum dispositivo/emulador conectado; nenhuma task `connectedAndroidTest`, `adb` ou instalação de APK executada.

## D. Controle negativo

Arquivo temporário `CiGateNegativeControl.kt` em `src/androidTest` com `val missing = SignalingPassageDao()` (referência inexistente — padrão exato do bloqueador QA-TRUST-43/P2-RECERT):

```
e: .../CiGateNegativeControl.kt:9:23 Unresolved reference 'SignalingPassageDao'.
FAILURE: Build failed with an exception.
BUILD FAILED in 23s   EXIT=1
```

Após remover o arquivo: `BUILD SUCCESSFUL` (EXIT=0). **Conclusão:** a task de compilação androidTest detecta o erro; no workflow o step é bloqueante (falha → job vermelho).

## E. CI

- Um PR Draft será aberto; o próprio workflow `Build Android APK` roda em `pull_request` (paths incluem `.github/workflows/android-build.yml`) e em `push` (branches `**`).
- Verificar no SHA final: `gh run list --workflow "Build Android APK" --limit ...`.
- Não usar CI do PR #43 como prova do gate — é trabalho prévio.

## F. Impacto estimado no tempo do workflow

- Cold cache: ~2–4 min adicionais (duas tasks, compilações paralelas limitadas a 2 workers).
- Warm/incremental: ~20–60s.
- Sem custo de dispositivo/emulador: são tasks de compilação Kotlin/Java.

## G. Limitações

- Ambiente JVM local flutuante: houve um crash SIGSEGV do compilador Kotlin durante a primeira execução; retentativa bem-sucedida. Source error não era — IR dump do compilador.
- O gate compila testes instrumentados; **não os executa**. Execução em dispositivo continua fora deste escopo (sem autorização).
- `lintPublicAppRelease` falhou localmente por crash do daemon JVM — pré-existente e fora do escopo do gate (não alterado).
- A main atual compila os dois flavors androidTest (após retentativas) — não bloqueou a publicação.
