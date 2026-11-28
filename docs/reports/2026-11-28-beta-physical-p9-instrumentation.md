# BETA-PHYSICAL/P9 — entrega de instrumentação (D)

**Branch:** `test/beta-physical-p9-instrumentation`
**Base:** `901d74fbf1f9120bd35be534a8d9ca9c3a8f32fd` (PR #86 certificado P8)
**PR Draft:** https://github.com/guilhermemellojw/better-talker/pull/89
**Ownership:** D = `androidTest`/fixtures; A = `ProviderSource`/injeção; C = APK/assinatura.
**Estado:** testes independentes **implementados e compilados**; provas físicas **BLOCKED**; provider fake **BLOCKED em A**.
**Sem PASS físico, merge, ADB, instalação, LLM pago, versão ou release.**

## 1. Arquivos entregues

| Arquivo | Conteúdo |
|---|---|
| `androidTest/.../beta/physical/PhysicalRoomFixture.kt` | Room real em arquivo; seed de nota/chat/proveniência; reabrir/limpar |
| `androidTest/.../beta/physical/PhysicalCasRoomTest.kt` | 8 testes de CAS real, concorrência e reabertura |
| `androidTest/.../beta/physical/PhysicalViewModelRestartTest.kt` | 5 testes de ViewModel/Room/cópia/inserção/cache/restart |
| `androidTest/.../beta/physical/README.md` | comandos, critérios, ownership |
| `androidTest/.../beta/physical/pending/PhysicalProviderPendingTest.kt.pending` | testes preparados para o contrato P10 de A |
| `beta-regression-p1/P9-PHYSICAL-MATRIX.md` | matriz P8-1..P8-8, critérios, dependências e autorizações |

Nenhum arquivo produtivo ou de build foi alterado.

## 2. Cenários independentes implementados

- Room real com banco de arquivo temporário.
- Persistência/recuperação de proveniência após fechar/reabrir.
- Recriação do ViewModel via `ViewModelStore.clear()`.
- CAS legítimo (1 linha; payload/proveniência atualizados).
- CAS após edição concorrente (latch determinístico; 0 linhas; edição preservada).
- CAS após exclusão (0; linha ausente).
- CAS após desativação (0; `isActive=0`; payload preservado).
- CAS após invalidação da proveniência (0; `fromPayload` null; texto preservado).
- Dois escritores CAS simultâneos (exatamente um vencedor).
- CAS após reabertura do arquivo (1; persistência).
- Consistência banco × cache (`messages`/`eligibleCopyText` após edição).
- Conteúdo válido após restart; rejeitado não copia/insere.

## 3. Compilação

```bash
./gradlew :app:assemblePublicAppDebugAndroidTest \
  --offline --no-daemon --max-workers=1 --no-build-cache
```

Resultado: **BUILD SUCCESSFUL**; `app-publicApp-debug-androidTest.apk` gerado.
Isso prova **compilação**, **não** execução física/PASS.

## 4. Dependências

### A — provider fake/history (P8-4/P8-5)

Contrato P10:
- interface `com.bettertalker.app.data.llm.ProviderSource`;
- `DefaultProviderSource`;
- 5º parâmetro `providerSource` em `CopilotViewModel` (e `GlobalAssistantViewModel`);
- todos os `ProviderFactory.resolveRemote/createFor/useRemoteRoute` roteados pela fonte injetada.

Depois disso: renomear `PhysicalProviderPendingTest.kt.pending` para `.kt`, compilar e executar. **Não criar segundo fake.**

### C — APK/assinatura (P8-1/P8-7)

- APK assinado do SHA exato, hash/cert/versão/ABI.
- Update/rollback e instrumentação no UID do app (o export interno não cobre chat/decisões; `allowBackup=false` impede backup convencional).

### A34 — execução física

- Instalação do APK assinado + APK androidTest.
- `connectedAndroidTest` (ADB interno) por classe.
- Backup/restauração de dados; nenhum dado pessoal em evidência.

## 5. Autorizações solicitadas

1. Execução física no A34 e instalação do APK assinado.
2. `connectedAndroidTest` para `PhysicalCasRoomTest` e `PhysicalViewModelRestartTest`.
3. A: publicar `ProviderSource` + injeção; autorizar a promoção dos `.pending`.
4. C: build/assinatura/hash/cert/versão e update/rollback.
5. Se houver smoke com provider real: orçamento/autorização separados; **plano inicial = 0 chamadas pagas**.

## 6. Critérios de parada

- Qualquer FAIL/BLOCKED interrompe; não compensar com compilação/host.
- Room real em arquivo; não usar mocks/in-memory em P8-2/P8-3.
- CAS concorrente: exatamente um vencedor; nenhum overwrite.
- Provider fake único via `ProviderSource` de A; sem infraestrutura paralela.
- Evidência: SHA/APK, classe/método, timestamp, PASS/FAIL/BLOCKED, XML, logcat, hash do banco; sem dados pessoais.
- **Não declarar PASS físico** sem execução no A34 e revisão independente.

## 7. Revisão solicitada

Solicito revisão independente de A/C/coordenação para:
- fixtures e testes `androidTest`;
- aderência ao contrato P10 (sem segunda infraestrutura de fake);
- critérios de CAS/concorrência e evidências;
- autorizações e sequência física.

A autoria destes testes **não** substitui a revisão independente da implementação produtiva de A nem do candidato combinado.
