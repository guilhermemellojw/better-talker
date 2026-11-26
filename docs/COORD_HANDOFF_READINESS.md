# COORD-08/09 PREP — Protocolo mínimo de transferência operacional e prontidão do supervisor

**Status:** proposta documental em PR Draft. **Não entra em vigor, não ativa modos, não concede autoridade e não altera nenhum documento de governança.**
**Data:** 26/11/2026 · **Main de referência:** `02b89856837d03cbaa11d2f30d5931b4d7e43bd3`
**Fonte normativa:** [COORDINATOR.md](COORDINATOR.md) (carta do Agente S), [DECISIONS.md](DECISIONS.md), [TEAM_COORDINATION.md](TEAM_COORDINATION.md), [ACTIVE_TASKS.md](ACTIVE_TASKS.md), [DELIVERY_POLICY.md](DELIVERY_POLICY.md), [ROADMAP_STATUS.md](ROADMAP_STATUS.md) e contratos COORD-04 no PR #64 (`COMMUNICATION.md`, `PILOT.md`, `coord_pilot*`).

> **Meta:** independência operacional da coordenação via ChatGPT até **10/12/2026**, com supervisão humana. A data é **meta de planejamento**, não autorização para reduzir controles. Se a certificação mínima não estiver completa, aplica-se o plano de continuidade (§11) — nunca a ativação parcial de ASSISTED/DELEGATED.

---

## 0. Escopo, não escopo e princípios

**Escopo:** inventariar responsabilidades da coordenação, classificar automação/delegação/reserva, definir critérios verificáveis de prontidão, cenários adversariais e procedimentos de continuidade/revogação/suspensão.

**Não escopo:** implementar segunda infraestrutura de comunicação ou ledger; alterar código de produto; alterar sessões; executar POST/prompts/LLM; ler credenciais; builds/ADB; merge/release/bump; HealthAgentForms; ativar ASSISTED ou DELEGATED.

**Princípios:**
1. **GitHub/CI/PR/SHA são a fonte de verdade**; relato de agente é dado, não certificado.
2. **Autorização explícita e registrada**; silêncio não é consentimento; presunção é bloqueio.
3. **Falha segura:** na dúvida, não despachar, não reenviar, registrar e escalar.
4. **Reutilizar a fundação COORD-04**; nenhum segundo motor, ledger ou canal.
5. **Mantenedor conserva autoridade final** sobre merge, release, schema, política TRUST/provedor, custo extraordinário, dispositivo e mudança de modo.
6. **Data não reduz controle:** itens da lista A (§6) são gates; itens B podem aguardar piloto supervisionado.

---

## 1. Inventário das responsabilidades atuais da coordenação

| # | Responsabilidade | Fonte/evidência | Cadência | Risco se interrompida |
|---|---|---|---|---|
| R1 | Checkpoint consolidado (main, PRs, CI, sessões, bloqueios, ownership) | COORDINATOR §11; ACTIVE_TASKS §5 | por ciclo | perda de continuidade, retrabalho, duplicidade |
| R2 | Proposta e despacho de missões | COORDINATOR §6 | por missão | agente ocioso, missão duplicada/errada |
| R3 | Preflight de destinatário e ownership | TEAM_COORDINATION §3; COORD-04 | por despacho | escrita em arquivo de outro dono, conflito |
| R4 | Verificação de HEAD/CI/pareceres antes de consolidação | TEAM_COORDINATION §5 | por PR | aprovar SHA errado, evidência inválida |
| R5 | Organização de QA independente e recertificações focais | TEAM_COORDINATION §4 | por entrega | autocertificação, regressão não detectada |
| R6 | Consolidação de bloqueadores e encaminhamentos | COORDINATOR §4 | contínuo | bloqueio sem dono, decisão por impulso |
| R7 | Registro de decisões e reconciliação documental | DECISIONS.md | por decisão material | memória divergente, autoridade implícita |
| R8 | Controle de modelos/custos (free first, sem fallback pago) | COORDINATOR §13; D-007 | por ciclo | custo não autorizado, modelo inadequado |
| R9 | Suspensão emergencial e incidentes | COORDINATOR §16 | eventual | propagação de risco, credencial exposta |
| R10 | Escalonamento e autorização humana | COORDINATOR §10 | por ação crítica | ação sem autoridade |
| R11 | Manutenção de handoff/checkpoints e continuidade | COORDINATOR §14–15; AGENT_HANDOFF | por ciclo/troca | perda de contexto na troca de modelo |
| R12 | Verificação pós-merge e atualização de estado | TEAM_COORDINATION §5; DELIVERY_POLICY §5 | por merge | estado “concluído” sem CI pós-merge |

