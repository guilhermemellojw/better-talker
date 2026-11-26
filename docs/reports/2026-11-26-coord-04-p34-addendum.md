# Adendo COORD-04/P3B.4 — Parecer complementar P10 (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Referência:** `issuecomment-7192473013` · **HEAD certificado:** `7e6f329294f10541edf0b1ecbd1778ad146ebe37`.

> Ciclo corretivo único, sem reiniciar a missão e sem repetir auditorias concluídas. Somente código offline, testes e documentação. **Nenhuma instância, revinculação real, sessão, POST, prompt, LLM, credencial, PilotApproval, ADB, Android, merge, ASSISTED/DELEGATED ou frente B/C/D.**

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD certificado / P3B.4 | `7e6f3292…` / `ac77f664…` |
| HEAD do código do adendo | `4e2885af81af037361c73952db7dabfe49fed797` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Delta do adendo | 8 arquivos, **+266/−24**; `git diff --check` limpo; push sem force-push |

Digests operacionais publicados antes de qualquer operação: `coord_pilot.py b833ea4c…`, `coord_pilot_cli.py 83da8943…`; fundação (`coord_checkpoint`, `coord_communication`, `demo_communication`, `test_communication`) intacta.

## B. Fail-first

O parecer complementar reproduziu, no código certificado, manifesto emitido com sessão ativa/ocupada, `busy>idle`, `inbox` 200 sem `data`, `active` 200 sem `data`, `inbox_count` ausente e flag local de permissões promovida a confirmação. As novas regressões (18 testes) reproduzem e bloqueiam cada forma: estado `BUSY`/`UNKNOWN`, `inbox_observed=false`, `inbox_count=None`, ausência de `idle` por metadados e nenhuma emissão de manifesto com lacuna.

## C. Contrato unificado de disponibilidade

`coord_pilot.classify_availability()` e `OpenCodeTransport.status()` usam **`FRESH_UNEXECUTED`**, **`IDLE_TERMINAL`**, **`BUSY`**, **`UNKNOWN`**. Presença da sessão em `active` ou `busy>idle` ⇒ `BUSY`; `outcome` fora de `succeeded|failed|interrupted` ⇒ `UNKNOWN`; ausência de `active`/inbox/mensagens/permissões pendentes ⇒ `UNKNOWN`. `status()` não declara `idle` por `time.idle>=time.busy`; em modo real só mapeia para `idle` quando a classificação é admissível. Primeira transmissão exige `FRESH_UNEXECUTED` com evidências cumulativas; transmissões posteriores, `IDLE_TERMINAL` documentado. Nenhuma heurística se aplica a A–E.

## D. Respostas malformadas

`active` 200 sem `data`, `inbox` 200 sem `data`, `inbox_count` ausente, `outcome` arbitrário, sessão ativa com `outcome` e `busy>idle` são tratados como falha/ocupação explícita. `observe_inbox()` valida presença e tipo de `data`; ausência nunca é convertida em lista vazia. Tipos e enum validados conforme o contrato real.

## E. Vínculo Git

`git_binding_evidence()` confirma **canonical + worktree + common-dir + origin + projectID** e rejeita common-dir divergente do `.git` canônico. `repositoryID` permanece ausente na API (`Session.Info` não o possui), registrado como `session_repository_status="not_returned"`; nunca fabricado. `repository_source=git-local` e o binding completo entram na evidência do manifesto.

## F. Custo do modelo

`pilot.verify_model_catalog()` (via `--catalog` no preflight) exige provider `opencode`, ID `hy4-flash-free`, variante autorizada `none`, custos conhecidos e compatíveis com a gratuidade e disponibilidade de catálogo. Custo desconhecido/divergente, variante ausente ou modelo ausente bloqueiam. Nenhuma seleção dinâmica ou fallback; PR #71 continua responsável pela política geral Free First / 1M Preferred.

## G. Permissões

Separadas rigorosamente: solicitadas, devolvidas pela API, pendentes (`GET /api/session/{id}/permission`), política efetiva (desconhecida) e enforcement comprovado (não observado). Lista vazia de pendentes **não** prova enforcement; o bloqueio permanece. Regras observadas: `[{"action":"*","resource":"*","effect":"deny"},{"action":"question","resource":"*","effect":"allow"}]` — argumentos `"*"`, não vazios.

## H. Higiene de testes e documentação

Fixtures Hungerg em `test_pilot_executor.py`/`test_pilot_closure.py` rotuladas como históricas/sintéticas. Bloqueios deduplicados (`dict.fromkeys`) em observação e prontidão. Corrigida a atribuição do inbox vazio: veio da leitura separada `verify-inbox.json`, não do preflight que encerrou antes da consulta (`PILOT.md` e relatório P3B.3.1). Contrato unificado, common-dir, catálogo, pendências e atribuição documentados em `PILOT.md`.

## I. Testes e hermeticidade

**362/362** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 195 (incluindo **18 novos** do adendo). Demos normal e `UNKNOWN`: `COMPLETED`, `transmit_calls=1`, reenvio bloqueado; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. Suíte 195/195 com `HOME` temporário e registry sintético preexistente — sem acessar o registry real. Stores temporários; segredos sintéticos; zero POST real.

## J. CI e diff

Check `propose` a conferir no SHA do código; o workflow é verificação de roadmap, não execução da suíte (executada localmente). Diff sem Android; fundação preservada; PR Draft.

## K. Preservação dos componentes certificados

Política HY4, criação/persistência da sessão `ses_de83…`, contratos P2 e agentes A–E preservados. Nenhuma alteração em `.opencode/agents/**`, banco/cadastro experimental, serviço compartilhado ou processos produtivos.

## L. Bloqueadores remanescentes

Enforcement de permissões não comprovado; catálogo atual não verificado em operação real (preparado, não executado); exclusividade e aprovação humana para transmissão ausentes; `repositoryID` não retornado (mitigado por evidência Git); nenhuma `PilotApproval` ou revinculação validada.

## M. Parecer para auditoria focal

Submeter apenas o delta do adendo `ac77f664..4e2885af` à auditoria independente, com as 362 regressões e as sondas de forma real. **Não autorizar** instância, revinculação, transmissão, prompt, LLM, nova sessão ou alteração de permissões sem decisão explícita posterior. Sem autocertificação; PR #64 permanece Draft.
