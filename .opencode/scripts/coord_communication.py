#!/usr/bin/env python3
"""Núcleo genérico de comunicação coordenada — P1 exclusivamente sintética.

Não contém transporte real, credenciais, rede, persistência ou regras específicas
do Better Talker. O motor é deliberadamente pequeno: contratos, autorização,
estados, reconciliação e eventos em memória.
"""
from __future__ import annotations

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
    def __init__(self, message: str, *, ambiguous: bool = False):
        super().__init__(message)
        self.ambiguous = ambiguous


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
        return f"<!-- {REPORT_SCHEMA_VERSION} -->\n" + canonical_json(self.to_dict())


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


@dataclass(frozen=True)
class ReportAssessment:
    classification: str
    verified: bool
    completed: bool
    reasons: tuple[str, ...]


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
        self.sessions = {target.key(): {"state": "idle", "inbox": []} for target in targets}
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
                             {"source": "synthetic"})

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
            raise TransportError("destinatário incorreto/inexistente")
        if outcome == "busy" or session["state"] == "busy" or session["inbox"]:
            raise TransportError("sessão ou inbox ocupado")
        if outcome == "pre_transmit_failure":
            raise TransportError("falha antes da transmissão")
        message_id = f"msg-{envelope.operation_id}"
        message = SyntheticMessage(message_id, envelope.operation_id, target,
                                   canonical_json(envelope.payload),
                                   {"operation_id": envelope.operation_id,
                                    "payload_sha256": envelope.payload_sha256})
        self.messages[message_id] = message
        session["inbox"].append(message_id)
        self.execution[envelope.operation_id] = "queued"
        if outcome in {"timeout_after_transmit", "lost_response"}:
            raise TransportError("resposta perdida após transmissão", ambiguous=True)
        if outcome == "partial":
            return TransportReceipt(envelope.operation_id, "ambiguous", None,
                                     {"stored": True, "message_id_not_observed": True}, utc_now())
        return TransportReceipt(envelope.operation_id, "accepted", message_id,
                                 {"stored": True, "source": "synthetic"}, utc_now())

    def operation_status(self, operation_id: str) -> OperationObservation:
        message_id = f"msg-{operation_id}"
        if message_id not in self.messages:
            return OperationObservation(operation_id, "not_observed", evidence={"source": "synthetic"})
        return OperationObservation(operation_id, self.execution.get(operation_id, "queued"),
                                    message_id, self.reports(operation_id),
                                    {"source": "synthetic"})

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

    def simulate_start(self, operation_id: str) -> None:
        if f"msg-{operation_id}" not in self.messages:
            raise TransportError("operação não observada")
        self.execution[operation_id] = "running"

    def simulate_report(self, operation_id: str, report: MissionReport) -> None:
        self.report_store[operation_id] = report
        self.execution[operation_id] = "completed" if report.status == "COMPLETED" else "failed"


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
        if self.clock() >= auth.expires_at:
            raise AuthorizationError("autorização expirada")
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
        record = OperationRecord(mission)
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

    def transmit(self, operation_id: str) -> OperationRecord:
        record = self.operations[operation_id]
        if record.state == OperationState.UNKNOWN:
            raise AuthorizationError("REENVIO BLOQUEADO: operação UNKNOWN")
        if record.state != OperationState.AUTHORIZED or record.authorization is None:
            raise ContractError("operação não autorizada")
        self._validate_authorization(record.mission, record.authorization)
        target = self.transport.lookup_target(record.mission.project_id, record.mission.target_agent,
                                              record.mission.target_session_id)
        if target != record.mission.target:
            raise AuthorizationError("identidade observada diverge do envelope")
        status = self.transport.status(target)
        if status.state != "idle" or status.inbox_count:
            record.transition(OperationState.FAILED, self.events, "rejected_before_send",
                              {"reason": "sessão/inbox ocupado", "status": status.state})
            return record
        record.transition(OperationState.DISPATCHING, self.events, "dispatch_started",
                          {"request_prepared": False})
        try:
            request = self.transport.prepare(record.mission, record.authorization)
            receipt = self.transport.transmit(request)
        except TransportError as e:
            ambiguous = e.ambiguous or "após transmissão" in str(e)
            record.transition(OperationState.UNKNOWN if ambiguous else OperationState.FAILED,
                              self.events, "transport_error", {"error": str(e), "ambiguous": ambiguous})
            return record
        if receipt.outcome == "accepted" and receipt.message_id:
            record.message_id = receipt.message_id
            record.transition(OperationState.DELIVERED, self.events, "delivery_observed",
                              {"message_id": receipt.message_id, "evidence": receipt.evidence})
        elif receipt.outcome == "ambiguous":
            record.transition(OperationState.UNKNOWN, self.events, "ambiguous_receipt",
                              {"evidence": receipt.evidence})
        else:
            record.transition(OperationState.FAILED, self.events, "rejected",
                              {"evidence": receipt.evidence})
        return record

    def observe(self, operation_id: str) -> OperationRecord:
        record = self.operations[operation_id]
        observation = self.transport.operation_status(operation_id)
        if observation.message_id and record.message_id and observation.message_id != record.message_id:
            raise ContractError("message_id divergente")
        if observation.message_id:
            record.message_id = observation.message_id
        if record.state in {OperationState.DISPATCHING, OperationState.UNKNOWN}:
            if observation.state != "not_observed":
                record.transition(OperationState.DELIVERED, self.events, "reconciled_delivery",
                                  {"observation": observation.state})
        if record.state == OperationState.DELIVERED and observation.state in {"running", "completed"}:
            record.transition(OperationState.RUNNING, self.events, "execution_started",
                              {"observation": observation.state})
        if observation.state == "failed" and record.state in {OperationState.RUNNING, OperationState.UNKNOWN}:
            record.transition(OperationState.FAILED, self.events, "execution_failed",
                              {"observation": observation.state})
        if observation.reports:
            self.receive_reports(operation_id, observation.reports)
        return record

    def receive_reports(self, operation_id: str, reports: tuple[MissionReport, ...]) -> OperationRecord:
        record = self.operations[operation_id]
        seen: dict[str, str] = {}
        for report in reports:
            report.validate()
            fingerprint = report.fingerprint()
            if report.message_id in seen:
                if seen[report.message_id] == fingerprint:
                    continue
                raise ContractError("resultado duplicado e divergente")
            seen[report.message_id] = fingerprint
            assessment = assess_report(record.mission, report)
            if assessment.classification == "divergent":
                raise ContractError("relatório divergente da missão")
            if record.report and record.report.fingerprint() != fingerprint:
                raise ContractError("relatório divergente do já associado")
            record.report = report
            record.assessment = assessment
            if assessment.classification == "completed":
                record.transition(OperationState.COMPLETED, self.events, "mission_completed",
                                  {"message_id": report.message_id, "verified": True})
            elif report.status == "FAILED" and record.state == OperationState.RUNNING:
                record.transition(OperationState.FAILED, self.events, "mission_failed",
                                  {"message_id": report.message_id})
        return record

    def reconcile(self, operation_id: str) -> OperationRecord:
        """Reconcilia por evidência; nunca chama transmit."""
        return self.observe(operation_id)