---

## 2. Classificação: automatizável, delegável, reservado

> Classificação **propositiva**; não altera a matriz de autoridade vigente.

| Classe | Definição | Exemplos |
|---|---|---|
| **Automatizável (ferramenta determinística)** | leitura/consolidação sem juízo de autoridade | coleta de main/PR/CI, `git diff --check`, inventário de sessões, catálogo de modelos, captura de evidências com SHA, geração do checkpoint em formato fixo |
| **Delegável ao Agente S (com contrato e autorização)** | juízo operacional dentro de escopo aprovado | propor missão, despachar após autorização vinculada, acompanhar entrega, consolidar pareceres, solicitar QA independente, manter checkpoint e registrar estado no ledger da missão |
| **Reservado ao mantenedor** | autoridade crítica ou excepcional | merge/release/tag/bump; schema Room; política TRUST/provedor; custo extraordinário; dispositivo/ADB; mudança de modo (§10); concessão/revogação de delegação; credenciais; exceções de política |
| **Reservado à revisão independente** | juízo técnico não autocertificável | QA de alto risco, recertificação de bloqueadores, parecer de PR crítico (autor ≠ revisor) |

**Regra:** automação não decide; delegação não transfere autoridade crítica; ferramenta não substitui autorização.

---

## 3. Checklist de recuperação de contexto após troca de modelo

Executar **somente leitura**, nesta ordem, antes de qualquer proposta:

| # | Passo | Evidência de conclusão |
|---|---|---|
| 1 | Ler COORDINATOR.md, DECISIONS.md, TEAM_COORDINATION.md, ACTIVE_TASKS.md, DELIVERY_POLICY.md e este documento | lista de leitura registrada com versões/SHA |
| 2 | Ler o último checkpoint e o registro de missões | checkpoint identificado (ciclo/data) e estado do ledger |
| 3 | `git ls-remote origin main` + `gh pr list` + `gh pr view` dos PRs ativos | SHA da main e HEADs/checks anotados |
| 4 | Inventário de sessões e instâncias (somente GET) | sessionID × título × diretório × instância × busy/idle |
| 5 | Comparar estado atual com o último checkpoint | lista explícita de divergências (verificado/inferido/desconhecido) |
| 6 | Confirmar modo vigente e ausência de delegação ativa | SHADOW (ou modo registrado) e “nenhum contrato vigente” |
| 7 | Confirmar limites de modelo/custo e credenciais | política free-first, modelo autorizado, nenhum segredo lido |
| 8 | Emitir relatório de divergências e propostas | nenhuma escrita antes deste passo |

**Critério de falha:** divergência material não explicada ⇒ permanecer somente leitura e escalar; não “corrigir” o estado por conta própria.

---

## 4. Procedimento de retomada após reinício

1. **Serviço/servidor:** verificar processo e listener; a identidade da instância inclui PID/metadados — reinício **invalida** o vínculo anterior. Revalidar `/api/info`, `/api/location`, banco e inventário; **revinculação controlada da mesma sessão**, nunca criar outra para “resolver” divergência.
2. **Sessão do supervisor:** reler documentos + último checkpoint; consultar registro/ledger; confirmar missão ativa; não reenviar.
3. **Despachos anteriores:** consultar mensagens/inbox/`active` antes de qualquer recuperação; distinguir entregue, executado e desconhecido.
4. **UNKNOWN:** permanece bloqueado; reconciliação somente por evidência positiva ou decisão humana.
5. **Registro:** atualizar checkpoint com causa do reinício, estado do ledger, evidências e ações propostas; nenhuma escrita de autoridade.
6. **Critério de pronto:** nenhuma duplicidade, nenhum reenvio automático, estado reconstruído por evidência e divergências reportadas.

> Regra derivada do desvio P3B.3: **uma única inicialização** por operação autorizada; variáveis herdadas (`OPENCODE_PASSWORD` etc.) devem ser removidas antes de exportar a credencial experimental.

---

## 5. Contrato mínimo de passagem de missão

**Campos obrigatórios:**

