# COORD-04/P1 — fundação de comunicação coordenada

Escopo exclusivamente **sintético e offline**. Nenhuma transmissão real, sessão, LLM,
credencial, serviço permanente ou permissão nova faz parte desta entrega.

## Arquitetura

| Arquivo | Responsabilidade |
| --- | --- |
| `scripts/coord_communication.py` | Motor genérico: contratos, identidade, autorização, estados, reconciliação, eventos e transporte simulado. |
| `scripts/test_communication.py` | Testes determinísticos de identidade, autorização, comunicação, relatórios e isolamento. |
| `scripts/demo_communication.py` | Demonstração offline de sucesso, timeout/UNKNOWN e próxima proposta não despachada. |

O motor não conhece nomes de agentes, documentos ou repositório do Better Talker.
`ProjectPolicy` e `TargetIdentity` são injetados. O fixture Better Talker fica apenas na
demonstração e nos testes. `DisabledRealTransport` falha fechado; não há adaptador real ativo.

## Contrato focal da API OpenCode v2.3.2

Discovery feito pela documentação oficial e OpenAPI correspondente, sem POST/PATCH/PUT/DELETE reais.

| Mecanismo | Método/endpoint | Schema/semântica | Erros e limites |
| --- | --- | --- | --- |
| Instância | `GET /api/info` | `ServerInfo`: versão, PID, URLs, paths e capacidades. Identidade observável da instância. | 400/401 documentados. |
| Projeto/location | `GET /api/location?location[directory]=...` | `Location.PublicInfo`: diretório, `project.id`, `directory` e `canonical`. | 400/401/404. |
| Sessão | `GET /api/session/{sessionID}` | `Session.Info`: ID, `projectID`, agente, modelo, outcome, tempo, tokens e location. | 400/401/404. |
| Lista de sessões | `GET /api/session` | `SessionsResponse` paginada; filtros por diretório/projeto e cursor. | 400/401. |
| Sessão ativa | `GET /api/session/active` | Mapa de sessões cujo agente está executando neste processo. Ausência prova apenas que não foi observada execução ativa naquela instância; não comprova disponibilidade nem idle confirmado. | 400/401. |
| Inbox | `GET /api/session/{sessionID}/inbox` | Itens ainda não entregues ao agente. Itens user/synthetic entregues migram para `message.list`. | 400/401/404. |
| Envio | `POST /api/session/{sessionID}/prompt` | Retorna item de inbox após enfileirar; não espera o agente terminar. `resume=false` não inicia execução. | 400/401/404/409/502. |
| ID da mensagem | Campo `id` do prompt | A spec documenta que reenviar o mesmo ID retorna o item original; reuso em outra sessão/tipo falha 409. | Garantia pontual de deduplicação do item; **não** prova exactly-once de execução. |
| Espera | `POST /api/experimental/session/{sessionID}/wait` | Retorna quando o agente fica idle; rota experimental. | 400/401/404/503. |
| Respostas | `GET /api/session/{sessionID}/message` | Mensagens paginadas; inbox ainda não incluído. Filtro `type=assistant` disponível. | 400/401/404/500. |
| Jobs | `GET /api/experimental/job` | Jobs ativos/não consumidos, filtráveis por sessão/kind/status; experimental. | 400/401/404. |

Não foi confirmado: entrega durável ao modelo, execução exactly-once, ordem global entre
instâncias, confirmação de leitura pelo agente ou sobrevivência a toda falha de transporte.
Essas propriedades não são presumidas pelo motor.

## Envelope de missão `1.0`

Campos obrigatórios:

- `schema_version`, `project_id`, `repository_id`;
- `mission_id` e `operation_id` separados;
- `coordinator_id`;
- `target_agent`, `target_session_id`, `target_instance_id`, `target_location`;
- `objective`, `payload`, `payload_sha256`;
- `expected_deliverables`, `acceptance_criteria`, `ownership_constraints`;
- `authorization_reference`, `created_at`, `expires_at`, `timeout`;
- `scope`; `expected_head_sha`/`expected_branch` opcionais para detectar divergência.

O hash SHA-256 cobre o JSON canônico do payload. Alteração de payload, escopo, projeto ou
destinatário exige nova autorização. IDs permitem correlação; não são idempotência.

## Relatório `BT-COORD-REPORT:v1`

Campos: projeto, missão, operação quando disponível, agente, sessão, mensagem, status,
HEAD/branch/PR quando aplicável, evidência de testes, bloqueios, artefatos, conclusão e
resultados por critério. `untrusted_text` permanece dado não confiável; o motor não executa
instruções contidas nele.

Classificações:

