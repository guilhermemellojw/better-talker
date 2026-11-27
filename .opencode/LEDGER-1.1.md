# COORD-05A-EXT — Ledger 1.1: autorização assinada, evidência e reconciliação

**Estado:** extensão em PR Draft separado, base `origin/main` `05176ada16bce1e6bd162e494e290b0bd02b0bb4` (linhagem do #70 confirmada: `cc90091a` é ancestral).
**Modo:** SHADOW/sintético. Nenhum transporte real, LLM, credencial, sessão Android/ADB ou migração de banco operacional nesta rodada.

## 1. Objetivo

O ledger 1.0 já é autoridade de estado e reserva, mas não persiste autorização assinada, consumo, revogação, expiração, snapshot da missão, receipt ou evidência de reconciliação. O 1.1 fecha essa lacuna **sem** transformar `evidence-cache.json` em autoridade e sem duplicar criptografia.

O componente de verificação real (#77) permanece externo. O ledger recebe uma porta neutra e persiste apenas o resultado verificado. Fixtures e `SyntheticAuthorization` não são autoridade humana.

## 2. Migração versionada

- `SCHEMA_VERSION = "coord-ledger-1.1"`; `OLD_SCHEMA_VERSION = "coord-ledger-1.0"`.
- `Ledger(..., allow_migration=True)` é obrigatório para abrir 1.0; sem opt-in, `SchemaMigrationRequired`, sem DDL/chmod.
- A migração roda em uma única transação `BEGIN IMMEDIATE`:
  - adiciona colunas a `missions`, `transitions` e `reservations`;
  - cria `authorizations`, `authorization_revocations`, `mission_evidence` e `schema_migrations`;
  - cria índices únicos de autorização/lease;
  - faz backfill de `transmissions_used` (1 se `dispatched_at`, 0 caso contrário);
  - marca registros antigos como `authorization_mode='LEGACY_UNVERIFIED'`;
  - atualiza `meta.schema_version` e registra `schema_migrations` no commit.
- Falha em qualquer DDL → rollback integral; versão 1.0 e dados intactos.
- Registros legados continuam legíveis/históricos, mas não são despacháveis quando a política `require_signed_authorization=True`.
- Antes de migrar um banco operacional, o operador deve fazer backup; esta rodada testa apenas bancos temporários.

## 3. Snapshot canônico

`MissionSnapshot` (em `coord_ledger_authority.py`) contém:

- `mission_id`, `operation_id`, projeto/repositório, coordenador;
- alvo: agente, sessão, worktree e instância;
- `mission_identity` e payload integral;
- `model` exato (inclui `#variant`), `scope`;
- critérios de aceitação;
- ambiente (`FIXTURE`, `SYNTHETIC` ou `SHADOW` nesta rodada);
- HEAD revisado, digests operacionais;
- `max_transmissions=1`, custo e `free_only`.

O snapshot é serializado canonicamente e persistido com `mission_snapshot_sha256`. Ao carregar, o ledger recalcula o hash, o payload hash e compara os campos da linha; qualquer divergência é `LedgerStorageError` (falha fechada).

`ExpectedOperation` é o material que o adapter #77 deve comparar com o envelope assinado. `VerifiedAuthorization` e `VerifiedRevocation` são os resultados tipados que o verificador real devolve. O ledger **não** assina, não lê chaves e não importa #77.

## 4. API

```python
# registro (PROPOSED)
Ledger.register_signed_proposed(
    snapshot: MissionSnapshot,
    *,
    authorization_reference: str,
    timeout_seconds: int = 0,
) -> MissionRecord

# autorização verificada (PROPOSED -> AUTHORIZED)
Ledger.authorize_signed(
    mission_id: str,
    *,
    envelope: Any,
    signature: bytes,
    payload: Mapping[str, Any],
    actor: str,
) -> MissionRecord

# revogação verificada (persistida antes/depois da autorização)
Ledger.revoke_authorization(
    *, statement: Any, signature: bytes, actor: str,
) -> RevocationRecord

# reserva atômica + consumo + lease
Ledger.reserve_dispatch_signed(
    mission_id: str,
    *,
    authorization_digest: str,
    agent: str, session_id: str, instance_id: str, worktree: str,
    actor: str,
) -> DispatchLease

# leitura imediatamente antes do POST; não consome
Ledger.assert_dispatchable(
    mission_id: str,
    *,
    authorization_digest: str,
    lease_id: str,
    actor: str,
) -> DispatchLease

# receipt/evidence imutáveis persistidos no ledger
Ledger.record_dispatch_evidence(
    mission_id: str,
    *,
    authorization_digest: str,
    lease_id: str,
    receipt: Mapping[str, Any],
    evidence: Mapping[str, Any],
    actor: str,
) -> MissionRecord

# reconciliação terminal (COMPLETED/FAILED)
Ledger.reconcile_signed(
    mission_id: str,
    *,
    authorization_digest: str,
    lease_id: str,
    target_state: str,
    report: Optional[Mapping[str, Any]] = None,
    evidence: Optional[Mapping[str, Any]] = None,
    authority_reference: str,
    actor: str,
    human_decision: bool = False,
) -> MissionRecord
```

Leituras: `get_authorization`, `get_authorization_by_digest`, `get_revocation`, `get_evidence`.

`AuthorityVerifier` é uma `Protocol` injetada no `Ledger`; o adapter P14B deve implementá-la com o verificador #77. O agente nunca fornece a porta, allowlist ou um booleano `eligible` por chamada.

## 5. Transações e invariantes

- **Autorização:** `authorize_signed` verifica fora da transação de escrita e relê a missão em `BEGIN IMMEDIATE`; insere `authorizations` e transita `PROPOSED→AUTHORIZED` no mesmo commit. Digest/autorização/missão únicos.
- **Consumo:** `reserve_dispatch_signed` exige `AUTHORIZED`, digest autorizado, snapshot íntegro, `consumed_at IS NULL`, janela válida, sem revogação e `transmissions_used=0`; na mesma transação insere reservation com `lease_id`, marca `consumed_at`, `transmissions_used=1` e `DISPATCHING`.
- **Lease:** `assert_dispatchable` é leitura consistente e exige estado `DISPATCHING`, digest/lease/reservation, consumo, snapshot e janela. Não consome nem cria novo lease.
- **Revogação:** verificada pelo port e persistida por digest; bloqueia `authorize_signed`, `reserve_dispatch_signed` e `assert_dispatchable`. Não cancela POST em voo nem faz rollback de consumo.
- **Expiração:** verificada na autorização, na reserva e no assert; relógio do ledger (`clock` injetável em testes).
- **Reconciliação:** `reconcile_signed` exige receipt persistido para `COMPLETED`; valida projeto/repo/missão/operação/agente/sessão, `reply_to` do receipt, critérios de aceitação, payload, `status=COMPLETED`, ausência de blockers e janela temporal. Divergência → falha fechada. `FAILED` exige evidência/decisão e `authority_reference`.
- **UNKNOWN:** `recover_stale_dispatching` leva `DISPATCHING→UNKNOWN`, preserva reservation e consumo; não há transição para `DISPATCHING` nem reenvio. `reconcile_signed` pode encerrar `UNKNOWN` com receipt/relatório válidos.
- **Idempotência:** registro, autorização, revogação, evidência e reconciliação repetidos com o mesmo material são idempotentes; material divergente é rejeitado.
- **Bypass:** missões `SIGNED_V1` rejeitam `authorize`, `reserve_dispatch`, `complete`, `fail`, `mark_*` e `reconcile` legados; transições assinadas exigem a API 1.1. `can_dispatch` legado retorna falso para `SIGNED_V1` e para legado sob política assinada.
- **Transporte:** o ledger não executa POST. `record_dispatch_evidence` rejeita `service_contacted=True`, `transport_real=True` ou `source=real` quando `allow_real_transport` não está habilitado; esta rodada permanece SHADOW/sintética.
- **Cache:** o ledger 1.1 não lê nem grava `evidence-cache.json`; snapshot, receipt, evidência e reconciliação são persistidos no SQLite. O cache externo não é autoridade para nenhuma transição.

## 6. Compatibilidade

- 49 testes originais do ledger 1.0 continuam passando.
- `register_proposed`, `authorize`, `reserve_dispatch`, `mark_*`, `reconcile` e `recover_stale_dispatching` legados permanecem para registros legados.
- Migração preserva `mission_id`, `operation_id`, `identity_hash`, payload hash, histórico, estados e reservations.
- O schema 1.0 não é aberto sem `allow_migration=True`.

## 7. Integração P14A/P14B

O Agente A deve, em branch própria:

1. construir `MissionSnapshot` a partir do envelope COORD-04 e do `PilotExecutor._snapshot`;
2. registrar com `register_signed_proposed` (não usar `authorize` autodeclarado);
3. implementar `AuthorityVerifier` com #77 e injetá-lo no `Ledger` protegido;
4. usar `reserve_dispatch_signed` e `assert_dispatchable` no lugar de `assert_transmittable` pós-reserva;
5. persistir receipt/evidence com `record_dispatch_evidence`;
6. reconciliar por `reconcile_signed`; não usar cache como autoridade.

A integração real de transporte, credenciais e #72/#77 permanece fora desta PR e depende de UID/serviço protegido.

## 8. Limitações e critérios de aprovação

- Sem transporte real, POST, LLM, sessão, credencial, Android/Gradle/ADB, merge ou migração operacional.
- `SyntheticAuthorization`/fixtures não são autenticação humana; #72/#77 continuam necessários para o piloto real.
- Não há exactly-once; a reservation/consumo reduz duplicação, mas crash após POST vai a `UNKNOWN` sem reenvio.
- A janela entre `assert_dispatchable` e POST é residual; revogação nessa janela não é cancelamento retroativo.
- Aprovação exige: migração 1.1 revisada e testada, snapshot/receipt/evidence vinculados, testes 49+31 host-only, concorrência/crash, e integração P14A/P14B separada.

## 9. Testes desta PR

```sh
cd .opencode/scripts
python3 -m unittest test_coord_ledger test_coord_ledger_11 -v
# 49 + 34 = 83/83
```

Fail-first: em `origin/main` `05176ada` (sem 1.1), os 34 testes novos falham/erram por ausência de `allow_migration`/API assinada; após a extensão, 83/83 passam. O CI atual só executa `propose`; a suíte é host-only.
