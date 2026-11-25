# COORD-04 / P3A.2 — Fechamento operacional pré-piloto (Agente S)

**PR #64** `feat(coord): fundação de comunicação bidirecional simulada` · branch `feat/coord-communication-foundation` (existente, mantida) · PR **OPEN/DRAFT**.
**Parecer independente de referência:** `issuecomment-7174441603` (262/262 testes; bloqueadores F2, F4, F5; F1/F6 para fechamento proporcional).

> Esta missão **não** executa transmissão real, não opera sessões/serviços, não ativa ASSISTED/DELEGATED, não inicia P3B e **não se autocertifica**. Aguarda revisão independente e autorização explícita do mantenedor.

---

## A. HEAD inicial e final

| Item | Valor |
|---|---|
| HEAD auditado (inicial) | `7d39868ab64223bf3f35a4d0305f9950c0728c1a` |
| HEAD final (código) | `2a04ab9326b6f28a3b121432110f5f90d4a458ae` — commits posteriores nesta branch são somente documentação (`git rev-parse HEAD` para o SHA corrente) |
| Main observada | `55056c7323065631350ba0565886f2c374ca4a59` |
| Push | `7d39868a..2a04ab93` via `git push origin feat/coord-communication-foundation`, **sem force-push**; PR permanece Draft |
| Delta inicial→final | 6 arquivos, **+1109/−175**; `git diff --check` CLEAN |
| Arquivos | `.opencode/PILOT.md`, `coord_pilot.py`, `coord_pilot_cli.py`, `test_pilot_adapter.py`, `test_pilot_executor.py`, `test_pilot_closure.py` (novo) |

**Nota de proveniência:** a worktree continha alterações não commitadas ao iniciar (já cobrindo F1/F2/F4/F5/F6). Elas foram **verificadas, não descartadas**: três testes falhavam e duas lacunas de segurança foram encontradas e corrigidas nesta rodada (seção I).

## B. Fail-first dos bloqueadores (no HEAD auditado)

Reprodução em cópia isolada (`git archive 7d39868 .opencode` → `/tmp/opencode/coord64-failfirst`), sem tocar a worktree original. Prompt lido de `PILOT.md` e comparado byte a byte.

| Bloqueador | Sonda | Resultado no HEAD auditado |
|---|---|---|
| F2 | `issued_at` no futuro | **ACEITO** |
| F2 | `reviewed_head_sha` divergente | **ACEITO** |
| F2 | `approval_reference` alterado | **ACEITO** |
| F2 | `expires_at` ampliado | **ACEITO** |
| F2 | `authorization_id` alterado | **ACEITO** |
| F2 | `maintainer_id` alterado | **ACEITO** |
| F4 | relatório `IN_PROGRESS` | **COMPLETED** |
| F4 | relatório `FAILED` / `BLOCKED` | **COMPLETED** |
| F4 | `mission_id` divergente | **COMPLETED** |
| F4 | autor divergente | **COMPLETED** |
| F5 | prompt exato de `PILOT.md`, JSON sem `message_id` | `parse_pilot_report() → None` |
| F5 | `message_id` inventado pelo modelo | **ACEITO** (dependência de campo não solicitado) |

## C. Correção F2 — manifesto e aprovação integral

`PilotManifest` é a representação canônica compartilhada (`cc.canonical_json` + SHA-256), com `canonical_sha256`. Vincula: HEAD **executado** (`executed_head_sha()`, via `git rev-parse HEAD`), digests **recalculados** dos quatro arquivos operacionais (`operational_file_digests()` — não confia em SHA informado pelo chamador), identidade canônica da missão, identidade canônica integral da `PilotAuthorization`, `authorization_id`, `maintainer_id`, `approval_reference`, destinatário (`target_key`), instância, modelo, endpoint local, prompt e hash, `operation_id`, validade (`issued_at`/`expires_at`) e `max_transmissions=1`.

`PilotApproval.validate()` rejeita: `approver_type != human`, ambiente inválido, HEAD divergente, arquivos operacionais divergentes, hash de prompt inválido, limite de transmissão ≠ 1, intervalo de validade invertido, `issued_at` no futuro e expiração. `matches()` compara o hash canônico integral do manifesto construído em tempo de execução — fechando a vinculação parcial corrigida na P2.2. Nenhuma aprovação é emitida pelo agente; a CLI `execute` exige `--activate` **e** arquivo de aprovação com `environment=APPROVED` **e** hash vinculado ao manifesto preparado. Sem infraestrutura criptográfica distribuída.

## D. Correção F4 — reconciliação segura

