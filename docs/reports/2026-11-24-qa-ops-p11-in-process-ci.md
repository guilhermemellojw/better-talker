# QA-OPS-P11 — estratégia Kotlin in-process no CI (experimental)

**Baseline:** main `b963b936e33a61c5283a49b79b6ae77032d1e8a1`.
**Escopo:** somente `.github/workflows/android-build.yml` (3 steps do #49) e este relatório. Nenhum `gradle.properties`, código produtivo, teste, lint, assembly ou assinatura foi alterado.

## Problema
`Compile androidTest (syncBeta)` falhou no PR #52 com `OutOfMemoryError: Java heap space` em `compileSyncBetaDebugKotlin`, embora o step já tivesse 3 GB Gradle / 1 worker desde o #49. No mesmo SHA, o run push teve `OOMErrorException` no daemon Kotlin e **fallback** bem-sucedido para `Compile without Kotlin daemon`. A margem do daemon de 2 GB é intermitentemente insuficiente.

## Correção da coordenação aplicada
Usar a propriedade documentada `-Pkotlin.compiler.execution.strategy=in-process` (não `-D`). Adicionada a:
- `Run unit tests`
- `Compile androidTest (publicApp)`
- `Compile androidTest (syncBeta)`

Mantidos integralmente: `--no-daemon`, `--max-workers=1`, `-Dorg.gradle.jvmargs="-Xmx3072m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8"`, `-Dkotlin.daemon.jvmargs=...`, `set -o pipefail`, `tee`, `continue-on-error` pré-existente, steps de log de falha, lint, assemblies, upload e verificação de assinatura.

## Evidência local de eficácia (host-only, sem ADB)
| Execução | Evidência do compilador |
|---|---|
| sem `-P`, com `-Dkotlin.daemon.jvmargs=2048m` | daemon Kotlin iniciado com `-Xmx1024m -XX:MaxMetaspaceSize=256m` (valor de `gradle.properties`); `Finished executing kotlin compiler using DAEMON strategy`; daemon crashou e usou fallback. **`-D` não é efetivo.** |
| com `-Pkotlin.compiler.execution.strategy=in-process`, `--rerun-tasks` | `Finished executing kotlin compiler using IN_PROCESS strategy`; `Compilation mode: in-process compilation`; **25/25 tarefas executadas** (sem cache); BUILD SUCCESSFUL em 5m51s. |

Logs.locais: `/home/guilherme/btwork/qa-b2/p11-local-inprocess-rerun.log` e `p11-local-default-evidence.log`.

## Aceite pendente no CI
1. 3 execuções consecutivas sem OOM, incluindo `pull_request`.
2. Pelo menos uma compilação real (não apenas cache/UP-TO-DATE) — verificar log do job.
3. Erro real de compilação continua bloqueando o job.
4. Duração e impacto registrados; sem retentativas indefinidas.

**Status:** PR Draft, sem merge. Proposta P9 (lint/assemblies) permanece separada, sem aplicação. APKs/fixtures do checkpoint físico preservados.
