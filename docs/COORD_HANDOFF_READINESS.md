# COORD-08/09 PREP — Protocolo mínimo de transferência operacional e prontidão do supervisor

**Status:** proposta documental em PR Draft. **Não entra em vigor, não ativa modos, não concede autoridade e não altera nenhum documento de governança.**
**Data:** 26/11/2026 · **Main de referência:** `02b89856837d03cbaa11d2f30d5931b4d7e43bd3`
**Fonte normativa:** [COORDINATOR.md](COORDINATOR.md) (carta do Agente S), [DECISIONS.md](DECISIONS.md), [TEAM_COORDINATION.md](TEAM_COORDINATION.md), [ACTIVE_TASKS.md](ACTIVE_TASKS.md), [DELIVERY_POLICY.md](DELIVERY_POLICY.md), [ROADMAP_STATUS.md](ROADMAP_STATUS.md) e contratos COORD-04 no PR #64 (`COMMUNICATION.md`, `PILOT.md`, `coord_pilot*`).
**Certificação COORD-04/P9:** [issuecomment-7191305574](https://github.com/guilhermemellojw/better-talker/pull/64#issuecomment-7191305574) (26/11/2026).

> **Meta:** independência operacional da coordenação via ChatGPT até **10/12/2026**, com supervisão humana. A data é **estimativa de planejamento, não garantia** nem autorização para reduzir controles. O plano distingue **três níveis** (§6.1); só o Nível 2 exige ASSISTED e o Nível 3 exige DELEGATED, sempre por decisão registrada do mantenedor. Se a certificação do nível pretendido não estiver completa, aplica-se o plano de continuidade (§11) — nunca a ativação parcial de modos.

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
6. **Data não reduz controle:** os requisitos de entrada de cada nível (§6) são gates; itens B podem aguardar piloto supervisionado.

### 0.1 Estado factual da COORD-04 (P9, 26/11/2026)

Registro do que foi **efetivamente validado**, sem presumir etapas posteriores:

| Item | Estado |
|---|---|
| Autenticação real (Basic experimental) | **VALIDADA** — `source=real`, `service_contacted=true`, `authentication_succeeded=true`, HTTP 200 |
| Contrato OpenAPI v2.3.2 | **CONFIRMADO** — `POST /api/session`, `operationId=session.create`, `location.directory` no corpo, `model{id,providerID}`, `permissions[{action,resource,effect}]`, sem prompt inicial |
| Testes COORD-04 | **323/323** certificados anteriormente (host-only); não reexecutados nesta rodada documental |
| Modelo anterior (`opencode-go/hungerg-free#default`) | **INDISPONÍVEL** na instância isolada |
| `opencode/hy4-flash-free` | **AUTORIZADO PELO MANTENEDOR EXCLUSIVAMENTE PARA O PILOTO** (decisão registrada; ainda não exercitado) |
| Criação da sessão experimental | **PENDENTE** — nenhuma sessão criada |
| Transmissão real | **NÃO AUTORIZADA** |
| Política experimental | **Free First / 1M Preferred** — modelos gratuitos primeiro; contexto ~1M+ quando adequado; nenhum fallback pago sem autorização |
| P3B.3.1 | **NÃO CONCLUÍDA** — não presumir conclusão |

**Limite:** a autorização do modelo não autoriza POST, prompt ou transmissão; a criação continua sujeita ao procedimento do PR #64 (`PILOT.md`), à confirmação do contrato real e a nova decisão humana.

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

### A. Requisitos essenciais — gates por nível (A1–A15)

> A lista A reúne os controles essenciais; **nem todos são pré-condição de todos os níveis**. A proporcionalidade está em §6.2: N1 usa A1/A12/A13/A14/A15; N2 acrescenta A2–A11; N3 exige N2 estável + certificação. A trilha de auditoria (A13), o modo vigente (A14) e o handoff (A15) **não podem ser omitidos** de nenhum nível.
>
> **Modo vigente confirmado em 26/11/2026:** SHADOW; D-005 permanece PROPOSTA; nenhum contrato de delegação vigente; nenhuma ativação implícita.

| # | Critério | Evidência exigida | Estado atual (26/11) |
|---|---|---|---|
| A1 | Checkpoint somente leitura completo e reproduzível | checkpoint + SHA + comandos | **VERIFICADO** (praticado em ciclos anteriores) |
| A2 | Registro persistente de missão **antes** do despacho | ledger/registro com estado, destinatário, payload hash, horário | **PARCIAL** — engine em memória + registry local de sessão; falta ledger operacional persistente |
| A3 | ID único + detecção de duplicidade + bloqueio em `UNKNOWN` | cenário duplicado e retomada | **PARCIAL** — engine implementa; falta operação real comprovada |
| A4 | Verificação de destinatário (sessionID × título × diretório × instância) | preflight + recusa de destinatário divergente | **VERIFICADO host-only** (COORD-04) |
| A5 | Autorização humana registrada e vinculada (missão+HEAD+escopo+modelo+timeout) | registro da decisão + manifesto `approved=false` até aprovação | **PARCIAL** — contrato implementado; nenhuma autorização real exercida |
| A6 | Retomada após reinício sem duplicidade | cenário reinício antes/depois do POST | **NÃO EXECUTADO** (simulado apenas) |
| A7 | Revogação imediata e efetiva; pendentes bloqueados | cenário de revogação durante missão | **NÃO IMPLEMENTADO** (revogação em memória apenas) |
| A8 | Modelo permitido (Free First) e bloqueio efetivo de não autorizados | allowlist + recusa de modelo ausente/não autorizado | **PARCIAL** — `hy4-flash-free` autorizado exclusivamente para o piloto; bloqueio exercitado apenas host-only |
| A9 | Custo extraordinário zero no piloto (A9-B); orçamento financeiro é evolução (A9-C) | política explícita + allowlist gratuita; telemetria/limite como evolução | **PARCIAL** — política zero para modelos gratuitos confirmados; backend financeiro (A9-C) não implementado |
| A10 | Permissões efetivas (privilégio mínimo, sem credenciais, sem escrita fora do escopo) | configuração + teste de efetividade | **NÃO IMPLEMENTADO** (D-008 é proposta; controles são política) |
| A11 | Suspensão emergencial documentada e testada | simulação de gatilho + evidência | **PARCIAL** — procedimento documentado; sem teste |
| A12 | QA independente e proibição de autocertificação | pareceres com autor≠revisor | **VERIFICADO** nos PRs recentes |
| A13 | Trilha de auditoria (checkpoint + ledger + relatórios com SHA) | artefatos linkados | **PARCIAL** — checkpoints/pareceres existem; ledger ausente |
| A14 | Nenhuma ativação implícita; SHADOW até decisão | registro em DECISIONS | **VERIFICADO** (modo atual SHADOW; D-005 PROPOSTA) |
| A15 | Handoff documental atualizado | este documento + runbooks | **PARCIAL** — este PR |

**Conclusão A:** o **Nível 1 (consultivo)** pode ser atingido com A1/A13/A14/A15 e não depende do Plus. O **Nível 2 (operacional supervisionado)** ainda **não está pronto** em 26/11: faltam A2/A3 (ledger operacional persistente), A5/A6 (autorização/retomada reais), A7 (revogação efetiva), A9-B (custo zero exercitado no piloto) e A10 (permissões efetivas). Nenhuma delas pode ser substituída por instrução de prompt. O **Nível 3 (delegação)** exige adicionalmente a certificação completa e o contrato de delegação.

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

### 6.1 Três níveis de independência (não confundir com os modos)

| Nível | Objetivo | O que o supervisor pode | O que permanece humano | Modo/dependência |
|---|---|---|---|---|
| **N1 — Independência consultiva** | recuperar contexto, consolidar estado, analisar entregas e propor próximas missões **localmente** | leitura, checkpoints, pareceres, propostas de missão e de priorização | **todo despacho** e toda autorização | **SHADOW**; não depende permanentemente do ChatGPT Plus |
| **N2 — Independência operacional supervisionada** | distribuir missões **previamente autorizadas**, receber relatórios, preservar estado e recuperar operações | despacho autorizado, acompanhamento, reconciliação, consolidação | autorização de cada missão; merge/release/schema/TRUST/custo/dispositivo | **ASSISTED** expressamente autorizado; exige os controles essenciais (destinatário, ledger, autorização, revogação, custos, permissões) |
| **N3 — Delegação operacional** | organizar e distribuir missões elegíveis dentro de contrato certificado | distribuição em escopos delegados, sem aprovação individual | **autoridade crítica permanece humana**; exceções e mudanças de política | **DELEGATED**; exige certificação completa e contrato de delegação |

**Invariante:** nenhum nível transfere ao supervisor merge, release, tag/bump, schema, política TRUST/provedor, custo extraordinário, dispositivo/ADB, credenciais ou mudança de modo. N1 não é ASSISTED; N2 não é DELEGATED.

### 6.2 Entrada vs permanência por nível (requisitos proporcionais)

| Nível | Requisitos de **entrada** | Critérios de **permanência** | Explicitamente **não exigido** para este nível |
|---|---|---|---|
| **N1** | A1 (checkpoint), A12 (QA independente), A13 (trilha de auditoria por checkpoint/pareceres), A14 (modo confirmado), A15 (handoff disponível) | checkpoints por ciclo; somente leitura + escrita documental em branch própria; relatório de divergências; nenhuma autorização presumida | ledger de despacho, revogação distribuída, orçamento, permissões de execução, verificação de destinatário de despacho |
| **N2** | N1 + A2/A3 (ledger persistente e anti-duplicidade), A4 (destinatário), A5 (autorização vinculada), A6 (retomada), A7 (revogação eficaz), A8 (modelo autorizado/bloqueio), A9-B (custo zero no piloto), A10 (permissões efetivas), A11 (suspensão testada) | ausência de duplicidade, de reenvio em `UNKNOWN`, de modelo/custo não autorizado; QA independente; revogação e suspensão reprovadas periodicamente; nenhum escopo proibido | sistema financeiro completo de quotas (A9-C); multi-projeto/multi-instância; exactly-once distribuído |
| **N3** | N2 estável + certificação de auditoria + contrato de delegação + testes de revogação/orçamento/limites + escopo nominal | auditoria periódica; nenhuma violação de escopo; revogação imediata; orçamento com telemetria; incidentes P0 suspendem o modo | backend completo de cobrança/quotas se o escopo permanecer exclusivamente em modelos gratuitos confirmados |

**Regra:** não tratar requisitos de delegação plena (N3) como pré-condição de consulta SHADOW (N1). Controles de despacho real (N2) não podem ser reduzidos por prazo.

### 6.3 Matriz de prontidão por nível (26/11/2026)

| Nível | Status | Lacunas bloqueantes | Próximo gate |
|---|---|---|---|
| **N1 — consultivo** | **PRONTO COM RESSALVAS** | A13 parcial (ledger ausente, mas checkpoints/pareceres existem); A15 em revisão neste PR | merge deste documento + confirmação de handoff |
| **N2 — operacional supervisionado** | **NÃO PRONTO** | A2/A3 (ledger persistente), A5/A6 (autorização/retomada reais), A7 (revogação), A9-B (custo zero exercitado), A10 (permissões efetivas), A11 (suspensão testada) | fechar A2–A11 + decisão ASSISTED do mantenedor |
| **N3 — delegação** | **NÃO PRONTO** | tudo de N2 + certificação de auditoria, contrato de delegação, testes de orçamento/revogação | decisão DELEGATED do mantenedor |

### 6.4 Política de modelos e custos (Free First / 1M Preferred)

**Preferência registrada do mantenedor, aplicável ao piloto experimental:**

1. **Free First:** modelos gratuitos confirmados são a primeira e única opção do piloto.
2. **Contexto ~1M+ quando adequado:** preferir modelos com janela de contexto de aproximadamente 1 milhão de tokens ou superior quando a tarefa exigir; contexto menor é aceitável para missões curtas e delimitadas.
3. **Rotação futura entre modelos gratuitos** diante de limite/indisponibilidade — sempre com atualização explícita da allowlist e nova autorização quando o modelo mudar; **nunca** rotação automática para modelo pago.
4. **Nenhum fallback pago sem autorização** humana registrada; ausência do modelo autorizado **bloqueia**, não substitui.
5. **Preservação de contexto:** troca de modelo/instância exige checkpoint e handoff (§3/§4); não se presume memória ou continuidade.
6. **Nenhum reenvio cego em `UNKNOWN`:** rotação de modelo não é mecanismo de recuperação de missão; `UNKNOWN` só se resolve por evidência ou decisão humana.

**Três conceitos que não devem ser confundidos:**

| Conceito | O que é | O que exige | O que **não** exige |
|---|---|---|---|
| **A. Bloqueio efetivo de modelos não autorizados** | allowlist + recusa de modelo ausente/não autorizado antes do envio | validação exata de `providerID`/`id` e bloqueio sem fallback | backend financeiro, telemetria de tokens ou quota global |
| **B. Política de custo extraordinário zero no piloto** | piloto restrito a modelos gratuitos confirmados; qualquer custo não previsto é bloqueante e escalado | declaração de política + allowlist gratuita + revisão humana | cobrança/quotas automáticas; cobrir todos os cenários de provedor |
| **C. Sistema futuro de orçamento financeiro** | telemetria de tokens, limites, alertas e bloqueio automático | backend de medição/enforcement, testes e auditoria | ser pré-condição de um piloto limitado a modelos gratuitos confirmados |

**Ressalva de custo:** custo nominal zero no catálogo **não prova**, por si só, ausência de qualquer cobrança em todos os cenários (limites de plano, política do provedor, mudança de catálogo). Por isso: (i) o piloto permanece restrito a modelos gratuitos confirmados; (ii) qualquer indício de cobrança suspende o piloto (§8); (iii) o sistema C continua sendo evolução necessária para N3.

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

**Gatilhos:** instrução maliciosa; suspeita de vazamento de credencial; consumo pago não autorizado ou indício de cobrança; risco iminente a dados de usuário; conflito de escrita ativo; corrupção de estado/ledger; repetição de despacho; perda de vínculo de instância/destinatário.

**Ações imediatas (Agente S):**
1. Parar **toda** nova escrita/despacho; manter apenas leituras inofensivas.
2. Não apagar evidências; preservar logs, estados e artefatos.
3. Registrar incidente no checkpoint/arquivo de incidentes com horário UTC, escopo, evidências e ações já tomadas.
4. Notificar o mantenedor e a coordenação; aguardar direção.
5. Não retomar automaticamente; retomada exige decisão humana registrada.

**Limites explícitos da suspensão:**
- A suspensão bloqueia **ações novas**; **não cancela retroativamente** transmissão já realizada.
- **Não se presume** capacidade de interromper LLM ou operação já em andamento; o estado deve ser classificado como `RUNNING`/`UNKNOWN` e reconciliado por evidência.
- Encerramento de processo/serviço só ocorre **mediante autorização específica** e apenas sobre PIDs expressamente identificados; processos produtivos e de A–E são preservados.
- Revogação/rotação de credencial é ato do mantenedor; o Agente S não lê, copia ou expõe segredo.
- Operação em `UNKNOWN` **não** é “resolvida” por suspensão, reinício ou reenvio; permanece bloqueada até evidência positiva ou decisão humana.

**Limite de autoridade:** o Agente S **não** registra decisões de autoridade em DECISIONS em nome do mantenedor; propõe o texto e o mantenedor decide/registra.

---

## 9. Checklist de revogação de autoridade

| # | Passo | Verificação |
|---|---|---|
| 1 | Identificar autoridade a revogar (modo, contrato de delegação, autorização de missão, credencial, sessão) | escopo e detentor explícitos |
| 2 | Registrar a revogação (mantenedor) com data/UTC e motivo | entrada em DECISIONS/registro operacional |
| 3 | Invalidar autorizações vinculadas | tentativas subsequentes rejeitadas |
| 4 | Bloquear **novos** despachos | nenhum novo envio a partir da revogação |
| 5 | Classificar operações existentes por estado (§9.1) | transmitida, em andamento e `UNKNOWN` tratadas de forma distinta |
| 6 | Revogar/rotacionar credenciais **quando aplicável e autorizado** | sem leitura/exposição do valor |
| 7 | Encerrar sessões/processos **somente com autorização específica** | somente PIDs autorizados; produtivos intactos |
| 8 | Confirmar efeito | sonda de recusa + registro |
| 9 | Comunicar e atualizar checkpoint | estado e pendências documentados |

**Critério:** a revogação bloqueia **novas** ações de imediato, sem depender de reinício do modelo/serviço; autorização revogada não sobrevive em cache. Ela **não** promete cancelamento retroativo nem interrupção de operação já transmitida.

### 9.1 Efeito da revogação por estado da operação

| Estado | Efeito da revogação | O que **não** presumir |
|---|---|---|
| **Autorizada, ainda não transmitida** | bloqueio total; autorização invalidada; nenhum POST | que o bloqueio dependa de novo deploy |
| **Já transmitida (`DELIVERED`)** | nenhuma ação nova; acompanhar por evidência; registrar | cancelamento retroativo da entrega |
| **Em andamento (`RUNNING`)** | bloquear novas ações; não iniciar outra missão; observar | capacidade de interromper LLM/execução em curso |
| **`UNKNOWN`** | permanece bloqueada; reconciliação somente por evidência ou decisão humana | que reenvio, reinício ou troca de modelo resolvam |
| **Processo/serviço** | encerrar apenas o PID autorizado, com autorização específica | encerrar processos produtivos, de A–E ou compartilhados |
| **Credencial** | rotação/revogação pelo mantenedor, quando aplicável | leitura, cópia ou exposição do segredo pelo supervisor |

---

## 10. Plano SHADOW → ASSISTED → DELEGATED

> **Nenhum modo é ativado por este documento.** D-005 permanece PROPOSTA; transição exige decisão registrada do mantenedor (DECISIONS). Correspondência: **SHADOW ↔ N1**, **ASSISTED ↔ N2**, **DELEGATED ↔ N3**; um nível não ativa o outro automaticamente.

| Modo | Quem coordena | O que o Agente S pode | O que permanece humano | Gate de entrada | Rollback |
|---|---|---|---|---|---|
| **SHADOW** (atual, N1) | ChatGPT + mantenedor | observar, verificar, propor; **sem despacho** | toda autorização e despacho | N1 (§6.2): A1/A12/A13/A14/A15 | — |
| **ASSISTED** (piloto, N2) | mantenedor + S em escopos | despachar missões **previamente autorizadas**, acompanhar, consolidar | merge/release/schema/TRUST/custo/dispositivo; aprovação de cada missão | N2 (§6.2): N1 + A2–A11 verificados; escopo/limites/revogação; piloto único | volta a SHADOW por decisão |
| **DELEGATED** (futuro, N3) | S em escopos delegados | organizar e distribuir missões elegíveis sem aprovação individual | autoridade crítica e exceções | N3: N2 estável + certificação de auditoria + contrato de delegação + orçamento/revogação testados | revogação imediata |

**Critérios de permanência:** nenhuma violação de escopo, nenhum reenvio em `UNKNOWN`, nenhuma duplicidade, nenhum custo não autorizado, QA independente preservado. Qualquer incidente P0 suspende o modo.

---

## 11. Continuidade se o ChatGPT Plus terminar antes da certificação

**Cenário:** o acesso termina antes de os requisitos de entrada do nível pretendido estarem completos (para N2: A2–A11; para N3: certificação integral).

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

## 12. Matriz de dependências e mapa de integração

### 12.1 Distribuição atual de responsabilidades (proposta × certificado)

| Frente | Responsável | Escopo | Estado em 26/11 | Evidência |
|---|---|---|---|---|
| **COORD-04** — comunicação | **S** | fundação, adaptador, CLI, preflight, contrato OpenAPI, runbook | **EM CERTIFICAÇÃO** — autenticação real/OpenAPI validados (P9); criação de sessão pendente; transmissão não autorizada | PR #64; P9 `issuecomment-7191305574` |
| **COORD-05A** — ledger persistente | **B** | registro durável de missão, ID, anti-duplicidade, estados | **PROPOSTA/ATRIBUÍDA — não certificada** | nenhuma implementação certificada anexada |
| **COORD-05B** — política de modelos | **C** | allowlist Free First, rotação gratuita, bloqueio sem fallback pago | **PROPOSTA/ATRIBUÍDA — não certificada** | nenhuma implementação certificada anexada |
| **COORD-05C** — recursos locais | **D** | instância isolada, portas, HOME/XDG, banco, PID, encerramento | **PROPOSTA/ATRIBUÍDA — não certificada** | nenhuma implementação certificada anexada |
| **COORD-08/09 PREP** — transferência e prontidão | **E** | este protocolo, critérios, cenários e auditorias independentes | **EM REVISÃO** (documento) + auditorias certificadas | PR #69; pareceres P8/P9 e anteriores |

**Regra:** tarefa atribuída **não** é implementação certificada; nenhuma frente pode ser considerada pronta por menção em documento ou por relato de agente. A certificação exige evidência primária (PR/SHA/CI/artefato).

### 12.2 Cadeia de dependências para a independência

| # | Dependência | Bloqueia | Estado |
|---|---|---|---|
| 1 | **Comunicação** (COORD-04) | tudo | parcial — fundação validada host-only; criação/transmissão pendentes |
| 2 | **Ledger persistente** (COORD-05A) | N2/N3 | não certificado |
| 3 | **Integração dos componentes** (comunicação + ledger + modelos + recursos) | N2/N3 | não certificada |
| 4 | **Segurança operacional essencial** (autorização vinculada, revogação, permissões, custo zero, suspensão) | N2/N3 | parcial/não implementada |
| 5 | **Piloto supervisionado** (N2/ASSISTED) | N3 | não iniciado |
| 6 | **Delegação certificada** (N3/DELEGATED) | — | não iniciada |

**Leitura:** a comunicação existe e foi validada (host-only); o que falta para N2 é **governança operacional persistente e integrada** (ledger, revogação, orçamento, permissões efetivas) — não um novo canal. A meta de **10/12/2026 permanece estimativa de planejamento**, não garantia.

### 12.3 Matriz de bloqueio por capacidade

| Capacidade | Depende de | Estado | Bloqueia N2? | Bloqueia N3? |
|---|---|---|---|---|
| Checkpoint confiável | GitHub/CI + sessões somente leitura | disponível | — | — |
| Despacho seguro | autorização + destinatário verificado + ledger | parcial | **sim (A2/A3/A5)** | — |
| Prevenção de duplicidade | ID único + registro persistente + bloqueio UNKNOWN | parcial | **sim (A2/A3)** | — |
| Retomada após reinício | ledger + reconciliação + revinculação | parcial | **sim (A6)** | — |
| Revogação efetiva | autoridade + mecanismo + teste | não implementada | **sim (A7)** | — |
| Free-first / modelo | catálogo + allowlist + decisão humana | parcial — `hy4-flash-free` autorizado, não exercitado | **sim (A8)** | — |
| Custo zero no piloto | allowlist gratuita + política + revisão | parcial — política definida; sem exercício real | **sim (A9-B)** | — |
| Orçamento financeiro | telemetria + limite + bloqueio | não implementado | — (piloto restrito a gratuitos) | **sim (A9-C)** |
| Permissões efetivas | configuração + teste + revisão | não implementado | **sim (A10)** | — |
| QA independente | matriz de revisores + evidência | disponível | — | — |
| Suspensão/incidente | procedimento + autoridade | parcial | **sim (A11)** | — |
| Multi-instância/exactly-once | lock/ledger distribuído | ausente | — | **sim (B1/B2)** |
| Backend/quotas | infra + política | ausente | — (piloto único) | **sim (B3)** |

---

## 13. Scorecard e conclusão

**Verificado:** checkpoint, QA independente, contratos de comunicação (host-only), autenticação real somente leitura, contrato OpenAPI, bloqueio por modelo anterior ausente, encerramento seguro, preservação de A–E e ausência de transmissão.

**Parcial:** ledger operacional, autorização real exercida, retomada, suspensão testada, handoff, modelo autorizado ainda não exercitado.

**Não implementado:** revogação efetiva, permissões efetivas configuradas/testadas, integração COORD-05 e backend de orçamento (este último **não exigido** para piloto restrito a modelos gratuitos confirmados).

**Conclusão por nível:**
- **N1 (consultivo):** **PRONTO COM RESSALVAS** — depende do merge deste handoff e da confirmação do modo SHADOW; não depende do ChatGPT Plus.
- **N2 (operacional supervisionado):** **NÃO PRONTO** — faltam A2/A3/A5/A6/A7/A9-B/A10/A11 e a integração COORD-05; exige decisão ASSISTED do mantenedor.
- **N3 (delegação):** **NÃO PRONTO** — exige N2 estável, certificação de auditoria, contrato de delegação e orçamento/revogação testados.

**Conclusão:** a transferência é **viável e recomendada**, mas a independência operacional até 10/12/2026 **não está certificada** sem fechar os gates de N2. O caminho seguro é: (1) concluir N1 e operar em SHADOW com checkpoints locais; (2) fechar A2–A11 e a integração COORD-05 antes de qualquer piloto ASSISTED; (3) manter merge/release/schema/TRUST/custo sob o mantenedor; (4) se o Plus terminar antes, operar no modo degradado do §11. **Nenhuma decisão de autoridade é emitida aqui**; qualquer ativação depende do mantenedor e de registro em DECISIONS.
