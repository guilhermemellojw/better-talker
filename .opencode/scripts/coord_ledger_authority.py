#!/usr/bin/env python3
"""Contrato neutro entre o ledger 1.1 e um verificador de autorização.

Este módulo NÃO verifica criptografia, não assina, não lê chaves e não conhece
#72/#77. Ele define os tipos que o adaptador P14B deve preencher com o resultado
do verificador real. O ledger persiste e compara esse material; a autenticidade
continua fora do ledger.

`MissionSnapshot` é o material canônico da missão: payload autorizado, alvo,
identidade, modelo, critérios de aceitação, ambiente e limites. O snapshot é
persistido no ledger e vinculado por SHA-256; cache externo não é autoridade.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping, Optional, Protocol

AUTHORIZATION_MODE_LEGACY = "LEGACY_UNVERIFIED"
AUTHORIZATION_MODE_SIGNED = "SIGNED_V1"
SNAPSHOT_SCHEMA = "BT-COORD-MISSION-SNAPSHOT-V1"
AUTHORITY_MATERIAL_SCHEMA = "BT-COORD-AUTHORITY-MATERIAL-V1"
ALLOWED_ENVIRONMENTS = frozenset({"FIXTURE", "SYNTHETIC", "SHADOW"})

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")
_MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*(?:#[A-Za-z0-9._-]+)?$")
_SESSION = re.compile(r"^ses[-_][A-Za-z0-9._:-]+$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_HEAD = re.compile(r"^[0-9a-f]{40}$")


class AuthorityContractError(ValueError):
    """Material de autoridade inválido ou divergente."""


class AuthorizationRejected(AuthorityContractError):
    """O verificador rejeitou a autorização; nenhuma transição é permitida."""


class RevocationRejected(AuthorityContractError):
    """O verificador rejeitou a revogação; nenhuma transição é permitida."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def payload_sha256(payload: Any) -> str:
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise AuthorityContractError(f"{label}: texto não vazio obrigatório")
    return value.strip()


