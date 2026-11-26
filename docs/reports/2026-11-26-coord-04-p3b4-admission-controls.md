# COORD-04 / P3B.4 — Admissão, revinculação e manifesto experimental (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Certificação P10:** `issuecomment-7192223094` / `docs/reports/2026-11-26-qa-coord-64-p10.md`.

> Somente código offline, testes e documentação. **Nenhuma instância iniciada, nenhuma revinculação real executada, nenhuma sessão criada/alterada, nenhum POST, prompt, LLM, PilotApproval ou manifesto apresentável.** A sessão `ses_de83b0620ffeMIQP5tvH7MLFEx` permanece intacta. Sem autocertificação.

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD inicial (certificado) | `7e6f329294f10541edf0b1ecbd1778ad146ebe37` |
| HEAD final do código | `5c0f4f4e15ef96eb55fd8799a00dd93aedf2fd87` |
| HEAD final da branch | SHA corrente; documentação posterior não altera código |
| Main / merge-base | `02b89856837d03cbaa11d2f30d5931b4d7e43bd3` / `4ae78375…` (main não é ancestral) |
| Delta | 8 arquivos, **+549/−78**; `git diff --check` limpo; push sem force-push |

## B. Correções J1–J11

- **J1–J4 (documentação):** `PILOT.md` passa a usar o destinatário real `ses_de83…`, agente `build`, modelo `opencode/hy4-flash-free` com variante `none`, `repository_id` validado e examples de autorização/manifesto coerentes. ID histórico `ses_defc…`/`A` ficam marcados como **fixtures**, não destinatários. Contrato: `POST /api/session`, sem query, `location.directory` no corpo; referência a `Hungerg` removida.
- **J5:**Removida a heurística `availability_confirmed = bool(outcome) and bool(time)`. Admissão explícita tri-state: `busy`, `inactive_observed`, `new_experimental_session`, `unknown`; ausência em `active` não é `idle`; `outcome`/`time` não provam disponibilidade. Contrato cumulativo da P10 implementado; falta de evidência ⇒ `unknown` e bloqueio. Nenhuma heurística se aplica a A–E.
- **J6:** `permissions_confirmed` deixou de ser derivado de registry/flag (flag `--permissions-confirmed` removida). Evidências separadas: solicitada, retornada, precedência documentada (last-match-wins, **não** observada em runtime), enforcement observado (sempre `False` sem prova) e restrição operacional. Hash canônico da política registrado.
- **J7:** `observe_inbox()` distingue vazio observado, ausente, falha HTTP, resposta inválida, ocupado e não consultado. `inbox_observed=true` e `inbox_count=0` são obrigatórios; ausência nunca convertida em zero.
- **J8:** `session_identity_confirmed` depende só de ID, agente, modelo, projeto interno, location e repositório. Admissão, permissões, repositório, inbox e bloqueios são campos independentes no resultado do preflight.
- **J9:** `repositoryID` ausente é aceito somente com `repository_source="git-local"` e validação de `projectID`/canonical/worktree/remote; se a API retornar o campo, exige-se igualdade exata. Campo nunca fabricado.
- **J10:** `PilotManifest` agora vincula `permission_policy_sha256`, `admission_basis`, `inbox_evidence` e `repository_source` no material canônico; alteração material invalida a aprovação. Validade UTC de 30 min preservada. Nenhuma aprovação/manifesto real emitido; manifestos sintéticos existem só em testes.
- **J11:** teste não hermético isolado com `--store-dir` temporário; 12 novas regressões cobrem os casos pedidos. Revinculação preparada como proposta (`rebind-check`), sem aplicação.

## C. Fail-first (cópia isolada do HEAD certificado)

| Sonda | Resultado antes |
|---|---|
| J5 | `availability_confirmed=True` apenas por `outcome`+`time`; nenhum `admission_state` |
| J6 | `permissions_confirmed=True` derivado do cadastro, sem enforcement |
| J9 | `repositoryID` ausente → bloqueio permanente por `session_repository_id divergente` |
| J7 | inbox ausente → `inbox_count=None`, sem `inbox_observed`, sem bloqueio adequado |
| J11 | teste consultava o registry real por padrão |

Após as correções, os mesmos cenários produzem `unknown`/bloqueio, evidência explícita e zero leitura do registry real.

## D. Identidade separada da admissão

O preflight real passa a expor `session_identity_confirmed`, `admission_state`, `admission_basis`, `admission_blockers`, `permission_policy_sha256`, `repository_source` e `inbox_observation` como resultados independentes. Identidade confirmada **não** autoriza transmissão; consultas posteriores permanecem somente leitura.

## E. Contrato de disponibilidade/admissão

