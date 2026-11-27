# BETA-TRUST/P1 — fidelidade obrigatória às fontes

**Baseline:** `main` @ `26c82f5cd6b5412cdce2dde68068a9a2f5e12fd8`.
**Evidência:** `/home/guilherme/btwork/qa-b2/final-beta-82/05-ai/AI-EVALUATION.md` (beta 82, NO-GO por citações sem proveniência).
**Propriedade:** Agente C liberou os gates TRUST e os call sites de `CopilotViewModel.kt` para esta missão; J05 e F8 não foram alterados.

## Causa estrutural

1. O modelo citava versículos e publicações sem que o texto correspondente estivesse no corpus do turno; o verifier era **warn-only** e o texto seguia intacto para histórico, rascunho e cópia.
2. O gate legado comparava versículo apenas pelo **nome do livro** (`RefCanonicalizer.canonicalBook`), permitindo `Jó 38:90` quando qualquer `Jó` estava no corpus.
3. AI-06 mostrava falso positivo: aspas de termo próprio (“Adoração Pura”) eram tratadas como citação bíblica.
4. O parcial de streaming e o turno cancelado eram persistidos sem nenhum gate.
5. O rascunho (`DossierFidelityCheck` + `DraftSheet`) verificava apenas refs, nunca aspas literais, e o aceite era informativo — nunca bloqueava.

## Correção

- **Novo gate puro** `SourceFidelityGate.kt`: classifica `LITERAL_SUPPORTED`, `LITERAL_UNSUPPORTED`, `REFERENCE_SUPPORTED`, `REFERENCE_UNSUPPORTED`, `REFERENCE_FROM_QUESTION`, `PUBLICATION_UNSUPPORTED`, `PARAPHRASE_ALLOWED` e `TERM_QUOTE`. Remove do texto devolvido toda afirmação bíblica sem suporte verificável. Ref citada na pergunta explica a referência, **mas nunca autoriza citação literal** sem o texto TNM/publicação recuperado.
- **Verificador legado**: comparação de versículo passa a exigir livro + capítulo + versículo (ou range integral), não só o livro. Comportamento warn-only preservado para as proteções já certificadas.
- **Entrega do chat**: `verifyChatTextForDelivery()` saneia a resposta final antes de virar histórico/conteúdo copiável; chamado nos caminhos de chat e de esboço colado.
- **Streaming/cancelamento**: o espelho de progresso e `persistCancelledTurn()` usam `sanitizePartialForDisplay()` com o corpus do turno.
- **Rascunho**: `SectionGeneratorImpl` saneia o HTML gerado contra ref+texto recuperados; `DossierFidelityReport.unsupportedLiteralQuotes` e `draftAcceptAllowed()` bloqueiam o aceite no `DraftSheet`.
- **Prompt**: regras explícitas em `ChatPromptBuilder` e `DossierPromptBuilder` — sem texto recuperado, apenas paráfrase marcada, sem aspas e sem atribuição indevida.

## Regressões fail-first (`BetaTrustSourceFidelityTest`)

| Evidência | Regressão |
|---|---|
| AI-01 | refs sem retorno local removidas; ref com texto recuperado preservada |
| AI-02 | aspas inventadas com contexto bíblico removidas |
| AI-05 | citação literal conferida com TNM; ref da pergunta não autoriza a citação |
| AI-06 | frase própria entre aspas sem contexto preservada como `TERM_QUOTE` |
| AI-08 / AI-10 | citações diretas sem fonte sinalizadas e aceite do draft bloqueado |
| AI-10 | parcial de streaming/cancelamento saneado |
| — | paráfrase marcada não é tratada como citação literal |

Antes da implementação o teste falhava em compilação (`SourceFidelityGate`, `FidelityFinding`, `draftAcceptAllowed`, `unsupportedLiteralQuotes` inexistentes) — fail-first funcional.

## Verificação

- `:app:testPublicAppDebugUnitTest` (offline): **4600 testes, 0 falhas** após a correção.
- J05 (`BibleDirectReadP2Test`) e F8 preservados: nenhum arquivo de teste/hotfix alterado.
- Limitações: nenhuma chamada de LLM pago, ADB, dispositivo, publicação ou migração; a bateria syncBeta depende de compilação JVM local (ver resultado no PR).

## Não feito

Sem merge, release, versionCode, credencial, transporte real, migração Room nem alteração em APK beta.
