# BETA-PEDAGOGY/P4 — integração A+C (candidato Draft)

**Data:** 27/11/2026  
**HEAD candidato:** branch `feat/beta-integration-p4` (merge A+C `67bc3b55`; SHA final no PR)  
**Estado:** candidato combinado em branch isolada; PR Draft; sem merge/tag/release/ADB/LLM pago.  
**Certificação:** aguardando D independente.

## 1. Origens

| Papel | SHA | Branch/PR |
|---|---|---|
| A — contrato de proveniência | `2b476eeb7967eb6bf5cc116c6bfb4b3eedd8bb41` | `fix/beta-trust-source-fidelity` / #84 |
| C — pedagogia | `fe0ca1b37089977918934ff18f197de8fbf14d2c` | `feat/beta-pedagogy-p1` / #85 |
| main | `26c82f5cd6b5412cdce2dde68068a9a2f5e12fd8` | — |

CI do SHA exato de A: **verde** (2× `build-apk`, `coord-python`, `propose`) antes da publicação do candidato.

## 2. Integração e sobreposições

- Worktree/branch isolada: `feat/beta-integration-p4`, criada a partir de `2b476eeb`.
- Merge de C (`fe0ca1b3`) sobre A: **sem conflitos textuais**. A sobreposição real era semântica em `CopilotViewModel.kt` e foi resolvida manualmente.
- Arquivos de A preservados: `DeliveryProvenance.kt`, `ContentEligibilityGate.kt`, `SourceFidelityGate.kt`, `OratoryFidelityCheck.kt`, `GroundednessVerifier.kt`, `OratoryFidelityCleanup.kt`, `GenerationPrompts.kt`, `GlobalAssistant*`.
- Nenhum verificador/resolvedor novo. C usa `ContentEligibilityGate` e `DeliveryProvenance.rewriteFrom` existentes.
- BODY/SUBPOINT/AMBIGUOUS preservados; subponto/ambíguo entrega texto sem card automático.

## 3. Fluxo de proveniência AI-09

1. `ConversationalReferentRecovery` recupera o card anterior com identidade, nota, escopo, alvo e status.
2. `deliveryProvenanceFor(messageId)` lê o payload persistido (não o corpus vivo).
3. C confere `messageId`, nota, escopo, `targetKey` e `binds(sourceText)`.
4. Releitura da proveniência imediatamente antes do `post`: edição concorrente do card-fonte falha fechado.
5. O texto revisado é revalidado pelo `ContentEligibilityGate` usando **somente** o corpus/refs da fonte (`forCardInsert` + `forTopicInsert` para card; `forDraftReview` para texto-only).
6. `DeliveryProvenance.rewriteFrom` é pré-validado e o `post` recebe `provenanceSource`/`provenanceSourceText`; o texto revisado recebe apenas novo hash, nunca vira corpus.
7. Sem proveniência válida, o card não é criado; o texto revisado não é usado como fonte.
8. Consentimento/hash gate existentes continuam obrigatórios; `REPLACE` cobre apenas o corpo e não duplica subpontos.

## 4. Regressões combinadas

| # | Regressão | Evidência |
|---|---|---|
| 1 | AI-03/AI-04 com continuidade e fontes fundamentadas | `OratoryPointContinuityTest`, `PedagogyFidelityIntegrationTest` (corpus relacionado) |
| 2 | AI-09 revisão efetiva com proveniência original | `PointReviewRewriteTest`, `PedagogyProvenanceIntegrationTest` |
| 3 | Card válido após fim do turno | `PedagogyProvenanceIntegrationTest.ai09_revisaoPreservaProvenienciaAposFimDoTurnoERestart` |
| 4 | Card válido após restart/reabertura | mesmo teste (roundtrip `toPayload`/`fromPayload`) |
| 5 | Reatribuição de proveniência rejeitada | `ai09_payloadReatribuidoAOutraMensagemEhRejeitado`, `DeliveryProvenanceTest` |
| 6 | Troca de nota/seção/conteúdo não reutiliza vínculo | `ai09_provenienciaAusenteOuDeOutraNotaNaoCriaCardElegivel`, `authorizesCard` |
| 7 | REPLACE não duplica subpontos | `PedagogyProvenanceIntegrationTest.ai09_corpoSubstituivelNaoDuplicaSubpontos`, P3 |
| 8 | Referente ambíguo não sobrescreve | `ai09_referenteAmbiguoNaoEscolheCardAntigo` |
| 9 | Sem consentimento não aplica | `ai09_hashGateBloqueiaReplaceSemConsentimentoOuAposEdicao`, P3 |
| 10 | J05/F8 preservados | `BibleDirectReadP2Test`, `BibleDirectAnswerTest`, `PedagogicalUnicodeLiteralTest`, `BetaTrustSourceFidelityTest` |

## 5. Testes executados

Comando (flavor público, in-process, 1 worker):

```bash
./gradlew :app:testPublicAppDebugUnitTest \
  --rerun-tasks --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+UseSerialGC' \
  -Pkotlin.compiler.execution.strategy=in-process
```

**PASS — 4650 testes, 0 falhas, 0 erros, 0 skipped**, 464 suites.  
Taxas relevantes: `DeliveryProvenanceTest` 9/0, `BetaTrustP2FailFirstTest` 10/0, `BetaTrustSourceFidelityTest` 11/0, `PedagogyFidelityIntegrationTest` 5/0, `PedagogyProvenanceIntegrationTest` 7/0, `PointReviewRewriteTest` 13/0.

Testes focados: 22 de pedagogia + proveniência A + J05/F8.  
Sem LLM pago, sem ADB, sem Room em teste; CI do candidato pendente no momento deste relatório.

## 6. Limitações e certificação D

- Os testes de integração exercitam os contratos reais (`ConversationalReferentRecovery`, `DeliveryProvenance`, `ContentEligibilityGate`, `CardPayload`, hash gate); o caminho Room/UI completo depende de D.
- A proveniência é local ao SQLite de chat (SHA-256 detecta edição, não atacante com escrita arbitrária).
- D deve validar: AI-03/AI-04/AI-09 reais, card após encerramento/restart, troca de nota/seção, reatribuição, subponto, ambíguo, consentimento, J05/F8 e ausência de auto-insertion.
- Pacote `beta-distribution-6.1.1-beta.1` permanece HOLD; nenhuma certificação beta integral é declarada.
