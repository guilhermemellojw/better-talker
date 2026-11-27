# COORD-CI/P3 — Isolamento efetivo do CI Python

**PR #79** · branch `ci/coord-python-host-only` · base `feat/coord-communication-foundation` @ `0a21cf5e`.
**HEAD de implementação:** `0390cfd332490ff9776845e4a9ab8406aa61864c`; **HEAD final:** commit de evidência no PR/comentário.
**Propriedade exclusiva confirmada:** `origin/ci/coord-python-host-only` = worktree local = `2c310120`, sem alterações pendentes, antes da modificação.
**Baseline de auditoria:** `2c310120c2fcaea9b1f1bc34aec02600db3cc687` (green condicional P1/P2).
**Escopo:** somente workflow/probe/dependências; PRs #64, #77 e #80 não foram alterados.

## 1. Objetivo

Fechar as ressalvas P1/P2 do parecer `7221084828`:

- executar as duas suítes **dentro** de namespace sem rede;
- provar TCP, UDP, DNS e subprocessos externos bloqueados;
- manter o guard Python como defesa adicional;
- ampliar path filters para docs/agents;
- fixar PyYAML por hash;
- separar preparação online da execução offline;
- minimizar privilégios e documentar sudo;
- preservar o probe de regressão e a propagação de falhas;
- reproduzir 545 testes.

## 2. Mudanças

| Arquivo | Mudança |
|---|---|
| `.github/workflows/coord-python.yml` | suítes sob `sudo -n unshare -rn`; probe OS; guard; path filters docs/agents; lock; sudo documentado |
| `.opencode/scripts/ci_network_probe.py` | probe TCP/UDP/DNS/subprocesso em modos `os` e `guard` |
| `.opencode/scripts/ci_netguard/sitecustomize.py` | guard adicional para `connect`, `create_connection`, `getaddrinfo`, `gethostbyname`, `sendto`, `sendmsg` |
| `.github/requirements-ci.txt` | `PyYAML==6.0.2` com hashes SHA-256 dos wheels CPython 3.12 x86_64 |
| `docs/reports/2026-11-27-coord-ci-p3-isolation.md` | este relatório |

## 3. Isolamento de rede

As duas suítes rodam com:

```text
sudo -n unshare -n -- /usr/bin/setpriv --reuid="$(id -u)" --regid="$(id -g)" --clear-groups \
  /usr/bin/env -i HOME=... TMPDIR=... XDG_STATE_HOME=... \
  PATH=... LANG=C LC_ALL=C PYTHONPATH=.../ci_netguard python3 ...
```

Antes delas, `ci_network_probe.py --mode os` roda no mesmo namespace e exige bloqueio de:

- TCP (`create_connection` para 1.1.1.1:443);
- UDP (`sendto` para 1.1.1.1:53);
- DNS (`getaddrinfo example.invalid`);
- subprocesso externo TCP;
- subprocesso externo DNS.

O guard Python roda separadamente fora do namespace (`--mode guard`) e cobre os mesmos canais via `sitecustomize`. Ele permanece como defesa adicional, não como substituto do namespace.

## 4. Dependências online × execução offline

- A única preparação online é `pip install --require-hashes --only-binary=:all: -r .github/requirements-ci.txt`.
- O wheel PyYAML 6.0.2 CPython 3.12 manylinux x86_64 tem hash verificado; o lock contém também o hash musllinux.
- Depois da instalação, todas as suítes e probes rodam como o usuário runner, via `setpriv`, dentro de `unshare -n`, com `env -i`; sem `pip`, sem DNS e sem `PYTHONPATH` de dependências.
- Nenhum segredo/token é usado; `permissions: contents: read`.

## 5. Privilégios e paths

- `sudo -n` é usado somente para symlinks canônicos efêmeros e para criar o network namespace; os testes rodam como o usuário runner via `setpriv`, sem root, e o runner é efêmero.
- Path filters agora incluem `.opencode/scripts/**`, `.opencode/agents/**`, `.opencode/*.md`, `docs/**`, o workflow e o lock.
- O runner continua sem credenciais, LLM, Android/Gradle/ADB, transporte real ou merge.

## 6. Evidência local

Executado antes do push, no worktree da branch:

```text
ci_network_probe.py --mode os     -> NETWORK-OS-BLOCKED (rc=0)
ci_network_probe.py --mode guard  -> NETWORK-GUARD-BLOCKED (rc=0)
ci_discover.py                    -> Ran 378 tests ... OK
test_supervisor.py                -> 167 passed, 0 failed
regression probe                  -> rc=1 e REGRESSAO-INTENCIONAL-DETECTADA
pip download --require-hashes      -> sucesso com hash
```

Total local: **378 + 167 = 545/545**, rede bloqueada por namespace e guard.

## 7. Evidência CI

- `6eed1562` / run `46020039896`: falhou porque `unshare -rn` (user namespace) impedia o root mapeado de abrir o workspace do runner; corrigido com `unshare -n` + `setpriv` para o usuário runner.
- `0390cfd3` / push `46020329072`: **success**; PR `46020336890`: **success no rerun**.
- Logs: `NETWORK-OS-BLOCKED`, `NETWORK-GUARD-BLOCKED`, `Ran 378 tests ... OK`, `DISCOVER_TESTS=378`, `SUPERVISOR_TESTS=167`, `TOTAL_TESTS=545`, `REGRESSION-DETECTION-OK rc=1`.
- O primeiro run do `0390cfd3` (PR) falhou apenas no teste concorrente P14A3 `test_two_processes_do_not_dispatch_same_mission`: o perdedor às vezes chega a `reserve_dispatch` e imprime traceback de `DuplicateReservation`, enquanto o teste exige `erro:`. O rerun passou e o run de push passou; o workflow **não** tem retry automático. Flake residual do teste, não do namespace.
- `propose` SUCCESS não é CI Python.

## 8. Limitações

- O runner precisa de `sudo -n`/`unshare` disponíveis; se o runner não permitir user/network namespaces, o job falha fechado.
- A preparação do PyYAML é online e depende do PyPI; a execução dos testes é offline.
- Permanecem as fronteiras de P14A/P14B; este PR é somente CI, não autoridade real, transporte ou merge.
- Flake P1/P2 residual do teste concorrente P14A3: o perdedor pode falhar em `reserve_dispatch` (traceback `DuplicateReservation`) em vez de `erro:`; exige correção no teste/CLI de P14A, sem relaxar a CI. O rerun manual não é retry do workflow.
