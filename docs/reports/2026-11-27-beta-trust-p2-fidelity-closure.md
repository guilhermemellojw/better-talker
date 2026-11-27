# BETA-TRUST/P2 — fechamento focal da fidelidade (Agente A)

**PR #84** · HEAD de referência `340a40b6493e5bd401c0c98ff3eb11599d831301` · branch `fix/beta-trust-source-fidelity` (PR Draft, sem merge).
**Coordenação:** alterações em `CopilotViewModel.kt` e cards são compartilhadas com C; os fluxos pedagógicos do PR #85 não foram sobrescritos.

> Nenhum merge, publicação, LLM pago, ADB, dispositivo, credencial, prompt real ou transmissão.

---

## A. Fail-first e classificação

Sondas JVM puras em `BetaTrustP2FailFirstTest.kt`, executadas **antes** das correções com `--offline --no-daemon`: 9 testes, 6 falhas.

| # | Verificação | Antes | Depois |
|---|---|---|---|
| 1 | Proveniência do turno/card original (não `activeDeliveryCorpus` corrente) | Falha conceitual: `eligibleTextForUse` lia estado mutável | PASS — `DeliveryProvenance` por mensagem + `provenanceForMessage` |
| 2 | Reabertura após restart, troca de nota/seção, resposta posterior | Falha conceitual: estado em memória | PASS — proveniência persistida no payload, `binds`/`matches`, fail-closed |
| 3 | Citação curta explicitamente atribuída à TNM | **FALHA** (TERM_QUOTE) | PASS — `EXPLICIT_TNM` passa a tratar como afirmação |
| 4 | Range completo e versículo exato (1 ≠ 10/11) | **FALHA** (range lido dentro do match) | PASS — lookahead + `canonContains` com fronteira de dígito |
| 5 | Texto de outra passagem não autoriza atribuição | **PASS** já | mantido — suporte exige ref vizinha no mesmo trecho |
| 6 | Concatenação artificial de fontes | **PASS** já | mantido — uma única entrada do corpus deve conter a citação |
| 7 | Aspas abertas, citação fragmentada, ref incompleta no parcial | **FALHA** | PASS — `redactUnfinished` em `sanitizePartialForDisplay` |
| 8 | Oito rotas reais consultam o contrato | Falha conceitual (funções puras) | PASS — call sites reais listados abaixo |
| 9 | Exceções de publicação e registros legados | **FALHA** (`be`/`th` isentos) | PASS — nenhuma sigla é exceção; legados exigem vínculo/hash |

## B. Matriz de cobertura das rotas reais

| Rota | Call site | Entrada do contrato |
|---|---|---|
| Resposta final | `CopilotViewModel.verifyChatTextForDelivery` | `forFinalAnswer` |
| Histórico/reabertura | `CopilotViewModel.eligibleTextForUse` | `forHistory` |
| Cópia de resposta | `CopilotViewModel.eligibleCopyText` ← `ChatScreen` | `forResponseCopy` |
| Cópia de card | `CopilotViewModel.eligibleCardCopyText` ← `ChatScreen` | `forCardCopy` |
| Inserção de card | `CopilotViewModel.applyCard` | `forCardInsert` |
| Inserção no tópico/editor | `CopilotViewModel.applyCard` (SECTION/NOTE) | `forTopicInsert` |
| Parcial cancelado | `SourceFidelityGate.sanitizePartialForDisplay` | `forCancelledPartial` |
| Rascunho/revisão | `DossierFidelityCheck.check` | `forDraftReview` |

Todas as rotas leem a proveniência vinculada à mensagem; nenhuma usa apenas o estado global mutável. Texto manual (`fromMe`) permanece intacto.

## C. Correções aplicadas

- `EXPLICIT_TNM`: aspas curtas com atribuição explícita à TNM são afirmações, não termos.
- `canonContains`: correspondência de chave canônica com fronteira de dígito — v.1 não casa com v.10/11.
- Range e listas lidos no texto após o casamento da regex, com todos os versículos exigidos.
- Citação aceita somente se **uma** entrada do corpus contém o texto **e** a referência vizinha; fim de exceções `be`/`th`.
- `redactUnfinished`: remove aspas abertas e referência truncada do parcial copiável/persistível.
- `DeliveryProvenance`: hash do conteúdo, nota/escopo, corpus/refs e validade persistidos no payload da mensagem; `rewriteFrom` exige hash da fonte; falha fechada sem vínculo.

## D. Preservação

Conteúdo fundamentado (TNM recuperada) permanece integral; autoria manual e consentimento preservados; J05/F8 e registros anteriores não apagados. Cards legados sem vínculo não são promovidos automaticamente.

## E. Testes e evidências

- Fail-first: 9 sondas, 6 falhas antes da correção.
- Pós-correção: suíte focada `BetaTrustP2FailFirstTest` + `BetaTrustSourceFidelityTest` **21/21 PASS**.
- Suíte JVM completa: `BUILD SUCCESSFUL` em execução isolada com `--max-workers=1`; 460 classes XML, **4620 testes, 0 falhas, 0 erros, 0 skips**.
- Builds anteriores falharam por ambiente (SIGSEGV do JIT, heap do KAPT e escrita concorrente de XML por build paralelo), não por regressão de código.

## F. Veredito

Fechamento focal entregue para auditoria independente. Sem autocertificação, sem transmissão real e sem autorização de piloto. PR #84 permanece Draft.