`PilotExecutor.reconcile()` não promove `COMPLETED` por presença de `observation.reports`. Antes de qualquer persistência: revalida instância e destino (`confirm_instance`/`confirm_target`); exige observation coerente (`operation_id`, `target`, `payload_sha256`); exige prompt efetivamente observado (`prompt_content_confirmed` e `observation.message_id == msg-pilot-<operation_id>`); exige receipt não divergente; para cada relatório obtém o `link` do envelope e aplica `assess_text_report()` com critérios **proporcionais à missão textual**: projeto, missão, `operation_id`, agente declarado e observado, sessão, associação `reply_to`, `message_id` do envelope, ausência de bloqueios, relatório posterior à missão, todos os `acceptance_results`, `status=COMPLETED` e não no futuro. **Nenhuma evidência fictícia de testes/código é exigida.** `IN_PROGRESS`/`FAILED`/`BLOCKED`, incompletos ou divergentes não concluem; estados terminais são preservados; a avaliação é feita antes do `store.update()`, sem mutações parciais. Receipt com `message_id` divergente permanece `receipt_confirmed=false`.

## E. Correção F5 — prompt e parser

`parse_pilot_report(text, operation_id, observed_message_id)` passa a tomar o `message_id` da **envelope observada pela API**; o valor inventado pelo modelo é registrado em `report_links.declared_message_id` apenas como observação, nunca substitui a autoria (`agent_id` vem de `item["agent"]`, `session_id` de `item["sessionID"]`). O prompt canônico `PILOT_PROMPT` é byte-idêntico ao publicado em `PILOT.md` (`sha256=0f31bb7a426931ca3e0d7e8b9d9f73c4fe24572b49da9b3fba4cb9f77caf4215`) e é compartilhado por executor, CLI e fixtures. Demonstração em `test_pilot_closure.py::test_f5_exact_prompt_fixture_completes` e no `dry-run`: resposta **recuperada** (GET de mensagens), **interpretada** (parser), **associada** à missão (`reply_to`/`operation_id`), **avaliada** pelos critérios (`reasons=[]`) e **persistida** como `COMPLETED`. Compatibilidade `BT-COORD-REPORT:v1` preservada (marcador obrigatório).

## F. Proteções F1 — caminho operacional

`_OperationGuard` vincula reserva + `DISPATCHING` persistido + autorização consumida à transmissão. `transmit()` exige guarda ativa, não consumida, com `operation_id`, identidade canônica da missão, autorização, `payload_sha256` e destino idênticos aos da reserva; exige registro `DISPATCHING` e reserva legível/privada; consome a guarda **antes** do POST. Bloqueios demonstrados: transmissão direta sem executor, repetição da mesma requisição, FIXTURE com HTTP real, destino divergente e reenvio após UNKNOWN. `LocalHttpClient` mantém `allow_write=False` por padrão. Sem sandbox contra processos do mesmo usuário, lock distribuído ou ledger completo.

## G. CLI operacional F6

| Comando | Comportamento observado (offline) |
|---|---|
| `preflight` | identidade observada da fixture; `agent_id=A` em `assumptions`; permissões/disponibilidade em `limits`; `real_calls=0` |
| `prepare` | manifesto canônico completo, `approved=false`, `canonical_sha256`; nenhuma aprovação criada |
| `execute` | sem `--activate` → `BLOCKED_BY_DEFAULT`; sem aprovação → `BLOCKED_NO_APPROVAL`; aprovação divergente → `BLOCKED_APPROVAL_MANIFEST_DIVERGENT`; vinculada → `BLOCKED_PENDING_REAL_ACTIVATION`; sempre `real_calls=0` |
| `reconcile` | somente transições válidas, sem reenvio; aplica fixture **apenas** a registro `environment=FIXTURE`; registro não-fixture permanece inalterado (`reason: registro não-fixture; reconciliação real exige P3B`) |
| `closeout` | estado, evidências e limitações; não altera terminais |
| `dry-run` | manifesto+autorização+executor+store+relatório com cliente falso: `http_posts=1`, `real_calls=0`, `final_state=COMPLETED` |

Nenhum comando real contra o OpenCode foi executado nesta missão.

## H. Demonstração integrada offline

