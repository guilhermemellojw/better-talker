# BETA-TRUST/P3 — fechamento da rota real de entrega

**Baseline:** PR #84 @ `2b476eeb7967eb6bf5cc116c6bfb4b3eedd8bb41` · `main` @ `cc7b452253d0e6a8039e36c32e4276fd14937e80`.
**Parecer D:** `7228795108` — FAIL P1. Esta rodada fecha apenas os bloqueadores reproduzidos.
**Coordenação:** API definitiva de revisão comunicada ao Agente C em `7228877833`; C é dono do call site AI-09.

## Matriz P1 → reprodução → correção → call site → teste → evidência

| P1 | Reprodução (D) | Correção | Call site | Teste |
|---|---|---|---|---|
| Payload cru em histórico/inserção | `conversationTurnsScoped()` e `ChatScreen → insertTextIntoScope(item.text)` | `historyTextFor()` e `insertEligibleTextIntoScope()` usam `eligibleTextForUse()` com proveniência persistida; `lastCopilotText()` também saneia; legado sem vínculo vira marcador sem apagar o registro | `CopilotViewModel.conversationTurnsScoped`, `resolveInsertCommand`, `lastCopilotText`; `ChatScreen` (dois call sites) | `BetaTrustP3ClosureTest` (rotas do contrato) + 30 testes anteriores + CI |
| Aspas abertas no resultado final | `PROBE_OPEN allowed=true … findings=[]` | `redactUnfinished()` passou a público e é aplicado em `ContentEligibilityGate.decide` — todas as rotas (final, histórico, card, parcial) | `ContentEligibilityGate.decide` | `probeOpen_aspasAbertasSaoRemovidasNoResultadoFinal` e `probeOpen_aspasLegitimasSaoPreservadas` |
| Corpus vazio fail-open | `PROBE_EMPTY removals=[]` | `verifyGeneratedText` usa `ContentEligibilityGate.forFinalAnswer`; sem corpus, citação literal é removida, explicação/paráfrase permanece | oratória/card antes de `post` | `probeEmpty_corpusVazioBloqueiaCitacaoLiteralMasNaoExplicacao` e `probeEmpty_cardBloqueiaCitacaoSemCorpus` |
| Handoff `provenanceSource` solto | nenhum call site; fallback para `activeDeliveryCorpus` | `post(revision, provenanceSource, provenanceSourceText, explicitTargetChange)` falha fechado: sem fonte/texto ou `rewriteFrom` inválido → nenhuma mensagem gravada | contrato `post`; call site AI-09 permanece com C | `revisaoSemFonteOuComEscopoDiverenteFalhaFechado` |
| Identidade/escopo/alvo | `authorizesMessage` sem `scopeId`; `pickCardTarget` sem `noteId` | `authorizesMessage`/`authorizesCard` com `scopeId`; `withTargetKey` exige autorização explícita; `pickCardTarget` rejeita seção de outra nota; cache de proveniência é recalculado na hidratação | `DeliveryProvenance`, `pickCardTarget`, `hydrateDeliveryProvenance` | `identidadeNotaEscopoAlvoEHashSaoVerificados`, `restartComPayloadAdulteradoNaoRestauraAutorizacao` |

## Preservação
- Autoria manual (`fromMe`) não é filtrada.
- Registros históricos originais permanecem no banco; quando sem vínculo verificável, a exibição recebe marcador compreensível.
- Aspas legítimas e texto com TNM recuperada continuam utilizáveis.
- J05/F8 não foram alterados.

## Limitações
- Nenhum teste instrumentado/Room real nesta rodada (sem ADB/dispositivo); a cobertura de persistência é feita sobre o contrato serializado e sobre os call sites produtivos alterados.
- Não há autocertificação: aguardando D.
