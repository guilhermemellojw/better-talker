# BETA-TRUST/P4 — fechamento residual do candidato integrado

**Baseline:** PR #86 @ `a8b1cfd0219f50a5ed20833247f2800e6f7cd946`, `main` @ `cc7b4522`.
**Coordenação:** A como único editor produtivo; aviso publicado em `7230248620`. API/call site AI-09 permanece com C.

## Matriz

| Item | Causa | Correção | Call site | Teste | Resultado |
|---|---|---|---|---|---|
| P1-B | `decide()` reditia trecho incompleto mas mantinha `allowed=true`; consumidores usavam `.text` sem checar `.allowed` | `FidelityFinding.UNFINISHED_REDACTED`, `allowed=false`, aviso; `DeliveryProvenance.rejected` persiste o bloqueio após restart | `ContentEligibilityGate.decide`, `verifyChatTextForDelivery`, `eligibleTextForUse`, `eligibleCopyText`, `eligibleCardCopyText`, `insertEligibleTextIntoScope`, `persistCancelledTurn`, `verifyGeneratedText` | `Pr84P4ProbeTest` (10/10) + `BetaTrustP4ResidualTest` | PASS |
| P2.1 | Inserção usava `forHistory` | Rota real agora usa `forTopicInsert` com proveniência persistida | `insertEligibleTextIntoScope` (chamado por `ChatScreen`) | `p21_topicInsertRouteUsesItsOwnContract` | PASS |
| P2.2 | `cardEligible` sem consumidores | Helper removido | — | busca de consumidores | PASS |
| P2.3 | Comentário descrevia fail-open | Atualizado para o comportamento atual | `GenerationVerification.verifyGeneratedText` | revisão de diff | PASS |
| P2.4 | Continuação alterava payload sem renovar proveniência | `continueFrom` exige vínculo válido, revalida trecho e conteúdo final, preserva identidade/nota/escopo/corpus/refs, sem fallback para corpus vivo | `continueGeneration` | `p24_*` | PASS |

## Verificação
- Probes independentes D: **10/10** (cópia literal do arquivo `Pr84P4ProbeTest.kt`, critério inalterado).
- Focados anteriores: **30/30**.
- AI-09/pedagogia/J05/F8: 112 testes relacionados no resultado completo (superset dos 107 de D).
- Suíte JVM integral, execução única, sem builds concorrentes: **4684 testes, 0 falhas, 0 erros, 0 skipped** (468 suítes).
- CI a registrar no SHA final.

## Limitações remanescentes
- UI física/ADB, provider fake com `LlmRequest.history`, Room real com recriação/reabertura e validação de dispositivo permanecem fora desta rodada (sem ADB/instalação).
- Sem autocertificação: aguardando recertificação independente D/P6.
