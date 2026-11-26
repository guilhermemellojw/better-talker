#!/usr/bin/env python3
"""COORD-06A — validador OFFLINE de elegibilidade de autorização/revogação.

Componente PURO: não concede autoridade, não despacha, não transmite, não
altera sessões, não lê credenciais e não executa operação externa. Toda a
autoridade vem de um `TrustContract` injetado pelo chamador; este módulo
apenas compara evidência externa fornecida com pinned digests e produz uma
decisão determinística com bloqueios e evidências necessárias.

NÃO é ledger, NÃO é PilotApproval e NÃO implementa delegação/faturamento/
revogação distribuída. Ver `.opencode/AUTHORITY.md`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from typing import Iterable, Mapping, Optional

__all__ = [
    "Mode", "OperationState", "AuthoritySource", "Recipient", "TrustContract",
    "Mission", "ExternalAuthorityEvidence", "Authorization", "Revocation",
    "Evaluation", "Blocker", "Decision", "evaluate",
]

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class Mode(str, Enum):
    SHADOW = "SHADOW"
    ASSISTED = "ASSISTED"
    DELEGATED = "DELEGATED"


class OperationState(str, Enum):
    NOT_DISPATCHED = "NOT_DISPATCHED"
    TRANSMITTED = "TRANSMITTED"
    RUNNING = "RUNNING"
    UNKNOWN = "UNKNOWN"
    TERMINAL = "TERMINAL"


class AuthoritySource(str, Enum):
    EXTERNAL_ARTIFACT = "EXTERNAL_ARTIFACT"
    SELF_REPORT = "SELF_REPORT"
    BOOLEAN = "BOOLEAN"
    NONE = "NONE"


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} ausente")
    return value.strip()


def _ident(value: object, label: str) -> str:
    value = _text(value, label)
    if not _ID.fullmatch(value):
        raise ValueError(f"{label} inválido")
    return value


def _digest(value: object, label: str) -> str:
    value = _text(value, label)
    if not _SHA256.fullmatch(value):
        raise ValueError(f"{label} inválido")
    return value


def _utc(value: object, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError(f"{label} deve ser datetime UTC com fuso")
    return value.astimezone(timezone.utc)


def _frozen(values: Iterable[str], label: str) -> frozenset[str]:
    if isinstance(values, (str, bytes)):
        raise ValueError(f"{label} deve ser coleção")
    return frozenset(_ident(v, label) for v in values)


def _heads(values: Iterable[str]) -> frozenset[str]:
    if isinstance(values, (str, bytes)):
        raise ValueError("authorized_heads deve ser coleção")
    result = set()
    for value in values:
        value = _text(value, "authorized_head")
        if not re.fullmatch(r"[0-9a-f]{40}", value):
            raise ValueError("authorized_head inválido")
        result.add(value)
    return frozenset(result)


@dataclass(frozen=True)
class Recipient:
    agent_id: str
    session_id: str
    worktree: str
    instance_id: str

    def validate(self) -> None:
        _ident(self.agent_id, "agent_id")
        _ident(self.session_id, "session_id")
        _text(self.worktree, "worktree")
        _ident(self.instance_id, "instance_id")

    def key(self) -> tuple[str, str, str, str]:
        return (self.agent_id, self.session_id, self.worktree, self.instance_id)


@dataclass(frozen=True)
class TrustContract:
    """Âncora de confiança EXPLÍCITA fornecida pelo chamador.

    `authority_pins` mapeia uma referência de aprovação externa ao digest
    SHA-256 do artefato que a coordenação registrou. O validador nunca gera,
    assina ou presume autoridade: apenas compara o que foi injetado.
    """

    project_id: str
    repository_id: str
    mode: Mode
    authorized_heads: frozenset[str]
    allowed_models: frozenset[str]
    allowed_scopes: frozenset[str]
    allowed_agents: frozenset[str]
    max_transmissions: int
    authority_pins: Mapping[str, str]

    def validate(self) -> None:
        _ident(self.project_id, "project_id")
        _ident(self.repository_id, "repository_id")
        if not isinstance(self.mode, Mode):
            raise ValueError("mode inválido")
        _heads(self.authorized_heads)
        _frozen(self.allowed_models, "allowed_models")
        _frozen(self.allowed_scopes, "allowed_scopes")
        _frozen(self.allowed_agents, "allowed_agents")
        if not isinstance(self.max_transmissions, int) or self.max_transmissions < 1:
            raise ValueError("max_transmissions inválido")
        if not isinstance(self.authority_pins, Mapping) or not self.authority_pins:
            raise ValueError("authority_pins ausentes")
        for reference, digest in sorted(self.authority_pins.items()):
            _ident(reference, "authority_reference")
            _digest(digest, "authority_pin")


@dataclass(frozen=True)
class Mission:
    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    mission_identity: str
    payload_sha256: str
    recipient: Recipient
    head_sha: str
    scope: str
    model: str
    transmissions: int

    def validate(self) -> None:
        for value, label in (
            (self.mission_id, "mission_id"), (self.operation_id, "operation_id"),
            (self.project_id, "project_id"), (self.repository_id, "repository_id"),
            (self.mission_identity, "mission_identity"), (self.scope, "scope"),
            (self.model, "model"),
        ):
            _ident(value, label)
        _digest(self.payload_sha256, "payload_sha256")
        _heads([self.head_sha])
        self.recipient.validate()
        if not isinstance(self.transmissions, int) or self.transmissions < 0:
            raise ValueError("transmissions inválido")


@dataclass(frozen=True)
class ExternalAuthorityEvidence:
    """Evidência externa de autoridade — nunca um bool ou auto-relato."""

    reference: str
    digest_sha256: str
    source: AuthoritySource
    approver_id: str
    observed_at: datetime

    def validate(self) -> None:
        _ident(self.reference, "evidence_reference")
        _digest(self.digest_sha256, "evidence_digest")
        _ident(self.approver_id, "approver_id")
        if not isinstance(self.source, AuthoritySource):
            raise ValueError("source inválido")
        _utc(self.observed_at, "observed_at")


@dataclass(frozen=True)
class Authorization:
    reference: str
    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    mission_identity: str
    payload_sha256: str
    recipient: Recipient
    head_sha: str
    scope: str
    model: str
    max_transmissions: int
    issued_at: datetime
    expires_at: datetime
    approval_reference: str
    # Aceito apenas para demonstrar que é IGNORADO: nunca prova aprovação humana.
    self_declared_approved: bool = False
    consumed: bool = False

    def validate(self) -> None:
        for value, label in (
            (self.reference, "authorization_reference"),
            (self.mission_id, "mission_id"), (self.operation_id, "operation_id"),
            (self.project_id, "project_id"), (self.repository_id, "repository_id"),
            (self.mission_identity, "mission_identity"), (self.scope, "scope"),
            (self.model, "model"), (self.approval_reference, "approval_reference"),
        ):
            _ident(value, label)
        _digest(self.payload_sha256, "payload_sha256")
        _heads([self.head_sha])
        self.recipient.validate()
        if not isinstance(self.max_transmissions, int) or self.max_transmissions < 1:
            raise ValueError("max_transmissions inválido")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise ValueError("janela de validade invertida")
        if not isinstance(self.consumed, bool) or not isinstance(self.self_declared_approved, bool):
            raise ValueError("flags booleanas inválidas")


@dataclass(frozen=True)
class Revocation:
    authorization_reference: str
    authority_reference: str
    digest_sha256: str
    revoked_at: datetime
    reason: str

    def validate(self) -> None:
        _ident(self.authorization_reference, "authorization_reference")
        _ident(self.authority_reference, "authority_reference")
        _digest(self.digest_sha256, "revocation_digest")
        _utc(self.revoked_at, "revoked_at")
        _text(self.reason, "reason")


@dataclass(frozen=True)
class Evaluation:
    mission: Mission
    authorization: Optional[Authorization]
    revocations: tuple[Revocation, ...]
    trust: TrustContract
    evidence: Optional[ExternalAuthorityEvidence]
    evaluated_at: datetime
    operation_state: OperationState


@dataclass(frozen=True, order=True)
class Blocker:
    code: str
    detail: str


@dataclass(frozen=True)
class Decision:
    eligible: bool
    blockers: tuple[Blocker, ...]
    required_evidence: tuple[str, ...]
    state: OperationState
    authorization_reference: Optional[str]
    notes: tuple[str, ...]


def _block(code: str, detail: str) -> Blocker:
    return Blocker(code=code, detail=detail)


def evaluate(analysis: Evaluation) -> Decision:
    """Avalia elegibilidade sem efeito externo. Determinístico e total."""
    blockers: list[Blocker] = []
    required: set[str] = set()
    notes: set[str] = {"NO_EXTERNAL_EXECUTION"}

    try:
        analysis.mission.validate()
        analysis.trust.validate()
        _utc(analysis.evaluated_at, "evaluated_at")
        if not isinstance(analysis.operation_state, OperationState):
            raise ValueError("operation_state inválido")
        for revocation in analysis.revocations:
            revocation.validate()
        auth = analysis.authorization
        if auth is not None:
            auth.validate()
        evidence = analysis.evidence
        if evidence is not None:
            evidence.validate()
    except (ValueError, TypeError, AttributeError) as exc:
        return Decision(
            eligible=False,
            blockers=tuple(sorted({_block("INVALID_INPUT", str(exc))})),
            required_evidence=(),
            state=analysis.operation_state if isinstance(
                getattr(analysis, "operation_state", None), OperationState
            ) else OperationState.UNKNOWN,
            authorization_reference=getattr(
                getattr(analysis, "authorization", None), "reference", None
            ),
            notes=("NO_EXTERNAL_EXECUTION",),
        )

    mission = analysis.mission
    trust = analysis.trust
    auth = analysis.authorization
    evidence = analysis.evidence
    state = analysis.operation_state
    reference = auth.reference if auth else None
    now = _utc(analysis.evaluated_at, "evaluated_at")

    if auth is None:
        blockers.append(_block("AUTHORIZATION_ABSENT", "autorização não fornecida"))
        return Decision(
            eligible=False,
            blockers=tuple(sorted(set(blockers))),
            required_evidence=("AUTHORIZATION", "EXTERNAL_AUTHORITY_EVIDENCE"),
            state=state,
            authorization_reference=None,
            notes=tuple(sorted(notes)),
        )

    if auth.self_declared_approved:
        notes.add("SELF_DECLARED_APPROVAL_IGNORED")

    # 1) Comprovação de autoridade: separada da estrutura e nunca autocertificada.
    if evidence is None or evidence.source is not AuthoritySource.EXTERNAL_ARTIFACT:
        blockers.append(_block("AUTHORITY_UNPROVEN", "evidência externa de autoridade ausente"))
        required.add(auth.approval_reference)
    else:
        pin = trust.authority_pins.get(auth.approval_reference)
        if pin is None:
            blockers.append(_block("AUTHORITY_UNPROVEN", "referência não fixada no contrato de confiança"))
            required.add(auth.approval_reference)
        elif evidence.reference != auth.approval_reference or evidence.digest_sha256 != pin:
            blockers.append(_block("AUTHORITY_DIVERGENT", "evidência externa diverge do pin"))
        if _utc(evidence.observed_at, "observed_at") > now:
            blockers.append(_block("AUTHORITY_NOT_YET_OBSERVED", "evidência observada no futuro"))

    # 2) Revogação: bloqueia novas operações, sem efeito retroativo presumido.
    for revocation in analysis.revocations:
        if revocation.authorization_reference != auth.reference:
            continue
        if _utc(revocation.revoked_at, "revoked_at") > now:
            continue
        notes.add("NO_RETROACTIVE_CANCELLATION")
        pin = trust.authority_pins.get(revocation.authority_reference)
        if pin is None or revocation.digest_sha256 != pin:
            blockers.append(_block("REVOCATION_EVIDENCE_UNPROVEN", "revogação sem evidência fixada"))
            required.add(revocation.authority_reference)
        else:
            blockers.append(_block("AUTHORIZATION_REVOKED", "autorização revogada"))

    # 3) Validade temporal e consumo.
    issued = _utc(auth.issued_at, "issued_at")
    expires = _utc(auth.expires_at, "expires_at")
    if now < issued:
        blockers.append(_block("AUTHORIZATION_NOT_YET_VALID", "fora da janela temporal"))
    if now > expires:
        blockers.append(_block("AUTHORIZATION_EXPIRED", "autorização vencida"))
    if auth.consumed:
        blockers.append(_block("AUTHORIZATION_CONSUMED", "autorização já consumida"))

    # 4) Correspondência estrutural.
    comparisons = (
        (mission.project_id == auth.project_id, "PROJECT_DIVERGENT", "projeto divergente"),
        (mission.repository_id == auth.repository_id, "REPOSITORY_DIVERGENT", "repositório divergente"),
        (mission.mission_id == auth.mission_id, "MISSION_ID_DIVERGENT", "mission ID divergente"),
        (mission.operation_id == auth.operation_id, "OPERATION_ID_DIVERGENT", "operation ID divergente"),
        (mission.mission_identity == auth.mission_identity, "MISSION_IDENTITY_DIVERGENT", "identidade da missão divergente"),
        (mission.payload_sha256 == auth.payload_sha256, "PAYLOAD_DIVERGENT", "hash do payload divergente"),
        (mission.recipient.key() == auth.recipient.key(), "RECIPIENT_DIVERGENT", "destinatário divergente"),
        (mission.head_sha == auth.head_sha, "HEAD_DIVERGENT", "HEAD divergente"),
        (mission.scope == auth.scope, "SCOPE_DIVERGENT", "escopo divergente"),
        (mission.model == auth.model, "MODEL_DIVERGENT", "modelo divergente"),
    )
    for ok, code, detail in comparisons:
        if not ok:
            blockers.append(_block(code, detail))

    if mission.project_id != trust.project_id:
        blockers.append(_block("PROJECT_UNTRUSTED", "projeto fora do contrato de confiança"))
    if mission.repository_id != trust.repository_id:
        blockers.append(_block("REPOSITORY_UNTRUSTED", "repositório fora do contrato de confiança"))
    if mission.head_sha not in trust.authorized_heads:
        blockers.append(_block("HEAD_NOT_AUTHORIZED", "HEAD não autorizado no contrato"))
    if mission.scope not in trust.allowed_scopes:
        blockers.append(_block("SCOPE_NOT_AUTHORIZED", "escopo não autorizado no contrato"))
    if mission.model not in trust.allowed_models:
        blockers.append(_block("MODEL_NOT_AUTHORIZED", "modelo não autorizado no contrato"))
    if mission.recipient.agent_id not in trust.allowed_agents:
        blockers.append(_block("RECIPIENT_NOT_ALLOWED", "agente não elegível no contrato"))
    if mission.transmissions >= min(auth.max_transmissions, trust.max_transmissions):
        blockers.append(_block("TRANSMISSION_LIMIT_EXCEEDED", "limite de transmissões atingido"))

    # 5) Modo e estado operacional.
    if trust.mode is Mode.SHADOW:
        blockers.append(_block("MODE_SHADOW_BLOCKED", "SHADOW não autoriza transmissão produtiva"))
    elif trust.mode in (Mode.ASSISTED, Mode.DELEGATED):
        blockers.append(_block("MODE_DISABLED", "ASSISTED/DELEGATED permanecem desativados"))

    state_blocks = {
        OperationState.UNKNOWN: ("STATE_UNKNOWN_BLOCKED", "resultado desconhecido bloqueia reenvio"),
        OperationState.TRANSMITTED: ("STATE_ALREADY_TRANSMITTED", "operação já transmitida"),
        OperationState.RUNNING: ("STATE_RUNNING_BLOCKED", "operação em execução"),
        OperationState.TERMINAL: ("STATE_TERMINAL_BLOCKED", "operação terminal"),
    }
    if state in state_blocks:
        code, detail = state_blocks[state]
        blockers.append(_block(code, detail))

    return Decision(
        eligible=not blockers,
        blockers=tuple(sorted(set(blockers))),
        required_evidence=tuple(sorted(required)),
        state=state,
        authorization_reference=reference,
        notes=tuple(sorted(notes)),
    )
