#!/usr/bin/env python3
"""Núcleo genérico de comunicação coordenada — P1 exclusivamente sintética.

Não contém transporte real, credenciais, rede, persistência ou regras específicas
do Better Talker. O motor é deliberadamente pequeno: contratos, autorização,
estados, reconciliação e eventos em memória.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Callable, Optional

MISSION_SCHEMA_VERSION = "1.0"
REPORT_SCHEMA_VERSION = "BT-COORD-REPORT:v1"
ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class ContractError(ValueError):
    pass


class AuthorizationError(ContractError):
    pass


class TransportError(RuntimeError):
    def __init__(self, message: str, *, ambiguous: bool = False,
                 phase: str = "unknown"):
        super().__init__(message)
        self.ambiguous = ambiguous
        self.phase = phase


class TransportDisabled(TransportError):
    pass


class OperationState(str, Enum):
    PROPOSED = "PROPOSED"
    AUTHORIZED = "AUTHORIZED"
    DISPATCHING = "DISPATCHING"
    DELIVERED = "DELIVERED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"


ALLOWED_TRANSITIONS = {
    OperationState.PROPOSED: {OperationState.AUTHORIZED, OperationState.FAILED},
    OperationState.AUTHORIZED: {OperationState.DISPATCHING, OperationState.FAILED},
    OperationState.DISPATCHING: {OperationState.DELIVERED, OperationState.RUNNING,
                                 OperationState.FAILED, OperationState.UNKNOWN},
    OperationState.DELIVERED: {OperationState.RUNNING, OperationState.FAILED, OperationState.UNKNOWN},
    OperationState.RUNNING: {OperationState.COMPLETED, OperationState.FAILED, OperationState.UNKNOWN},
    OperationState.UNKNOWN: {OperationState.DELIVERED, OperationState.RUNNING,
                             OperationState.COMPLETED, OperationState.FAILED},
    OperationState.COMPLETED: set(),
    OperationState.FAILED: set(),
}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def payload_sha256(payload: Any) -> str:
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def canonical_mission_identity(mission: "MissionEnvelope") -> str:
    """Identidade canônica de todos os campos materiais autorizados."""
    material = {
        "schema_version": mission.schema_version,
        "project_id": mission.project_id,
        "repository_id": mission.repository_id,
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "coordinator_id": mission.coordinator_id,
        "target": list(mission.target.key()),
        "objective": mission.objective,
        "payload": mission.payload,
        "payload_sha256": mission.payload_sha256,
        "scope": mission.scope,
        "expected_deliverables": list(mission.expected_deliverables),
        "acceptance_criteria": list(mission.acceptance_criteria),
        "ownership_constraints": list(mission.ownership_constraints),
        "expected_head_sha": mission.expected_head_sha,
        "expected_branch": mission.expected_branch,
        "created_at": iso(mission.created_at),
        "expires_at": iso(mission.expires_at),
        "timeout": mission.timeout,
    }
    return hashlib.sha256(canonical_json(material).encode("utf-8")).hexdigest()


def _require_aware(value: datetime, label: str) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ContractError(f"{label} sem timezone válido")


def _require_id(value: str, label: str) -> None:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise ContractError(f"{label} inválido")


def _require_text(value: str, label: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ContractError(f"{label} ausente")


@dataclass(frozen=True)
class TargetIdentity:
    project_id: str
    repository_id: str
    agent_id: str
    session_id: str
    instance_id: str
    location: str

    def validate(self) -> None:
        for value, label in ((self.project_id, "project_id"), (self.repository_id, "repository_id"),
                             (self.agent_id, "agent_id"), (self.session_id, "session_id"),
                             (self.instance_id, "instance_id")):
            _require_id(value, label)
        _require_text(self.location, "location")
        if not self.location.startswith("/"):
            raise ContractError("location deve ser absoluto")

    def key(self) -> tuple[str, ...]:
        return (self.project_id, self.repository_id, self.agent_id, self.session_id,
                self.instance_id, self.location)


@dataclass(frozen=True)
class SessionStatus:
    target: TargetIdentity
    state: str
    inbox_count: int = 0
    evidence: dict[str, Any] = field(default_factory=dict)

    def validate(self) -> None:
        if self.state not in {"idle", "busy", "missing"}:
            raise ContractError("estado de sessão inválido")
        if not isinstance(self.inbox_count, int) or self.inbox_count < 0:
            raise ContractError("inbox_count inválido")


@dataclass(frozen=True)
class Authorization:
    reference: str
    project_id: str
    repository_id: str
    target: TargetIdentity
    payload_sha256: str
    mission_identity: str
    scope: str
    issued_at: datetime
    expires_at: datetime
    revoked: bool = False
    synthetic: bool = True

    def validate(self) -> None:
        _require_id(self.reference, "authorization_reference")
        _require_text(self.scope, "scope")
        self.target.validate()
        if not SHA256_PATTERN.fullmatch(self.payload_sha256):
            raise ContractError("hash da autorização inválido")
        if not SHA256_PATTERN.fullmatch(self.mission_identity):
            raise ContractError("identidade canônica da missão inválida")
        _require_aware(self.issued_at, "issued_at")
        _require_aware(self.expires_at, "expires_at")
        if self.expires_at <= self.issued_at:
            raise ContractError("autorização com validade inválida")


@dataclass(frozen=True)
class MissionEnvelope:
    schema_version: str
    project_id: str
    repository_id: str
    mission_id: str
    operation_id: str
    coordinator_id: str
    target_agent: str
    target_session_id: str
    target_instance_id: str
    target_location: str
    objective: str
    payload: dict[str, Any]
    payload_sha256: str
    expected_deliverables: tuple[str, ...]
    acceptance_criteria: tuple[str, ...]
    ownership_constraints: tuple[str, ...]
    authorization_reference: str
    created_at: datetime
    expires_at: datetime
    timeout: int
    scope: str
    expected_head_sha: Optional[str] = None
    expected_branch: Optional[str] = None

    @property
    def target(self) -> TargetIdentity:
        return TargetIdentity(self.project_id, self.repository_id, self.target_agent,
                              self.target_session_id, self.target_instance_id, self.target_location)

    def validate(self) -> None:
        if self.schema_version != MISSION_SCHEMA_VERSION:
            raise ContractError("schema_version de missão não suportado")
        self.target.validate()
        for value, label in ((self.mission_id, "mission_id"), (self.operation_id, "operation_id"),
                             (self.coordinator_id, "coordinator_id"),
                             (self.authorization_reference, "authorization_reference")):
            _require_id(value, label)
        _require_text(self.objective, "objective")
        _require_text(self.scope, "scope")
        if not isinstance(self.payload, dict):
            raise ContractError("payload deve ser objeto")
        if payload_sha256(self.payload) != self.payload_sha256:
            raise ContractError("payload diverge do hash")
        for value, label in ((self.expected_deliverables, "expected_deliverables"),
                             (self.acceptance_criteria, "acceptance_criteria"),
                             (self.ownership_constraints, "ownership_constraints")):
            if not isinstance(value, tuple) or not value or any(not isinstance(v, str) or not v.strip() for v in value):
                raise ContractError(f"{label} inválido")
        if not isinstance(self.timeout, int) or self.timeout <= 0:
            raise ContractError("timeout inválido")
        _require_aware(self.created_at, "created_at")
        _require_aware(self.expires_at, "expires_at")
        if self.expires_at <= self.created_at:
            raise ContractError("validade da missão inválida")
        if self.expected_head_sha is not None and not SHA_PATTERN.fullmatch(self.expected_head_sha):
            raise ContractError("expected_head_sha inválido")


@dataclass(frozen=True)
class MissionReport:
    schema_version: str
    project_id: str
    mission_id: str
    operation_id: Optional[str]
    agent_id: str
    session_id: str
    message_id: str
    status: str
    head_sha: Optional[str]
    branch: Optional[str]
    pull_request: Optional[int]
    test_evidence: tuple[dict[str, Any], ...]
    blockers: tuple[str, ...]
    artifacts: tuple[str, ...]
    completed_at: datetime
    acceptance_results: dict[str, bool] = field(default_factory=dict)
    untrusted_text: str = ""

    def validate(self) -> None:
        if self.schema_version != REPORT_SCHEMA_VERSION:
            raise ContractError("schema_version de relatório não suportado")
        for value, label in ((self.project_id, "project_id"), (self.mission_id, "mission_id"),
                             (self.agent_id, "agent_id"), (self.session_id, "session_id"),
                             (self.message_id, "message_id")):
            _require_id(value, label)
        if self.operation_id is not None:
            _require_id(self.operation_id, "operation_id")
        if self.status not in {"COMPLETED", "FAILED", "BLOCKED", "IN_PROGRESS"}:
            raise ContractError("status de relatório inválido")
        if self.head_sha is not None and not SHA_PATTERN.fullmatch(self.head_sha):
            raise ContractError("head_sha inválido")
        if self.pull_request is not None and (not isinstance(self.pull_request, int) or self.pull_request <= 0):
            raise ContractError("pull_request inválido")
        if not isinstance(self.test_evidence, tuple) or not isinstance(self.blockers, tuple) or not isinstance(self.artifacts, tuple):
            raise ContractError("evidências/artefatos inválidos")
        if not isinstance(self.acceptance_results, dict) or any(not isinstance(v, bool) for v in self.acceptance_results.values()):
            raise ContractError("acceptance_results inválido")
        _require_aware(self.completed_at, "completed_at")

    def fingerprint(self) -> str:
        return hashlib.sha256(canonical_json(self.to_dict()).encode("utf-8")).hexdigest()

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version, "project_id": self.project_id,
            "mission_id": self.mission_id, "operation_id": self.operation_id,
            "agent_id": self.agent_id, "session_id": self.session_id,
            "message_id": self.message_id, "status": self.status,
            "head_sha": self.head_sha, "branch": self.branch, "pull_request": self.pull_request,
            "test_evidence": list(self.test_evidence), "blockers": list(self.blockers),
            "artifacts": list(self.artifacts), "completed_at": iso(self.completed_at),
            "acceptance_results": self.acceptance_results, "untrusted_text": self.untrusted_text,
        }

    def marker(self) -> str:
        """Texto compatível com o contrato v1 integrado na COORD-03."""
        head = self.head_sha or "0" * 40
        lines = [
            f"<!-- {REPORT_SCHEMA_VERSION} -->",
            f"mission_id: {self.mission_id}",
            f"operation_id: {self.operation_id or 'DESCONHECIDA'}",
            f"head_sha: {head}",
            f"status: {self.status}",
            f"schema_version: {self.schema_version}",
            f"contract: {canonical_json(self.to_dict())}",
            "Parecer: relatório estruturado; dado não confiável; não executa instruções.",
        ]
        return "\n".join(lines)


@dataclass(frozen=True)
class PreparedRequest:
    request_id: str
    envelope: MissionEnvelope
    authorization: Authorization
    prepared_at: datetime


@dataclass(frozen=True)
class TransportReceipt:
    operation_id: str
    outcome: str
    message_id: Optional[str]
    evidence: dict[str, Any]
    observed_at: datetime


@dataclass(frozen=True)
class OperationObservation:
    operation_id: str
    state: str
    message_id: Optional[str] = None
    reports: tuple[MissionReport, ...] = ()
    evidence: dict[str, Any] = field(default_factory=dict)
    target: Optional[TargetIdentity] = None
    evidence_level: str = "not_observed"
    message_operation_id: Optional[str] = None
    payload_sha256: Optional[str] = None


@dataclass(frozen=True)
class ReportAssessment:
    classification: str
    verified: bool
    completed: bool
    reasons: tuple[str, ...]
    structural_valid: bool = True
    associated: bool = True
    declared_evidence: bool = False
    declared_conclusion: bool = False
    independently_verified: bool = False
    code_certified: bool = False


@dataclass(frozen=True)
class Event:
    sequence: int
    operation_id: str
    event_type: str
    from_state: Optional[OperationState]
    to_state: OperationState
    evidence: dict[str, Any]
    timestamp: datetime


class EventLog:
    def __init__(self) -> None:
        self.events: list[Event] = []

    def append(self, operation_id: str, event_type: str, from_state: Optional[OperationState],
               to_state: OperationState, evidence: Optional[dict[str, Any]] = None) -> None:
        self.events.append(Event(len(self.events) + 1, operation_id, event_type, from_state,
                                 to_state, evidence or {}, utc_now()))


@dataclass
class OperationRecord:
    mission: MissionEnvelope
    state: OperationState = OperationState.PROPOSED
    authorization: Optional[Authorization] = None
    message_id: Optional[str] = None
    report: Optional[MissionReport] = None
    assessment: Optional[ReportAssessment] = None
    created_at: datetime = field(default_factory=utc_now)
    updated_at: datetime = field(default_factory=utc_now)

    def transition(self, target: OperationState, events: EventLog, event_type: str,
                   evidence: Optional[dict[str, Any]] = None) -> None:
        if target not in ALLOWED_TRANSITIONS[self.state]:
            raise ContractError(f"transição proibida: {self.state.value} → {target.value}")
        previous = self.state
        self.state = target
        self.updated_at = utc_now()
        events.append(self.mission.operation_id, event_type, previous, target, evidence)


@dataclass(frozen=True)
class ProjectPolicy:
    project_id: str
    repository_id: str
    allowed_locations: tuple[str, ...]
    allowed_agent_ids: tuple[str, ...]
    registered_targets: tuple[TargetIdentity, ...]
    forbidden_scope_terms: tuple[str, ...] = ("merge", "push main", "release", "adb", "schema", "credential")
    mode: str = "SHADOW"

    def validate(self) -> None:
        if self.mode != "SHADOW":
            raise AuthorizationError("P1 não promove SHADOW")
        if not self.registered_targets:
            raise ContractError("política sem destinatários")
        for target in self.registered_targets:
            target.validate()
            if target.project_id != self.project_id or target.repository_id != self.repository_id:
                raise ContractError("destinatário de outro projeto")
            if target.agent_id not in self.allowed_agent_ids or target.location not in self.allowed_locations:
                raise ContractError("destinatário fora da política")

    def validate_target(self, target: TargetIdentity) -> None:
        self.validate()
        if target not in self.registered_targets:
            raise AuthorizationError("destinatário não cadastrado ou identidade divergente")

    def validate_scope(self, mission: MissionEnvelope) -> None:
        text = canonical_json({"scope": mission.scope, "objective": mission.objective,
                               "payload": mission.payload}).lower()
        blocked = [term for term in self.forbidden_scope_terms if term in text]
        if blocked:
            raise AuthorizationError(f"escopo proibido: {', '.join(blocked)}")


class Transport(ABC):
    """Interface desacoplada; P1 implementa apenas SimulatedTransport."""

    @abstractmethod
    def lookup_target(self, project_id: str, agent_id: str, session_id: str) -> TargetIdentity:
        raise NotImplementedError

    @abstractmethod
    def status(self, target: TargetIdentity) -> SessionStatus:
        raise NotImplementedError

    @abstractmethod
    def prepare(self, envelope: MissionEnvelope, authorization: Authorization) -> PreparedRequest:
        raise NotImplementedError

    @abstractmethod
    def transmit(self, request: PreparedRequest) -> TransportReceipt:
        raise NotImplementedError

    @abstractmethod
    def operation_status(self, operation_id: str) -> OperationObservation:
        raise NotImplementedError

    @abstractmethod
    def reports(self, operation_id: str) -> tuple[MissionReport, ...]:
        raise NotImplementedError


class DisabledRealTransport(Transport):
    """Adaptador real intencionalmente inativo."""

    def _disabled(self) -> None:
        raise TransportDisabled("transporte real desativado na P1")

    def lookup_target(self, project_id: str, agent_id: str, session_id: str) -> TargetIdentity:
        self._disabled()

    def status(self, target: TargetIdentity) -> SessionStatus:
        self._disabled()

    def prepare(self, envelope: MissionEnvelope, authorization: Authorization) -> PreparedRequest:
        self._disabled()

    def transmit(self, request: PreparedRequest) -> TransportReceipt:
        self._disabled()

    def operation_status(self, operation_id: str) -> OperationObservation:
        self._disabled()

    def reports(self, operation_id: str) -> tuple[MissionReport, ...]:
        self._disabled()


@dataclass
class SyntheticMessage:
    message_id: str
    operation_id: str
    target: TargetIdentity
    text: str
    metadata: dict[str, Any]


class SimulatedTransport(Transport):
    """Transporte determinístico em memória; nenhuma chamada externa."""

    def __init__(self, targets: tuple[TargetIdentity, ...], scripts: Optional[dict[str, str]] = None):
        self.targets = {target.key(): target for target in targets}
        self.scripts = dict(scripts or {})
        self.sessions = {target.key(): {"state": "idle", "inbox": [], "recorded": []} for target in targets}
        self.messages: dict[str, SyntheticMessage] = {}
        self.execution: dict[str, str] = {}
        self.report_store: dict[str, MissionReport] = {}
        self.transmit_calls: list[str] = []

    def lookup_target(self, project_id: str, agent_id: str, session_id: str) -> TargetIdentity:
        matches = [t for t in self.targets.values() if t.project_id == project_id
                   and t.agent_id == agent_id and t.session_id == session_id]
        if len(matches) != 1:
            raise TransportError("destinatário inexistente ou ambíguo")
        return matches[0]

    def status(self, target: TargetIdentity) -> SessionStatus:
        session = self.sessions.get(target.key())
        if session is None:
            return SessionStatus(target, "missing")
        return SessionStatus(target, session["state"], len(session["inbox"]),
                             {"source": "synthetic", "recorded": len(session["recorded"])})

    def prepare(self, envelope: MissionEnvelope, authorization: Authorization) -> PreparedRequest:
        envelope.validate()
        authorization.validate()
        return PreparedRequest(f"req-{envelope.operation_id}", envelope, authorization, utc_now())

    def transmit(self, request: PreparedRequest) -> TransportReceipt:
        envelope = request.envelope
        self.transmit_calls.append(envelope.operation_id)
        outcome = self.scripts.get(envelope.operation_id, "success")
        target = envelope.target
        session = self.sessions.get(target.key())
        if outcome in {"wrong_target", "missing"} or session is None:
            raise TransportError("destinatário incorreto/inexistente", phase="pre_transmit")
        if outcome == "busy" or session["state"] == "busy" or session["inbox"]:
            raise TransportError("sessão ou inbox ocupado", phase="pre_transmit")
        if outcome == "pre_transmit_failure":
            raise TransportError("falha antes da transmissão", phase="pre_transmit")
        message_id = f"msg-{envelope.operation_id}"
        message = SyntheticMessage(message_id, envelope.operation_id, target,
                                   canonical_json(envelope.payload),
                                   {"operation_id": envelope.operation_id,
                                    "payload_sha256": envelope.payload_sha256})
        self.messages[message_id] = message
        session["inbox"].append(message_id)
        self.execution[envelope.operation_id] = "queued"
        if outcome in {"timeout_after_transmit", "lost_response"}:
            raise TransportError("resposta perdida após transmissão", ambiguous=True,
                                 phase="post_transmit")
        evidence = {"queued_in_inbox": True, "source": "synthetic",
                    "operation_id": envelope.operation_id,
                    "message_id": message_id,
                    "payload_sha256": envelope.payload_sha256,
                    "project_id": envelope.project_id,
                    "repository_id": envelope.repository_id,
                    "agent_id": envelope.target_agent,
                    "session_id": envelope.target_session_id,
                    "instance_id": envelope.target_instance_id,
                    "location": envelope.target_location}
        if outcome == "partial":
            return TransportReceipt(envelope.operation_id, "ambiguous", None,
                                     {**evidence, "message_id_not_observed": True}, utc_now())
        return TransportReceipt(envelope.operation_id, "accepted", message_id, evidence, utc_now())

    def operation_status(self, operation_id: str) -> OperationObservation:
        message_id = f"msg-{operation_id}"
        message = self.messages.get(message_id)
        if message is None:
            return OperationObservation(operation_id, "not_observed", evidence={"source": "synthetic"})
        target = message.target
        session = self.sessions.get(target.key(), {"inbox": [], "recorded": []})
        queued = message_id in session["inbox"]
        recorded = message_id in session["recorded"]
        execution = self.execution.get(operation_id, "queued")
        evidence = {"source": "synthetic", "queued": queued, "recorded": recorded}
        if execution == "completed":
            level, state = "report_received", "completed"
            evidence.update({"executing": True, "report_available": True})
        elif execution == "failed":
            level, state = "result_available", "failed"
            evidence.update({"executing": True, "result_available": True})
        elif execution == "running":
            level, state = "execution_started", "running"
            evidence["executing"] = True
        elif recorded:
            level, state = "recorded_in_session", "queued"
        elif queued:
            level, state = "queued_in_inbox", "queued"
        else:
            level, state = "not_observed", "not_observed"
        return OperationObservation(operation_id, state, message_id, self.reports(operation_id),
                                    evidence,
                                    target, level, message.metadata.get("operation_id"),
                                    message.metadata.get("payload_sha256"))

    def reports(self, operation_id: str) -> tuple[MissionReport, ...]:
        report = self.report_store.get(operation_id)
        if report is None:
            return ()
        outcome = self.scripts.get(operation_id, "success")
        if outcome == "duplicate_result":
            return (report, report)
        if outcome == "divergent_result":
            return (report, replace(report, status="FAILED"))
        return (report,)

    def simulate_deliver(self, operation_id: str) -> None:
        """Move a mensagem do inbox para a sessão; evidência mínima de DELIVERED."""
        message_id = f"msg-{operation_id}"
        message = self.messages.get(message_id)
        if message is None:
            raise TransportError("operação não observada")
        session = self.sessions[message.target.key()]
        if message_id in session["inbox"]:
            session["inbox"].remove(message_id)
        if message_id not in session["recorded"]:
            session["recorded"].append(message_id)

    def simulate_start(self, operation_id: str) -> None:
        message_id = f"msg-{operation_id}"
        message = self.messages.get(message_id)
        if message is None or message_id not in self.sessions[message.target.key()]["recorded"]:
            raise TransportError("mensagem não registrada na sessão")
        self.execution[operation_id] = "running"

    def simulate_report(self, operation_id: str, report: MissionReport) -> None:
        message_id = f"msg-{operation_id}"
        message = self.messages.get(message_id)
        if message is None or message_id not in self.sessions[message.target.key()]["recorded"]:
            raise TransportError("mensagem não registrada na sessão")
        self.report_store[operation_id] = report
        self.execution[operation_id] = "completed" if report.status == "COMPLETED" else "failed"


def canonical_authorization_identity(auth: Authorization) -> str:
    """Representação canônica de todos os campos materiais da autorização."""
    material = {
        "reference": auth.reference,
        "project_id": auth.project_id,
        "repository_id": auth.repository_id,
        "target": list(auth.target.key()),
        "payload_sha256": auth.payload_sha256,
        "mission_identity": auth.mission_identity,
        "scope": auth.scope,
        "issued_at": iso(auth.issued_at),
        "expires_at": iso(auth.expires_at),
        "revoked": auth.revoked,
        "synthetic": auth.synthetic,
    }
    return hashlib.sha256(canonical_json(material).encode("utf-8")).hexdigest()


def capture_authorized_snapshot(mission: MissionEnvelope, auth: Authorization) -> dict[str, Any]:
    """Referência estável da missão autorizada antes da preparação do transporte."""
    return {
        "mission_identity": canonical_mission_identity(mission),
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "project_id": mission.project_id,
        "repository_id": mission.repository_id,
        "target": list(mission.target.key()),
        "objective": mission.objective,
        "payload_sha256": mission.payload_sha256,
        "scope": mission.scope,
        "expected_deliverables": list(mission.expected_deliverables),
        "acceptance_criteria": list(mission.acceptance_criteria),
        "ownership_constraints": list(mission.ownership_constraints),
        "expected_head_sha": mission.expected_head_sha,
        "expected_branch": mission.expected_branch,
        "expires_at": iso(mission.expires_at),
        "timeout": mission.timeout,
        "authorization_reference": auth.reference,
        "authorization_identity": canonical_authorization_identity(auth),
    }


def clone_prepared_request(request: PreparedRequest) -> PreparedRequest:
    """Cópia profunda para impedir mutação in-place do conteúdo aprovado."""
    payload = copy.deepcopy(request.envelope.payload)
    envelope = replace(request.envelope, payload=payload,
                       expected_deliverables=tuple(request.envelope.expected_deliverables),
                       acceptance_criteria=tuple(request.envelope.acceptance_criteria),
                       ownership_constraints=tuple(request.envelope.ownership_constraints))
    authorization = replace(request.authorization, target=replace(request.authorization.target))
    return PreparedRequest(request.request_id, envelope, authorization, request.prepared_at)


class CommunicationEngine:
    def __init__(self, policy: ProjectPolicy, transport: Transport,
                 clock: Callable[[], datetime] = utc_now, *, allow_synthetic_only: bool = True):
        policy.validate()
        self.policy = policy
        self.transport = transport
        self.clock = clock
        self.allow_synthetic_only = allow_synthetic_only
        self.operations: dict[str, OperationRecord] = {}
        self.events = EventLog()

    def _validate_authorization(self, mission: MissionEnvelope, auth: Authorization) -> None:
        mission.validate()
        auth.validate()
        if self.allow_synthetic_only and not auth.synthetic:
            raise AuthorizationError("P1 aceita somente autorização sintética")
        if auth.revoked:
            raise AuthorizationError("autorização revogada")
        current = self.clock()
        _require_aware(current, "clock")
        if current >= auth.expires_at:
            raise AuthorizationError("autorização expirada")
        if auth.issued_at > current:
            raise AuthorizationError("autorização emitida no futuro")
        if mission.created_at > current:
            raise AuthorizationError("missão criada no futuro")
        if current >= mission.expires_at:
            raise AuthorizationError("missão expirada")
        if auth.mission_identity != canonical_mission_identity(mission):
            raise AuthorizationError("autorização vinculada a outra missão material")
        if auth.project_id != mission.project_id or auth.repository_id != mission.repository_id:
            raise AuthorizationError("projeto/repositório não autorizado")
        if auth.target != mission.target:
            raise AuthorizationError("destinatário diferente do autorizado")
        if auth.payload_sha256 != mission.payload_sha256:
            raise AuthorizationError("payload divergente do hash autorizado")
        if auth.scope != mission.scope:
            raise AuthorizationError("escopo diferente do autorizado")
        self.policy.validate_target(mission.target)
        self.policy.validate_scope(mission)

    def propose(self, mission: MissionEnvelope) -> OperationRecord:
        mission.validate()
        if mission.operation_id in self.operations:
            raise ContractError("operation_id já registrado")
        self.policy.validate_target(mission.target)
        self.policy.validate_scope(mission)
        record = OperationRecord(mission, created_at=mission.created_at,
                                 updated_at=mission.created_at)
        self.operations[mission.operation_id] = record
        self.events.append(mission.operation_id, "proposed", None, OperationState.PROPOSED,
                           {"mission_id": mission.mission_id})
        return record

    def authorize(self, operation_id: str, authorization: Authorization) -> OperationRecord:
        record = self.operations[operation_id]
        if record.state != OperationState.PROPOSED:
            raise ContractError("operação não está PROPOSED")
        self._validate_authorization(record.mission, authorization)
        if authorization.reference != record.mission.authorization_reference:
            raise AuthorizationError("referência da autorização divergente")
        record.authorization = authorization
        record.transition(OperationState.AUTHORIZED, self.events, "authorized",
                          {"authorization_reference": authorization.reference})
        return record

    def _validate_prepared_request(self, request: PreparedRequest, snapshot: dict[str, Any]) -> None:
        if not isinstance(request, PreparedRequest):
            raise ContractError("requisição preparada malformada")
        if not isinstance(request.request_id, str) or not request.request_id.strip():
            raise ContractError("request_id ausente")
        envelope, auth = request.envelope, request.authorization
        if not isinstance(envelope, MissionEnvelope) or not isinstance(auth, Authorization):
            raise ContractError("tipos da requisição inválidos")
        envelope.validate()
        auth.validate()
        current = self.clock()
        _require_aware(current, "clock")
        if envelope.created_at > current or current >= envelope.expires_at:
            raise AuthorizationError("requisição fora da validade da missão")
        if auth.issued_at > current or current >= auth.expires_at:
            raise AuthorizationError("autorização expirada ou futura")
        if auth.revoked:
            raise AuthorizationError("autorização revogada")
        material = {
            "mission_identity": canonical_mission_identity(envelope),
            "mission_id": envelope.mission_id,
            "operation_id": envelope.operation_id,
            "project_id": envelope.project_id,
            "repository_id": envelope.repository_id,
            "target": list(envelope.target.key()),
            "objective": envelope.objective,
            "payload_sha256": envelope.payload_sha256,
            "scope": envelope.scope,
            "expected_deliverables": list(envelope.expected_deliverables),
            "acceptance_criteria": list(envelope.acceptance_criteria),
            "ownership_constraints": list(envelope.ownership_constraints),
            "expected_head_sha": envelope.expected_head_sha,
            "expected_branch": envelope.expected_branch,
            "expires_at": iso(envelope.expires_at),
            "timeout": envelope.timeout,
            "authorization_reference": auth.reference,
            "authorization_identity": canonical_authorization_identity(auth),
        }
        divergent = [key for key, value in snapshot.items() if material.get(key) != value]
        if divergent:
            raise ContractError(f"requisição diverge da missão autorizada: {', '.join(divergent)}")

    def _validate_receipt(self, record: OperationRecord, receipt: TransportReceipt) -> None:
        if receipt.operation_id != record.mission.operation_id:
            raise ContractError("operation_id do receipt divergente")
        if receipt.outcome not in {"accepted", "ambiguous", "rejected", "unknown"}:
            raise ContractError("outcome de transporte desconhecido")
        if receipt.message_id and receipt.message_id.startswith("msg-report-"):
            raise ContractError("ID do prompt não pode ser ID de relatório")
        if receipt.outcome == "accepted" and not receipt.message_id:
            raise ContractError("receipt aceito sem message_id")
        expected_evidence = {
            "operation_id": record.mission.operation_id,
            "payload_sha256": record.mission.payload_sha256,
            "project_id": record.mission.project_id,
            "repository_id": record.mission.repository_id,
            "agent_id": record.mission.target_agent,
            "session_id": record.mission.target_session_id,
            "instance_id": record.mission.target_instance_id,
            "location": record.mission.target_location,
        }
        if not isinstance(receipt.evidence, dict):
            raise ContractError("evidência do receipt ausente")
        for key, expected in expected_evidence.items():
            if receipt.evidence.get(key) != expected:
                raise ContractError(f"evidência divergente ou ausente: {key}")
        if receipt.evidence.get("message_id") not in (None, receipt.message_id):
            raise ContractError("message_id do receipt divergente da evidência")

    def transmit(self, operation_id: str) -> OperationRecord:
        record = self.operations[operation_id]
        if record.state == OperationState.UNKNOWN:
            raise AuthorizationError("REENVIO BLOQUEADO: operação UNKNOWN")
        if record.state != OperationState.AUTHORIZED or record.authorization is None:
            raise ContractError("operação não autorizada")
        self._validate_authorization(record.mission, record.authorization)
        snapshot = capture_authorized_snapshot(record.mission, record.authorization)
        target = self.transport.lookup_target(record.mission.project_id, record.mission.target_agent,
                                              record.mission.target_session_id)
        if target != record.mission.target:
            raise AuthorizationError("identidade observada diverge do envelope")
        status = self.transport.status(target)
        if status.target != target or status.state != "idle" or status.inbox_count:
            record.transition(OperationState.FAILED, self.events, "rejected_before_send",
                              {"reason": "status insuficiente", "status": status.state,
                               "availability_not_claimed": True})
            return record
        record.transition(OperationState.DISPATCHING, self.events, "dispatch_started",
                          {"request_prepared": False})
        try:
            request = self.transport.prepare(record.mission, record.authorization)
            self._validate_prepared_request(request, snapshot)
            self._validate_authorization(request.envelope, request.authorization)
            safe_request = clone_prepared_request(request)
            self._validate_prepared_request(safe_request, snapshot)
            self._validate_authorization(safe_request.envelope, safe_request.authorization)
        except TransportError as e:
            failed_before_send = e.phase == "pre_transmit" and not e.ambiguous
            record.transition(OperationState.FAILED if failed_before_send else OperationState.UNKNOWN,
                              self.events, "prepare_error",
                              {"error": str(e), "phase": e.phase})
            return record
        except (ContractError, AuthorizationError) as e:
            record.transition(OperationState.FAILED, self.events, "pre_transmit_validation",
                              {"error": str(e), "transmit_called": False})
            return record
        try:
            receipt = self.transport.transmit(safe_request)
        except TransportError as e:
            ambiguous = e.ambiguous or e.phase in {"during_transmit", "post_transmit"}
            record.transition(OperationState.UNKNOWN if ambiguous else OperationState.FAILED,
                              self.events, "transport_error",
                              {"error": str(e), "phase": e.phase, "ambiguous": ambiguous})
            return record
        try:
            self._validate_receipt(record, receipt)
        except ContractError as e:
            record.transition(OperationState.UNKNOWN, self.events, "ambiguous_receipt",
                              {"error": str(e), "transmit_called": True})
            return record
        if receipt.outcome == "accepted" and receipt.message_id:
            record.message_id = receipt.message_id
            self.events.append(operation_id, "accepted_in_inbox", OperationState.DISPATCHING,
                               OperationState.DISPATCHING,
                               {"message_id": receipt.message_id, "evidence": receipt.evidence})
        elif receipt.outcome in {"ambiguous", "unknown"}:
            record.transition(OperationState.UNKNOWN, self.events, "ambiguous_receipt",
                              {"evidence": receipt.evidence})
        else:
            record.transition(OperationState.FAILED, self.events, "rejected",
                              {"evidence": receipt.evidence})
        return record

    def _ensure_delivered(self, record: OperationRecord, observation: OperationObservation) -> None:
        if record.state in {OperationState.DISPATCHING, OperationState.UNKNOWN}:
            record.transition(OperationState.DELIVERED, self.events, "delivery_observed",
                              {"evidence_level": observation.evidence_level,
                               "message_id": observation.message_id,
                               "observation": observation.state})

    def _ensure_running(self, record: OperationRecord) -> None:
        if record.state in {OperationState.DISPATCHING, OperationState.DELIVERED, OperationState.UNKNOWN}:
            self._ensure_delivered(record, OperationObservation(
                record.mission.operation_id, "execution_started", record.message_id,
                evidence_level="execution_started"))
        if record.state == OperationState.DELIVERED:
            record.transition(OperationState.RUNNING, self.events, "execution_started",
                              {"observation": "execution_started"})

    def observe(self, operation_id: str) -> OperationRecord:
        record = self.operations[operation_id]
        observation = self.transport.operation_status(operation_id)
        if observation.operation_id != record.mission.operation_id:
            raise ContractError("operation_id da observação divergente")
        if observation.target is not None and observation.target != record.mission.target:
            raise ContractError("sessão/projeto/instance/location observados divergentes")
        if observation.message_operation_id is not None and observation.message_operation_id != record.mission.operation_id:
            raise ContractError("operation_id da mensagem divergente")
        if observation.payload_sha256 is not None and observation.payload_sha256 != record.mission.payload_sha256:
            raise ContractError("payload da mensagem divergente")
        if observation.message_id and record.message_id and observation.message_id != record.message_id:
            raise ContractError("message_id divergente")
        if observation.message_id:
            record.message_id = observation.message_id
        level = observation.evidence_level
        if level in {"not_observed", "queued_in_inbox", "unknown"}:
            return record
        if observation.target is None:
            raise ContractError("observação sem identidade de sessão")
        if not isinstance(observation.evidence, dict):
            raise ContractError("observação sem evidência estruturada")
        if level == "recorded_in_session":
            if observation.evidence.get("recorded") is not True or not observation.message_id:
                return record
            self._ensure_delivered(record, observation)
            return record
        if level == "execution_started":
            if observation.evidence.get("executing") is not True:
                return record
            self._ensure_delivered(record, observation)
            self._ensure_running(record)
            return record
        if level == "result_available":
            if observation.evidence.get("result_available") is not True:
                return record
            self._ensure_running(record)
            if observation.state == "failed" and record.state == OperationState.RUNNING:
                record.transition(OperationState.FAILED, self.events, "execution_failed",
                                  {"observation": observation.state})
            return record
        if level == "report_received":
            if observation.evidence.get("report_available") is not True or not observation.reports:
                return record
            prepared = self._validate_report_batch(record, observation.reports)
            self._ensure_running(record)
            self._apply_report_batch(record, prepared)
            return record
        return record

    def _validate_report_batch(self, record: OperationRecord,
                               reports: tuple[MissionReport, ...]) -> list[tuple[MissionReport, str, ReportAssessment]]:
        if record.message_id is None or record.state == OperationState.DISPATCHING:
            raise ContractError("relatório recebido sem entrega confirmada")
        prepared: list[tuple[MissionReport, str, ReportAssessment]] = []
        seen: dict[str, str] = {}
        for item in reports:
            item.validate()
            fingerprint = item.fingerprint()
            if item.message_id == record.message_id:
                raise ContractError("ID do relatório igual ao ID do prompt")
            if item.completed_at < record.created_at:
                raise ContractError("relatório anterior à missão")
            if item.operation_id is not None and item.operation_id != record.mission.operation_id:
                raise ContractError("relatório de operação divergente")
            assessment = assess_report(record.mission, item)
            if assessment.classification == "divergent":
                raise ContractError("relatório divergente da missão")
            if item.message_id in seen and seen[item.message_id] != fingerprint:
                raise ContractError("resultado duplicado e divergente")
            seen[item.message_id] = fingerprint
            if record.report:
                previous = record.report
                if item.message_id == previous.message_id and fingerprint != previous.fingerprint():
                    raise ContractError("relatório alterado com o mesmo message_id")
                if record.state in {OperationState.COMPLETED, OperationState.FAILED}:
                    if item.message_id != previous.message_id:
                        raise ContractError("relatório contraditório após estado terminal")
                    continue
                if previous.status == "COMPLETED" and item.status != "COMPLETED":
                    raise ContractError("relatório contraditório após conclusão")
                if previous.status == "FAILED" and item.status == "COMPLETED":
                    raise ContractError("relatório contraditório após falha")
                if item.completed_at < previous.completed_at:
                    continue
            if fingerprint == (record.report.fingerprint() if record.report else None):
                continue
            if item.operation_id is None:
                continue
            prepared.append((item, fingerprint, assessment))
        prepared.sort(key=lambda row: (row[0].completed_at, row[0].status == "COMPLETED"))
        return prepared

    def _apply_report_batch(self, record: OperationRecord,
                            prepared: list[tuple[MissionReport, str, ReportAssessment]]) -> None:
        for item, fingerprint, assessment in prepared:
            record.report = item
            record.assessment = assessment
            if assessment.classification == "completed":
                self._ensure_running(record)
                if record.state == OperationState.RUNNING:
                    record.transition(OperationState.COMPLETED, self.events, "mission_completed",
                                      {"message_id": item.message_id, "structural_verification": True})
            elif item.status == "FAILED" and record.state == OperationState.RUNNING:
                record.transition(OperationState.FAILED, self.events, "mission_failed",
                                  {"message_id": item.message_id})

    def receive_reports(self, operation_id: str, reports: tuple[MissionReport, ...]) -> OperationRecord:
        record = self.operations[operation_id]
        prepared = self._validate_report_batch(record, reports)
        self._apply_report_batch(record, prepared)
        return record

    def reconcile(self, operation_id: str) -> OperationRecord:
        """Reconcilia por evidência; nunca chama transmit."""
        return self.observe(operation_id)


def assess_report(mission: MissionEnvelope, report: MissionReport) -> ReportAssessment:
    try:
        report.validate()
    except ContractError as e:
        return ReportAssessment("candidate", False, False, (str(e),), False, False)
    if not all((report.project_id, report.mission_id, report.agent_id, report.session_id, report.message_id)):
        return ReportAssessment("candidate", False, False, ("identidade ausente",), False, False)
    if (report.project_id != mission.project_id or report.mission_id != mission.mission_id
            or report.agent_id != mission.target_agent or report.session_id != mission.target_session_id):
        return ReportAssessment("divergent", False, False, ("identidade divergente",), True, False)
    if report.operation_id is not None and report.operation_id != mission.operation_id:
        return ReportAssessment("divergent", False, False, ("operation_id divergente",), True, False)
    if report.completed_at < mission.created_at:
        return ReportAssessment("stale", False, False, ("relatório anterior à missão",), True, False)
    if mission.expected_head_sha and report.head_sha != mission.expected_head_sha:
        return ReportAssessment("divergent", False, False, ("HEAD divergente",), True, True)
    if mission.expected_branch and report.branch != mission.expected_branch:
        return ReportAssessment("divergent", False, False, ("branch divergente",), True, True)
    missing = [criterion for criterion in mission.acceptance_criteria
               if report.acceptance_results.get(criterion) is not True]
    evidence_ok = not missing and bool(report.test_evidence)
    declared_conclusion = report.status == "COMPLETED"
    if report.status != "COMPLETED":
        if evidence_ok:
            return ReportAssessment("verified", True, False, (f"status={report.status}",),
                                    True, True, True, False, False, False)
        return ReportAssessment("associated", False, False, (f"status={report.status}",),
                                True, True, False, False, False, False)
    if not evidence_ok:
        return ReportAssessment("incomplete", False, False,
                                ("critérios/evidência incompletos", tuple(missing)),
                                True, True, bool(report.test_evidence), True, False, False)
    return ReportAssessment("completed", True, True,
                            ("estrutura/identidade/critérios verificados; qualidade não certificada",),
                            True, True, True, True, False, False)