| Campo | Conteúdo mínimo |
|---|---|
| `mission_id` | único e imutável (`MISS-<ciclo>-<seq>`) |
| `operation_id` | correlato, distinto do mission_id |
| Destinatário | agente, `sessionID`, título, diretório e `instance_id` verificados |
| Objetivo | resultado observável e critério de aceite |
| Escopo | arquivos/frentes permitidos e proibidos; ownership confirmado |
| Dependências | PRs/SHAs/gates que precisam estar prontos |
| Baseline | HEAD esperado e branch; divergência ⇒ revalidar |
| Autorização | referência humana, validade, escopo, modelo, timeout e limite de transmissões |
| Payload | conteúdo e `payload_sha256`; alteração ⇒ nova autorização |
| QA | revisor independente e proporcionalidade |
| Evidência esperada | relatório v1, testes, CI, artefatos |
| Estado | `PROPOSED/AUTHORIZED/DISPATCHING/DELIVERED/RUNNING/COMPLETED/FAILED/UNKNOWN` |
| No-go | merge/release/schema/credenciais/ADB/outros itens proibidos |

**Regras:** uma missão ativa por agente; autorização vinculada a missão+HEAD+escopo; nenhum despacho sobre sessão ocupada; `UNKNOWN` bloqueia reenvio; ID permite correlação, **não** garante exactly-once; mudança de instância exige reconfirmação.

**Recibo de passagem (checkpoint):** missão, estado, destinatário verificado, autorização, payload hash, evidências, pendências e próxima ação — sem presumir aceite.

---

## 6. Critérios de aceitação da coordenação semiautônoma

### A. Indispensáveis antes de dispensar o ChatGPT Plus

| # | Critério | Evidência exigida | Estado atual (26/11) |
|---|---|---|---|
| A1 | Checkpoint somente leitura completo e reproduzível | checkpoint + SHA + comandos | **VERIFICADO** (praticado em ciclos anteriores) |
| A2 | Registro persistente de missão **antes** do despacho | ledger/registro com estado, destinatário, payload hash, horário | **PARCIAL** — engine em memória + registry local de sessão; falta ledger operacional persistente |
| A3 | ID único + detecção de duplicidade + bloqueio em `UNKNOWN` | cenário duplicado e retomada | **PARCIAL** — engine implementa; falta operação real comprovada |
| A4 | Verificação de destinatário (sessionID × título × diretório × instância) | preflight + recusa de destinatário divergente | **VERIFICADO host-only** (COORD-04) |
| A5 | Autorização humana registrada e vinculada (missão+HEAD+escopo+modelo+timeout) | registro da decisão + manifesto `approved=false` até aprovação | **PARCIAL** — contrato implementado; nenhuma autorização real exercida |
| A6 | Retomada após reinício sem duplicidade | cenário reinício antes/depois do POST | **NÃO EXECUTADO** (simulado apenas) |
| A7 | Revogação imediata e efetiva; pendentes bloqueados | cenário de revogação durante missão | **NÃO IMPLEMENTADO** (revogação em memória apenas) |
| A8 | Modelo permitido (free-first) e ausência de fallback pago | allowlist + bloqueio quando modelo ausente | **PARCIAL** — allowlist existe; modelo autorizado ausente na instância; decisão humana pendente |
| A9 | Guarda de custo/orçamento com bloqueio | telemetria de tokens + limite efetivo | **NÃO IMPLEMENTADO** (quota não observável; sem enforcement) |
| A10 | Permissões efetivas (privilégio mínimo, sem credenciais, sem escrita fora do escopo) | configuração + teste de efetividade | **NÃO IMPLEMENTADO** (D-008 é proposta; controles são política) |
| A11 | Suspensão emergencial documentada e testada | simulação de gatilho + evidência | **PARCIAL** — procedimento documentado; sem teste |
| A12 | QA independente e proibição de autocertificação | pareceres com autor≠revisor | **VERIFICADO** nos PRs recentes |
| A13 | Trilha de auditoria (checkpoint + ledger + relatórios com SHA) | artefatos linkados | **PARCIAL** — checkpoints/pareceres existem; ledger ausente |
| A14 | Nenhuma ativação implícita; SHADOW até decisão | registro em DECISIONS | **VERIFICADO** (modo atual SHADOW; D-005 PROPOSTA) |
| A15 | Handoff documental atualizado | este documento + runbooks | **PARCIAL** — este PR |

**Conclusão A:** a independência do Plus **não está pronta** em 26/11. Faltam A2/A3 (ledger operacional persistente), A5/A6 (autorização/retomada reais), A7 (revogação efetiva), A9 (orçamento), A10 (permissões efetivas). Nenhuma delas pode ser substituída por instrução de prompt.

### B. Posteriores — não impedem piloto supervisionado

| # | Item | Por que pode esperar |
|---|---|---|
| B1 | Lock distribuído / exactly-once | piloto único com consumidor exclusivo e sequencial |
| B2 | Multi-projeto/multi-instância | escopo atual é Better Talker, uma instância |
| B3 | Backend de quotas/antiabuso | sem custo extraordinário autorizado; free-first |
| B4 | UI/analytics de ledger | operação pode usar registro textual/JSON |
| B5 | Failover automático de modelo | proibido por política; decisão humana |
| B6 | Automação completa de incidentes | procedimento manual supervisionado basta no piloto |
| B7 | Arquivamento de longo prazo | pode ser posterior à estabilização |

