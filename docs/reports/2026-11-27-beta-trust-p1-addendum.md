# BETA-TRUST/P1 — adendo obrigatório: elegibilidade central do conteúdo (Agente A)

**Origem:** auditoria independente BETA-REGRESSION/P2 do Agente D (P1 nas rotas de utilização de conteúdo).
**Escopo:** somente os bloqueadores do beta; nenhuma chamada paga, ADB, merge ou publicação.

---

## A. Achados tratados

| Achado D | Tratamento |
|---|---|
| Verifier warn-only | `verifyChatTextForDelivery` usa o contrato central com remoção efetiva; modo aviso permanece apenas para medição strict shadow |
| `canInsertCard` só status | `applyCard` exige `ContentEligibilityGate.forCardInsert` antes de gravar seção/nota |
| `applyCard` não consulta verifier | gate central consultado; texto saneado é o gravado no editor |
| Cópia do card com texto cru | `ChatScreen` copia `vm.eligibleTextForUse(cardText)` |
| Inserção no tópico sem proveniência | mesma decisão central em `applyCard` (SECTION/NOTE) |
| Histórico reutiliza payload sem filtragem | `forHistory` no contrato; resposta já é armazenada saneada |
| Cancelamento persiste parcial | `sanitizePartialForDisplay` no streaming e no cancelamento |
| Editor sem garantia por trecho | rascunho/revisão usam `DossierFidelityCheck` + `draftAcceptAllowed`; botão de aceite desabilitado quando há citação sem fonte |

## B. Contrato central

`ContentEligibilityGate` (`data/copilot/ContentEligibilityGate.kt`) é a única decisão de elegibilidade. Expõe `decide` e as oito entradas nomeadas: `forFinalAnswer`, `forHistory`, `forResponseCopy`, `forCardCopy`, `forCardInsert`, `forTopicInsert`, `forCancelledPartial`, `forDraftReview`. Todas delegam ao mesmo `SourceFidelityGate.enforce`, com proveniência always-on: citação literal exige texto recuperado no corpus; referência da pergunta nunca autoriza citação literal; paráfrase marcada não é falso positivo.

`ContentEligibility.allowed` exige `!blocked` **e** texto não vazio. Nenhuma rota depende apenas de aviso visual, status de card ou verificação na interface.

## C. Preservação

- Texto válido com fonte recuperada permanece integral (TNM/publicação).
- Autoria manual e pergunta do usuário não são filtradas (`UserMessageActions` mantém cópiacrua da pergunta).
- Consentimento explícito e registros anteriores: cards legados sem corpus disponível só são inseridos se já possuírem verificação local sem pendências (`insufficient == 0 && partial == 0`); nunca por `AVAILABLE`.
- Nenhum discurso existente é apagado silenciosamente; a decisão atua em texto gerado antes de cópia/inserção/aceite.

## D. Testes fail-first

`BetaTrustSourceFidelityTest` ampliado com:

1. `p1_todasAsOitoRotasUsamOMesmoContratoDeElegibilidade` — afirmação inventada é removida e bloqueada nas oito rotas.
2. `p1_conteudoFundamentadoPermaneceUtilizavelEmTodasAsRotas` — texto TNM conferido permanece integral nas oito rotas.

Regressões pré-existentes (AI-01, AI-02, AI-05, AI-06, AI-08, AI-10) permanecem. Nenhuma chamada de rede, LLM, Room, ADB ou dispositivo.

## E. Coordenação com C

Arquivos compartilhados modificados: `CopilotViewModel.kt` (gate de entrega, corpus do turno, `eligibleTextForUse`, `cardEligible`, `applyCard`, saneamento de parcial/cancelamento), `ChatScreen.kt` (cópias de resposta e card passam pelo contrato), `DraftSheet.kt` (aceite desabilitado sem fidelidade). Componentes de card (`CardFirst.kt`) não tiveram regras alteradas; a decisão de proveniência foi adicionada no ponto de uso (`applyCard`), não no status. Recomenda-se que C revise estes pontos antes do merge beta.

## F. Limitações

O contrato é puro e determinístico; a fronteira de confiança (acervo recuperado de fonte certificada, verificação efetiva do modelo) permanece responsabilidade da solução de fidelidade em desenvolvimento. Nenhuma transmissão real, prompt pago, chamada de LLM ou operação de dispositivo foi executada.
