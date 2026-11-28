#!/usr/bin/env python3
"""P14B — adapter entre o verificador #77 e o ledger 1.1 (#80).

O adapter é uma porta neutra: verifica assinaturas OpenSSH destacadas e
material canônico, mas não assina, não gera chaves, não persiste consumo e não
decide transporte. O ledger #80 continua sendo a autoridade operacional.

Contrato publicado em `.opencode/P14B-AUTHORITY-ADAPTER.md`.

Dependências copiadas verbatim nesta branch stacked:

* `coord_authority.py` e `coord_authority_signed.py` — #77 `47c7cc87`;
* `coord_communication.py` — #64 `4ed209f9` (identidade canônica da missão).

O envelope V1 do #77 não vincula `acceptance_criteria`/`environment`; por isso
este adapter aceita somente o envelope V2 e recusa V1 sem downgrade.
"""
from __future__ import annotations

import hashlib
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping, Optional, Protocol

import coord_authority as ca
import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger_authority as la

AUTHORIZATION_SCHEMA = "BT-COORD-AUTHORIZATION-V2"
AUTHORIZATION_NAMESPACE = "bt-coord-authorization-v2"
AUTHORIZATION_DOMAIN = b"BT-COORD-AUTHORIZATION-V2\n"

RECONCILIATION_SCHEMA = "BT-COORD-RECONCILIATION-V1"
RECONCILIATION_NAMESPACE = "bt-coord-reconciliation-v1"
RECONCILIATION_DOMAIN = b"BT-COORD-RECONCILIATION-V1\n"

