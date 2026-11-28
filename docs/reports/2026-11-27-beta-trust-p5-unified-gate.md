# BETA-TRUST/P5 — fechamento unificado da elegibilidade de geração

**Baseline:** PR #86 @ `f05ce35c399d60a5fba3fe12defe2e90d8f4d850`.
**Coordenação:** A como único editor produtivo, registrada em `7237420428`; C não editou a branch nesta rodada.

## Matriz de caminhos de geração

| Caminho | Antes | Depois | Teste |
|---|---|---|---|
| `verifyGeneratedText` com corpus vazio | usava só o gate central | gate central antes e depois do `GroundednessVerifier`, com aviso | `gapEmptyOpenQuote` |
| `verifyGeneratedText` com corpus não vazio (oratória) | `GroundednessVerifier` puro — aspas abertas passavam | gate central antes/depois; `UNFINISHED_REDACTED`, texto vazio, aviso | `gapNonemptyOpenQuote` |
| “Insira tudo”/estrutura | mesmo bypass | mesmo gate unificado; blank → aviso e nada aplicado | `gapNonemptyClosedInventedQuote` + suíte |
| Chat final | já bloqueado | inalterado, aviso preservado | probes canônicas 10/10 |
| Histórico/cópia/card/inserção | já checavam `allowed` | inalterado | 30/30 focados |
| Revisão (`runDraftRewrite`/`launchPointRewrite`) | fonte rejeitada não era checada antes do provider | `rewriteFrom` rejeita `source.rejected`; checagem de proveniência/elegibilidade antes do provider | `gapRewriteFromRejectedSource` |
| `safeText` | retornava `.text` mesmo bloqueado | retorna `""` quando `!allowed` | `gapSafeTextReturnsEmptyWhenBlocked` |
| Continuação | validava após o provider e gravava por id | valida antes, relê e compara payload/hash, atualização CAS, cache só após sucesso, sem corpus vivo | `gapConcurrentEditInvalidatesContinuation` |

## Evidências
- 8 gap probes de D/P6: **8/8**.
- 10 probes canônicas P4: **10/10**.
- 30 focados anteriores: **30/30**.
- AI-09/pedagogia/J05/F8 e demais conjuntos preservados na suíte integral.
- Suíte JVM integral, execução única, sem builds concorrentes: **4692 testes, 0 falhas, 0 erros, 0 skipped** (469 suítes).

## Observação técnica
Foi adicionado um único `@Query` CAS em `ChatDao` (`UPDATE ... WHERE id AND payload = expected AND isActive = 1`); sem migration de schema, sem mudança de arquitetura do provider.

## Limitações
Homologação física (Room/ViewModel real com recriação, provider fake capturando `LlmRequest.history`, UI, J05/F8 no APK combinado) permanece pendente — sem ADB/instalação nesta rodada. Não autocertificando; aguardando D/P7.
