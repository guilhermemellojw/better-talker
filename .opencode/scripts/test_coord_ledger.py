#!/usr/bin/env python3
"""Testes determinísticos do ledger persistente de missões (COORD-05A/P2)."""

from __future__ import annotations

import multiprocessing
import os
import stat
import sqlite3
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


def register(ledger: Ledger, payload=None, **overrides):
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


def authorized(ledger: Ledger, payload=None, **overrides):
    record = register(ledger, payload, **overrides)
    return ledger.authorize(
        record.mission_id,
        authorization_reference=record.authorization_reference,
        payload_sha256_value=record.payload_sha256,
        actor="human",
    )


def reserved(ledger: Ledger, **overrides):
    record = authorized(ledger, **overrides)
    return ledger.reserve_dispatch(
        record.mission_id,
        agent=record.agent,
        session_id=record.session_id,
        instance_id=record.instance_id,
        worktree=record.worktree,
        actor="coord-S",
    )


def _try_reserve(db_path, mission_id, agent, session_id, queue):
    ledger = Ledger(db_path, project_id=PROJECT, repository_id=REPO)
    try:
        ledger.reserve_dispatch(
            mission_id,
            agent=agent,
            session_id=session_id,
            instance_id="instance-2",
            worktree="/tmp/wt-b2",
            actor="process-2",
        )
        queue.put("ok")
    except Exception as exc:  # noqa: BLE001
        queue.put(type(exc).__name__)


