#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P1 — serviço local protegido (fundação sintética).

O serviço é o único processo que abre o ledger 1.1 e o trust store. Consumidores
locais falam com ele por um Unix domain socket; a identidade é o UID recebido
em `SCM_CREDENTIALS` por mensagem, nunca um `consumer_id` autodeclarado.

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
import stat
import struct
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from stat import S_ISSOCK
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
REQUEST_TIMEOUT_SECONDS = 15.0
ALLOWED_ENVIRONMENTS = frozenset({"SHADOW"})
KNOWN_ACTIONS = frozenset({
    "propose", "authorize", "reserve", "assert", "record_evidence",
    "reconcile", "revoke", "interrupt", "status", "transmit", "recover",
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


class ReplayStale(ServiceError):
    """Resposta histórica cuja permissão não é mais válida no presente."""

    code = "REPLAY_STALE"


class OutcomeUnknown(ServiceError):
    """Crash/erro entre ação e resposta; o ledger é a única autoridade."""

    code = "OUTCOME_UNKNOWN"


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


def _utc(value: Any, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise BadRequest(f"{label}: datetime com fuso obrigatório")
    return value.astimezone(timezone.utc)


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


@dataclass(frozen=True)
class PeerIdentity:
    """Credenciais de UMA mensagem fornecidas pelo kernel via SCM_CREDENTIALS.

    A fronteira de autoridade é o UID. PID/GID são usados para auditoria e
    consistência da conexão; não há leitura de /proc, hash de interpretador ou
    identidade autodeclarada. Processos do mesmo UID pertencem à mesma
    fronteira de confiança.
    """

    pid: int
    uid: int
    gid: int
    source: str = "SCM_CREDENTIALS"

    def validate(self) -> None:
        if self.source not in {"SO_PEERCRED", "SCM_CREDENTIALS"}:
            raise AuthenticationError("credenciais do consumidor não são do kernel")
        if self.pid <= 0 or self.uid < 0 or self.gid < 0:
            raise AuthenticationError("credenciais de peer inválidas")


def peer_identity_from_credentials(pid: int, uid: int, gid: int) -> PeerIdentity:
    peer = PeerIdentity(
        pid=int(pid), uid=int(uid), gid=int(gid), source="SCM_CREDENTIALS")
    peer.validate()
    return peer


@dataclass(frozen=True)
class ConsumerPolicy:
    """Principal de consumidor: UID exclusivo + ações explícitas.

    Não há hash de executável, argv ou SCM_RIGHTS como prova de identidade.
    Processos do mesmo UID pertencem à mesma fronteira de confiança.
    """

    consumer_id: str
    uid: int
    allowed_actions: frozenset[str]
    agent_id: Optional[str] = None
    role: str = "consumer"

    def validate(self) -> None:
        _ident(self.consumer_id, "consumer_id")
        if self.uid < 0:
            raise ServiceConfigurationError("uid inválido")
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
            if consumer.uid in seen:
                raise ServiceConfigurationError("consumer duplicado por UID")
            seen.add(consumer.uid)
        self.consumers = tuple(consumers)
        self.allow_same_uid = allow_same_uid
        self._by_uid = {consumer.uid: consumer for consumer in consumers}

    def resolve(self, peer: PeerIdentity) -> ConsumerPolicy:
        peer.validate()
        consumer = self._by_uid.get(peer.uid)
        if consumer is None:
            raise AuthenticationError("UID de consumidor desconhecido")
        return consumer

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
    if info.st_nlink != 1:
        raise StorageSecurityError(f"{label} não pode ter hardlink")
    if info.st_mode & 0o077:
        raise StorageSecurityError(f"{label} deve ser 0600")
    if info.st_uid != os.geteuid():
        raise StorageSecurityError(f"{label} deve pertencer ao serviço")


def _require_private_dir(path: str, label: str, *, mode: int) -> None:
    target = Path(path)
    try:
        info = target.lstat()
    except OSError as exc:
        raise StorageSecurityError(f"{label} ausente") from exc
    if target.is_symlink() or not target.is_dir():
        raise StorageSecurityError(f"{label} deve ser diretório real")
    if info.st_uid != os.geteuid():
        raise StorageSecurityError(f"{label} deve pertencer ao serviço")
    if (info.st_mode & 0o777) != mode:
        raise StorageSecurityError(f"{label} deve ser {oct(mode)[2:]}")
    if info.st_mode & 0o022:
        raise StorageSecurityError(f"{label} não pode ser group/world-writable")


def _validate_ancestors(path: str, label: str) -> None:
    """Valida cada ancestral existente, sem seguir symlink/world-write."""
    target = Path(path).absolute()
    for parent in reversed(target.parents):
        if not parent.exists():
            continue
        info = parent.lstat()
        if parent.is_symlink() or not parent.is_dir():
            raise StorageSecurityError(f"{label}: ancestral inválido {parent}")
        if info.st_uid not in {0, os.geteuid()}:
            raise StorageSecurityError(f"{label}: ancestral de outro dono")
        if info.st_mode & 0o022 and not info.st_mode & 0o1000:
            raise StorageSecurityError(f"{label}: ancestral gravável {parent}")


def _require_config_file(path: str) -> None:
    target = Path(path)
    try:
        info = target.lstat()
    except OSError as exc:
        raise StorageSecurityError("configuração ausente") from exc
    if target.is_symlink() or not target.is_file() or info.st_nlink != 1:
        raise StorageSecurityError("configuração deve ser arquivo regular único")
    if info.st_uid not in {0, os.geteuid()}:
        raise StorageSecurityError("configuração de outro dono")
    if info.st_mode & 0o022:
        raise StorageSecurityError("configuração não pode ser group/world-writable")
    _validate_ancestors(path, "configuração")


def _validate_sqlite_sidecars(path: str, label: str) -> None:
    for suffix in ("", "-wal", "-shm", "-journal"):
        candidate = Path(str(path) + suffix)
        if not candidate.exists() and not candidate.is_symlink():
            continue
        _require_private_file(str(candidate), f"{label}{suffix}")


def _remaining(deadline: float) -> float:
    left = deadline - time.monotonic()
    if left <= 0:
        raise ConnectionError("tempo total da requisição excedido")
    return max(0.01, left)


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
    anchor_path: Optional[str] = None
    allow_same_uid: bool = False
    test_mode: bool = False
    real_transport: bool = False
    max_frame_bytes: int = MAX_FRAME_BYTES
    request_timeout_seconds: float = REQUEST_TIMEOUT_SECONDS
    max_workers: int = 4

    def __post_init__(self) -> None:
        for field in ("state_dir", "trust_store_path",
                      "consumer_registry_path", "socket_path"):
            object.__setattr__(self, field, os.path.abspath(getattr(self, field)))
        if self.anchor_path is None:
            parent = os.path.dirname(os.path.abspath(self.state_dir))
            name = os.path.basename(os.path.abspath(self.state_dir)) + "-anchor"
            object.__setattr__(self, "anchor_path",
                               os.path.join(parent, name, "anchor.json"))
        else:
            object.__setattr__(self, "anchor_path", os.path.abspath(self.anchor_path))

    @property
    def ledger_path(self) -> str:
        return os.path.join(self.state_dir, "ledger.db")

    @property
    def service_db_path(self) -> str:
        return os.path.join(self.state_dir, "service.db")

    def prepare(self) -> None:
        if self.real_transport:
            raise TransportDisabled("transporte real desabilitado")
        if self.allow_same_uid and not self.test_mode:
            raise StorageSecurityError(
                "allow_same_uid exige test_mode=True (não usar em produção)")
        if self.request_timeout_seconds <= 0 or self.request_timeout_seconds > 60:
            raise ServiceConfigurationError("request_timeout_seconds inválido")
        if self.service_uid != os.geteuid() or self.service_gid != os.getegid():
            raise StorageSecurityError("processo não roda sob o UID/GID do serviço")
        _ident(self.project_id, "project_id")
        _ident(self.repository_id, "repository_id")
        _validate_ancestors(self.state_dir, "state_dir")
        state = Path(self.state_dir)
        if state.exists() and state.is_symlink():
            raise StorageSecurityError("state_dir não pode ser symlink")
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        _require_private_dir(str(state), "state_dir", mode=0o700)
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
        for label, path in (("ledger", self.ledger_path),
                            ("service db", self.service_db_path)):
            if os.path.exists(path):
                _validate_sqlite_sidecars(path, label)
        socket_path = Path(self.socket_path)
        if socket_path.exists() and socket_path.is_symlink():
            raise StorageSecurityError("socket não pode ser symlink")
        anchor = Path(self.anchor_path)
        if state in anchor.parents or anchor == state:
            raise StorageSecurityError("anchor deve ficar fora do state_dir")
        _validate_ancestors(self.anchor_path, "anchor")
        anchor_parent = anchor.parent
        anchor_parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        _require_private_dir(str(anchor_parent), "anchor_dir", mode=0o700)
        if anchor.exists() and anchor.is_symlink():
            raise StorageSecurityError("anchor não pode ser symlink")
        _validate_ancestors(self.socket_path, "socket")
        socket_parent = socket_path.parent
        socket_parent.mkdir(parents=True, exist_ok=True, mode=0o750)
        if socket_parent != state:
            _require_private_dir(str(socket_parent), "socket dir", mode=0o750)
            if socket_parent.stat().st_gid != self.service_gid:
                raise StorageSecurityError("grupo do socket deve ser o grupo do serviço")
        if socket_path.exists() and not socket_path.is_socket():
            raise StorageSecurityError("socket path existente não é socket")
        if socket_path.exists():
            info = socket_path.lstat()
            if info.st_uid != os.geteuid():
                raise StorageSecurityError("socket existente não pertence ao serviço")
            if info.st_mode & 0o007:
                raise StorageSecurityError("socket existente tem modo excessivo")
        _require_private_file(self.trust_store_path, "trust store")
        _require_private_file(self.consumer_registry_path, "consumer registry")
        if anchor.exists():
            _require_private_file(str(anchor), "anchor")
        if self.max_frame_bytes <= 0 or self.max_frame_bytes > MAX_FRAME_BYTES:
            raise ServiceConfigurationError("max_frame_bytes inválido")
        if not (0 < self.request_timeout_seconds <= 60):
            raise ServiceConfigurationError("request_timeout_seconds inválido")
        if not (1 <= self.max_workers <= 16):
            raise ServiceConfigurationError("max_workers inválido")

    def validate_consumers(self, registry: ConsumerRegistry) -> None:
        if registry.allow_same_uid != self.allow_same_uid:
            raise ServiceConfigurationError("registry diverge do test_mode do serviço")
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
            self._harden()

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
        for suffix in ("", "-wal", "-shm", "-journal"):
            candidate = self.path + suffix
            if not os.path.lexists(candidate):
                continue
            info = os.lstat(candidate)
            if os.path.islink(candidate):
                raise StorageSecurityError("arquivo auxiliar do service db é symlink")
            if info.st_uid != os.geteuid():
                raise StorageSecurityError("arquivo do service db não pertence ao serviço")
            if info.st_nlink != 1:
                raise StorageSecurityError("arquivo do service db não pode ter hardlink")
            os.chmod(candidate, 0o600, follow_symlinks=False)
        _validate_sqlite_sidecars(self.path, "service db")

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
                    epoch INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS service_requests (
                    request_id TEXT PRIMARY KEY,
                    consumer_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    response_json TEXT,
                    status TEXT NOT NULL DEFAULT 'in_progress',
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
                CREATE TABLE IF NOT EXISTS service_recovery_nonces (
                    nonce TEXT PRIMARY KEY,
                    epoch INTEGER NOT NULL,
                    used_at TEXT NOT NULL
                );
            """)
            columns = {
                row[1] for row in conn.execute(
                    "PRAGMA table_info(service_missions)").fetchall()
            }
            if "epoch" not in columns:
                conn.execute(
                    "ALTER TABLE service_missions ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1")
            request_columns = {
                row[1] for row in conn.execute(
                    "PRAGMA table_info(service_requests)").fetchall()
            }
            if "status" not in request_columns:
                conn.execute(
                    "ALTER TABLE service_requests ADD COLUMN status TEXT NOT NULL DEFAULT 'in_progress'")
            conn.execute(
                "UPDATE service_requests SET status='unknown', updated_at=? "
                "WHERE status='in_progress' AND response_json IS NULL",
                (_iso(datetime.now(timezone.utc)),))
        finally:
            conn.close()
        self._harden()

    def put_mission(self, mission: cc.MissionEnvelope, consumer_id: str,
                    now: datetime, *, epoch: int = 1) -> None:
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
                   (mission_id, mission_json, mission_sha256, consumer_id,
                    epoch, created_at)
                   VALUES (?,?,?,?,?,?)""",
                (mission.mission_id, raw, digest, consumer_id, int(epoch), _iso(now)))

    def mission_epoch(self, mission_id: str) -> int:
        with self._read() as conn:
            row = conn.execute(
                "SELECT epoch FROM service_missions WHERE mission_id=?",
                (mission_id,)).fetchone()
        if row is None:
            raise NotFound("missão desconhecida")
        return int(row["epoch"])

    def audit_max(self) -> int:
        with self._read() as conn:
            row = conn.execute("SELECT COALESCE(MAX(seq),0) AS n FROM service_audit").fetchone()
        return int(row["n"])

    def clear_requests(self) -> None:
        with self._write() as conn:
            conn.execute("DELETE FROM service_requests")

    def consume_recovery_nonce(self, nonce: str, epoch: int,
                               now: datetime) -> None:
        """Consome o nonce de recuperação de forma persistente e irreversível.

        A epoch já bloqueia a repetição da mesma declaração; o nonce persistido
        também impede que uma nova declaração assinada reutilize o mesmo nonce
        para uma epoch futura. Falhas posteriores à gravação queimam o nonce
        (fail closed); a autoridade deve emitir outro.
        """
        with self._write() as conn:
            try:
                conn.execute(
                    """INSERT INTO service_recovery_nonces
                       (nonce, epoch, used_at) VALUES (?,?,?)""",
                    (nonce, int(epoch), _iso(now)))
            except sqlite3.IntegrityError as exc:
                raise ServiceConflict(
                    "nonce de recuperação já utilizado") from exc

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
                if row["response_json"] is None or row["status"] != "done":
                    raise OutcomeUnknown(
                        "resultado da requisição anterior é desconhecido")
                return json.loads(row["response_json"])
            conn.execute(
                """INSERT INTO service_requests
                   (request_id, consumer_id, action, request_sha256,
                    response_json, status, created_at, updated_at)
                   VALUES (?,?,?,?,NULL,'in_progress',?,?)""",
                (request_id, consumer_id, action, request_sha256,
                 _iso(now), _iso(now)))
            return None

    def finish_request(self, request_id: str, result: Mapping[str, Any],
                       now: datetime) -> None:
        with self._write() as conn:
            conn.execute(
                "UPDATE service_requests SET response_json=?, status='done', "
                "updated_at=? WHERE request_id=?",
                (canonical_json(dict(result)), _iso(now), request_id))

    def abort_request(self, request_id: str) -> None:
        with self._write() as conn:
            conn.execute(
                "UPDATE service_requests SET status='unknown', updated_at=? "
                "WHERE request_id=? AND response_json IS NULL",
                (_iso(datetime.now(timezone.utc)), request_id))

    def audit(self, consumer_id: str, action: str, outcome: str,
              detail: Mapping[str, Any], now: datetime) -> None:
        digest = sha256_text(canonical_json(dict(detail)))
        with self._write() as conn:
            conn.execute(
                """INSERT INTO service_audit
                   (at, consumer_id, action, outcome, detail_sha256)
                   VALUES (?,?,?,?,?)""",
                (_iso(now), consumer_id, action, outcome, digest))

    @staticmethod
    def _ledger_anchor(ledger_path: str) -> dict[str, int]:
        """Contadores monotônicos para detectar restauração de ledger antigo."""
        try:
            uri = Path(ledger_path).resolve().as_uri() + "?mode=ro"
            conn = sqlite3.connect(uri, uri=True)
            try:
                conn.row_factory = sqlite3.Row
                values = {
                    "missions": conn.execute(
                        "SELECT COUNT(*) FROM missions").fetchone()[0],
                    "authorizations": conn.execute(
                        "SELECT COUNT(*) FROM authorizations").fetchone()[0],
                    "consumed_authorizations": conn.execute(
                        "SELECT COUNT(*) FROM authorizations "
                        "WHERE consumed_at IS NOT NULL").fetchone()[0],
                    "mission_evidence": conn.execute(
                        "SELECT COUNT(*) FROM mission_evidence").fetchone()[0],
                    "transitions": conn.execute(
                        "SELECT COUNT(*) FROM transitions").fetchone()[0],
                }
            finally:
                conn.close()
        except sqlite3.Error as exc:
            raise StorageSecurityError(
                "não foi possível calcular âncora do ledger") from exc
        return {key: int(value) for key, value in values.items()}


ANCHOR_SCHEMA = "BT-COORD-TRUST-ANCHOR-V1"
RECOVERY_SCHEMA = "BT-COORD-TRUST-RECOVERY-V1"
RECOVERY_NAMESPACE = "bt-coord-trust-recovery-v1"
RECOVERY_DOMAIN = b"BT-COORD-TRUST-RECOVERY-V1\n"


def _anchor_data(ledger: Mapping[str, int], service_seq: int, *,
                 epoch: int = 1, state: str = "OPEN",
                 reason: Optional[str] = None,
                 now: Optional[datetime] = None) -> dict[str, Any]:
    return {
        "schema": ANCHOR_SCHEMA,
        "epoch": int(epoch),
        "state": state,
        "reason": reason,
        "ledger": {key: int(value) for key, value in ledger.items()},
        "service_seq": int(service_seq),
        "updated_at": _iso(now) if now is not None else None,
    }


class RollbackAnchor:
    """Âncora de atualidade FORA do state_dir/backup do ledger.

    Não é uma âncora distribuída nem protege contra rollback integral do host.
    Serve para bloquear restaurações silenciosas de ledger/service state em
    quarentena e exigir recuperação assinada por autoridade externa.
    """

    def __init__(self, path: str, data: Mapping[str, Any]) -> None:
        self.path = path
        self.data = dict(data)

    @property
    def epoch(self) -> int:
        return int(self.data["epoch"])

    @property
    def state(self) -> str:
        return str(self.data["state"])

    @property
    def reason(self) -> Optional[str]:
        return self.data.get("reason")

    @classmethod
    def load_or_initialize(cls, path: str, ledger: Mapping[str, int],
                           service_seq: int, now: datetime) -> "RollbackAnchor":
        if os.path.exists(path):
            value = _load_private_json(path, "rollback anchor")
            if value.get("schema") != ANCHOR_SCHEMA:
                raise StorageSecurityError("schema da âncora inválido")
            if value.get("state") not in {"OPEN", "QUARANTINED"}:
                raise StorageSecurityError("estado da âncora inválido")
            if not isinstance(value.get("ledger"), Mapping):
                raise StorageSecurityError("âncora sem contadores")
            return cls(path, value)
        has_data = any(int(v) for v in ledger.values()) or service_seq > 0
        data = _anchor_data(
            ledger, service_seq, epoch=1,
            state="QUARANTINED" if has_data else "OPEN",
            reason=None if not has_data else "âncora ausente com estado existente",
            now=now)
        _write_private_json(path, data)
        return cls(path, data)

    def verify(self, ledger: Mapping[str, int], service_seq: int,
               now: datetime) -> tuple[bool, Optional[str]]:
        if self.state != "OPEN":
            return False, self.reason or "âncora em quarentena"
        stored = self.data.get("ledger")
        if not isinstance(stored, Mapping):
            raise StorageSecurityError("âncora sem contadores")
        for key, current in ledger.items():
            previous = stored.get(key)
            if not isinstance(previous, int):
                raise StorageSecurityError("contador da âncora inválido")
            if int(current) < previous:
                self.quarantine("ledger regrediu em " + key, now)
                return False, self.reason
            if int(current) > previous:
                self.quarantine("ledger à frente da âncora em " + key, now)
                return False, self.reason
        previous_seq = self.data.get("service_seq")
        if not isinstance(previous_seq, int):
            raise StorageSecurityError("service_seq da âncora inválido")
        if service_seq < previous_seq:
            self.quarantine("service state regrediu", now)
            return False, self.reason
        if service_seq > previous_seq:
            self.quarantine("service state à frente da âncora", now)
            return False, self.reason
        return True, None

    def update(self, ledger: Mapping[str, int], service_seq: int,
               *, epoch: int, now: datetime) -> None:
        if self.state != "OPEN":
            raise StorageSecurityError("âncora em quarentena")
        data = _anchor_data(ledger, service_seq, epoch=epoch, state="OPEN", now=now)
        _write_private_json(self.path, data)
        self.data = data

    def quarantine(self, reason: str, now: datetime) -> None:
        data = dict(self.data)
        data.update({
            "state": "QUARANTINED",
            "reason": reason,
            "updated_at": _iso(now),
        })
        _write_private_json(self.path, data)
        self.data = data

    def recover(self, new_epoch: int, ledger: Mapping[str, int],
                service_seq: int, now: datetime) -> None:
        data = _anchor_data(
            ledger, service_seq, epoch=new_epoch, state="OPEN", now=now)
        _write_private_json(self.path, data)
        self.data = data


@dataclass(frozen=True)
class RecoveryStatement:
    """Autorização externa para sair da quarentena e invalidar epochs antigos."""

    schema: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    new_epoch: int
    reason: str
    issued_at: datetime
    expires_at: datetime
    nonce: str

    def validate(self) -> None:
        if self.schema != RECOVERY_SCHEMA:
            raise BadRequest("schema de recuperação inválido")
        _ident(self.approver_id, "approver_id")
        _text(self.approver_key_fingerprint, "approver_key_fingerprint")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        if not isinstance(self.new_epoch, int) or self.new_epoch < 2:
            raise BadRequest("new_epoch inválido")
        _text(self.reason, "reason")
        _ident(self.nonce, "nonce")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise BadRequest("janela da recuperação invertida")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": self.schema,
            "approver_id": self.approver_id,
            "approver_key_fingerprint": self.approver_key_fingerprint,
            "trust_policy_sha256": self.trust_policy_sha256,
            "new_epoch": self.new_epoch,
            "reason": self.reason,
            "issued_at": _iso(self.issued_at),
            "expires_at": _iso(self.expires_at),
            "nonce": self.nonce,
        }

    def to_mapping(self) -> dict[str, Any]:
        return self.canonical_material()

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "RecoveryStatement":
        required = {
            "schema", "approver_id", "approver_key_fingerprint",
            "trust_policy_sha256", "new_epoch", "reason", "issued_at",
            "expires_at", "nonce",
        }
        if not isinstance(value, Mapping) or set(value) != required:
            raise BadRequest("statement de recuperação inválido")
        try:
            return cls(
                schema=value["schema"],
                approver_id=value["approver_id"],
                approver_key_fingerprint=value["approver_key_fingerprint"],
                trust_policy_sha256=value["trust_policy_sha256"],
                new_epoch=int(value["new_epoch"]), reason=value["reason"],
                issued_at=_parse_dt(value["issued_at"]),
                expires_at=_parse_dt(value["expires_at"]),
                nonce=value["nonce"])
        except (KeyError, TypeError, ValueError) as exc:
            raise BadRequest("statement de recuperação inválido") from exc


def canonical_recovery_bytes(statement: RecoveryStatement) -> bytes:
    return RECOVERY_DOMAIN + canonical_json(
        statement.canonical_material()).encode("utf-8")


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
        anchor: RollbackAnchor,
        epoch: int,
        quarantined: bool = False,
        quarantine_reason: Optional[str] = None,
        clock: Optional[Callable[[], datetime]] = None,
    ) -> None:
        self.config = config
        self.ledger = ledger_db
        self.authority = authority
        self.consumers = consumers
        self.state = state
        self.anchor = anchor
        self.epoch = int(epoch)
        self.quarantined = bool(quarantined)
        self.quarantine_reason = quarantine_reason
        self.clock = clock or _utc_now

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
            if self.quarantined and action not in {"status", "recover"}:
                raise ReplayStale(
                    "serviço em quarentena; estado não demonstra atualidade")
            request_sha = sha256_text(canonical_json(
                {"action": action, "payload": dict(payload)}))
            cached = self.state.begin_request(
                request_id, consumer.consumer_id, action, request_sha, self._now())
            if cached is not None:
                current = self._revalidate_replay(
                    consumer, action, payload, cached)
                return {"ok": True, "request_id": request_id,
                        "result": dict(cached), "replayed": True,
                        "authorization_current": current,
                        "historical": not current}
            try:
                result = self._dispatch(consumer, action, payload)
            except Exception:
                self.state.abort_request(request_id)
                raise
            self.state.finish_request(request_id, result, self._now())
            self.state.audit(
                consumer.consumer_id, action, "ok", {"request_id": request_id},
                self._now())
            # A recuperação também precisa selar a âncora DEPOIS do audit
            # final; caso contrário um restart veria service_seq à frente da
            # âncora e entraria em quarentena imediatamente após recuperar.
            if not self.quarantined:
                self._seal_anchor()
            response = {"ok": True, "request_id": request_id,
                        "result": dict(result)}
            if action in {"assert", "reserve"}:
                response.update({
                    "authorization_current": True,
                    "historical": False,
                })
            return response
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

    def _seal_anchor(self) -> None:
        try:
            ledger_anchor = self.state._ledger_anchor(self.config.ledger_path)
            self.anchor.update(
                ledger_anchor, self.state.audit_max(),
                epoch=self.epoch, now=self._now())
        except Exception as exc:
            self.quarantined = True
            self.quarantine_reason = "falha ao selar âncora"
            try:
                self.anchor.quarantine(self.quarantine_reason, self._now())
            except Exception:
                pass
            raise StorageSecurityError(
                "âncora de atualidade não pôde ser selada") from exc

    def _require_current_epoch(self, mission_id: str) -> None:
        if self.state.mission_epoch(mission_id) != self.epoch:
            raise ReplayStale("missão pertence a epoch anterior à recuperação")

    def _recover(self, consumer: ConsumerPolicy,
                 payload: Mapping[str, Any]) -> dict[str, Any]:
        if set(payload) != {"statement", "signature"}:
            raise BadRequest("recover com campos inválidos")
        statement = RecoveryStatement.from_mapping(payload["statement"])
        statement.validate()
        if statement.new_epoch <= self.epoch:
            raise ServiceConflict("nova epoch deve ser maior que a atual")
        allowlist = self.authority.allowlist
        if statement.trust_policy_sha256 != allowlist.digest():
            raise AuthenticationError("trust policy de recuperação divergente")
        approver = allowlist.find(
            statement.approver_id, statement.approver_key_fingerprint)
        if approver is None:
            raise AuthenticationError("aprovador de recuperação desconhecido")
        actual = cas.public_key_fingerprint(approver.public_key)
        if actual != approver.fingerprint or actual != statement.approver_key_fingerprint:
            raise AuthenticationError("fingerprint de recuperação divergente")
        now = self._now()
        if not approver.active_at(now):
            raise AuthenticationError("chave de recuperação inativa")
        issued = _utc(statement.issued_at, "issued_at")
        expires = _utc(statement.expires_at, "expires_at")
        if now < issued or now >= expires:
            raise AuthenticationError("janela de recuperação inválida")
        ok, detail = cas.verify_openssh_signature(
            data=canonical_recovery_bytes(statement),
            signature=_b64decode(payload["signature"], "signature"),
            approver=approver, namespace=RECOVERY_NAMESPACE)
        if not ok:
            raise AuthenticationError(f"assinatura de recuperação inválida: {detail}")
        self.state.consume_recovery_nonce(statement.nonce, statement.new_epoch, now)
        self.epoch = statement.new_epoch
        self.quarantined = False
        self.quarantine_reason = None
        self.state.clear_requests()
        self.state.audit(
            consumer.consumer_id, "recover", "ok",
            {"epoch": self.epoch, "reason": statement.reason}, now)
        ledger_anchor = self.state._ledger_anchor(self.config.ledger_path)
        self.anchor.recover(
            statement.new_epoch, ledger_anchor, self.state.audit_max(), now)
        return {"epoch": self.epoch, "state": "OPEN"}

    def _revalidate_replay(self, consumer: ConsumerPolicy, action: str,
                           payload: Mapping[str, Any],
                           cached: Mapping[str, Any]) -> bool:
        """Distingue resposta histórica de permissão atualmente válida.

        A resposta persistida é idempotente, mas não é uma nova autorização.
        Para ações que confirmam/consomem capacidade, o estado atual do ledger
        é reconsultado. Revogação, expiração, UNKNOWN ou terminal invalidam a
        resposta antiga (`REPLAY_STALE`) em vez de devolver o lease cacheado.
        """
        mission_id = cached.get("mission_id") or payload.get("mission_id")
        if action in {"assert", "reserve"}:
            digest = cached.get("authorization_digest") or payload.get(
                "authorization_digest")
            lease_id = cached.get("lease_id") or payload.get("lease_id")
            if not isinstance(mission_id, str) or not isinstance(digest, str):
                raise ReplayStale("resposta histórica incompleta")
            self._require_current_epoch(mission_id)
            if action == "reserve" and not isinstance(lease_id, str):
                # A reserva cacheada expõe o lease; sem ele não há como
                # demonstrar que a permissão ainda é a mesma.
                raise ReplayStale("reserva histórica sem lease verificável")
            try:
                self.ledger.assert_dispatchable(
                    mission_id, authorization_digest=digest,
                    lease_id=lease_id, actor=consumer.consumer_id)
            except (ledger.LedgerError, cla.AuthorityContractError) as exc:
                raise ReplayStale(
                    "resposta histórica não é permissão atual") from exc
            return True
        if action == "authorize":
            digest = cached.get("authorization_digest")
            if not isinstance(mission_id, str) or not isinstance(digest, str):
                raise ReplayStale("autorização histórica incompleta")
            self._require_current_epoch(mission_id)
            try:
                record = self.ledger.get(mission_id)
                auth = self.ledger.get_authorization(mission_id)
                revoked = self.ledger.get_revocation(digest)
            except ledger.LedgerError as exc:
                raise ReplayStale("autorização histórica ilegível") from exc
            if (record is None or auth is None
                    or record.state != ledger.AUTHORIZED
                    or auth.authorization_digest != digest
                    or auth.consumed_at is not None
                    or revoked is not None
                    or self._now() >= ledger.parse_iso(auth.expires_at)):
                raise ReplayStale("autorização histórica não está mais válida")
            return True
        if action == "reconcile":
            record = self.ledger.get(str(mission_id)) if mission_id else None
            if record is None or record.state != cached.get("state"):
                raise ReplayStale("reconciliação histórica não corresponde ao estado")
            return False
        if action == "interrupt":
            record = self.ledger.get(str(mission_id)) if mission_id else None
            if record is None or record.state not in {
                    ledger.UNKNOWN, ledger.COMPLETED, ledger.FAILED}:
                raise ReplayStale("interrupção histórica não corresponde ao estado")
            return False
        if action == "revoke":
            digest = cached.get("authorization_digest")
            if not isinstance(digest, str) or self.ledger.get_revocation(digest) is None:
                raise ReplayStale("revogação histórica ausente")
            return False
        if action == "record_evidence":
            if not mission_id or self.ledger.get(str(mission_id)) is None:
                raise ReplayStale("evidência histórica sem missão")
            return False
        if action == "status":
            # Status não é permissão, mas deve refletir o estado atual; nunca
            # devolver um snapshot histórico silencioso.
            mission_id = _ident(mission_id, "mission_id")
            record = self._record(mission_id)
            cached["mission_id"] = mission_id
            cached["state"] = record.state
            cached["reserved"] = self.ledger.is_reserved(mission_id)
            cached["real_transport"] = False
            cached["quarantined"] = self.quarantined
            cached["epoch"] = self.epoch
            return False
        # propose: resposta histórica, sem concessão de capacidade.
        if mission_id:
            self._record(str(mission_id))
        return False

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
        if action == "recover":
            return self._recover(consumer, payload)
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
        self.state.put_mission(
            mission, consumer.consumer_id, self._now(), epoch=self.epoch)
        self._require_current_epoch(mission.mission_id)
        self.ledger.register_signed_proposed(
            snapshot, authorization_reference=mission.authorization_reference)
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
        self._require_current_epoch(mission_id)
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
        self._require_current_epoch(mission_id)
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
        self._require_current_epoch(mission_id)
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
            "quarantined": self.quarantined,
            "epoch": self.epoch,
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
    _validate_sqlite_sidecars(config.ledger_path, "ledger")
    current_clock = clock or _utc_now
    now = current_clock()
    if not isinstance(now, datetime) or now.tzinfo is None:
        raise ServiceConfigurationError("clock do serviço inválido")
    ledger_anchor = state._ledger_anchor(config.ledger_path)
    service_seq = state.audit_max()
    anchor = RollbackAnchor.load_or_initialize(
        config.anchor_path, ledger_anchor, service_seq, now)
    current, reason = anchor.verify(ledger_anchor, service_seq, now)
    return TrustService(
        config=config, ledger_db=db, authority=authority,
        consumers=registry, state=state, anchor=anchor,
        epoch=anchor.epoch, quarantined=not current,
        quarantine_reason=reason, clock=current_clock)


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


# ---------- protocolo local (AF_UNIX seqpacket; sem TCP) ----------

_CRED_SIZE = struct.calcsize("3i")


def _credentials_from_ancillary(
    ancdata: list[tuple[int, int, bytes]], flags: int,
    *, expected: Optional[PeerIdentity] = None,
) -> PeerIdentity:
    """Exige exatamente uma credencial SCM_CREDENTIALS completa por frame."""
    if flags & getattr(socket, "MSG_CTRUNC", 0):
        raise AuthenticationError("credenciais truncadas pelo kernel")
    found: list[tuple[int, int, int]] = []
    for level, kind, payload in ancdata:
        if level != socket.SOL_SOCKET or kind != socket.SCM_CREDENTIALS:
            raise AuthenticationError("ancillary não permitido")
        if len(payload) != _CRED_SIZE:
            raise AuthenticationError("credenciais truncadas")
        found.append(struct.unpack("3i", payload))
    if len(found) != 1:
        raise AuthenticationError("credenciais do consumidor ausentes")
    pid, uid, gid = found[0]
    if expected is not None and (pid, uid, gid) != (
            expected.pid, expected.uid, expected.gid):
        raise AuthenticationError("identidade divergente durante a requisição")
    return PeerIdentity(
        pid=pid, uid=uid, gid=gid, source="SCM_CREDENTIALS")


def _deadline_remaining(deadline: Optional[float]) -> Optional[float]:
    if deadline is None:
        return None
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ConnectionError("tempo total da requisição excedido")
    return remaining


def _frame_send(conn: socket.socket, value: Mapping[str, Any],
                max_bytes: int, *, deadline: Optional[float] = None) -> None:
    raw = canonical_json(dict(value)).encode("utf-8")
    if len(raw) > max_bytes:
        raise BadRequest("frame excessivo")
    packet = struct.pack(">I", len(raw)) + raw
    remaining = _deadline_remaining(deadline)
    if remaining is not None:
        conn.settimeout(remaining)
    sent = conn.send(packet)
    if sent != len(packet):
        raise ConnectionError("frame parcial em SOCK_SEQPACKET")


def _frame_send_client(conn: socket.socket, value: Mapping[str, Any],
                       max_bytes: int) -> None:
    _frame_send(conn, value, max_bytes)


def _frame_recv_plain(conn: socket.socket, max_bytes: int, *,
                      deadline: Optional[float] = None) -> dict[str, Any]:
    remaining = _deadline_remaining(deadline)
    if remaining is not None:
        conn.settimeout(remaining)
    try:
        data, _ancillary, flags, _ = conn.recvmsg(max_bytes + 4)
    except (OSError, socket.timeout) as exc:
        raise ConnectionError("falha ao receber frame") from exc
    if not data:
        raise ConnectionError("conexão encerrada")
    if flags & getattr(socket, "MSG_TRUNC", 0):
        raise BadRequest("frame truncado")
    if len(data) < 4:
        raise BadRequest("frame inválido")
    size = struct.unpack(">I", data[:4])[0]
    if size <= 0 or size > max_bytes or len(data) != 4 + size:
        raise BadRequest("frame inválido")
    try:
        value = json.loads(data[4:].decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise BadRequest("JSON inválido") from exc
    if not isinstance(value, dict):
        raise BadRequest("frame deve ser objeto")
    return value


def _frame_recv(conn: socket.socket, max_bytes: int, *,
                expected: Optional[PeerIdentity] = None,
                deadline: Optional[float] = None) -> tuple[dict[str, Any], PeerIdentity]:
    remaining = _deadline_remaining(deadline)
    if remaining is not None:
        conn.settimeout(remaining)
    try:
        data, ancillary, flags, _ = conn.recvmsg(
            max_bytes + 4, socket.CMSG_SPACE(_CRED_SIZE))
    except (OSError, socket.timeout) as exc:
        raise ConnectionError("falha ao receber frame") from exc
    if not data:
        raise ConnectionError("conexão encerrada")
    if flags & getattr(socket, "MSG_TRUNC", 0):
        raise BadRequest("frame truncado")
    peer = _credentials_from_ancillary(ancillary, flags, expected=expected)
    if len(data) < 4:
        raise BadRequest("frame inválido")
    size = struct.unpack(">I", data[:4])[0]
    if size <= 0 or size > max_bytes or len(data) != 4 + size:
        raise BadRequest("frame inválido")
    try:
        value = json.loads(data[4:].decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise BadRequest("JSON inválido") from exc
    if not isinstance(value, dict):
        raise BadRequest("frame deve ser objeto")
    return value, peer


class LocalService:
    """Servidor AF_UNIX; autentica cada mensagem por SCM_CREDENTIALS."""

    def __init__(self, service: TrustService) -> None:
        self.service = service
        self.config = service.config
        self._socket: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._executor: Optional[ThreadPoolExecutor] = None
        self._slots: Optional[threading.BoundedSemaphore] = None
        self._stop = threading.Event()

    def _prepare_socket_path(self) -> None:
        path = Path(self.config.socket_path)
        parent = path.parent
        parent.mkdir(parents=True, exist_ok=True, mode=0o750)
        if parent != Path(self.config.state_dir):
            _require_private_dir(str(parent), "socket dir", mode=0o750)
            if parent.stat().st_gid != self.config.service_gid:
                raise StorageSecurityError("grupo do socket divergente")
        if os.path.lexists(path):
            info = os.lstat(path)
            if os.path.islink(path) or not stat.S_ISSOCK(info.st_mode):
                raise StorageSecurityError("socket path existente não é socket")
            if info.st_uid != os.geteuid():
                raise StorageSecurityError("socket existente não pertence ao serviço")
            path.unlink()

    def start(self) -> None:
        self._prepare_socket_path()
        path = self.config.socket_path
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        sock.bind(path)
        os.chmod(path, 0o660)
        info = os.lstat(path)
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.geteuid():
            sock.close()
            raise StorageSecurityError("socket criado com identidade inválida")
        sock.listen(8)
        sock.settimeout(0.2)
        self._socket = sock
        self._executor = ThreadPoolExecutor(
            max_workers=self.config.max_workers,
            thread_name_prefix="coord-trust")
        self._slots = threading.BoundedSemaphore(self.config.max_workers)
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self) -> None:
        assert self._socket is not None and self._slots is not None
        while not self._stop.is_set():
            try:
                conn, _ = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self._slots.acquire(blocking=False):
                conn.close()
                continue
            assert self._executor is not None
            self._executor.submit(self._run, conn)

    def _run(self, conn: socket.socket) -> None:
        try:
            self._handle(conn)
        finally:
            conn.close()
            if self._slots is not None:
                self._slots.release()

    def _handle(self, conn: socket.socket) -> None:
        deadline = time.monotonic() + self.config.request_timeout_seconds
        conn.settimeout(self.config.request_timeout_seconds)
        try:
            if not hasattr(socket, "SO_PASSCRED"):
                raise AuthenticationError("SO_PASSCRED indisponível")
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
            _frame_send(conn, {
                "type": "welcome", "protocol": PROTOCOL,
                "service_id": hashlib.sha256(
                    (self.config.project_id + self.config.repository_id).encode()
                ).hexdigest()[:16],
            }, self.config.max_frame_bytes, deadline=deadline)
            hello, hello_peer = _frame_recv(
                conn, self.config.max_frame_bytes, deadline=deadline)
            if hello.get("type") != "hello" or hello.get("protocol") != PROTOCOL:
                _frame_send(conn, {
                    "type": "error", "error": {"code": "PROTOCOL_MISMATCH",
                                              "message": "protocolo inválido"}},
                    self.config.max_frame_bytes, deadline=deadline)
                return
            hello_consumer = self.service.consumers.resolve(hello_peer)
            request, request_peer = _frame_recv(
                conn, self.config.max_frame_bytes, deadline=deadline)
            # O fd pode ser transferido por SCM_RIGHTS; a credencial da
            # mensagem, não a credencial de connect, é a autoridade. Exigimos
            # continuidade de UID/GID e do consumidor resolvido.
            if (request_peer.uid, request_peer.gid) != (
                    hello_peer.uid, hello_peer.gid):
                raise AuthenticationError("credenciais da mensagem divergentes")
            request_consumer = self.service.consumers.resolve(request_peer)
            if request_consumer.consumer_id != hello_consumer.consumer_id:
                raise AuthenticationError("consumidor da mensagem divergente")
            response = self.service.handle(request_peer, request)
            _frame_send(conn, {"type": "response", **response},
                        self.config.max_frame_bytes, deadline=deadline)
        except (ConnectionError, OSError, BadRequest, AuthenticationError):
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
        if self._executor is not None:
            self._executor.shutdown(wait=False, cancel_futures=True)
        try:
            Path(self.config.socket_path).unlink()
        except FileNotFoundError:
            pass


class ServiceClient:
    """Cliente local; autentica o servidor antes de enviar qualquer payload."""

    def __init__(self, socket_path: str, *,
                 expected_service_uid: Optional[int] = None,
                 expected_service_gid: Optional[int] = None,
                 service_uid: Optional[int] = None,
                 service_gid: Optional[int] = None,
                 expected_service_id: Optional[str] = None,
                 timeout: float = REQUEST_TIMEOUT_SECONDS) -> None:
        uid = expected_service_uid if expected_service_uid is not None else service_uid
        gid = expected_service_gid if expected_service_gid is not None else service_gid
        if uid is None:
            raise ValueError("UID esperado do serviço é obrigatório")
        self.socket_path = socket_path
        self.expected_service_uid = uid
        self.expected_service_gid = gid
        self.expected_service_id = expected_service_id
        self.timeout = timeout

    def _check_socket_path(self) -> None:
        try:
            info = os.lstat(self.socket_path)
        except OSError as exc:
            raise ConnectionError("socket do serviço inacessível") from exc
        if not S_ISSOCK(info.st_mode):
            raise ConnectionError("path do serviço não é socket")
        if info.st_uid != self.expected_service_uid:
            raise AuthenticationError("dono do socket não é o serviço esperado")
        if (self.expected_service_gid is not None
                and info.st_gid != self.expected_service_gid):
            raise AuthenticationError("grupo do socket não é o esperado")
        if info.st_mode & 0o002:
            raise AuthenticationError("socket world-writable")
        if info.st_nlink > 1:
            raise AuthenticationError("socket com hardlink inesperado")

    def _check_server_peer(self, conn: socket.socket) -> None:
        try:
            raw = conn.getsockopt(
                socket.SOL_SOCKET, socket.SO_PEERCRED, _CRED_SIZE)
            pid, uid, gid = struct.unpack("3i", raw)
        except (AttributeError, OSError, struct.error) as exc:
            raise AuthenticationError("SO_PEERCRED do servidor indisponível") from exc
        if uid != self.expected_service_uid:
            raise AuthenticationError("UID do servidor divergente")
        if self.expected_service_gid is not None and gid != self.expected_service_gid:
            raise AuthenticationError("GID do servidor divergente")
        if pid <= 0:
            raise AuthenticationError("PID do servidor inválido")

    def request(self, action: str, payload: Mapping[str, Any],
                *, request_id: Optional[str] = None) -> dict[str, Any]:
        request_id = request_id or "req-" + os.urandom(12).hex()
        deadline = time.monotonic() + self.timeout
        self._check_socket_path()
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        conn.settimeout(self.timeout)
        try:
            conn.connect(self.socket_path)
            self._check_server_peer(conn)
            welcome = _frame_recv_plain(
                conn, MAX_FRAME_BYTES, deadline=deadline)
            if welcome.get("type") != "welcome" or welcome.get("protocol") != PROTOCOL:
                raise ConnectionError("welcome inválido")
            if (self.expected_service_id is not None
                    and welcome.get("service_id") != self.expected_service_id):
                raise AuthenticationError("identidade do serviço divergente")
            _frame_send_client(conn, {"type": "hello", "protocol": PROTOCOL},
                               MAX_FRAME_BYTES)
            _frame_send_client(conn, {
                "request_id": request_id, "action": action,
                "payload": dict(payload)}, MAX_FRAME_BYTES)
            response = _frame_recv_plain(
                conn, MAX_FRAME_BYTES, deadline=deadline)
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
        return ServiceClient(
            service.config.socket_path,
            expected_service_uid=service.config.service_uid,
            expected_service_gid=service.config.service_gid).request(
            action, payload, request_id=request_id)
    finally:
        server.stop()
