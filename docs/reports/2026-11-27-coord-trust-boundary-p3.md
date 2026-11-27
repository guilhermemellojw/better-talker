# COORD-TRUST-BOUNDARY/P3 — fechamento consolidado de segurança

**PR:** #83 (Draft, stacked em #82 `7c1c4b1a`), sem merge.
**Baseline auditada:** `cb4c95c5a8cc9370521eb53dde9f4e4eac185dc7`.
**HEAD final:** commit que contém este relatório; hash publicado no comentário de recertificação.
**Escopo:** serviço local, identidade, anchor/quarentena, replay, crash, storage, socket, runbook e regressões. Nenhum Android, interface visual, transporte real, credencial, usuário, sudo, instalação ou migração.

## 1. F01–F09 — implementação e evidência

| ID | Implementação | Evidência |
|---|---|---|
| **F01** | Principal = UID exclusivo; `SCM_CREDENTIALS` por mensagem; sem `/proc/PID/exe`, hash de interpretador, argv ou `SCM_RIGHTS`; credenciais ausentes/truncadas/divergentes/autodeclaradas recusadas; mesmo UID = mesma fronteira documentada. | `test_kernel_credentials_reject_missing_divergent_and_truncated`, `test_uid_registry_rejects_duplicate_and_unknown`, `test_least_privilege_observer_cannot_execute`, `test_wrong_consumer_peer_is_unauthenticated`; `coord_trust_cross_uid_probe.py` (CI descartável). |
| **F02** | Âncora forward-only **fora** do `state_dir`/backup; contadores de ledger/service state + epoch; quarentena em rollback, ausência de âncora ou crash; recuperação exige declaração assinada por autoridade externa, incrementa epoch e invalida missões antigas sem apagar evidências/UNKNOWN/reservas. | `test_full_rollback_quarantines_and_signed_recovery_invalidates_epoch`, `test_missing_external_anchor_with_state_quarantines`, `test_ledger_rollback_anchor_quarantines_old_restore`. |
| **F03** | Cache histórico não é permissão atual: `assert`/`reserve`/`authorize` revalidam o ledger; revogação, expiração/UNKNOWN/terminal retornam `REPLAY_STALE`; `status` consulta estado atual e marca resposta histórica. | `test_replay_assert_after_revocation_is_stale`, `test_replay_reserve_after_revocation_is_stale`, `test_replay_authorize_after_revocation_is_stale`, `test_replay_assert_after_unknown_and_expiry_is_stale`, `test_replay_assert_after_terminal_is_stale_and_status_is_historical`. |
| **F04** | Identidade do kernel por mensagem e UID; sem CAP_SYS_PTRACE; descritor transferido carrega credencial do emissor atual, não a do connect. | Mesmos testes F01 + `_credentials_from_ancillary` rejeita `SCM_RIGHTS`, credencial ausente/truncada/divergente. |
| **F05** | `service_requests` tem `status`; crash/erro resulta em `OUTCOME_UNKNOWN`; não há TTL que libere reserva nem recuperação automática de lease; `propose` compara material e reconcilia gap; dois SQLite não prometem atomicidade. | `test_crash_before_ledger_is_outcome_unknown_after_restart`, `test_crash_after_ledger_commit_quarantines_without_lease_recovery`, `test_propose_reconciles_service_mission_gap_by_full_material`. |
| **F06** | Validação de ancestrais, ownership, symlink/hardlink, socket regular/dono/grupo/modo, sidecars SQLite; config validada; servidor autenticado pelo cliente por UID/GID/socket/service_id; limites de frame, workers, duração e slots. | `test_ancestor_symlink_is_rejected`, `test_hardlink_trust_store_and_sqlite_are_rejected`, `test_socket_path_substitution_is_rejected`, `test_client_authenticates_server_uid_gid_and_service_id`, `test_total_request_deadline_closes_slow_client`. |
| **F07** | Revogação assinada bloqueia assert/novas reservas; interrupção preserva reserva/consumo e não reenvia; conclusão/report/attestation autenticados; lease/digest/replay errados falham. | `test_revocation_blocks_assert_without_resend`, `test_interrupt_preserves_lease_and_blocks_resend`, `test_fabricated_completion_is_rejected`, `test_tampered_report_is_rejected`, `test_wrong_lease_and_digest_are_rejected`. |
| **F08** | Separação de UID/GID, socket 0660 em runtime 0750, estado 0700, arquivos 0600, anchor fora do backup, sandbox do consumidor no runbook; `allow_same_uid` exige `test_mode` explícito. | `test_same_uid_requires_explicit_test_mode`, `test_same_uid_deployment_is_rejected_unless_explicit_test_mode`, `test_ledger_and_trust_store_are_private_files`, `test_storage_security_rejects_permissions_and_symlink`. |
| **F09** | Runbook sem `install /dev/null` destrutivo; staging/atômico; paths/UIDs canônicos; anchor forward-only separada; sandbox do consumidor; recuperação assinada; sem execução real. | `docs/operations/trust-service-deployment.md`; `test_deployment_examples_and_cli_check`; revisão documental. |

## 2. Correspondência operacional C1–C12

B mantém a matriz nominal C1–C12; o crosswalk abaixo liga cada critério ao
controle e à evidência desta rodada. Se a numeração nominal divergir, a matriz
de B prevalece.

