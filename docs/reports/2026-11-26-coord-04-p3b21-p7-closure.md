# COORD-04 / P3B.2.1 — Fechamento focal dos bloqueadores P7 (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Parecer independente:** `issuecomment-7179955042` (310/310; F1/F2/F3/F4 bloqueados; F5 aprovado).

> A autorização operacional já concedida (instância privada autenticada + uma sessão experimental) **não foi utilizada**. Nenhuma instância, sessão, POST, prompt, LLM, credencial produtiva, build, ADB, merge, acesso a A–E, HealthAgentForms, ASSISTED/DELEGATED ou P3B.3. **Sem autocertificação.**

---

## A. HEAD inicial e final

| Item | Valor |
|---|---|
| HEAD bloqueado (inicial) | `606828af0887b35889305eb45d5e360de0fb012a` |
| HEAD final do código | `cdced54d54b148d12ea96856f9dfba5a253c3826` |
| HEAD final da branch | SHA corrente; documentação posterior não altera código |
| Main observada | `02b89856837d03cbaa11d2f30d5931b4d7e43bd3` |
| Delta corretivo | 6 arquivos, **+575/−31**; `git diff --check` limpo; push sem force-push |

Arquivos: `coord_pilot.py`, `coord_pilot_cli.py`, `PILOT.md`, `test_pilot_api_compat.py`, `test_pilot_p7.py` (novo), relatório P3B.2 (correção do runbook). Fundação P2 e motor de comunicação intactos.

## B. Fail-first dos bloqueadores (cópia isolada do HEAD bloqueado)

| Bloqueador | Sonda adversarial | Resultado antes |
|---|---|---|
| F1 | cliente autenticado para A, `base_url` mutado para B | `URL=http://127.0.0.1:51111/api/info`, `Authorization=True` — **credencial vazada** |
| F2 | `_confirm_target()` com `/api/location` real sanitizado (`project.id` hash) | `TransportError: location/projeto divergente` — **formato real rejeitado** |
| F3 | cadastro instância A, `/api/info` observado instância B | 6 GETs, incluindo sessão e inbox — **consulta antes da validação** |
| F3 | cadastro existente + novo `session_id` | substituição **silenciosa** |
| F3 | JSON corrompido | `JSONDecodeError` **não tratado** |
| F4 | nove divergências em `_prepare_real` | oito produziram manifesto no probe local; auditor reproduziu **9/9** |

## C. F1 — credencial e endpoint efetivo

`endpoint` e `base_url` são agora **propriedades somente leitura** apoiadas em `_endpoint` único; a duplicidade mutável foi eliminada. `LocalHttpClient.request()` chama `_effective_endpoint()` **antes** de validar método/caminho, antes de verificar o vínculo da credencial, antes de consultar o provedor de senha e antes de construir a requisição. A URL é `endpoint + path`, derivada da mesma fonte validada entregue a `auth.header(endpoint)`. Proxies permanecem desativados e redirecionamentos proibidos; cliente somente leitura continua bloqueando POST mecanicamente.

Pós-correção: atribuição a `base_url`/`endpoint` falha; mutação direta de `_endpoint`, de porta ou para endereço externo gera `ContractError` com `opener.assert_not_called()` e provedor de senha **zero chamadas**; troca de credencial também bloqueia antes de qualquer requisição. Segredos apenas sintéticos nos testes.

## D. F2 — identidade OpenCode/Git

Nova validação compartilhada `pilot.validate_observed_identity()`, usada por preflight, manifesto e `_confirm_target()`. Ela distingue `project_id` lógico (`better-talker`), `opencode_project_id` (hash interno), `repository_id` (lido de `git remote get-url origin` na worktree experimental observada), `project_canonical`, worktree, endpoint, instância, sessão, agente e modelo. O hash interno **nunca** é comparado ao ID lógico nem fixado como valor universal; `repository_id` não é derivado do hash. No HEAD corrigido, o formato real sanitizado com `project.id` hash é **aceito** no destino legítimo; divergências individuais de hash, agente, modelo, location e canônico são rejeitadas com `TransportError`. Contratos certificados da fundação preservados.

## E. F3 — cadastro e ordem das consultas

