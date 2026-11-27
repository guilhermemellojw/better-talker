#!/usr/bin/env python3
"""COORD-04/P14A — integração offline ledger #70 + política #71 no piloto #64."""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

import coord_communication as cc
import coord_ledger as ledger
import coord_p14_adapter as adapter
import coord_pilot as pilot

import test_coord_model_policy as mp

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)
PROJECT = "better-talker"
REPO = "guilhermemellojw/better-talker"
SESSION = "ses-p14a"
AUTH = "AUTH-P14A"


def target(session: str = SESSION) -> cc.TargetIdentity:
    return cc.TargetIdentity(PROJECT, REPO, "A", session, "inst-p14a", "/repo/wt")


def record(mission_id: str, operation_id: str, tgt: cc.TargetIdentity) -> pilot.PilotOperationRecord:
    return pilot.PilotOperationRecord(
        mission_id, operation_id, AUTH, "canonical", tgt.key(), "prompt-hash",
        ledger.AUTHORIZED, cc.iso(NOW), cc.iso(NOW),
        mission_snapshot={"mission_id": mission_id},
    )


def build_store(directory: str, *, mission_id: str = "MISS-P14A-1",
                operation_id: str = "OP-P14A-1", session: str = SESSION):
    db = ledger.Ledger(
        os.path.join(directory, "ledger.db"), project_id=PROJECT, repository_id=REPO,
    )
    tgt = target(session)
    store = adapter.LedgerOperationStore(
        db, mission_id=mission_id, operation_id=operation_id,
        authorization_reference=AUTH, payload={"text": "p14a"}, target=tgt,
    )
    store.create(record(mission_id, operation_id, tgt))
    return db, store, tgt


