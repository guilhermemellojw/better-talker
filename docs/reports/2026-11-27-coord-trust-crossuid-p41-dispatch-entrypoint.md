# COORD-TRUST-BOUNDARY/P4.1 — entrada de dispatch consolidada

**Estado:** Draft; job cross-UID **não disparado** nesta rodada. Nenhum merge,
deploy, usuário persistente, secret de produção, transporte real, POST, LLM,
Android ou ADB foi executado.

**PR:** #87 · **branch:** `feat/coord-trust-crossuid-cert`.
**Base P4 recebida:** `186819d71c031455d72b825772f00b9153070e86`.
**HEAD do código/final:** o commit que contém este relatório; o SHA exato é
publicado no comentário de certificação do PR #87.

## 1. Mudança focal

| Antes (P4) | Depois (P4.1) |
|---|---|
| `.github/workflows/coord-trust-crossuid.yml` dedicado | job `crossuid` dentro de `.github/workflows/coord-python.yml` |
| `workflow_dispatch` sem `coord-python` | `workflow_dispatch` no workflow já registrado na branch padrão |
| sem input de SHA certificado | input `certified_sha` + checkout exato + guarda de SHA |

O workflow dedicado foi removido; o harness G1–G9 existente foi preservado. Não
há duplicação de workflow nem de harness.

### Entrada e jobs

- `workflow_dispatch.inputs.crossuid_full`: **boolean**, default **false**,
  required true.
- `workflow_dispatch.inputs.certified_sha`: string 40-hex; usado apenas no job
  cross-UID.
- Job `coord-python`: `if: github.event_name != 'workflow_dispatch' || inputs.crossuid_full == false`.
  Portanto roda em push/PR e em dispatch manual comum, mas **não** roda junto ao
  dispatch cross-UID.
- Job `crossuid`: `if: github.event_name == 'workflow_dispatch' && inputs.crossuid_full == true`.
  Exclusivamente manual/opt-in; nunca em push/PR.
- `timeout-minutes: 10` no job cross-UID; `concurrency` serializa execuções
  cross-UID; `permissions: contents: read` no workflow; nenhum secret.
- Checkout do job cross-UID usa `ref: ${{ inputs.certified_sha }}` e
  `persist-credentials: false`; uma guarda valida o formato 40-hex antes do
  checkout e `git rev-parse HEAD == certified_sha` depois.
- O checkout do job ordinário também usa `persist-credentials: false`.
- O job cross-UID preserva `unshare -n`, `setpriv`, pré-condições, harness,
  sanitização de logs e artefatos sanitizados.

## 2. Contrato e validação local

`test_coord_trust_crossuid.py` foi reconciliado: agora lê
`.github/workflows/coord-python.yml`, exige os dois jobs, os inputs, as condições
de dispatch, o timeout de 10 min, o checkout do SHA certificado, a guarda e a
ausência do workflow dedicado.

| Suíte | Resultado |
|---|---|
| `test_coord_trust_crossuid.py` | **8/8 OK** |
| `test_coord_trust_service.py` | **40/40 OK** |
| `unittest discover` | **554/554 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **721** |

Também passaram `py_compile`, parser YAML do workflow, `git diff --check` e
verificação de que o arquivo dedicado não existe mais. Nenhum dispatch foi
executado.

## 3. Preservação

- G1–G9, UIDs numéricos, layout, permissões de arquivos, nonce/rollback,
  crash/replay, sandbox, `real_transport=false` e `TRANSPORT_DISABLED`
  permanecem no harness sem alteração de lógica.
- Nenhum `useradd`/`adduser`, secret de produção, LLM, API externa, serviço
  real, sessão OpenCode, transporte real, Android/ADB ou merge.
- A suíte ordinária continua com `timeout-minutes: 20`; o job cross-UID tem
  limite próprio de 10 min.

## 4. Limites e próximo passo

- A definição do workflow vem da ref usada no dispatch; o **código executado**
  é o `certified_sha` exato. O dispatch deve partir da `main`, com o SHA
  previamente certificado por B.
- O teste cross-UID real continua **BLOCKED** neste host; o job só roda em
  runner descartável.
- Nenhum dispatch, merge, deploy ou transporte real foi autorizado nesta
  rodada.

Próximo passo: B certifica o novo HEAD e o contrato do job `crossuid`; somente
depois, com autorização humana específica, executar um dispatch único com
`crossuid_full=true` e `certified_sha` exato.
