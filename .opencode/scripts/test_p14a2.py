#!/usr/bin/env python3
"""COORD-04/P14A.2 — executor completo com LedgerOperationStore e transporte sintético."""
from __future__ import annotations

import os
import tempfile
import unittest

import coord_communication as cc
import coord_ledger as ledger
import coord_p14_adapter as adapter
import coord_pilot as pilot

import test_pilot_executor as fx


def integrated(directory: str, *, client=None, mission=None):
    client = client or fx.fake_client()[0]
    instance = fx.fake_client()[1]
    mission = mission or fx.mission()
    pilot_authorization = fx.pilot_auth(mission, instance_id=instance)
    transport = pilot.OpenCodeTransport(
        client, expected_instance_id=instance, registered_targets=(fx.TARGET,),
        pilot_authorization=pilot_authorization,
        allow_write=True, clock=lambda: fx.NOW, mode="fixture")
    db = ledger.Ledger(
        os.path.join(directory, "ledger.db"),
        project_id=mission.project_id, repository_id=mission.repository_id,
    )
    store = adapter.LedgerOperationStore(
        db, mission_id=mission.mission_id, operation_id=mission.operation_id,
        authorization_reference=mission.authorization_reference, payload=mission.payload,
        target=fx.TARGET, clock=lambda: fx.NOW,
        authorization_canonical=pilot_authorization.canonical(),
        mission_snapshot=pilot.PilotExecutor._snapshot(mission),
        synthetic_authorization=adapter.synthetic_fixture_authorization(
            mission.authorization_reference))
    executor = pilot.PilotExecutor(
        transport, store, fx.approval(), endpoint=pilot.DEFAULT_ENDPOINT,
        allow_real=False, clock=lambda: fx.NOW)
    return mission, client, db, store, executor


def posts(client) -> int:
    return len([call for call in client.calls if call[0] == "POST"])


def initial_record(mission):
    return pilot.PilotOperationRecord(
        mission.mission_id, mission.operation_id, mission.authorization_reference,
        "canonical", mission.target.key(), "prompt-hash", ledger.AUTHORIZED,
        cc.iso(fx.NOW), cc.iso(fx.NOW), mission_snapshot={"mission_id": mission.mission_id})


class P14A2ExecutorTests(unittest.TestCase):
    def test_valid_mission_reaches_synthetic_transport_exactly_once(self):
        with tempfile.TemporaryDirectory() as td:
            mission, client, db, store, executor = integrated(td)
            result = executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            self.assertEqual("DISPATCHING", result["state"])
            self.assertTrue(result["attempted"])
            self.assertTrue(result["executed"])
            self.assertFalse(result["completed"])
            self.assertEqual(1, posts(client))
            self.assertEqual(ledger.DISPATCHING, db.get(mission.mission_id).state)
            evidence = store.load().evidence
            self.assertEqual("fixture", evidence["source"])
            self.assertFalse(evidence["service_contacted"])

    def test_missing_or_divergent_reservation_has_zero_posts(self):
        with tempfile.TemporaryDirectory() as td:
            mission, client, _, store, executor = integrated(td)
            # Reserva prévia/alheia: store já não é elegível para primeira reserva.
            store.create(initial_record(mission))
            with store.reserve():
                pass
            with self.assertRaises(cc.AuthorizationError):
                executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            self.assertEqual(0, posts(client))

    def test_two_concurrent_consumers_do_not_dispatch_same_mission(self):
        with tempfile.TemporaryDirectory() as td:
            mission, client, _, _, first = integrated(td)
            _, second_client, _, _, second = integrated(td, client=fx.fake_client()[0])
            first.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            with self.assertRaises(cc.AuthorizationError):
                second.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            self.assertEqual(1, posts(client))
            self.assertEqual(0, posts(second_client))

    def test_unknown_blocks_resend_without_new_post(self):
        with tempfile.TemporaryDirectory() as td:
            mission, client, _, store, executor = integrated(td)
            executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            record = store.update(ledger.UNKNOWN, {"interruption": "simulada"})
            self.assertEqual(ledger.UNKNOWN, record.state)
            with self.assertRaises(cc.AuthorizationError):
                executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            self.assertEqual(1, posts(client))

    def test_restart_preserves_reservation_protection(self):
        with tempfile.TemporaryDirectory() as td:
            mission, client, db, _, executor = integrated(td)
            executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            restarted = adapter.LedgerOperationStore(
                db, mission_id=mission.mission_id, operation_id=mission.operation_id,
                authorization_reference=mission.authorization_reference,
                payload=mission.payload, target=fx.TARGET, clock=lambda: fx.NOW,
                authorization_canonical=fx.pilot_auth(mission).canonical(),
                mission_snapshot=pilot.PilotExecutor._snapshot(mission))
            self.assertEqual(ledger.DISPATCHING, restarted.load().state)
            with self.assertRaises(cc.AuthorizationError):
                restarted.assert_transmittable()
            self.assertEqual(1, posts(client))

    def test_failed_attempt_is_not_a_conclusion(self):
        with tempfile.TemporaryDirectory() as td:
            mission, _, db, store, executor = integrated(td)
            executor.execute(mission, fx.core_auth(mission), pilot.PILOT_PROMPT)
            store.update(ledger.UNKNOWN, {"interruption": "simulada"})
            # Saída de UNKNOWN exige reconciliação explícita com autoridade.
            db.reconcile(mission.mission_id, target=ledger.RUNNING,
                         evidence="observação parcial", authority_reference="AUTH-QA",
                         actor="qa")
            failed = store.update(ledger.FAILED, {"reason": "simulada"})
            self.assertEqual(ledger.FAILED, failed.state)
            self.assertEqual(ledger.FAILED, store.load().state)

    def test_synthetic_provenance_is_explicit(self):
        evidence = adapter.synthetic_transport_evidence("UNKNOWN")
        self.assertEqual("synthetic", evidence["source"])
        self.assertFalse(evidence["transport_real"])
        self.assertFalse(evidence["service_contacted"])
        self.assertFalse(evidence["authoritative_transport"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
