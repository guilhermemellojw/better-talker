# COORD-TRUST-BOUNDARY/P4/P4.2 — certificação cross-UID descartável

**Estado:** Draft; workflow e harness preparados, **não disparados** nesta
rodada. Nenhum merge, deploy, piloto, usuário persistente, secret de produção,
transporte real, POST, LLM ou Android foi executado.

**Base:** `main` no merge do PR #83, `cc7b452253d0e6a8039e36c32e4276fd14937e80`;
cabeça P3 certificada por B: `fa89ebb23cf13cca86d6c475d2771e7891cd4844`.
**Adendo P4.1:** a entrada de dispatch foi consolidada em
`.github/workflows/coord-python.yml` (job `crossuid`, inputs `crossuid_full` e
`certified_sha`); o workflow dedicado `.github/workflows/coord-trust-crossuid.yml`
foi removido. Ver
`docs/reports/2026-11-27-coord-trust-crossuid-p41-dispatch-entrypoint.md`.
**Adendo P4.2:** A1–A6 corrigidos (transmit real, G6 estrito, limpeza
`always()`, sanitização de resumo/tracebacks, SHA certificado por env,
`run_attempt`, G9 ampliado e documentação reconciliada). Ver
`docs/reports/2026-11-27-coord-trust-crossuid-p42-preflight.md`.
**P4.3:** o runtime bundle/ancestrais está em
`docs/reports/2026-11-27-coord-trust-crossuid-p43-runner-fs.md`.
**Branch:** `feat/coord-trust-crossuid-cert`.
**HEAD do código:** preenchido no relatório P4.2 e no comentário do PR.
**HEAD final do PR:** publicado no corpo/comentário do PR.

## 1. Entrega

| Arquivo | Papel |
|---|---|
| `.github/workflows/coord-python.yml` | job `crossuid` opt-in P4.2 (`workflow_dispatch`, `certified_sha` por env, `run_attempt=1`, 10 min, `contents: read`, runner hospedado, `setsid unshare -n`, limpeza `always()`) |
| `.opencode/scripts/coord_trust_crossuid_harness.py` | orquestração root descartável, UIDs numéricos, layout, crash/restart, avaliação G1–G9 e resumo sanitizado |
| `.opencode/scripts/coord_trust_crossuid_client.py` | cliente executado como consumidor; jornada, SCM_RIGHTS, GID, saturação, frames, sandbox e recuperação |
| `.opencode/scripts/coord_trust_crossuid_common.py` | fixtures sintéticas, job do cliente sem material privado, layout/config, assinatura dinâmica da atestação |
| `.opencode/scripts/coord_trust_crossuid_crash_service.py` | crash real CI-only após COMMIT/limite, preservando WAL para replay |
| `.opencode/scripts/test_coord_trust_crossuid.py` | 12 testes host-only (workflow, layout, job, sanitizador, limites, GID, nonce, A1, A2, resumo e bundle runtime) |
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
- `certified_sha` chega por env; formato, `GITHUB_SHA` e checkout HEAD devem
  coincidir. `run_attempt != 1` não executa o job.
- Etapa `always()` limpa `$RUNNER_TEMP/crossuid-*` e o `WORK_ROOT` da execução;
  process groups vêm do manifesto e do marcador `COORD_TRUST_RUN_ID`, nunca de
  nome de processo.
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
| **G6** | mudança de GID na mesma conexão com `CAP_SETGID` ambiente; exige rejeição pelo serviço | `gid_changed=true` e conexão fechada; sem ambient caps = **BLOCKED**; `EACCES` de socket é apenas informativo | preparado, não executado |
| **G7** | 2 workers, 2 concorrentes, 2 holds, probe excedente, deadline 2 s, frame 8192 > 4096, limites de config | concorrentes OK; extra sem welcome; holds fechados; frame/deadline fechados | preparado, não executado |
| **G8** | consumidor em netns/Uid próprio, sem caps/no_new_privs; tenta state/ledger/trust/anchor/keys, rede e `kill` | leituras `EACCES`; rede bloqueada; sinal `EPERM`; `status` via AF_UNIX OK | preparado, não executado |
| **G9** | status/transmit reais, config e source scan do serviço/crash service; cliente só usa `create_connection` como probe negativa em netns | `TRANSPORT_DISABLED`; `real_transport=false`; sem `AF_INET` no serviço; rede bloqueada comprovada | preparado, não executado |

