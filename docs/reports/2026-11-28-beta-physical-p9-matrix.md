# BETA-PHYSICAL/P9 — matriz de homologação física

**Candidato:** `901d74fbf1f9120bd35be534a8d9ca9c3a8f32fd` (PR #86)
**Baseline host:** P8 PASS (GO host; NO-GO distribuição).
**Status:** testes independentes **implementados e compilados**; execução física **BLOCKED**; provider fake **BLOCKED em A**.
**PR Draft de testes:** https://github.com/guilhermemellojw/better-talker/pull/89
**Compilação androidTest:** BUILD SUCCESSFUL; APK SHA-256 `df60bcf6000fafacc8c1ec42c832f6c30fc67b11f24b214d82bffd93e4e019a6`.
**Regra:** compilação/host-only **não** é PASS físico.

## 1. P8-1..P8-8

| Gate | Teste/harness | Status | Dependência |
|---|---|---|---|
| **P8-1** identidade/assinatura do APK | C: build/sign; hash/cert/versão/ABI | **BLOCKED** | C + autorização |
| **P8-2** Room real + ViewModel/reabertura | `PhysicalViewModelRestartTest`, `PhysicalRoomFixture` | **READY (compilado); BLOCKED device** | A34 |
| **P8-3** CAS | `PhysicalCasRoomTest` | **READY (compilado); BLOCKED device** | A34 |
| **P8-4** provider fake/history | `PhysicalProviderPendingTest.kt.pending` | **BLOCKED** | A `ProviderSource` + 5º parâmetro |
| **P8-5** AI-09 | pending + `Ai09JourneyIntegrationTest` (host) | **BLOCKED** | A + A34 |
| **P8-6** UI/cópia/inserção/cancelamento/consentimento | ViewModel copy/insert (host/device); UI Compose pendente | **PARCIAL/BLOCKED** | A34 + UI harness |
| **P8-7** update preservando dados | C + instrumentação/UID app | **BLOCKED** | C + dispositivo userdebug/root/UID app |
| **P8-8** J05/F8/Palco | host PASS; smoke físico pendente | **BLOCKED físico** | APK + A34 |

## 2. Testes independentes implementados

### `PhysicalCasRoomTest`

- `casLegitimoAtualizaPayloadEProveniencia`
- `casAposEdicaoConcorrenteNaoSobrescreve` (latch determinístico)
- `casAposExclusaoNaoAtualiza`
- `casAposDesativacaoNaoAtualiza`
- `casAposInvalidacaoDeProvenienciaNaoAtualiza`
- `doisEscritoresCasApenasUmVence` (dois writers simultâneos; exatamente um `1`)
- `casPersisteAposFecharEReabrirArquivo`
- `casComExpectedErradoNaoAtualiza`

**PASS:** CAS 1 só com payload esperado/id ativo; 0 após edição/exclusão/desativação/invalidação; payload não sobrescrito; um único vencedor concorrente. **FAIL:** qualquer overwrite ou dois vencedores.

### `PhysicalViewModelRestartTest`

- `provenienciaPersisteERecuperaAposReabrirArquivo`
- `recriacaoDoViewModelMantemCopiaEInsercao`
- `rejeitadoNaoCopiaNemInsereAposRestart`
- `cacheAcompanhaInvalidacaoNoBanco`
- `conteudoValidoSobreviveAoRestartSemPerda`

**PASS:** Room em arquivo, `ViewModelStore.clear()`, cópia/inserção válidas, rejeitado bloqueado, cache acompanha invalidação, conteúdo/timestamps preservados. **FAIL:** vínculo perdido, rejeitado copiável, cache divergente.

### Pendente A (`PhysicalProviderPendingTest.kt.pending`)

- histórico enviado ao provider sem rejeitado;
- fonte rejeitada bloqueia antes do provider (zero requests);
- cancelamento durante streaming não persiste final;
- AI-09 com fonte persistida/sem fallback;
- continuação concorrente não sobrescreve edição;
- conteúdo sem fundamento não vira card/cópia.

**PASS:** zero requests quando bloqueado; `LlmRequest.history` sem fragmento rejeitado; AI-09 preserva `sourceMessageId`/corpus/refs/escopo; cancelamento sem final; CAS na continuação. **FAIL:** chamada indevida, fallback vivo, card/cópia sem fonte.

## 3. Comandos

```bash
# host: compila o APK androidTest, sem dispositivo
./gradlew :app:assemblePublicAppDebugAndroidTest \
  --offline --no-daemon --max-workers=1 --no-build-cache

# física (somente com autorização A34):
./gradlew :app:connectedPublicAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bettertalker.app.beta.physical.PhysicalCasRoomTest
./gradlew :app:connectedPublicAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bettertalker.app.beta.physical.PhysicalViewModelRestartTest
```

## 4. Critérios de parada

- Qualquer FAIL/BLOCKED interrompe a rodada; não compensar com média/host.
- Não aceitar compilação, teste JVM ou teste puro como PASS físico.
- Room real em arquivo; não substituir por mocks/in-memory nos cenários P8-2/P8-3.
- CAS concorrente exige exatamente um vencedor e zero overwrite.
- Provider fake: único, via `ProviderSource` de A; sem segunda infraestrutura.
- Evidência por teste: SHA/APK, classe/método, timestamp, PASS/FAIL/BLOCKED, XML, logcat, hash do banco temporário.
- Dados: backup antes do update; restaurar ao final; nenhum dado pessoal em evidência.
- **Zero chamadas pagas** no plano inicial; smoke real exige orçamento/autorização separada.

## 5. Autorizações necessárias

1. A34 disponível e autorização física.
2. Instalação do APK assinado (C) e execução de `connectedAndroidTest` (ADB interno).
3. A: `ProviderSource` + 5º parâmetro no ViewModel; permissão para mover os `.pending` para `.kt`.
4. C: build/assinatura/hash/cert/versão; update/rollback.
5. Backup/restauração de dados e teste de update no UID do app (P8-7).
6. Se houver smoke com provider real: orçamento e autorização separados; caso contrário, 0 chamadas.

## 6. Dependências restantes

- **A:** implementação produtiva do contrato P10 (`ProviderSource`, `DefaultProviderSource`, injeção nos ViewModels); sem ela P8-4/P8-5 e a maior parte de P8-6 não executam.
- **C:** APK assinado do SHA exato, versão/hash/cert, update/rollback.
- **D:** executar os testes no A34, coletar evidência e revisar os resultados; não autodeclarar PASS.
- **Coordenação:** revisão independente dos testes/fixtures e autorização física.

**Não há PASS físico nesta rodada.** GO host P8 permanece; distribuição NO-GO.
