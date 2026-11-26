#!/usr/bin/env python3
"""Testes determinísticos do ledger persistente de missões."""

from __future__ import annotations

import multiprocessing
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from coord_ledger import (
    AUTHORIZED,
    COMPLETED,
    DELIVERED,
    DISPATCHING,
    DuplicateMission,
    DuplicateReservation,
    FAILED,
    InvalidTransition,
    Ledger,
    LedgerError,
    LedgerStorageError,
    PROPOSED,
    RUNNING,
    UnsafeReconciliation,
    UNKNOWN,
    iso,
    payload_sha256,
)

PROJECT = "better-talker"
REPO = "guilhermemellojw/better-talker"


def base_payload(text: str = "prompt") -> dict:
    return {"text": text, "mission": "MISS-BETA-0001"}


def register(ledger: Ledger, payload: dict | None = None, **overrides):
    values = {
        "mission_id": "MISS-BETA-0001",
        "operation_id": "OP-0001",
        "coordinator_id": "coord-S",
        "agent": "B",
        "session_id": "ses-B",
        "instance_id": "instance-1",
        "worktree": "/tmp/wt-b",
        "authorization_reference": "AUTH-0001",
        "payload": payload if payload is not None else base_payload(),
    }
    values.update(overrides)
    return ledger.register_proposed(**values)


def authorized(ledger: Ledger, payload: dict | None = None):
    record = register(ledger, payload)
    return ledger.authorize(
        record.mission_id,
        authorization_reference=record.authorization_reference,
        payload_sha256_value=record.payload_sha256,
        actor="human",
    )


def reserved(ledger: Ledger):
    record = authorized(ledger)
    return ledger.reserve_dispatch(
        record.mission_id,
        agent=record.agent,
        session_id=record.session_id,
        instance_id=record.instance_id,
        worktree=record.worktree,
        actor="coord-S",
    )


def _try_reserve(db_path: str, mission_id: str, queue) -> None:
    ledger = Ledger(db_path, project_id=PROJECT, repository_id=REPO)
    try:
        ledger.reserve_dispatch(
            mission_id,
            agent="B",
            session_id="ses-B",
            instance_id="instance-1",
            worktree="/tmp/wt-b",
            actor="process-2",
        )
        queue.put("ok")
    except Exception as exc:  # noqa: BLE001 - resultado é a asserção
        queue.put(type(exc).__name__)


class LedgerTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.db = Path(self._tmp.name) / "ledger.sqlite3"
        self.ledger = Ledger(self.db, project_id=PROJECT, repository_id=REPO)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_persistencia_apos_encerramento(self) -> None:
        record = register(self.ledger)
        del self.ledger
        reopened = Ledger(self.db, project_id=PROJECT, repository_id=REPO)
        self.assertEqual(PROPOSED, reopened.get(record.mission_id).state)
        self.assertEqual(record.identity_hash, reopened.get(record.mission_id).identity_hash)

    def test_identidade_imutavel_e_payload_divergente(self) -> None:
        record = register(self.ledger)
        self.assertEqual(payload_sha256(base_payload()), record.payload_sha256)
        with self.assertRaises(DuplicateMission):
            register(self.ledger, payload={"text": "diferente"})

    def test_operacao_repetida_com_mesmo_conteudo_e_idempotente(self) -> None:
        first = register(self.ledger)
        second = register(self.ledger)
        self.assertEqual(first.identity_hash, second.identity_hash)

    def test_ciclo_de_vida_completo_e_historico(self) -> None:
        record = reserved(self.ledger)
        self.assertEqual(DISPATCHING, record.state)
        self.ledger.mark_delivered(record.mission_id, evidence="message-id", actor="S")
        self.ledger.mark_running(record.mission_id, evidence="session busy", actor="S")
        done = self.ledger.complete(record.mission_id, evidence="checkpoint", actor="S")
        self.assertEqual(COMPLETED, done.state)
        states = [t.to_state for t in self.ledger.history(record.mission_id)]
        self.assertEqual(
            [PROPOSED, AUTHORIZED, DISPATCHING, DELIVERED, RUNNING, COMPLETED], states
        )

    def test_transicao_invalida(self) -> None:
        record = register(self.ledger)
        with self.assertRaises(InvalidTransition):
            self.ledger.mark_delivered(record.mission_id, evidence="x", actor="S")
        self.assertEqual(PROPOSED, self.ledger.get(record.mission_id).state)

    def test_estados_terminais_protegidos(self) -> None:
        record = reserved(self.ledger)
        self.ledger.mark_delivered(record.mission_id, evidence="x", actor="S")
        self.ledger.mark_running(record.mission_id, evidence="x", actor="S")
        self.ledger.complete(record.mission_id, evidence="x", actor="S")
        with self.assertRaises(InvalidTransition):
            self.ledger.fail(record.mission_id, evidence="x", actor="S")

    def test_reserva_duplicada_mesma_instancia(self) -> None:
        record = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            record.mission_id,
            agent=record.agent,
            session_id=record.session_id,
            instance_id=record.instance_id,
            worktree=record.worktree,
            actor="coord-S",
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                record.mission_id,
                agent=record.agent,
                session_id=record.session_id,
                instance_id=record.instance_id,
                worktree=record.worktree,
                actor="coord-S",
            )

    def test_concorrencia_entre_dois_processos(self) -> None:
        record = authorized(self.ledger)
        queue: multiprocessing.Queue[str] = multiprocessing.Queue()
        proc = multiprocessing.Process(
            target=_try_reserve, args=(str(self.db), record.mission_id, queue)
        )
        proc.start()
        self.ledger.reserve_dispatch(
            record.mission_id,
            agent=record.agent,
            session_id=record.session_id,
            instance_id=record.instance_id,
            worktree=record.worktree,
            actor="process-1",
        )
        result = queue.get(timeout=20)
        proc.join(timeout=20)
        self.assertNotEqual("ok", result)

    def test_recuperacao_dispatching_para_unknown(self) -> None:
        record = reserved(self.ledger)
        future = datetime.now(timezone.utc) + timedelta(hours=1)
        recovered = self.ledger.recover_stale_dispatching(
            timeout_seconds=1, actor="restart", now=future
        )
        self.assertEqual([record.mission_id], recovered)
        self.assertEqual(UNKNOWN, self.ledger.get(record.mission_id).state)
        self.assertFalse(self.ledger.can_dispatch(record.mission_id))

    def test_unknown_sem_reenvio(self) -> None:
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        with self.assertRaises(InvalidTransition):
            self.ledger.reconcile(
                record.mission_id,
                target=DISPATCHING,
                evidence="",
                actor="S",
                human_decision=True,
            )

    def test_reconciliacao_por_evidencia(self) -> None:
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        done = self.ledger.reconcile(
            record.mission_id,
            target=COMPLETED,
            evidence="checkpoint do agente",
            actor="human",
        )
        self.assertEqual(COMPLETED, done.state)

    def test_reconciliacao_sem_evidencia_bloqueada(self) -> None:
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        with self.assertRaises(UnsafeReconciliation):
            self.ledger.reconcile(
                record.mission_id, target=DELIVERED, evidence="", actor="S"
            )

    def test_reinicio_da_instancia_preserva_identidade(self) -> None:
        first = register(self.ledger)
        del self.ledger
        self.ledger = Ledger(self.db, project_id=PROJECT, repository_id=REPO)
        again = self.ledger.get(first.mission_id)
        self.assertEqual(first.identity_hash, again.identity_hash)
        self.assertEqual(first.operation_id, again.operation_id)

    def test_falha_de_armazenamento(self) -> None:
        ledger = Ledger(Path(self._tmp.name) / "inexistente" / "ledger.sqlite3",
                        project_id=PROJECT, repository_id=REPO)
        record = register(ledger)
        ledger.db_path = Path(self._tmp.name) / "bloqueado"
        ledger.db_path.write_text("não é sqlite")
        with self.assertRaises(LedgerStorageError):
            ledger.authorize(
                record.mission_id,
                authorization_reference=record.authorization_reference,
                payload_sha256_value=record.payload_sha256,
                actor="S",
            )

    def test_projeto_e_politica_injetados(self) -> None:
        other = Ledger(
            Path(self._tmp.name) / "outro.sqlite3",
            project_id="outro-projeto",
            repository_id="outro/repo",
            policy={"mode": "SHADOW", "allow_dispatch": False},
        )
        record = register(self.ledger)
        self.assertIsNone(other.get(record.mission_id))
        self.assertFalse(other.policy["allow_dispatch"])

    def test_sem_reenvio_em_estado_ativo(self) -> None:
        record = authorized(self.ledger)
        self.assertTrue(self.ledger.can_dispatch(record.mission_id))
        self.ledger.reserve_dispatch(
            record.mission_id,
            agent=record.agent,
            session_id=record.session_id,
            instance_id=record.instance_id,
            worktree=record.worktree,
            actor="S",
        )
        self.assertTrue(self.ledger.can_dispatch(record.mission_id))
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        self.assertFalse(self.ledger.can_dispatch(record.mission_id))


if __name__ == "__main__":
    unittest.main()
