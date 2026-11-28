# COORD-TRUST-BOUNDARY/P4.6 — causa raiz da autoridade assinada

**Estado:** Draft; correção implementada e validada localmente; aguardando
recertificação por B. Nenhum novo dispatch, merge,
deploy, piloto, usuário persistente, secret de produção, transporte real, POST,
LLM ou Android foi executado.

**Baseline:** `876bc654ce1ab1032f64e25405d89cb8601e55d3` (P4.5).
**Run que falhou:** `46087440250`, job `140800230211`, attempt 1, no HEAD
`876bc654ce1ab1032f64e25405d89cb8601e55d3`.
**SHA de implementação P4.6:** `ca915c7072ec34f0a2db65d1ae7b3ede894facf6`.
**Correção posterior P4.7:** o handler genérico que referenciava `exc` sem
binder foi fechado em
`docs/reports/2026-11-27-coord-trust-crossuid-p47-exception-handler.md`.
**Candidato histórico P4.2 (dispatch FAIL):** `31a150330d2b9c8af420cdfac322fde73132dda8`.
**SHA final certificado:** definido no comentário externo de certificação em
https://github.com/guilhermemellojw/better-talker/pull/87; não é
auto-referenciado neste documento.

## 1. Causa raiz comprovada

A hipótese prioritária foi confirmada localmente com material sintético e sem
sudo/usuários persistente. Sob um UID sem entrada em `/etc/passwd`:

```text
$ unshare --map-user=65533 --map-group=65533 --fork --pid \
    ssh-keygen -lf key.pub -E sha256
No user exists for uid 65533
rc=255

$ unshare --map-user=65533 --map-group=65533 --fork --pid \
    ssh-keygen -Y verify ...
No user exists for uid 65533
rc=255
```

O mesmo `ssh-keygen -lf` funciona como usuário com entrada NSS. Quando a
entrada para 65533 é montada em um mount namespace privado, `getent passwd
65533` a encontra. Portanto:

- `public_key_fingerprint()` e `verify_openssh_signature()` dependem de
  `getpwuid` do OpenSSH;
- `AuthorityAdapter._active_approver()` convertia a falha de fingerprint em
  `AuthorizationRejected`, e o serviço expunha apenas `SERVICE_REJECTED`;
- `TrustService._recover()` chamava fingerprint diretamente e a falha caía no
  `SERVICE_ERROR` genérico;
- `propose` não usa OpenSSH e por isso passava.

Isso explica `authorize` falhando em todos os cenários e a recuperação
retornando `SERVICE_ERROR`.

## 2. Correção mínima

- O harness escreve `passwd`/`group` sintéticos no `WORK_ROOT` (0644 root,
  diretório 0711), apenas com `root`, `bt-coord-trust` `65533` e `nobody`.
- O serviço e o crash service são lançados por `unshare -m --propagation
  private`, que faz `mount --bind` desses arquivos sobre `/etc/passwd` e
  `/etc/group` **somente no namespace do serviço**, e então `setpriv` para
  `65533:65533`.
- Nenhum arquivo de `/etc` do host é alterado; nenhum usuário é criado; o
  serviço continua sem root/capabilities.
- G0 executa, sob `65533`, fingerprint e `ssh-keygen -Y verify` com chave
  sintética e namespace de teste. Falha interrompe antes de G1.
- O adaptador P14B distingue falha local de fingerprint
  (`CRYPTO_INFRASTRUCTURE`) de divergência calculada
  (`FINGERPRINT_MISMATCH`); a recuperação classifica fingerprint, janela,
  assinatura e infraestrutura com códigos distintos.
- A verificação criptográfica continua sendo OpenSSH real; não há mock,
  assinatura inválida aceita nem relaxamento do contrato.

## 3. Diagnóstico estruturado

Respostas de erro agora carregam `reason` allowlisted e sanitizado. Falhas de
infraestrutura OpenSSH/NSS não são mais rotuladas como fingerprint divergente:

- `authorize` com fingerprint indisponível: `SERVICE_REJECTED` com
  `reason=CRYPTO_INFRASTRUCTURE`;
