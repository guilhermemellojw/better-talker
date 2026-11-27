#!/usr/bin/env python3
"""COORD-05A-EXT/1.1 — testes host-only do ledger, migração e reconciliação."""
from __future__ import annotations

import dataclasses
import hashlib
import json
import multiprocessing
import os
import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import coord_ledger as ledger
from coord_ledger_authority import (
    AuthorityContractError,
    AuthorizationRejected,
    MissionSnapshot,
    RevocationRejected,
    VerifiedAuthorization,
    VerifiedReconciliation,
    VerifiedRevocation,
    canonical_json,
)

PROJECT = "better-talker"
REPO = "guilhermemellojw/better-talker"
NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)
AUTH = "AUTH-REF"


class Clock:
    def __init__(self, now: datetime = NOW):
        self.now = now

    def __call__(self) -> datetime:
        return self.now


class FakeVerifier:
    """Verificador determinístico; não representa autenticação humana."""

    def __init__(
        self, *, label: str = "auth", digest: str | None = None,
        approval_reference: str = AUTH, environment: str = "FIXTURE",
        issued_at: datetime | None = None, expires_at: datetime | None = None,
        mission_identity: str | None = None,
        authority_material_sha256: str | None = None,
        authorization_error: Exception | None = None,
        revocation_error: Exception | None = None,
        revocation_digest: str | None = None,
        revocation_at: datetime | None = None,
        revocation_reason: str = "contramandamento",
        revocation_nonce: str | None = None,
    ) -> None:
        self.label = label
        self.digest = digest or hashlib.sha256(label.encode()).hexdigest()
        self.approval_reference = approval_reference
        self.environment = environment
        self.issued_at = issued_at or NOW - timedelta(minutes=1)
        self.expires_at = expires_at or NOW + timedelta(minutes=10)
        self.mission_identity = mission_identity
        self.authority_material_sha256 = authority_material_sha256
        self.authorization_error = authorization_error
        self.revocation_error = revocation_error
        self.revocation_digest = revocation_digest
        self.revocation_at = revocation_at
        self.revocation_reason = revocation_reason
        self.revocation_nonce = revocation_nonce
        self.signature = b"signature-" + label.encode()
        self.calls = 0

    def verify_authorization(self, *, envelope, signature, payload, expected, now):
        self.calls += 1
        if self.authorization_error:
            raise self.authorization_error
        if bytes(signature) != self.signature:
            raise AuthorizationRejected("assinatura sintética divergente")
        material_sha = self.authority_material_sha256 or hashlib.sha256(
            canonical_json(expected.material).encode()).hexdigest()
        return VerifiedAuthorization(
            authorization_digest=self.digest,
            authorization_id="AUTH-ID-" + self.label,
            approval_reference=self.approval_reference,
            mission_id=expected.mission_id,
            mission_identity=self.mission_identity or expected.mission_identity,
            authority_material_sha256=material_sha,
            approver_id="maintainer",
            approver_key_fingerprint="SHA256:synthetic",
            trust_policy_sha256="f" * 64,
            environment=self.environment,
            issued_at=self.issued_at or now - timedelta(minutes=1),
            expires_at=self.expires_at or now + timedelta(minutes=10),
            nonce="nonce-" + self.label,
            signature=self.signature,
        )

    def verify_revocation(self, *, statement, signature, now):
        if self.revocation_error:
            raise self.revocation_error
        return VerifiedRevocation(
            authorization_digest=self.revocation_digest or self.digest,
            approver_id="maintainer",
            approver_key_fingerprint="SHA256:synthetic",
            trust_policy_sha256="f" * 64,
            revoked_at=self.revocation_at or now,
            reason=self.revocation_reason,
            nonce=self.revocation_nonce or ("rev-" + self.label),
            signature=b"revocation-" + self.label.encode(),
        )


class FakeReconciliationVerifier:
    def __init__(
        self, *, outcome: str | None = None, error: Exception | None = None,
        authority_id: str = "maintainer", issued_at: datetime | None = None,
        expires_at: datetime | None = None, mutate=None,
    ) -> None:
        self.outcome = outcome
        self.error = error
        self.authority_id = authority_id
        self.issued_at = issued_at or NOW
        self.expires_at = expires_at or NOW + timedelta(hours=1)
        self.mutate = dict(mutate or {})

    def verify_reconciliation(self, *, attestation, signature, expected, now):
        if self.error:
            raise self.error
        values = {
            "authority_id": self.authority_id,
            "mission_id": expected.mission_id,
            "authorization_digest": expected.authorization_digest,
            "payload_sha256": expected.payload_sha256,
            "receipt_sha256": expected.receipt_sha256,
            "report_sha256": expected.report_sha256,
            "evidence_sha256": expected.evidence_sha256,
            "outcome": self.outcome or expected.outcome,
            "issued_at": self.issued_at,
            "expires_at": self.expires_at,
            "nonce": "attestation-nonce-1",
            "signature": b"attestation-signature",
        }
        values.update(self.mutate)
        return VerifiedReconciliation(**values)


def make_snapshot(mission_id: str = "MISS-L11", operation_id: str = "OP-L11",
                  **overrides) -> MissionSnapshot:
    values = {
        "mission_id": mission_id,
        "operation_id": operation_id,
        "project_id": PROJECT,
        "repository_id": REPO,
        "coordinator_id": "coord",
        "agent": "A",
        "session_id": "ses-l11",
        "instance_id": "inst-1",
        "worktree": "/tmp/wt-l11",
        "mission_identity": hashlib.sha256(mission_id.encode()).hexdigest(),
        "payload": {"prompt": "hello", "mission": mission_id},
        "model": "opencode/hy4-flash-free",
        "scope": "SCOPE",
        "acceptance_criteria": ("criterion-a", "criterion-b"),
        "environment": "FIXTURE",
        "reviewed_head_sha": "b" * 40,
        "operational_digests": {"coord_ledger.py": "c" * 64},
        "max_transmissions": 1,
        "cost_ceiling_usd": "0",
        "free_only": True,
    }
    values.update(overrides)
    return MissionSnapshot(**values)


def make_ledger(directory: str, *, verifier=None, reconciliation_verifier=None,
                clock=None, policy=None,
                allow_migration=False) -> ledger.Ledger:
    return ledger.Ledger(
        os.path.join(directory, "ledger.db"), project_id=PROJECT,
        repository_id=REPO, authority_verifier=verifier,
        reconciliation_verifier=reconciliation_verifier or FakeReconciliationVerifier(),
        clock=clock or Clock(), policy=policy, allow_migration=allow_migration)


def authorize(db: ledger.Ledger, snapshot: MissionSnapshot,
              verifier: FakeVerifier, reference: str = AUTH):
    db.register_signed_proposed(snapshot, authorization_reference=reference)
    return db.authorize_signed(
        snapshot.mission_id, envelope={"synthetic": True},
        signature=verifier.signature, payload=snapshot.payload, actor="human")


