#!/usr/bin/env python3
"""Adaptador OpenCode restrito e persistência mínima para o piloto COORD-04/P3.

Inativo por padrão: nenhum cliente real ou escrita HTTP é criado sem ativação
explícita. Esta versão prepara o circuito; não transmite nesta missão.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from abc import ABC, abstractmethod
from contextlib import contextmanager
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coord_checkpoint as coord_checkpoint
import coord_communication as cc

INFO_PATH = "/api/info"
LOCATION_PATH = "/api/location"
ACTIVE_PATH = "/api/session/active"
INBOX_SUFFIX = "/inbox"
PROMPT_SUFFIX = "/prompt"
MESSAGE_SUFFIX = "/message"
DEFAULT_PILOT_DIR = os.path.expanduser("~/.local/state/better-talker/coord-pilot")
PILOT_TRANSITIONS = {
    "AUTHORIZED": {"DISPATCHING", "UNKNOWN", "FAILED"},
    "DISPATCHING": {"UNKNOWN", "FAILED", "DELIVERED", "RUNNING", "COMPLETED"},
    "DELIVERED": {"RUNNING", "FAILED", "UNKNOWN"},
    "RUNNING": {"COMPLETED", "FAILED", "UNKNOWN"},
    "UNKNOWN": {"RUNNING", "COMPLETED", "FAILED"},
    "COMPLETED": set(),
    "FAILED": set(),
}
ALLOWED_METHODS = {"GET", "POST"}
ALLOWED_PATH_PREFIXES = ("/api/",)


class HttpClient(ABC):
    """Cliente mínimo; implementações reais são injetadas e inativas por padrão."""

    @abstractmethod
    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        raise NotImplementedError


class DisabledHttpClient(HttpClient):
    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        raise cc.TransportDisabled("cliente HTTP real desativado")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "redirecionamento proibido", headers, fp)


class LocalHttpClient(HttpClient):
    """Cliente opcional, restrito a base local fixa e caminhos /api/."""

    def __init__(self, base_url: str, *, allowed_port: int, allow_write: bool = False):
        parsed = urllib.parse.urlparse(base_url)
        if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.username
                or parsed.password or parsed.fragment or parsed.query or parsed.params):
            raise cc.ContractError("base_url inválida")
        try:
            port = parsed.port
        except ValueError as e:
            raise cc.ContractError("porta inválida") from e
        if port is None or port != allowed_port or not 1 <= port <= 65535:
            raise cc.ContractError("porta não autorizada")
        if parsed.path not in {"", "/"}:
            raise cc.ContractError("base_url não pode conter caminho adicional")
        self.base_url = f"http://127.0.0.1:{port}"
        self.allowed_port = port
        self.allow_write = allow_write
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)

    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        method = method.upper()
        if method not in ALLOWED_METHODS:
            raise cc.ContractError("método HTTP não permitido")
        if not path.startswith(ALLOWED_PATH_PREFIXES):
            raise cc.ContractError("caminho HTTP não permitido")
        if method == "POST" and not self.allow_write:
            raise cc.TransportDisabled("escrita HTTP desativada")
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data,
                                         headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=10) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as e:
            raw = e.read()
            status = e.code
        text = raw.decode("utf-8", errors="replace").strip()
        payload = json.loads(text) if text else {}
        if not isinstance(payload, dict):
            raise cc.ContractError("resposta HTTP inválida")
        return status, payload


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def instance_identity(info: dict[str, Any]) -> str:
    material = {"version": info.get("version"), "pid": info.get("pid"),
                "urls": info.get("urls"), "hostname": info.get("hostname")}
    return hashlib.sha256(cc.canonical_json(material).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class PilotAuthorization:
    """Contrato de autorização experimental; não é emitida nesta missão."""

    authorization_id: str
    maintainer_id: str
    approval_reference: str
    project_id: str
    repository_id: str
    instance_id: str
    target: cc.TargetIdentity
    mission_id: str
    mission_identity: str
    operation_id: str
    prompt_sha256: str
    scope: str
    issued_at: datetime
    expires_at: datetime
    approved: bool = False
    environment: str = "PROPOSED"
    max_transmissions: int = 1
    expected_model: str = "opencode-go/hungerg-free#default"

    def validate(self) -> None:
        self.target.validate()
        for value, label in ((self.authorization_id, "authorization_id"),
                             (self.maintainer_id, "maintainer_id"),
                             (self.approval_reference, "approval_reference"),
                             (self.project_id, "project_id"),
                             (self.repository_id, "repository_id"),
                             (self.instance_id, "instance_id"),
                             (self.mission_id, "mission_id"),
                             (self.operation_id, "operation_id"),
                             (self.scope, "scope"),
                             (self.expected_model, "expected_model")):
            if not isinstance(value, str) or not value.strip():
                raise cc.ContractError(f"{label} ausente")
        if not cc.SHA256_PATTERN.fullmatch(self.prompt_sha256):
            raise cc.ContractError("hash do prompt inválido")
        if not cc.SHA256_PATTERN.fullmatch(self.mission_identity):
            raise cc.ContractError("identidade da missão inválida")
        cc._require_aware(self.issued_at, "issued_at")
        cc._require_aware(self.expires_at, "expires_at")
        if self.expires_at <= self.issued_at:
            raise cc.ContractError("validade da autorização inválida")
        if self.max_transmissions != 1:
            raise cc.ContractError("piloto permite no máximo uma transmissão")
        if self.maintainer_id.lower() in {"supervisor", "agent-s", "agente s"}:
            raise cc.ContractError("supervisor não pode emitir sua própria autorização")

    def validate_for_pilot(self, clock: Optional[datetime] = None, *, require_real: bool = False) -> None:
        self.validate()
        if not self.approved:
            raise cc.AuthorizationError("autorização experimental não aprovada")
        if self.environment in {"PROPOSED", "SYNTHETIC"}:
            raise cc.AuthorizationError("autorização proposta/sintética não habilita transmissão")
        if require_real:
            if self.environment != "APPROVED":
                raise cc.AuthorizationError("HTTP real exige autorização APPROVED")
            if self.maintainer_id.lower() in {"fixture", "fake", "test", "simulado"}:
                raise cc.AuthorizationError("aprovador fixtures não habilita HTTP real")
        elif self.environment not in {"FIXTURE", "APPROVED"}:
            raise cc.AuthorizationError("autorização não utilizável no transporte de teste")
        current = clock or cc.utc_now()
        if current >= self.expires_at:
            raise cc.AuthorizationError("autorização expirada")
        if current < self.issued_at:
            raise cc.AuthorizationError("autorização futura")

    def canonical(self) -> str:
        material = {
            "authorization_id": self.authorization_id,
            "maintainer_id": self.maintainer_id,
            "approval_reference": self.approval_reference,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "instance_id": self.instance_id,
            "target": list(self.target.key()),
            "mission_id": self.mission_id,
            "mission_identity": self.mission_identity,
            "operation_id": self.operation_id,
            "prompt_sha256": self.prompt_sha256,
            "scope": self.scope,
            "issued_at": cc.iso(self.issued_at),
            "expires_at": cc.iso(self.expires_at),
            "approved": self.approved,
            "environment": self.environment,
            "max_transmissions": self.max_transmissions,
            "expected_model": self.expected_model,
        }
        return hashlib.sha256(cc.canonical_json(material).encode("utf-8")).hexdigest()

    def proposal(self) -> dict[str, Any]:
        return {
            "authorization_id": self.authorization_id,
            "maintainer_id": self.maintainer_id,
            "approval_reference": self.approval_reference,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "instance_id": self.instance_id,
            "target": list(self.target.key()),
            "mission_id": self.mission_id,
            "mission_identity": self.mission_identity,
            "operation_id": self.operation_id,
            "prompt_sha256": self.prompt_sha256,
            "scope": self.scope,
            "issued_at": cc.iso(self.issued_at),
            "expires_at": cc.iso(self.expires_at),
            "approved": self.approved,
            "environment": self.environment,
            "max_transmissions": self.max_transmissions,
            "expected_model": self.expected_model,
            "canonical_sha256": self.canonical(),
        }


@dataclass(frozen=True)
class PilotApproval:
    """Aprovação humana explícita do manifesto exato; não é emitida pelo agente."""

    approval_id: str
    approver_id: str
    approver_type: str
    approval_reference: str
    reviewed_head_sha: str
    manifest_sha256: str
    operation_id: str
    session_id: str
    prompt_sha256: str
    issued_at: datetime
    expires_at: datetime
    environment: str = "PROPOSED"

    def validate(self) -> None:
        if self.approver_type != "human":
            raise cc.AuthorizationError("aprovação deve ser humana")
        if self.environment not in {"PROPOSED", "APPROVED"}:
            raise cc.AuthorizationError("ambiente de aprovação inválido")
        if not re.fullmatch(r"[0-9a-f]{64}", self.manifest_sha256):
            raise cc.ContractError("hash do manifesto inválido")
        if not cc.SHA256_PATTERN.fullmatch(self.prompt_sha256):
            raise cc.ContractError("hash do prompt inválido")
        if not re.fullmatch(r"[0-9a-f]{40,64}", self.reviewed_head_sha):
            raise cc.ContractError("HEAD revisado inválido")
        cc._require_aware(self.issued_at, "issued_at")
        cc._require_aware(self.expires_at, "expires_at")
        if self.expires_at <= self.issued_at:
            raise cc.ContractError("validade da aprovação inválida")

    def matches(self, mission: cc.MissionEnvelope, target: cc.TargetIdentity,
                prompt_sha256: str) -> bool:
        manifest = cc.canonical_json({
            "mission_identity": cc.canonical_mission_identity(mission),
            "operation_id": mission.operation_id,
            "session_id": target.session_id,
            "prompt_sha256": prompt_sha256,
        })
        return (self.operation_id == mission.operation_id
                and self.session_id == target.session_id
                and self.prompt_sha256 == prompt_sha256
                and self.manifest_sha256 == hashlib.sha256(manifest.encode("utf-8")).hexdigest())


def mission_report_from_dict(value: dict[str, Any]) -> Optional[cc.MissionReport]:
    try:
        completed = datetime.fromisoformat(str(value["completed_at"]).replace("Z", "+00:00"))
        return cc.MissionReport(
            schema_version=str(value["schema_version"]),
            project_id=str(value["project_id"]),
            mission_id=str(value["mission_id"]),
            operation_id=(str(value["operation_id"]) if value.get("operation_id") else None),
            agent_id=str(value["agent_id"]),
            session_id=str(value["session_id"]),
            message_id=str(value["message_id"]),
            status=str(value["status"]),
            head_sha=value.get("head_sha"),
            branch=value.get("branch"),
            pull_request=value.get("pull_request"),
            test_evidence=tuple(value.get("test_evidence", ())),
            blockers=tuple(value.get("blockers", ())),
            artifacts=tuple(value.get("artifacts", ())),
            completed_at=completed,
            acceptance_results=dict(value.get("acceptance_results", {})),
            untrusted_text=str(value.get("untrusted_text", "")),
        )
    except Exception:
        return None


def parse_pilot_report(text: str, operation_id: str) -> Optional[cc.MissionReport]:
    if coord_checkpoint.REPORT_CONTRACT not in text:
        return None
    for line in text.splitlines():
        if line.startswith("contract:"):
            try:
                value = json.loads(line.split(":", 1)[1])
            except Exception:
                continue
            if isinstance(value, dict) and value.get("operation_id") == operation_id:
                return mission_report_from_dict(value)
    return None


class OpenCodeTransport(cc.Transport):
    """Adaptador restrito; GET permitido, POST somente com allow_write explícito."""

    def __init__(self, client: HttpClient, *, expected_instance_id: str,
                 registered_targets: tuple[cc.TargetIdentity, ...],
                 pilot_authorization: PilotAuthorization,
                 allow_write: bool = False,
                 clock=None, mode: str = "fixture"):
        if mode not in {"fixture", "real"}:
            raise cc.ContractError("modo de transporte inválido")
        self.mode = mode
        self.is_real = mode == "real"
        self.last_receipt: Optional[cc.TransportReceipt] = None
        self.client = client
        self.expected_instance_id = expected_instance_id
        self.registered = {target.key(): target for target in registered_targets}
        self.pilot = pilot_authorization
        self.pilot.validate()
        self.allow_write = allow_write
        self.clock = clock or cc.utc_now
        self.prepared_bodies: dict[str, dict[str, Any]] = {}
        self.calls: list[tuple[str, str]] = []

    def _get(self, path: str) -> dict[str, Any]:
        self.calls.append(("GET", path))
        status, payload = self.client.request("GET", path)
        if status != 200:
            raise cc.TransportError(f"GET falhou: {status}", phase="pre_transmit")
        return payload

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(("POST", path))
        if not self.allow_write:
            raise cc.TransportDisabled("escrita HTTP desativada")
        status, payload = self.client.request("POST", path, body)
        if status not in {200, 201, 202}:
            raise cc.TransportError(f"POST falhou: {status}", phase="during_transmit")
        return payload

    def _confirm_instance(self) -> None:
        info = self._get(INFO_PATH)
        instance_id = hashlib.sha256(cc.canonical_json({
            "version": info.get("version"), "pid": info.get("pid"),
            "urls": info.get("urls"), "hostname": info.get("hostname"),
        }).encode("utf-8")).hexdigest()
        if instance_id != self.expected_instance_id:
            raise cc.TransportError("instância divergente", phase="pre_transmit")

    def _confirm_target(self, target: cc.TargetIdentity) -> None:
        if target != self.pilot.target or target.key() not in self.registered:
            raise cc.TransportError("destino não autorizado", phase="pre_transmit")
        location = self._get(LOCATION_PATH)
        project = location.get("project") or {}
        if project.get("id") != target.project_id or location.get("directory") != target.location:
            raise cc.TransportError("location/projeto divergente", phase="pre_transmit")
        session = (self._get(f"/api/session/{target.session_id}") or {}).get("data") or {}
        model = session.get("model") or {}
        model_ref = f"{model.get('providerID')}/{model.get('id')}" + (
            f"#{model.get('variant')}" if model.get("variant") else "")
        if (session.get("id") != target.session_id or session.get("projectID") != target.project_id
                or session.get("agent") != target.agent_id
                or (session.get("location") or {}).get("directory") != target.location
                or model_ref != self.pilot.expected_model):
            raise cc.TransportError("sessão/modelo divergente", phase="pre_transmit")

    def lookup_target(self, project_id: str, agent_id: str, session_id: str) -> cc.TargetIdentity:
        target = self.pilot.target
        if (project_id, agent_id, session_id) != (target.project_id, target.agent_id, target.session_id):
            raise cc.TransportError("destino diferente do autorizado", phase="pre_transmit")
        self._confirm_instance()
        self._confirm_target(target)
        return target

    def status(self, target: cc.TargetIdentity) -> cc.SessionStatus:
        self._confirm_target(target)
        active = (self._get(ACTIVE_PATH) or {}).get("data") or {}
        if target.session_id in active:
            return cc.SessionStatus(target, "busy", 0,
                                    {"active_observed": True, "availability_claimed": False})
        inbox = (self._get(f"/api/session/{target.session_id}{INBOX_SUFFIX}") or {}).get("data") or []
        session = (self._get(f"/api/session/{target.session_id}") or {}).get("data") or {}
        times = session.get("time") or {}
        idle_confirmed = isinstance(times.get("idle"), (int, float)) and times.get("idle", 0) >= times.get("busy", 0)
        state = "idle" if idle_confirmed else "unknown"
        return cc.SessionStatus(target, state, len(inbox),
                                {"active_observed": False, "availability_claimed": False,
                                 "idle_metadata": idle_confirmed, "source": "opencode-api"})

    def prepare(self, envelope: cc.MissionEnvelope, authorization: cc.Authorization) -> cc.PreparedRequest:
        self.pilot.validate_for_pilot(self.clock(), require_real=self.is_real)
        envelope.validate()
        if (envelope.mission_id != self.pilot.mission_id or
                envelope.operation_id != self.pilot.operation_id or
                cc.canonical_mission_identity(envelope) != self.pilot.mission_identity):
            raise cc.ContractError("missão diferente da autorizada")
        if envelope.project_id != self.pilot.project_id or envelope.repository_id != self.pilot.repository_id:
            raise cc.ContractError("projeto diferente do autorizado")
        if envelope.scope != self.pilot.scope:
            raise cc.ContractError("escopo diferente do autorizado")
        if envelope.target != self.pilot.target:
            raise cc.ContractError("destinatário diferente do autorizado")
        prompt = envelope.payload.get("prompt")
        if not isinstance(prompt, str) or sha256_text(prompt) != self.pilot.prompt_sha256:
            raise cc.ContractError("prompt diferente do autorizado")
        self._confirm_instance()
        self._confirm_target(envelope.target)
        request_id = f"msg-pilot-{envelope.operation_id}"
        body = {"id": request_id, "text": prompt,
                "metadata": {"operation_id": envelope.operation_id,
                             "mission_id": envelope.mission_id,
                             "authorization_id": self.pilot.authorization_id}}
        self.prepared_bodies[request_id] = body
        return cc.PreparedRequest(request_id, envelope, authorization, cc.utc_now())

    def transmit(self, request: cc.PreparedRequest) -> cc.TransportReceipt:
        body = self.prepared_bodies.get(request.request_id)
        if body is None:
            raise cc.ContractError("requisição preparada não encontrada")
        target = request.envelope.target
        path = f"/api/session/{target.session_id}{PROMPT_SUFFIX}"
        response = self._post(path, body)
        observed_id = (response.get("data") or {}).get("id")
        evidence = {"operation_id": request.envelope.operation_id,
                    "expected_message_id": request.request_id,
                    "observed_message_id": observed_id,
                    "payload_sha256": request.envelope.payload_sha256,
                    "project_id": request.envelope.project_id,
                    "repository_id": request.envelope.repository_id,
                    "agent_id": request.envelope.target_agent,
                    "session_id": request.envelope.target_session_id,
                    "instance_id": request.envelope.target_instance_id,
                    "location": request.envelope.target_location,
                    "http_status": 202, "source": "opencode-api"}
        if not isinstance(observed_id, str) or not observed_id:
            receipt = cc.TransportReceipt(request.envelope.operation_id, "ambiguous", None,
                                          {**evidence, "message_id_unavailable": True}, cc.utc_now())
        else:
            receipt = cc.TransportReceipt(request.envelope.operation_id, "accepted",
                                          observed_id, {**evidence, "queued_in_inbox": True},
                                          cc.utc_now())
        self.last_receipt = receipt
        return receipt

    def operation_status(self, operation_id: str) -> cc.OperationObservation:
        if operation_id != self.pilot.operation_id:
            raise cc.ContractError("operação não autorizada")
        target = self.pilot.target
        active = (self._get(ACTIVE_PATH) or {}).get("data") or {}
        inbox = (self._get(f"/api/session/{target.session_id}{INBOX_SUFFIX}") or {}).get("data") or []
        prompt_id = f"msg-pilot-{operation_id}"
        messages = (self._get(f"/api/session/{target.session_id}{MESSAGE_SUFFIX}?limit=20&order=desc") or {}).get("data") or []
        reports, recorded, queued = [], False, False
        for item in messages:
            item_id = item.get("id")
            text = "\n".join(p.get("text", "") for p in item.get("content", [])
                             if isinstance(p, dict) and p.get("type") == "text")
            if (item.get("type") == "user" and item_id == prompt_id
                    and sha256_text(text) == self.pilot.prompt_sha256):
                recorded = True
            if item.get("type") == "assistant" and item_id:
                parsed = parse_pilot_report(text, operation_id)
                if parsed:
                    reports.append(replace(
                        parsed, message_id=item_id,
                        agent_id=item.get("agent") or target.agent_id,
                        session_id=target.session_id,
                        test_evidence=({"name": "transporte observado", "message_id": item_id},)
                        + tuple(parsed.test_evidence)))
        for item in inbox:
            item_id = item.get("id")
            inbox_text = str(item.get("text") or "")
            if item_id == prompt_id and (not inbox_text or sha256_text(inbox_text) == self.pilot.prompt_sha256):
                queued = True
        evidence = {"source": "opencode-api", "active_observed": target.session_id in active,
                    "queue_observed": bool(inbox), "availability_claimed": False,
                    "prompt_content_confirmed": recorded, "prompt_id_expected": prompt_id}
        if reports:
            level, state, evidence["report_available"] = "report_received", "completed", True
        elif recorded and target.session_id in active:
            level, state, evidence["executing"] = "execution_started", "running", True
        elif recorded:
            level, state, evidence["recorded"] = "recorded_in_session", "queued", True
        elif queued:
            level, state = "queued_in_inbox", "queued"
        else:
            level, state = "not_observed", "not_observed"
        return cc.OperationObservation(operation_id, state, prompt_id if recorded or queued else None,
                                       tuple(reports), evidence, target, level, operation_id, None)

    def reports(self, operation_id: str) -> tuple[cc.MissionReport, ...]:
        if operation_id != self.pilot.operation_id:
            raise cc.ContractError("operação não autorizada")
        return self.operation_status(operation_id).reports


@dataclass
class PilotOperationRecord:
    mission_id: str
    operation_id: str
    authorization_id: str
    authorization_canonical: str
    target_key: tuple[str, ...]
    prompt_sha256: str
    state: str
    created_at: str
    updated_at: str
    evidence: dict[str, Any] = field(default_factory=dict)
    history: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {"schema_version": "1.0", "mission_id": self.mission_id,
                "operation_id": self.operation_id, "authorization_id": self.authorization_id,
                "authorization_canonical": self.authorization_canonical,
                "target_key": list(self.target_key), "prompt_sha256": self.prompt_sha256,
                "state": self.state, "created_at": self.created_at,
                "updated_at": self.updated_at, "evidence": self.evidence,
                "history": self.history}


def _record_from_dict(value: dict[str, Any]) -> PilotOperationRecord:
    return PilotOperationRecord(
        mission_id=value["mission_id"], operation_id=value["operation_id"],
        authorization_id=value["authorization_id"],
        authorization_canonical=value["authorization_canonical"],
        target_key=tuple(value["target_key"]), prompt_sha256=value["prompt_sha256"],
        state=value["state"], created_at=value["created_at"], updated_at=value["updated_at"],
        evidence=value.get("evidence", {}), history=value.get("history", []),
    )


class PilotOperationStore:
    """Persistência privada mínima para uma única operação experimental."""

    def __init__(self, directory: str = DEFAULT_PILOT_DIR):
        self.directory = os.path.abspath(directory)
        self.path = os.path.join(self.directory, "operation.json")
        self.lock_path = os.path.join(self.directory, "operation.lock")

    def _ensure_private_dir(self) -> None:
        os.makedirs(self.directory, mode=0o700, exist_ok=True)
        if os.path.realpath(self.directory) != self.directory:
            raise cc.ContractError("diretório do piloto contém symlink")
        st = os.lstat(self.directory)
        if not stat.S_ISDIR(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o700:
            raise cc.ContractError("diretório do piloto não é privado")

    def _write(self, record: PilotOperationRecord) -> None:
        self._ensure_private_dir()
        raw = json.dumps(record.to_dict(), sort_keys=True, ensure_ascii=False).encode("utf-8")
        fd, temporary = tempfile.mkstemp(prefix=".pilot-", dir=self.directory)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(raw)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary, self.path)
            dir_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def create(self, record: PilotOperationRecord) -> None:
        if os.path.lexists(self.path):
            raise cc.ContractError("operação experimental já registrada")
        self._write(record)

    def load(self) -> Optional[PilotOperationRecord]:
        if not os.path.lexists(self.path):
            return None
        if os.path.realpath(self.path) != self.path:
            raise cc.ContractError("registro do piloto contém symlink")
        st = os.lstat(self.path)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
            raise cc.ContractError("registro do piloto não é privado")
        fd = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(fd, "rb") as f:
            return _record_from_dict(json.loads(f.read().decode("utf-8")))

    def update(self, state: str, evidence: dict[str, Any]) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if state != record.state and state not in PILOT_TRANSITIONS.get(record.state, set()):
            raise cc.ContractError(f"transição proibida: {record.state} → {state}")
        record.state = state
        record.updated_at = cc.iso(cc.utc_now())
        record.evidence.update(evidence)
        record.history.append({"utc": record.updated_at, "state": state, "evidence": evidence})
        self._write(record)
        return record

    @contextmanager
    def reserve(self):
        self._ensure_private_dir()
        try:
            fd = os.open(self.lock_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError as e:
            raise cc.ContractError("reserva exclusiva já ativa") from e
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(json.dumps({"pid": os.getpid(), "created_utc": cc.iso(cc.utc_now())}))
            f.flush()
            os.fsync(f.fileno())
        try:
            yield
        finally:
            if os.path.lexists(self.lock_path):
                os.unlink(self.lock_path)

    def assert_transmittable(self) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"UNKNOWN", "DISPATCHING", "COMPLETED", "FAILED"}:
            raise cc.AuthorizationError(f"reenvio bloqueado em {record.state}")
        if record.state != "AUTHORIZED":
            raise cc.ContractError("operação não autorizada")
        return record

    def recover(self) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"DISPATCHING", "AUTHORIZED"}:
            return self.update("UNKNOWN", {"interruption_unknown": True,
                                           "automatic_resend": False})
        return record


class PilotExecutor:
    """Executa uma única operação experimental pelo caminho protegido."""

    def __init__(self, transport: OpenCodeTransport, store: PilotOperationStore,
                 approval: PilotApproval, *, allow_real: bool = False, clock=None):
        self.transport = transport
        self.store = store
        self.approval = approval
        self.allow_real = allow_real
        self.clock = clock or cc.utc_now

    def _validate(self, mission: cc.MissionEnvelope, authorization: cc.Authorization,
                  prompt: str) -> None:
        self.approval.validate()
        if self.clock() >= self.approval.expires_at:
            raise cc.AuthorizationError("aprovação expirada")
        if self.allow_real:
            if not self.transport.is_real or self.approval.environment != "APPROVED":
                raise cc.AuthorizationError("execução real exige aprovação e transporte real")
            if self.transport.pilot.environment != "APPROVED":
                raise cc.AuthorizationError("autorização FIXTURE não habilita HTTP real")
        elif self.transport.is_real:
            raise cc.AuthorizationError("transporte real requer allow_real explícito")
        if not self.approval.matches(mission, self.transport.pilot.target,
                                     pilot_sha256 := sha256_text(prompt)):
            raise cc.ContractError("manifesto aprovado diverge da operação")
        if pilot_sha256 != self.transport.pilot.prompt_sha256:
            raise cc.ContractError("prompt diverge da autorização")

    def execute(self, mission: cc.MissionEnvelope, authorization: cc.Authorization,
                prompt: str) -> dict[str, Any]:
        self._validate(mission, authorization, prompt)
        existing = self.store.load()
        if existing is None:
            self.store.create(PilotOperationRecord(
                mission.mission_id, mission.operation_id,
                self.approval.approval_id, self.transport.pilot.canonical(),
                tuple(self.transport.pilot.target.key()), sha256_text(prompt),
                "AUTHORIZED", cc.iso(self.clock()), cc.iso(self.clock())))
        else:
            if (existing.operation_id != mission.operation_id
                    or existing.authorization_canonical != self.transport.pilot.canonical()):
                raise cc.ContractError("registro persistente divergente")
        self.store.assert_transmittable()
        with self.store.reserve():
            self.store.assert_transmittable()
            # O piloto é textual e aprovado por hash exato; a autoridade vem de
            # PilotApproval + PilotAuthorization. Mantemos cinco guardas da fundação;
            # apenas "schema" é excetuado porque o contrato BT-COORD-REPORT:v1 exige
            # a chave schema_version no JSON do relatório.
            policy = cc.ProjectPolicy(mission.project_id, mission.repository_id,
                                      (mission.target_location,), (mission.target_agent,),
                                      (mission.target,),
                                      forbidden_scope_terms=("merge", "push main", "release",
                                                             "adb", "credential"),
                                      mode="SHADOW")
            engine = cc.CommunicationEngine(policy, self.transport, clock=self.clock)
            engine.propose(mission)
            engine.authorize(mission.operation_id, authorization)
            # DISPATCHING é persistido antes de qualquer POST.
            persisted = self.store.update("DISPATCHING", {"pre_transmit": True})
            if persisted.state != "DISPATCHING":
                raise cc.ContractError("persistência de DISPATCHING falhou")
            # Revalidação imediata antes da tentativa única.
            self._validate(mission, authorization, prompt)
            engine_record = engine.transmit(mission.operation_id)
            evidence = {"engine_state": engine_record.state.value,
                        "message_id": engine_record.message_id,
                        "receipt": self.transport.last_receipt.evidence
                        if self.transport.last_receipt else {},
                        "transmit_called": True}
            if engine_record.state == cc.OperationState.UNKNOWN:
                state = "UNKNOWN"
            elif engine_record.state == cc.OperationState.FAILED:
                state = "FAILED"
            else:
                state = "DISPATCHING"
            self.store.update(state, evidence)
            return {"executed": True, "state": state,
                    "receipt_confirmed": engine_record.message_id is not None,
                    "engine_events": len(engine.events.events)}

    def reconcile(self) -> dict[str, Any]:
        record = self.store.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"COMPLETED", "FAILED"}:
            return {"state": record.state, "changed": False}
        observation = self.transport.operation_status(record.operation_id)
        level = observation.evidence_level
        if observation.reports:
            state = "COMPLETED"
        elif level == "execution_started":
            state = "RUNNING"
        elif level == "recorded_in_session":
            state = "DELIVERED"
        elif record.state == "UNKNOWN":
            return {"state": "UNKNOWN", "changed": False, "reason": "sem evidência positiva"}
        else:
            return {"state": record.state, "changed": False}
        self.store.update(state, {"observation": observation.evidence,
                                  "automatic_resend": False})
        return {"state": state, "changed": True, "reports": len(observation.reports)}
