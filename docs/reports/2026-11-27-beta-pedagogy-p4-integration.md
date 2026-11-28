# BETA-PEDAGOGY/P4 — integração A+C (candidato Draft)

**Data:** 27/11/2026  
**HEAD candidato:** branch `feat/beta-integration-p4` (A `ddc80e3e` + C `fe0ca1b3` + main `cc7b4522`; SHA final no PR Draft)  
**Estado:** candidato combinado em branch isolada; PR Draft; sem merge/tag/release/ADB/LLM pago.  
**Certificação:** aguardando D independente.

## 1. Origens

| Papel | SHA | Branch/PR |
|---|---|---|
| A — contrato de proveniência (final P3) | `ddc80e3e0a8d3b568a69f2106f5f6fff6e1ac72e` | `fix/beta-trust-source-fidelity` / #84 |
| C — pedagogia (HEAD certificado) | `fe0ca1b37089977918934ff18f197de8fbf14d2c` | `feat/beta-pedagogy-p1` / #85 |
| main | `cc7b452253d0e6a8039e36c32e4276fd14937e80` | — |

CI dos SHAs exatos: A `ddc80e3e` (`coord-python` PASS, `propose` PASS, `build-apk` verificado antes da publicação), C `fe0ca1b3` (`build-apk`/`propose` PASS), main `cc7b4522` (`coord-python` PASS). Nenhum SHA é publicado com CI vermelho.

## 2. Integração e sobreposições

- Worktree/branch isolada: `feat/beta-integration-p4`, criada a partir de `2b476eeb`.
- Merge de C (`fe0ca1b3`) sobre A: **sem conflitos textuais**. A sobreposição real era semântica em `CopilotViewModel.kt` e foi resolvida manualmente.
- Arquivos de A preservados: `DeliveryProvenance.kt`, `ContentEligibilityGate.kt`, `SourceFidelityGate.kt`, `OratoryFidelityCheck.kt`, `GroundednessVerifier.kt`, `OratoryFidelityCleanup.kt`, `GenerationPrompts.kt`, `GlobalAssistant*`.
- P4.1: fusão de A `ddc80e3e` gerou **um conflito** em `CopilotViewModel.kt` (bloco `storedProvenance` do `post`); resolvido preservando as duas regras: decisão de handoff extraída para `RewriteHandoff` (C) e `revision`/`explicitTargetChange` fail-closed de A. O caminho texto-only declara troca de alvo explícita porque `targetKey=null` significa "sem card", não troca de alvo.
- Main `cc7b4522` mesclado sem conflitos (somente infraestrutura do trust boundary).
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

**PASS — 4667 testes, 0 falhas, 0 erros, 0 skipped**, 466 suites (`BUILD SUCCESSFUL` em 3m30s, `--rerun-tasks`).  
Taxas relevantes: `Ai09JourneyIntegrationTest` 9/0, `DeliveryProvenanceTest` 9/0, `BetaTrustP3ClosureTest` 8/0, `BetaTrustP2FailFirstTest` 10/0, `BetaTrustSourceFidelityTest` 11/0, `PedagogyFidelityIntegrationTest` 5/0, `PedagogyProvenanceIntegrationTest` 7/0, `PointReviewRewriteTest` 13/0.

Fail-first P4.1: `ai09_notaEscopoOuAlvoDivergentesRejeitados` falhava no SHA pré-fecho de A (`2b476eeb`) e passa com `ddc80e3e`; os demais 8 testes da classe passaram antes e depois.  
Focados no SHA combinado: 33 testes AI-09/proveniência (9 + 7 + 9 + 8), 0 falhas.  
Sem LLM pago, sem ADB, sem Room em teste. CI dos SHAs de origem: `coord-python`/`propose` PASS em A/C/main; `build-apk` de A acompanhado antes da publicação.

## 6. Limitações e certificação D

