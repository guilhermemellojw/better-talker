#!/usr/bin/env python3
"""COORD-04/P14B — integração sintética e testes negativos da fronteira assinada."""
from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from datetime import timedelta

import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger as ledger
import coord_ledger_authority as cla
import coord_pilot as pilot
import coord_pilot_cli as cli
import coord_p14b as p14b

CLI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "coord_pilot_cli.py")


def context(store_dir: str, *, now=cli.NOW):
    mission = cli._fixture_context(store_dir=os.path.join(store_dir, "fixture"))["mission"]
    store = p14b.SignedOperationStore(
        mission=mission, store_dir=store_dir,
        model=cli.selected_model_for_dispatch(cli.fixture_model_recommendation()),
        now=now, target=mission.target,
        authorization_reference=mission.authorization_reference,
        authorization_canonical="canonical", clock=lambda: now)
    return store, mission


def authorize_and_reserve(store):
    store.create(None)  # type: ignore[arg-type]
    lease = store.db.reserve_dispatch_signed(
        store.mission_id, authorization_digest=store._digest,
        agent=store.target.agent_id, session_id=store.target.session_id,
        instance_id=store.target.instance_id, worktree=store.target.location,
        actor="qa")
    store._lease_id = lease.lease_id
    return lease


class P14BSyntheticJourneyTests(unittest.TestCase):
    def test_legitimate_authorization_completes(self):
        with tempfile.TemporaryDirectory() as td:
            mission = cli._fixture_context(store_dir=os.path.join(td, "base"))["mission"]
            result = p14b.run_synthetic_journey(mission, store_dir=td)
            self.assertEqual(ledger.COMPLETED, result["state"])
            self.assertFalse(result["reserved"])
            self.assertFalse(result["transport_real"])
            self.assertFalse(result["service_contacted"])
            self.assertIsNotNone(result["receipt_sha256"])
            self.assertEqual("coord_authority_signed+coord_ledger_1_1", result["basis"])

    def test_cli_uses_signed_authority_not_legacy_cache(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "dry-run"])
            self.assertEqual("COMPLETED", out["final_state"])
            self.assertEqual(1, out["http_posts"])
            self.assertFalse(os.path.exists(os.path.join(td, "operation.json")))
            from coord_ledger import AUTHORIZATION_MODE_SIGNED
            with sqlite3.connect(os.path.join(td, "ledger.db")) as conn:
                mode = conn.execute(
                    "SELECT authorization_mode FROM missions WHERE mission_id=?",
                    ("MISS-COORD04-PILOT-001",)).fetchone()[0]
            self.assertEqual(AUTHORIZATION_MODE_SIGNED, mode)

    def test_interrupted_post_then_revocation_preserves_unknown(self):
        with tempfile.TemporaryDirectory() as td:
            mission = cli._fixture_context(store_dir=os.path.join(td, "base"))["mission"]
            result = p14b.run_synthetic_journey(
                mission, store_dir=td, interrupt_before_receipt=True,
                revoke_after_interrupt=True)
            self.assertEqual("UNKNOWN", result["state"])
            self.assertIsNone(result["receipt_sha256"])
            self.assertTrue(result["reserved"])
            self.assertFalse(result["can_dispatch"])
            self.assertEqual("UNKNOWN", result["late_state"])
            self.assertTrue(result["late_reserved"])
            self.assertEqual("blocked", result["post_interrupt_terminalize_attempt"])

    def test_interrupted_post_then_expiration_preserves_unknown(self):
        with tempfile.TemporaryDirectory() as td:
            mission = cli._fixture_context(store_dir=os.path.join(td, "base"))["mission"]
            result = p14b.run_synthetic_journey(
                mission, store_dir=td, interrupt_before_receipt=True,
                expire_after_interrupt=True)
            self.assertEqual("UNKNOWN", result["state"])
            self.assertIsNone(result["receipt_sha256"])
            self.assertTrue(result["reserved"])
            self.assertTrue(result["late_reserved"])
            self.assertFalse(result["late_can_dispatch"])
            self.assertEqual("blocked", result["post_interrupt_terminalize_attempt"])