- `candidate`: identidade/schema insuficiente;
- `associated`: missão correta, sem evidência bastante;
- `verified`: evidência/critérios verificados, missão ainda não concluída;
- `completed`: status COMPLETED + associação + evidência/critérios;
- `stale`: anterior à missão;
- `divergent`: projeto, missão, operação, agente, sessão, HEAD ou branch divergentes;
- `incomplete`: critérios ou evidência ausentes.

Relatório COMPLETED não certifica código. Duplicidade idêntica é descartada; duplicidade
divergente é rejeitada.

## Autorização

`Authorization` vincula referência, projeto, repositório, identidade completa do destinatário,
hash do payload, escopo, emissão, expiração e revogação. A P1 aceita somente autorização
sintética. O motor rejeita ausência, expiração, revogação, hash divergente, destinatário
modificado, projeto diferente e escopo proibido. A política permanece `SHADOW`.

## Estados e reconciliação

```text
PROPOSED → AUTHORIZED → DISPATCHING → DELIVERED → RUNNING → COMPLETED
                     ↘ FAILED/UNKNOWN   ↘ FAILED/UNKNOWN   ↘ FAILED/UNKNOWN
UNKNOWN → DELIVERED/RUNNING/COMPLETED/FAILED somente por evidência
```

- `DELIVERED` exige `message_id`/evidência de entrega definida no transporte.
- Timeout, resposta perdida ou observação parcial viram `UNKNOWN`.
- `transmit()` recusa nova chamada em `UNKNOWN`.
- `reconcile()` consulta o transporte e nunca chama `transmit()`.
- Eventos são registrados em memória com sequência, estado anterior/posterior e evidência.

Não há ledger persistente, lock, daemon, banco externo ou garantia exactly-once nesta P1.

## Transporte simulado

Cenários determinísticos: sucesso, destinatário incorreto/inexistente, sessão/inbox ocupado,
falha antes da transmissão, timeout após transmissão, resposta perdida, observação parcial,
resultado duplicado e resultado divergente. Nenhuma chamada externa é realizada.

## Execução

```sh
python3 .opencode/scripts/test_communication.py
python3 .opencode/scripts/demo_communication.py
python3 .opencode/scripts/test_supervisor.py
```

A demonstração produz dois fluxos COMPLETED: um normal e um timeout reconciliado sem reenvio.
A próxima missão permanece PROPOSED e não é despachada.

## Reutilização futura

O núcleo é independente do Better Talker. Identidade de projeto, política, repositório,
location e sessões autorizadas são dados externos ao motor. O HealthAgentForms não foi acessado.
Multiprojeto, persistência, transporte real, concorrência e orquestração pertencem a etapas
futuras e não estão implementados aqui.

## Correções COORD-04/P2

### F1 — identidade canônica da missão

`canonical_mission_identity()` cobre versão, projeto, repositório, mission/operation IDs,
coordenador, identidade completa do destinatário, objetivo, payload, hash do payload, escopo,
entregáveis, critérios, ownership, HEAD/branch esperados, validade e timeout. A autorização
armazena essa identidade e é rejeitada se qualquer campo material divergir. `payload_sha256`
continua com sua finalidade específica; IDs não implicam idempotência.

### F2 — validade temporal

`created_at`, `expires_at` e `issued_at` exigem timezone válido. Missão e autorização futuras
são rejeitadas; expiração é verificada na autorização, no despacho e novamente após a
preparação do transporte, antes da transmissão simulada. Revogação em memória é eficaz apenas
dentro do processo atual; não é revogação operacional distribuída.

### F3 — evidências de transporte

O receipt precisa coincidir com `operation_id`, projeto, instância, location, sessão, hash do
payload e identificador da mensagem da missão. Níveis distintos:

- `queued_in_inbox`: aceitação/enfileiramento; não é entrega;
- `recorded_in_session`: mensagem registrada na sessão — evidência mínima de `DELIVERED`;
- `execution_started`: execução observada;
- `result_available`: resultado disponível;
- `report_received`: relatório disponível.

Aceitação pelo servidor não comprova leitura pelo agente. `id` do prompt, `message_id` do
relatório e `operation_id` são distintos. Evidência divergente não avança estado; ambiguidade
permanece `UNKNOWN`. A ausência em `/api/session/active` não é idle confirmado.

### F4 — lotes de relatório

O lote é validado integralmente antes de qualquer transição: identidade, contrato v1 integrado,
operação, ordem temporal, duplicidade e divergência. Duplicidade idêntica é idempotente;
divergente é rejeitada sem mutar o registro. Atualizações legítimas `IN_PROGRESS → COMPLETED`
são aceitas; relatório atrasado é ignorado; relatório anterior ao despacho é rejeitado.

Distinção explícita: estrutura válida, associação, evidência declarada pelo agente, conclusão
declarada, verificação estrutural local e certificação de código. Critérios autodeclarados não
são prova independente de qualidade; `code_certified` permanece falso.

