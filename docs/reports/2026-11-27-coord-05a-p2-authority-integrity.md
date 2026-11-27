# COORD-05A-EXT/P2 — Integridade da autoridade assinada

**PR #80** · branch `feat/coord05a-ledger-1.1` · base `main` `05176ada` · **HEAD: commit que contém este relatório; hash publicado no comentário de recertificação**.
**Escopo:** ledger 1.1, contrato neutro, testes e documentação; #64/#77/#80 não foram alterados por esta rodada além do próprio #80.

## 1. Matriz de resolução dos achados

| ID | Achado da auditoria | Resolução | Evidência |
|---|---|---|---|
| **P0-1** | vínculo autorização↔snapshot não revalidado no assert/reconcile | `_revalidate_authorization` compara snapshot/authority material persistidos e recalculados em reserva, assert, evidência e reconciliação | `test_revalidation_rejects_snapshot_mutation_at_assert_and_reconcile`, `test_authority_material_binds_criteria_and_payload` |
| **P0-2** | assinatura/autorização persistida não revalidada; DB autoridade | envelope+assinatura persistidos; `_revalidate_authorization` chama o `AuthorityVerifier` e compara digest/material/janela; `expires_at` não é aceito isoladamente | `test_revalidation_rejects_signature_and_expiry_db_tamper`, `test_expiry_database_mutation_rejected_by_revalidation` |
| **P1-1** | reconciliação autodeclarada | `ReconciliationVerifier` + `ExpectedReconciliation`/`VerifiedReconciliation`; atestação assinada obrigatória; `authority_reference`/`human_decision` livres removidos | `test_attestation_is_required_and_self_declared_fields_do_not_authorize` |
| **P1-2** | critérios/ambiente fora do material; payload opcional; tempo anterior ao dispatch | `authority_material` inclui payload_sha256, critérios e ambiente; relatório exige payload_sha256 e `completed_at >= dispatched_at`; autoridade atesta report/evidence/receipt | `test_authority_material_binds_criteria_and_payload`, `test_report_requires_payload_and_post_dispatch_completion` |
| **P1-3** | legado opt-in | despacho legado fail-closed por padrão; `allow_legacy_dispatch` explícito; `can_dispatch` falso sem opt-in | `test_legacy_default_disabled_and_explicit_allow`, suíte original com policy explícita |
| **P1-4** | evidência REAL em FIXTURE | `_validate_transport_material` aplicado à evidência de reconciliação; `source` obrigatório e ambiente coerente | `test_real_evidence_rejected_for_fixture_reconciliation` |
| **P1-5** | nonce de revogação não único | `UNIQUE(nonce)` + índice único; conflito → `EvidenceDivergence` | `test_revocation_nonce_is_unique` |
| **P2-1** | receipt/evidence/reconciliation só com hash local | hash e JSON são conferidos em assert/reconciliação; qualquer divergência falha fechado | `test_evidence_hash_and_post_terminal_tamper_rejected`, `test_receipt_database_tampering_rejected_at_reconciliation` |
| **P2-2** | autorização expirada/revogada em AUTHORIZED sem terminalização | `terminate_signed_before_dispatch` terminaliza AUTHORIZED/DISPATCHING sem receipt, limpa reservation e preserva consumo | `test_terminate_expired_or_revoked_before_dispatch` |
| **P2-3** | evidência pós-terminal e reconciliation_json adulterado | missão terminal só aceita evidência idempotente; reconciliation_json é verificado pelo hash nos caminhos de reconciliação **e** de recebimento de evidência | `test_post_terminal_evidence_and_reconciliation_tamper_rejected` |
| **P2-4** | schema auxiliar incompatível/migração concorrente | validação de schema 1.1, lock de inicialização entre processos e conexões de leitura sempre fechadas (sem snapshot WAL órfão após fork) | `test_incompatible_auxiliary_table_fails_closed`, `test_concurrent_migration_is_safe`, `test_multiprocess_reservation_has_one_winner` |
| **P3** | docs/edge cases | contagem 49+49=98 e `recover_stale_dispatching` usando clock injetado | documentação e suíte |

