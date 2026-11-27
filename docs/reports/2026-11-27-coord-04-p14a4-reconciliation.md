# COORD-04/P14A.4 — Reconciliação íntegra e política vinculante (Agente A)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Parecer:** `issuecomment-7219781332` (P14A.3 — bloqueantes de integridade do cache).
**Auditoria P14A.3:** `e92bedca90d014e719f6e4a463ae6c15e8a004ee` · 535/535 host-only.

> Nenhum merge, migração real, transporte real, credencial, LLM, Android/Gradle/ADB, interferência no ledger do Agente E ou na homologação beta. Nenhuma `PilotApproval`, sessão ou POST real. **Não declarar P14A certificado antes da integração e auditoria independente.**

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD auditado | `e92bedca90d014e719f6e4a463ae6c15e8a004ee` |
| HEAD do código | `52839bb874cc593d6bac9ff5042ba7c2b153eb62` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Delta | 5 arquivos, **+161/−17**; `git diff --check` limpo; push sem force-push |

Arquivos: `coord_ledger.py`, `coord_p14_adapter.py`, `coord_pilot.py`, `coord_pilot_cli.py`, `test_p14a4.py` (novo).

## B. Fail-first

No HEAD auditado: ausência de `acceptance_results` no relatório só era concluída após remover `acceptance_criteria` do cache; `payload_sha256` divergente da observação só era concluído após alterar o cache; `environment=FIXTURE` forjado no cache desbloqueava a reconciliação pós-dispatch; evidência pós-transmit não sobrevivia ao reinício; `UNKNOWN` na CLI propagava `InvalidTransition`. As regressões P14A.4 reproduzem cada forma e exigem falha fechada.

## C. Correções independentes de schema

- **Cache não autoritativo:** `evidence-cache.json` é contexto de reconstrução; `authorization_canonical`, `environment` e `receipt` não são lidos como autoridade. `environment` vem de `SyntheticAuthorization` (FIXTURE); receipt e payload vêm da observação/ledger.
- **Âncora no ledger:** `PilotExecutor.reconcile()` usa `store.anchored_snapshot()` e `anchored_payload_sha256()`, validando snapshot contra `MissionRecord` (payload, projeto, repositório, agente, sessão, instância, worktree, referência). Qualquer divergência → falha fechada. `acceptance_criteria` vem da âncora da operação, não do cache.
- **Persistência pós-transmit:** `Ledger.append_evidence()` registra a evidência de `DISPATCHING` como transição de mesmo estado no schema já existente — nenhuma coluna/migração nova. Interrupção imediata após o despacho sobrevive ao reinício sem reconstrução externa.
- **UNKNOWN/FAILED na CLI:** `reconcile --target COMPLETED|FAILED --reason ...` usa `Ledger.reconcile()` com referência de autoridade e evidência; `InvalidTransition` é convertido em bloqueio, nunca propagado. Estados terminais permanecem protegidos; nenhum reenvio.
- **Política #71 vinculante:** `Recommendation.selected` / `ModelRef` alimenta `PilotAuthorization.expected_model` e a validação de destino no transporte; a evidência `model_policy` usa a mesma recomendação. Modelo selecionado divergente do efetivo bloqueia o despacho.
- **`SyntheticAuthorization`:** permanece marcador FIXTURE explícito, nunca `APPROVED`; não é transportado para P14B e não substitui #72/#77.

## D. Regressões e evidências

`test_p14a4.py` — 10 testes:

| Sonda | Resultado |
|---|---|
| `acceptance_criteria` adulterado + relatório sem critérios | não `COMPLETED` |
| `payload_sha256` adulterado no cache + prompt divergente | não `COMPLETED` |
| `environment`/`receipt` adulterados + associação divergente | não `COMPLETED` |
| Cache corrompido | não `COMPLETED` (fail-closed) |
| Interrupção imediata após despacho + restart | reconcilia `COMPLETED`, zero POST no cliente reiniciado |
| `UNKNOWN` → `FAILED` pela CLI | `FAILED`, sem reenvio |
| Relatório duplicado | segunda reconciliação `changed=false`, 1 POST |
| Dois processos | um vencedor, 1 POST |
| Modelo selecionado divergente | `TransportError`, zero POST |
| `SyntheticAuthorization` `APPROVED`/`REAL`/`PRODUCTION` | rejeitado |

**545/545 host-only**: `unittest discover` 378 (inclui 10 novos) + `test_supervisor.py` 167. Demos normal/UNKNOWN e `dry-run` preservadas: `http_posts=1`, `final_state=COMPLETED`, `attempted=true`, `executed=true`, `completed=false` até a reconciliação. Hermeticidade com `HOME`/stores temporários confirmada. CI `propose` concluiu `success` no run `46013519109` (`head_sha=52839bb8`), mas é verificação de roadmap, **não** executa a suíte; 545/545 é evidência host-only.

## E. Dependências do ledger 1.1 (Agente E)

Não implementei segunda migração nem dupliquei o ledger. Para a integração futura são necessários, no ledger 1.1: campos canônicos para `snapshot_sha256`, `authorization_canonical`, `permission_policy_sha256`, `receipt`/`message_id` e `criteria_sha256`, com validação transacional no `reconcile`. Atualmente usamos transições de mesmo estado e âncoras reconstruídas de forma determinística; isso é provisório e **não** substitui os contratos 1.1. Nenhuma extensão ledger disponível nesta rodada; evidências integradas com 1.1: **não produzidas**.

## F. Fronteiras preservadas

PR #70 (ledger), #71 (política de modelos), #72/#77 (autorização) e homologação beta intocados. Nenhum agente A–E interferido; nenhum produto, Android, ADB, HealthAgentForms ou configuração global alterado.

## G. Parecer

Correções focalizadas e 10 regressões adversariais entregues para integração com o ledger 1.1 do Agente E. **Não certificar P14A** antes da integração e auditoria independente. A transição real, a aprovação humana e a autorização de despacho permanecem bloqueadas. PR #64 permanece Draft; nenhuma outra missão iniciada.
