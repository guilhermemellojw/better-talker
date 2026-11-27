#!/usr/bin/env python3
"""Ledger persistente local de missões (COORD-05A).

Sem transporte, OpenCode, LLM, credenciais ou autorização por iniciativa própria.
SQLite da biblioteca padrão é usado por ser local, transacional, disponível sem
dependências e suficiente para reserva atômica e histórico auditável.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, Mapping, Optional

from coord_ledger_authority import (
    ALLOWED_ENVIRONMENTS,
    AUTHORIZATION_MODE_LEGACY,
    AUTHORIZATION_MODE_SIGNED,
    AuthorityContractError,
    AuthorityVerifier,
    ExpectedOperation,
    MissionSnapshot,
    VerifiedAuthorization,
    VerifiedRevocation,
)

SCHEMA_VERSION = "coord-ledger-1.1"
OLD_SCHEMA_VERSION = "coord-ledger-1.0"
_UMASK_LOCK = threading.Lock()

PROPOSED = "PROPOSED"
AUTHORIZED = "AUTHORIZED"
DISPATCHING = "DISPATCHING"
DELIVERED = "DELIVERED"
RUNNING = "RUNNING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
UNKNOWN = "UNKNOWN"

STATES = (
    PROPOSED,
    AUTHORIZED,
    DISPATCHING,
    DELIVERED,
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN,
)
TERMINAL = frozenset({COMPLETED, FAILED})
ACTIVE = frozenset({AUTHORIZED, DISPATCHING, DELIVERED, RUNNING})

ALLOWED_TRANSITIONS: Mapping[str, frozenset[str]] = {
    PROPOSED: frozenset({AUTHORIZED}),
    AUTHORIZED: frozenset({DISPATCHING, FAILED}),
    DISPATCHING: frozenset({DELIVERED, UNKNOWN}),
    DELIVERED: frozenset({RUNNING, UNKNOWN}),
    RUNNING: frozenset({COMPLETED, FAILED, UNKNOWN}),
    COMPLETED: frozenset(),
    FAILED: frozenset(),
    UNKNOWN: frozenset({DELIVERED, RUNNING, COMPLETED, FAILED}),
}


class LedgerError(Exception):
    """Falha de contrato do ledger."""


class LedgerStorageError(LedgerError):
    """Falha de armazenamento; nenhuma transição é confirmada."""


class DuplicateMission(LedgerError):
    """Missão ou operação repetida com identidade divergente."""


class DuplicateReservation(LedgerError):
    """Já existe missão ativa para o mesmo agente/destino."""


class InvalidTransition(LedgerError):
    """Transição não permitida pelo contrato."""


class UnsafeReconciliation(LedgerError):
    """Reconciliação sem evidência ou decisão humana registrada."""


class SchemaMigrationRequired(LedgerError):
    """Banco 1.0 exige opt-in explícito de migração."""


class SignedAuthorizationRequired(LedgerError):
    """Missão SIGNED_V1 exige a API de autorização assinada."""


class SignedTransitionRequired(LedgerError):
    """Transição de missão SIGNED_V1 exige reconciliação assinada."""


class EvidenceDivergence(LedgerError):
    """Evidência/receipt divergente do material já persistido."""


class AuthorizationMissing(LedgerError):
    """Autorização persistida ausente."""


class AuthorizationExpired(LedgerError):
    """Autorização fora da janela de validade."""


class AuthorizationRevoked(LedgerError):
    """Autorização revogada."""


class DispatchBlocked(LedgerError):
    """Reserva/lease não permite o despacho no instante consultado."""


def canonical_json(value: Any) -> str:
    """Mesmo contrato COORD-04: chaves ordenadas, sem espaços, UTF-8."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def payload_sha256(payload: Any) -> str:
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


@dataclass(frozen=True)
class MissionRecord:
    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    agent: str
    session_id: str
    instance_id: str
    worktree: str
    authorization_reference: str
    payload_sha256: str
    identity_hash: str
    state: str
    created_at: str
    updated_at: str
    dispatched_at: Optional[str]
    timeout_seconds: int
    authorization_mode: str = AUTHORIZATION_MODE_LEGACY
    authorization_digest: Optional[str] = None
    mission_snapshot_sha256: Optional[str] = None
    environment: Optional[str] = None
    transmissions_used: int = 0
    receipt_sha256: Optional[str] = None
    dispatch_evidence_sha256: Optional[str] = None
    reconciliation_sha256: Optional[str] = None


@dataclass(frozen=True)
class DispatchLease:
    """Lease devolvido pela reserva; não é autorização nem bearer token."""

    mission_id: str
    authorization_digest: str
    lease_id: str
    target_key: tuple[str, str, str, str]
    reserved_at: datetime
    expires_at: datetime


@dataclass(frozen=True)
class AuthorizationRecord:
    authorization_digest: str
    authorization_id: str
    approval_reference: str
    mission_id: str
    mission_snapshot_sha256: str
    authority_material_sha256: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    environment: str
    issued_at: str
    expires_at: str
    nonce: str
    consumed_at: Optional[str]
    consumed_by_mission_id: Optional[str]
    created_at: str


@dataclass(frozen=True)
class RevocationRecord:
    authorization_digest: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    revoked_at: str
    recorded_at: str
    reason: str
    nonce: str
    signature_sha256: str


@dataclass(frozen=True)
class EvidenceRecord:
    seq: int
    mission_id: str
    kind: str
    evidence_sha256: str
    actor: str
    source: str
    recorded_at: str
    authorization_digest: Optional[str]
    lease_id: Optional[str]


@dataclass(frozen=True)
class Transition:
    seq: int
    mission_id: str
    from_state: Optional[str]
    to_state: str
    evidence: str
    actor: str
    at: str
    authorization_digest: Optional[str] = None


