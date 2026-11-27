#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P1 — serviço local protegido (fundação sintética).

O serviço é o único processo que abre o ledger 1.1 e o trust store. Consumidores
locais falam com ele por um Unix domain socket; a identidade vem de
`SO_PEERCRED` + hash do executável via `/proc`, nunca de um `consumer_id`
autodeclarado na requisição.

O transporte real é desabilitado por padrão. Não há TCP, HTTP, LLM, credencial,
serviço instalado, usuário criado ou migração operacional. Chaves, ledger,
trust store e evidências desta rodada são sintéticos.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import socket
import sqlite3
import struct
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger as ledger
import coord_ledger_authority as cla
import coord_p14b_authority as p14b_authority

PROTOCOL = "BT-COORD-TRUST-SERVICE-V1"
SERVICE_SCHEMA = "BT-COORD-TRUST-SERVICE-CONFIG-V1"
CONSUMER_SCHEMA = "BT-COORD-CONSUMER-REGISTRY-V1"
TRUST_SCHEMA = "BT-COORD-TRUST-STORE-V1"
MAX_FRAME_BYTES = 1024 * 1024
ALLOWED_ENVIRONMENTS = frozenset({"SHADOW"})
KNOWN_ACTIONS = frozenset({
    "propose", "authorize", "reserve", "assert", "record_evidence",
    "reconcile", "revoke", "interrupt", "status", "transmit",
})
FORBIDDEN_REQUEST_KEYS = frozenset({
    "consumer_id", "uid", "gid", "role", "allowed_actions", "permissions",
    "trust_store", "ledger_path", "state_dir", "service_uid", "real_transport",
    "authorization_grant", "eligible",
})
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SESSION = re.compile(r"^ses[-_][A-Za-z0-9._:-]+$")


class ServiceError(RuntimeError):
    """Falha de protocolo ou estado do serviço; mensagem não expõe paths."""

    code = "SERVICE_ERROR"


class ServiceConfigurationError(ServiceError):
    code = "CONFIGURATION_ERROR"


class StorageSecurityError(ServiceError):
    code = "STORAGE_SECURITY"


class AuthenticationError(ServiceError):
    code = "UNAUTHENTICATED_PEER"


class PermissionDenied(ServiceError):
    code = "PERMISSION_DENIED"


class RequestReplay(ServiceError):
    code = "REQUEST_REPLAY"


class TransportDisabled(ServiceError):
    code = "TRANSPORT_DISABLED"


class BadRequest(ServiceError):
    code = "BAD_REQUEST"


class NotFound(ServiceError):
    code = "NOT_FOUND"


class ServiceConflict(ServiceError):
    code = "CONFLICT"


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _iso(value: datetime) -> str:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ServiceConfigurationError("datetime com fuso obrigatório")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _parse_dt(value: Any) -> datetime:
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, str) and value.strip():
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise BadRequest("datetime inválido") from exc
    else:
        raise BadRequest("datetime obrigatório")
    if parsed.tzinfo is None:
        raise BadRequest("datetime sem fuso")
    return parsed.astimezone(timezone.utc)


def _b64decode(value: Any, label: str) -> bytes:
    if not isinstance(value, str) or not value:
        raise BadRequest(f"{label}: base64 obrigatório")
    try:
        result = base64.b64decode(value.encode("ascii"), validate=True)
    except (ValueError, UnicodeEncodeError) as exc:
        raise BadRequest(f"{label}: base64 inválido") from exc
    if not result:
        raise BadRequest(f"{label}: assinatura vazia")
    return result