def reserve(db: ledger.Ledger, snapshot: MissionSnapshot,
            verifier: FakeVerifier) -> ledger.DispatchLease:
    return db.reserve_dispatch_signed(
        snapshot.mission_id, authorization_digest=verifier.digest,
        agent=snapshot.agent, session_id=snapshot.session_id,
        instance_id=snapshot.instance_id, worktree=snapshot.worktree,
        actor="coord")


def receipt(snapshot: MissionSnapshot, observed: str = "msg-prompt-1"):
    return {
        "operation_id": snapshot.operation_id,
        "payload_sha256": snapshot.payload_sha256,
        "agent_id": snapshot.agent,
        "session_id": snapshot.session_id,
        "instance_id": snapshot.instance_id,
        "location": snapshot.worktree,
        "observed_message_id": observed,
        "source": "fixture",
    }


def evidence(snapshot: MissionSnapshot):
    return {
        "source": "fixture",
        "environment": snapshot.environment,
        "service_contacted": False,
        "transport_real": False,
    }


def report(snapshot: MissionSnapshot, receipt_value, *, clock: Clock,
           **overrides):
    values = {
        "project_id": snapshot.project_id,
        "repository_id": snapshot.repository_id,
        "mission_id": snapshot.mission_id,
        "operation_id": snapshot.operation_id,
        "agent_id": snapshot.agent,
        "session_id": snapshot.session_id,
        "message_id": "msg-report-1",
        "reply_to": receipt_value["observed_message_id"],
        "status": "COMPLETED",
        "blockers": [],
        "acceptance_results": {c: True for c in snapshot.acceptance_criteria},
        "payload_sha256": snapshot.payload_sha256,
        "completed_at": ledger.iso(clock.now),
    }
    values.update(overrides)
    return values


def record_evidence(db: ledger.Ledger, snapshot: MissionSnapshot,
                    verifier: FakeVerifier, *, lease=None, receipt_value=None):
    lease = lease or reserve(db, snapshot, verifier)
    receipt_value = receipt_value or receipt(snapshot)
    db.record_dispatch_evidence(
        snapshot.mission_id, authorization_digest=verifier.digest,
        lease_id=lease.lease_id, receipt=receipt_value,
        evidence=evidence(snapshot), actor="executor")
    return lease, receipt_value