Estados explícitos e cumulativos para a sessão experimental recém-criada e consumidor exclusivo: instância autenticada, endpoint autorizado, banco experimental esperado, exatamente uma sessão, identidade coerente, `active` observado sem a sessão, inbox observado e vazio, zero mensagens/parts, `time.created==time.updated`, tokens/custo zero, `outcome` ausente, repositório por evidência independente, política resolvida, exclusividade e autorização humana posterior. Exclusividade e autorização não são observáveis via API e permanecem como lacunas. Nada disso é generalizado para agentes produtivos.

## F. Política de permissões e lacunas

Política observada na sessão real: `[{"action":"*","resource":"*","effect":"deny"},{"action":"question","resource":"*","effect":"allow"}]`, hash canônico vinculado ao manifesto. Regras devolvidas são eco da solicitação; tabela `permission` vazia e nenhum evento de runtime foram observados na P10 — **enforcement não comprovado**. Instrução textual não equivale a bloqueio mecânico. Mantém-se o bloqueio até uma operação futura específica de validação (não executada).

## G. Evidência independente do repositório

`session_repository_id` ausente é tratado como indisponibilidade, não como divergência, quando `git-local` confirma `guilhermemellojw/better-talker` e projeto/canonical/worktree conferem. Testes positivos (ausência + git-local) e negativos (campo divergente) passam. Nenhum valor é fabricado.

## H. Revinculação preparada

`rebind-check` lê evidência sanitizada e o cadastro, e produz proposta com `proposal_only=true`, `applied=false`, `requires_authorization=true`, verificando mesmo Session ID, projeto, agente `build`, modelo, location, repositório, política/hash, inbox vazio, zero mensagens e endpoint autorizado. Mudança de `instance_id`/PID é registrada, não autoriza nova sessão. Mesma sessão → proposta; outra sessão → bloqueio. **Nenhuma revinculação real executada.**

## I. Manifesto e vinculação integral

HEAD, digests, endpoint, instância, projeto, repositório e `repository_source`, sessão, agente, modelo, location, hash da política, base de admissão, evidência de inbox, payload/hash, critérios, validade e limite de uma transmissão entram no material canônico. Aprovação anterior é invalidada por qualquer alteração material (teste de hash de permissão divergente). `approved=false` e `environment=PROPOSED` permanecem; nenhuma `PilotApproval` emitida.

## J. Testes e hermeticidade

**344/344** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 177 (incluindo **12 novos** `test_pilot_p34`). Demos normal e `UNKNOWN`: `COMPLETED`, 1 transmissão, reenvio bloqueado; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. Suíte também executada com `HOME` temporário contendo registry sintético preexistente: **177/177**, sem acessar o registry experimental real. Stores temporários; segredos sintéticos; zero POST real.

## K. Diff e CI

8 arquivos, +549/−78; fundação certificada (`coord_checkpoint.py`, `coord_communication.py`, `demo_communication.py`, `test_communication.py`) intacta. CI: check `propose` concluiu `success` no run `45825034299` (`head_sha=5c0f4f4e`); commits posteriores são somente documentação. `propose` é verificação de roadmap, **não** execução da suíte — esta foi executada localmente conforme item J.

## L. Preservação dos componentes certificados

Política HY4, criação/persistência da sessão, contratos P2 e agentes A–E preservados. Nenhuma instância, serviço, banco produtivo, credencial, sessão, permissão real, Android, ADB, HealthAgentForms, merge, ASSISTED/DELEGATED ou frente B/C/D foi tocada.

## M. Menor operação real seguinte (ainda não executada)

1. Iniciar uma única instância privada autenticada com o banco experimental existente.
2. Confirmar endpoint/instância/autenticação e a mesma sessão; tratar novo PID como revinculação proposta.
3. Repetir GETs autorizados e obter evidência de enforcement de permissões e de exclusividade operacional.
4. Recalcular manifesto/vínculos sobre HEAD e digests vigentes; obter aprovação humana explícita.
5. Somente então decidir a primeira transmissão — decisão separada.

## N. Bloqueadores remanescentes

Enforcement de permissões não observado; exclusividade e autorização humana para transmissão ausentes; `repositoryID` não retornado pela API (mitigado por evidência Git, mas ainda uma lacuna de campo); disponibilidade dependente da admissão excepcional; nenhuma `PilotApproval`; nenhuma revinculação real validada.

## O. Parecer para auditoria independente

Submeter **somente o delta corretivo** `7e6f3292..5c0f4f4e` à auditoria focal J1–J11, com as 344 regressões e o probe fail-first como evidência. Não autorizar instância, revinculação, transmissão, prompt, LLM, nova sessão ou alteração de permissões sem decisão explícita posterior. PR permanece Draft.
