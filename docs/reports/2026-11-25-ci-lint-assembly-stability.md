# QA-OPS-P9 — estabilização final do CI (infraestrutura)

**Baseline:** main `b23d334418cf0c615e4d7cc7f981441b9197dfff` (inclui #56 com `in-process` nos 3 steps já protegidos).
**Falha tratada:** run `45586822028`, job `139110421715` — `OutOfMemoryError: Java heap space` em `:app:compilePublicAppReleaseKotlin` durante `Lint release` (42m33s), propagado por `Show lint failure log`; builds subsequentes `skipped`.

## Delta (somente `.github/workflows/android-build.yml`)
Aplicados os parâmetros já comprovados pelo #56 aos três steps restantes:
- `Lint release` (`:app:lintPublicAppRelease`)
- `Build debug APK` (`:app:assemblePublicAppDebug`)
- `Build syncBeta debug APK` (`:app:assembleSyncBetaDebug`)

Parâmetros: `--no-daemon --max-workers=1`, `-Dorg.gradle.jvmargs="-Xmx3072m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8"`, `-Dkotlin.daemon.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"`, `-Pkotlin.compiler.execution.strategy=in-process`. `set -o pipefail` e `tee` preservados em todos.

**Preservado integralmente:** `continue-on-error` pré-existente, steps de log de falha, uploads, assinatura, gates androidTest, testes, lint, assemblies e códigos de saída. Nenhum arquivo Android, teste ou `gradle.properties` alterado.

## Evidência local (host-only, sem ADB)
`:app:compilePublicAppReleaseKotlin --rerun-tasks` (compilação **real** da variante release, 19/19 tarefas executadas, sem cache):
- `Finished executing kotlin compiler using IN_PROCESS strategy` (2×) — estratégia efetiva confirmada por evidência do compilador;
- `BUILD SUCCESSFUL in 3m 14s` (com `--info`); execução anterior sem `--info`: `BUILD SUCCESSFUL in 3m 10s`;
- pico de memória residente: **2.394.964 KB (~2,34 GB)**, dentro do heap de 3 GB — sem OOM.

Logs: `qa-b2/p9-final-local-release.log`, `p9-final-local-release-info.log`.

## Aceite pendente no CI
1. PR Draft com apenas workflow + relatório.
2. CI no HEAD final: lint, builds, assinatura e gates verdes, sem OOM.
3. Pelo menos uma compilação real da variante release nos logs do step (não apenas `FROM-CACHE`/`UP-TO-DATE`).
4. Registrar duração/consumo quando disponível; erro real de compilação continua bloqueando o job.
5. Sem retentativa do run vermelho anterior, merge, release, bump, ADB ou instrumentação.