class Ledger11Test(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.clock = Clock()
        self.verifier = FakeVerifier()
        self.db = make_ledger(self._tmp.name, verifier=self.verifier, clock=self.clock)
        self.snapshot = make_snapshot()

    def tearDown(self):
        self._tmp.cleanup()

    def _mutate_snapshot(self, **changes):
        tampered = dataclasses.replace(self.snapshot, **changes)
        raw = canonical_json(tampered.canonical_material())
        conn = sqlite3.connect(self.db.db_path)
        conn.execute(
            """UPDATE missions SET mission_snapshot_json=?,
               mission_snapshot_sha256=?, payload_sha256=?, environment=?
               WHERE mission_id=?""",
            (raw, tampered.sha256(), tampered.payload_sha256,
             tampered.environment, self.snapshot.mission_id),
        )
        conn.commit()
        conn.close()
        return tampered

    def test_register_snapshot_and_authorize_persist_digest(self):
        record = self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.assertEqual(ledger.PROPOSED, record.state)
        self.assertEqual("SIGNED_V1", record.authorization_mode)
        self.assertEqual(self.snapshot.sha256(), record.mission_snapshot_sha256)
        self.assertEqual(self.snapshot.payload_sha256, record.payload_sha256)
        self.assertIsNone(self.db.get_authorization(self.snapshot.mission_id))
        record = self.db.authorize_signed(
            self.snapshot.mission_id, envelope={}, signature=self.verifier.signature,
            payload=self.snapshot.payload, actor="human")
        self.assertEqual(ledger.AUTHORIZED, record.state)
        self.assertEqual(self.verifier.digest, record.authorization_digest)
        auth = self.db.get_authorization(self.snapshot.mission_id)
        self.assertIsNotNone(auth)
        self.assertEqual(self.snapshot.sha256(), auth.mission_snapshot_sha256)
        self.assertEqual(self.snapshot.authority_material_sha256(),
                         auth.authority_material_sha256)
        self.assertEqual(1, self.db.authorize_signed(
            self.snapshot.mission_id, envelope={}, signature=self.verifier.signature,
            payload=self.snapshot.payload, actor="human").state == ledger.AUTHORIZED)

    def test_unsigned_paths_rejected_for_signed_mission(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        with self.assertRaises(ledger.SignedAuthorizationRequired):
            self.db.authorize(self.snapshot.mission_id, authorization_reference=AUTH,
                              payload_sha256_value=self.snapshot.payload_sha256, actor="x")
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        with self.assertRaises(ledger.SignedAuthorizationRequired):
            self.db.reserve_dispatch(self.snapshot.mission_id, agent="A",
                                     session_id="ses-l11", instance_id="inst-1",
                                     worktree="/tmp/wt-l11", actor="x")
        self.assertFalse(self.db.can_dispatch(self.snapshot.mission_id))

    def test_payload_and_snapshot_tampering_fail_closed(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                     signature=self.verifier.signature,
                                     payload={"prompt": "tampered"}, actor="human")
        conn = sqlite3.connect(self.db.db_path)
        conn.execute("UPDATE missions SET mission_snapshot_json='{}' WHERE mission_id=?",
                     (self.snapshot.mission_id,))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                     signature=self.verifier.signature,
                                     payload=self.snapshot.payload, actor="human")

    def test_snapshot_hash_tampering_blocks_reservation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        conn = sqlite3.connect(self.db.db_path)
        conn.execute("UPDATE missions SET mission_snapshot_json='{}' WHERE mission_id=?",
                     (self.snapshot.mission_id,))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            reserve(self.db, self.snapshot, self.verifier)
        self.assertIsNone(self.db.get_authorization(self.snapshot.mission_id).consumed_at)

    def test_authorization_expiry_and_revocation(self):
        expiring = FakeVerifier(label="exp", expires_at=NOW - timedelta(seconds=1))
        db = make_ledger(self._tmp.name)
        db = ledger.Ledger(db.db_path, project_id=PROJECT, repository_id=REPO,
                           authority_verifier=expiring, clock=self.clock)
        with self.assertRaises(ledger.AuthorizationExpired):
            authorize(db, self.snapshot, expiring)
        # Pré-revogação por digest: autorização posterior falha.
        db2 = make_ledger(self._tmp.name + "-2")
        db2 = ledger.Ledger(db2.db_path, project_id=PROJECT, repository_id=REPO,
                            authority_verifier=self.verifier, clock=self.clock)
        db2.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db2.revoke_authorization(statement={}, signature=self.verifier.signature, actor="human")
        with self.assertRaises(ledger.AuthorizationRevoked):
            db2.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")

    def test_revocation_after_reservation_blocks_assert(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        self.db.revoke_authorization(statement={}, signature=self.verifier.signature,
                                     actor="human")
        with self.assertRaises(ledger.AuthorizationRevoked):
            self.db.assert_dispatchable(self.snapshot.mission_id,
                                        authorization_digest=self.verifier.digest,
                                        lease_id=lease.lease_id, actor="exec")
        self.assertEqual(ledger.DISPATCHING,
                         self.db.get(self.snapshot.mission_id).state)
        self.assertTrue(self.db.is_reserved(self.snapshot.mission_id))

    def test_invalid_revocation_not_persisted(self):
        failing = FakeVerifier(
            label="bad-rev", revocation_error=RevocationRejected("invalid"))
        db = ledger.Ledger(make_ledger(self._tmp.name + "-rev").db_path,
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=failing, clock=self.clock)
        with self.assertRaises(RevocationRejected):
            db.revoke_authorization(statement={}, signature=failing.signature, actor="x")
        self.assertIsNone(db.get_revocation(failing.digest))

    def test_revocation_idempotent_and_divergent(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        first = self.db.revoke_authorization(statement={}, signature=self.verifier.signature,
                                             actor="x")
        second = self.db.revoke_authorization(statement={}, signature=self.verifier.signature,
                                              actor="x")
        self.assertEqual(first.signature_sha256, second.signature_sha256)
        divergent = FakeVerifier(label="auth", revocation_reason="outro")
        db2 = ledger.Ledger(self.db.db_path, project_id=PROJECT, repository_id=REPO,
                            authority_verifier=divergent, clock=self.clock)
        with self.assertRaises(ledger.EvidenceDivergence):
            db2.revoke_authorization(statement={}, signature=divergent.signature, actor="x")

    def test_reservation_consumes_once_and_asserts(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        record = self.db.get(self.snapshot.mission_id)
        self.assertEqual(ledger.DISPATCHING, record.state)
        self.assertEqual(1, record.transmissions_used)
        auth = self.db.get_authorization(self.snapshot.mission_id)
        self.assertIsNotNone(auth.consumed_at)
        self.assertEqual(lease, self.db.assert_dispatchable(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, actor="exec"))
        with self.assertRaises((ledger.DuplicateReservation, ledger.InvalidTransition)):
            reserve(self.db, self.snapshot, self.verifier)
        with self.assertRaises(ledger.DispatchBlocked):
            self.db.assert_dispatchable(self.snapshot.mission_id,
                                        authorization_digest=self.verifier.digest,
                                        lease_id="wrong", actor="exec")

    def test_expired_reservation_does_not_consume(self):
        expiring = FakeVerifier(label="late", expires_at=NOW + timedelta(seconds=5))
        clock = Clock()
        db = ledger.Ledger(os.path.join(self._tmp.name, "late.db"),
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=expiring, clock=clock)
        self.db = db
        db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db.authorize_signed(self.snapshot.mission_id, envelope={},
                            signature=expiring.signature, payload=self.snapshot.payload,
                            actor="human")
        clock.now = NOW + timedelta(seconds=10)
        with self.assertRaises(ledger.AuthorizationExpired):
            reserve(db, self.snapshot, expiring)
        self.assertIsNone(db.get_authorization(self.snapshot.mission_id).consumed_at)
        self.assertEqual(ledger.AUTHORIZED, db.get(self.snapshot.mission_id).state)

    def test_evidence_and_valid_reconciliation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        record = self.db.get(self.snapshot.mission_id)
        self.assertIsNotNone(record.receipt_sha256)
        self.assertIsNotNone(record.dispatch_evidence_sha256)
        self.assertEqual({"RECEIPT", "DISPATCH"},
                         {e.kind for e in self.db.get_evidence(self.snapshot.mission_id)
                          if e.kind != "RESERVATION"})
        report_value = report(self.snapshot, receipt_value, clock=self.clock)
        record = self.db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report_value, evidence=evidence(self.snapshot),
            attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.COMPLETED, record.state)
        self.assertFalse(self.db.is_reserved(self.snapshot.mission_id))
        self.assertIsNotNone(self.db.get_authorization(
            self.snapshot.mission_id).consumed_at)
        same = self.db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report_value, evidence=evidence(self.snapshot),
            attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.COMPLETED, same.state)
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=dict(report_value, message_id="other"), evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")

    def test_restart_preserves_evidence_and_allows_reconciliation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        restarted = ledger.Ledger(
            self.db.db_path, project_id=PROJECT, repository_id=REPO,
            authority_verifier=self.verifier,
            reconciliation_verifier=FakeReconciliationVerifier(),
            clock=self.clock)
        record = restarted.get(self.snapshot.mission_id)
        self.assertIsNotNone(record.receipt_sha256)
        self.assertIsNotNone(record.dispatch_evidence_sha256)
        self.assertEqual(lease.lease_id, restarted.assert_dispatchable(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, actor="exec").lease_id)
        report_value = report(self.snapshot, receipt_value, clock=self.clock)
        record = restarted.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report_value, evidence=evidence(self.snapshot),
            attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.COMPLETED, record.state)
        self.assertFalse(restarted.is_reserved(self.snapshot.mission_id))

    def test_reconciliation_rejects_divergent_payload_criteria_and_receipt(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        base = report(self.snapshot, receipt_value, clock=self.clock)
        cases = (
            dict(base, payload_sha256="0" * 64),
            dict(base, acceptance_results={"criterion-a": True}),
            dict(base, reply_to="wrong-prompt"),
            dict(base, mission_id="MISS-OTHER"),
            dict(base, agent_id="B"),
        )
        for value in cases:
            with self.assertRaises((ledger.EvidenceDivergence, ledger.UnsafeReconciliation)):
                self.db.reconcile_signed(
                    self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                    lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                    report=value, evidence=evidence(self.snapshot),
                    attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.DISPATCHING,
                         self.db.get(self.snapshot.mission_id).state)
        self.assertTrue(self.db.is_reserved(self.snapshot.mission_id))

    def test_receipt_tampering_and_real_transport_blocked(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        with self.assertRaises(ledger.DispatchBlocked):
            self.db.record_dispatch_evidence(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id,
                receipt=dict(receipt(self.snapshot), service_contacted=True),
                evidence=evidence(self.snapshot), actor="exec")
        self.db.record_dispatch_evidence(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, receipt=receipt(self.snapshot),
            evidence=evidence(self.snapshot), actor="exec")
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.record_dispatch_evidence(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id,
                receipt=dict(receipt(self.snapshot), observed_message_id="other"),
                evidence=evidence(self.snapshot), actor="exec")

    def test_recovery_unknown_preserves_reservation_and_consumption(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        reserve(self.db, self.snapshot, self.verifier)
        recovered = self.db.recover_stale_dispatching(cause="process_loss")
        self.assertEqual([self.snapshot.mission_id], recovered)
        record = self.db.get(self.snapshot.mission_id)
        self.assertEqual(ledger.UNKNOWN, record.state)
        self.assertTrue(self.db.is_reserved(self.snapshot.mission_id))
        self.assertIsNotNone(self.db.get_authorization(
            self.snapshot.mission_id).consumed_at)
        with self.assertRaises(ledger.DispatchBlocked):
            self.db.assert_dispatchable(self.snapshot.mission_id,
                                        authorization_digest=self.verifier.digest,
                                        lease_id=record.authorization_digest or "x",
                                        actor="exec")
        with self.assertRaises((ledger.InvalidTransition, ledger.DuplicateReservation)):
            reserve(self.db, self.snapshot, self.verifier)

    def test_failed_terminal_clears_reservation_not_consumption(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, _ = record_evidence(self.db, self.snapshot, self.verifier)
        record = self.db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.FAILED,
            evidence={**evidence(self.snapshot), "reason": "synthetic failure"},
            attestation={"authority": "maintainer"},
            attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.FAILED, record.state)
        self.assertFalse(self.db.is_reserved(self.snapshot.mission_id))
        self.assertIsNotNone(self.db.get_authorization(
            self.snapshot.mission_id).consumed_at)

    def test_signed_transition_methods_are_blocked(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        with self.assertRaises(ledger.SignedTransitionRequired):
            self.db.complete(self.snapshot.mission_id, evidence="x", actor="x")
        with self.assertRaises(ledger.SignedTransitionRequired):
            self.db.fail_before_dispatch(self.snapshot.mission_id, evidence="x", actor="x")
        reserve(self.db, self.snapshot, self.verifier)
        with self.assertRaises(ledger.SignedTransitionRequired):
            self.db.mark_unknown(self.snapshot.mission_id, evidence="x", actor="x")

    def test_authorization_replay_across_missions_rejected(self):
        first = make_snapshot("MISS-A", "OP-A")
        second = make_snapshot("MISS-B", "OP-B")
        fake = FakeVerifier(label="replay", digest="d" * 64)
        db = ledger.Ledger(os.path.join(self._tmp.name, "replay.db"),
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=fake, clock=self.clock)
        authorize(db, first, fake)
        db.register_signed_proposed(second, authorization_reference=AUTH)
        with self.assertRaises((ledger.DuplicateMission, ledger.EvidenceDivergence)):
            db.authorize_signed(second.mission_id, envelope={},
                                signature=fake.signature, payload=second.payload,
                                actor="human")
        self.assertEqual(ledger.PROPOSED, db.get(second.mission_id).state)

    def test_signed_registration_is_idempotent_and_rejects_divergence(self):
        first = self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        second = self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.assertEqual(first.identity_hash, second.identity_hash)
        different = make_snapshot(payload={"prompt": "different"})
        with self.assertRaises(ledger.DuplicateMission):
            self.db.register_signed_proposed(different, authorization_reference=AUTH)

    def test_two_signed_missions_same_agent_or_session_blocked(self):
        first = make_snapshot("MISS-A", "OP-A")
        second = make_snapshot("MISS-B", "OP-B")
        fake_a = FakeVerifier(label="a")
        fake_b = FakeVerifier(label="b")
        db = ledger.Ledger(os.path.join(self._tmp.name, "exclusive.db"),
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=fake_a, clock=self.clock)
        authorize(db, first, fake_a)
        db_b = ledger.Ledger(db.db_path, project_id=PROJECT, repository_id=REPO,
                             authority_verifier=fake_b, clock=self.clock)
        authorize(db_b, second, fake_b)
        db_a = ledger.Ledger(db.db_path, project_id=PROJECT, repository_id=REPO,
                             authority_verifier=fake_a, clock=self.clock)
        first_lease = reserve(db_a, first, fake_a)
        with self.assertRaises(ledger.DuplicateReservation):
            reserve(db_b, second, fake_b)
        self.assertTrue(db_a.is_reserved(first.mission_id))
        self.assertEqual(ledger.AUTHORIZED, db_b.get(second.mission_id).state)
        with self.assertRaises(ledger.DispatchBlocked):
            db_a.assert_dispatchable(first.mission_id,
                                     authorization_digest=fake_a.digest,
                                     lease_id="wrong", actor="x")
        self.assertEqual(first_lease.lease_id,
                         db_a.assert_dispatchable(first.mission_id,
                                                  authorization_digest=fake_a.digest,
                                                  lease_id=first_lease.lease_id,
                                                  actor="x").lease_id)

    def test_revocation_before_reservation_blocks_consumption(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        self.db.revoke_authorization(statement={}, signature=self.verifier.signature,
                                     actor="human")
        with self.assertRaises(ledger.AuthorizationRevoked):
            reserve(self.db, self.snapshot, self.verifier)
        self.assertIsNone(self.db.get_authorization(
            self.snapshot.mission_id).consumed_at)
        self.assertEqual(ledger.AUTHORIZED, self.db.get(self.snapshot.mission_id).state)

    def test_assert_dispatchable_rejects_expired_authorization(self):
        expiring = FakeVerifier(label="window", expires_at=NOW + timedelta(seconds=5))
        clock = Clock()
        db = ledger.Ledger(os.path.join(self._tmp.name, "window.db"),
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=expiring, clock=clock)
        db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db.authorize_signed(self.snapshot.mission_id, envelope={},
                            signature=expiring.signature, payload=self.snapshot.payload,
                            actor="human")
        lease = reserve(db, self.snapshot, expiring)
        clock.now = NOW + timedelta(seconds=10)
        with self.assertRaises(ledger.AuthorizationExpired):
            db.assert_dispatchable(self.snapshot.mission_id,
                                   authorization_digest=expiring.digest,
                                   lease_id=lease.lease_id, actor="exec")
        self.assertEqual(ledger.DISPATCHING, db.get(self.snapshot.mission_id).state)
        self.assertTrue(db.is_reserved(self.snapshot.mission_id))

    def test_unknown_terminal_reconciliation_with_receipt(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        self.db.recover_stale_dispatching(cause="process_loss")
        self.db.record_dispatch_evidence(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, receipt=receipt(self.snapshot),
            evidence=evidence(self.snapshot), actor="exec")
        report_value = report(self.snapshot, receipt(self.snapshot), clock=self.clock)
        record = self.db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report_value, evidence=evidence(self.snapshot),
            attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.COMPLETED, record.state)
        self.assertFalse(self.db.is_reserved(self.snapshot.mission_id))
        with self.assertRaises((ledger.InvalidTransition, ledger.DuplicateReservation)):
            reserve(self.db, self.snapshot, self.verifier)

    def test_receipt_database_tampering_rejected_at_reconciliation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        conn = sqlite3.connect(self.db.db_path)
        conn.execute("UPDATE missions SET receipt_json=? WHERE mission_id=?",
                     (json.dumps(dict(receipt_value, observed_message_id="tampered")),
                      self.snapshot.mission_id))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report(self.snapshot, receipt_value, clock=self.clock),
                evidence=evidence(self.snapshot), attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.DISPATCHING, self.db.get(self.snapshot.mission_id).state)

    def test_environment_and_signed_api_boundaries(self):
        with self.assertRaises(AuthorityContractError):
            make_snapshot(environment="APPROVED").validate()
        legacy = ledger.Ledger(os.path.join(self._tmp.name, "legacy2.db"),
                               project_id=PROJECT, repository_id=REPO,
                               clock=self.clock)
        legacy.register_proposed(
            mission_id="M-LEGACY", operation_id="O-LEGACY", coordinator_id="c",
            agent="A", session_id="ses-legacy", instance_id="i",
            worktree="/tmp/w", authorization_reference=AUTH, payload={"x": 1})
        with self.assertRaises(ledger.SignedAuthorizationRequired):
            legacy.reserve_dispatch_signed(
                "M-LEGACY", authorization_digest="d" * 64, agent="A",
                session_id="ses-legacy", instance_id="i", worktree="/tmp/w",
                actor="x")
        no_verifier = ledger.Ledger(os.path.join(self._tmp.name, "noverifier.db"),
                                    project_id=PROJECT, repository_id=REPO,
                                    clock=self.clock)
        no_verifier.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        with self.assertRaises(ledger.AuthorizationMissing):
            no_verifier.authorize_signed(self.snapshot.mission_id, envelope={},
                                         signature=b"x", payload=self.snapshot.payload,
                                         actor="x")

    def test_evidence_is_idempotent_and_wrong_lease_blocks_reconcile(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        value = receipt(self.snapshot)
        self.db.record_dispatch_evidence(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, receipt=value,
            evidence=evidence(self.snapshot), actor="exec")
        count = len(self.db.get_evidence(self.snapshot.mission_id))
        self.db.record_dispatch_evidence(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, receipt=value,
            evidence=evidence(self.snapshot), actor="exec")
        self.assertEqual(count, len(self.db.get_evidence(self.snapshot.mission_id)))
        with self.assertRaises(ledger.DispatchBlocked):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id="wrong", target_state=ledger.COMPLETED,
                report=report(self.snapshot, value, clock=self.clock),
                evidence=evidence(self.snapshot), attestation={"authority": "maintainer"}, attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.DISPATCHING, self.db.get(self.snapshot.mission_id).state)
        self.assertTrue(self.db.is_reserved(self.snapshot.mission_id))

    def test_legacy_reconcile_rejected_for_signed_unknown(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        reserve(self.db, self.snapshot, self.verifier)
        self.db.recover_stale_dispatching(cause="process_loss")
        with self.assertRaises(ledger.SignedTransitionRequired):
            self.db.reconcile(
                self.snapshot.mission_id, target=ledger.FAILED,
                evidence="legacy", authority_reference="QA", actor="qa")

    def test_revalidation_rejects_snapshot_mutation_at_assert_and_reconcile(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        self._mutate_snapshot(model="opencode/premium", scope="OTHER",
                              acceptance_criteria=("outro",))
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.assert_dispatchable(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, actor="exec")
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report(self.snapshot, receipt_value, clock=self.clock),
                evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.DISPATCHING, self.db.get(self.snapshot.mission_id).state)

    def test_authority_material_binds_criteria_and_payload(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        tampered = dataclasses.replace(
            self.snapshot, acceptance_criteria=("critério-adulterado",))
        raw = canonical_json(tampered.canonical_material())
        conn = sqlite3.connect(self.db.db_path)
        conn.execute(
            """UPDATE missions SET mission_snapshot_json=?,
               mission_snapshot_sha256=?, payload_sha256=?
               WHERE mission_id=?""",
            (raw, tampered.sha256(), tampered.payload_sha256,
             self.snapshot.mission_id),
        )
        # Atualiza a âncora de snapshot da autorização, mas não o material de autoridade.
        conn.execute(
            "UPDATE authorizations SET mission_snapshot_sha256=? WHERE mission_id=?",
            (tampered.sha256(), self.snapshot.mission_id))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.assert_dispatchable(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, actor="exec")

    def test_revalidation_rejects_signature_and_expiry_db_tamper(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        conn = sqlite3.connect(self.db.db_path)
        conn.execute(
            "UPDATE authorizations SET signature=?, signature_sha256=? WHERE mission_id=?",
            ("00", hashlib.sha256(b"\x00").hexdigest(), self.snapshot.mission_id))
        conn.commit()
        conn.close()
        with self.assertRaises((ledger.LedgerError, AuthorityContractError)):
            self.db.assert_dispatchable(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, actor="exec")
        with self.assertRaises((ledger.LedgerError, AuthorityContractError)):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.FAILED,
                evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")

    def test_expiry_database_mutation_rejected_by_revalidation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease = reserve(self.db, self.snapshot, self.verifier)
        conn = sqlite3.connect(self.db.db_path)
        conn.execute(
            "UPDATE authorizations SET expires_at='2030-01-01T00:00:00Z' WHERE mission_id=?",
            (self.snapshot.mission_id,))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.assert_dispatchable(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, actor="exec")
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.FAILED,
                evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")

    def test_attestation_is_required_and_self_declared_fields_do_not_authorize(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        report_value = report(self.snapshot, receipt_value, clock=self.clock)
        with self.assertRaises(TypeError):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report_value, evidence=evidence(self.snapshot), actor="qa")
        bad = FakeReconciliationVerifier(mutate={"outcome": "FAILED"})
        db = ledger.Ledger(self.db.db_path, project_id=PROJECT,
                           repository_id=REPO, authority_verifier=self.verifier,
                           reconciliation_verifier=bad, clock=self.clock)
        with self.assertRaises(ledger.EvidenceDivergence):
            db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report_value, evidence=evidence(self.snapshot),
                attestation={"authority": "fabricated"},
                attestation_signature=b"fabricated", actor="qa")
        self.assertEqual(ledger.DISPATCHING, self.db.get(self.snapshot.mission_id).state)

    def test_report_requires_payload_and_post_dispatch_completion(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        base = report(self.snapshot, receipt_value, clock=self.clock)
        missing = dict(base); missing.pop("payload_sha256")
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=missing, evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")
        before_dispatch = dict(
            base, completed_at=ledger.iso(
                self.clock.now - timedelta(seconds=1)))
        with self.assertRaises(ledger.UnsafeReconciliation):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=before_dispatch, evidence=evidence(self.snapshot),
                attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")

    def test_real_evidence_rejected_for_fixture_reconciliation(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        real = {"source": "real", "environment": "FIXTURE",
                "service_contacted": True, "transport_real": True}
        with self.assertRaises(ledger.DispatchBlocked):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report(self.snapshot, receipt_value, clock=self.clock),
                evidence=real, attestation={"authority": "maintainer"},
                attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.DISPATCHING, self.db.get(self.snapshot.mission_id).state)

    def test_revocation_nonce_is_unique(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        first = FakeVerifier(label="rev1", revocation_nonce="same-nonce")
        second = FakeVerifier(label="rev2", revocation_nonce="same-nonce",
                              revocation_digest="e" * 64)
        db = ledger.Ledger(self.db.db_path, project_id=PROJECT, repository_id=REPO,
                           authority_verifier=first, clock=self.clock)
        db.revoke_authorization(statement={}, signature=first.signature, actor="x")
        db2 = ledger.Ledger(self.db.db_path, project_id=PROJECT, repository_id=REPO,
                            authority_verifier=second, clock=self.clock)
        with self.assertRaises(ledger.EvidenceDivergence):
            db2.revoke_authorization(statement={}, signature=second.signature, actor="x")

    def test_legacy_default_disabled_and_explicit_allow(self):
        blocked = ledger.Ledger(os.path.join(self._tmp.name, "legacy-default.db"),
                                project_id=PROJECT, repository_id=REPO, clock=self.clock)
        record = blocked.register_proposed(
            mission_id="M-DEFAULT", operation_id="O-DEFAULT", coordinator_id="c",
            agent="A", session_id="ses-default", instance_id="i", worktree="/tmp/w",
            authorization_reference="AUTH", payload={"x": 1})
        self.assertFalse(blocked.can_dispatch(record.mission_id))
        with self.assertRaises(ledger.LegacyDispatchDisabled):
            blocked.authorize(record.mission_id, authorization_reference="AUTH",
                              payload_sha256_value=record.payload_sha256, actor="x")
        allowed = ledger.Ledger(os.path.join(self._tmp.name, "legacy-allowed.db"),
                                project_id=PROJECT, repository_id=REPO,
                                policy={"allow_legacy_dispatch": True}, clock=self.clock)
        record = allowed.register_proposed(
            mission_id="M-ALLOW", operation_id="O-ALLOW", coordinator_id="c",
            agent="A", session_id="ses-allow", instance_id="i", worktree="/tmp/w",
            authorization_reference="AUTH", payload={"x": 1})
        allowed.authorize(record.mission_id, authorization_reference="AUTH",
                          payload_sha256_value=record.payload_sha256, actor="x")
        self.assertTrue(allowed.can_dispatch(record.mission_id))

    def test_terminate_expired_or_revoked_before_dispatch(self):
        expiring = FakeVerifier(label="terminate", expires_at=NOW + timedelta(seconds=5))
        clock = Clock()
        db = ledger.Ledger(os.path.join(self._tmp.name, "terminate.db"),
                           project_id=PROJECT, repository_id=REPO,
                           authority_verifier=expiring, clock=clock)
        db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db.authorize_signed(self.snapshot.mission_id, envelope={},
                            signature=expiring.signature, payload=self.snapshot.payload,
                            actor="human")
        clock.now = NOW + timedelta(seconds=10)
        failed = db.terminate_signed_before_dispatch(
            self.snapshot.mission_id, authorization_digest=expiring.digest,
            lease_id=None, evidence={**evidence(self.snapshot), "reason": "expired"},
            actor="ops")
        self.assertEqual(ledger.FAILED, failed.state)
        self.assertIsNone(db.get_authorization(self.snapshot.mission_id).consumed_at)
        self.assertFalse(db.is_reserved(self.snapshot.mission_id))
        # Revogação após reserva, antes do POST: terminalização sem reenvio.
        clock2 = Clock()
        fake = FakeVerifier(label="revoked")
        db2 = ledger.Ledger(os.path.join(self._tmp.name, "terminate-rev.db"),
                            project_id=PROJECT, repository_id=REPO,
                            authority_verifier=fake, clock=clock2)
        db2.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db2.authorize_signed(self.snapshot.mission_id, envelope={},
                             signature=fake.signature, payload=self.snapshot.payload,
                             actor="human")
        lease = reserve(db2, self.snapshot, fake)
        db2.revoke_authorization(statement={}, signature=fake.signature, actor="human")
        failed = db2.terminate_signed_before_dispatch(
            self.snapshot.mission_id, authorization_digest=fake.digest,
            lease_id=lease.lease_id,
            evidence={**evidence(self.snapshot), "reason": "revoked"}, actor="ops")
        self.assertEqual(ledger.FAILED, failed.state)
        self.assertFalse(db2.is_reserved(self.snapshot.mission_id))
        self.assertIsNotNone(db2.get_authorization(self.snapshot.mission_id).consumed_at)

    def test_reconcile_allowed_after_expiry_if_dispatch_was_before(self):
        expiring = FakeVerifier(label="prior", expires_at=NOW + timedelta(seconds=5))
        clock = Clock()
        db = ledger.Ledger(
            os.path.join(self._tmp.name, "prior.db"), project_id=PROJECT,
            repository_id=REPO, authority_verifier=expiring,
            reconciliation_verifier=FakeReconciliationVerifier(), clock=clock)
        db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        db.authorize_signed(self.snapshot.mission_id, envelope={},
                            signature=expiring.signature, payload=self.snapshot.payload,
                            actor="human")
        lease, receipt_value = record_evidence(db, self.snapshot, expiring)
        clock.now = NOW + timedelta(seconds=10)
        # O receipt/despacho ocorreu antes da expiração; a reconciliação é permitida.
        record = db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=expiring.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report(self.snapshot, receipt_value, clock=Clock(NOW)),
            evidence=evidence(self.snapshot),
            attestation={"authority": "maintainer"},
            attestation_signature=b"attestation", actor="qa")
        self.assertEqual(ledger.COMPLETED, record.state)

    def test_post_terminal_evidence_and_reconciliation_tamper_rejected(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        report_value = report(self.snapshot, receipt_value, clock=self.clock)
        attestation = {"authority": "maintainer"}
        self.db.reconcile_signed(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, target_state=ledger.COMPLETED,
            report=report_value, evidence=evidence(self.snapshot),
            attestation=attestation, attestation_signature=b"attestation", actor="qa")
        same = self.db.record_dispatch_evidence(
            self.snapshot.mission_id, authorization_digest=self.verifier.digest,
            lease_id=lease.lease_id, receipt=receipt_value,
            evidence=evidence(self.snapshot), actor="exec")
        self.assertEqual(ledger.COMPLETED, same.state)
        with self.assertRaises(ledger.EvidenceDivergence):
            self.db.record_dispatch_evidence(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id,
                receipt=dict(receipt_value, observed_message_id="other"),
                evidence=evidence(self.snapshot), actor="exec")
        conn = sqlite3.connect(self.db.db_path)
        conn.execute("UPDATE missions SET reconciliation_json='{}' WHERE mission_id=?",
                     (self.snapshot.mission_id,))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            self.db.reconcile_signed(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report_value, evidence=evidence(self.snapshot),
                attestation=attestation, attestation_signature=b"attestation", actor="qa")
        with self.assertRaises(ledger.LedgerStorageError):
            self.db.record_dispatch_evidence(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, receipt=receipt_value,
                evidence=evidence(self.snapshot), actor="exec")

    def test_evidence_hash_and_post_terminal_tamper_rejected(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        lease, receipt_value = record_evidence(self.db, self.snapshot, self.verifier)
        conn = sqlite3.connect(self.db.db_path)
        conn.execute("UPDATE missions SET dispatch_evidence_json='{}' WHERE mission_id=?",
                     (self.snapshot.mission_id,))
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            self.db.assert_dispatchable(
                self.snapshot.mission_id, authorization_digest=self.verifier.digest,
                lease_id=lease.lease_id, actor="exec")

    def test_multiprocess_reservation_has_one_winner(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        ctx = multiprocessing.get_context("fork")
        barrier = ctx.Barrier(2)
        queue = ctx.Queue()
        processes = [
            ctx.Process(target=_reserve_worker,
                        args=(str(self.db.db_path), self.snapshot.mission_id,
                              self.verifier.digest, barrier, queue))
            for _ in range(2)
        ]
        for process in processes:
            process.start()
        for process in processes:
            process.join(timeout=10)
        results = [queue.get(timeout=5) for _ in processes]
        self.assertEqual(1, sum(1 for result in results if result[0] == "ok"))
        self.assertEqual(1, sum(1 for result in results if result[0] != "ok"))
        self.assertEqual(1, len(self.db.get_evidence(self.snapshot.mission_id)))
        self.assertEqual(1, self.db.get(self.snapshot.mission_id).transmissions_used)
        self.assertIsNotNone(self.db.get_authorization(
            self.snapshot.mission_id).consumed_at)

    def test_restart_after_reservation_recovery_without_resend(self):
        self.db.register_signed_proposed(self.snapshot, authorization_reference=AUTH)
        self.db.authorize_signed(self.snapshot.mission_id, envelope={},
                                 signature=self.verifier.signature,
                                 payload=self.snapshot.payload, actor="human")
        ctx = multiprocessing.get_context("fork")
        process = ctx.Process(target=_reserve_then_exit,
                              args=(str(self.db.db_path), self.snapshot.mission_id,
                                    self.verifier.digest))
        process.start()
        process.join(timeout=10)
        self.assertFalse(process.is_alive(), "child still alive")
        self.assertEqual(0, process.exitcode, "child failed")
        reopened = ledger.Ledger(self.db.db_path, project_id=PROJECT,
                                 repository_id=REPO, clock=self.clock)
        self.assertEqual(ledger.DISPATCHING, reopened.get(self.snapshot.mission_id).state)
        self.assertTrue(reopened.is_reserved(self.snapshot.mission_id))
        self.assertIsNotNone(reopened.get_authorization(
            self.snapshot.mission_id).consumed_at)
        self.assertEqual([self.snapshot.mission_id],
                         reopened.recover_stale_dispatching(cause="process_loss"))
        self.assertEqual(ledger.UNKNOWN, reopened.get(self.snapshot.mission_id).state)
        self.assertTrue(reopened.is_reserved(self.snapshot.mission_id))
        with self.assertRaises(ledger.DispatchBlocked):
            reopened.assert_dispatchable(self.snapshot.mission_id,
                                         authorization_digest=self.verifier.digest,
                                         lease_id="x", actor="x")


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "v1.db")
        _make_v1(self.path)

    def tearDown(self):
        self._tmp.cleanup()

    def test_migration_preserves_records_and_versions(self):
        db = ledger.Ledger(self.path, project_id=PROJECT, repository_id=REPO,
                           allow_migration=True, clock=Clock())
        old = db.get("M-OLD")
        self.assertEqual("UNKNOWN", old.state)
        self.assertEqual("LEGACY_UNVERIFIED", old.authorization_mode)
        self.assertEqual("id-old", old.identity_hash)
        self.assertEqual("a" * 64, old.payload_sha256)
        self.assertEqual(1, old.transmissions_used)
        self.assertEqual(0, db.get("M-NEW").transmissions_used)
        self.assertTrue(db.is_reserved("M-OLD"))
        self.assertEqual(3, len(db.history("M-OLD")))
        conn = sqlite3.connect(self.path)
        version = conn.execute(
            "SELECT value FROM meta WHERE key='schema_version'").fetchone()[0]
        migrated = conn.execute(
            "SELECT version, source_version FROM schema_migrations").fetchall()
        names = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        conn.close()
        self.assertEqual("coord-ledger-1.1", version)
        self.assertEqual([("coord-ledger-1.1", "coord-ledger-1.0")], migrated)
        self.assertTrue({"authorizations", "authorization_revocations",
                         "mission_evidence", "schema_migrations"} <= names)
        reopened = ledger.Ledger(self.path, project_id=PROJECT, repository_id=REPO,
                                 clock=Clock())
        self.assertEqual("UNKNOWN", reopened.get("M-OLD").state)
        conn = sqlite3.connect(self.path)
        self.assertEqual(1, conn.execute(
            "SELECT COUNT(*) FROM schema_migrations").fetchone()[0])
        conn.close()

    def test_migration_requires_opt_in(self):
        with self.assertRaises(ledger.SchemaMigrationRequired):
            ledger.Ledger(self.path, project_id=PROJECT, repository_id=REPO)
        conn = sqlite3.connect(self.path)
        self.assertEqual("coord-ledger-1.0", conn.execute(
            "SELECT value FROM meta WHERE key='schema_version'").fetchone()[0])
        self.assertEqual("UNKNOWN", conn.execute(
            "SELECT state FROM missions WHERE mission_id='M-OLD'").fetchone()[0])
        conn.close()

    def test_migration_rollback_on_partial_ddl(self):
        def fail(conn, *, now):
            conn.execute("ALTER TABLE missions ADD COLUMN rollback_probe TEXT")
            raise ledger.LedgerError("falha injetada")

        with patch.object(ledger, "_migrate_1_0_to_1_1", fail):
            with self.assertRaises(ledger.LedgerError):
                ledger.Ledger(self.path, project_id=PROJECT, repository_id=REPO,
                              allow_migration=True, clock=Clock())
        conn = sqlite3.connect(self.path)
        self.assertEqual("coord-ledger-1.0", conn.execute(
            "SELECT value FROM meta WHERE key='schema_version'").fetchone()[0])
        columns = {row[1] for row in conn.execute("PRAGMA table_info(missions)")}
        self.assertNotIn("rollback_probe", columns)
        self.assertEqual("UNKNOWN", conn.execute(
            "SELECT state FROM missions WHERE mission_id='M-OLD'").fetchone()[0])
        conn.close()

    def test_incompatible_auxiliary_table_fails_closed(self):
        path = os.path.join(self._tmp.name, "bad-aux.db")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        conn.execute("INSERT INTO meta VALUES ('schema_version','coord-ledger-1.1')")
        conn.execute("CREATE TABLE authorizations (authorization_digest TEXT PRIMARY KEY)")
        conn.commit()
        conn.close()
        os.chmod(path, 0o600)
        with self.assertRaises(ledger.LedgerStorageError):
            ledger.Ledger(path, project_id=PROJECT, repository_id=REPO, clock=Clock())

    def test_concurrent_migration_is_safe(self):
        path = os.path.join(self._tmp.name, "concurrent-migration.db")
        _make_v1(path)
        ctx = multiprocessing.get_context("fork")
        barrier = ctx.Barrier(2)
        queue = ctx.Queue()
        processes = [
            ctx.Process(target=_migration_worker, args=(path, barrier, queue))
            for _ in range(2)
        ]
        for process in processes:
            process.start()
        for process in processes:
            process.join(timeout=15)
        results = [queue.get(timeout=5) for _ in processes]
        self.assertEqual(["ok", "ok"], sorted(results))
        db = ledger.Ledger(path, project_id=PROJECT, repository_id=REPO, clock=Clock())
        self.assertEqual("UNKNOWN", db.get("M-OLD").state)

    def test_unknown_version_rejected_without_change(self):
        conn = sqlite3.connect(self.path)
        conn.execute("UPDATE meta SET value='coord-ledger-9.9' WHERE key='schema_version'")
        conn.commit()
        conn.close()
        with self.assertRaises(ledger.LedgerStorageError):
            ledger.Ledger(self.path, project_id=PROJECT, repository_id=REPO,
                          allow_migration=True)
        conn = sqlite3.connect(self.path)
        names = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertNotIn("authorizations", names)
        self.assertEqual("coord-ledger-9.9", conn.execute(
            "SELECT value FROM meta WHERE key='schema_version'").fetchone()[0])
        conn.close()

    def test_legacy_not_dispatchable_under_signed_policy(self):
        db = ledger.Ledger(os.path.join(self._tmp.name, "policy.db"),
                           project_id=PROJECT, repository_id=REPO,
                           policy={"require_signed_authorization": True},
                           clock=Clock())
        record = db.register_proposed(
            mission_id="M-LEGACY", operation_id="O-LEGACY",
            coordinator_id="coord", agent="A", session_id="ses-legacy",
            instance_id="inst", worktree="/tmp/wt", authorization_reference="AUTH",
            payload={"x": 1})
        self.assertFalse(db.can_dispatch(record.mission_id))
        with self.assertRaises((ledger.SignedAuthorizationRequired,
                                ledger.LegacyDispatchDisabled)):
            db.authorize(record.mission_id, authorization_reference="AUTH",
                         payload_sha256_value=record.payload_sha256, actor="x")
        with self.assertRaises((ledger.SignedAuthorizationRequired,
                                ledger.LegacyDispatchDisabled)):
            db.reserve_dispatch(record.mission_id, agent="A", session_id="ses-legacy",
                                instance_id="inst", worktree="/tmp/wt", actor="x")


def _make_v1(path: str) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE missions (
            mission_id TEXT PRIMARY KEY, operation_id TEXT NOT NULL UNIQUE,
            project_id TEXT NOT NULL, repository_id TEXT NOT NULL,
            coordinator_id TEXT NOT NULL, agent TEXT NOT NULL,
            session_id TEXT NOT NULL, instance_id TEXT NOT NULL, worktree TEXT NOT NULL,
            authorization_reference TEXT NOT NULL, payload_sha256 TEXT NOT NULL,
            identity_hash TEXT NOT NULL, state TEXT NOT NULL,
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            dispatched_at TEXT, timeout_seconds INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE transitions (
            seq INTEGER PRIMARY KEY AUTOINCREMENT, mission_id TEXT NOT NULL,
            from_state TEXT, to_state TEXT NOT NULL, evidence TEXT NOT NULL,
            actor TEXT NOT NULL, at TEXT NOT NULL
        );
        CREATE TABLE reservations (
            project_id TEXT NOT NULL, agent TEXT NOT NULL, session_id TEXT NOT NULL,
            mission_id TEXT NOT NULL,
            PRIMARY KEY (project_id, agent, session_id),
            UNIQUE (project_id, agent), UNIQUE (project_id, session_id)
        );
        """
    )
    now = ledger.iso(NOW)
    old = ledger.iso(NOW - timedelta(minutes=1))
    conn.execute("INSERT INTO meta VALUES ('schema_version','coord-ledger-1.0')")
    conn.execute(
        "INSERT INTO missions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ("M-OLD", "O-OLD", PROJECT, REPO, "coord", "B", "ses-B", "i", "/tmp/w",
         "AUTH", "a" * 64, "id-old", "UNKNOWN", now, now, old, 0))
    conn.execute(
        "INSERT INTO missions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ("M-NEW", "O-NEW", PROJECT, REPO, "coord", "C", "ses-C", "i", "/tmp/w2",
         "AUTH2", "b" * 64, "id-new", "AUTHORIZED", now, now, None, 0))
    conn.execute(
        "INSERT INTO transitions VALUES (NULL,?,?,?,?,?,?)",
        ("M-OLD", None, "PROPOSED", "registro inicial", "coord", now))
    conn.execute(
        "INSERT INTO transitions VALUES (NULL,?,?,?,?,?,?)",
        ("M-OLD", "PROPOSED", "AUTHORIZED", "autorização AUTH", "human", now))
    conn.execute(
        "INSERT INTO transitions VALUES (NULL,?,?,?,?,?,?)",
        ("M-OLD", "AUTHORIZED", "DISPATCHING", "reserva atômica", "coord", now))
    conn.execute(
        "INSERT INTO reservations VALUES (?,?,?,?)",
        (PROJECT, "B", "ses-B", "M-OLD"))
    conn.commit()
    conn.close()
    os.chmod(path, 0o600)


def _migration_worker(db_path, barrier, queue):
    barrier.wait()
    try:
        ledger.Ledger(db_path, project_id=PROJECT, repository_id=REPO,
                      allow_migration=True, clock=Clock())
        queue.put("ok")
    except Exception as exc:  # noqa: BLE001
        queue.put(type(exc).__name__ + ":" + str(exc))


def _reserve_worker(db_path, mission_id, digest, barrier, queue):
    db = ledger.Ledger(
        db_path, project_id=PROJECT, repository_id=REPO,
        authority_verifier=FakeVerifier(label="auth"), clock=Clock())
    barrier.wait()
    try:
        lease = db.reserve_dispatch_signed(
            mission_id, authorization_digest=digest,
            agent="A", session_id="ses-l11", instance_id="inst-1",
            worktree="/tmp/wt-l11", actor="race")
        queue.put(("ok", lease.lease_id))
    except Exception as exc:  # noqa: BLE001
        queue.put((type(exc).__name__, str(exc)))


def _reserve_then_exit(db_path, mission_id, digest):
    db = ledger.Ledger(
        db_path, project_id=PROJECT, repository_id=REPO,
        authority_verifier=FakeVerifier(label="auth"), clock=Clock())
    db.reserve_dispatch_signed(
        mission_id, authorization_digest=digest,
        agent="A", session_id="ses-l11", instance_id="inst-1",
        worktree="/tmp/wt-l11", actor="crash")
    os._exit(0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
