# COORD-TRUST-BOUNDARY/P4 — certificação cross-UID descartável

**Estado:** Draft; workflow e harness preparados, **não disparados** nesta
rodada. Nenhum merge, deploy, piloto, usuário persistente, secret de produção,
transporte real, POST, LLM ou Android foi executado.

**Base:** `main` no merge do PR #83, `cc7b452253d0e6a8039e36c32e4276fd14937e80`;
cabeça P3 certificada por B: `fa89ebb23cf13cca86d6c475d2771e7891cd4844`.
**Branch:** `feat/coord-trust-crossuid-cert`.
**HEAD do código:** `93a2e637f8dc1567827b5f0301f19e6cd602c055`.
**HEAD final do PR:** publicado no corpo/comentário do PR.

## 1. Entrega

| Arquivo | Papel |
|---|---|
| `.github/workflows/coord-trust-crossuid.yml` | job opt-in `workflow_dispatch`, 10 min, `contents: read`, runner hospedado, `unshare -n` |
| `.opencode/scripts/coord_trust_crossuid_harness.py` | orquestração root descartável, UIDs numéricos, layout, crash/restart, avaliação G1–G9 e resumo sanitizado |
| `.opencode/scripts/coord_trust_crossuid_client.py` | cliente executado como consumidor; jornada, SCM_RIGHTS, GID, saturação, frames, sandbox e recuperação |
| `.opencode/scripts/coord_trust_crossuid_common.py` | fixtures sintéticas, job do cliente sem material privado, layout/config, assinatura dinâmica da atestação |
| `.opencode/scripts/coord_trust_crossuid_crash_service.py` | crash real CI-only após COMMIT/limite, preservando WAL para replay |
| `.opencode/scripts/test_coord_trust_crossuid.py` | 8 testes host-only do workflow, layout, job, sanitizador, limites, GID e nonce |
| `docs/operations/trust-service-deployment.md §7` | procedimento P4, UIDs, isolamento, comando previsto e critérios de interrupção |
| `.opencode/TRUST-SERVICE.md` | contrato atualizado com nonce persistido e certificação opt-in |

O único delta comportamental no serviço é defensivo: o nonce da declaração de
recuperação é persistido (`service_recovery_nonces`) e consumido uma vez, e a
âncora é selada após o audit final da recuperação para que um restart não entre
em quarentena indevidamente. A suíte P3 continua 40/40.

## 2. Ambiente e isolamento

| Papel | UID:GID | Observação |
|---|---|---|
| serviço | `65533:65533` | sem root, sem capabilities, `no_new_privs` |
| executor | `65534:65533` | UID próprio; grupo comum somente para o socket |
| observer | `65531:65533` | somente `status` |
| helper/UID não registrado | `65532:65533` | recebe fd e tenta request com UID divergente |
| GID divergente | UID `65534`, GID `65532` | mesmo UID, GID exclusivo |

- Nenhum `useradd`/`adduser`; UIDs numéricos efêmeros. O harness compara hashes
  de `/etc/passwd`, `/etc/group` e `/etc/shadow` antes/depois.
- Root é usado apenas no runner descartável para criar/chown diretórios, spawnar
  `setpriv` e matar/reiniciar o serviço. Serviço e clientes rodam sem root.
- Todo o job roda em `unshare -n`; a rede é provada inacessível pelo sandbox.
- Chaves OpenSSH sintéticas são geradas no job e ficam em diretório 0700 root;
  o job do cliente contém apenas material assinado/público, socket e IDs. O
  probe de sandbox G8 é um diagnóstico separado que recebe paths somente para
  tentar a leitura e comprovar `EACCES`; ele não é o job normal do consumidor.
- `real_transport=false`; `transmit` deve retornar `TRANSPORT_DISABLED`; o
  serviço não contém `AF_INET`/`create_connection`.
- Layout: `state/` 0700 do serviço, `anchor/` 0700 fora do state, `run/` 0750
  do grupo, socket 0660, config/trust/registry/DBs 0600, chaves 0700 root.
- Nenhum secret de produção, `gh`, push, LLM, API externa ou Android.

## 3. Gates G1–G9

| Gate | Implementação no harness | Evidência esperada | Estado |
|---|---|---|---|
| **G1** | jornada completa `propose→authorize→reserve→assert→record_evidence→reconcile→status`; observer; UID não registrado; autenticação de UID/GID/service_id | logs `PASS`; `status=COMPLETED`, `real_transport=false`; `PERMISSION_DENIED`/`TRANSPORT_DISABLED` | preparado, não executado |
| **G2** | fd real por `SCM_RIGHTS` entre dois UIDs; fd anexado a request; socket encaminhado ao helper com UID divergente | `fd_received=true`; helper sem resposta; serviço fecha sem resposta | preparado, não executado |
| **G3** | backup/restore do ledger/service state, quarentena, recuperação assinada, lease/request_id antigos, nonce persistido | `quarantined=true`; epoch 2; `REPLAY_STALE`; nonce repetido `CONFLICT` | preparado, não executado |
| **G4** | `os._exit(137)` após COMMIT antes/depois do ledger, `finish_request`, selagem da âncora; WAL preservado; restart | rc 137; WAL > 0; `OUTCOME_UNKNOWN` antes do ledger; quarentena/sem liberação depois; replay idempotente pós-âncora | preparado, não executado |
| **G5** | assinatura inválida, declaração expirada, `trust_policy_sha256` divergente, aprovador desconhecido, epoch repetida | `UNAUTHENTICATED_PEER`/`CONFLICT`; quarentena mantida; epoch válida abre | preparado, não executado |
| **G6** | mudança de GID na mesma conexão com `CAP_SETGID` ambiente; fallback de socket 0750 com GID errado | `gid_changed=true` e conexão fechada; ou `EACCES` de socket | preparado, não executado |
| **G7** | 2 workers, 2 concorrentes, 2 holds, probe excedente, deadline 2 s, frame 8192 > 4096, limites de config | concorrentes OK; extra sem welcome; holds fechados; frame/deadline fechados | preparado, não executado |
| **G8** | consumidor em netns/Uid próprio, sem caps/no_new_privs; tenta state/ledger/trust/anchor/keys, rede e `kill` | leituras `EACCES`; rede bloqueada; sinal `EPERM`; `status` via AF_UNIX OK | preparado, não executado |
| **G9** | status/transmit reais, config e source scan; job em `unshare -n` | `TRANSPORT_DISABLED`; `real_transport=false`; sem `AF_INET` | preparado, não executado |

