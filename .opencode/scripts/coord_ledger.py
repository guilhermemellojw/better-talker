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
import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterator, Mapping, Optional

SCHEMA_VERSION = "coord-ledger-1.0"

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
    AUTHORIZED: frozenset({DISPATCHING}),
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


@dataclass(frozen=True)
class Transition:
    seq: int
    mission_id: str
    from_state: Optional[str]
    to_state: str
    evidence: str
    actor: str
    at: str


class Ledger:
    """Ledger local, por projeto/política injetados; sem efeitos externos."""

    def __init__(
        self,
        db_path: str | os.PathLike[str],
        *,
        project_id: str,
        repository_id: str,
        policy: Optional[Mapping[str, Any]] = None,
    ) -> None:
        if not project_id or not repository_id:
            raise LedgerError("project_id e repository_id são obrigatórios")
        self.db_path = Path(db_path)
        self.project_id = project_id
        self.repository_id = repository_id
        self.policy = dict(policy or {})
        self._local = threading.local()
        self._initialize()

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

    def _initialize(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with self._connect() as conn:
                conn.executescript(
                    """
                    PRAGMA journal_mode=WAL;
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
                        dispatched_at TEXT
                    );
                    CREATE TABLE IF NOT EXISTS transitions (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        mission_id TEXT NOT NULL,
                        from_state TEXT,
                        to_state TEXT NOT NULL,
                        evidence TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        at TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS reservations (
                        agent TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        mission_id TEXT NOT NULL,
                        PRIMARY KEY (agent, session_id)
                    );
                    """
                )
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao inicializar banco: {exc}") from exc

    # ---------- leitura ----------
    def get(self, mission_id: str) -> Optional[MissionRecord]:
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM missions WHERE mission_id=?", (mission_id,)
                ).fetchone()
            return self._record(row) if row else None
        except sqlite3.Error as exc:
            raise LedgerStorageError(f"falha ao ler missão: {exc}") from exc

    def history(self, mission_id: str) -> list[Transition]:
        try:
            with self._connect() as conn:
                rows = conn.execute(
                    "SELECT * FROM transitions WHERE mission_id=? ORDER BY seq ASC",
                    (mission_id,),
                ).fetchall()
            return [
                Transition(
                    seq=int(row["seq"]),
                    mission_id=row["mission_id"],
                    from_state=row["from_state"],
                    to_state=row["to_state"],
                    evidence=row["evidence"],
                    actor=row["actor"],
                    at=row["at"],
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
        }
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
    ) -> MissionRecord:
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
                ):
                    raise DuplicateMission("missão/ operaçao repetida com identidade divergente")
                return self._record(existing)
            conn.execute(
                """INSERT INTO missions (
                    mission_id, operation_id, project_id, repository_id,
                    coordinator_id, agent, session_id, instance_id, worktree,
                    authorization_reference, payload_sha256, identity_hash,
                    state, created_at, updated_at, dispatched_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)""",
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
                ),
            )
            self._append(conn, mission_id, None, PROPOSED, "registro inicial", coordinator_id, now)
        record = self.get(mission_id)
        assert record is not None
        return record

    def _append(
        self,
        conn: sqlite3.Connection,
        mission_id: str,
        from_state: Optional[str],
        to_state: str,
        evidence: str,
        actor: str,
        at: Optional[str] = None,
    ) -> None:
        conn.execute(
            """INSERT INTO transitions
               (mission_id, from_state, to_state, evidence, actor, at)
               VALUES (?,?,?,?,?,?)""",
            (mission_id, from_state, to_state, evidence, actor, at or iso(utc_now())),
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
            current = row["state"]
            if current in TERMINAL:
                raise InvalidTransition(f"estado terminal {current} é protegido")
            if row["project_id"] != self.project_id or row["repository_id"] != self.repository_id:
                raise LedgerError("projeto/repositório divergentes")
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
            if conn.execute(
                "SELECT 1 FROM reservations WHERE mission_id=?", (mission_id,)
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
            busy = conn.execute(
                """SELECT mission_id FROM reservations
                   WHERE agent=? AND session_id=? AND mission_id<>?""",
                (agent, session_id, mission_id),
            ).fetchone()
            if busy:
                raise DuplicateReservation(
                    f"agente/destino ocupado pela missão {busy['mission_id']}"
                )
            existing = conn.execute(
                "SELECT mission_id FROM reservations WHERE mission_id=?", (mission_id,)
            ).fetchone()
            if existing:
                raise DuplicateReservation("operação já reservada")
            conn.execute(
                "INSERT INTO reservations (agent, session_id, mission_id) VALUES (?,?,?)",
                (agent, session_id, mission_id),
            )
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

    def fail(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        if not evidence.strip():
            raise UnsafeReconciliation("falha exige evidência")
        return self._transition(
            mission_id, FAILED, evidence=evidence, actor=actor, clear_reservation=True
        )

    def mark_unknown(self, mission_id: str, *, evidence: str, actor: str) -> MissionRecord:
        return self._transition(
            mission_id, UNKNOWN, evidence=evidence, actor=actor, clear_reservation=True
        )

    def reconcile(
        self,
        mission_id: str,
        *,
        target: str,
        evidence: str,
        actor: str,
        human_decision: bool = False,
    ) -> MissionRecord:
        """Reconcilia UNKNOWN. Nunca reenvia e nunca faz transição automática."""
        record = self.get(mission_id)
        if record is None:
            raise LedgerError("missão inexistente")
        if record.state != UNKNOWN:
            raise InvalidTransition("reconciliação exige UNKNOWN")
        if not evidence.strip() and not human_decision:
            raise UnsafeReconciliation("evidência ou decisão humana é obrigatória")
        if target == DISPATCHING:
            raise InvalidTransition("reenvio automático é proibido em UNKNOWN")
        return self._transition(
            mission_id,
            target,
            evidence=evidence or f"decisão humana registrada por {actor}",
            actor=actor,
        )

    def recover_stale_dispatching(
        self,
        *,
        timeout_seconds: int,
        actor: str = "recovery",
        now: Optional[datetime] = None,
    ) -> list[str]:
        """DISPATCHING interrompido vira UNKNOWN; nunca volta a DISPATCHING."""
        if timeout_seconds < 0:
            raise LedgerError("timeout inválido")
        reference = now or utc_now()
        recovered: list[str] = []
        with self._write() as conn:
            rows = conn.execute(
                "SELECT mission_id, dispatched_at, state FROM missions WHERE state=?",
                (DISPATCHING,),
            ).fetchall()
            for row in rows:
                dispatched = row["dispatched_at"]
                if not dispatched:
                    stale = True
                else:
                    stale = parse_iso(dispatched) + timedelta(
                        seconds=timeout_seconds
                    ) <= reference
                if not stale:
                    continue
                mission_id = row["mission_id"]
                current = row["state"]
                now_s = iso(reference)
                conn.execute(
                    "UPDATE missions SET state=?, updated_at=? WHERE mission_id=?",
                    (UNKNOWN, now_s, mission_id),
                )
                conn.execute("DELETE FROM reservations WHERE mission_id=?", (mission_id,))
                self._append(
                    conn,
                    mission_id,
                    current,
                    UNKNOWN,
                    "DISPATCHING interrompido após reinício/timeout",
                    actor,
                    now_s,
                )
                recovered.append(mission_id)
        return recovered

    def can_dispatch(self, mission_id: str) -> bool:
        record = self.get(mission_id)
        if record is None:
            return False
        if record.state == UNKNOWN:
            return False
        if record.state not in ACTIVE:
            return False
        return True