- `authorize` com fingerprint calculado e divergente:
  `reason=FINGERPRINT_MISMATCH`;
- `recover` com fingerprint/verificação indisponível: código
  `CRYPTO_INFRASTRUCTURE`;
- `recover` com assinatura inválida: `UNAUTHENTICATED_PEER` com
  `reason=SIGNATURE_INVALID`;
- `recover` fora da janela: `reason=AUTHORIZATION_EXPIRED` ou
  `AUTHORIZATION_NOT_YET_VALID`;
- material divergente: `TRUST_POLICY_MISMATCH`, `MISSION_MATERIAL_MISMATCH` ou
  `OPERATION_MISMATCH`;
- erro não classificado: `INTERNAL`.

O conjunto allowlisted também cobre `APPROVER_KEY_UNKNOWN`,
`APPROVER_KEY_INACTIVE`, `MISSION_IDENTITY_MISMATCH`,
`MISSION_SOURCE_ERROR`, `MISSION_IDENTITY_INVALID`, `INSUFFICIENT_ENVELOPE`,
`UNKNOWN_SCHEMA`, `INVALID_ENVELOPE`, `REVOCATION_SIGNATURE_INVALID` e
`AUTHORIZATION_REVOKED`. Nenhuma mensagem crua, chave, path ou material
sensível é devolvido.

## 4. Gates e falsos positivos

- **G1:** `authorize` é pré-condição; reserve/assert/evidence/reconcile não são
  executados se a autorização falhar.
- **G3:** prepare/reserva/assert válidos são pré-requisitos; sem lease, rollback
  e replay não são avaliados. Replay pós-recuperação só é avaliado se a
  recuperação abrir a nova epoch.
- **G4:** preparação falha não produz crash/WAL; o ponto é registrado
  BLOCKED/FAIL.
- **G5:** rejeição esperada é comparada ao código; `SERVICE_ERROR` interno não
  vira PASS.
- **G7:** concorrência/limites independentes continuam medidos; a preparação
  autorizada é pré-requisito separado, nunca PASS artificial.
- G0/G2/G6/G8/G9 permanecem preservados.

## 5. Regressões

- `test_service_launch_mounts_synthetic_passwd_for_ssh`: exige mount privado,
  `passwd`/`group` com `65533`, arquivos 0644 e setpriv no comando.
- `test_crypto_infrastructure_and_recovery_reasons_are_distinct`: cobre
  autorização e recuperação, separando infraestrutura, fingerprint, assinatura,
  expiração, trust policy e aprovador desconhecido; garante que nenhuma
  mensagem crua do OpenSSH é devolvida.
- `test_dependent_client_modes_are_blocked_without_authorization`: reserve,
  assert, evidence, reconcile e replays não são executados quando a
  autorização anterior falhou.
- `test_g0_access_requires_permission_error_and_no_write`: ENOENT, leitura e
  escrita indevidas continuam fail-first.
- `test_authorization_rejection_has_safe_reason`: rejeição de assinatura
  retorna `SIGNATURE_INVALID`, sem material sensível.

## 6. Validação local

| Suíte | Resultado |
|---|---|
| `unittest discover` (inclui 41 serviço + 17 P4) | **564/564 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **731 = 564 + 167** |

`test_coord_trust_service.py` (**41/41**) e `test_coord_trust_crossuid.py`
(**17/17**) são subconjuntos do discover. Também passaram `py_compile`, YAML e
`git diff --check`. Nenhum dispatch foi executado nesta rodada; a CI do HEAD
final é registrada no comentário externo do PR #87.

## 7. Limites

- A autorização anterior foi consumida pelo run `46087440250`.
- O mount namespace exige root no runner descartável; serviço e clientes
  continuam sem root. Não há alteração persistente de `/etc` nem criação de
  usuário.
- G6 estrito, `certified_sha`, `run_attempt=1`, timeout de 10 min, limpeza
  `always()`, sanitização e `real_transport=false` permanecem.
- Sem merge, deploy, piloto, transporte real, Android ou LLM pago.

Próximo passo: B recertifica este HEAD e a reprodução/correção; somente depois
uma nova autorização explícita poderá ser considerada.
