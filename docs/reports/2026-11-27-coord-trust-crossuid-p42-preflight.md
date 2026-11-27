# COORD-TRUST-BOUNDARY/P4.2 — fechamento dos bloqueios de preflight

**Estado:** Draft; P4.4 é reconciliação exclusivamente documental. O dispatch
único do candidato histórico `31a150330d2b9c8af420cdfac322fde73132dda8` falhou
no run `46081657455`; a autorização foi consumida e nenhuma nova execução está
autorizada.

> **P4.3:** a correção de runtime bundle/ancestrais está em
> `docs/reports/2026-11-27-coord-trust-crossuid-p43-runner-fs.md`; as contagens
> atuais são 558 discover + 167 supervisor = 725, com os 40 do serviço e os 12
> P4 dentro do discover.

Nenhum merge, deploy, piloto, usuário persistente, secret de produção,
transporte real, POST, LLM ou Android foi executado.

**Baseline recebida:** `131cdb51db0fd206036f375ba4ff884d8d230824` (P4.1,
aceita). **Base de ancestralidade:** `main` `cc7b452253d0e6a8039e36c32e4276fd14937e80`.
**Branch:** `feat/coord-trust-crossuid-cert`.
**SHA de implementação P4.2:** `5ee8d98cc9f739caff91686728c4a736f9cc4b3d`.
**SHA do candidato histórico (dispatch FAIL):** `31a150330d2b9c8af420cdfac322fde73132dda8`.
**SHA da baseline documental P4.4:** `0ced987a0c1e15686c2710d697abde9053112496`.
**SHA final certificado:** definido no comentário externo de certificação em
https://github.com/guilhermemellojw/better-talker/pull/87; não é
auto-referenciado neste documento.

## 1. Correções A1–A6 + limites de execução

| Item | Correção | Regressão/evidência |
|---|---|---|
| **A1** | `run_main_gates()` usa `transmit_value` real; `results` indefinido removido. | `test_run_main_gates_executes_transmit_path` executa o caminho real até o check G9 de `transmit`. |
| **A2** | G6 exige `gid_value["ok"]` do serviço após mudança de GID na mesma conexão. `EACCES` de socket é apenas informativo; sem `--ambient-caps` ou sem mudança efetiva de GID, o gate é **BLOCKED**. | `test_g6_requires_service_rejection_not_socket_eacces` cobre BLOCKED, falha por aceitação e PASS por rejeição. |
| **A3** | Etapa `if: always()` de limpeza, após os artefatos. Manifest `cleanup.json` registra `run_id`, `WORK_ROOT`, output e process groups. A limpeza valida `COORD_TRUST_RUN_ID` no `/proc`, encerra somente grupos da execução e remove explicitamente `$RUNNER_TEMP/crossuid-out`, `$RUNNER_TEMP/crossuid-home`, `$RUNNER_TEMP/crossuid-tmp` e o `WORK_ROOT` exato, sem wildcard. `setsid --wait` pertence à etapa de certificação, não à limpeza. Não usa `pkill`/`killall` nem nome global de processo. | `test_workflow_is_opt_in_bounded_and_secretless` exige a etapa, o manifesto, o marcador e a ausência de limpeza por nome. |
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
execução é a limpeza independente e os guards de SHA/attempt/ambiente. Somente
a etapa de certificação cross-UID roda em `unshare -n`; checkout, setup,
sanitização e limpeza permanecem no runner normal.

## 3. Validação local desta rodada

| Suíte | Resultado |
|---|---|
| `unittest discover` (inclui os 40 do serviço e os 12 P4) | **558/558 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **725 = 558 + 167** |

`test_coord_trust_service.py` (**40/40**) e `test_coord_trust_crossuid.py`
(**12/12**) são subconjuntos do discover; não entram novamente na soma. Também
passaram `py_compile`, parser YAML do workflow consolidado e `git diff --check`.
Os logs locais não são versionados; as contagens são reproduzíveis pelos
comandos do workflow. O dispatch do candidato histórico falhou; nenhuma nova
execução foi disparada.

## 4. Riscos / BLOCKED

- **G6:** sem `--ambient-caps` ou sem mudança efetiva de GID, o gate é
  BLOCKED; negação de socket não certifica a rejeição pelo serviço.
- **Cross-UID real do serviço:** BLOCKED neste host; só no runner descartável.
- **Execução real do job:** o dispatch do candidato `31a15033` falhou no run
  `46081657455`; a autorização foi consumida. Uma nova execução exige
  recertificação e autorização explícita, com `certified_sha` exato.
- **Cleanup:** a etapa `always()` cobre o fluxo normal e timeout; se o runner
  for destruído sem executar etapas, o teardown do runner é a barreira final.
- **Transporte real/merge/deploy/piloto:** não executados e não autorizados.

## 5. Próximo passo

B recertifica o HEAD final P4.4, ancestralidade, árvore Android, limites, G6
estrito, limpeza e sanitização. A autorização anterior está consumida; uma nova
execução exige autorização explícita, com `crossuid_full=true` e
`certified_sha` igual a `GITHUB_SHA`/checkout HEAD.