---

## 7. Cenários de validação ponta a ponta (propostos; nenhum executado aqui)

| # | Cenário | Ação | Resultado esperado | Evidência |
|---|---|---|---|---|
| E1 | Missão normal | propor → autorizar → despachar → reconciliar | uma transmissão, `COMPLETED`, relatório associado | ledger + recibo + relatório |
| E2 | Missão duplicada | reenviar mesma missão/payload | segunda tentativa bloqueada; correlação por ID | registro de bloqueio |
| E3 | Destinatário incorreto | sessão/título/diretório/instância divergentes | zero requisição/zero POST; bloqueio antes do envio | preflight + erro |
| E4 | Reinício antes do despacho | matar serviço antes do POST | nenhum envio; retomar somente leitura | checkpoint + estado |
| E5 | Reinício após despacho | interromper após envio | `UNKNOWN`; sem reenvio; reconciliar por evidência | estado + evidência |
| E6 | Timeout/UNKNOWN | sem resposta no prazo | `UNKNOWN`; reenvio proibido; decisão humana | evento + registro |
| E7 | Revogação durante missão | revogar autorização antes/depois do envio | novas ações bloqueadas; pendentes classificadas | registro de revogação |
| E8 | Limite de modelo/custo | modelo ausente ou orçamento excedido | bloqueio antes do envio; sem fallback pago | catálogo + política |
| E9 | Tentativa de credencial | leitura/envio de segredo a endpoint divergente | bloqueio antes da requisição; zero header | sonda de endpoint |
| E10 | Escopo proibido | pedido de merge/release/schema/ADB | recusa explícita; escalonamento ao mantenedor | registro de recusa |
| E11 | Concorrência | duas chamadas simultâneas | consumidor único; segunda bloqueada/erro claro | registro de corrida |
| E12 | Duas inicializações | duas instâncias privadas | apenas uma autorizada; a outra encerrada e registrada | PID/listener + incidente |

---

## 8. Procedimento de suspensão emergencial

**Gatilhos:** instrução maliciosa; suspeita de vazamento de credencial; consumo pago não autorizado; risco iminente a dados de usuário; conflito de escrita ativo; corrupção de estado/ledger; repetição de despacho.

**Ações imediatas (Agente S):**
1. Parar **toda** escrita/despacho; manter apenas leituras inofensivas.
2. Não apagar evidências; preservar logs, estados e artefatos.
3. Registrar incidente no checkpoint/arquivo de incidentes com horário UTC, escopo, evidências e ações já tomadas.
4. Notificar o mantenedor e a coordenação; aguardar direção.
5. Não retomar automaticamente; retomada exige decisão humana registrada.

**Limite de autoridade:** o Agente S **não** registra decisões de autoridade em DECISIONS em nome do mantenedor; propõe o texto e o mantenedor decide/registra.

---

## 9. Checklist de revogação de autoridade

| # | Passo | Verificação |
|---|---|---|
| 1 | Identificar autoridade a revogar (modo, contrato de delegação, autorização de missão, credencial, sessão) | escopo e detentor explícitos |
| 2 | Registrar a revogação (mantenedor) com data/UTC e motivo | entrada em DECISIONS/registro operacional |
| 3 | Invalidar autorizações vinculadas | tentativas subsequentes rejeitadas |
| 4 | Bloquear despachos pendentes | nenhum novo envio; `UNKNOWN` permanece bloqueado |
| 5 | Revogar/rotacionar credenciais quando aplicável | sem leitura/exposição do valor |
| 6 | Encerrar sessões/processos sob autoridade revogada | somente PIDs autorizados; produtivos intactos |
| 7 | Confirmar efeito | sonda de recusa + registro |
| 8 | Comunicar e atualizar checkpoint | estado e pendências documentados |

**Critério:** revogação é imediata e não depende de reinício do modelo/serviço; autorização revogada não sobrevive em cache.

---

## 10. Plano SHADOW → ASSISTED → DELEGATED

> **Nenhum modo é ativado por este documento.** D-005 permanece PROPOSTA; transição exige decisão registrada do mantenedor (DECISIONS).