Ordem enforceada: (1) validar cadastro e endpoint — divergência gera **zero GET**; (2) `GET /api/info`; (3) confirmar instância cadastrada; (4) confirmar projeto/location/repositório pela validação compartilhada; (5) inventário e `active`; (6) somente então `GET /api/session/{id}`; (7) validar identidade efetivamente observada; (8) somente então `inbox`. Sonda pós-correção com instância divergente: `GETs=['GET /api/info']`, `status_kind=instance_divergent`, nenhum GET de sessão/inbox. Substituição exige `--replace` explícito; cadastro inválido/incompleto/corrompido gera `ValueError` controlado (`status_kind=registry_invalid`, `real_calls=0`); permissões de arquivo `0600` e esquema íntegro preservados.

## F. F4 — manifesto e evidências

`_manifest_readiness_blockers()` exige correspondência explícita entre cadastro (esperado) e API (observado) e exige confirmações: instância, projeto/location, sessão encontrada, identidade da sessão, disponibilidade, modelo gratuito e permissões efetivas. As nove divergências — `session_id`, agente, modelo, `projectID` interno, location da sessão, repositório, `session_found=false`, inbox ocupado e disponibilidade desconhecida — **bloqueiam**, sem manifesto. Valores ausentes são registrados como indisponibilidade, nunca substituídos pelo esperado. Preflight de infraestrutura sem destinatário continua funcionando (`service_contacted=true`, `recipient_authorized=false`), mas `prepare --real` bloqueia. Nenhuma `PilotApproval` emitida; validade UTC de 30 minutos preservada.

## G. Preservação F5

401 continua classificado como serviço contactado + autenticação recusada; 404 `SessionNotFoundError` como sessão ausente; sem fallback para fixture; formatos `data`+`cursor` e `active` compreendidos; `NOW` fixo apenas em fixtures; janelas futuras/expiradas bloqueadas. Regressões F5 existentes permanecem verdes.

## H. Runbook corrigido

Evidência do SDK instalado (`@opencode-ai/sdk/dist/gen`): `POST /session` na base do SDK, `directory` como parâmetro de **query**, corpo documentado com `parentID`/`title`. Caminho HTTP efetivo pode incluir `/api` — confirmar com `GET /doc` na instância v2.3.2. Na superfície v2: `model` usa **`id`** (não `modelID`) e `permission` é **lista** de objetos `{permission, pattern, action}`. Não usar operation ID `session.create` sem confirmação; não inventar payload. Criação sem prompt inicial e sem LLM; política de ferramentas restritas verificável; modelo gratuito congelado; ausência do modelo autorizado no ambiente isolado **bloqueia**, sem fallback pago. `PILOT.md` e o relatório P3B.2 foram corrigidos. Procedimento **não executado**.

## I. Testes, demos, diff e CI

| Verificação (rede bloqueada) | Resultado |
|---|---|
| `test_supervisor.py` | 167 passed, 0 failed |
| Demais suítes via `unittest discover` | 156 OK (56+20+19+20+15+13+**13 P7**) |
| **Total** | **323/323** |

Demos normal e `UNKNOWN`: `final_state=COMPLETED`, `transmit_calls=1`, reenvio bloqueado no timeout. `dry-run`: `source=fixture`, `service_contacted=false`, `http_posts=1`, `real_calls=0`. Stores temporários; segredos sintéticos; zero POST real; zero conexão real. CI: `propose` a conferir no SHA final do código.

## J. Limitações remanescentes

Nenhuma instância, autenticação real, criação de sessão ou `/doc` foi executada nesta rodada; `permission` e disponibilidade de modelo gratuito na instalação isolada seguem não confirmados; agente e permissões efetivas não observados; sem exactly-once, entrega durável ou ordem global; manifesto real só após evidências conferentes e Autorização humana explícita.

## K. Preservação de A–E

Nenhuma sessão, prompt, agente, modelo, credencial, mensagem, worktree, configuração global, Android, HealthAgentForms ou serviço de A–E foi acessado ou modificado; serviço compartilhado e processos preexistentes preservados; nenhum merge, build, ADB, ASSISTED/DELEGATED ou P3B.3.

## L. Parecer para recertificação focal

Solicitar recertificação **apenas sobre o delta corretivo** `606828af..cdced54d`: F1/F2/F3/F4 com sondas adversariais transformadas em 13 regressões permanentes, F5 inalterado, runbook alinhado ao SDK e ao `/doc` pendente. A autorização operacional já concedida **não** deve ser utilizada antes da recertificação; qualquer criação de sessão, POST, prompt, LLM, transmissão ou merge depende de decisão separada e autorização humana explícita sobre o manifesto final. PR permanece Draft.