### F5 — portabilidade

Testes e demonstração resolvem o próprio diretório por `__file__`, sem caminhos absolutos da
worktree. A demonstração foi executada a partir de local isolado. HealthAgentForms não foi
acessado; o núcleo permanece genérico.

### Autoridade e contenção

A blacklist de termos proibidos é filtro auxiliar, não barreira de segurança nem prova de
autorização. Antes de um piloto real serão necessários autoridade configurada efetiva,
transporte real revisado, persistência/ledger, revogação distribuída, exclusão mútua e
reconciliação entre processos. Nada disso está implementado ou ativado aqui.

## Correções COORD-04/P2.1

### R2 — requisição preparada

Antes de `prepare()` o motor captura um snapshot imutável da missão autorizada. Após
`prepare()`, o `PreparedRequest` é validado integralmente: estrutura, `request_id`, identidade
canônica, IDs, projeto/repositório, destinatário, objetivo, payload/hash, escopo, entregáveis,
critérios, ownership, HEAD/branch, validade, timeout, referência/identidade da autorização,
expiração e revogação. Cópia profunda é transmitida no lugar do objeto compartilhado.

Falha de validação ou falha classificada como `pre_transmit` produz `FAILED` com
`transmit_called=false` e zero chamadas ao transporte. Falha durante/pós transmissão ou receipt
contraditório produz `UNKNOWN`, sem reenvio automático.

### R1 — evidência positiva

São exigidos: `SessionStatus.target` coerente; receipt com `operation_id`, `message_id` e
evidência de projeto/repositório/destinatário/payload; observação com target, nível de evidência
e sinais positivos (`recorded`, `executing`, `result_available`, `report_available`). Inbox não
é entrega; ausência em `/api/session/active` não é disponibilidade; campos ausentes impedem
promoção a `DELIVERED`/`RUNNING`.

### R3 — consistência terminal

O lote é validado antes de qualquer transição. Conclusão terminal não é substituída por
relatório posterior divergente; mesmo `message_id` não pode mudar de conteúdo entre consultas;
`IN_PROGRESS`/`FAILED` posteriores a `COMPLETED` são rejeitados; `FAILED → COMPLETED` é
rejeitado; relatório recebido quando a missão está apenas no inbox não conclui. `IN_PROGRESS → COMPLETED` legítimo e
duplicidade idêntica permanecem válidos. Estado, relatório, assessment e eventos são mantidos
coerentes.

### Requisitos mínimos para o piloto real (COORD-04/P3, não implementados)

1. Uma missão experimental com objetivo, escopo e critérios verificáveis.
2. Autorização humana específica, registrada e vinculada à identidade canônica.
3. Destinatário experimental confirmado por ID de sessão, instância, location e agente.
4. Registro persistente anterior ao envio, com operação, payload, autorização e estado inicial.
5. Reserva exclusiva da operação e consumidor único; nenhuma operação concorrente.
6. Reconstrução/verificação do estado antes de reenviar; `UNKNOWN` bloqueia reenvio.
7. Adaptador OpenCode restrito ao conjunto autorizado, sem ampliar permissões do supervisor.
8. Limites efetivos de ferramentas, destinatários, tempo, custo e escopo.
9. Reconciliação após timeout/interrupção com evidência positiva, nunca presunção.
10. Recertificação independente do delta e decisão registrada do mantenedor.

Infraestrutura distribuída, orquestrador completo e reutilização multiprojeto não são
requisitos deste piloto. O HealthAgentForms segue como requisito arquitetural futuro apenas.

## Correção COORD-04/P2.2 — vinculação integral da autorização

`canonical_authorization_identity()` produz uma única representação canônica de **todos** os campos
materiais da autorização: referência, projeto, repositório, destinatário completo, hash do
payload, identidade da missão, escopo, `issued_at`, `expires_at`, `revoked` e `synthetic`.
Timestamps são normalizados para UTC ISO; o destinatário usa a tupla canônica de identidade.

O snapshot é capturado antes de `prepare()` e contém apenas valores imutáveis. Após
`prepare()`, o motor valida a estrutura do `PreparedRequest`, valida o par efetivo com
`_validate_authorization()`, compara a autorização preparada ao snapshot por uma única função
canônica, clona o conteúdo em cópia independente e repete validação e comparação antes de
`transmit()`.

Divergência em qualquer campo — inclusive `synthetic=False`, `expires_at` ampliado,
`issued_at` alterado para data válida ou mutação compartilhada durante `prepare()` — produz
`FAILED` pré-envio com zero chamadas ao transporte. Falha após transmissão continua `UNKNOWN`,
sem reenvio automático. Nenhuma assinatura digital, criptografia adicional, ledger distribuído
ou autorização real foi acrescentada.
