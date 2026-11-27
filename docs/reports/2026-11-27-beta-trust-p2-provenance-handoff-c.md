# BETA-TRUST/P2 — contrato de proveniência para AI-09 (handoff C)

**PR #84:** Draft, `340a40b6` + correção P2 desta rodada. Sem merge/publicação.
**Objetivo:** permitir que o card revisado de AI-09 reutilize a proveniência do
rascunho/card original sem usar `activeDeliveryCorpus`/`activeQuestionRefs` do
turno atual e sem tratar o texto revisado como fonte.

## 1. Persistência (sem migração de schema)

A proveniência é gravada no `payload` da própria `ChatEntity`, nas chaves:

```text
deliveryProvenance       -> JSON BT-COORD-DELIVERY-PROVENANCE-V1
deliveryProvenanceSha256 -> SHA-256 exato do JSON
```

O JSON contém `messageId`, `turnId`, `noteId`, `scopeId`, `targetKey`,
`corpus[]`, `questionRefs[]`, `contentSha256`, `boundAt` e
`sourceMessageId?`. O texto de `contentSha256` é o texto aplicável do card
(ou o texto da mensagem, quando não há card). `corpus` e `questionRefs` são os
materiais recuperados do turno original; **nunca** o texto gerado.

`DeliveryProvenance.fromPayload(map)` recalcula o SHA-256 e rejeita JSON
ausente, schema desconhecido, digest divergente, listas inválidas ou hash de
conteúdo inválido. O `messageId` interno também precisa coincidir com a
`ChatEntity` proprietária: `deliveryProvenanceFor` e `provenanceForMessage`
rejeitam um vínculo reatribuído a outra mensagem. `ChatDao.get(id)` lê a
mensagem por id (query nova, sem coluna/tabela/migração).

## 2. API para C/AI-09

```kotlin
// Lê a proveniência persistida da mensagem/card original.
suspend fun CopilotViewModel.deliveryProvenanceFor(messageId: String): DeliveryProvenance?

// Cria a proveniência da revisão a partir da fonte.
// Retorna null se sourceText não corresponder ao hash da fonte, se a nota
// divergir ou se revisedText estiver vazio.
DeliveryProvenance.rewriteFrom(
    source = sourceProvenance,
    sourceText = sourceText,          // texto original efetivamente recuperado
    revisedText = revisedText,
    messageId = newMessageId,
    turnId = currentTurnId,
    noteId = currentNoteId,
    scopeId = targetScope,
    targetKey = cardTargetKey(targetCard),
    boundAt = now,
): DeliveryProvenance?

// `post` aceita a fonte; o texto revisado recebe apenas novo hash.
post(..., provenanceSource = sourceProvenance, provenanceSourceText = sourceText)
```

### Fluxo obrigatório de C

1. `sourceMessageId` = card/rascunho anterior recuperado pelo contrato central
   (`ConversationalReferentRecovery`); não varrer histórico manualmente.
2. `sourceProvenance = deliveryProvenanceFor(sourceMessageId)`. Se `null`, não
   criar card revisado; orientar o usuário a gerar novamente/abrir o ponto.
3. `sourceText` = texto do card original, extraído do payload; conferir que
   `sourceProvenance.binds(sourceText)`.
4. `targetKey` = `cardTargetKey` do alvo atual; `noteId`/`scopeId` do card.
5. Chamar `DeliveryProvenance.rewriteFrom(...)` e `post` com
   `provenanceSource`; nunca usar `activeDeliveryCorpus`/`activeQuestionRefs`
   como fallback.
6. Se a proveniência não existir, for de outra nota, estiver adulterada ou o
   conteúdo tiver mudado, o card falha fechado (`cardEligible`/`applyCard`).

## 3. Comportamento fail-closed

- `cardEligible`/`applyCard` sem proveniência persistida: falso/rejeitado.
- `applyCard` exige `provenance.matches(entity.noteId, cardTargetKey(card))` e
  `provenance.binds(rawText)`; conteúdo editado (`updateCardText`) remove a
  proveniência e exige nova verificação.
- `pickCardTarget` preserva corpus/conteúdo e apenas rebinda `targetKey`.
- Restart, novo turno ou troca de seção não usam o corpus vivo; o payload da
  mensagem é a autoridade.
- Registros antigos/manuais sem proveniência permanecem legíveis; não são
  automaticamente elegíveis para inserção de card. Cópia manual (`fromMe`) não
  é bloqueada pelo gate de IA.

## 4. Testes mínimos (A + C)

- `DeliveryProvenanceTest`: roundtrip payload, restart, nota/alvo divergentes,
  digest/conteúdo adulterados, rewrite sem self-corpus, pick de alvo.
- `BetaTrustP2FailFirstTest`: gate central e proveniência persistida.
- C, ao integrar AI-09: card válido após fim do turno/restart; card de outra
  nota/turno não herda corpus; revisão preserva refs fundamentadas e bloqueia
  refs novas; texto revisado não vira corpus; consentimento/REPLACE.
- `J05/F8`: manter `SourceFidelityGate` e `BetaTrustSourceFidelityTest` verdes.

## 5. Limites

A proveniência é local ao SQLite de chat; não é assinatura criptográfica nem
autoridade externa. O SHA-256 detecta edição parcial/rotina, não um atacante
com escrita arbitrária no banco. O gate central de A continua sendo a única
autoridade de proveniência; C não deve duplicar verificadores.
