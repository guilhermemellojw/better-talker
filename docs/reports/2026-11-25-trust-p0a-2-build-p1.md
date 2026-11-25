# TRUST-P0A-2 / BUILD P1 — proteção factual mínima (primeira fatia)

**Base:** `origin/main` `b23d334418cf0c615e4d7cc7f981441b9197dfff`.
**Escopo:** S1 (ref correta + citação incompatível), S2 (aviso ausente), S4 (memória/paramétrico como evidência). S3 verificado, sem escape — não promovido a bloqueador.

---

## Fail-first

Antes da implementação, `FactualSupportProtectionTest` não compilava (`unsupportedQuotes`/`supportNotice`/`retrievedExcerpt` inexistentes) → `:app:compilePublicAppDebugUnitTestKotlin FAILED`. Após a implementação mínima: **BUILD SUCCESSFUL**.

## Mudanças (delta pequeno, somente componentes puros)

1. `data/llm/GroundednessVerifier.kt` — `unsupportedQuotes(text, sources)`: reusa a própria regex `QUOTE`, normalização e isenção de `〈sugestão〉`; **não** é verificação semântica (somente literal).
2. `data/copilot/ChatBiblicalSupport.kt` — delega `unsupportedQuotes`, adiciona `supportNotice()` (refs sem retorno + citações literais sem apoio) e `retrievedExcerpt()` (rótulo restrito ao trecho efetivamente recuperado); `noticeWithBilingualSafety()` passa a usar `supportNotice()` — ponto já chamado por `verifyChatText`, portanto **nenhuma edição em `CopilotViewModel`**.
3. `tests/.../FactualSupportProtectionTest.kt` — 7 testes: S1, S2 (corpus vazio), S4 (memória/usuário/estrutural fora do corpus), combinação de avisos, controle positivo oratório, rótulo do trecho e proteção de parcial (S3).

## Evidências

- `:app:testPublicAppDebugUnitTest --tests FactualSupportProtectionTest` — PASS.
- Regressão focada: `ChatBiblicalSupportTest` (#48) + `GroundednessVerifierTest` + novo teste — **BUILD SUCCESSFUL**.
- Texto da resposta permanece intacto (warn-only); nenhum dado do usuário, rascunho aceito ou decisão persistida foi alterado.

## Limitações

- `retrievedExcerpt()` é contrato puro e **ainda não está ligado** ao `answerAsk()` (que hoje rotula "TNM 2015" e recorta com `take(180/220)` no `CopilotViewModel`) — integração depende de coordenação de ownership com A; não executada aqui.
- Nenhuma heurística de paráfrase foi introduzida: paráfrase sem apoio continua fora da detecção literal (`non liquet`, nunca "verificado").
- Não há verificação semântica (`SemanticVerification` permanece P3, sem integração); não há promoção de `RECORD_MATCH` a autenticidade.
- S3: `EvidenceProvenance(partial=true).isDocumentaryEvidence()==false` e `documentaryTexts()` exclui CONVERSATION/WRITING_MEMORY/STRUCTURAL — proteção existente, sem escape demonstrável nesta fatia.
- Nenhum Gradle pesado fora dos testes focados; sem ADB, instrumentação física, merge, schema, provider, release, bump ou LLM pago.

**PR:** Draft. HEAD e CI registrados na publicação.