- Os testes de integração exercitam os contratos reais (`ConversationalReferentRecovery`, `DeliveryProvenance`, `ContentEligibilityGate`, `CardPayload`, hash gate); o caminho Room/UI completo depende de D.
- A proveniência é local ao SQLite de chat (SHA-256 detecta edição, não atacante com escrita arbitrária).
- D deve validar: AI-03/AI-04/AI-09 reais, card após encerramento/restart, troca de nota/seção, reatribuição, subponto, ambíguo, consentimento, J05/F8 e ausência de auto-insertion.
- Pacote `beta-distribution-6.1.1-beta.1` permanece HOLD; nenhuma certificação beta integral é declarada.
## 7. BETA-PEDAGOGY/P4.1 — cobertura AI-09 (jornadas reais)

**Origens combinadas:** A `2b476eeb` (pré-fecho) → substituído pelo SHA final de A após P3; C `fe0ca1b3` (HEAD certificado do PR #85); main `cc7b4522`.

### Matriz de cobertura

| Item P4.1 | Evidência (teste real) | Resultado |
|---|---|---|
| Recuperação do referente original | `Ai09JourneyIntegrationTest.ai09_jornadaRecuperaReferentePersisteReabreEInsere` → `ConversationalReferentRecovery.recover` | PASS |
| Proveniência persistida por mensagem | idem (`toPayload`/`fromPayload` + `authorizesCard`) | PASS |
| Revisão efetiva com fonte válida | `PedagogyProvenanceIntegrationTest.ai09_revisaoPreservaProvenienciaAposFimDoTurnoERestart` + gate central | PASS |
| `provenanceSource` ausente rejeitado | `Ai09JourneyIntegrationTest.ai09_provenanceSourceAusenteEhRejeitadaSemFallback` (`RewriteHandoff`, extraído do `post`) | PASS |
| `provenanceSourceText` ausente rejeitado | `Ai09JourneyIntegrationTest.ai09_provenanceSourceTextAusenteEhRejeitada` | PASS |
| Corpus do turno atual não é fallback | `Ai09JourneyIntegrationTest.ai09_corpusDoTurnoAtualNaoEhFallbackDaRevisao` | PASS |
| Texto revisado nunca vira fonte de si próprio | `Ai09JourneyIntegrationTest.ai09_textoRevisadoNuncaViraFonteDeSiMesmo` | PASS |
| Nota, escopo e alvo divergentes rejeitados | `Ai09JourneyIntegrationTest.ai09_notaEscopoOuAlvoDivergentesRejeitados` | PASS com `ddc80e3e` (fail-first confirmado no SHA pré-fecho) |
| Card válido após novo turno e restart | `ai09_jornadaRecuperaReferentePersisteReabreEInsere` + `PedagogyProvenanceIntegrationTest` | PASS |
| Consentimento manual obrigatório | `Ai09JourneyIntegrationTest.ai09_consentimentoManualEFalhaFechadaNoReplace` | PASS |
| BODY/SUBPOINT/AMBIGUOUS preservados | `Ai09JourneyIntegrationTest.ai09_corpoSubpontoEAmbiguoPreservados`, `PointReviewRewriteTest` | PASS |
| REPLACE sem duplicação de subpontos | `PedagogyProvenanceIntegrationTest.ai09_corpoSubstituivelNaoDuplicaSubpontos` | PASS |
| AI-03/04 preservados | `Ai09JourneyIntegrationTest.ai03Ai04_corpusDoPontoAnteriorContinuaAutorizandoGate`, `PedagogyFidelityIntegrationTest` | PASS |

### Nota de método

O flavor posterior possui apenas JUnit4 + sqlite-jdbc (sem Robolectric/UI automator). As jornadas são exercitadas nos componentes reais usados pelo app — `ConversationalReferentRecovery`, `DeliveryProvenance` (payload persistido), `RewriteHandoff` (decisão extraída do `post`) e `ContentEligibilityGate` — sem LLM pago, ADB ou dispositivo. A verificação de UI/Room completa permanece com D.
