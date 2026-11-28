# COORD-TRUST-BOUNDARY/P4.5 — fechamento de evidência G0/G8

**Estado:** Draft; correção exclusiva das provas de acesso. A causa raiz
OpenSSH/NSS está em
`docs/reports/2026-11-27-coord-trust-crossuid-p46-signed-authority.md`.
Nenhum dispatch,
merge, deploy, piloto, usuário persistente, secret de produção, transporte
real, POST, LLM ou Android foi executado.

**Baseline:** `d2022b2062a5a694e3bc724a5c9553e2d00b4d04` (P4.4 documental).
**SHA de implementação P4.5:** `7066a482ff9e7f4628f8d9962f6623a213a6eba3`.
**Candidato histórico (dispatch FAIL):** `31a150330d2b9c8af420cdfac322fde73132dda8`.
**SHA final certificado:** definido no comentário externo de certificação em
https://github.com/guilhermemellojw/better-talker/pull/87; não é
auto-referenciado neste documento.

## 1. G0 — antes do serviço

`ACCESS_PROBE` distingue o tipo real de erro:

- `dir` existente sem acesso → `PermissionError`; inexistente →
  `FileNotFoundError`; acessível → `readable`.
- `file` existente sem acesso → `PermissionError`; inexistente →
  `FileNotFoundError`; acessível → `readable`.
- `nowrite` retorna `not-writable` somente quando `os.access(W_OK)` é falso.

Condições obrigatórias para `65533`/`65534`:

| Caminho | Serviço | Consumidor |
|---|---|---|
| `state/` (existe, diretório) | legível | `PermissionError` |
| trust store | legível | `PermissionError` |
| consumer registry | legível | `PermissionError` |
| âncora (diretório) | legível | `PermissionError` |
| chaves (diretório + arquivo sintético existente) | `PermissionError` | `PermissionError` |
| `work_root` / `runtime` | `not-writable` | `not-writable` |
| scripts/imports/ancestrais | legível/importa/atravessa | legível/importa/atravessa |

Ledger e arquivo de âncora ainda não existem antes do start: G0 exige os
diretórios pais existentes e negação efetiva. `FileNotFoundError` nunca é
aceito como prova de isolamento. Falha de qualquer condição obrigatória
interrompe antes de G1.

## 2. G8 — depois do start

O harness confirma como root que ledger, trust store, consumer registry,
âncora e arquivo de chave existem. O consumidor deve retornar exatamente
`PermissionError` para cada um, não `FileNotFoundError`; `state_read_blocked`
também exige a lista completa. O `sandbox`/G8 só passa com existência
confirmada + negação efetiva.

## 3. Bundle vinculado ao SHA certificado

- O workflow passa `--certified-sha "$CERTIFIED_SHA"`.
- `prepare_runtime_bundle()` exige `HEAD == certified_sha` e compara os bytes
  copiados com os blobs `HEAD:.opencode/scripts/<name>` via `git show`.
- O manifesto registra `source_head` e `certified_sha`, além dos SHA-256 por
  arquivo.
- O resumo sanitizado preserva, após a limpeza, `runtime_bundle` (source/
  certified/arquivos) e `access_evidence` (G0/G8). O manifesto bruto e
  `cleanup.json` são removidos com o `WORK_ROOT`/`OUT`, mas a evidência
  sanitizada necessária permanece no artefato `summary.json.sanitized` e no log.

## 4. Regressões fail-first

`test_g0_access_requires_permission_error_and_no_write` falha se:

- `FileNotFoundError` for aceito no lugar de `PermissionError`;
- um caminho obrigatório for `readable` para o consumidor;
- `work_root`/`runtime` for `writable`.

O teste passa somente com existência confirmada, `PermissionError`/`not-writable`
e serviço com leitura esperada. Falha em qualquer condição impede G1.

## 5. Validação local

| Suíte | Resultado |
|---|---|
| `unittest discover` (inclui 41 serviço + 17 P4 + 14 P9) | **578/578 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **745 = 578 + 167** |

`test_coord_trust_service.py` (**41/41**) e `test_coord_trust_crossuid.py`
(**17/17**) são subconjuntos do discover; não entram novamente na soma.
Também passaram `py_compile`, YAML e `git diff --check`. Logs locais não são
versionados.

## 6. Limites

- A autorização anterior foi consumida pelo run `46081657455` (FAIL). O
  dispatch posterior do HEAD P4.5
  `876bc654ce1ab1032f64e25405d89cb8601e55d3` falhou no run `46087440250`
  (G1/G3/G4/G5/G7); a causa e a correção P4.6 estão em
  `docs/reports/2026-11-27-coord-trust-crossuid-p46-signed-authority.md`.
  Nenhuma nova execução está autorizada.
- G6 estrito, `certified_sha`, `run_attempt=1`, timeout de 10 min, limpeza
  `always()`, sanitização e `real_transport=false` permanecem inalterados.
- Sem merge, deploy, piloto, transporte real, instalação ou criação de usuário.

Próximo passo: B recertifica este HEAD e as evidências G0/G8; somente depois uma
nova autorização explícita poderá ser considerada.