## 2. Autoridade e gates

- O envelope assinado é persistido (`envelope_json`/SHA-256) junto da assinatura; o ledger não confia em `expires_at` ou outro campo mutável isolado.
- `reserve_dispatch_signed`, `assert_dispatchable`, `record_dispatch_evidence` e `reconcile_signed` revalidam assinatura, snapshot, material, ambiente, aprovador, trust policy e janela pelo port injetado.
- O port continua neutro: `AuthorityVerifier`/`ReconciliationVerifier`; o ledger não importa #72/#77, não assina e não lê chaves.
- `MissionSnapshot.authority_material` agora cobre `payload_sha256`, critérios de aceitação e ambiente, além de missão/modelo/escopo/HEAD/digests/limite/custo/free_only.

## 3. Reconciliação

- `reconcile_signed` exige `attestation` + assinatura verificada, receipt persistido para `COMPLETED`, relatório com payload_sha256, coerência temporal (`completed_at >= dispatched_at`, atestação ≥ relatório) e evidência com procedência/ambiente.
- `UNKNOWN` continua sem reenvio; a reconciliação de despacho ocorrido **antes** de expiração/revogação é permitida, mas uma nova transmissão nunca é autorizada.
- Autorização expirada/revogada **antes** do POST pode ser terminalizada com `terminate_signed_before_dispatch`, sem receipt e sem reenvio.
- Evidência REAL em missão FIXTURE é rejeitada; cache externo não é autoridade.

## 4. Fronteira de confiança

Hashes locais e revalidação detectam adulteração e erros, mas **não** protegem contra um agente com escrita irrestrita no SQLite, no trust store ou no código do verificador. A garantia forte depende de DB/ledger/verifier em UID/serviço separado e da allowlist fora do alcance do agente. Sem essa separação, o ledger 1.1 é defesa em profundidade, não uma âncora humana implantada. Fixtures e `AuthorityVerifier` fake não são autenticação humana.

## 5. Testes

Host-only, `unshare -rn`, `HOME`/`TMPDIR`/`XDG_STATE_HOME` isolados:

| Comando | Resultado | SHA-256 do log |
|---|---|---|
| `unittest test_coord_ledger test_coord_ledger_11` | **98/98 OK** (49 antigos + 49 novos) | `cc05dbd107c6dc2b36d43b83482030ef44ae2aca3a8169a4bc73707f4d61e318` |
| `unittest discover` | **168/168 OK** (repetido 5x sem flake) | `9376f837c82de3a4e82d4423d15eb3183cd45c68c1f45dbb6cbb805876f7e505` |
| `test_supervisor.py` | **167/167 passed** | `92f3f934f682b8c48a854d41c2f6fd30b5173d7a7ff70987edeed1b18e8e79c4` |

O CI atual só executa `propose`; a suíte é host-only. Fail-first: no HEAD anterior `2f5c855f` os 49 testes novos falham/erram por ausência/API antiga; as sondas de auditoria do parecer `7221498417` reproduzem os ataques e estão codificadas nas regressões acima.

## 6. Limitações/dependências

- `AuthorityVerifier`/`ReconciliationVerifier` reais (#77/autoridade) ainda não estão integrados; o adapter precisa incluir payload/critérios/ambiente no material assinado e produzir atestações terminais.
- Sem transporte real, POST, LLM, credencial, sessão, Android/Gradle/ADB, sudo ou migração operacional.
- Sem exactly-once; crash após POST → `UNKNOWN`, sem reenvio; a janela assert→POST é residual.
- P14A/P14B continuam integração separada; este PR não altera #64/#77.

Solicito recertificação independente no HEAD final do PR #80.