def assess_report(mission: MissionEnvelope, report: MissionReport) -> ReportAssessment:
    try:
        report.validate()
    except ContractError as e:
        return ReportAssessment("candidate", False, False, (str(e),))
    if not all((report.project_id, report.mission_id, report.agent_id, report.session_id, report.message_id)):
        return ReportAssessment("candidate", False, False, ("identidade ausente",))
    if (report.project_id != mission.project_id or report.mission_id != mission.mission_id
            or report.agent_id != mission.target_agent or report.session_id != mission.target_session_id):
        return ReportAssessment("divergent", False, False, ("identidade divergente",))
    if report.operation_id is not None and report.operation_id != mission.operation_id:
        return ReportAssessment("divergent", False, False, ("operation_id divergente",))
    if report.completed_at < mission.created_at:
        return ReportAssessment("stale", False, False, ("relatório anterior à missão",))
    if mission.expected_head_sha and report.head_sha != mission.expected_head_sha:
        return ReportAssessment("divergent", False, False, ("HEAD divergente",))
    if mission.expected_branch and report.branch != mission.expected_branch:
        return ReportAssessment("divergent", False, False, ("branch divergente",))
    missing = [criterion for criterion in mission.acceptance_criteria
               if report.acceptance_results.get(criterion) is not True]
    evidence_ok = not missing and bool(report.test_evidence)
    if report.status != "COMPLETED":
        if evidence_ok:
            return ReportAssessment("verified", True, False, (f"status={report.status}",))
        return ReportAssessment("associated", False, False, (f"status={report.status}",))
    if not evidence_ok:
        return ReportAssessment("incomplete", False, False,
                                 ("critérios/evidência incompletos", tuple(missing)))
    return ReportAssessment("completed", True, True, ("relatório associado e verificado",))