- `demo_communication.py`: normal → `COMPLETED`, `transmit_calls=1`, `unknown_resend_blocked=false`; timeout → `UNKNOWN` reconciliado por evidência, `COMPLETED`, `transmit_calls=1`, `unknown_resend_blocked=true`; próxima missão permanece `PROPOSED`.
- `coord_pilot_cli.py dry-run`: `execute` `DISPATCHING` com `receipt_confirmed=true`; `reconcile` `COMPLETED` com `reports=1`, `reasons=[]`; `http_posts=1`, `real_calls=0`, `registered=3`.
- Rede bloqueada por `sitecustomize` (`socket.socket.connect`, `socket.create_connection`, `urllib.request.OpenerDirector.open` → exceção): suíte completa passa; nenhuma conexão real.
- Zero POST com autorização divergente, sem proteção operacional ou duplicado; uma tentativa no cenário autorizado simulado; nenhuma conclusão falsa; nenhuma transmissão real.

## I. Testes, diff, CI e consumo de recursos

| Suíte | Resultado |
|---|---|
| `test_supervisor.py` (COORD-03) | 167 passed, 0 failed |
| `test_communication.py` | 56 OK |
| `test_pilot_adapter.py` | 20 OK |
| `test_pilot_executor.py` | 19 OK |
| `test_pilot_closure.py` (novo) | 20 OK |
| **Total** | **282/282** (262 existentes + 20 regressões F2/F4/F5/F1/F6) |

Ajustes feitos sobre o trabalho herdado não commitado: (i) `test_pilot_adapter` — dois testes de evidência de transporte passaram a exercitar o caminho protegido via guarda mínima, preservando a checagem de `accepted`/timeout sem reabrir `transmit()` direto; (ii) `test_pilot_executor` — a fixture de sucesso passou a incluir o prompt observado, e a asserção de relatório divergente passou a exigir apenas ausência de `COMPLETED` (o avanço `DISPATCHING→DELIVERED` por prompt observado é legítimo); (iii) duas proteções adicionais: evidência de fixture não aplicável a registro real e exigência de manifesto vinculado no `execute`. Nenhuma dessas alterações reabriu a fundação certificada (`coord_communication.py` e `coord_checkpoint.py` intactos).

CI: no HEAD de código `2a04ab93` o check `propose` concluiu `SUCCESS` (`run 45708520256`); sem `build-apk` neste HEAD, como observado no parecer pré-piloto. Consumo: somente Python offline, sem LLM pago, sem build, sem ADB, sem sessões, sem serviços; artefatos em `/tmp/opencode`. `__pycache__` gerado permanece untracked e não foi commitado.

## J. Comandos exatos para o preflight posterior (documentados, NÃO executados)

```sh
python3 .opencode/scripts/coord_pilot_cli.py preflight --endpoint <endpoint-local-autorizado>
opencode service status
opencode api get /api/info
opencode api get "/api/location?location[directory]=/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x
opencode api get /api/session/active
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x/inbox
```

Destinatário proposto mantido: `ses_defc05853ffeej4fPXrp7frx4x`. Confirmar depois: identidade real do agente (**não presumir `agent_id=A`**), projeto e location, instância derivada de `/api/info`, modelo gratuito, disponibilidade, inbox, permissões efetivas da sessão e compatibilidade observada da API OpenCode v2.3.2. Qualquer divergência **bloqueia**; não improvisar. Runbook atualizado em `.opencode/PILOT.md`.

## K. Limitações remanescentes

Transporte real desativado; nenhuma aprovação/transmissão real; sem garantia de exactly-once, entrega durável ou ordem global; sem lock distribuído/ledger/recuperação entre processos (COORD-05/06); permissões efetivas e disponibilidade reais não observadas; compatibilidade da API v2.3.2 a confirmar no preflight; CI `propose` em andamento no SHA final; cancelamento/cleanup produtivos sem prova real. Nada disso foi tratado como certificação de produção.

## L. Preservação de A–E

Nenhuma sessão, prompt, agente, modelo, configuração global, credencial, mensagem ou worktree de A–E foi acessado ou modificado. Nenhum serviço iniciado/reiniciado; nenhum POST real; nenhum merge, release, bump, build, ADB, ativação ASSISTED/DELEGATED ou início de P3B; HealthAgentForms não acessado; Android não alterado. Apenas a worktree da branch do PR #64 foi modificada.

## M. Parecer para recertificação focal

**Entrega concluída para revisão independente; sem autocertificação.** Os três bloqueadores reproduzidos no SHA auditado têm correção focal e regressões correspondentes; F1 e F6 receberam fechamento proporcional ao piloto único. Solicitar recertificação focal no SHA final `2a04ab9326b6f28a3b121432110f5f90d4a458ae`, com atenção à vinculação integral do manifesto, à reconciliação por evidência e à impossibilidade de POST sem proteção. **Não autorizar transmissão real, não ativar ASSISTED/DELEGATED, não iniciar P3B e não fazer merge** sem decisão de coordenação e autorização humana explícita sobre o manifesto exato. Manter PR #64 Draft.