| Modo | Quem coordena | O que o Agente S pode | O que permanece humano | Gate de entrada | Rollback |
|---|---|---|---|---|---|
| **SHADOW** (atual) | ChatGPT + mantenedor | observar, verificar, propor; **sem despacho** | toda autorização e despacho | — | — |
| **ASSISTED** (piloto) | mantenedor + S em escopos | despachar missões **previamente autorizadas**, acompanhar, consolidar | merge/release/schema/TRUST/custo/dispositivo; aprovação de cada missão | A1–A12 verificados; contrato de delegação com escopo/limites/revogação; piloto único | volta a SHADOW por decisão |
| **DELEGATED** (futuro) | S em escopos delegados | organizar e distribuir missões elegíveis sem aprovação individual | autoridade crítica e exceções | ASSISTED estável + certificação de auditoria + orçamento/revogação testados | revogação imediata |

**Critérios de permanência:** nenhuma violação de escopo, nenhum reenvio em `UNKNOWN`, nenhuma duplicidade, nenhum custo não autorizado, QA independente preservado. Qualquer incidente P0 suspende o modo.

---

## 11. Continuidade se o ChatGPT Plus terminar antes da certificação

**Cenário:** o acesso termina antes de A1–A12 completos.

**Operação degradada mínima (segura):**
1. **Congelar novos despachos** pelo supervisor; manter apenas checkpoints somente leitura e consolidação de estado.
2. **Mantenedor assume o despacho** diretamente (instrução explícita por escrito), usando os contratos COORD-04 já certificados; sem novas autorizações implícitas.
3. **Prioridades:** P0 de dados/TRUST/segurança e gates do beta; pausar conveniências.
4. **Sem nova infraestrutura:** usar CLI/registros existentes; não criar ledger de emergência sem autorização.
5. **Preservar documentação:** checkpoints, DECISIONS, runbooks e evidências; registrar o modo degradado no próximo checkpoint.
6. **Credenciais:** permanecem com o mantenedor; nenhuma leitura/cópia.
7. **Retomada:** quando houver substituto autorizado, seguir §3/§4 e revalidar tudo; não presumir continuidade.

**Regra:** a falta do Plus não autoriza ASSISTED/DELEGATED nem reduz controles; apenas muda quem executa o despacho (humano).

---

## 12. Matriz de dependências

| Capacidade | Depende de | Estado | Bloqueia A? | Bloqueia B? |
|---|---|---|---|---|
| Checkpoint confiável | GitHub/CI + sessões somente leitura | disponível | — | — |
| Despacho seguro | autorização + destinatário verificado + ledger | parcial | **sim (A2/A3/A5)** | — |
| Prevenção de duplicidade | ID único + registro persistente + bloqueio UNKNOWN | parcial | **sim (A2/A3)** | — |
| Retomada após reinício | ledger + reconciliação + revinculação | parcial | **sim (A6)** | — |
| Revogação efetiva | autoridade + mecanismo + teste | não implementada | **sim (A7)** | — |
| Free-first / modelo | catálogo + allowlist + decisão humana | parcial (modelo ausente) | **sim (A8)** | — |
| Orçamento/custo | telemetria + limite + bloqueio | não implementado | **sim (A9)** | — |
| Permissões efetivas | configuração + teste + revisão | não implementado | **sim (A10)** | — |
| QA independente | matriz de revisores + evidência | disponível | — | — |
| Suspensão/incidente | procedimento + autoridade | parcial | **sim (A11)** | — |
| Multi-instância/exactly-once | lock/ledger distribuído | ausente | — | **sim (B1/B2)** |
| Backend/quotas | infra + política | ausente | — | **sim (B3)** |

**Leitura:** comunicação existe e foi validada (host-only); o que falta para independência é **governança operacional persistente** (ledger, revogação, orçamento, permissões efetivas) — não um novo canal.

---

## 13. Scorecard e conclusão

**Verificado:** checkpoint, QA independente, contratos de comunicação, autenticação real somente leitura, validação OpenAPI, bloqueio de modelo ausente, encerramento seguro, preservação de A–E.

**Parcial:** ledger operacional, autorização real exercida, retomada, suspensão testada, handoff.

**Não implementado:** revogação efetiva, orçamento com bloqueio, permissões efetivas configuradas/testadas.

**Conclusão:** a transferência é **viável e recomendada**, mas **não está certificada** para 10/12 sem fechar A2/A3/A5/A6/A7/A9/A10. O caminho seguro é: (1) piloto ASSISTED supervisionado após A1–A12; (2) manter merge/release/schema/TRUST/custo sob o mantenedor; (3) se o Plus terminar antes, operar no modo degradado do §11. Nenhuma decisão de autoridade é emitida aqui; qualquer ativação depende do mantenedor e de registro em DECISIONS.