def _ident(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise BadRequest(f"{label}: identificador inválido")
    return value


def _digest(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise BadRequest(f"{label}: sha256 inválido")
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BadRequest(f"{label}: texto obrigatório")
    return value.strip()


def executable_sha256(path: str) -> str:
    try:
        with open(path, "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except OSError as exc:
        raise AuthenticationError("executável do consumidor inacessível") from exc


def _proc_start_time(pid: int) -> int:
    try:
        with open(f"/proc/{pid}/stat", encoding="utf-8") as handle:
            stat = handle.read()
    except OSError as exc:
        raise AuthenticationError("processo do consumidor inacessível") from exc
    # comm pode conter espaços; os campos após o último ')' são estáveis.
    try:
        tail = stat.rsplit(")", 1)[1].split()
        return int(tail[19])  # campo 22 de /proc/pid/stat (starttime)
    except (IndexError, ValueError) as exc:
        raise AuthenticationError("starttime do consumidor inválido") from exc


@dataclass(frozen=True)
class PeerIdentity:
    """Identidade derivada do kernel, não de campos da requisição."""

    pid: int
    uid: int
    gid: int
    start_time: int
    exe_path: str
    exe_sha256: str
    source: str = "SO_PEERCRED"

    def validate(self) -> None:
        if self.source != "SO_PEERCRED":
            raise AuthenticationError("identidade do consumidor não é kernel")
        if self.pid <= 0 or self.uid < 0 or self.gid < 0:
            raise AuthenticationError("credenciais de peer inválidas")
        if not self.exe_path.startswith("/"):
            raise AuthenticationError("executável do consumidor inválido")
        _digest(self.exe_sha256, "exe_sha256")


def peer_identity_from_socket(conn: socket.socket) -> PeerIdentity:
    """Lê SO_PEERCRED + starttime + hash do executável; falha fechado."""
    try:
        raw = conn.getsockopt(
            socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
        pid, uid, gid = struct.unpack("3i", raw)
    except (AttributeError, OSError, struct.error) as exc:
        raise AuthenticationError("SO_PEERCRED indisponível") from exc
    try:
        exe_path = os.readlink(f"/proc/{pid}/exe")
    except OSError as exc:
        raise AuthenticationError("executável do consumidor inacessível") from exc
    if exe_path.endswith(" (deleted)"):
        exe_path = exe_path[:-10]
    peer = PeerIdentity(
        pid=pid, uid=uid, gid=gid, start_time=_proc_start_time(pid),
        exe_path=exe_path, exe_sha256=executable_sha256(exe_path))
    peer.validate()
    return peer


@dataclass(frozen=True)
class ConsumerPolicy:
    consumer_id: str
    uid: int
    exe_sha256: str
    allowed_actions: frozenset[str]
    agent_id: Optional[str] = None
    role: str = "consumer"

    def validate(self) -> None:
        _ident(self.consumer_id, "consumer_id")
        if self.uid < 0:
            raise ServiceConfigurationError("uid inválido")
        _digest(self.exe_sha256, "exe_sha256")
        unknown = set(self.allowed_actions) - KNOWN_ACTIONS
        if unknown:
            raise ServiceConfigurationError(
                "ação desconhecida: " + ", ".join(sorted(unknown)))
        if not self.allowed_actions:
            raise ServiceConfigurationError("consumer sem ações permitidas")
        if self.agent_id is not None:
            _ident(self.agent_id, "agent_id")
        _text(self.role, "role")

    def allows(self, action: str) -> bool:
        return action in self.allowed_actions


class ConsumerRegistry:
    """Trust store de consumidores mantido pelo serviço, nunca pelo agente."""

    def __init__(self, consumers: tuple[ConsumerPolicy, ...],
                 *, allow_same_uid: bool = False) -> None:
        if not consumers:
            raise ServiceConfigurationError("consumer registry vazio")
        seen = set()
        for consumer in consumers:
            consumer.validate()
            marker = (consumer.uid, consumer.exe_sha256)
            if marker in seen:
                raise ServiceConfigurationError("consumer duplicado")
            seen.add(marker)
        self.consumers = tuple(consumers)
        self.allow_same_uid = allow_same_uid

    def resolve(self, peer: PeerIdentity) -> ConsumerPolicy:
        peer.validate()
        for consumer in self.consumers:
            if consumer.uid == peer.uid and consumer.exe_sha256 == peer.exe_sha256:
                return consumer
        raise AuthenticationError("consumidor não autorizado")

    def validate_for_service(self, service_uid: int) -> None:
        if not self.allow_same_uid:
            for consumer in self.consumers:
                if consumer.uid == service_uid:
                    raise StorageSecurityError(
                        "consumidor e serviço não podem compartilhar UID")
        if not all(c.allowed_actions for c in self.consumers):
            raise ServiceConfigurationError("consumer sem ações")

    def to_mapping(self) -> dict[str, Any]:
        return {
            "schema": CONSUMER_SCHEMA,
            "consumers": [
                {
                    "consumer_id": c.consumer_id,
                    "uid": c.uid,
                    "exe_sha256": c.exe_sha256,
                    "allowed_actions": sorted(c.allowed_actions),
                    "agent_id": c.agent_id,
                    "role": c.role,
                }
                for c in sorted(self.consumers, key=lambda item: item.consumer_id)
            ],
        }

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any],
                     *, allow_same_uid: bool = False) -> "ConsumerRegistry":
        if value.get("schema") != CONSUMER_SCHEMA:
            raise ServiceConfigurationError("schema de consumers inválido")
        entries = value.get("consumers")
        if not isinstance(entries, list) or not entries:
            raise ServiceConfigurationError("consumers deve ser lista não vazia")
        result = []
        for entry in entries:
            if not isinstance(entry, Mapping):
                raise ServiceConfigurationError("consumer inválido")
            result.append(ConsumerPolicy(
                consumer_id=entry["consumer_id"], uid=int(entry["uid"]),
                exe_sha256=entry["exe_sha256"],
                allowed_actions=frozenset(entry["allowed_actions"]),
                agent_id=entry.get("agent_id"), role=entry.get("role", "consumer")))
        return cls(tuple(result), allow_same_uid=allow_same_uid)


class TrustStore:
    """Allowlist pública carregada de arquivo 0600 controlado pelo serviço."""

    def __init__(self, allowlist: cas.ApproverAllowlist) -> None:
        allowlist.validate()
        self.allowlist = allowlist

    @property
    def digest(self) -> str:
        return self.allowlist.digest()

    @classmethod
    def load(cls, path: str) -> "TrustStore":
        _require_private_file(path, "trust store")
        try:
            with open(path, encoding="utf-8") as handle:
                value = json.load(handle)
        except (OSError, ValueError) as exc:
            raise ServiceConfigurationError("trust store inválido") from exc
        if value.get("schema") != TRUST_SCHEMA:
            raise ServiceConfigurationError("schema do trust store inválido")
        entries = value.get("entries")
        if not isinstance(entries, list) or not entries:
            raise ServiceConfigurationError("trust store sem entradas")
        keys = []
        for entry in entries:
            if not isinstance(entry, Mapping):
                raise ServiceConfigurationError("entrada de trust store inválida")
            keys.append(cas.ApproverKey(
                approver_id=entry["approver_id"],
                fingerprint=entry["fingerprint"],
                public_key=entry["public_key"],
                valid_from=_parse_dt(entry["valid_from"]),
                valid_until=_parse_dt(entry["valid_until"]),
                revoked_at=None if entry.get("revoked_at") is None
                else _parse_dt(entry["revoked_at"])))
        try:
            return cls(cas.ApproverAllowlist(entries=tuple(keys)))
        except ValueError as exc:
            raise ServiceConfigurationError("trust store inválido") from exc


def write_trust_store(path: str, allowlist: cas.ApproverAllowlist) -> None:
    allowlist.validate()
    value = {
        "schema": TRUST_SCHEMA,
        "entries": [
            {
                "approver_id": entry.approver_id,
                "fingerprint": entry.fingerprint,
                "public_key": entry.public_key,
                "valid_from": _iso(entry.valid_from),
                "valid_until": _iso(entry.valid_until),
                "revoked_at": None if entry.revoked_at is None
                else _iso(entry.revoked_at),
            }
            for entry in sorted(allowlist.entries, key=lambda e: e.approver_id)
        ],
    }
    _write_private_json(path, value)


def write_consumer_registry(path: str, registry: ConsumerRegistry) -> None:
    _write_private_json(path, registry.to_mapping())


def _write_private_json(path: str, value: Mapping[str, Any]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.is_symlink():
        raise StorageSecurityError("arquivo de serviço não pode ser symlink")
    fd, temporary = tempfile_name(str(target))
    try:
        with os.fdopen(fd, "wb", closefd=True) as handle:
            handle.write((canonical_json(value) + "\n").encode("utf-8"))
            handle.flush()
            os.fsync(handle.fileno())
        fd = -1
        os.chmod(temporary, 0o600)
        os.replace(temporary, target)
    finally:
        if fd != -1:
            try:
                os.close(fd)
            except OSError:
                pass
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
    os.chmod(target, 0o600)


def tempfile_name(target: str) -> tuple[int, str]:
    # Nome temporário no mesmo diretório para `os.replace` atômico.
    directory = os.path.dirname(target) or "."
    for _ in range(20):
        candidate = os.path.join(
            directory, ".coord-trust-" + os.urandom(8).hex() + ".tmp")
        try:
            fd = os.open(candidate, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            return fd, candidate
        except FileExistsError:
            continue
    raise StorageSecurityError("não foi possível criar arquivo temporário")


def _require_private_file(path: str, label: str) -> None:
    target = Path(path)
    try:
        info = target.lstat()
    except OSError as exc:
        raise StorageSecurityError(f"{label} ausente") from exc
    if target.is_symlink() or not target.is_file():
        raise StorageSecurityError(f"{label} deve ser arquivo regular")
    if info.st_mode & 0o077:
        raise StorageSecurityError(f"{label} deve ser 0600")
    if info.st_uid != os.geteuid():
        raise StorageSecurityError(f"{label} deve pertencer ao serviço")


@dataclass(frozen=True)
class ServiceConfig:
    state_dir: str
    project_id: str
    repository_id: str
    service_uid: int
    service_gid: int
    trust_store_path: str
    consumer_registry_path: str
    socket_path: str
    allow_same_uid: bool = False
    real_transport: bool = False
    max_frame_bytes: int = MAX_FRAME_BYTES

    def __post_init__(self) -> None:
        for field in ("state_dir", "trust_store_path",
                      "consumer_registry_path", "socket_path"):
            object.__setattr__(self, field, os.path.abspath(getattr(self, field)))

    @property
    def ledger_path(self) -> str:
        return os.path.join(self.state_dir, "ledger.db")

    @property
    def service_db_path(self) -> str:
        return os.path.join(self.state_dir, "service.db")

    def prepare(self) -> None:
        if self.real_transport:
            raise TransportDisabled("transporte real desabilitado")
        if self.service_uid != os.geteuid() or self.service_gid != os.getegid():
            raise StorageSecurityError("processo não roda sob o UID/GID do serviço")
        _ident(self.project_id, "project_id")
        _ident(self.repository_id, "repository_id")
        state = Path(self.state_dir)
        if state.exists() and state.is_symlink():
            raise StorageSecurityError("state_dir não pode ser symlink")
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        if state.stat().st_uid != os.geteuid():
            raise StorageSecurityError("state_dir deve pertencer ao serviço")
        if state.stat().st_mode & 0o077:
            raise StorageSecurityError("state_dir deve ser 0700")
        for label, path in (
            ("ledger", self.ledger_path),
            ("service db", self.service_db_path),
            ("trust store", self.trust_store_path),
            ("consumer registry", self.consumer_registry_path),
        ):
            candidate = Path(path)
            if candidate.exists() and candidate.is_symlink():
                raise StorageSecurityError(f"{label} não pode ser symlink")
            if candidate.parent != state and state not in candidate.parents:
                raise StorageSecurityError(f"{label} deve ficar no state_dir")
        socket_path = Path(self.socket_path)
        if socket_path.exists() and socket_path.is_symlink():
            raise StorageSecurityError("socket não pode ser symlink")
        socket_parent = socket_path.parent
        socket_parent.mkdir(parents=True, exist_ok=True, mode=0o750)
        if socket_parent.stat().st_uid != os.geteuid():
            raise StorageSecurityError("diretório do socket deve pertencer ao serviço")
        if socket_parent.stat().st_mode & 0o002:
            raise StorageSecurityError("diretório do socket não pode ser world-writable")
        if socket_parent != state:
            if not socket_parent.stat().st_mode & 0o010:
                raise StorageSecurityError("diretório do socket exige group-execute")
            if socket_parent.stat().st_gid != self.service_gid:
                raise StorageSecurityError("grupo do socket deve ser o grupo do serviço")
        _require_private_file(self.trust_store_path, "trust store")
        _require_private_file(self.consumer_registry_path, "consumer registry")
        if self.max_frame_bytes <= 0 or self.max_frame_bytes > MAX_FRAME_BYTES:
            raise ServiceConfigurationError("max_frame_bytes inválido")

    def validate_consumers(self, registry: ConsumerRegistry) -> None:
        registry.validate_for_service(self.service_uid)
        if not self.allow_same_uid and any(
                consumer.uid == self.service_uid for consumer in registry.consumers):
            raise StorageSecurityError("consumidor e serviço compartilham UID")


class ServiceState:
    """Store SQLite privado do serviço: missões, replay e auditoria."""

    def __init__(self, path: str) -> None:
        self.path = path
        parent = Path(path).parent
        if parent.stat().st_mode & 0o077:
            raise StorageSecurityError("diretório do service state deve ser 0700")
        self._initialize()
        os.chmod(path, 0o600)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=30000")
        return conn

    @contextmanager
    def _read(self):
        conn = self._connect()
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def _write(self):
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            yield conn
            conn.execute("COMMIT")
        except Exception:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise
        finally:
            conn.close()
            self._harden()

    def _harden(self) -> None:
        for suffix in ("", "-wal", "-shm"):
            candidate = self.path + suffix
            if os.path.exists(candidate):
                os.chmod(candidate, 0o600)

    def _initialize(self) -> None:
        conn = self._connect()
        try:
            conn.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS service_missions (
                    mission_id TEXT PRIMARY KEY,
                    mission_json TEXT NOT NULL,
                    mission_sha256 TEXT NOT NULL,
                    consumer_id TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS service_requests (
                    request_id TEXT PRIMARY KEY,
                    consumer_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    response_json TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS service_audit (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    at TEXT NOT NULL,
                    consumer_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    detail_sha256 TEXT NOT NULL
                );
            """)
        finally:
            conn.close()
        self._harden()

    def put_mission(self, mission: cc.MissionEnvelope, consumer_id: str,
                    now: datetime) -> None:
        mission.validate()
        raw = canonical_json(_mission_mapping(mission))
        digest = sha256_text(raw)
        with self._write() as conn:
            existing = conn.execute(
                "SELECT mission_sha256 FROM service_missions WHERE mission_id=?",
                (mission.mission_id,)).fetchone()
            if existing is not None:
                if existing["mission_sha256"] != digest:
                    raise ServiceConflict("missão já registrada com outro material")
                return
            conn.execute(
                """INSERT INTO service_missions
                   (mission_id, mission_json, mission_sha256, consumer_id, created_at)
                   VALUES (?,?,?,?,?)""",
                (mission.mission_id, raw, digest, consumer_id, _iso(now)))

    def get_mission(self, mission_id: str) -> cc.MissionEnvelope:
        with self._read() as conn:
            row = conn.execute(
                "SELECT * FROM service_missions WHERE mission_id=?",
                (mission_id,)).fetchone()
        if row is None:
            raise NotFound("missão desconhecida")
        raw = row["mission_json"]
        if sha256_text(raw) != row["mission_sha256"]:
            raise StorageSecurityError("missão do serviço adulterada")
        try:
            value = json.loads(raw)
            mission = _mission_from_mapping(value)
            mission.validate()
        except (ValueError, TypeError, KeyError, ServiceError) as exc:
            raise StorageSecurityError("missão do serviço inválida") from exc
        return mission

    def begin_request(self, request_id: str, consumer_id: str, action: str,
                      request_sha256: str, now: datetime) -> Optional[Mapping[str, Any]]:
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM service_requests WHERE request_id=?",
                (request_id,)).fetchone()
            if row is not None:
                if (row["consumer_id"] != consumer_id
                        or row["action"] != action
                        or row["request_sha256"] != request_sha256):
                    raise RequestReplay("request_id reutilizado com outro material")
                if row["response_json"] is None:
                    raise RequestReplay("request_id ainda em processamento")
                return json.loads(row["response_json"])
            conn.execute(
                """INSERT INTO service_requests
                   (request_id, consumer_id, action, request_sha256,
                    response_json, created_at, updated_at)
                   VALUES (?,?,?,?,NULL,?,?)""",
                (request_id, consumer_id, action, request_sha256,
                 _iso(now), _iso(now)))
            return None

    def finish_request(self, request_id: str, result: Mapping[str, Any],
                       now: datetime) -> None:
        with self._write() as conn:
            conn.execute(
                "UPDATE service_requests SET response_json=?, updated_at=? "
                "WHERE request_id=?",
                (canonical_json(dict(result)), _iso(now), request_id))

    def abort_request(self, request_id: str) -> None:
        with self._write() as conn:
            conn.execute(
                "DELETE FROM service_requests WHERE request_id=? AND response_json IS NULL",
                (request_id,))

    def audit(self, consumer_id: str, action: str, outcome: str,
              detail: Mapping[str, Any], now: datetime) -> None:
        digest = sha256_text(canonical_json(dict(detail)))
        with self._write() as conn:
            conn.execute(
                """INSERT INTO service_audit
                   (at, consumer_id, action, outcome, detail_sha256)
                   VALUES (?,?,?,?,?)""",
                (_iso(now), consumer_id, action, outcome, digest))


class MissionRegistry:
    """MissionEnvelopeSource do adapter V2, apoiado no store do serviço."""

    def __init__(self, state: ServiceState) -> None:
        self.state = state

    def get(self, mission_id: str) -> cc.MissionEnvelope:
        return self.state.get_mission(mission_id)


def _mission_mapping(mission: cc.MissionEnvelope) -> dict[str, Any]:
    return {
        "schema_version": mission.schema_version,
        "project_id": mission.project_id,
        "repository_id": mission.repository_id,
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "coordinator_id": mission.coordinator_id,
        "target_agent": mission.target_agent,
        "target_session_id": mission.target_session_id,
        "target_instance_id": mission.target_instance_id,
        "target_location": mission.target_location,
        "objective": mission.objective,
        "payload": dict(mission.payload),
        "payload_sha256": mission.payload_sha256,
        "expected_deliverables": list(mission.expected_deliverables),
        "acceptance_criteria": list(mission.acceptance_criteria),
        "ownership_constraints": list(mission.ownership_constraints),
        "authorization_reference": mission.authorization_reference,
        "created_at": _iso(mission.created_at),
        "expires_at": _iso(mission.expires_at),
        "timeout": mission.timeout,
        "scope": mission.scope,
        "expected_head_sha": mission.expected_head_sha,
        "expected_branch": mission.expected_branch,
    }


def _mission_from_mapping(value: Mapping[str, Any]) -> cc.MissionEnvelope:
    if not isinstance(value, Mapping):
        raise BadRequest("mission deve ser mapping")
    required = {
        "schema_version", "project_id", "repository_id", "mission_id",
        "operation_id", "coordinator_id", "target_agent", "target_session_id",
        "target_instance_id", "target_location", "objective", "payload",
        "payload_sha256", "expected_deliverables", "acceptance_criteria",
        "ownership_constraints", "authorization_reference", "created_at",
        "expires_at", "timeout", "scope", "expected_head_sha", "expected_branch",
    }
    if set(value) != required:
        raise BadRequest("mission com campos inválidos")
    try:
        mission = cc.MissionEnvelope(
            schema_version=value["schema_version"],
            project_id=value["project_id"], repository_id=value["repository_id"],
            mission_id=value["mission_id"], operation_id=value["operation_id"],
            coordinator_id=value["coordinator_id"],
            target_agent=value["target_agent"],
            target_session_id=value["target_session_id"],
            target_instance_id=value["target_instance_id"],
            target_location=value["target_location"],
            objective=value["objective"], payload=dict(value["payload"]),
            payload_sha256=value["payload_sha256"],
            expected_deliverables=tuple(value["expected_deliverables"]),
            acceptance_criteria=tuple(value["acceptance_criteria"]),
            ownership_constraints=tuple(value["ownership_constraints"]),
            authorization_reference=value["authorization_reference"],
            created_at=_parse_dt(value["created_at"]),
            expires_at=_parse_dt(value["expires_at"]),
            timeout=int(value["timeout"]), scope=value["scope"],
            expected_head_sha=value.get("expected_head_sha"),
            expected_branch=value.get("expected_branch"))
        mission.validate()
        return mission
    except (KeyError, TypeError, ValueError, cc.ContractError) as exc:
        raise BadRequest("mission inválida") from exc


def _check_no_forbidden(value: Any, *, path: str = "request") -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if not isinstance(key, str):
                raise BadRequest(f"{path}: chave inválida")
            if key in FORBIDDEN_REQUEST_KEYS:
                raise PermissionDenied(f"{path}.{key}: campo proibido")
            _check_no_forbidden(child, path=f"{path}.{key}")
    elif isinstance(value, (list, tuple)):
        for index, child in enumerate(value):
            _check_no_forbidden(child, path=f"{path}[{index}]")


class TrustService:
    """Dispatcher protegido; único dono do ledger/trust store."""

    def __init__(
        self,
        *,
        config: ServiceConfig,
        ledger_db: ledger.Ledger,
        authority: p14b_authority.AuthorityAdapter,
        consumers: ConsumerRegistry,
        state: ServiceState,
        clock: Callable[[], datetime],
    ) -> None:
        self.config = config
        self.ledger = ledger_db
        self.authority = authority
        self.consumers = consumers
        self.state = state
        self.clock = clock

    def _now(self) -> datetime:
        value = self.clock()
        if not isinstance(value, datetime) or value.tzinfo is None:
            raise ServiceError("relógio do serviço inválido")
        return value.astimezone(timezone.utc)

    def _consumer(self, peer: PeerIdentity) -> ConsumerPolicy:
        return self.consumers.resolve(peer)

    def handle(self, peer: PeerIdentity, request: Mapping[str, Any]) -> dict[str, Any]:
        try:
            consumer = self._consumer(peer)
        except AuthenticationError:
            raise
        request_id = request.get("request_id") if isinstance(request, Mapping) else None
        try:
            if not isinstance(request, Mapping) or set(request) != {
                    "request_id", "action", "payload"}:
                raise BadRequest("requisição deve conter request_id/action/payload")
            request_id = _ident(request["request_id"], "request_id")
            action = _ident(request["action"], "action")
            payload = request["payload"]
            if not isinstance(payload, Mapping):
                raise BadRequest("payload deve ser mapping")
            if action not in KNOWN_ACTIONS:
                raise BadRequest("ação desconhecida")
            _check_no_forbidden(payload)
            if action == "transmit":
                raise TransportDisabled("transporte real desabilitado")
            if not consumer.allows(action):
                raise PermissionDenied(
                    f"consumidor {consumer.consumer_id} não pode executar {action}")
            request_sha = sha256_text(canonical_json(
                {"action": action, "payload": dict(payload)}))
            cached = self.state.begin_request(
                request_id, consumer.consumer_id, action, request_sha, self._now())
            if cached is not None:
                return {"ok": True, "request_id": request_id,
                        "result": dict(cached), "replayed": True}
            try:
                result = self._dispatch(consumer, action, payload)
            except Exception:
                self.state.abort_request(request_id)
                raise
            self.state.finish_request(request_id, result, self._now())
            self.state.audit(
                consumer.consumer_id, action, "ok", {"request_id": request_id},
                self._now())
            return {"ok": True, "request_id": request_id, "result": dict(result)}
        except ServiceError as exc:
            try:
                self.state.audit(
                    consumer.consumer_id,
                    str(request.get("action", "?")) if isinstance(request, Mapping) else "?",
                    "error", {"code": exc.code}, self._now())
            except Exception:
                pass
            return {"ok": False, "request_id": request_id,
                    "error": {"code": exc.code, "message": str(exc)}}
        except (ledger.LedgerError, cla.AuthorityContractError) as exc:
            try:
                self.state.audit(
                    consumer.consumer_id,
                    str(request.get("action", "?")) if isinstance(request, Mapping) else "?",
                    "rejected", {"type": type(exc).__name__}, self._now())
            except Exception:
                pass
            return {"ok": False, "request_id": request_id,
                    "error": {"code": "SERVICE_REJECTED",
                              "message": type(exc).__name__}}
        except Exception:
            try:
                self.state.audit(
                    consumer.consumer_id,
                    str(request.get("action", "?")) if isinstance(request, Mapping) else "?",
                    "error", {"type": "internal"}, self._now())
            except Exception:
                pass
            return {"ok": False, "request_id": request_id,
                    "error": {"code": "SERVICE_ERROR",
                              "message": "falha interna do serviço"}}

    def _dispatch(self, consumer: ConsumerPolicy, action: str,
                  payload: Mapping[str, Any]) -> dict[str, Any]:
        if action == "propose":
            return self._propose(consumer, payload)
        if action == "authorize":
            return self._authorize(consumer, payload)
        if action == "reserve":
            return self._reserve(consumer, payload)
        if action == "assert":
            return self._assert(consumer, payload)
        if action == "record_evidence":
            return self._record_evidence(consumer, payload)
        if action == "reconcile":
            return self._reconcile(consumer, payload)
        if action == "revoke":
            return self._revoke(consumer, payload)
        if action == "interrupt":
            return self._interrupt(consumer, payload)
        if action == "status":
            return self._status(consumer, payload)
        raise BadRequest("ação não implementada")

    def _record(self, mission_id: str) -> ledger.MissionRecord:
        record = self.ledger.get(mission_id)
        if record is None:
            raise NotFound("missão desconhecida")
        return record

    def _propose(self, consumer: ConsumerPolicy,
                  payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {
            "mission", "model", "environment", "operational_digests",
            "max_transmissions", "cost_ceiling_usd", "free_only",
            "authorization_reference",
        }
        if set(payload) != required:
            raise BadRequest("propose com campos inválidos")
        mission = _mission_from_mapping(payload["mission"])
        if (mission.project_id != self.config.project_id
                or mission.repository_id != self.config.repository_id):
            raise BadRequest("missão fora do escopo do serviço")
        if consumer.agent_id is not None and mission.target_agent != consumer.agent_id:
            raise PermissionDenied("consumidor não é o agente da missão")
        if payload["authorization_reference"] != mission.authorization_reference:
            raise BadRequest("authorization_reference divergente da missão")
        environment = payload["environment"]
        if environment not in ALLOWED_ENVIRONMENTS:
            raise BadRequest("ambiente deve ser SHADOW")
        model = _text(payload["model"], "model")
        digests = payload["operational_digests"]
        if not isinstance(digests, Mapping) or not digests:
            raise BadRequest("operational_digests inválido")
        head = mission.expected_head_sha
        if not isinstance(head, str):
            raise BadRequest("mission.expected_head_sha ausente")
        max_transmissions = payload["max_transmissions"]
        free_only = payload["free_only"]
        if (not isinstance(max_transmissions, int)
                or isinstance(max_transmissions, bool)):
            raise BadRequest("max_transmissions deve ser inteiro")
        if not isinstance(free_only, bool):
            raise BadRequest("free_only deve ser booleano")
        snapshot = cla.MissionSnapshot(
            mission_id=mission.mission_id,
            operation_id=mission.operation_id,
            project_id=mission.project_id,
            repository_id=mission.repository_id,
            coordinator_id=mission.coordinator_id,
            agent=mission.target_agent,
            session_id=mission.target_session_id,
            instance_id=mission.target_instance_id,
            worktree=mission.target_location,
            mission_identity=cc.canonical_mission_identity(mission),
            payload=dict(mission.payload),
            model=model,
            scope=mission.scope,
            acceptance_criteria=tuple(mission.acceptance_criteria),
            environment=environment,
            reviewed_head_sha=head,
            operational_digests=dict(digests),
            max_transmissions=max_transmissions,
            cost_ceiling_usd=payload["cost_ceiling_usd"],
            free_only=free_only,
        )
        snapshot.validate()
        self.ledger.register_signed_proposed(
            snapshot, authorization_reference=mission.authorization_reference)
        self.state.put_mission(mission, consumer.consumer_id, self._now())
        record = self._record(mission.mission_id)
        return {
            "mission_id": mission.mission_id,
            "state": record.state,
            "snapshot_sha256": snapshot.sha256(),
            "authority_material_sha256": snapshot.authority_material_sha256(),
        }

    def _authorize(self, consumer: ConsumerPolicy,
                   payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "envelope", "signature"}
        if set(payload) != required:
            raise BadRequest("authorize com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        mission = self.state.get_mission(mission_id)
        signature = _b64decode(payload["signature"], "signature")
        record = self.ledger.authorize_signed(
            mission_id, envelope=payload["envelope"], signature=signature,
            payload=dict(mission.payload), actor=consumer.consumer_id)
        return {
            "mission_id": mission_id, "state": record.state,
            "authorization_digest": record.authorization_digest,
        }

    def _target(self, snapshot: cla.MissionSnapshot) -> dict[str, Any]:
        return {
            "agent": snapshot.agent, "session_id": snapshot.session_id,
            "instance_id": snapshot.instance_id, "worktree": snapshot.worktree,
        }

    def _agent_mission(self, consumer: ConsumerPolicy, mission_id: str,
                       *, require_agent: bool = True) -> cc.MissionEnvelope:
        mission = self.state.get_mission(mission_id)
        if consumer.agent_id is None:
            if require_agent:
                raise PermissionDenied("consumidor sem agent_id vinculado")
            return mission
        if consumer.agent_id != mission.target_agent:
            raise PermissionDenied("consumidor não é o agente da missão")
        return mission

    def _check_target(self, consumer: ConsumerPolicy,
                      mission_id: str, payload: Mapping[str, Any]) -> None:
        if consumer.agent_id is None:
            raise PermissionDenied("consumidor sem agent_id vinculado")
        mission = self.state.get_mission(mission_id)
        expected = {
            "agent": mission.target_agent,
            "session_id": mission.target_session_id,
            "instance_id": mission.target_instance_id,
            "worktree": mission.target_location,
        }
        if any(payload.get(key) != value for key, value in expected.items()):
            raise PermissionDenied("target divergente do consumidor")
        if consumer.agent_id != mission.target_agent:
            raise PermissionDenied("consumidor não é o agente da missão")

    def _reserve(self, consumer: ConsumerPolicy,
                 payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "authorization_digest", "agent", "session_id",
                    "instance_id", "worktree"}
        if set(payload) != required:
            raise BadRequest("reserve com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        digest = _digest(payload["authorization_digest"], "authorization_digest")
        self._check_target(consumer, mission_id, payload)
        lease = self.ledger.reserve_dispatch_signed(
            mission_id, authorization_digest=digest,
            agent=payload["agent"], session_id=payload["session_id"],
            instance_id=payload["instance_id"], worktree=payload["worktree"],
            actor=consumer.consumer_id)
        record = self._record(mission_id)
        return {
            "mission_id": mission_id, "state": record.state,
            "authorization_digest": digest, "lease_id": lease.lease_id,
            "target": {
                "agent": lease.target_key[0], "session_id": lease.target_key[1],
                "worktree": lease.target_key[2], "instance_id": lease.target_key[3],
            },
        }

    def _assert(self, consumer: ConsumerPolicy,
                payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "authorization_digest", "lease_id"}
        if set(payload) != required:
            raise BadRequest("assert com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        digest = _digest(payload["authorization_digest"], "authorization_digest")
        lease_id = _ident(payload["lease_id"], "lease_id")
        self._agent_mission(consumer, mission_id)
        lease = self.ledger.assert_dispatchable(
            mission_id, authorization_digest=digest,
            lease_id=lease_id, actor=consumer.consumer_id)
        return {
            "mission_id": mission_id, "state": self._record(mission_id).state,
            "authorization_digest": digest, "lease_id": lease.lease_id,
        }

    def _record_evidence(self, consumer: ConsumerPolicy,
                         payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "authorization_digest", "lease_id",
                    "receipt", "evidence"}
        if set(payload) != required:
            raise BadRequest("record_evidence com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        digest = _digest(payload["authorization_digest"], "authorization_digest")
        lease_id = _ident(payload["lease_id"], "lease_id")
        self._agent_mission(consumer, mission_id)
        record = self.ledger.record_dispatch_evidence(
            mission_id, authorization_digest=digest, lease_id=lease_id,
            receipt=payload["receipt"], evidence=payload["evidence"],
            actor=consumer.consumer_id)
        return {"mission_id": mission_id, "state": record.state}

    def _reconcile(self, consumer: ConsumerPolicy,
                   payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "authorization_digest", "lease_id",
                    "target_state", "attestation", "attestation_signature",
                    "report", "evidence"}
        if set(payload) != required:
            raise BadRequest("reconcile com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        digest = _digest(payload["authorization_digest"], "authorization_digest")
        lease_id = _ident(payload["lease_id"], "lease_id")
        target_state = payload["target_state"]
        if target_state not in {ledger.COMPLETED, ledger.FAILED}:
            raise BadRequest("target_state inválido")
        self._agent_mission(consumer, mission_id)
        record = self.ledger.reconcile_signed(
            mission_id, authorization_digest=digest, lease_id=lease_id,
            target_state=target_state,
            attestation=payload["attestation"],
            attestation_signature=_b64decode(
                payload["attestation_signature"], "attestation_signature"),
            report=payload["report"], evidence=payload["evidence"],
            actor=consumer.consumer_id)
        return {"mission_id": mission_id, "state": record.state}

    def _revoke(self, consumer: ConsumerPolicy,
                payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"statement", "signature"}
        if set(payload) != required:
            raise BadRequest("revoke com campos inválidos")
        revoked = self.ledger.revoke_authorization(
            statement=payload["statement"],
            signature=_b64decode(payload["signature"], "signature"),
            actor=consumer.consumer_id)
        return {
            "authorization_digest": revoked.authorization_digest,
            "revoked_at": revoked.revoked_at,
            "nonce": revoked.nonce,
        }

    def _interrupt(self, consumer: ConsumerPolicy,
                   payload: Mapping[str, Any]) -> dict[str, Any]:
        required = {"mission_id", "cause"}
        if set(payload) != required:
            raise BadRequest("interrupt com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        cause = _ident(payload["cause"], "cause")
        if cause not in ledger.Ledger.RECOVERY_CAUSES:
            raise BadRequest("causa de interrupção inválida")
        self._agent_mission(consumer, mission_id)
        # recover_stale_dispatching opera no escopo do projeto; recusamos se
        # houver missão DISPATCHING de outro agente, para não permitir que um
        # executor interrompa trabalho alheio.
        with self.ledger._read() as conn:
            rows = conn.execute(
                """SELECT mission_id, agent FROM missions
                   WHERE state=? AND project_id=? AND repository_id=?""",
                (ledger.DISPATCHING, self.config.project_id,
                 self.config.repository_id)).fetchall()
        for row in rows:
            if row["agent"] != consumer.agent_id:
                raise PermissionDenied(
                    "interrupção envolve missão de outro agente")
        recovered = self.ledger.recover_stale_dispatching(
            cause=cause, actor=consumer.consumer_id)
        record = self._record(mission_id)
        return {
            "mission_id": mission_id, "state": record.state,
            "interrupted": mission_id in recovered,
            "reserved": self.ledger.is_reserved(mission_id),
        }

    def _status(self, consumer: ConsumerPolicy,
                payload: Mapping[str, Any]) -> dict[str, Any]:
        if set(payload) != {"mission_id"}:
            raise BadRequest("status com campos inválidos")
        mission_id = _ident(payload["mission_id"], "mission_id")
        self._agent_mission(consumer, mission_id, require_agent=False)
        record = self._record(mission_id)
        return {
            "mission_id": mission_id, "state": record.state,
            "reserved": self.ledger.is_reserved(mission_id),
            "real_transport": False,
        }


def build_service(
    config: ServiceConfig, *,
    clock: Optional[Callable[[], datetime]] = None,
) -> TrustService:
    """Monta o serviço; os paths/trust store nunca são expostos ao cliente."""
    config.prepare()
    registry = ConsumerRegistry.from_mapping(
        _load_private_json(config.consumer_registry_path, "consumer registry"),
        allow_same_uid=config.allow_same_uid)
    config.validate_consumers(registry)
    trust = TrustStore.load(config.trust_store_path)
    state = ServiceState(config.service_db_path)
    authority = p14b_authority.AuthorityAdapter(
        allowlist=trust.allowlist, mission_source=MissionRegistry(state))
    db = ledger.Ledger(
        config.ledger_path, project_id=config.project_id,
        repository_id=config.repository_id, authority_verifier=authority,
        reconciliation_verifier=authority, clock=clock,
        policy={"allow_legacy_dispatch": False, "allow_real_transport": False},
        allow_migration=True)
    return TrustService(
        config=config, ledger_db=db, authority=authority,
        consumers=registry, state=state, clock=clock or _utc_now)


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _load_private_json(path: str, label: str) -> Mapping[str, Any]:
    _require_private_file(path, label)
    try:
        with open(path, encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, ValueError) as exc:
        raise ServiceConfigurationError(f"{label} inválido") from exc
    if not isinstance(value, Mapping):
        raise ServiceConfigurationError(f"{label} deve ser objeto")
    return value


# ---------- protocolo local (AF_UNIX; sem TCP) ----------

def _frame_send(conn: socket.socket, value: Mapping[str, Any],
                max_bytes: int) -> None:
    raw = canonical_json(dict(value)).encode("utf-8")
    if len(raw) > max_bytes:
        raise BadRequest("frame excessivo")
    conn.sendall(struct.pack(">I", len(raw)) + raw)


def _recv_exact(conn: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining:
        chunk = conn.recv(remaining)
        if not chunk:
            raise ConnectionError("conexão encerrada")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _frame_recv(conn: socket.socket, max_bytes: int) -> dict[str, Any]:
    header = _recv_exact(conn, 4)
    size = struct.unpack(">I", header)[0]
    if size <= 0 or size > max_bytes:
        raise BadRequest("frame inválido")
    raw = _recv_exact(conn, size)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise BadRequest("JSON inválido") from exc
    if not isinstance(value, dict):
        raise BadRequest("frame deve ser objeto")
    return value


class LocalService:
    """Servidor AF_UNIX; autentica cada conexão por SO_PEERCRED."""

    def __init__(self, service: TrustService) -> None:
        self.service = service
        self.config = service.config
        self._socket: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

    def start(self) -> None:
        path = self.config.socket_path
        parent = Path(path).parent
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if Path(path).exists() or Path(path).is_symlink():
            if Path(path).is_symlink():
                raise StorageSecurityError("socket não pode ser symlink")
            Path(path).unlink()
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.bind(path)
        os.chmod(path, 0o660)
        sock.listen(8)
        sock.settimeout(0.2)
        self._socket = sock
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self) -> None:
        assert self._socket is not None
        while not self._stop.is_set():
            try:
                conn, _ = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                self._handle(conn)
            finally:
                conn.close()

    def _handle(self, conn: socket.socket) -> None:
        conn.settimeout(10)
        try:
            peer = peer_identity_from_socket(conn)
            self.service.consumers.resolve(peer)  # autentica antes do greeting
        except ServiceError:
            return
        try:
            _frame_send(conn, {
                "type": "welcome", "protocol": PROTOCOL,
                "service_id": hashlib.sha256(
                    (self.config.project_id + self.config.repository_id).encode()
                ).hexdigest()[:16],
            }, self.config.max_frame_bytes)
            hello = _frame_recv(conn, self.config.max_frame_bytes)
            if hello.get("type") != "hello" or hello.get("protocol") != PROTOCOL:
                _frame_send(conn, {
                    "type": "error", "error": {"code": "PROTOCOL_MISMATCH",
                                              "message": "protocolo inválido"}},
                    self.config.max_frame_bytes)
                return
            request = _frame_recv(conn, self.config.max_frame_bytes)
            response = self.service.handle(peer, request)
            _frame_send(conn, {"type": "response", **response},
                        self.config.max_frame_bytes)
        except (ConnectionError, OSError, BadRequest):
            return

    def stop(self) -> None:
        self._stop.set()
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass
        if self._thread is not None:
            self._thread.join(timeout=2)
        try:
            Path(self.config.socket_path).unlink()
        except FileNotFoundError:
            pass


class ServiceClient:
    """Cliente local; nunca vê paths do ledger/trust store."""

    def __init__(self, socket_path: str, *, timeout: float = 10.0) -> None:
        self.socket_path = socket_path
        self.timeout = timeout

    def request(self, action: str, payload: Mapping[str, Any],
                *, request_id: Optional[str] = None) -> dict[str, Any]:
        request_id = request_id or "req-" + os.urandom(12).hex()
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.settimeout(self.timeout)
        try:
            conn.connect(self.socket_path)
            welcome = _frame_recv(conn, MAX_FRAME_BYTES)
            if welcome.get("type") != "welcome" or welcome.get("protocol") != PROTOCOL:
                raise ConnectionError("welcome inválido")
            _frame_send(conn, {"type": "hello", "protocol": PROTOCOL}, MAX_FRAME_BYTES)
            _frame_send(conn, {
                "request_id": request_id, "action": action,
                "payload": dict(payload)}, MAX_FRAME_BYTES)
            response = _frame_recv(conn, MAX_FRAME_BYTES)
            if response.get("type") != "response":
                raise ConnectionError("resposta inválida")
            return response
        finally:
            conn.close()


def service_socket_roundtrip(service: TrustService,
                             action: str, payload: Mapping[str, Any],
                             *, request_id: Optional[str] = None) -> dict[str, Any]:
    """Executa um round-trip real em AF_UNIX; usado por testes/CLI."""
    server = LocalService(service)
    server.start()
    try:
        return ServiceClient(service.config.socket_path).request(
            action, payload, request_id=request_id)
    finally:
        server.stop()
