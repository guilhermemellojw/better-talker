# COORD-TRUST-BOUNDARY/P4.2 — fechamento dos bloqueios de preflight

**Estado:** Draft; correções A1–A6 aplicadas, **workflow não disparado**.

> **P4.3:** o dispatch único falhou por acesso ao script sob UID efêmero. A
> correção de runtime bundle/ancestrais está em
> `docs/reports/2026-11-27-coord-trust-crossuid-p43-runner-fs.md`; as contagens
> atuais são 12/558/725.
Nenhum merge, deploy, piloto, usuário persistente, secret de produção,
transporte real, POST, LLM ou Android foi executado.

**Baseline recebida:** `131cdb51db0fd206036f375ba4ff884d8d230824` (P4.1,
aceita). **Base de ancestralidade:** `main` `cc7b452253d0e6a8039e36c32e4276fd14937e80`.
**Branch:** `feat/coord-trust-crossuid-cert`.
**HEAD do código:** `5ee8d98cc9f739caff91686728c4a736f9cc4b3d`.
**HEAD final do PR:** publicado no corpo/comentário do PR.

## 1. Correções A1–A6 + limites de execução

| Item | Correção | Regressão/evidência |
|---|---|---|
| **A1** | `run_main_gates()` usa `transmit_value` real; `results` indefinido removido. | `test_run_main_gates_executes_transmit_path` executa o caminho real até o check G9 de `transmit`. |
| **A2** | G6 exige `gid_value["ok"]` do serviço após mudança de GID na mesma conexão. `EACCES` de socket é apenas informativo; sem `--ambient-caps` ou sem mudança efetiva de GID, o gate é **BLOCKED**. | `test_g6_requires_service_rejection_not_socket_eacces` cobre BLOCKED, falha por aceitação e PASS por rejeição. |
| **A3** | Etapa `if: always()` de limpeza, após os artefatos. Manifest `cleanup.json` registra `run_id`, `WORK_ROOT`, output e process groups. A limpeza valida `COORD_TRUST_RUN_ID` no `/proc`, usa `setsid`, encerra somente grupos da execução e remove apenas `$RUNNER_TEMP/crossuid-{out,home,tmp}` e o `WORK_ROOT` exato. Não usa `pkill`/`killall` nem nome global de processo. | `test_workflow_is_opt_in_bounded_and_secretless` exige a etapa, o manifesto, o marcador e a ausência de limpeza por nome. |
| **A4** | `summary.json`, tracebacks, stdout/stderr, logs de serviço e resultados de cliente passam por `sanitize_value`/`sanitize_text`: paths e blocos de chave privada são redigidos; SHAs, UIDs/GIDs e fingerprints sintéticos são preservados para rastreabilidade. O workflow reaplica regex de chave privada. | `test_sanitizer_removes_paths_and_private_key_blocks` e `test_summary_and_traceback_are_sanitized` cobrem mapping, resumo real, traceback, path e chave. |
| **A5** | Relatório P4 reconciliado; hashes de logs locais não versionados removidos; contagens atualizadas; P4.1 permanece histórico e aponta para P4.2. | Documentos e corpo do PR atualizados no HEAD final. |
| **A6** | G9 verifica serviço e crash service sem `AF_INET`/`create_connection`; no cliente, `create_connection` só é aceito como probe negativa com `network_error` comprovado no netns. | Check G9 adicional `execução cross-UID comprovadamente sem rede`; teste A1 executa o bloco. |

### certified_sha, github.sha e run_attempt

- `certified_sha` chega ao job por `env` (`CERTIFIED_SHA`), sem interpolação
  direta no shell.
- O job valida formato 40-hex, exige `GITHUB_SHA == CERTIFIED_SHA` e, após o
  checkout, `git rev-parse HEAD == CERTIFIED_SHA`.
- O job só executa com `github.run_attempt == 1`; a etapa de pré-condições
  repete a checagem e falha com `RUN-ATTEMPT-BLOCKED` se for violada.
- `timeout-minutes: 10` preservado; deadline interno 480 s.
- `permissions: contents: read`, `persist-credentials: false`, nenhum secret.

## 2. Workflow consolidado (sem mudança de arquitetura)

O job `crossuid` permanece dentro de `.github/workflows/coord-python.yml`, como
aceito no P4.1. Não foi reintroduzido workflow dedicado. A única mudança de
execução é a limpeza independente e os guards de SHA/attempt/ambiente.

## 3. Validação local desta rodada

| Suíte | Resultado |
|---|---|
| `test_coord_trust_service.py` (P3) | **40/40 OK** |
| `test_coord_trust_crossuid.py` (P4.2) | **12/12 OK** |
| `unittest discover` | **558/558 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **725** |

Também passaram `py_compile`, parser YAML do workflow consolidado e
`git diff --check`. Os logs locais não são versionados; as contagens são
reproduzíveis pelos comandos do workflow. Nenhum dispatch foi executado.

## 4. Riscos / BLOCKED

- **G6:** sem `--ambient-caps` ou sem mudança efetiva de GID, o gate é
  BLOCKED; negação de socket não certifica a rejeição pelo serviço.
- **Cross-UID real do serviço:** BLOCKED neste host; só no runner descartável.
- **Execução real do job:** BLOCKED até B certificar este HEAD e autorizar um
  dispatch único com `certified_sha` exato.
- **Cleanup:** a etapa `always()` cobre o fluxo normal e timeout; se o runner
  for destruído sem executar etapas, o teardown do runner é a barreira final.
- **Transporte real/merge/deploy/piloto:** não executados e não autorizados.

## 5. Próximo passo

B recertifica HEAD, ancestralidade, árvore Android, limites, G6 estrito,
limpeza e sanitização. Somente depois, um único dispatch com
`crossuid_full=true` e `certified_sha` igual a `GITHUB_SHA`/checkout HEAD.