def _barrier_reserve(db_path, mission_id, barrier, queue):
    ledger = Ledger(db_path, project_id=PROJECT, repository_id=REPO)
    barrier.wait()
    try:
        ledger.reserve_dispatch(
            mission_id,
            agent="B",
            session_id="ses-B",
            instance_id="instance-1",
            worktree="/tmp/wt-b",
            actor="process-race",
        )
        queue.put("ok")
    except Exception as exc:  # noqa: BLE001
        queue.put(type(exc).__name__)


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db = Path(self._tmp.name) / "ledger.sqlite3"
        self.ledger = Ledger(self.db, project_id=PROJECT, repository_id=REPO)

    def tearDown(self):
        self._tmp.cleanup()

    def test_persistencia_apos_encerramento(self):
        record = register(self.ledger)
        del self.ledger
        reopened = Ledger(self.db, project_id=PROJECT, repository_id=REPO)
        self.assertEqual(PROPOSED, reopened.get(record.mission_id).state)

    def test_payload_divergente(self):
        register(self.ledger)
        with self.assertRaises(DuplicateMission):
            register(self.ledger, payload={"text": "diferente"})

    def test_operacao_repetida_idempotente(self):
        first = register(self.ledger)
        second = register(self.ledger)
        self.assertEqual(first.identity_hash, second.identity_hash)

    def test_ciclo_de_vida_e_historico(self):
        record = reserved(self.ledger)
        self.ledger.mark_delivered(record.mission_id, evidence="message-id", actor="S")
        self.ledger.mark_running(record.mission_id, evidence="session busy", actor="S")
        done = self.ledger.complete(record.mission_id, evidence="checkpoint", actor="S")
        self.assertEqual(COMPLETED, done.state)
        self.assertEqual(
            [PROPOSED, AUTHORIZED, DISPATCHING, DELIVERED, RUNNING, COMPLETED],
            [t.to_state for t in self.ledger.history(record.mission_id)],
        )

    def test_transicao_invalida(self):
        record = register(self.ledger)
        with self.assertRaises(InvalidTransition):
            self.ledger.mark_delivered(record.mission_id, evidence="x", actor="S")

    def test_estados_terminais_protegidos(self):
        record = reserved(self.ledger)
        self.ledger.mark_delivered(record.mission_id, evidence="x", actor="S")
        self.ledger.mark_running(record.mission_id, evidence="x", actor="S")
        self.ledger.complete(record.mission_id, evidence="x", actor="S")
        with self.assertRaises(InvalidTransition):
            self.ledger.fail(record.mission_id, evidence="x", actor="S")

    def test_reserva_duplicada_mesma_missao(self):
        record = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            record.mission_id, agent=record.agent, session_id=record.session_id,
            instance_id=record.instance_id, worktree=record.worktree, actor="S")
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                record.mission_id, agent=record.agent, session_id=record.session_id,
                instance_id=record.instance_id, worktree=record.worktree, actor="S")

    def test_f1_duplicidade_entre_missoes_distintas(self):
        first = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            first.mission_id, agent=first.agent, session_id=first.session_id,
            instance_id=first.instance_id, worktree=first.worktree, actor="S")
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                second.mission_id, agent=second.agent, session_id=second.session_id,
                instance_id=second.instance_id, worktree=second.worktree, actor="S")

    def test_f1_concorrencia_processos_missoes_distintas(self):
        first = authorized(self.ledger)
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
        )
        self.ledger.reserve_dispatch(
            first.mission_id, agent=first.agent, session_id=first.session_id,
            instance_id=first.instance_id, worktree=first.worktree, actor="process-1")
        queue = multiprocessing.Queue()
        proc = multiprocessing.Process(
            target=_try_reserve,
            args=(str(self.db), second.mission_id, "B", "ses-B", queue),
        )
        proc.start()
        result = queue.get(timeout=20)
        proc.join(timeout=20)
        self.assertNotEqual("ok", result)

    def test_f1_mudanca_instancia_nao_libera_reserva(self):
        first = reserved(self.ledger)
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
            instance_id="instance-99",
            worktree="/tmp/wt-b2",
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                second.mission_id, agent=second.agent, session_id=second.session_id,
                instance_id=second.instance_id, worktree=second.worktree, actor="S")
        self.assertEqual(DISPATCHING, self.ledger.get(first.mission_id).state)

    def test_f2_unknown_preserva_reserva_e_bloqueia_nova(self):
        first = reserved(self.ledger)
        self.ledger.mark_unknown(first.mission_id, evidence="timeout", actor="S")
        self.assertTrue(self.ledger.is_reserved(first.mission_id))
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                second.mission_id, agent=second.agent, session_id=second.session_id,
                instance_id=second.instance_id, worktree=second.worktree, actor="S")

    def test_f2_reconciliacao_nao_terminal_preserva_reserva(self):
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        self.ledger.reconcile(
            record.mission_id, target=DELIVERED, evidence="inbox",
            authority_reference="AUTH-EVIDENCE-1", actor="human")
        self.assertTrue(self.ledger.is_reserved(record.mission_id))

    def test_f2_reconciliacao_terminal_libera_reserva(self):
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        done = self.ledger.reconcile(
            record.mission_id, target=COMPLETED, evidence="checkpoint",
            authority_reference="AUTH-EVIDENCE-2", actor="human")
        self.assertEqual(COMPLETED, done.state)
        self.assertFalse(self.ledger.is_reserved(record.mission_id))

    def test_f3_can_dispatch_so_para_autorizado(self):
        record = authorized(self.ledger)
        self.assertTrue(self.ledger.can_dispatch(record.mission_id))
        self.ledger.reserve_dispatch(
            record.mission_id, agent=record.agent, session_id=record.session_id,
            instance_id=record.instance_id, worktree=record.worktree, actor="S")
        self.assertFalse(self.ledger.can_dispatch(record.mission_id))
        self.assertTrue(self.ledger.in_progress(record.mission_id))
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        self.assertFalse(self.ledger.can_dispatch(record.mission_id))

    def test_f4_privacidade(self):
        register(self.ledger)
        self.assertEqual(
            0o700, stat.S_IMODE(self.db.parent.stat().st_mode)
        )
        self.assertEqual(0o600, stat.S_IMODE(self.db.stat().st_mode))
        for suffix in ("-wal", "-shm"):
            path = Path(str(self.db) + suffix)
            if path.exists():
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_f4_isolamento_projeto_divergente(self):
        record = register(self.ledger)
        other = Ledger(
            self.db,
            project_id="outro-projeto",
            repository_id="outro/repo",
        )
        self.assertIsNone(other.get(record.mission_id))
        with self.assertRaises(LedgerError):
            other.reconcile(
                record.mission_id, target=COMPLETED, evidence="x",
                authority_reference="AUTH-X", actor="S")
        self.assertEqual(PROPOSED, self.ledger.get(record.mission_id).state)

    def test_f5_unknown_nao_contorna_reconciliacao(self):
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        with self.assertRaises(InvalidTransition):
            self.ledger.complete(record.mission_id, evidence="x", actor="S")
        with self.assertRaises(InvalidTransition):
            self.ledger.mark_delivered(record.mission_id, evidence="x", actor="S")

    def test_f5_autoridade_obrigatoria(self):
        record = reserved(self.ledger)
        self.ledger.mark_unknown(record.mission_id, evidence="timeout", actor="S")
        with self.assertRaises(UnsafeReconciliation):
            self.ledger.reconcile(
                record.mission_id, target=DELIVERED, evidence="x",
                authority_reference="", actor="S", human_decision=True)

    def test_f5_falha_antes_do_envio(self):
        record = authorized(self.ledger)
        failed = self.ledger.fail_before_dispatch(
            record.mission_id, evidence="pré-despacho recusado", actor="S")
        self.assertEqual(FAILED, failed.state)
        self.assertFalse(self.ledger.is_reserved(record.mission_id))

    def test_f6_timeout_persistido(self):
        record = register(self.ledger, timeout_seconds=30)
        self.assertEqual(30, self.ledger.get(record.mission_id).timeout_seconds)

    def test_f6_timeout_arbitrario_nao_recupera(self):
        record = reserved(self.ledger)
        future = datetime.now(timezone.utc) + timedelta(hours=1)
        self.assertEqual(
            [],
            self.ledger.recover_stale_dispatching(
                cause="timeout", actor="restart", now=future),
        )
        self.assertEqual(DISPATCHING, self.ledger.get(record.mission_id).state)

    def test_f6_reinicio_confirmado_para_unknown(self):
        record = reserved(self.ledger)
        recovered = self.ledger.recover_stale_dispatching(
            cause="restart_confirmed", actor="restart")
        self.assertEqual([record.mission_id], recovered)
        self.assertEqual(UNKNOWN, self.ledger.get(record.mission_id).state)
        self.assertTrue(self.ledger.is_reserved(record.mission_id))

    def test_f6_causa_desconhecida_mantem_bloqueio(self):
        record = reserved(self.ledger)
        self.assertEqual([], self.ledger.recover_stale_dispatching(cause="misterio"))
        self.assertEqual(DISPATCHING, self.ledger.get(record.mission_id).state)
        self.assertTrue(self.ledger.is_reserved(record.mission_id))

    def test_recuperacao_apos_reinicio_preserva_identidade(self):
        first = register(self.ledger)
        del self.ledger
        self.ledger = Ledger(self.db, project_id=PROJECT, repository_id=REPO)
        again = self.ledger.get(first.mission_id)
        self.assertEqual(first.identity_hash, again.identity_hash)

    def test_falha_de_armazenamento_com_rollback(self):
        ledger = Ledger(
            Path(self._tmp.name) / "inexistente" / "ledger.sqlite3",
            project_id=PROJECT, repository_id=REPO)
        record = register(ledger)
        ledger.db_path = Path(self._tmp.name) / "bloqueado"
        ledger.db_path.write_text("não é sqlite")
        before = record.updated_at
        with self.assertRaises(LedgerStorageError):
            ledger.authorize(
                record.mission_id,
                authorization_reference=record.authorization_reference,
                payload_sha256_value=record.payload_sha256,
                actor="S")
        ledger.db_path = Path(self._tmp.name) / "inexistente" / "ledger.sqlite3"
        self.assertEqual(PROPOSED, ledger.get(record.mission_id).state)
        self.assertEqual(before, ledger.get(record.mission_id).updated_at)

    def test_politica_injetada(self):
        other = Ledger(
            Path(self._tmp.name) / "outro.sqlite3",
            project_id="outro-projeto",
            repository_id="outro/repo",
            policy={"mode": "SHADOW", "allow_dispatch": False})
        self.assertFalse(other.policy["allow_dispatch"])

    def test_projeto_injetado_nao_altera_missao_alheia(self):
        record = register(self.ledger)
        other = Ledger(self.db, project_id="outro-projeto", repository_id="outro/repo")
        with self.assertRaises(LedgerError):
            other.fail_before_dispatch(record.mission_id, evidence="x", actor="S")



    def test_f1a_mesmo_agente_sessao_diferente_bloqueado(self):
        first = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            first.mission_id, agent=first.agent, session_id=first.session_id,
            instance_id=first.instance_id, worktree=first.worktree, actor="S")
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
            session_id="ses-B-2",
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                second.mission_id, agent=second.agent, session_id=second.session_id,
                instance_id=second.instance_id, worktree=second.worktree, actor="S")

    def test_f1b_agente_diferente_mesma_sessao_bloqueado(self):
        first = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            first.mission_id, agent=first.agent, session_id=first.session_id,
            instance_id=first.instance_id, worktree=first.worktree, actor="S")
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
            agent="C",
        )
        with self.assertRaises(DuplicateReservation):
            self.ledger.reserve_dispatch(
                second.mission_id, agent=second.agent, session_id=second.session_id,
                instance_id=second.instance_id, worktree=second.worktree, actor="S")

    def test_f1_agentes_e_sessoes_distintos_podem_reservar(self):
        first = authorized(self.ledger)
        self.ledger.reserve_dispatch(
            first.mission_id, agent=first.agent, session_id=first.session_id,
            instance_id=first.instance_id, worktree=first.worktree, actor="S")
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
            agent="C",
            session_id="ses-C",
            worktree="/tmp/wt-c",
        )
        record = self.ledger.reserve_dispatch(
            second.mission_id, agent=second.agent, session_id=second.session_id,
            instance_id=second.instance_id, worktree=second.worktree, actor="S")
        self.assertEqual(DISPATCHING, record.state)

    def test_f2_corrida_simultaneia_um_vencedor(self):
        first = authorized(self.ledger)
        second = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
        )
        barrier = multiprocessing.Barrier(2)
        queue: multiprocessing.Queue[str] = multiprocessing.Queue()
        procs = [
            multiprocessing.Process(
                target=_barrier_reserve,
                args=(str(self.db), mid, barrier, queue),
            )
            for mid in (first.mission_id, second.mission_id)
        ]
        for proc in procs:
            proc.start()
        results = [queue.get(timeout=20) for _ in procs]
        for proc in procs:
            proc.join(timeout=20)
        self.assertEqual(1, results.count("ok"))
        self.assertEqual(2, len(results))
        with self._connect_raw() as conn:
            count = conn.execute("SELECT COUNT(*) FROM reservations").fetchone()[0]
        self.assertEqual(1, count)
        states = {
            mid: self.ledger.get(mid).state
            for mid in (first.mission_id, second.mission_id)
        }
        self.assertEqual(1, list(states.values()).count(DISPATCHING))
        self.assertEqual(1, list(states.values()).count(AUTHORIZED))

    def _connect_raw(self):
        conn = sqlite3.connect(self.db)
        conn.row_factory = sqlite3.Row
        return conn

    def test_f3_banco_sem_versao_e_rejeitado_sem_perda(self):
        old = Path(self._tmp.name) / "antigo.sqlite3"
        conn = sqlite3.connect(old)
        conn.executescript(
            """
            CREATE TABLE missions (mission_id TEXT PRIMARY KEY, operation_id TEXT,
                project_id TEXT, repository_id TEXT, coordinator_id TEXT,
                agent TEXT, session_id TEXT, instance_id TEXT, worktree TEXT,
                authorization_reference TEXT, payload_sha256 TEXT,
                identity_hash TEXT, state TEXT, created_at TEXT, updated_at TEXT,
                dispatched_at TEXT, timeout_seconds INTEGER DEFAULT 0);
            CREATE TABLE transitions (seq INTEGER PRIMARY KEY AUTOINCREMENT,
                mission_id TEXT, from_state TEXT, to_state TEXT, evidence TEXT,
                actor TEXT, at TEXT);
            CREATE TABLE reservations (project_id TEXT, agent TEXT, session_id TEXT,
                mission_id TEXT, PRIMARY KEY (project_id, agent, session_id));
            INSERT INTO missions VALUES ('M-OLD','O-OLD','better-talker',
                'guilhermemellojw/better-talker','coord','B','ses-B','i','w',
                'AUTH','h','id','UNKNOWN','t','t',NULL,0);
            INSERT INTO reservations VALUES ('better-talker','B','ses-B','M-OLD');
            """
        )
        conn.commit()
        conn.close()
        with self.assertRaises(LedgerStorageError):
            Ledger(old, project_id=PROJECT, repository_id=REPO)
        conn = sqlite3.connect(old)
        state = conn.execute("SELECT state FROM missions WHERE mission_id='M-OLD'").fetchone()[0]
        reserved = conn.execute("SELECT COUNT(*) FROM reservations").fetchone()[0]
        conn.close()
        self.assertEqual("UNKNOWN", state)
        self.assertEqual(1, reserved)

    def test_f4_diretorio_preexistente_aberto_e_rejeitado(self):
        open_dir = Path(self._tmp.name) / "aberto"
        open_dir.mkdir()
        os.chmod(open_dir, 0o777)
        before = stat.S_IMODE(open_dir.stat().st_mode)
        with self.assertRaises(LedgerStorageError):
            Ledger(open_dir / "ledger.sqlite3", project_id=PROJECT, repository_id=REPO)
        self.assertEqual(before, stat.S_IMODE(open_dir.stat().st_mode))

    def test_f4_caminho_simbolico_rejeitado(self):
        real = Path(self._tmp.name) / "real"
        real.mkdir()
        link = Path(self._tmp.name) / "link"
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaises(LedgerStorageError):
            Ledger(link / "ledger.sqlite3", project_id=PROJECT, repository_id=REPO)

    def test_f4_arquivos_auxiliares_protegidos(self):
        for suffix in ("-wal", "-shm"):
            Path(str(self.db) + suffix).write_text("aux")
            os.chmod(Path(str(self.db) + suffix), 0o666)
        self.ledger._harden_files()
        for suffix in ("-wal", "-shm"):
            path = Path(str(self.db) + suffix)
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_f5_fail_cross_states(self):
        record = reserved(self.ledger)
        with self.assertRaises(InvalidTransition):
            self.ledger.fail_before_dispatch(
                record.mission_id, evidence="x", actor="S")
        active = authorized(
            self.ledger,
            mission_id="MISS-BETA-0002",
            operation_id="OP-0002",
            payload={"text": "segunda", "mission": "MISS-BETA-0002"},
        )
        with self.assertRaises(InvalidTransition):
            self.ledger.fail(active.mission_id, evidence="x", actor="S")

    def test_f6_causa_nao_comprova_reinicio_real(self):
        record = reserved(self.ledger)
        self.ledger.recover_stale_dispatching(
            cause="restart_confirmed", actor="synthetic")
        self.assertEqual(UNKNOWN, self.ledger.get(record.mission_id).state)
        self.assertTrue(self.ledger.is_reserved(record.mission_id))

if __name__ == "__main__":
    unittest.main()
