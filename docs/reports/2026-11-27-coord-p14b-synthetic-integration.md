# COORD-04/P14B — integração definitiva sintética

**Status:** PR Draft, somente `FIXTURE`/`SHADOW`. Nenhuma operação real, POST real, LLM, sessão real, dispositivo, ADB, credencial real, migração operacional ou merge na `main`.

## Componentes integrados (baselines certificados)

| PR | HEAD | Papel |
|---|---|---|
| #64 | `4ed209f9758431fafa501b337b02859b5612f235` | CLI, missão #64 e convergência `DuplicateReservation` (P14A.5) |
| #72 | `cfc498e0b6f2fb10ff1a4b93c451b048230d95db` | fundação de autorização |
| #77 | `47c7cc871a948d781400976f3b58ba820fa0bfe2` | verificador offline de autoridade assinada |
| #79 | `f57468d637a39ec0cc9b10f7209a53e4a3ba83e5` | CI Python host-only (namespace, guard, hashes) |
| #80 | `27506ea379fbd1d8ee7f6d4dc44717ee099a6048` | ledger 1.1, reconciliação autenticada, `UNKNOWN` |

Os cinco HEADs foram mesclados por merge convencional em branch isolada; nenhum PR de origem foi alterado.

## Matriz de integração

| Requisito | Implementação | Evidência |
|---|---|---|
| CLI → ledger 1.1 | `SignedOperationStore` substitui o store legado em `_fixture_context`; `allow_legacy_dispatch=false` | `test_p14b.P14BSyntheticJourneyTests.test_cli_uses_signed_authority_not_legacy_cache` |
| `MissionSnapshot` e identidade canônica | `coord_p14b.build_snapshot` reusa `canonical_mission_identity` (#64), HEAD executado e digests operacionais | `test_p14b`/`test_p14a4` |
| Política #71 vinculante | `cli.selected_model_for_dispatch` + `cli.fixture_model_recommendation`, modelo no snapshot e no envelope | `test_p14a3`, `test_p14a4.ModelBindingTests` |
| Autorização assinada #77 | `AuthorityPort` traduz `VerificationResult` para `VerifiedAuthorization` do ledger; nenhuma nova autoridade | `test_p14b.P14BNegativeTests.test_forged_signature_is_rejected` |
| Consumo atômico/lease | `reserve_dispatch_signed` → lease de uso único → `assert_dispatchable` antes do POST sintético | `test_p14b.P14BNegativeTests.test_lease_replay_is_blocked` |
| Caminhos legados eliminados | `SignedAuthorizationRequired` / `LegacyDispatchDisabled` sem opt-in | `test_p14b.P14BNegativeTests.test_legacy_dispatch_path_is_closed_by_default` |
| Autoridade do cache eliminada | cache é somente reconstrução de contexto; receipt/snapshot vêm do ledger | `test_p14a3`, `test_p14a4.CacheIntegrityTests` |
| Reconciliação autenticada | `ReconciliationPort` + `reconcile_signed` com atestação vinculada a missão/operação/lease/receipt/relatório | `test_p14b.P14BSyntheticJourneyTests.test_legitimate_authorization_completes` |
| `UNKNOWN` sem reenvio | `recover_stale_dispatching` preserva reserva; ausência de receipt não libera reserva | `test_p14b` (interrupção + revogação/expiração posteriores) |
| Transporte sintético | `http_posts=1`, `service_contacted=false`, `transport_real=false` | `test_p14a5`, `test_p14b` |

## Testes (host-only, rede bloqueada por `unshare -rn`)

- `unittest discover`: **492** testes, OK.
- `test_supervisor.py`: **167** passed, 0 failed.
- Total integrado: **659** (sem duplicar contagens: 492 + 167).
- Baseline do `coord-python.yml` atualizado de 378/545 para 492/659; namespace `sudo -n unshare`, `setpriv`, `sitecustomize`, `permissions: contents: read`, hashes de dependências e detecção de regressão intencional preservados.

## Delta ainda não certificado

- O adaptador `coord_p14b.py`, a migração de `_fixture_context` e as adaptações de testes P14A são **novos** neste PR e dependem de certificação focal independente (Agente B).
- O adapter criptográfico do Agente E ainda não foi integrado: a chave sintética é gerada localmente por `ssh-keygen` em diretório temporário/privado e usada apenas para assinar o verificador `#77`; nenhuma infraestrutura operacional foi criada.
- Verificadores de revogação/reconciliação são sintéticos (`FIXTURE`), não autoridade humana real.
- Fronteira de confiança (UID separado, trust store/chaves inacessíveis aos agentes) permanece pendente para operação real.