def _ident(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _ID.fullmatch(value):
        raise AuthorityContractError(f"{label}: identificador inválido")
    return value


def _digest(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _SHA256.fullmatch(value):
        raise AuthorityContractError(f"{label}: sha256 inválido")
    return value


def _head(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _HEAD.fullmatch(value):
        raise AuthorityContractError(f"{label}: HEAD de 40 hex obrigatório")
    return value


def _utc(value: Any, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise AuthorityContractError(f"{label}: datetime UTC com fuso obrigatório")
    return value.astimezone(timezone.utc)


def _cost_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, bool):
        raise AuthorityContractError("cost_ceiling_usd: decimal obrigatório")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise AuthorityContractError("cost_ceiling_usd: decimal inválido") from exc
    if not result.is_finite() or result < 0:
        raise AuthorityContractError("cost_ceiling_usd: decimal não-negativo obrigatório")
    return format(result.normalize(), "f")


@dataclass(frozen=True)
class MissionSnapshot:
    """Material canônico e imutável da missão autorizada.

    O snapshot inclui o payload integral; o banco do ledger é privado (0600).
    `environment` é limitado a fixture/sintético/SHADOW nesta rodada.
    """

    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    agent: str
    session_id: str
    instance_id: str
    worktree: str
    mission_identity: str
    payload: Mapping[str, Any]
    model: str
    scope: str
    acceptance_criteria: tuple[str, ...]
    environment: str
    reviewed_head_sha: str
    operational_digests: Mapping[str, str]
    max_transmissions: int
    cost_ceiling_usd: Optional[str]
    free_only: bool

    def __post_init__(self) -> None:
        object.__setattr__(self, "payload", dict(self.payload))
        object.__setattr__(self, "acceptance_criteria", tuple(self.acceptance_criteria))
        object.__setattr__(self, "operational_digests", dict(self.operational_digests))
        object.__setattr__(self, "cost_ceiling_usd", _cost_text(self.cost_ceiling_usd))

    @property
    def payload_sha256(self) -> str:
        return payload_sha256(self.payload)

    @property
    def target_key(self) -> tuple[str, str, str, str]:
        return (self.agent, self.session_id, self.worktree, self.instance_id)

    def validate(self) -> None:
        for value, label in (
            (self.mission_id, "mission_id"),
            (self.operation_id, "operation_id"),
            (self.project_id, "project_id"),
            (self.repository_id, "repository_id"),
            (self.coordinator_id, "coordinator_id"),
            (self.agent, "agent"),
            (self.instance_id, "instance_id"),
            (self.scope, "scope"),
        ):
            _ident(value, label)
        if not _SESSION.fullmatch(self.session_id):
            raise AuthorityContractError("session_id: formato ses-* obrigatório")
        worktree = _text(self.worktree, "worktree")
        if not worktree.startswith("/"):
            raise AuthorityContractError("worktree: caminho absoluto obrigatório")
        _digest(self.mission_identity, "mission_identity")
        if not isinstance(self.payload, Mapping):
            raise AuthorityContractError("payload: mapping obrigatório")
        _digest(self.payload_sha256, "payload_sha256")
        if not _MODEL.fullmatch(_text(self.model, "model")):
            raise AuthorityContractError("model: identidade canônica inválida")
        if not self.acceptance_criteria:
            raise AuthorityContractError("acceptance_criteria: ao menos um critério")
        for criterion in self.acceptance_criteria:
            _text(criterion, "acceptance_criteria[]")
        if self.environment not in ALLOWED_ENVIRONMENTS:
            raise AuthorityContractError(
                "environment: apenas FIXTURE/SYNTHETIC/SHADOW nesta rodada")
        _head(self.reviewed_head_sha, "reviewed_head_sha")
        if not isinstance(self.operational_digests, Mapping):
            raise AuthorityContractError("operational_digests: mapping obrigatório")
        for name, digest in self.operational_digests.items():
            _text(name, "operational_digests.name")
            _digest(digest, f"operational_digests.{name}")
        if not isinstance(self.max_transmissions, int) or isinstance(
                self.max_transmissions, bool) or self.max_transmissions != 1:
            raise AuthorityContractError("max_transmissions: piloto exige exatamente 1")
        cost = self.cost_ceiling_usd
        if not isinstance(self.free_only, bool):
            raise AuthorityContractError("free_only: booleano obrigatório")
        if self.free_only and cost not in (None, "0"):
            raise AuthorityContractError("free_only exige custo zero/ausente")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": SNAPSHOT_SCHEMA,
            "mission_id": self.mission_id,
            "operation_id": self.operation_id,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "coordinator_id": self.coordinator_id,
            "target": list(self.target_key),
            "mission_identity": self.mission_identity,
            "payload": self.payload,
            "payload_sha256": self.payload_sha256,
            "model": self.model,
            "scope": self.scope,
            "acceptance_criteria": list(self.acceptance_criteria),
            "environment": self.environment,
            "reviewed_head_sha": self.reviewed_head_sha,
            "operational_digests": dict(sorted(self.operational_digests.items())),
            "max_transmissions": self.max_transmissions,
            "cost_ceiling_usd": self.cost_ceiling_usd,
            "free_only": self.free_only,
        }

    def sha256(self) -> str:
        return hashlib.sha256(canonical_json(self.canonical_material()).encode("utf-8")).hexdigest()

    def authority_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": AUTHORITY_MATERIAL_SCHEMA,
            "mission_identity": self.mission_identity,
            "model": self.model,
            "scope": self.scope,
            "reviewed_head_sha": self.reviewed_head_sha,
            "operational_digests": dict(sorted(self.operational_digests.items())),
            "max_transmissions": self.max_transmissions,
            "cost_ceiling_usd": self.cost_ceiling_usd,
            "free_only": self.free_only,
        }

    def authority_material_sha256(self) -> str:
        return hashlib.sha256(
            canonical_json(self.authority_material()).encode("utf-8")).hexdigest()

    def expected_operation(self) -> "ExpectedOperation":
        self.validate()
        return ExpectedOperation(
            mission_id=self.mission_id,
            operation_id=self.operation_id,
            project_id=self.project_id,
            repository_id=self.repository_id,
            coordinator_id=self.coordinator_id,
            mission_identity=self.mission_identity,
            payload=self.payload,
            target_key=self.target_key,
            material=self.authority_material(),
        )


@dataclass(frozen=True)
class ExpectedOperation:
    """Material que o verificador real deve comparar com o envelope assinado."""

    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    mission_identity: str
    payload: Mapping[str, Any]
    target_key: tuple[str, str, str, str]
    material: Mapping[str, Any]

    def validate(self) -> None:
        for value, label in (
            (self.mission_id, "mission_id"),
            (self.operation_id, "operation_id"),
            (self.project_id, "project_id"),
            (self.repository_id, "repository_id"),
            (self.coordinator_id, "coordinator_id"),
        ):
            _ident(value, label)
        _digest(self.mission_identity, "mission_identity")
        if not isinstance(self.payload, Mapping):
            raise AuthorityContractError("payload: mapping obrigatório")
        if not isinstance(self.material, Mapping) or not self.material:
            raise AuthorityContractError("material: mapping não vazio obrigatório")
        if len(self.target_key) != 4 or not all(isinstance(v, str) and v for v in self.target_key):
            raise AuthorityContractError("target_key inválido")


@dataclass(frozen=True)
class VerifiedAuthorization:
    """Resultado tipado de um verificador real; nunca um booleano do chamador."""

    authorization_digest: str
    authorization_id: str
    approval_reference: str
    mission_id: str
    mission_identity: str
    authority_material_sha256: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    environment: str
    issued_at: datetime
    expires_at: datetime
    nonce: str
    signature: bytes

    def validate(self) -> None:
        _digest(self.authorization_digest, "authorization_digest")
        _ident(self.authorization_id, "authorization_id")
        _text(self.approval_reference, "approval_reference")
        _ident(self.mission_id, "mission_id")
        _digest(self.mission_identity, "mission_identity")
        _digest(self.authority_material_sha256, "authority_material_sha256")
        _ident(self.approver_id, "approver_id")
        _text(self.approver_key_fingerprint, "approver_key_fingerprint")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        if self.environment not in ALLOWED_ENVIRONMENTS:
            raise AuthorityContractError("environment do verificador inválido")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise AuthorityContractError("janela da autorização invertida")
        _ident(self.nonce, "nonce")
        if not isinstance(self.signature, (bytes, bytearray)) or not self.signature:
            raise AuthorityContractError("assinatura verificada ausente")


@dataclass(frozen=True)
class VerifiedRevocation:
    authorization_digest: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    revoked_at: datetime
    reason: str
    nonce: str
    signature: bytes

    def validate(self) -> None:
        _digest(self.authorization_digest, "authorization_digest")
        _ident(self.approver_id, "approver_id")
        _text(self.approver_key_fingerprint, "approver_key_fingerprint")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        _utc(self.revoked_at, "revoked_at")
        _text(self.reason, "reason")
        _ident(self.nonce, "nonce")
        if not isinstance(self.signature, (bytes, bytearray)) or not self.signature:
            raise AuthorityContractError("assinatura da revogação ausente")


class AuthorityVerifier(Protocol):
    """Porta injetada no processo protegido; o agente não a fornece por chamada."""

    def verify_authorization(
        self,
        *,
        envelope: Any,
        signature: bytes,
        payload: Mapping[str, Any],
        expected: ExpectedOperation,
        now: datetime,
    ) -> VerifiedAuthorization: ...

    def verify_revocation(
        self,
        *,
        statement: Any,
        signature: bytes,
        now: datetime,
    ) -> VerifiedRevocation: ...
