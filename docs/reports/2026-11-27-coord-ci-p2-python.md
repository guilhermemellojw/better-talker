# COORD-CI/P2 — CI Python funcional (Agente A)

**PR #79** `ci/coord-python-host-only` · branch dependente sincronizada com P14A.4 por **merge convencional**. **PR #64 não modificado.**

> Nenhum workflow Android/release, Gradle, ADB, LLM, credencial, transporte real, merge à main ou interferência no ledger 1.1 / Agente E.

---

## A. Diagnóstico das falhas

Runs `46013599034` e `46013591212` (HEAD `2300c373`): `unittest discover` falhava com `Permission denied` ao abrir `ci_discover.py` porque o runner usava `sudo -n unshare` com `env -i`; a suíte do supervisor era **skipped**. Causa de ambiente/runner.

Runs `46014075190`, `46014083504` (HEAD `33d1518f`): com `sudo` removido e guarda de rede aplicada, a descoberta executou 378 testes, mas falhou por:

1. **Ancestralidade desatualizada** — a branch CI estava em `e92bedca` (P14A.3) e não continha P14A.4; `coord_pilot.py` exigia um caminho absoluto de worktree/canonical (`/home/guilherme/Documentos/...`) que não existe no runner, produzindo `common-dir Git ausente` em 77 erros e 11 falhas.
2. **Baseline defasada** — workflow esperava 368 descobertos / 535 totais, mas o código já tinha 378 / 545.
3. **Dependência ausente** — `test_supervisor.py` importa `yaml`, não instalado no Python do runner (`ModuleNotFoundError`).

Não houve regressão real nas suítes: after sync + dependência + caminhos esperados, ambas passam.

## B. Correção aplicada

- Merge convencional de `feat/coord-communication-foundation` (`0a21cf5e`) na branch CI; nenhum force-push e nenhuma alteração ao PR #64.
- Instalação de `PyYAML==6.0.2` antes do isolamento.
- Criação de links simbólicos efêmeros para os caminhos canônicos/worktree esperados pelos testes (`sudo -n ln -sfn`).
- Baselines atualizadas: `unittest discover=378`, `supervisor=167`, `total=545`.
- Novo step **Provar detecção de regressão intencional**: cria teste temporário com falha explícita, executa o mesmo runner e exige `rc != 0`; nenhum `continue-on-error`, nenhum teste ignorado.
- Suíte do supervisor com `if: always()` para garantir execução efetiva mesmo se a descoberta falhar.
- Sanitização e upload incluem `regression-probe.log`.
- Rede bloqueada: `unshare -rn` na prova e `ci_netguard/sitecustomize.py` no `PYTHONPATH` das duas suítes.

## C. HEADs e runs

| Item | Valor |
|---|---|
| PR #79 head / HEAD final | `968e5b996a8bf3d7e3f1ce541f75927d3bcb44db` |
| PR #64 | `0a21cf5eee76dfed5a8d11994d7b1acfced071fb` (intacto) |
| Runs com falha | `46013599034`, `46013591212`, `46014075190`, `46014083504`, `46016806168`, `46016815057` |
| Runs finais aprovados | `46017099447`, `46017114298`, `46017142178`, `46017154261` |

Todos os steps dos runs finais: `success`, incluindo as duas suítes e a prova de regressão.

## D. Resultado das suítes e isolamento

- `DISCOVER_TESTS=378`, `Ran 378 tests ... OK`.
- `SUPERVISOR_TESTS=167`, `RESULTADO: 167 passed, 0 failed`.
- `TOTAL_TESTS=545` e `RESULTADO: discover=378 supervisor=167 total=545 (somente host-only, rede bloqueada)`.
- `NETWORK-BLOCKED OSError` (unshare) e `NETWORK-GUARD-ACTIVE NetworkDisabled` (guarda aplicada aos testes).
- `REGRESSION-DETECTION-OK rc=1` — o CI falha diante da regressão intencional, sem ocultá-la.

Logs sanitizados publicados como artefato `coord-python-logs`. Nenhuma credencial coletada.

## E. Veredito

**COORD-CI/P2 — CI PYTHON FUNCIONAL — SUÍTES EXECUTADAS COM REDE BLOQUEADA.** 378 + 167 = 545 testes efetivamente executados no CI, baseline correta, detecção de regressão comprovada, PR #64 e fronts paralelos preservados.