class P14BNegativeTests(unittest.TestCase):
    def test_forged_signature_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authority = p14b.AuthorityPort(allowlist=store._allowlist)
            mutated = bytearray(store._signature)
            mutated[50] ^= 0x20
            forged = bytes(mutated)
            with self.assertRaises(cla.AuthorizationRejected) as caught:
                authority.verify_authorization(
                    envelope=store._envelope_mapping, signature=forged,
                    payload=store.payload,
                    expected=store._snapshot.expected_operation(), now=cli.NOW)
            self.assertIn("SIGNATURE_INVALID", str(caught.exception))

    def test_wrong_namespace_signature_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            bad = store._signer.sign(
                cas.canonical_envelope_bytes(store._envelope),
                namespace="wrong-namespace", directory=td)
            authority = p14b.AuthorityPort(allowlist=store._allowlist)
            with self.assertRaises(cla.AuthorizationRejected):
                authority.verify_authorization(
                    envelope=store._envelope_mapping, signature=bad,
                    payload=store.payload,
                    expected=store._snapshot.expected_operation(), now=cli.NOW)

    def test_payload_and_model_divergence_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authority = p14b.AuthorityPort(allowlist=store._allowlist)
            for field, value in (("payload", {"prompt": "outro"}), ("model", "outro/model")):
                with self.subTest(field=field):
                    snapshot = replace(
                        store._snapshot,
                        payload=value if field == "payload" else store._snapshot.payload,
                        model=value if field == "model" else store._snapshot.model)
                    with self.assertRaises(cla.AuthorizationRejected) as caught:
                        authority.verify_authorization(
                            envelope=store._envelope_mapping,
                            signature=store._signature, payload=store.payload,
                            expected=snapshot.expected_operation(), now=cli.NOW)
                    self.assertIn("OPERATION_MISMATCH", str(caught.exception))

    def test_self_declared_authorization_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            store.create(None)  # type: ignore[arg-type]
            with self.assertRaises(ledger.SignedAuthorizationRequired):
                store.db.authorize(
                    store.mission_id, authorization_reference="EU-MESMO",
                    payload_sha256_value=store.payload_hash, actor="agente")

    def test_untrusted_allowlist_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            other = p14b.SyntheticSigner(os.path.join(td, "other"))
            authority = p14b.AuthorityPort(
                allowlist=cas.ApproverAllowlist((other.approver_key(cli.NOW),)))
            with self.assertRaises(cla.AuthorizationRejected) as caught:
                authority.verify_authorization(
                    envelope=store._envelope_mapping, signature=store._signature,
                    payload=store.payload,
                    expected=store._snapshot.expected_operation(), now=cli.NOW)
            self.assertTrue(
                any(code in str(caught.exception)
                    for code in ("TRUST_POLICY_MISMATCH", "APPROVER_KEY_UNKNOWN")))

    def test_snapshot_tampering_is_detected(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authorize_and_reserve(store)
            path = os.path.join(td, "ledger.db")
            with sqlite3.connect(path) as conn:
                row = conn.execute(
                    "SELECT mission_snapshot_json FROM missions WHERE mission_id=?",
                    (store.mission_id,)).fetchone()
                values = json.loads(row[0])
                values["model"] = "outro/model"
                conn.execute(
                    "UPDATE missions SET mission_snapshot_json=? WHERE mission_id=?",
                    (json.dumps(values, sort_keys=True), store.mission_id))
            with self.assertRaises(ledger.LedgerStorageError):
                store.db.assert_dispatchable(
                    store.mission_id, authorization_digest=store._digest,
                    lease_id=store._lease_id, actor="qa")

    def test_revocation_after_reservation_blocks_dispatch(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            lease = authorize_and_reserve(store)
            statement = cas.SignedRevocation(
                schema=cas.REVOCATION_SCHEMA_VERSION,
                authorization_digest=store._digest,
                approver_id=store._signer.approver_id,
                approver_key_fingerprint=store._signer.fingerprint,
                trust_policy_sha256=store._allowlist.digest(),
                revoked_at=cli.NOW, reason="contramandamento", nonce="rev-p14b")
            signature = store._signer.sign(
                cas.canonical_revocation_bytes(statement),
                namespace=cas.REVOCATION_NAMESPACE, directory=td)
            store.db.revoke_authorization(
                statement=statement.canonical_material(), signature=signature,
                actor="human-synthetic")
            with self.assertRaises(ledger.AuthorizationRevoked):
                store.db.assert_dispatchable(
                    store.mission_id, authorization_digest=store._digest,
                    lease_id=lease.lease_id, actor="executor")

    def test_expiration_after_reservation_blocks_dispatch(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            lease = authorize_and_reserve(store)
            late = ledger.Ledger(
                os.path.join(td, "ledger.db"), project_id=store.db.project_id,
                repository_id=store.db.repository_id,
                authority_verifier=p14b.AuthorityPort(allowlist=store._allowlist),
                reconciliation_verifier=store.db.reconciliation_verifier,
                clock=lambda: cli.NOW + timedelta(hours=1),
                policy={"allow_legacy_dispatch": False,
                        "allow_real_transport": False})
            with self.assertRaises(cla.AuthorityContractError):
                late.assert_dispatchable(
                    store.mission_id, authorization_digest=store._digest,
                    lease_id=lease.lease_id, actor="executor")

    def test_lease_replay_is_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authorize_and_reserve(store)
            with self.assertRaises(cc.AuthorizationError):
                with store.reserve():
                    pass

    def test_forged_attestation_cannot_fabricate_completion(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authorize_and_reserve(store)
            store.db.record_dispatch_evidence(
                store.mission_id, authorization_digest=store._digest,
                lease_id=store._lease_id,
                receipt={"operation_id": store.operation_id,
                         "payload_sha256": store.payload_hash,
                         "observed_message_id": "msg-p14b", "source": "fixture"},
                evidence={"source": "fixture", "environment": "FIXTURE",
                          "service_contacted": False, "transport_real": False},
                actor="executor")
            with self.assertRaises(cla.ReconciliationRejected):
                store.db.reconcile_signed(
                    store.mission_id, authorization_digest=store._digest,
                    lease_id=store._lease_id, target_state=ledger.COMPLETED,
                    attestation={"authority": "synthetic-attestor",
                                 "outcome": "COMPLETED"},
                    attestation_signature=b"forjado",
                    report={"project_id": store._mission.project_id,
                            "repository_id": store._mission.repository_id,
                            "mission_id": store.mission_id,
                            "operation_id": store.operation_id,
                            "agent_id": store.target.agent_id,
                            "session_id": store.target.session_id,
                            "message_id": "msg-r", "reply_to": "msg-p14b",
                            "status": "COMPLETED", "blockers": [],
                            "acceptance_results": {
                                c: True for c in store._snapshot.acceptance_criteria},
                            "payload_sha256": store.payload_hash,
                            "completed_at": cc.iso(cli.NOW)},
                    evidence={"source": "fixture", "environment": "FIXTURE",
                              "service_contacted": False, "transport_real": False},
                    actor="agente")

    def test_legacy_dispatch_path_is_closed_by_default(self):
        with tempfile.TemporaryDirectory() as td:
            store, _ = context(td)
            authorize_and_reserve(store)
            self.assertFalse(store.db.can_dispatch(store.mission_id))
            with self.assertRaises(ledger.SignedTransitionRequired):
                store.db.mark_unknown(store.mission_id, evidence="x", actor="agente")


if __name__ == "__main__":
    unittest.main(verbosity=2)