| C | Critério de recertificação | F | Evidência principal |
|---|---|---|---|
| **C1** | UID exclusivo por consumidor / registry sem ambiguidade | F01/F08 | `test_uid_registry...`, `test_same_uid...` |
| **C2** | Credenciais do kernel por mensagem / sem autodeclaração | F01/F04 | `test_kernel_credentials...`, socket real |
| **C3** | Sem dependência de `/proc/exe`, hash de interpretador, argv, CAP_SYS_PTRACE ou SCM_RIGHTS | F01 | remoção de `/proc`/hash; `test_kernel_credentials...` |
| **C4** | Rollback forward-only, quarentena e anchor independente | F02 | restauração full + recovery assinada |
| **C5** | Cache/replay nunca vira permissão atual; status atual | F03 | testes de revogação/expiração/UNKNOWN/terminal |
| **C6** | Crash/outcome incerto, sem lease recovery/replay automático | F05 | crash antes/depois do ledger + quarantine |
| **C7** | Storage/ancestrais/hardlinks/sidecars 0600 | F06 | ancestor/hardlink/private files |
| **C8** | Socket, autenticação do servidor e limites de recursos | F06 | substitution/server auth/deadline |
| **C9** | Revogação, interrupção segura e evidência autenticada | F07 | revoke/interrupt/attestation/report tests |
| **C10** | Recuperação excepcional invalida epoch/autorizações antigas | F02 | signed recovery + old mission blocked |
| **C11** | Runbook, UIDs/paths canônicos e sandbox do consumidor | F09 | runbook/deploy examples |
| **C12** | Suítes host-only, CI no SHA exato, sem dupla contagem | todos | 40/546/167 = 713; CI |

## 3. Evidência de testes

Host-only, guarda de rede (`PYTHONPATH=.../ci_netguard`), chaves/ledger/trust/transporte sintéticos:

| Comando | Resultado |
|---|---|
| `test_coord_trust_service.py` | **40/40 OK** | `5ed239f1afc72df0ca370d844d049778c0ae6a3a15019c697180c7dbf05695c9` |
| `unittest discover` | **546/546 OK** | `163326602f11923ca88a274e38c06fae7f2ddaa41b1e6c857ada8cf1751ce229` |
| `test_supervisor.py` | **167/167 passed** | `92f3f934f682b8c48a854d41c2f6fd30b5173d7a7ff70987edeed1b18e8e79c4` |
| Total | **713** | — |

O workflow `coord-python` usa baselines 546/40/167/713 dentro do namespace
sem rede; a execução final no SHA exato é publicada no comentário do PR.

### Fail-first / reproduções da baseline

Log de fail-first no baseline `cb4c95c5` (testes novos, 40 erros por APIs/controles ausentes):
`79abc53b6fb84fddca9a0888f43e08dd47e7674f296de6c0761bbbe492b95cbf`.

- **F02:** em `cb4c95c5`, backup do ledger após `authorize`, restauração após
  `reserve`, e nova reserva com o mesmo envelope foi aceita (consumed_at
  reativado). O novo teste de rollback full falha nessa árvore e passa após
  anchor/quarentena/epoch.
- **F03:** em `cb4c95c5`, `assert` cacheado após `revoke` retornava sucesso
  histórico; o novo teste exige `REPLAY_STALE`.
- **F01:** `/proc/<pid>/exe` cross-UID com CapEff=0 retorna `EACCES`/falha;
  o novo caminho por `SCM_CREDENTIALS` não usa `/proc` nem capabilities.
- **F05:** crash entre ledger e resposta deixava `request_id` preso/ambíguo;
  agora retorna `OUTCOME_UNKNOWN` ou quarentena, sem lease recovery.
- **F06:** socket parent symlink e hardlink de trust/SQLite eram aceitos na
  baseline de investigação; agora são recusados.

## 4. Riscos residuais e cenários BLOCKED

- **Cross-UID real: BLOCKED neste host.** Não há segundo UID efêmero sem
  sudo/criação de usuário; a suíte usa UID único em `test_mode` e valida a
  credencial por mensagem/divergência. O CI pode executar o probe descartável
  `coord_trust_cross_uid_probe.py` com `sudo -n setpriv` para UID 65534; ele
  comprova a entrega de `SCM_CREDENTIALS` pelo kernel, não a integração
  completa do serviço. O procedimento do runbook §7 deve ser executado por
  B/CI antes do piloto.
- **Sandbox do consumidor: BLOCKED fora do PR.** O runbook entrega drop-in,
  mas o consumidor real, a API local do OpenCode e AF_INET não estão isolados
  nesta fundação; pré-requisito do piloto.
- **Rollback integral do host:** sem âncora distribuída, a âncora fora do
  backup é a melhor barreira local; restauração conjunta de host/âncora não é
  protegida por este PR.
- **Janela `assert` → POST:** residual; não há exactly-once. Revogação após
  assert não cancela POST em voo.
- **Transporte real:** não implementado; `real_transport=false` e `transmit`
  recusado.
- **Mesmo UID:** apenas laboratório; processos do mesmo UID são a mesma
  fronteira.

## 5. Runbook

`docs/operations/trust-service-deployment.md` contém: contrato canônico de
UIDs/paths, preparação atômica sem truncamento, trust store/consumer registry,
config com `anchor_path`/timeouts/workers, unidade systemd, sandbox do
consumidor, health check, operação/recuperação/quarentena e teste cross-UID
descartável.

Nenhuma migração, instalação, criação de usuário, sudo, credencial, POST, LLM,
Android/ADB ou merge foi executada. Aguardando recertificação independente de B
no HEAD final.