# O caminho de autoridade humana é SHADOW. Fixtures usam verificador próprio.
ALLOWED_ENVIRONMENTS = frozenset({"SHADOW"})
ALLOWED_MODES = frozenset({"SHADOW"})

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")
_MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*(?:#[A-Za-z0-9._-]+)?$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_HEAD = re.compile(r"^[0-9a-f]{40}$")
_FINGERPRINT = re.compile(r"^SHA256:[A-Za-z0-9+/]+={0,2}$")

_AUTHORIZATION_FIELDS = frozenset({
    "schema", "authorization_id", "approval_reference", "approver_id",
    "approver_key_fingerprint", "trust_policy_sha256", "mission_id",
    "operation_id", "project_id", "repository_id", "coordinator_id",
    "mission_identity", "payload", "payload_sha256", "target", "model",
    "scope", "acceptance_criteria", "environment", "reviewed_head_sha",
    "operational_digests", "max_transmissions", "cost_ceiling_usd",
    "free_only", "issued_at", "expires_at", "nonce", "mode",
})

_RECONCILIATION_FIELDS = frozenset({
    "schema", "authority_id", "approver_id", "approver_key_fingerprint",
    "trust_policy_sha256", "mission_id", "authorization_digest",
    "payload_sha256", "receipt_sha256", "report_sha256", "evidence_sha256",
    "outcome", "environment", "issued_at", "expires_at", "nonce", "mode",
})

_MATERIAL_FIELDS = frozenset({
    "schema", "mission_identity", "payload_sha256", "model", "scope",
    "acceptance_criteria", "environment", "reviewed_head_sha",
    "operational_digests", "max_transmissions", "cost_ceiling_usd",
    "free_only",
})


def canonical_json(value: Any) -> str:
    return cas.canonical_json(value)


def payload_sha256(payload: Any) -> str:
    return cas.payload_sha256(payload)


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise la.AuthorizationRejected(f"{label}: texto não vazio obrigatório")
    return value.strip()


def _ident(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _ID.fullmatch(value):
        raise la.AuthorizationRejected(f"{label}: identificador inválido")
    return value


def _digest(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _SHA256.fullmatch(value):
        raise la.AuthorizationRejected(f"{label}: sha256 inválido")
    return value


def _head(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _HEAD.fullmatch(value):
        raise la.AuthorizationRejected(f"{label}: HEAD de 40 hex obrigatório")
    return value


def _utc(value: Any, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise la.AuthorizationRejected(
            f"{label}: datetime UTC com fuso obrigatório")
    return value.astimezone(timezone.utc)


def _iso(value: datetime) -> str:
    return _utc(value, "datetime").isoformat().replace("+00:00", "Z")


def _parse_dt(value: Any, label: str) -> datetime:
    if isinstance(value, datetime):
        return _utc(value, label)
    if not isinstance(value, str) or not value.strip():
        raise la.AuthorizationRejected(f"{label}: datetime obrigatório")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise la.AuthorizationRejected(f"{label}: datetime inválido") from exc
    return _utc(parsed, label)


def _cost_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, bool):
        raise la.AuthorizationRejected("cost_ceiling_usd: decimal obrigatório")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise la.AuthorizationRejected("cost_ceiling_usd: decimal inválido") from exc
    if not result.is_finite() or result < 0:
        raise la.AuthorizationRejected(
            "cost_ceiling_usd: decimal não-negativo obrigatório")
    return format(result.normalize(), "f")


def _signature_bytes(value: Any) -> bytes:
    if isinstance(value, str):
        value = value.encode("utf-8")
    if not isinstance(value, (bytes, bytearray)) or not value:
        raise la.AuthorizationRejected("assinatura OpenSSH ausente")
    return bytes(value)


def _exact_mapping(value: Any, expected: frozenset[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise la.AuthorizationRejected(f"{label}: mapping obrigatório")
    keys = set(value)
    missing = expected - keys
    extra = keys - expected
    if missing or extra:
        details = []
        if missing:
            details.append("faltam " + ", ".join(sorted(missing)))
        if extra:
            details.append("extras " + ", ".join(sorted(extra)))
        raise la.AuthorizationRejected(f"{label}: campos inválidos ({'; '.join(details)})")
    return value


def _target_from_mapping(value: Any) -> ca.Recipient:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        raise la.AuthorizationRejected("target: lista de 4 campos obrigatória")
    if any(not isinstance(item, str) or not item for item in value):
        raise la.AuthorizationRejected("target: campos textuais obrigatórios")
    target = ca.Recipient(*value)
    try:
        target.validate()
    except ValueError as exc:
        raise la.AuthorizationRejected(f"target inválido: {exc}") from exc
    return target


def _criteria(value: Any) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)) or not value:
        raise la.AuthorizationRejected(
            "acceptance_criteria: lista não vazia obrigatória")
    result = []
    for item in value:
        result.append(_text(item, "acceptance_criteria[]"))
    return tuple(result)


def _digests(value: Any) -> dict[str, str]:
    if not isinstance(value, Mapping) or not value:
        raise la.AuthorizationRejected("operational_digests: mapping não vazio")
    result = {}
    for name, digest in value.items():
        result[_text(name, "operational_digests.name")] = _digest(
            digest, f"operational_digests.{name}")
    return dict(sorted(result.items()))


@dataclass(frozen=True)
class SignedAuthorizationV2:
    """Envelope V2; inclui integralmente o material do ledger 1.1."""

    schema: str
    authorization_id: str
    approval_reference: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    mission_identity: str
    payload: Mapping[str, Any]
    payload_sha256: str
    target: ca.Recipient
    model: str
    scope: str
    acceptance_criteria: tuple[str, ...]
    environment: str
    reviewed_head_sha: str
    operational_digests: Mapping[str, str]
    max_transmissions: int
    cost_ceiling_usd: Optional[str]
    free_only: bool
    issued_at: datetime
    expires_at: datetime
    nonce: str
    mode: str = "SHADOW"

    def __post_init__(self) -> None:
        object.__setattr__(self, "payload", dict(self.payload))
        object.__setattr__(self, "acceptance_criteria", tuple(self.acceptance_criteria))
        object.__setattr__(self, "operational_digests", dict(self.operational_digests))
        object.__setattr__(self, "cost_ceiling_usd", _cost_text(self.cost_ceiling_usd))

    def validate(self) -> None:
        if self.schema != AUTHORIZATION_SCHEMA:
            raise la.AuthorizationRejected("INSUFFICIENT_ENVELOPE: schema V2 obrigatório")
        if self.mode not in ALLOWED_MODES:
            raise la.AuthorizationRejected("mode: SHADOW obrigatório")
        if self.environment not in ALLOWED_ENVIRONMENTS:
            raise la.AuthorizationRejected(
                "environment: caminho humano aceita somente SHADOW")
        for value, label in (
            (self.authorization_id, "authorization_id"),
            (self.approver_id, "approver_id"),
            (self.mission_id, "mission_id"),
            (self.operation_id, "operation_id"),
            (self.project_id, "project_id"),
            (self.repository_id, "repository_id"),
            (self.coordinator_id, "coordinator_id"),
            (self.scope, "scope"),
            (self.nonce, "nonce"),
        ):
            _ident(value, label)
        _text(self.approval_reference, "approval_reference")
        if not _FINGERPRINT.fullmatch(_text(
                self.approver_key_fingerprint, "approver_key_fingerprint")):
            raise la.AuthorizationRejected("approver_key_fingerprint inválido")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        _digest(self.mission_identity, "mission_identity")
        _digest(self.payload_sha256, "payload_sha256")
        if not isinstance(self.payload, Mapping):
            raise la.AuthorizationRejected("payload: mapping obrigatório")
        if self.payload_sha256 != payload_sha256(self.payload):
            raise la.AuthorizationRejected("payload_sha256 não corresponde ao payload")
        try:
            self.target.validate()
        except ValueError as exc:
            raise la.AuthorizationRejected(f"target inválido: {exc}") from exc
        if not _MODEL.fullmatch(_text(self.model, "model")):
            raise la.AuthorizationRejected("model inválido")
        _head(self.reviewed_head_sha, "reviewed_head_sha")
        _digests(self.operational_digests)
        if self.max_transmissions != 1 or isinstance(self.max_transmissions, bool):
            raise la.AuthorizationRejected("max_transmissions deve ser exatamente 1")
        if not isinstance(self.free_only, bool):
            raise la.AuthorizationRejected("free_only: booleano obrigatório")
        if self.free_only and self.cost_ceiling_usd not in (None, "0"):
            raise la.AuthorizationRejected("free_only exige custo zero/ausente")
        if not self.acceptance_criteria:
            raise la.AuthorizationRejected(
                "acceptance_criteria: ao menos um critério")
        for criterion in self.acceptance_criteria:
            _text(criterion, "acceptance_criteria[]")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise la.AuthorizationRejected("janela de autorização invertida")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": self.schema,
            "authorization_id": self.authorization_id,
            "approval_reference": self.approval_reference,
            "approver_id": self.approver_id,
            "approver_key_fingerprint": self.approver_key_fingerprint,
            "trust_policy_sha256": self.trust_policy_sha256,
            "mission_id": self.mission_id,
            "operation_id": self.operation_id,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "coordinator_id": self.coordinator_id,
            "mission_identity": self.mission_identity,
            "payload": self.payload,
            "payload_sha256": self.payload_sha256,
            "target": list(self.target.key()),
            "model": self.model,
            "scope": self.scope,
            "acceptance_criteria": list(self.acceptance_criteria),
            "environment": self.environment,
            "reviewed_head_sha": self.reviewed_head_sha,
            "operational_digests": dict(self.operational_digests),
            "max_transmissions": self.max_transmissions,
            "cost_ceiling_usd": self.cost_ceiling_usd,
            "free_only": self.free_only,
            "issued_at": _iso(self.issued_at),
            "expires_at": _iso(self.expires_at),
            "nonce": self.nonce,
            "mode": self.mode,
        }

    def to_mapping(self) -> dict[str, Any]:
        return self.canonical_material()

    @classmethod
    def from_mapping(cls, value: Any) -> "SignedAuthorizationV2":
        if not isinstance(value, Mapping):
            raise la.AuthorizationRejected("envelope: mapping obrigatório")
        schema = value.get("schema")
        if schema == cas.SCHEMA_VERSION:
            raise la.AuthorizationRejected(
                "INSUFFICIENT_ENVELOPE: V1 não vincula critérios/ambiente")
        if schema != AUTHORIZATION_SCHEMA:
            raise la.AuthorizationRejected(
                "UNKNOWN_SCHEMA: envelope de autorização não suportado")
        mapping = _exact_mapping(value, _AUTHORIZATION_FIELDS, "envelope")
        try:
            return cls(
                schema=mapping["schema"],
                authorization_id=mapping["authorization_id"],
                approval_reference=mapping["approval_reference"],
                approver_id=mapping["approver_id"],
                approver_key_fingerprint=mapping["approver_key_fingerprint"],
                trust_policy_sha256=mapping["trust_policy_sha256"],
                mission_id=mapping["mission_id"],
                operation_id=mapping["operation_id"],
                project_id=mapping["project_id"],
                repository_id=mapping["repository_id"],
                coordinator_id=mapping["coordinator_id"],
                mission_identity=mapping["mission_identity"],
                payload=mapping["payload"],
                payload_sha256=mapping["payload_sha256"],
                target=_target_from_mapping(mapping["target"]),
                model=mapping["model"],
                scope=mapping["scope"],
                acceptance_criteria=_criteria(mapping["acceptance_criteria"]),
                environment=mapping["environment"],
                reviewed_head_sha=mapping["reviewed_head_sha"],
                operational_digests=_digests(mapping["operational_digests"]),
                max_transmissions=mapping["max_transmissions"],
                cost_ceiling_usd=mapping["cost_ceiling_usd"],
                free_only=mapping["free_only"],
                issued_at=_parse_dt(mapping["issued_at"], "issued_at"),
                expires_at=_parse_dt(mapping["expires_at"], "expires_at"),
                nonce=mapping["nonce"],
                mode=mapping["mode"],
            )
        except la.AuthorizationRejected:
            raise
        except (KeyError, TypeError, ValueError) as exc:
            raise la.AuthorizationRejected(f"envelope inválido: {exc}") from exc


def canonical_authorization_bytes(envelope: SignedAuthorizationV2) -> bytes:
    return AUTHORIZATION_DOMAIN + canonical_json(
        envelope.canonical_material()).encode("utf-8")


def authorization_digest(envelope: SignedAuthorizationV2) -> str:
    return hashlib.sha256(canonical_authorization_bytes(envelope)).hexdigest()


@dataclass(frozen=True)
class SignedReconciliation:
    """Atestação terminal assinada; não substitui receipt/relatório/evidência."""

    schema: str
    authority_id: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    mission_id: str
    authorization_digest: str
    payload_sha256: str
    receipt_sha256: str
    report_sha256: str
    evidence_sha256: str
    outcome: str
    environment: str
    issued_at: datetime
    expires_at: datetime
    nonce: str
    mode: str = "SHADOW"

    def validate(self) -> None:
        try:
            self._validate()
        except la.ReconciliationRejected:
            raise
        except la.AuthorizationRejected as exc:
            raise la.ReconciliationRejected(str(exc)) from exc

    def _validate(self) -> None:
        if self.schema != RECONCILIATION_SCHEMA:
            raise la.ReconciliationRejected(
                "INSUFFICIENT_RECONCILIATION: schema V1 obrigatório")
        if self.mode not in ALLOWED_MODES:
            raise la.ReconciliationRejected("mode: SHADOW obrigatório")
        if self.environment not in ALLOWED_ENVIRONMENTS:
            raise la.ReconciliationRejected(
                "environment: caminho humano aceita somente SHADOW")
        _ident(self.authority_id, "authority_id")
        _ident(self.approver_id, "approver_id")
        if self.authority_id != self.approver_id:
            raise la.ReconciliationRejected(
                "authority_id deve ser igual ao approver_id nesta versão")
        if not _FINGERPRINT.fullmatch(_text(
                self.approver_key_fingerprint, "approver_key_fingerprint")):
            raise la.ReconciliationRejected("approver_key_fingerprint inválido")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        _ident(self.mission_id, "mission_id")
        _digest(self.authorization_digest, "authorization_digest")
        _digest(self.payload_sha256, "payload_sha256")
        _digest(self.receipt_sha256, "receipt_sha256")
        _digest(self.report_sha256, "report_sha256")
        _digest(self.evidence_sha256, "evidence_sha256")
        if self.outcome not in {"COMPLETED", "FAILED"}:
            raise la.ReconciliationRejected("outcome de reconciliação inválido")
        _ident(self.nonce, "nonce")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise la.ReconciliationRejected("janela da atestação invertida")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": self.schema,
            "authority_id": self.authority_id,
            "approver_id": self.approver_id,
            "approver_key_fingerprint": self.approver_key_fingerprint,
            "trust_policy_sha256": self.trust_policy_sha256,
            "mission_id": self.mission_id,
            "authorization_digest": self.authorization_digest,
            "payload_sha256": self.payload_sha256,
            "receipt_sha256": self.receipt_sha256,
            "report_sha256": self.report_sha256,
            "evidence_sha256": self.evidence_sha256,
            "outcome": self.outcome,
            "environment": self.environment,
            "issued_at": _iso(self.issued_at),
            "expires_at": _iso(self.expires_at),
            "nonce": self.nonce,
            "mode": self.mode,
        }

    def to_mapping(self) -> dict[str, Any]:
        return self.canonical_material()

    @classmethod
    def from_mapping(cls, value: Any) -> "SignedReconciliation":
        if not isinstance(value, Mapping):
            raise la.ReconciliationRejected("attestation: mapping obrigatório")
        schema = value.get("schema")
        if schema != RECONCILIATION_SCHEMA:
            raise la.ReconciliationRejected(
                "UNKNOWN_SCHEMA: atestação de reconciliação não suportada")
        try:
            mapping = _exact_mapping(
                value, _RECONCILIATION_FIELDS, "attestation")
            return cls(
                schema=mapping["schema"],
                authority_id=mapping["authority_id"],
                approver_id=mapping["approver_id"],
                approver_key_fingerprint=mapping["approver_key_fingerprint"],
                trust_policy_sha256=mapping["trust_policy_sha256"],
                mission_id=mapping["mission_id"],
                authorization_digest=mapping["authorization_digest"],
                payload_sha256=mapping["payload_sha256"],
                receipt_sha256=mapping["receipt_sha256"],
                report_sha256=mapping["report_sha256"],
                evidence_sha256=mapping["evidence_sha256"],
                outcome=mapping["outcome"],
                environment=mapping["environment"],
                issued_at=_parse_dt(mapping["issued_at"], "issued_at"),
                expires_at=_parse_dt(mapping["expires_at"], "expires_at"),
                nonce=mapping["nonce"],
                mode=mapping["mode"],
            )
        except la.ReconciliationRejected:
            raise
        except la.AuthorizationRejected as exc:
            raise la.ReconciliationRejected(str(exc)) from exc
        except (KeyError, TypeError, ValueError) as exc:
            raise la.ReconciliationRejected(f"atestado inválido: {exc}") from exc


def canonical_reconciliation_bytes(attestation: SignedReconciliation) -> bytes:
    return RECONCILIATION_DOMAIN + canonical_json(
        attestation.canonical_material()).encode("utf-8")


class MissionEnvelopeSource(Protocol):
    """Fonte confiável da MissionEnvelope #64; nunca é fornecida pelo agente."""

    def get(self, mission_id: str) -> cc.MissionEnvelope: ...


class AuthorityAdapter:
    """Implementa AuthorityVerifier e ReconciliationVerifier sobre #77."""

    def __init__(
        self,
        *,
        allowlist: cas.ApproverAllowlist,
        mission_source: MissionEnvelopeSource,
        expected_trust_policy_sha256: Optional[str] = None,
    ) -> None:
        if not isinstance(allowlist, cas.ApproverAllowlist):
            raise ValueError("allowlist inválida")
        allowlist.validate()
        if mission_source is None:
            raise ValueError("mission_source é obrigatório")
        if expected_trust_policy_sha256 is not None:
            _digest(expected_trust_policy_sha256, "expected_trust_policy_sha256")
        self.allowlist = allowlist
        self.mission_source = mission_source
        self.expected_trust_policy_sha256 = expected_trust_policy_sha256

    def _trust_policy(self) -> str:
        digest = self.allowlist.digest()
        if (self.expected_trust_policy_sha256 is not None
                and self.expected_trust_policy_sha256 != digest):
            raise la.AuthorizationRejected(
                "TRUST_POLICY_MISMATCH: allowlist atual diverge do pin")
        return digest

    def _active_approver(self, approver_id: str, fingerprint: str,
                         trust_policy_sha256: str, now: datetime) -> cas.ApproverKey:
        expected_trust = self._trust_policy()
        if trust_policy_sha256 != expected_trust:
            raise la.AuthorizationRejected(
                "TRUST_POLICY_MISMATCH: envelope não vincula a allowlist atual")
        approver = self.allowlist.find(approver_id, fingerprint)
        if approver is None:
            raise la.AuthorizationRejected(
                "APPROVER_KEY_UNKNOWN: chave do aprovador ausente da allowlist")
        try:
            actual = cas.public_key_fingerprint(approver.public_key)
        except (OSError, ValueError, subprocess.SubprocessError) as exc:
            # Falha local do OpenSSH/NSS não é rejeição da chave: o fingerprint
            # sequer pôde ser calculado. O prefixo permite diagnóstico seguro
            # sem expor a mensagem crua ao consumidor.
            raise la.AuthorizationRejected(
                "CRYPTO_INFRASTRUCTURE: fingerprint indisponível") from exc
        if actual != approver.fingerprint or actual != fingerprint:
            raise la.AuthorizationRejected(
                "FINGERPRINT_MISMATCH: fingerprint não corresponde à chave")
        if not approver.active_at(now):
            raise la.AuthorizationRejected(
                "APPROVER_KEY_INACTIVE: chave expirada/revogada/futura")
        return approver

    def _resolve_mission(self, mission_id: str) -> cc.MissionEnvelope:
        try:
            mission = self.mission_source.get(mission_id)
        except Exception as exc:  # noqa: BLE001
            raise la.AuthorizationRejected(
                f"MISSION_SOURCE_ERROR: {type(exc).__name__}") from exc
        if not isinstance(mission, cc.MissionEnvelope):
            raise la.AuthorizationRejected(
                "MISSION_SOURCE_ERROR: MissionEnvelope #64 obrigatória")
        try:
            mission.validate()
            identity = cc.canonical_mission_identity(mission)
        except (cc.ContractError, TypeError, ValueError) as exc:
            raise la.AuthorizationRejected(
                f"MISSION_IDENTITY_INVALID: {exc}") from exc
        _digest(identity, "mission_identity")
        return mission

    @staticmethod
    def _mission_mismatches(mission: cc.MissionEnvelope,
                            envelope: SignedAuthorizationV2) -> tuple[str, ...]:
        target = mission.target
        comparisons = (
            ("mission_id", mission.mission_id, envelope.mission_id),
            ("operation_id", mission.operation_id, envelope.operation_id),
            ("project_id", mission.project_id, envelope.project_id),
            ("repository_id", mission.repository_id, envelope.repository_id),
            ("coordinator_id", mission.coordinator_id, envelope.coordinator_id),
            ("authorization_reference", mission.authorization_reference,
             envelope.approval_reference),
            ("payload_sha256", mission.payload_sha256, envelope.payload_sha256),
            ("payload", canonical_json(mission.payload), canonical_json(envelope.payload)),
            ("target_agent", target.agent_id, envelope.target.agent_id),
            ("target_session", target.session_id, envelope.target.session_id),
            ("target_instance", target.instance_id, envelope.target.instance_id),
            ("target_location", target.location, envelope.target.worktree),
            ("scope", mission.scope, envelope.scope),
            ("acceptance_criteria", list(mission.acceptance_criteria),
             list(envelope.acceptance_criteria)),
            ("reviewed_head_sha", mission.expected_head_sha, envelope.reviewed_head_sha),
        )
        return tuple(name for name, expected, actual in comparisons if expected != actual)

    @staticmethod
    def _expected_mismatches(expected: la.ExpectedOperation,
                             envelope: SignedAuthorizationV2,
                             identity: str) -> tuple[str, ...]:
        expected.validate()
        comparisons = (
            ("mission_id", expected.mission_id, envelope.mission_id),
            ("operation_id", expected.operation_id, envelope.operation_id),
            ("project_id", expected.project_id, envelope.project_id),
            ("repository_id", expected.repository_id, envelope.repository_id),
            ("coordinator_id", expected.coordinator_id, envelope.coordinator_id),
            ("mission_identity", expected.mission_identity, envelope.mission_identity),
            ("mission_identity_canonical", identity, envelope.mission_identity),
            ("payload", canonical_json(expected.payload), canonical_json(envelope.payload)),
            ("payload_sha256", la.payload_sha256(expected.payload),
             envelope.payload_sha256),
            ("target_key", tuple(expected.target_key), envelope.target.key()),
        )
        return tuple(name for name, left, right in comparisons if left != right)

    @staticmethod
    def _material_mismatches(expected: la.ExpectedOperation,
                             envelope: SignedAuthorizationV2,
                             identity: str) -> tuple[str, ...]:
        material = expected.material
        if not isinstance(material, Mapping):
            return ("material",)
        if set(material) != _MATERIAL_FIELDS:
            return ("material_fields",)
        criteria = material.get("acceptance_criteria")
        digests = material.get("operational_digests")
        if not isinstance(criteria, (list, tuple)):
            return ("material.acceptance_criteria",)
        if not isinstance(digests, Mapping):
            return ("material.operational_digests",)
        comparisons = (
            ("material.schema", material.get("schema"), la.AUTHORITY_MATERIAL_SCHEMA),
            ("material.mission_identity", material.get("mission_identity"), identity),
            ("material.payload_sha256", material.get("payload_sha256"),
             envelope.payload_sha256),
            ("material.model", material.get("model"), envelope.model),
            ("material.scope", material.get("scope"), envelope.scope),
            ("material.acceptance_criteria", list(criteria),
             list(envelope.acceptance_criteria)),
            ("material.environment", material.get("environment"), envelope.environment),
            ("material.reviewed_head_sha", material.get("reviewed_head_sha"),
             envelope.reviewed_head_sha),
            ("material.operational_digests",
             dict(sorted(digests.items())),
             dict(sorted(envelope.operational_digests.items()))),
            ("material.max_transmissions", material.get("max_transmissions"),
             envelope.max_transmissions),
            ("material.cost_ceiling_usd", material.get("cost_ceiling_usd"),
             envelope.cost_ceiling_usd),
            ("material.free_only", material.get("free_only"), envelope.free_only),
        )
        return tuple(name for name, left, right in comparisons if left != right)

    def verify_authorization(
        self,
        *,
        envelope: Mapping[str, Any],
        signature: bytes,
        payload: Mapping[str, Any],
        expected: la.ExpectedOperation,
        now: datetime,
    ) -> la.VerifiedAuthorization:
        now = _utc(now, "now")
        parsed = (envelope if isinstance(envelope, SignedAuthorizationV2)
                  else SignedAuthorizationV2.from_mapping(envelope))
        parsed.validate()
        try:
            expected.validate()
        except la.AuthorityContractError as exc:
            raise la.AuthorizationRejected(str(exc)) from exc
        if canonical_json(payload) != canonical_json(parsed.payload):
            raise la.AuthorizationRejected("OPERATION_MISMATCH: payload divergente")
        mission = self._resolve_mission(expected.mission_id)
        identity = cc.canonical_mission_identity(mission)
        if identity != expected.mission_identity:
            raise la.AuthorizationRejected(
                "MISSION_IDENTITY_MISMATCH: identidade esperada não é canônica")
        mission_mismatches = self._mission_mismatches(mission, parsed)
        if mission_mismatches:
            raise la.AuthorizationRejected(
                "MISSION_MATERIAL_MISMATCH: " + ", ".join(mission_mismatches))
        expected_mismatches = self._expected_mismatches(expected, parsed, identity)
        if expected_mismatches:
            raise la.AuthorizationRejected(
                "OPERATION_MISMATCH: " + ", ".join(expected_mismatches))
        material_mismatches = self._material_mismatches(expected, parsed, identity)
        if material_mismatches:
            raise la.AuthorizationRejected(
                "OPERATION_MISMATCH: " + ", ".join(material_mismatches))
        approver = self._active_approver(
            parsed.approver_id, parsed.approver_key_fingerprint,
            parsed.trust_policy_sha256, now)
        issued = _utc(parsed.issued_at, "issued_at")
        expires = _utc(parsed.expires_at, "expires_at")
        if now < issued:
            raise la.AuthorizationRejected(
                "AUTHORIZATION_NOT_YET_VALID: autorização ainda não válida")
        if now >= expires:
            raise la.AuthorizationRejected(
                "AUTHORIZATION_EXPIRED: autorização expirada")
        signature_bytes = _signature_bytes(signature)
        verified, reason = cas.verify_openssh_signature(
            data=canonical_authorization_bytes(parsed),
            signature=signature_bytes, approver=approver,
            namespace=AUTHORIZATION_NAMESPACE)
        if not verified:
            raise la.AuthorizationRejected(f"SIGNATURE_INVALID: {reason}")
        return la.VerifiedAuthorization(
            authorization_digest=authorization_digest(parsed),
            authorization_id=parsed.authorization_id,
            approval_reference=parsed.approval_reference,
            mission_id=parsed.mission_id,
            mission_identity=identity,
            authority_material_sha256=hashlib.sha256(
                canonical_json(expected.material).encode("utf-8")).hexdigest(),
            approver_id=parsed.approver_id,
            approver_key_fingerprint=parsed.approver_key_fingerprint,
            trust_policy_sha256=parsed.trust_policy_sha256,
            environment=parsed.environment,
            issued_at=issued,
            expires_at=expires,
            nonce=parsed.nonce,
            signature=signature_bytes,
        )

    def verify_revocation(
        self,
        *,
        statement: Mapping[str, Any],
        signature: bytes,
        now: datetime,
    ) -> la.VerifiedRevocation:
        try:
            return self._verify_revocation(
                statement=statement, signature=signature, now=now)
        except la.RevocationRejected:
            raise
        except la.AuthorizationRejected as exc:
            raise la.RevocationRejected(str(exc)) from exc

    def _verify_revocation(
        self,
        *,
        statement: Mapping[str, Any],
        signature: bytes,
        now: datetime,
    ) -> la.VerifiedRevocation:
        now = _utc(now, "now")
        _exact_mapping(statement, frozenset({
            "schema", "authorization_digest", "approver_id",
            "approver_key_fingerprint", "trust_policy_sha256", "revoked_at",
            "reason", "nonce",
        }), "statement")
        try:
            parsed = cas.SignedRevocation(
                schema=statement["schema"],
                authorization_digest=statement["authorization_digest"],
                approver_id=statement["approver_id"],
                approver_key_fingerprint=statement["approver_key_fingerprint"],
                trust_policy_sha256=statement["trust_policy_sha256"],
                revoked_at=_parse_dt(statement["revoked_at"], "revoked_at"),
                reason=statement["reason"],
                nonce=statement["nonce"],
            )
            parsed.validate()
        except la.ReconciliationRejected as exc:
            raise la.RevocationRejected(str(exc)) from exc
        except (KeyError, TypeError, ValueError) as exc:
            raise la.RevocationRejected(f"INVALID_REVOCATION: {exc}") from exc
        result = cas.verify_signed_revocation(
            parsed, signature, self.allowlist, now=now)
        if not result.verified:
            raise la.RevocationRejected(f"{result.code}: {result.detail}")
        expected_trust = self._trust_policy()
        if parsed.trust_policy_sha256 != expected_trust:
            raise la.RevocationRejected(
                "TRUST_POLICY_MISMATCH: allowlist atual divergente")
        return la.VerifiedRevocation(
            authorization_digest=parsed.authorization_digest,
            approver_id=parsed.approver_id,
            approver_key_fingerprint=parsed.approver_key_fingerprint,
            trust_policy_sha256=parsed.trust_policy_sha256,
            revoked_at=_utc(parsed.revoked_at, "revoked_at"),
            reason=parsed.reason,
            nonce=parsed.nonce,
            signature=_signature_bytes(signature),
        )

    def verify_reconciliation(
        self,
        *,
        attestation: Mapping[str, Any],
        signature: bytes,
        expected: la.ExpectedReconciliation,
        now: datetime,
    ) -> la.VerifiedReconciliation:
        try:
            return self._verify_reconciliation(
                attestation=attestation, signature=signature,
                expected=expected, now=now)
        except la.ReconciliationRejected:
            raise
        except la.AuthorizationRejected as exc:
            raise la.ReconciliationRejected(str(exc)) from exc

    def _verify_reconciliation(
        self,
        *,
        attestation: Mapping[str, Any],
        signature: bytes,
        expected: la.ExpectedReconciliation,
        now: datetime,
    ) -> la.VerifiedReconciliation:
        now = _utc(now, "now")
        parsed = (attestation if isinstance(attestation, SignedReconciliation)
                  else SignedReconciliation.from_mapping(attestation))
        parsed.validate()
        try:
            expected.validate()
        except la.AuthorityContractError as exc:
            raise la.ReconciliationRejected(str(exc)) from exc
        comparisons = (
            ("mission_id", expected.mission_id, parsed.mission_id),
            ("authorization_digest", expected.authorization_digest,
             parsed.authorization_digest),
            ("payload_sha256", expected.payload_sha256, parsed.payload_sha256),
            ("receipt_sha256", expected.receipt_sha256, parsed.receipt_sha256),
            ("report_sha256", expected.report_sha256, parsed.report_sha256),
            ("evidence_sha256", expected.evidence_sha256, parsed.evidence_sha256),
            ("outcome", expected.outcome, parsed.outcome),
        )
        mismatches = tuple(name for name, left, right in comparisons if left != right)
        if mismatches:
            raise la.ReconciliationRejected(
                "RECONCILIATION_MISMATCH: " + ", ".join(mismatches))
        approver = self._active_approver(
            parsed.approver_id, parsed.approver_key_fingerprint,
            parsed.trust_policy_sha256, now)
        issued = _utc(parsed.issued_at, "issued_at")
        expires = _utc(parsed.expires_at, "expires_at")
        if now < issued:
            raise la.ReconciliationRejected(
                "RECONCILIATION_NOT_YET_VALID: atestação ainda não válida")
        if now >= expires:
            raise la.ReconciliationRejected(
                "RECONCILIATION_EXPIRED: atestação expirada")
        signature_bytes = _signature_bytes(signature)
        verified, reason = cas.verify_openssh_signature(
            data=canonical_reconciliation_bytes(parsed),
            signature=signature_bytes, approver=approver,
            namespace=RECONCILIATION_NAMESPACE)
        if not verified:
            raise la.ReconciliationRejected(f"SIGNATURE_INVALID: {reason}")
        return la.VerifiedReconciliation(
            authority_id=parsed.authority_id,
            mission_id=parsed.mission_id,
            authorization_digest=parsed.authorization_digest,
            payload_sha256=parsed.payload_sha256,
            receipt_sha256=parsed.receipt_sha256,
            report_sha256=parsed.report_sha256,
            evidence_sha256=parsed.evidence_sha256,
            outcome=parsed.outcome,
            issued_at=issued,
            expires_at=expires,
            nonce=parsed.nonce,
            signature=signature_bytes,
        )


def snapshot_from_envelope(
    mission: cc.MissionEnvelope,
    envelope: SignedAuthorizationV2 | Mapping[str, Any],
) -> la.MissionSnapshot:
    """Constrói o snapshot do ledger 1.1 a partir da missão #64 + envelope V2.

    Não verifica assinatura; o chamador protegido deve verificar antes. A
    identidade é recalculada e todos os campos materiais são comparados.
    """
    if not isinstance(mission, cc.MissionEnvelope):
        raise la.AuthorizationRejected("MISSION_SOURCE_ERROR: MissionEnvelope #64 obrigatória")
    parsed = (envelope if isinstance(envelope, SignedAuthorizationV2)
              else SignedAuthorizationV2.from_mapping(envelope))
    mission.validate()
    parsed.validate()
    identity = cc.canonical_mission_identity(mission)
    mismatches = AuthorityAdapter._mission_mismatches(mission, parsed)
    if mismatches:
        raise la.AuthorizationRejected(
            "MISSION_MATERIAL_MISMATCH: " + ", ".join(mismatches))
    if parsed.mission_identity != identity:
        raise la.AuthorizationRejected(
            "MISSION_IDENTITY_MISMATCH: envelope não vincula identidade canônica")
    if mission.authorization_reference != parsed.approval_reference:
        raise la.AuthorizationRejected(
            "OPERATION_MISMATCH: authorization_reference divergente")
    snapshot = la.MissionSnapshot(
        mission_id=mission.mission_id,
        operation_id=mission.operation_id,
        project_id=mission.project_id,
        repository_id=mission.repository_id,
        coordinator_id=mission.coordinator_id,
        agent=mission.target.agent_id,
        session_id=mission.target.session_id,
        instance_id=mission.target.instance_id,
        worktree=mission.target.location,
        mission_identity=identity,
        payload=dict(mission.payload),
        model=parsed.model,
        scope=mission.scope,
        acceptance_criteria=tuple(mission.acceptance_criteria),
        environment=parsed.environment,
        reviewed_head_sha=parsed.reviewed_head_sha,
        operational_digests=dict(parsed.operational_digests),
        max_transmissions=parsed.max_transmissions,
        cost_ceiling_usd=parsed.cost_ceiling_usd,
        free_only=parsed.free_only,
    )
    snapshot.validate()
    return snapshot