class P14AIntegrationTests(unittest.TestCase):
    def test_ledger_is_state_and_reservation_authority(self):
        with tempfile.TemporaryDirectory() as td:
            db, store, _ = build_store(td)
            self.assertEqual(ledger.AUTHORIZED, db.get("MISS-P14A-1").state)
            with store.reserve():
                self.assertEqual(ledger.DISPATCHING, db.get("MISS-P14A-1").state)
                self.assertTrue(db.is_reserved("MISS-P14A-1"))
                with self.assertRaises(cc.AuthorizationError):
                    store.assert_transmittable()
            # A reserva do ledger persiste até o estado terminal (sem lock de arquivo).
            self.assertTrue(db.in_progress("MISS-P14A-1"))
            self.assertTrue(db.is_reserved("MISS-P14A-1"))

    def test_two_concurrent_missions_same_agent_are_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            _, first, _ = build_store(td)
            db2 = ledger.Ledger(os.path.join(td, "ledger.db"),
                                project_id=PROJECT, repository_id=REPO)
            second = adapter.LedgerOperationStore(
                db2, mission_id="MISS-P14A-2", operation_id="OP-P14A-2",
                authorization_reference=AUTH, payload={"text": "p14a-2"}, target=target(),
            )
            second.create(record("MISS-P14A-2", "OP-P14A-2", target()))
            with first.reserve():
                with self.assertRaises(ledger.DuplicateReservation):
                    with second.reserve():
                        pass

    def test_two_reservations_same_session_are_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            _, first, _ = build_store(td, mission_id="MISS-A", operation_id="OP-A")
            db2 = ledger.Ledger(os.path.join(td, "ledger.db"),
                                project_id=PROJECT, repository_id=REPO)
            second = adapter.LedgerOperationStore(
                db2, mission_id="MISS-B", operation_id="OP-B",
                authorization_reference=AUTH, payload={"text": "b"}, target=target(),
            )
            second.create(record("MISS-B", "OP-B", target()))
            with first.reserve():
                with self.assertRaises(ledger.DuplicateReservation):
                    with second.reserve():
                        pass

    def test_interruption_after_reservation_is_unknown_without_resend(self):
        with tempfile.TemporaryDirectory() as td:
            _, store, _ = build_store(td)
            with store.reserve():
                pass
            restarted = adapter.LedgerOperationStore(
                store.db, mission_id="MISS-P14A-1", operation_id="OP-P14A-1",
                authorization_reference=AUTH, payload={"text": "p14a"}, target=target(),
            )
            recovered = restarted.recover()
            self.assertEqual(ledger.UNKNOWN, recovered.state)
            self.assertIn("automatic_resend", recovered.evidence)
            self.assertFalse(recovered.evidence["automatic_resend"])
            with self.assertRaises(cc.AuthorizationError):
                restarted.assert_transmittable()

    def test_duplicate_terminal_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            _, store, _ = build_store(td)
            with store.reserve():
                pass
            store.update(ledger.DELIVERED, {"receipt": "msg-1"})
            store.update(ledger.RUNNING, {"observed": "ok"})
            store.update(ledger.COMPLETED, {"report": "ok"})
            with self.assertRaises((ledger.InvalidTransition, cc.ContractError)):
                store.update(ledger.COMPLETED, {"report": "duplicado"})

    def test_divergent_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            db, _, _ = build_store(td)
            with self.assertRaises(ledger.DuplicateMission):
                db.authorize("MISS-P14A-1", authorization_reference=AUTH,
                             payload_sha256_value="0" * 64, actor="test")

    def test_same_lock_two_declared_consumers_is_not_identity_proof(self):
        interface = adapter.authorization_interface_p14b()
        self.assertFalse(interface["consumer_identity_proven"])
        self.assertTrue(interface["flock_is_not_consumer_identity"])
        self.assertFalse(interface["operational_authorization"])
        with tempfile.TemporaryDirectory() as td:
            lock = os.path.join(td, "consumer.lock")
            with open(lock, "w", encoding="utf-8") as handle:
                handle.write("lock")
            sample = os.path.join(td, "sample.json")
            now = datetime.now(timezone.utc)
            with open(sample, "w", encoding="utf-8") as handle:
                json.dump({
                    "consumer_id": "A", "authorization_reference": AUTH,
                    "session_id": SESSION, "endpoint": pilot.DEFAULT_ENDPOINT,
                    "acquired_at": (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
                    "expires_at": (now + timedelta(minutes=30)).isoformat().replace("+00:00", "Z"),
                    "lock_scope": lock,
                }, handle)
            with open(sample, encoding="utf-8") as handle:
                base = json.load(handle)
            import fcntl
            descriptor = os.open(lock, os.O_RDWR)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                verified = []
                for consumer in ("CONSUMER-A", "CONSUMER-B"):
                    path = os.path.join(td, consumer + ".json")
                    with open(path, "w", encoding="utf-8") as handle:
                        json.dump({**base, "consumer_id": consumer}, handle)
                    verified.append(pilot.verify_consumer_evidence(
                        path, session_id=SESSION, endpoint=pilot.DEFAULT_ENDPOINT,
                    )["verified"])
                self.assertEqual([True, True], verified)
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)

    def test_model_selection_is_delegated_to_71(self):
        one_m = mp.model(model_id="hy4-flash-free", context=1_000_000)
        small = mp.model(model_id="small-free", context=128_000)
        result = adapter.select_with_policy(
            mp.inventory(one_m, small), mp.mission(), mp.policy(),
            [mp.evidence(one_m), mp.evidence(small)], [], NOW,
        )
        self.assertEqual(mp.Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/hy4-flash-free#default", adapter.model_evidence(result)["selected"])

    def test_unavailable_free_and_unauthorized_paid_are_blocked(self):
        free = mp.model(model_id="free-only")
        blocked = adapter.select_with_policy(
            mp.inventory(free), mp.mission(), mp.policy(), [], [], NOW,
        )
        self.assertIsNone(blocked.selected)
        self.assertIn("insufficient_evidence", blocked.reason_codes)
        paid = mp.model(provider_id="opencode-go", model_id="premium", cost="additional")
        needs_auth = adapter.select_with_policy(
            mp.inventory(paid), mp.mission(estimated_extra_cost_usd="0.10"),
            mp.policy(allow_additional_consumption=True), [mp.evidence(paid)], [], NOW,
        )
        self.assertEqual(mp.Decision.NEEDS_AUTHORIZATION, needs_auth.decision)
        self.assertTrue(adapter.model_evidence(needs_auth)["authorization_required"])

    def test_synthetic_evidence_never_claims_real_transport(self):
        evidence = adapter.synthetic_transport_evidence("UNKNOWN")
        self.assertEqual("synthetic", evidence["source"])
        self.assertFalse(evidence["transport_real"])
        self.assertFalse(evidence["service_contacted"])
        self.assertFalse(evidence["authoritative_transport"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