_SCHEMA_1_1_SQL = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY, value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS missions (
    mission_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    project_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    coordinator_id TEXT NOT NULL,
    agent TEXT NOT NULL,
    session_id TEXT NOT NULL,
    instance_id TEXT NOT NULL,
    worktree TEXT NOT NULL,
    authorization_reference TEXT NOT NULL,
    payload_sha256 TEXT NOT NULL,
    identity_hash TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    dispatched_at TEXT,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    authorization_mode TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
    authorization_digest TEXT,
    mission_snapshot_json TEXT,
    mission_snapshot_sha256 TEXT,
    environment TEXT,
    transmissions_used INTEGER NOT NULL DEFAULT 0,
    receipt_json TEXT,
    receipt_sha256 TEXT,
    dispatch_evidence_json TEXT,
    dispatch_evidence_sha256 TEXT,
    reconciliation_json TEXT,
    reconciliation_sha256 TEXT
);
CREATE TABLE IF NOT EXISTS transitions (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    mission_id TEXT NOT NULL,
    from_state TEXT,
    to_state TEXT NOT NULL,
    evidence TEXT NOT NULL,
    actor TEXT NOT NULL,
    at TEXT NOT NULL,
    authorization_digest TEXT
);
CREATE TABLE IF NOT EXISTS reservations (
    project_id TEXT NOT NULL,
    agent TEXT NOT NULL,
    session_id TEXT NOT NULL,
    mission_id TEXT NOT NULL,
    authorization_digest TEXT,
    lease_id TEXT,
    reserved_at TEXT,
    PRIMARY KEY (project_id, agent, session_id),
    UNIQUE (project_id, agent),
    UNIQUE (project_id, session_id)
);
CREATE TABLE IF NOT EXISTS authorizations (
    authorization_digest TEXT PRIMARY KEY,
    authorization_id TEXT NOT NULL UNIQUE,
    approval_reference TEXT NOT NULL,
    mission_id TEXT NOT NULL UNIQUE,
    mission_snapshot_sha256 TEXT NOT NULL,
    authority_material_sha256 TEXT NOT NULL,
    approver_id TEXT NOT NULL,
    approver_key_fingerprint TEXT NOT NULL,
    trust_policy_sha256 TEXT NOT NULL,
    environment TEXT NOT NULL,
    signature TEXT NOT NULL,
    signature_sha256 TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    nonce TEXT NOT NULL UNIQUE,
    consumed_at TEXT,
    consumed_by_mission_id TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS authorization_revocations (
    authorization_digest TEXT PRIMARY KEY,
    approver_id TEXT NOT NULL,
    approver_key_fingerprint TEXT NOT NULL,
    trust_policy_sha256 TEXT NOT NULL,
    revoked_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    reason TEXT NOT NULL,
    nonce TEXT NOT NULL,
    signature TEXT NOT NULL,
    signature_sha256 TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS mission_evidence (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    mission_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    evidence_sha256 TEXT NOT NULL,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    authorization_digest TEXT,
    lease_id TEXT,
    UNIQUE (mission_id, kind, evidence_sha256)
);
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    source_version TEXT NOT NULL,
    applied_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_missions_authorization
    ON missions(authorization_digest) WHERE authorization_digest IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_reservations_authorization
    ON reservations(authorization_digest) WHERE authorization_digest IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_reservations_lease
    ON reservations(lease_id) WHERE lease_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transitions_mission ON transitions(mission_id);
CREATE INDEX IF NOT EXISTS idx_mission_evidence_mission ON mission_evidence(mission_id, kind);
"""


def _migrate_1_0_to_1_1(conn: sqlite3.Connection, *, now: str) -> None:
    """Migração atômica 1.0 -> 1.1; chamada dentro de BEGIN IMMEDIATE."""
    conn.execute(
        "ALTER TABLE missions ADD COLUMN authorization_mode TEXT NOT NULL "
        "DEFAULT 'LEGACY_UNVERIFIED'")
    conn.execute("ALTER TABLE missions ADD COLUMN authorization_digest TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN mission_snapshot_json TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN mission_snapshot_sha256 TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN environment TEXT")
    conn.execute(
        "ALTER TABLE missions ADD COLUMN transmissions_used INTEGER NOT NULL DEFAULT 0")
    conn.execute("ALTER TABLE missions ADD COLUMN receipt_json TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN receipt_sha256 TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN dispatch_evidence_json TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN dispatch_evidence_sha256 TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN reconciliation_json TEXT")
    conn.execute("ALTER TABLE missions ADD COLUMN reconciliation_sha256 TEXT")
    conn.execute(
        "UPDATE missions SET transmissions_used = "
        "CASE WHEN dispatched_at IS NOT NULL THEN 1 ELSE 0 END")
    conn.execute("ALTER TABLE transitions ADD COLUMN authorization_digest TEXT")
    conn.execute("ALTER TABLE reservations ADD COLUMN authorization_digest TEXT")
    conn.execute("ALTER TABLE reservations ADD COLUMN lease_id TEXT")
    conn.execute("ALTER TABLE reservations ADD COLUMN reserved_at TEXT")
    for statement in (
        """CREATE UNIQUE INDEX IF NOT EXISTS ux_missions_authorization
            ON missions(authorization_digest) WHERE authorization_digest IS NOT NULL""",
        """CREATE UNIQUE INDEX IF NOT EXISTS ux_reservations_authorization
            ON reservations(authorization_digest) WHERE authorization_digest IS NOT NULL""",
        """CREATE UNIQUE INDEX IF NOT EXISTS ux_reservations_lease
            ON reservations(lease_id) WHERE lease_id IS NOT NULL""",
        "CREATE INDEX IF NOT EXISTS idx_transitions_mission ON transitions(mission_id)",
        """CREATE TABLE IF NOT EXISTS authorizations (
            authorization_digest TEXT PRIMARY KEY,
            authorization_id TEXT NOT NULL UNIQUE,
            approval_reference TEXT NOT NULL,
            mission_id TEXT NOT NULL UNIQUE,
            mission_snapshot_sha256 TEXT NOT NULL,
            authority_material_sha256 TEXT NOT NULL,
            approver_id TEXT NOT NULL,
            approver_key_fingerprint TEXT NOT NULL,
            trust_policy_sha256 TEXT NOT NULL,
            environment TEXT NOT NULL,
            signature TEXT NOT NULL,
            signature_sha256 TEXT NOT NULL,
            issued_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE,
            consumed_at TEXT,
            consumed_by_mission_id TEXT,
            created_at TEXT NOT NULL
        )""",
        """CREATE TABLE IF NOT EXISTS authorization_revocations (
            authorization_digest TEXT PRIMARY KEY,
            approver_id TEXT NOT NULL,
            approver_key_fingerprint TEXT NOT NULL,
            trust_policy_sha256 TEXT NOT NULL,
            revoked_at TEXT NOT NULL,
            recorded_at TEXT NOT NULL,
            reason TEXT NOT NULL,
            nonce TEXT NOT NULL,
            signature TEXT NOT NULL,
            signature_sha256 TEXT NOT NULL
        )""",
        """CREATE TABLE IF NOT EXISTS mission_evidence (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            mission_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            evidence_json TEXT NOT NULL,
            evidence_sha256 TEXT NOT NULL,
            actor TEXT NOT NULL,
            source TEXT NOT NULL,
            recorded_at TEXT NOT NULL,
            authorization_digest TEXT,
            lease_id TEXT,
            UNIQUE (mission_id, kind, evidence_sha256)
        )""",
        "CREATE INDEX IF NOT EXISTS idx_mission_evidence_mission "
        "ON mission_evidence(mission_id, kind)",
        """CREATE TABLE IF NOT EXISTS schema_migrations (
            version TEXT PRIMARY KEY,
            source_version TEXT NOT NULL,
            applied_at TEXT NOT NULL
        )""",
    ):
        conn.execute(statement)
    conn.execute(
        "INSERT OR IGNORE INTO schema_migrations(version, source_version, applied_at) "
        "VALUES (?, ?, ?)", (SCHEMA_VERSION, OLD_SCHEMA_VERSION, now))
    conn.execute("UPDATE meta SET value=? WHERE key='schema_version'", (SCHEMA_VERSION,))


class Ledger:
    """Ledger local, por projeto/política injetados; sem efeitos externos."""

    def __init__(
        self,
        db_path: str | os.PathLike[str],
        *,
        project_id: str,
        repository_id: str,
        policy: Optional[Mapping[str, Any]] = None,
        allow_migration: bool = False,
        authority_verifier: Optional[AuthorityVerifier] = None,
        clock: Optional[Callable[[], datetime]] = None,
    ) -> None:
        if not project_id or not repository_id:
            raise LedgerError("project_id e repository_id são obrigatórios")
        self.db_path = Path(db_path)
        self.project_id = project_id
        self.repository_id = repository_id
        self.policy = dict(policy or {})
        self.allow_migration = bool(allow_migration)
        self.authority_verifier = authority_verifier
        self.clock = clock or utc_now
        self._local = threading.local()
        self._initialize()

    def _now(self) -> datetime:
        value = self.clock()
        if not isinstance(value, datetime) or value.tzinfo is None:
            raise LedgerError("clock deve retornar datetime com fuso")
        return value.astimezone(timezone.utc)

    # ---------- infraestrutura ----------
    def _connect(self) -> sqlite3.Connection:
        try:
            conn = sqlite3.connect(
                self.db_path,
                timeout=30,
                isolation_level=None,
                check_same_thread=False,
            )
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA busy_timeout=30000")
            return conn
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao abrir banco: {exc}") from exc

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            yield conn
            conn.execute("COMMIT")
        except LedgerError:
            conn.execute("ROLLBACK")
            raise
        except sqlite3.Error as exc:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise LedgerStorageError(f"falha de transação: {exc}") from exc
        finally:
            conn.close()

    def _harden_files(self) -> None:
        """Permissões privadas; nunca segue links nem altera diretórios alheios."""
        for path in (
            self.db_path,
            Path(str(self.db_path) + "-wal"),
            Path(str(self.db_path) + "-shm"),
        ):
            try:
                if path.is_symlink():
                    raise LedgerStorageError(f"arquivo auxiliar simbólico: {path}")
                if path.exists():
                    os.chmod(path, 0o600, follow_symlinks=False)
            except OSError as exc:
                raise LedgerStorageError(f"falha ao proteger {path}: {exc}") from exc

    @contextmanager
    def _private_umask(self) -> Iterator[None]:
        """Umask restritivo desde a criação; restaurado sempre."""
        with _UMASK_LOCK:
            previous = os.umask(0o077)
            try:
                yield
            finally:
                os.umask(previous)

    def _validate_paths(self) -> bool:
        """Valida caminhos em modo somente leitura; retorna se o pai já existia."""
        for path in (self.db_path, *self.db_path.parents):
            if path.is_symlink():
                raise LedgerStorageError("caminho simbólico não é permitido")
        for suffix in ("-wal", "-shm"):
            aux = Path(str(self.db_path) + suffix)
            if aux.is_symlink():
                raise LedgerStorageError(
                    f"arquivo auxiliar simbólico antes da abertura: {aux}"
                )
        parent_existed = self.db_path.parent.exists()
        if parent_existed:
            mode = self.db_path.parent.stat().st_mode
            if mode & 0o077:
                raise LedgerStorageError(
                    "diretório preexistente não é privado; não será modificado"
                )
        return parent_existed

    def _check_compatibility(self) -> tuple[bool, Optional[str]]:
        """Retorna (banco_novo, versão). Valida antes de DDL/chmod."""
        if not self.db_path.exists():
            return True, None
        try:
            uri = self.db_path.resolve().as_uri() + "?mode=ro"
            conn = sqlite3.connect(uri, uri=True)
            try:
                conn.row_factory = sqlite3.Row
                tables = {
                    row["name"]
                    for row in conn.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    )
                }
                if not tables:
                    return True, None
                if "meta" not in tables:
                    raise LedgerStorageError(
                        "banco preexistente sem versão: migração não implementada"
                    )
                version = conn.execute(
                    "SELECT value FROM meta WHERE key='schema_version'"
                ).fetchone()
                if version is None:
                    raise LedgerStorageError("esquema sem schema_version")
                value = version["value"]
                if value not in {OLD_SCHEMA_VERSION, SCHEMA_VERSION}:
                    raise LedgerStorageError(f"esquema incompatível: {value}")
            finally:
                conn.close()
            return False, value
        except LedgerStorageError:
            raise
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao validar banco: {exc}") from exc

    def _initialize(self) -> None:
        with self._private_umask():
            parent_existed = self._validate_paths()
            fresh, version = self._check_compatibility()
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            if not parent_existed:
                os.chmod(self.db_path.parent, 0o700)
            try:
                if fresh:
                    # Meta e versão primeiro, em transação própria. Se a
                    # inicialização for interrompida, o rollback deixa um banco
                    # vazio (reconhecido como novo na reabertura).
                    with self._write() as conn:
                        conn.execute(
                            "CREATE TABLE IF NOT EXISTS meta "
                            "(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                        )
                        conn.execute(
                            "INSERT OR IGNORE INTO meta (key, value) "
                            "VALUES ('schema_version', ?)",
                            (SCHEMA_VERSION,),
                        )
                elif version == OLD_SCHEMA_VERSION:
                    if not self.allow_migration:
                        raise SchemaMigrationRequired(
                            "banco coord-ledger-1.0 exige allow_migration=True")
                    with self._write() as conn:
                        _migrate_1_0_to_1_1(conn, now=iso(self._now()))
                with self._connect() as conn:
                    conn.executescript(_SCHEMA_1_1_SQL)
                    if fresh:
                        conn.execute(
                            "INSERT OR IGNORE INTO schema_migrations"
                            "(version, source_version, applied_at) VALUES (?,?,?)",
                            (SCHEMA_VERSION, "fresh", iso(self._now())),
                        )
                self._harden_files()
            except LedgerError:
                raise
            except sqlite3.Error as exc:
                raise LedgerStorageError(f"falha ao inicializar banco: {exc}") from exc


    def _assert_scope(self, row: sqlite3.Row) -> None:
        if (
            row["project_id"] != self.project_id
            or row["repository_id"] != self.repository_id
        ):
            raise LedgerError("projeto/repositório divergentes")

    # ---------- leitura ----------
    def get(self, mission_id: str) -> Optional[MissionRecord]:
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
                ).fetchone()
            if row and (
                row["project_id"] != self.project_id
                or row["repository_id"] != self.repository_id
            ):
                return None
            return self._record(row) if row else None
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler missão: {exc}") from exc

    def history(self, mission_id: str) -> list[Transition]:
        try:
            with self._connect() as conn:
                rows = conn.execute(
                    """SELECT t.*, m.project_id, m.repository_id
                       FROM transitions t JOIN missions m ON m.mission_id=t.mission_id
                       WHERE t.mission_id=? ORDER BY t.seq ASC""",
                    (mission_id,),
                ).fetchall()
                for row in rows:
                    self._assert_scope(row)
            return [
                Transition(
                    seq=int(row["seq"]),
                    mission_id=row["mission_id"],
                    from_state=row["from_state"],
                    to_state=row["to_state"],
                    evidence=row["evidence"],
                    actor=row["actor"],
                    at=row["at"],
                    authorization_digest=row["authorization_digest"],
                )
                for row in rows
            ]
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler histórico: {exc}") from exc

    @staticmethod
    def _record(row: sqlite3.Row) -> MissionRecord:
        return MissionRecord(
            mission_id=row["mission_id"],
            operation_id=row["operation_id"],
            project_id=row["project_id"],
            repository_id=row["repository_id"],
            coordinator_id=row["coordinator_id"],
            agent=row["agent"],
            session_id=row["session_id"],
            instance_id=row["instance_id"],
            worktree=row["worktree"],
            authorization_reference=row["authorization_reference"],
            payload_sha256=row["payload_sha256"],
            identity_hash=row["identity_hash"],
            state=row["state"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            dispatched_at=row["dispatched_at"],
            timeout_seconds=int(row["timeout_seconds"] or 0),
            authorization_mode=row["authorization_mode"] or AUTHORIZATION_MODE_LEGACY,
            authorization_digest=row["authorization_digest"],
            mission_snapshot_sha256=row["mission_snapshot_sha256"],
            environment=row["environment"],
            transmissions_used=int(row["transmissions_used"] or 0),
            receipt_sha256=row["receipt_sha256"],
            dispatch_evidence_sha256=row["dispatch_evidence_sha256"],
            reconciliation_sha256=row["reconciliation_sha256"],
        )

    @staticmethod
    def _identity(record: Mapping[str, Any]) -> str:
        material = {
            "mission_id": record["mission_id"],
            "operation_id": record["operation_id"],
            "project_id": record["project_id"],
            "repository_id": record["repository_id"],
            "coordinator_id": record["coordinator_id"],
            "agent": record["agent"],
            "session_id": record["session_id"],
            "instance_id": record["instance_id"],
            "worktree": record["worktree"],
            "authorization_reference": record["authorization_reference"],
            "payload_sha256": record["payload_sha256"],
            "timeout_seconds": int(record.get("timeout_seconds", 0)),
        }
        snapshot_sha = record.get("mission_snapshot_sha256")
        if snapshot_sha:
            material["mission_snapshot_sha256"] = snapshot_sha
        return hashlib.sha256(canonical_json(material).encode("utf-8")).hexdigest()

    # ---------- escrita ----------
    def register_proposed(
        self,
        *,
        mission_id: str,
        operation_id: str,
        coordinator_id: str,
        agent: str,
        session_id: str,
        instance_id: str,
        worktree: str,
        authorization_reference: str,
        payload: Mapping[str, Any],
        timeout_seconds: int = 0,
    ) -> MissionRecord:
        if timeout_seconds < 0:
            raise LedgerError("timeout_seconds não pode ser negativo")
        for label, value in {
            "mission_id": mission_id,
            "operation_id": operation_id,
            "coordinator_id": coordinator_id,
            "agent": agent,
            "session_id": session_id,
            "instance_id": instance_id,
            "worktree": worktree,
            "authorization_reference": authorization_reference,
        }.items():
            if not isinstance(value, str) or not value.strip():
                raise LedgerError(f"{label} é obrigatório")
        digest = payload_sha256(payload)
        now = iso(utc_now())
        material = {
            "mission_id": mission_id,
            "operation_id": operation_id,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "coordinator_id": coordinator_id,
            "agent": agent,
            "session_id": session_id,
            "instance_id": instance_id,
            "worktree": worktree,
            "authorization_reference": authorization_reference,
            "payload_sha256": digest,
            "timeout_seconds": timeout_seconds,
        }
        identity = self._identity(material)
        with self._write() as conn:
            existing = conn.execute(
                "SELECT * FROM missions WHERE mission_id=? OR operation_id=?",
                (mission_id, operation_id),
            ).fetchone()
            if existing:
                if (
                    existing["mission_id"] != mission_id
                    or existing["operation_id"] != operation_id
                    or existing["payload_sha256"] != digest
                    or existing["identity_hash"] != identity
                    or int(existing["timeout_seconds"] or 0) != timeout_seconds
                ):
                    raise DuplicateMission("missão/ operaçao repetida com identidade divergente")
                return self._record(existing)
            conn.execute(
                """INSERT INTO missions (
                    mission_id, operation_id, project_id, repository_id,
                    coordinator_id, agent, session_id, instance_id, worktree,
                    authorization_reference, payload_sha256, identity_hash,
                    state, created_at, updated_at, dispatched_at, timeout_seconds
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)""",
                (
                    mission_id,
                    operation_id,
                    self.project_id,
                    self.repository_id,
                    coordinator_id,
                    agent,
                    session_id,
                    instance_id,
                    worktree,
                    authorization_reference,
                    digest,
                    identity,
                    PROPOSED,
                    now,
                    now,
                    timeout_seconds,
                ),
            )
            self._append(conn, mission_id, None, PROPOSED, "registro inicial", coordinator_id, now)
        record = self.get(mission_id)
        assert record is not None
        return record

    # ---------- COORD-05A-EXT / ledger 1.1 ----------
    def register_signed_proposed(
        self,
        snapshot: MissionSnapshot,
        *,
        authorization_reference: str,
        timeout_seconds: int = 0,
    ) -> MissionRecord:
        """Registra material assinado imutável; ainda não é autorização."""
        if not isinstance(snapshot, MissionSnapshot):
            raise LedgerError("snapshot deve ser MissionSnapshot")
        snapshot.validate()
        if snapshot.project_id != self.project_id or snapshot.repository_id != self.repository_id:
            raise LedgerError("snapshot diverge do projeto/repositório do ledger")
        if not isinstance(authorization_reference, str) or not authorization_reference.strip():
            raise LedgerError("authorization_reference é obrigatório")
        if timeout_seconds < 0:
            raise LedgerError("timeout_seconds não pode ser negativo")
        digest = snapshot.payload_sha256
        snapshot_json = canonical_json(snapshot.canonical_material())
        snapshot_sha = snapshot.sha256()
        now = iso(self._now())
        material = {
            "mission_id": snapshot.mission_id,
            "operation_id": snapshot.operation_id,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "coordinator_id": snapshot.coordinator_id,
            "agent": snapshot.agent,
            "session_id": snapshot.session_id,
            "instance_id": snapshot.instance_id,
            "worktree": snapshot.worktree,
            "authorization_reference": authorization_reference,
            "payload_sha256": digest,
            "timeout_seconds": timeout_seconds,
            "mission_snapshot_sha256": snapshot_sha,
        }
        identity = self._identity(material)
        with self._write() as conn:
            existing = conn.execute(
                "SELECT * FROM missions WHERE mission_id=? OR operation_id=?",
                (snapshot.mission_id, snapshot.operation_id),
            ).fetchone()
            if existing:
                if (
                    existing["mission_id"] != snapshot.mission_id
                    or existing["operation_id"] != snapshot.operation_id
                    or existing["authorization_mode"] != AUTHORIZATION_MODE_SIGNED
                    or existing["mission_snapshot_sha256"] != snapshot_sha
                    or existing["payload_sha256"] != digest
                    or existing["identity_hash"] != identity
                    or existing["authorization_reference"] != authorization_reference
                    or int(existing["timeout_seconds"] or 0) != timeout_seconds
                ):
                    raise DuplicateMission(
                        "missão/operação repetida com material assinado divergente")
                return self._record(existing)
            conn.execute(
                """INSERT INTO missions (
                    mission_id, operation_id, project_id, repository_id,
                    coordinator_id, agent, session_id, instance_id, worktree,
                    authorization_reference, payload_sha256, identity_hash,
                    state, created_at, updated_at, dispatched_at, timeout_seconds,
                    authorization_mode, authorization_digest, mission_snapshot_json,
                    mission_snapshot_sha256, environment, transmissions_used
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,NULL,?,?,?,0)""",
                (
                    snapshot.mission_id, snapshot.operation_id, self.project_id,
                    self.repository_id, snapshot.coordinator_id, snapshot.agent,
                    snapshot.session_id, snapshot.instance_id, snapshot.worktree,
                    authorization_reference, digest, identity, PROPOSED, now, now,
                    timeout_seconds, AUTHORIZATION_MODE_SIGNED, snapshot_json,
                    snapshot_sha, snapshot.environment,
                ),
            )
            self._append(
                conn, snapshot.mission_id, None, PROPOSED,
                canonical_json({"mission_snapshot_sha256": snapshot_sha}),
                snapshot.coordinator_id, now,
            )
        record = self.get(snapshot.mission_id)
        assert record is not None
        return record

    def _snapshot_from_row(self, row: sqlite3.Row) -> MissionSnapshot:
        raw = row["mission_snapshot_json"]
        if not raw or not row["mission_snapshot_sha256"]:
            raise SignedAuthorizationRequired("missão sem snapshot canônico")
        try:
            value = json.loads(raw)
            target = value["target"]
            snapshot = MissionSnapshot(
                mission_id=value["mission_id"],
                operation_id=value["operation_id"],
                project_id=value["project_id"],
                repository_id=value["repository_id"],
                coordinator_id=value["coordinator_id"],
                agent=target[0], session_id=target[1], worktree=target[2],
                instance_id=target[3], mission_identity=value["mission_identity"],
                payload=value["payload"], model=value["model"], scope=value["scope"],
                acceptance_criteria=tuple(value["acceptance_criteria"]),
                environment=value["environment"],
                reviewed_head_sha=value["reviewed_head_sha"],
                operational_digests=value["operational_digests"],
                max_transmissions=int(value["max_transmissions"]),
                cost_ceiling_usd=value.get("cost_ceiling_usd"),
                free_only=bool(value["free_only"]),
            )
            snapshot.validate()
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise LedgerStorageError("snapshot canônico inválido") from exc
        actual = snapshot.sha256()
        if actual != row["mission_snapshot_sha256"]:
            raise LedgerStorageError("snapshot canônico adulterado")
        if (
            snapshot.mission_id != row["mission_id"]
            or snapshot.operation_id != row["operation_id"]
            or snapshot.project_id != row["project_id"]
            or snapshot.repository_id != row["repository_id"]
            or snapshot.coordinator_id != row["coordinator_id"]
            or snapshot.target_key != (
                row["agent"], row["session_id"], row["worktree"], row["instance_id"])
            or snapshot.payload_sha256 != row["payload_sha256"]
            or snapshot.environment != row["environment"]
        ):
            raise LedgerStorageError("snapshot diverge da linha da missão")
        return snapshot

    def get_authorization(self, mission_id: str) -> Optional[AuthorizationRecord]:
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM authorizations WHERE mission_id=?", (mission_id,)
                ).fetchone()
            return None if row is None else self._authorization_record(row)
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler autorização: {exc}") from exc

    def get_authorization_by_digest(self, digest: str) -> Optional[AuthorizationRecord]:
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM authorizations WHERE authorization_digest=?", (digest,)
                ).fetchone()
            return None if row is None else self._authorization_record(row)
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler autorização: {exc}") from exc

    @staticmethod
    def _authorization_record(row: sqlite3.Row) -> AuthorizationRecord:
        return AuthorizationRecord(
            authorization_digest=row["authorization_digest"],
            authorization_id=row["authorization_id"],
            approval_reference=row["approval_reference"],
            mission_id=row["mission_id"],
            mission_snapshot_sha256=row["mission_snapshot_sha256"],
            authority_material_sha256=row["authority_material_sha256"],
            approver_id=row["approver_id"],
            approver_key_fingerprint=row["approver_key_fingerprint"],
            trust_policy_sha256=row["trust_policy_sha256"],
            environment=row["environment"],
            issued_at=row["issued_at"],
            expires_at=row["expires_at"],
            nonce=row["nonce"],
            consumed_at=row["consumed_at"],
            consumed_by_mission_id=row["consumed_by_mission_id"],
            created_at=row["created_at"],
        )

    def get_revocation(self, digest: str) -> Optional[RevocationRecord]:
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM authorization_revocations WHERE authorization_digest=?",
                    (digest,),
                ).fetchone()
            if row is None:
                return None
            return RevocationRecord(
                authorization_digest=row["authorization_digest"],
                approver_id=row["approver_id"],
                approver_key_fingerprint=row["approver_key_fingerprint"],
                trust_policy_sha256=row["trust_policy_sha256"],
                revoked_at=row["revoked_at"],
                recorded_at=row["recorded_at"],
                reason=row["reason"],
                nonce=row["nonce"],
                signature_sha256=row["signature_sha256"],
            )
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler revogação: {exc}") from exc

    def get_evidence(self, mission_id: str) -> list[EvidenceRecord]:
        try:
            with self._connect() as conn:
                rows = conn.execute(
                    "SELECT * FROM mission_evidence WHERE mission_id=? ORDER BY seq",
                    (mission_id,),
                ).fetchall()
            return [
                EvidenceRecord(
                    seq=int(row["seq"]), mission_id=row["mission_id"],
                    kind=row["kind"], evidence_sha256=row["evidence_sha256"],
                    actor=row["actor"], source=row["source"],
                    recorded_at=row["recorded_at"],
                    authorization_digest=row["authorization_digest"],
                    lease_id=row["lease_id"],
                )
                for row in rows
            ]
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler evidências: {exc}") from exc

    def authorize_signed(
        self,
        mission_id: str,
        *,
        envelope: Any,
        signature: bytes,
        payload: Mapping[str, Any],
        actor: str,
    ) -> MissionRecord:
        """Verifica pelo port injetado e persiste autorização+digest atomicamente."""
        if self.authority_verifier is None:
            raise AuthorizationMissing("authority_verifier não injetado")
        if not isinstance(payload, Mapping):
            raise LedgerError("payload deve ser mapping")
        record = self.get(mission_id)
        if record is None:
            raise LedgerError("missão inexistente")
        if record.authorization_mode != AUTHORIZATION_MODE_SIGNED:
            raise SignedAuthorizationRequired("authorize_signed exige missão SIGNED_V1")
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
        if row is None:
            raise LedgerError("missão inexistente")
        self._assert_scope(row)
        snapshot = self._snapshot_from_row(row)
        if payload_sha256(payload) != snapshot.payload_sha256:
            raise EvidenceDivergence("payload diverge do snapshot autorizado")
        now = self._now()
        if record.state not in {PROPOSED, AUTHORIZED}:
            raise InvalidTransition(f"autorização não permitida em {record.state}")
        if record.state == AUTHORIZED:
            existing = self.get_authorization(mission_id)
            if existing is not None and existing.mission_snapshot_sha256 == snapshot.sha256():
                return record
            raise EvidenceDivergence("missão autorizada com snapshot divergente")
        expected = snapshot.expected_operation()
        verified = self.authority_verifier.verify_authorization(
            envelope=envelope, signature=signature, payload=payload,
            expected=expected, now=now,
        )
        verified.validate()
        if verified.mission_id != mission_id:
            raise AuthorizationRejected("autorização para outra missão")
        if verified.mission_identity != snapshot.mission_identity:
            raise AuthorizationRejected("identidade da missão divergente")
        if verified.authority_material_sha256 != snapshot.authority_material_sha256():
            raise AuthorizationRejected("material de autorização divergente")
        if verified.approval_reference != record.authorization_reference:
            raise AuthorizationRejected("referência de aprovação divergente")
        if verified.environment != snapshot.environment:
            raise AuthorizationRejected("ambiente da autorização divergente")
        issued = verified.issued_at.astimezone(timezone.utc)
        expires = verified.expires_at.astimezone(timezone.utc)
        if now < issued:
            raise AuthorizationExpired("autorização ainda não válida")
        if now >= expires:
            raise AuthorizationExpired("autorização expirada")
        signature_bytes = bytes(signature)
        signature_sha = hashlib.sha256(signature_bytes).hexdigest()
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            self._assert_scope(row)
            if row["authorization_mode"] != AUTHORIZATION_MODE_SIGNED:
                raise SignedAuthorizationRequired("missão não é SIGNED_V1")
            snapshot = self._snapshot_from_row(row)
            current = row["state"]
            existing = conn.execute(
                "SELECT * FROM authorizations WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if current == AUTHORIZED and existing is not None:
                if existing["authorization_digest"] == verified.authorization_digest:
                    return self._record(row)
                raise DuplicateMission("missão já autorizada com outro digest")
            if current != PROPOSED:
                raise InvalidTransition(f"autorização exige PROPOSED (observado: {current})")
            if conn.execute(
                "SELECT 1 FROM authorization_revocations WHERE authorization_digest=?",
                (verified.authorization_digest,),
            ).fetchone():
                raise AuthorizationRevoked("digest já revogado")
            if existing is not None:
                raise DuplicateMission("missão já possui autorização")
            try:
                conn.execute(
                    """INSERT INTO authorizations (
                        authorization_digest, authorization_id, approval_reference,
                        mission_id, mission_snapshot_sha256, authority_material_sha256,
                        approver_id, approver_key_fingerprint, trust_policy_sha256,
                        environment, signature, signature_sha256, issued_at, expires_at,
                        nonce, consumed_at, consumed_by_mission_id, created_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?)""",
                    (
                        verified.authorization_digest, verified.authorization_id,
                        verified.approval_reference, mission_id,
                        snapshot.sha256(), verified.authority_material_sha256,
                        verified.approver_id, verified.approver_key_fingerprint,
                        verified.trust_policy_sha256, verified.environment,
                        signature_bytes.hex(), signature_sha, iso(issued), iso(expires),
                        verified.nonce, iso(now),
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise DuplicateMission("digest/autorização já utilizada") from exc
            conn.execute(
                """UPDATE missions SET state=?, authorization_digest=?, updated_at=?
                   WHERE mission_id=?""",
                (AUTHORIZED, verified.authorization_digest, iso(now), mission_id),
            )
            self._append(
                conn, mission_id, PROPOSED, AUTHORIZED,
                canonical_json({"authorization_digest": verified.authorization_digest}),
                actor, iso(now), verified.authorization_digest,
            )
        return self.get(mission_id)

    def revoke_authorization(
        self,
        *,
        statement: Any,
        signature: bytes,
        actor: str,
    ) -> RevocationRecord:
        if self.authority_verifier is None:
            raise AuthorizationMissing("authority_verifier não injetado")
        now = self._now()
        verified = self.authority_verifier.verify_revocation(
            statement=statement, signature=signature, now=now)
        verified.validate()
        signature_bytes = bytes(signature)
        signature_sha = hashlib.sha256(signature_bytes).hexdigest()
        with self._write() as conn:
            existing = conn.execute(
                "SELECT * FROM authorization_revocations WHERE authorization_digest=?",
                (verified.authorization_digest,),
            ).fetchone()
            if existing is not None:
                if (existing["signature_sha256"] == signature_sha
                        and existing["revoked_at"] == iso(verified.revoked_at)
                        and existing["reason"] == verified.reason):
                    return RevocationRecord(
                        authorization_digest=existing["authorization_digest"],
                        approver_id=existing["approver_id"],
                        approver_key_fingerprint=existing["approver_key_fingerprint"],
                        trust_policy_sha256=existing["trust_policy_sha256"],
                        revoked_at=existing["revoked_at"],
                        recorded_at=existing["recorded_at"],
                        reason=existing["reason"], nonce=existing["nonce"],
                        signature_sha256=existing["signature_sha256"],
                    )
                raise EvidenceDivergence("revogação divergente para o mesmo digest")
            recorded = iso(now)
            conn.execute(
                """INSERT INTO authorization_revocations (
                    authorization_digest, approver_id, approver_key_fingerprint,
                    trust_policy_sha256, revoked_at, recorded_at, reason, nonce,
                    signature, signature_sha256
                ) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (
                    verified.authorization_digest, verified.approver_id,
                    verified.approver_key_fingerprint, verified.trust_policy_sha256,
                    iso(verified.revoked_at), recorded, verified.reason,
                    verified.nonce, signature_bytes.hex(), signature_sha,
                ),
            )
        result = self.get_revocation(verified.authorization_digest)
        assert result is not None
        return result

    def reserve_dispatch_signed(
        self,
        mission_id: str,
        *,
        authorization_digest: str,
        agent: str,
        session_id: str,
        instance_id: str,
        worktree: str,
        actor: str,
    ) -> DispatchLease:
        now = self._now()
        now_s = iso(now)
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            self._assert_scope(row)
            if row["authorization_mode"] != AUTHORIZATION_MODE_SIGNED:
                raise SignedAuthorizationRequired("reserva assinada exige SIGNED_V1")
            if row["state"] != AUTHORIZED:
                raise InvalidTransition("reserva assinada exige AUTHORIZED")
            if row["authorization_digest"] != authorization_digest:
                raise AuthorizationMissing("digest não corresponde à missão")
            snapshot = self._snapshot_from_row(row)
            if snapshot.target_key != (agent, session_id, worktree, instance_id):
                raise LedgerError("destino divergente do snapshot")
            auth = conn.execute(
                "SELECT * FROM authorizations WHERE authorization_digest=? AND mission_id=?",
                (authorization_digest, mission_id),
            ).fetchone()
            if auth is None:
                raise AuthorizationMissing("autorização persistida ausente")
            if auth["mission_snapshot_sha256"] != snapshot.sha256():
                raise EvidenceDivergence("autorização não vincula o snapshot atual")
            if auth["authority_material_sha256"] != snapshot.authority_material_sha256():
                raise EvidenceDivergence("material de autoridade divergente")
            if auth["consumed_at"] is not None:
                raise AuthorizationRevoked("autorização já consumida")
            expires = parse_iso(auth["expires_at"])
            if now >= expires:
                raise AuthorizationExpired("autorização expirada na reserva")
            if conn.execute(
                "SELECT 1 FROM authorization_revocations WHERE authorization_digest=?",
                (authorization_digest,),
            ).fetchone():
                raise AuthorizationRevoked("autorização revogada")
            if int(row["transmissions_used"] or 0) != 0:
                raise AuthorizationExpired("limite de transmissões já consumido")
            if conn.execute(
                "SELECT 1 FROM reservations WHERE mission_id=?", (mission_id,)
            ).fetchone():
                raise DuplicateReservation("operação já reservada")
            for column, value, label in (
                ("agent", agent, "agente"), ("session_id", session_id, "sessão"),
            ):
                busy = conn.execute(
                    f"""SELECT mission_id FROM reservations
                        WHERE project_id=? AND {column}=? AND mission_id<>?""",
                    (self.project_id, value, mission_id),
                ).fetchone()
                if busy:
                    raise DuplicateReservation(
                        f"{label} ocupado pela missão {busy['mission_id']}")
            lease_id = secrets.token_hex(32)
            try:
                conn.execute(
                    """INSERT INTO reservations (
                        project_id, agent, session_id, mission_id,
                        authorization_digest, lease_id, reserved_at
                    ) VALUES (?,?,?,?,?,?,?)""",
                    (self.project_id, agent, session_id, mission_id,
                     authorization_digest, lease_id, now_s),
                )
            except sqlite3.IntegrityError as exc:
                raise DuplicateReservation("destinatário/autorização já reservado") from exc
            conn.execute(
                """UPDATE missions SET state=?, updated_at=?, dispatched_at=?,
                   transmissions_used=1 WHERE mission_id=?""",
                (DISPATCHING, now_s, now_s, mission_id),
            )
            conn.execute(
                """UPDATE authorizations SET consumed_at=?, consumed_by_mission_id=?
                   WHERE authorization_digest=?""",
                (now_s, mission_id, authorization_digest),
            )
            self._append(
                conn, mission_id, AUTHORIZED, DISPATCHING,
                canonical_json({"authorization_digest": authorization_digest,
                                "lease_id": lease_id}),
                actor, now_s, authorization_digest,
            )
            self._insert_evidence(
                conn, mission_id, "RESERVATION",
                {"authorization_digest": authorization_digest, "lease_id": lease_id},
                actor=actor, source="ledger", at=now_s,
                authorization_digest=authorization_digest, lease_id=lease_id)
        return DispatchLease(
            mission_id=mission_id, authorization_digest=authorization_digest,
            lease_id=lease_id, target_key=(agent, session_id, worktree, instance_id),
            reserved_at=now, expires_at=expires,
        )

    def _insert_evidence(
        self, conn: sqlite3.Connection, mission_id: str, kind: str,
        value: Mapping[str, Any], *, actor: str, source: str, at: str,
        authorization_digest: Optional[str] = None, lease_id: Optional[str] = None,
    ) -> str:
        raw = canonical_json(dict(value))
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        conn.execute(
            """INSERT OR IGNORE INTO mission_evidence (
                mission_id, kind, evidence_json, evidence_sha256, actor, source,
                recorded_at, authorization_digest, lease_id
            ) VALUES (?,?,?,?,?,?,?,?,?)""",
            (mission_id, kind, raw, digest, actor, source, at,
             authorization_digest, lease_id),
        )
        return digest

    def assert_dispatchable(
        self,
        mission_id: str,
        *,
        authorization_digest: str,
        lease_id: str,
        actor: str,
    ) -> DispatchLease:
        """Leitura imediatamente antes do POST; não consome e não reenvia."""
        now = self._now()
        try:
            with self._connect() as conn:
                row = conn.execute(
                    """SELECT m.*, r.lease_id AS lease_id,
                              r.authorization_digest AS reservation_digest,
                              a.expires_at AS auth_expires_at,
                              a.consumed_at AS auth_consumed_at
                       FROM missions m
                       LEFT JOIN reservations r ON r.mission_id=m.mission_id
                       LEFT JOIN authorizations a
                         ON a.authorization_digest=m.authorization_digest
                       WHERE m.mission_id=?""",
                    (mission_id,),
                ).fetchone()
                revoked = conn.execute(
                    "SELECT 1 FROM authorization_revocations WHERE authorization_digest=?",
                    (authorization_digest,),
                ).fetchone()
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao validar despacho: {exc}") from exc
        if row is None:
            raise DispatchBlocked("missão inexistente")
        self._assert_scope(row)
        if row["authorization_mode"] != AUTHORIZATION_MODE_SIGNED:
            raise SignedAuthorizationRequired("assert_dispatchable exige SIGNED_V1")
        if row["state"] != DISPATCHING:
            raise DispatchBlocked(f"estado não despachável: {row['state']}")
        if row["authorization_digest"] != authorization_digest:
            raise DispatchBlocked("digest não corresponde")
        if row["reservation_digest"] != authorization_digest or row["lease_id"] != lease_id:
            raise DispatchBlocked("reserva/lease divergente")
        if row["auth_consumed_at"] is None or row["auth_expires_at"] is None:
            raise DispatchBlocked("autorização não consumida/persistida")
        if revoked is not None:
            raise AuthorizationRevoked("autorização revogada")
        if now >= parse_iso(row["auth_expires_at"]):
            raise AuthorizationExpired("autorização expirada")
        if int(row["transmissions_used"] or 0) != 1:
            raise DispatchBlocked("consumo de transmissão inválido")
        snapshot = self._snapshot_from_row(row)
        return DispatchLease(
            mission_id=mission_id, authorization_digest=authorization_digest,
            lease_id=lease_id, target_key=snapshot.target_key,
            reserved_at=parse_iso(row["dispatched_at"]),
            expires_at=parse_iso(row["auth_expires_at"]),
        )

    def _validate_transport_material(
        self, snapshot: MissionSnapshot, value: Mapping[str, Any], *, receipt: bool,
    ) -> None:
        if not isinstance(value, Mapping):
            raise EvidenceDivergence("evidência deve ser mapping")
        if value.get("environment") is not None and value["environment"] != snapshot.environment:
            raise EvidenceDivergence("ambiente divergente")
        allow_real = bool(self.policy.get("allow_real_transport", False))
        if not allow_real:
            if value.get("service_contacted") is True or value.get("transport_real") is True:
                raise DispatchBlocked("transporte real bloqueado")
            if value.get("source") == "real":
                raise DispatchBlocked("fonte real bloqueada")
        if value.get("operation_id") is not None and value["operation_id"] != snapshot.operation_id:
            raise EvidenceDivergence("operation_id divergente")
        if value.get("mission_id") is not None and value["mission_id"] != snapshot.mission_id:
            raise EvidenceDivergence("mission_id divergente")
        if value.get("payload_sha256") is not None and value["payload_sha256"] != snapshot.payload_sha256:
            raise EvidenceDivergence("payload divergente")
        for key, expected in (
            ("agent_id", snapshot.agent), ("agent", snapshot.agent),
            ("session_id", snapshot.session_id), ("instance_id", snapshot.instance_id),
            ("location", snapshot.worktree), ("worktree", snapshot.worktree),
        ):
            if value.get(key) is not None and value[key] != expected:
                raise EvidenceDivergence(f"{key} divergente")
        if receipt:
            observed = value.get("observed_message_id") or value.get("expected_message_id")
            if not isinstance(observed, str) or not observed.strip():
                raise EvidenceDivergence("receipt sem identificador observado")

    def record_dispatch_evidence(
        self,
        mission_id: str,
        *,
        authorization_digest: str,
        lease_id: str,
        receipt: Mapping[str, Any],
        evidence: Mapping[str, Any],
        actor: str,
    ) -> MissionRecord:
        """Persiste receipt/evidence imutáveis; não altera o estado da missão."""
        now = self._now()
        now_s = iso(now)
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            self._assert_scope(row)
            if row["authorization_mode"] != AUTHORIZATION_MODE_SIGNED:
                raise SignedAuthorizationRequired("evidência exige SIGNED_V1")
            if row["authorization_digest"] != authorization_digest:
                raise AuthorizationMissing("digest divergente")
            if row["state"] in {PROPOSED, AUTHORIZED}:
                raise InvalidTransition("evidência exige reserva")
            if row["state"] not in {DISPATCHING, DELIVERED, RUNNING, UNKNOWN, COMPLETED, FAILED}:
                raise InvalidTransition("estado incompatível com evidência")
            reservation = conn.execute(
                "SELECT lease_id FROM reservations WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if reservation is not None and reservation["lease_id"] != lease_id:
                raise DispatchBlocked("lease divergente")
            snapshot = self._snapshot_from_row(row)
            self._validate_transport_material(snapshot, receipt, receipt=True)
            self._validate_transport_material(snapshot, evidence, receipt=False)
            receipt_raw = canonical_json(dict(receipt))
            evidence_raw = canonical_json(dict(evidence))
            receipt_sha = hashlib.sha256(receipt_raw.encode("utf-8")).hexdigest()
            evidence_sha = hashlib.sha256(evidence_raw.encode("utf-8")).hexdigest()
            if row["receipt_sha256"] and row["receipt_sha256"] != receipt_sha:
                raise EvidenceDivergence("receipt divergente do persistido")
            if row["dispatch_evidence_sha256"] and row["dispatch_evidence_sha256"] != evidence_sha:
                raise EvidenceDivergence("evidência divergente da persistida")
            if row["receipt_sha256"] == receipt_sha and row["dispatch_evidence_sha256"] == evidence_sha:
                return self._record(row)
            conn.execute(
                """UPDATE missions SET receipt_json=?, receipt_sha256=?,
                   dispatch_evidence_json=?, dispatch_evidence_sha256=?, updated_at=?
                   WHERE mission_id=?""",
                (receipt_raw, receipt_sha, evidence_raw, evidence_sha, now_s, mission_id),
            )
            self._insert_evidence(
                conn, mission_id, "RECEIPT", receipt, actor=actor,
                source=str(receipt.get("source", "unknown")), at=now_s,
                authorization_digest=authorization_digest, lease_id=lease_id)
            self._insert_evidence(
                conn, mission_id, "DISPATCH", evidence, actor=actor,
                source=str(evidence.get("source", "unknown")), at=now_s,
                authorization_digest=authorization_digest, lease_id=lease_id)
        return self.get(mission_id)

    def _validate_report(
        self, snapshot: MissionSnapshot, report: Mapping[str, Any],
        receipt: Mapping[str, Any], now: datetime, created_at: str,
    ) -> None:
        if not isinstance(report, Mapping):
            raise UnsafeReconciliation("relatório deve ser mapping")
        checks = (
            ("project_id", snapshot.project_id), ("repository_id", snapshot.repository_id),
            ("mission_id", snapshot.mission_id), ("operation_id", snapshot.operation_id),
            ("agent_id", snapshot.agent), ("session_id", snapshot.session_id),
        )
        for key, expected in checks:
            if report.get(key) != expected:
                raise EvidenceDivergence(f"relatório: {key} divergente")
        if report.get("status") != "COMPLETED":
            raise UnsafeReconciliation("relatório não está COMPLETED")
        if report.get("blockers"):
            raise UnsafeReconciliation("relatório contém bloqueios")
        message_id = report.get("message_id")
        reply_to = report.get("reply_to")
        if not isinstance(message_id, str) or not message_id:
            raise EvidenceDivergence("relatório sem message_id")
        observed = receipt.get("observed_message_id") or receipt.get("expected_message_id")
        if not isinstance(observed, str) or not observed:
            raise EvidenceDivergence("receipt sem prompt observado")
        if reply_to != observed:
            raise EvidenceDivergence("relatório não responde ao receipt")
        results = report.get("acceptance_results")
        if not isinstance(results, Mapping):
            raise EvidenceDivergence("relatório sem acceptance_results")
        missing = [c for c in snapshot.acceptance_criteria if results.get(c) is not True]
        if missing:
            raise UnsafeReconciliation("critérios de aceitação incompletos")
        payload_hash = report.get("payload_sha256")
        if payload_hash is not None and payload_hash != snapshot.payload_sha256:
            raise EvidenceDivergence("payload do relatório divergente")
        completed = report.get("completed_at")
        if not isinstance(completed, str):
            raise EvidenceDivergence("completed_at ausente")
        try:
            completed_at = parse_iso(completed)
        except ValueError as exc:
            raise EvidenceDivergence("completed_at inválido") from exc
        if completed_at >= now + timedelta(minutes=5):
            raise UnsafeReconciliation("relatório no futuro")
        if completed_at < parse_iso(created_at):
            raise UnsafeReconciliation("relatório anterior à missão")

    def reconcile_signed(
        self,
        mission_id: str,
        *,
        authorization_digest: str,
        lease_id: str,
        target_state: str,
        report: Optional[Mapping[str, Any]] = None,
        evidence: Optional[Mapping[str, Any]] = None,
        authority_reference: str,
        actor: str,
        human_decision: bool = False,
    ) -> MissionRecord:
        """Reconciliação terminal vinculada a snapshot/receipt/lease persistidos."""
        if target_state not in TERMINAL:
            raise InvalidTransition("reconciliação assinada exige COMPLETED/FAILED")
        if not isinstance(authority_reference, str) or not authority_reference.strip():
            raise UnsafeReconciliation("authority_reference obrigatória")
        if report is not None and not isinstance(report, Mapping):
            raise UnsafeReconciliation("report deve ser mapping")
        if evidence is not None and not isinstance(evidence, Mapping):
            raise UnsafeReconciliation("evidence deve ser mapping")
        if not evidence and not human_decision and target_state == FAILED:
            raise UnsafeReconciliation("falha exige evidência/decisão")
        now = self._now()
        payload = {
            "target_state": target_state,
            "authority_reference": authority_reference,
            "report": dict(report) if report is not None else None,
            "evidence": dict(evidence) if evidence is not None else None,
            "human_decision": bool(human_decision),
        }
        reconciliation_sha = hashlib.sha256(
            canonical_json(payload).encode("utf-8")).hexdigest()
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            self._assert_scope(row)
            if row["authorization_mode"] != AUTHORIZATION_MODE_SIGNED:
                raise SignedTransitionRequired("reconcile_signed exige SIGNED_V1")
            if row["authorization_digest"] != authorization_digest:
                raise AuthorizationMissing("digest divergente")
            state = row["state"]
            if state in TERMINAL:
                if state == target_state and row["reconciliation_sha256"] == reconciliation_sha:
                    return self._record(row)
                raise EvidenceDivergence("estado terminal divergente")
            snapshot = self._snapshot_from_row(row)
            reservation = conn.execute(
                "SELECT lease_id FROM reservations WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if reservation is None or reservation["lease_id"] != lease_id:
                raise DispatchBlocked("lease/reserva ausente ou divergente")
            if target_state == COMPLETED:
                receipt_raw = row["receipt_json"]
                if not receipt_raw:
                    raise EvidenceDivergence("receipt ausente para conclusão")
                receipt_sha = hashlib.sha256(receipt_raw.encode("utf-8")).hexdigest()
                if row["receipt_sha256"] != receipt_sha:
                    raise LedgerStorageError("receipt persistido adulterado")
                receipt = json.loads(receipt_raw)
                self._validate_transport_material(snapshot, receipt, receipt=True)
                if report is None:
                    raise UnsafeReconciliation("COMPLETED exige relatório")
                self._validate_report(snapshot, report, receipt, now, row["created_at"])
            else:
                if report is not None:
                    if report.get("status") not in {"FAILED", "BLOCKED", "UNKNOWN"}:
                        raise UnsafeReconciliation("relatório incompatível com FAILED")
                    for key, expected in (
                        ("project_id", snapshot.project_id),
                        ("repository_id", snapshot.repository_id),
                        ("mission_id", snapshot.mission_id),
                        ("operation_id", snapshot.operation_id),
                        ("agent_id", snapshot.agent),
                        ("session_id", snapshot.session_id),
                    ):
                        if report.get(key) != expected:
                            raise EvidenceDivergence(f"relatório: {key} divergente")
                    if report.get("payload_sha256") not in (None, snapshot.payload_sha256):
                        raise EvidenceDivergence("payload do relatório divergente")
            now_s = iso(now)
            detail = canonical_json(payload)
            if state == DISPATCHING and target_state == COMPLETED:
                conn.execute("UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                             (DELIVERED, now_s, mission_id))
                self._append(conn, mission_id, DISPATCHING, DELIVERED, detail,
                             actor, now_s, authorization_digest)
                state = DELIVERED
            if state == DELIVERED and target_state == COMPLETED:
                conn.execute("UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                             (RUNNING, now_s, mission_id))
                self._append(conn, mission_id, DELIVERED, RUNNING, detail,
                             actor, now_s, authorization_digest)
                state = RUNNING
            if state == DISPATCHING and target_state == FAILED:
                conn.execute("UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                             (UNKNOWN, now_s, mission_id))
                self._append(conn, mission_id, DISPATCHING, UNKNOWN, detail,
                             actor, now_s, authorization_digest)
                state = UNKNOWN
            if state == DELIVERED and target_state == FAILED:
                conn.execute("UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                             (RUNNING, now_s, mission_id))
                self._append(conn, mission_id, DELIVERED, RUNNING, detail,
                             actor, now_s, authorization_digest)
                state = RUNNING
            if state not in {RUNNING, UNKNOWN} and state != target_state:
                raise InvalidTransition(f"{state} -> {target_state} não permitido")
            conn.execute(
                """UPDATE missions SET state=?, updated_at=?, reconciliation_json=?,
                   reconciliation_sha256=? WHERE mission_id=?""",
                (target_state, now_s, detail, reconciliation_sha, mission_id),
            )
            conn.execute("DELETE FROM reservations WHERE mission_id=?", (mission_id,))
            self._append(conn, mission_id, state, target_state, detail,
                         actor, now_s, authorization_digest)
            self._insert_evidence(
                conn, mission_id, "RECONCILE", payload, actor=actor,
                source="ledger", at=now_s,
                authorization_digest=authorization_digest, lease_id=lease_id)
        return self.get(mission_id)

    def _append(
        self,
        conn: sqlite3.Connection,
        mission_id: str,
        from_state: Optional[str],
        to_state: str,
        evidence: str,
        actor: str,
        at: Optional[str] = None,
        authorization_digest: Optional[str] = None,
    ) -> None:
        conn.execute(
            """INSERT INTO transitions
               (mission_id, from_state, to_state, evidence, actor, at,
                authorization_digest)
               VALUES (?,?,?,?,?,?,?)""",
            (mission_id, from_state, to_state, evidence, actor,
             at or iso(utc_now()), authorization_digest),
        )

    def _transition(
        self,
        mission_id: str,
        target: str,
        *,
        evidence: str,
        actor: str,
        require_evidence: bool = False,
        clear_reservation: bool = False,
        allow_unknown: bool = False,
        signed_ok: bool = False,
    ) -> MissionRecord:
        if target not in STATES:
            raise InvalidTransition(f"estado desconhecido: {target}")
        if require_evidence and not evidence.strip():
            raise UnsafeReconciliation("evidência/decisão humana é obrigatória")
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            if row["authorization_mode"] == AUTHORIZATION_MODE_SIGNED and not signed_ok:
                raise SignedTransitionRequired(
                    "missão SIGNED_V1 exige transição/reconciliação assinada")
            current = row["state"]
            if current in TERMINAL:
                raise InvalidTransition(f"estado terminal {current} é protegido")
            if current == UNKNOWN and not allow_unknown:
                raise InvalidTransition(
                    "saída de UNKNOWN exige reconciliação explícita"
                )
            self._assert_scope(row)
            if target not in ALLOWED_TRANSITIONS[current]:
                raise InvalidTransition(f"{current} -> {target} não permitido")
            now = iso(utc_now())
            conn.execute(
                "UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                (target, now, mission_id),
            )
            if clear_reservation:
                conn.execute(
                    "DELETE FROM reservations WHERE mission_id=?", (mission_id,)
                )
            self._append(conn, mission_id, current, target, evidence, actor, now)
        record = self.get(mission_id)
        assert record is not None
        return record

    def authorize(
        self,
        mission_id: str,
        *,
        authorization_reference: str,
        payload_sha256_value: str,
        actor: str,
    ) -> MissionRecord:
        record = self.get(mission_id)
        if record is None:
            raise LedgerError("missão inexistente")
        if record.authorization_mode == AUTHORIZATION_MODE_SIGNED:
            raise SignedAuthorizationRequired(
                "missão SIGNED_V1 exige authorize_signed")
        if self.policy.get("require_signed_authorization"):
            raise SignedAuthorizationRequired(
                "política exige autorização assinada; legado inelegível")
        if record.payload_sha256 != payload_sha256_value:
            raise DuplicateMission("hash do payload divergente da autorização")
        if record.authorization_reference != authorization_reference:
            raise LedgerError("referência de autorização divergente")
        return self._transition(
            mission_id,
            AUTHORIZED,
            evidence=f"autorização {authorization_reference}",
            actor=actor,
        )

    def reserve_dispatch(
        self,
        mission_id: str,
        *,
        agent: str,
        session_id: str,
        instance_id: str,
        worktree: str,
        actor: str,
    ) -> MissionRecord:
        now = iso(utc_now())
        with self._write() as conn:
            row = conn.execute(
                "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            if row["authorization_mode"] == AUTHORIZATION_MODE_SIGNED:
                raise SignedAuthorizationRequired(
                    "missão SIGNED_V1 exige reserve_dispatch_signed")
            if self.policy.get("require_signed_authorization"):
                raise SignedAuthorizationRequired(
                    "política exige autorização assinada; legado inelegível")
            self._assert_scope(row)
            if conn.execute(
                "SELECT project_id FROM reservations WHERE mission_id=?", (mission_id,)
            ).fetchone():
                raise DuplicateReservation("operação já reservada")
            if row["state"] != AUTHORIZED:
                raise InvalidTransition("reserva exige AUTHORIZED")
            if (
                row["agent"] != agent
                or row["session_id"] != session_id
                or row["instance_id"] != instance_id
                or row["worktree"] != worktree
            ):
                raise LedgerError("destino divergente da missão registrada")
            for column, value, label in (
                ("agent", agent, "agente"),
                ("session_id", session_id, "sessão"),
            ):
                busy = conn.execute(
                    f"""SELECT mission_id FROM reservations
                        WHERE project_id=? AND {column}=? AND mission_id<>?""",
                    (self.project_id, value, mission_id),
                ).fetchone()
                if busy:
                    raise DuplicateReservation(
                        f"{label} ocupado pela missão {busy['mission_id']}"
                    )
            try:
                conn.execute(
                    """INSERT INTO reservations (project_id, agent, session_id, mission_id)
                       VALUES (?,?,?,?)""",
                    (self.project_id, agent, session_id, mission_id),
                )
            except sqlite3.IntegrityError as exc:
                raise DuplicateReservation("destinatário já reservado") from exc
            conn.execute(
                "UPDATE missions SET state=?, updated_at=?, dispatched_at=? WHERE mission_id=?",
                (DISPATCHING, now, now, mission_id),
            )
            self._append(
                conn,
                mission_id,
                AUTHORIZED,
                DISPATCHING,
                "reserva atômica antes do envio",
                actor,
                now,
            )
        record = self.get(mission_id)
        assert record is not None
        return record

    def mark_delivered(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        if not evidence.strip():
            raise UnsafeReconciliation("entrega exige evidência")
        return self._transition(
            mission_id, DELIVERED, evidence=evidence, actor=actor
        )

    def mark_running(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        if not evidence.strip():
            raise UnsafeReconciliation("execução exige evidência")
        return self._transition(mission_id, RUNNING, evidence=evidence, actor=actor)

    def complete(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        if not evidence.strip():
            raise UnsafeReconciliation("conclusão exige evidência")
        return self._transition(
            mission_id,
            COMPLETED,
            evidence=evidence,
            actor=actor,
            clear_reservation=True,
        )

    def fail_before_dispatch(
        self, mission_id: str, *, evidence: str, actor: str
    ) -> MissionRecord:
        """Falha comprovada antes do envio: AUTHORIZED -> FAILED, sem envio."""
        if not evidence.strip():
            raise UnsafeReconciliation("falha exige evidência")
        record = self.get(mission_id)
        if record is None or record.state != AUTHORIZED:
            raise InvalidTransition("fail_before_dispatch exige AUTHORIZED")
        return self._transition(
            mission_id, FAILED, evidence=evidence, actor=actor, clear_reservation=True
        )

    def fail(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        """Falha confirmada após execução: RUNNING -> FAILED."""
        if not evidence.strip():
            raise UnsafeReconciliation("falha exige evidência")
        record = self.get(mission_id)
        if record is None or record.state != RUNNING:
            raise InvalidTransition("fail exige RUNNING")
        return self._transition(
            mission_id, FAILED, evidence=evidence, actor=actor, clear_reservation=True
        )

    def mark_unknown(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        """Resultado indeterminado conserva a exclusividade do destinatário."""
        return self._transition(
            mission_id, UNKNOWN, evidence=evidence, actor=actor
        )

    def reconcile(
        self,
        mission_id: str,
        *,
        target: str,
        evidence: str,
        authority_reference: str,
        actor: str,
        human_decision: bool = False,
    ) -> MissionRecord:
        """Reconcilia UNKNOWN dentro da mesma transação que aplica o resultado.

        Nenhuma leitura fora da transação é usada como decisão; uma observação
        defasada não pode liberar a reserva nem rebaixar um estado mais adiante.
        """
        if target not in STATES:
            raise InvalidTransition(f"estado desconhecido: {target}")
        if target == DISPATCHING:
            raise InvalidTransition("reenvio automático é proibido em UNKNOWN")
        if not authority_reference.strip():
            raise UnsafeReconciliation(
                "referência de autoridade externa é obrigatória"
            )
        if not evidence.strip() and not human_decision:
            raise UnsafeReconciliation("evidência ou decisão humana é obrigatória")
        detail = (
            f"{evidence} | autoridade={authority_reference}"
            f" | ator={actor}"
            + (" | decisão humana registrada" if human_decision else "")
        )
        with self._write() as conn:
            row = conn.execute(
                """SELECT * FROM missions
                   WHERE mission_id=? AND project_id=? AND repository_id=?""",
                (mission_id, self.project_id, self.repository_id),
            ).fetchone()
            if row is None:
                raise LedgerError("missão inexistente")
            if row["authorization_mode"] == AUTHORIZATION_MODE_SIGNED:
                raise SignedTransitionRequired(
                    "missão SIGNED_V1 exige reconcile_signed")
            current = row["state"]
            if current != UNKNOWN:
                raise InvalidTransition(
                    f"reconciliação exige UNKNOWN (observado: {current})"
                )
            if target not in ALLOWED_TRANSITIONS[UNKNOWN]:
                raise InvalidTransition(f"UNKNOWN -> {target} não permitido")
            now = iso(utc_now())
            conn.execute(
                "UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                (target, now, mission_id),
            )
            if target in TERMINAL:
                conn.execute(
                    "DELETE FROM reservations WHERE mission_id=?", (mission_id,)
                )
            self._append(conn, mission_id, current, target, detail, actor, now)
        record = self.get(mission_id)
        assert record is not None
        return record

    RECOVERY_CAUSES = frozenset({"timeout", "process_loss", "restart_confirmed"})

    def recover_stale_dispatching(
        self,
        *,
        cause: str,
        actor: str = "recovery",
        now: Optional[datetime] = None,
    ) -> list[str]:
        """Usa o timeout persistido por missão; nunca substitui por valor arbitrário.

        Causa desconhecida mantém o bloqueio: nenhuma transição é feita.
        """
        if cause not in self.RECOVERY_CAUSES:
            return []
        reference = now or utc_now()
        recovered: list[str] = []
        with self._write() as conn:
            rows = conn.execute(
                """SELECT mission_id, dispatched_at, state, project_id,
                          repository_id, timeout_seconds, authorization_digest
                   FROM missions WHERE state=?""",
                (DISPATCHING,),
            ).fetchall()
            for row in rows:
                if row["project_id"] != self.project_id or row["repository_id"] != self.repository_id:
                    continue
                dispatched = row["dispatched_at"]
                timeout = int(row["timeout_seconds"] or 0)
                if cause == "timeout" and timeout > 0 and dispatched:
                    stale = parse_iso(dispatched) + timedelta(
                        seconds=timeout
                    ) <= reference
                elif cause in {"process_loss", "restart_confirmed"}:
                    stale = True
                else:
                    stale = False
                if not stale:
                    continue
                mission_id = row["mission_id"]
                current = row["state"]
                now_s = iso(reference)
                conn.execute(
                    "UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                    (UNKNOWN, now_s, mission_id),
                )
                # Reserva preservada: destinatário continua bloqueado.
                self._append(
                    conn,
                    mission_id,
                    current,
                    UNKNOWN,
                    f"DISPATCHING interrompido: {cause}",
                    actor,
                    now_s,
                    authorization_digest=row["authorization_digest"],
                )
                recovered.append(mission_id)
        return recovered

    def is_reserved(self, mission_id: str) -> bool:
        try:
            with self._connect() as conn:
                return (
                    conn.execute(
                        """SELECT 1 FROM reservations r
                           JOIN missions m ON m.mission_id=r.mission_id
                           WHERE r.project_id=? AND m.repository_id=?
                             AND r.mission_id=?""",
                        (self.project_id, self.repository_id, mission_id),
                    ).fetchone()
                    is not None
                )
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao verificar reserva: {exc}") from exc

    def in_progress(self, mission_id: str) -> bool:
        record = self.get(mission_id)
        return bool(
            record
            and record.state in {DISPATCHING, DELIVERED, RUNNING}
        )

    def can_dispatch(self, mission_id: str) -> bool:
        """Elegibilidade legada para PRIMEIRA reserva; não garante reserva."""
        record = self.get(mission_id)
        if record is None or record.state != AUTHORIZED:
            return False
        if record.authorization_mode == AUTHORIZATION_MODE_SIGNED:
            return False
        if self.policy.get("require_signed_authorization"):
            return False
        return not self.is_reserved(mission_id)