## 4. Workflow e execução prevista

- Gatilho único: `workflow_dispatch` com `crossuid_full` booleano; **não**
  executa em `push`/`pull_request`.
- Permissões: `contents: read`; nenhum secret.
- `runs-on: ubuntu-latest`; `timeout-minutes: 10`; deadline interno 480 s.
- Comando previsto no runbook §7; nada foi disparado nesta rodada.
- Artefatos: `summary.json.sanitized`, `crossuid.log.sanitized`,
  `service-logs.log.sanitized`; paths, chaves e credenciais são removidos.
- Critérios de interrupção: qualquer gate falho, timeout, ausência de
  `sudo -n`/`setpriv`/`unshare`/`ssh-keygen`, UID colidindo, usuário criado,
  rede acessível, transporte habilitado ou logs não sanitizados.

## 5. Validação local desta rodada

| Suíte | Resultado | SHA-256 do log |
|---|---|---|
| `test_coord_trust_service.py` (P3) | **40/40 OK** | `2b8463281ada984534e8f0e9b4e24930d313214caa518bc9ff118c2b793fb3f1` |
| `test_coord_trust_crossuid.py` (P4) | **8/8 OK** | `f48501204d46f1daed5ab7532fbe1f04654762d6869890577a28d550d35b1d90` |
| `unittest discover` | **554/554 OK** | `87ccf93aaa1f5a17a4128cfb1ce0c8978cf88542df64a274e6f117694c17a052` |
| `test_supervisor.py` | **167/167 passed** | `92f3f934f682b8c48a854d41c2f6fd30b5173d7a7ff70987edeed1b18e8e79c4` |
| Total | **721** | — |

Também passaram: `py_compile` dos scripts novos/alterados, parser YAML dos dois
workflows, `git diff --check`, jornada local same-UID com socket real, crash
local nos quatro limites e rollback/recuperação local. O teste **cross-UID real
do serviço continua BLOCKED neste host** (sem privilégio para criar/menorizar
UID); o harness não foi executado como root e o workflow não foi disparado.

## 6. Delta em relação ao P3

- Nonce de recuperação persistido e consumido uma única vez; uma nova declaração
  assinada com o mesmo nonce é recusada mesmo em epoch futura.
- Âncora selada após o audit final de `recover`, evitando quarentena falsa no
  restart imediatamente após recuperação.
- Crash wrapper CI-only com `os._exit(137)` após COMMIT, preservando WAL para
  replay real; não há hook de crash no serviço normal.
- 8 testes P4 no `unittest discover`; baseline do workflow `coord-python`
  atualizado de 546/713 para **554/721**.
- Nenhuma alteração em Android, transporte, LLM, credenciais reais ou no PR #82.

## 7. Riscos residuais / BLOCKED

- **Execução real do job:** BLOCKED até certificação independente do SHA e dos
  limites por B e autorização de um dispatch único; nada foi disparado.
- **Cross-UID do serviço:** o harness prova o mecanismo e a jornada somente no
  runner descartável; este host não reproduz dois UIDs reais.
- **`--ambient-caps`:** se o runner não suportar, G6 usa o fallback de negação
  de socket por GID; o resumo registra qual caminho foi exercitado.
- **Netns aninhado:** se o runner negar `unshare -n` dentro do netns externo, o
  sandbox usa o netns externo já sem rede e registra o fallback.
- **WAL no limite pós-âncora:** o crash após selar a âncora pode não deixar WAL
  (SQLite checkpoint); a evidência é a âncora selada e o replay idempotente.
- **Root do harness:** o root é limitado ao runner descartável e à orquestração;
  não é uma garantia de isolamento do host real.
- **Rollback integral do host:** a âncora externa continua sendo barreira local,
  não distribuída.
- **Transporte real/piloto/merge:** continuam BLOCKED e fora deste PR.

## 8. Próximo passo

1. B certifica o HEAD final, o workflow opt-in, as permissões, o timeout, os UIDs
   e os critérios de interrupção.
2. Autorizado o dispatch único, executar o job no SHA exato e publicar os
   artefatos sanitizados.
3. Somente após G1–G9 verdes, avaliar piloto separado; sem merge/deploy/
   transporte real nesta rodada.
