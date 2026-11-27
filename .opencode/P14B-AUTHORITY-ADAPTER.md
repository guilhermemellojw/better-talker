# P14B — Adapter de autoridade #77 → ledger 1.1 (#80)

**Estado:** contrato publicado antecipadamente em PR Draft. Implementação e
regressões na mesma branch, sem merge.
**Branch:** `feat/p14b-authority-adapter`.
**Base de integração:** `feat/coord05a-ledger-1.1` @
`27506ea379fbd1d8ee7f6d4dc44717ee099a6048` (#80).
**Verificador dependente:** `47c7cc87` (#77), arquivos copiados verbatim.
**Identidade canônica dependente:** `4ed209f9` (#64),
`coord_communication.py` copiado verbatim.
**Nenhum HEAD de #64/#77/#80 é alterado por esta branch.**

| Dependência | Origem | SHA-256 do arquivo |
| --- | --- | --- |
| `coord_authority.py` | #77 `47c7cc87` | `3435f9bd976f2e06187c2541c1e7f638e275ab8cd6cac9b6570241ec387faf7f` |
| `coord_authority_signed.py` | #77 `47c7cc87` | `467fb88055e357a5da329262c087dd73ef644e87c6463fdada07f0ae089575c1` |
| `coord_communication.py` | #64 `4ed209f9` | `e972edec1ea77544fd2174ea6bb5718f8966e66230cb9d812ad0b114eb01df4d` |

## 1. Objetivo e separação

O adapter responde somente:

1. a assinatura OpenSSH destacada foi produzida por uma chave da allowlist
   pinada, ativa e autorizada para o namespace correto?
2. o envelope assinado corresponde exatamente ao `MissionSnapshot`/material
   canônico do ledger 1.1, incluindo payload, critérios, ambiente, modelo,
   identidade, alvo e limites?
3. a atestação terminal foi assinada pela mesma autoridade e vincula
   receipt/report/evidence/resultado?

Ele **não** decide sozinho “pode despachar?”. Consumo, reserva, lease,
revogação persistida, UNKNOWN e replay pertencem ao ledger #80. O adapter é
uma porta neutra que devolve tipos verificados (`VerifiedAuthorization`,
`VerifiedRevocation`, `VerifiedReconciliation`) e falha fechada.

## 2. Fronteira de confiança

Uma assinatura válida em testes não prova uma fronteira implantada. A chave
privada, a allowlist, o código do adapter, o resolver de identidade e o SQLite
do ledger precisam estar fora do alcance de escrita/leitura do agente. Mesmo
UID não é separação de autoridade. Sem essa separação, o máximo obtido é
integridade material e detecção de divergência, não autoridade humana provada.

## 3. Envelope de autorização V2

O envelope V1 do #77 **não** vincula `acceptance_criteria` nem `environment`,
portanto é insuficiente para o ledger 1.1. O adapter aceita somente:

```json
{
  "schema": "BT-COORD-AUTHORIZATION-V2",
  "authorization_id": "AUTH-...",
  "approval_reference": "APP-...",
  "approver_id": "maintainer",
  "approver_key_fingerprint": "SHA256:...",
  "trust_policy_sha256": "<sha256 da allowlist>",
  "mission_id": "...",
  "operation_id": "...",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "coordinator_id": "...",
  "mission_identity": "<sha256 de canonical_mission_identity(#64)>",
  "payload": {"...": "..."},
  "payload_sha256": "<sha256 do payload efetivo>",
  "target": ["agent", "ses-...", "/worktree/absoluto", "instance"],
  "model": "provider/model#variant",
  "scope": "...",
  "acceptance_criteria": ["critério 1", "critério 2"],
  "environment": "SHADOW",
  "reviewed_head_sha": "<40 hex>",
  "operational_digests": {"coord_ledger.py": "<sha256>"},
  "max_transmissions": 1,
  "cost_ceiling_usd": "0",
  "free_only": true,
  "issued_at": "2026-11-27T12:00:00Z",
  "expires_at": "2026-11-27T12:15:00Z",
  "nonce": "nonce-...",
  "mode": "SHADOW"
}
```

Regras:

- JSON canônico (`sort_keys`, separadores `,`/`:`, UTF-8).
- Bytes assinados: `b"BT-COORD-AUTHORIZATION-V2\n" + canonical_json(material)`.
- Namespace OpenSSH: `bt-coord-authorization-v2`.
- `authorization_digest = SHA-256(bytes assinados)`; é o digest persistido e
  revogável.
- `payload_sha256` e `mission_identity` são recalculados, nunca aceitos como
  declaração do agente.
- `acceptance_criteria` é lista não vazia de textos não vazios e deve ser
  idêntica à lista canônica da `MissionEnvelope` #64.
- `environment` e `mode` são `SHADOW` nesta rodada. O caminho humano recusa
  `FIXTURE`/`SYNTHETIC`; fixtures usam verificador fake/sintético separado.
- `max_transmissions` é exatamente `1`; `free_only=true` exige custo `0`/ausente.
- Campos extras, campos ausentes, schema V1 ou schema desconhecido são
  rejeitados com `AuthorizationRejected`; não há downgrade silencioso.
- O envelope assinado é persistido pelo ledger (`envelope_json` + hash) e
  reinterpretado/revalidado em cada gate, inclusive após restart.

## 4. Identidade canônica

O adapter exige uma fonte confiável de `MissionEnvelope` #64:

```python
class MissionEnvelopeSource(Protocol):
    def get(self, mission_id: str) -> MissionEnvelope: ...
```

Em cada verificação o adapter:

1. obtém a missão pelo `mission_id` esperado;
2. valida a `MissionEnvelope` e recalcula
   `coord_communication.canonical_mission_identity(mission)`;
3. compara a identidade recalculada com `ExpectedOperation.mission_identity`
   e com o envelope assinado;
4. compara payload, hash, alvo, projeto/repositório, operação, coordenador,
   escopo, critérios de aceitação e HEAD revisado com a mesma `MissionEnvelope`.

Uma identidade apenas copiada do envelope ou do ledger não é suficiente. Se a
fonte canônica estiver ausente, o adapter falha fechado.

## 5. Atestação terminal

A atestação é um objeto separado, assinado com namespace próprio:

```json
{
  "schema": "BT-COORD-RECONCILIATION-V1",
  "authority_id": "maintainer",
  "approver_id": "maintainer",
  "approver_key_fingerprint": "SHA256:...",
  "trust_policy_sha256": "<sha256 da allowlist>",
  "mission_id": "...",
  "authorization_digest": "<digest V2>",
  "payload_sha256": "<sha256>",
  "receipt_sha256": "<sha256>",
  "report_sha256": "<sha256>",
  "evidence_sha256": "<sha256>",
  "outcome": "COMPLETED",
  "environment": "SHADOW",
  "issued_at": "2026-11-27T12:00:00Z",
  "expires_at": "2026-11-27T12:10:00Z",
  "nonce": "nonce-recon-...",
  "mode": "SHADOW"
}
```

- Bytes: `b"BT-COORD-RECONCILIATION-V1\n" + canonical_json(material)`.
- Namespace: `bt-coord-reconciliation-v1`.
- `authority_id` deve ser igual ao `approver_id` nesta versão; autoria e chave
  são a mesma identidade.
- Todos os digests são comparados com `ExpectedReconciliation`; outcome,
  janela, allowlist, fingerprint e trust policy são revalidados.
- A atestação não substitui receipt/relatório/evidência; ela autentica os
  hashes persistidos pelo ledger.
- `environment`/`mode` são `SHADOW`; uma atestação de fixture não pode
  completar uma missão de autoridade humana.

## 6. Revogação

A revogação reutiliza o contrato V1 do #77 (`SignedRevocation`,
`bt-coord-revocation-v1`) e é indexada pelo `authorization_digest` V2. O
adapter:

- valida schema, assinatura, allowlist, fingerprint, trust policy e janela da
  chave;
- devolve `VerifiedRevocation` somente para revogação criptograficamente
  válida;
- aceita `revoked_at` futuro como verificada, mas o ledger só a aplica quando
  `revoked_at <= now`;
- rejeita assinatura inválida, chave desconhecida/inativa, trust policy
  divergente e schema inválido.
- O ledger persiste o digest; o adapter não persiste consumo nem replay.

## 7. API do adapter

```python
class AuthorityAdapter:
    def __init__(
        self,
        *,
        allowlist: cas.ApproverAllowlist,
        mission_source: MissionEnvelopeSource,
        expected_trust_policy_sha256: str | None = None,
    ): ...

    def verify_authorization(
        self, *, envelope: Mapping[str, Any], signature: bytes,
        payload: Mapping[str, Any], expected: ledger_authority.ExpectedOperation,
        now: datetime,
    ) -> ledger_authority.VerifiedAuthorization: ...

    def verify_revocation(
        self, *, statement: Mapping[str, Any], signature: bytes,
        now: datetime,
    ) -> ledger_authority.VerifiedRevocation: ...

    def verify_reconciliation(
        self, *, attestation: Mapping[str, Any], signature: bytes,
        expected: ledger_authority.ExpectedReconciliation,
        now: datetime,
    ) -> ledger_authority.VerifiedReconciliation: ...
```

Helper:

```python
def snapshot_from_envelope(
    mission: cc.MissionEnvelope,
    envelope: SignedAuthorizationV2,
) -> ledger_authority.MissionSnapshot
```

O helper recalcula a identidade #64, exige consistência integral e produz o
snapshot que o P14B registra no ledger com `register_signed_proposed`.

## 8. Códigos e falhas

Falhas de autorização são `AuthorizationRejected`; de revogação,
`RevocationRejected`; de reconciliação, `ReconciliationRejected` — nunca
`False` silencioso. Casos esperados incluem:

`INSUFFICIENT_ENVELOPE`, `UNKNOWN_SCHEMA`, `TRUST_POLICY_MISMATCH`,
`APPROVER_KEY_UNKNOWN`, `FINGERPRINT_MISMATCH`, `APPROVER_KEY_INACTIVE`,
`AUTHORIZATION_NOT_YET_VALID`, `AUTHORIZATION_EXPIRED`, `SIGNATURE_INVALID`,
`OPERATION_MISMATCH`, `MISSION_IDENTITY_MISMATCH`, `MISSION_MATERIAL_MISMATCH`,
`REVOCATION_SIGNATURE_INVALID`, `REVOCATION_NOT_EFFECTIVE`,
`RECONCILIATION_MISMATCH`, `RECONCILIATION_EXPIRED`.

## 9. Limitações

- Sem chaves reais, credenciais, transporte, POST, LLM, sessão, Android/ADB,
  serviço operacional, migração ou merge.
- O adapter não assina, não gera chaves e não persiste estado.
- Replay é responsabilidade do ledger (#80): digest único, consumo atômico,
  reservation e estado. O adapter é stateless.
- SHADOW/sintético nesta rodada; a fronteira real depende de UID/serviço
  separado, hardware/signer service e allowlist protegida.
- Fixtures e chaves sintéticas não são autoridade humana.
