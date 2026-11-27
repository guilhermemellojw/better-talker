# COORD-04/P3B.5 — Fechamento do caminho de execução e probe de permissões (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Referência:** investigação do Agente E `issuecomment-7193190169`.

> Somente código, documentação e testes offline. **Nenhuma instância, revinculação real, POST, prompt, LLM, nova sessão, alteração de permissões, PilotApproval, ADB, Android, merge, ASSISTED/DELEGATED ou frente B/C/D.** A sessão `ses_de83b0620ffeMIQP5tvH7MLFEx` permanece preservada. Sem autocertificação.

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD vigente | `bd14ad8a780c8d33e0114f06135e6ffe149712d8` |
| HEAD do código | `8a35c9dcd1b28d792db6f699f8255f86dd0160d1` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Delta | 6 arquivos, **+335/−12**; `git diff --check` limpo; push sem force-push |

Digests operacionais publicados: `coord_pilot.py 01666d2b…`, `coord_pilot_cli.py 7935a0f4…`; fundação intacta.

## B. F1 — manifesto em runtime

`PilotExecutor._validate()` reconstrói o manifesto com **todas** as evidências do manifesto aprovado: `permission_policy_sha256`, `admission_basis`, `inbox_evidence`, `repository_source`, `admission_authorized` e `exclusive_consumer` (agora no material canônico). Proposta preparada e reconstrução produzem `canonical_sha256` idêntico; alteração material (hash da política) invalida a aprovação; evidência ausente/divergente gera `AuthorizationError` **antes** de qualquer POST. A revalidação continua ocorrendo no início de `execute()` e novamente antes de `transmit()`. Testes positivos e negativos sintéticos cobrem reconstrução, divergência e bloqueio sem default permissivo.

## C. F2 — admissão cumulativa

`tokens` e `cost` precisam ser observados explicitamente com tipos numéricos e valores zero; campos ausentes não são convertidos em zero. `FRESH_UNEXECUTED` permanece **descritivo**, separado de `authorized_admission`; exclusividade e autorização humana externa são exigidas pelos gates de prontidão e de execução. Proposta não autorizada pode ser elaborada com evidência técnica suficiente; somente aprovação externa válida (`APPROVED` + evidências completas) permite executar — sem dependência circular.

## D. F3 — revinculação compatível

O cadastro real da P3B.3.1 **não contém** `permission_policy_sha256` (nem `repository_source`, `admission_basis`, `inbox_evidence`). `registry_gaps()` reporta essas lacunas; `rebind-check` bloqueia e não preenche silenciosamente. A proposta de atualização preserva `session_id`, projeto, agente `build`, modelo e location, e limita campos atualizáveis a `instance_id` e evidências efêmeras, exigindo `--replace` e autorização humana. Nenhuma revinculação real executada.

## E. F4 — probe de permissões

Runbook e plano offline em `coord_pilot_cli.py permission-probe-plan`, conforme o parecer do Agente E: uma instância privada autenticada, mesmo banco/Session ID, política original com hash verificado, endpoint `POST /api/session/{id}/permission` com `{action, resources}`, uma ação por requisição, `agent` omitido (usa `build`). Matriz fechada e inofensiva: `shell/git status`, `edit`, `write`, `read`, `external_directory`, `subagent`, `execute` → `deny`; `question` → `allow`. Sem `save`, prompt, LLM, execução de ferramenta, nova sessão ou transmissão. `ask` pendente é respondido com `reject` via `POST .../permission/{requestID}/reply`, registrado e contabilizado. Limite máximo de requisições = casos + 2; parar diante de resultado inesperado. `question` é tool (não "zero tools") e `deny` do avaliador não é enforcement exaustivo de plugins/snapshot. Nenhuma operação foi executada.

## F. F5 — fronteiras

Nenhuma alteração em PR #70 (ledger), PR #71 (política de modelos) ou PR #72 (autorização/revogação); nenhuma nova frente iniciada.

## G. Verificação

**370/370** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 203 (incluindo **8 novos** `test_pilot_p35`). Demos normal e `UNKNOWN`: `COMPLETED`, `transmit_calls=1`, reenvio bloqueado; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. Suíte 203/203 com `HOME` temporário e registry sintético preexistente. Stores temporários; segredos sintéticos; zero POST real.

## H. Diff e CI

6 arquivos, +168/−12; CI `propose` concluiu `success` no run `45832856859` (`head_sha=8a35c9dc`); commits posteriores são somente documentação (workflow de roadmap, não execução da suíte — esta foi executada localmente). PR mantido Draft.

## I. Bloqueadores residuais

Enforcement runtime de permissões não comprovado; probe ainda não executado (exige autorização específica); cadastro legado com lacunas de evidência; catálogo real não verificado em operação; aprovação humana válida e manifesto final ausentes; nenhuma transmissão autorizada. **Limitação:** `IDLE_TERMINAL` está implementado, mas é inalcançável na observação real — `classify_availability()` exige `idle_evidence=True` e nenhum chamador real fornece esse parâmetro; nenhuma heurística não comprovada foi criada para supri-lo.

## J. Parecer

Submeter apenas o delta `bd14ad8a..8a35c9dc` à auditoria independente, com a prova de reconstrução canônica e o plano de probe. **Não autorizar** instância, probe real, revinculação, transmissão, prompt, LLM, nova sessão ou alteração de permissões sem decisão explícita posterior. Aguardar auditoria e autorização humana específica; não iniciar outra missão. PR #64 permanece Draft.