## 4. Workflow e execução prevista

- Entrada única no P4.1: `.github/workflows/coord-python.yml`, job `crossuid`.
- Gatilho manual: `workflow_dispatch` com `crossuid_full=true` e
  `certified_sha=<40-hex>`; **não** executa em `push`/`pull_request` e a suíte
  ordinária é omitida no dispatch cross-UID.
- `certified_sha` por env, sem interpolação direta no shell; formato 40-hex,
  `GITHUB_SHA` e checkout HEAD devem coincidir.
- Checkout exato do `certified_sha`, `persist-credentials: false`, guarda
  `git rev-parse HEAD == certified_sha`.
- `run_attempt=1` obrigatório; reexecução não executa o job.
- Permissões: `contents: read`; nenhum secret.
- `runs-on: ubuntu-latest`; `timeout-minutes: 10`; deadline interno 480 s.
- Limpeza independente `if: always()` após os artefatos, com manifest de
  process groups e marcador único da execução.
- Comando previsto no runbook §7; nada foi disparado nesta rodada.
- Artefatos: `summary.json.sanitized`, `crossuid.log.sanitized`,
  `service-logs.log.sanitized`; paths, chaves e credenciais são removidos.
- Critérios de interrupção: qualquer gate falho/BLOCKED, timeout, ausência de
  `sudo -n`/`setpriv`/`unshare`/`setsid`/`ssh-keygen`, SHA certificado
  ausente/divergente, `run_attempt != 1`, UID colidindo, usuário criado, rede
  acessível, transporte habilitado ou logs não sanitizados.

## 5. Validação local desta rodada

| Suíte | Resultado |
|---|---|
| `test_coord_trust_service.py` (P3) | **40/40 OK** |
| `test_coord_trust_crossuid.py` (P4.2) | **12/12 OK** |
| `unittest discover` | **558/558 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **725** |

Os logs locais não são versionados; as contagens acima são reproduzíveis pelos
comandos do workflow. Evidência CI do HEAD final fica no comentário do PR.

Também passaram: `py_compile` dos scripts novos/alterados, parser YAML do
workflow consolidado, `git diff --check`, jornada local same-UID com socket real, crash
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
- 12 testes P4 no `unittest discover`; baseline do workflow `coord-python`
  atualizado de 546/713 para **558/725**.
- P4.2 corrige A1 (transmit real), A2 (G6 estrito/BLOCKED), A3 (limpeza
  independente), A4 (sanitização), A5 (documentação) e A6 (escopo G9).
- Nenhuma alteração em Android, transporte, LLM, credenciais reais ou no PR #82.

## 7. Riscos residuais / BLOCKED

- **Execução real do job:** BLOCKED até certificação independente do SHA e dos
  limites por B e autorização de um dispatch único; nada foi disparado.
- **Cross-UID do serviço:** o harness prova o mecanismo e a jornada somente no
  runner descartável; este host não reproduz dois UIDs reais.
- **`--ambient-caps`:** se o runner não suportar, G6 é **BLOCKED**; `EACCES`
  de socket não é aceito como certificação.
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

1. B certifica o HEAD final P4.2, o job opt-in em `coord-python.yml`, as
   permissões, o timeout, o checkout do SHA certificado, `run_attempt`, a
   limpeza e os UIDs.
2. Autorizado o dispatch único, executar o job `crossuid` com
   `crossuid_full=true` e `certified_sha` exato e publicar os artefatos
   sanitizados.
3. Somente após G1–G9 verdes, avaliar piloto separado; sem merge/deploy/
   transporte real nesta rodada.
