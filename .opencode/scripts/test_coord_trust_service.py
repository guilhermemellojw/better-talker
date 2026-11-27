#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P1 — testes host-only do serviço local protegido.

Chaves, ledger, trust store, consumidores e transporte são sintéticos. Não há
TCP, POST, LLM, credencial, usuário real, sudo, instalação ou migração.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import socket
import shutil
import sqlite3
import struct
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from datetime import datetime, timedelta, timezone

import coord_authority as ca
import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger as ledger
import coord_ledger_authority as cla
import coord_p14b as p14b
import coord_p14b_authority as p14ba
import coord_trust_service as svc
import coord_trust_service_cli as cli

NOW = datetime(2026, 11, 27, 14, 0, tzinfo=timezone.utc)
HEAD = "a" * 40
PROJECT = "better-talker"
REPO = "guilhermemellojw/better-talker"
MISSION_ID = "MISS-TRUST-001"
OPERATION_ID = "OP-TRUST-001"
APPROVAL = "APP-TRUST-001"
PAYLOAD = {"schema": "BT-COORD-PAYLOAD-V1", "message": "trust-boundary", "n": 7}
CRITERIA = ("criterion-a", "criterion-b")
SCOPE = "TRUST-BOUNDARY"
MODEL = "opencode/hy4-flash-free"
DIGESTS = {"coord_trust_service.py": "d" * 64, "coord_ledger.py": "e" * 64}
ALL_ACTIONS = frozenset({
    "propose", "authorize", "reserve", "assert", "record_evidence",
    "reconcile", "revoke", "interrupt", "status", "recover",
})


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _digest(value) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"),
                   ensure_ascii=False).encode("utf-8")).hexdigest()


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def make_mission(**overrides) -> cc.MissionEnvelope:
    values = {
        "schema_version": cc.MISSION_SCHEMA_VERSION,
        "project_id": PROJECT, "repository_id": REPO,
        "mission_id": MISSION_ID, "operation_id": OPERATION_ID,
        "coordinator_id": "coordinator-a",
        "target_agent": "agent-a", "target_session_id": "ses-trust",
        "target_instance_id": "inst-1", "target_location": "/tmp/wt-trust",
        "objective": "fundação da fronteira de confiança",
        "payload": PAYLOAD, "payload_sha256": cc.payload_sha256(PAYLOAD),
        "expected_deliverables": ("deliverable-a",),
        "acceptance_criteria": CRITERIA,
        "ownership_constraints": ("ownership-a",),
        "authorization_reference": APPROVAL,
        "created_at": NOW - timedelta(hours=1),
        "expires_at": NOW + timedelta(hours=1),
        "timeout": 3600, "scope": SCOPE, "expected_head_sha": HEAD,
        "expected_branch": "feat/coord-trust-boundary-service",
    }
    values.update(overrides)
    return cc.MissionEnvelope(**values)


def make_envelope(mission: cc.MissionEnvelope, signer: p14b.SyntheticSigner,
                  allowlist: cas.ApproverAllowlist, **overrides
                  ) -> p14ba.SignedAuthorizationV2:
    values = {
        "schema": p14ba.AUTHORIZATION_SCHEMA,
        "authorization_id": "AUTH-TRUST-001",
        "approval_reference": mission.authorization_reference,
        "approver_id": signer.approver_id,
        "approver_key_fingerprint": signer.fingerprint,
        "trust_policy_sha256": allowlist.digest(),
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "project_id": mission.project_id,
        "repository_id": mission.repository_id,
        "coordinator_id": mission.coordinator_id,
        "mission_identity": cc.canonical_mission_identity(mission),
        "payload": dict(mission.payload),
        "payload_sha256": mission.payload_sha256,
        "target": ca.Recipient(
            mission.target_agent, mission.target_session_id,
            mission.target_location, mission.target_instance_id),
        "model": MODEL, "scope": mission.scope,
        "acceptance_criteria": tuple(mission.acceptance_criteria),
        "environment": "SHADOW",
        "reviewed_head_sha": mission.expected_head_sha,
        "operational_digests": dict(DIGESTS),
        "max_transmissions": 1, "cost_ceiling_usd": "0",
        "free_only": True,
        "issued_at": NOW - timedelta(minutes=5),
        "expires_at": NOW + timedelta(minutes=15),
        "nonce": "nonce-trust-001", "mode": "SHADOW",
    }
    values.update(overrides)
    return p14ba.SignedAuthorizationV2(**values)


def current_peer() -> svc.PeerIdentity:
    return svc.PeerIdentity(
        pid=os.getpid(), uid=os.geteuid(), gid=os.getegid())


class Fixture:
    def __init__(self, root: str, *, observer: bool = False,
                 wrong_peer: bool = False, real_transport: bool = False,
                 allow_same_uid: bool = True,
                 request_timeout_seconds: float = 15.0) -> None:
        self.root = root
        self.state = os.path.join(root, "state")
        os.makedirs(self.state, mode=0o700)
        self.keys = os.path.join(root, "keys")
        self.signer = p14b.SyntheticSigner(self.keys, persist=True)
        self.allowlist = cas.ApproverAllowlist(
            (self.signer.approver_key(NOW),))
        self.trust_path = os.path.join(self.state, "trust-store.json")
        svc.write_trust_store(self.trust_path, self.allowlist)
        self.consumer_path = os.path.join(self.state, "consumers.json")
        peer = current_peer()
        executor_uid = peer.uid + 1000 if wrong_peer else peer.uid
        executor = svc.ConsumerPolicy(
            consumer_id="executor", uid=executor_uid,
            allowed_actions=ALL_ACTIONS, agent_id="agent-a", role="executor")
        consumers = [executor]
        if observer:
            consumers.append(svc.ConsumerPolicy(
                consumer_id="observer", uid=peer.uid + 1000,
                allowed_actions=frozenset({"status"}),
                agent_id=None, role="observer"))
        self.registry = svc.ConsumerRegistry(
            tuple(consumers), allow_same_uid=allow_same_uid)
        svc.write_consumer_registry(self.consumer_path, self.registry)
        self.config = svc.ServiceConfig(
            state_dir=self.state, project_id=PROJECT,
            repository_id=REPO, service_uid=os.geteuid(),
            service_gid=os.getegid(), trust_store_path=self.trust_path,
            consumer_registry_path=self.consumer_path,
            socket_path=os.path.join(self.state, "service.sock"),
            allow_same_uid=allow_same_uid, test_mode=allow_same_uid,
            real_transport=real_transport,
            request_timeout_seconds=request_timeout_seconds)
        self.service = svc.build_service(self.config, clock=lambda: NOW)
        self.peer = peer
        self.observer_peer = svc.PeerIdentity(
            pid=peer.pid, uid=peer.uid + 1000, gid=peer.gid)
        self.mission = make_mission()
        self.envelope = make_envelope(
            self.mission, self.signer, self.allowlist)
        self.digest = p14ba.authorization_digest(self.envelope)

    def request(self, action: str, payload: dict,
                *, peer: svc.PeerIdentity | None = None,
                request_id: str | None = None) -> dict:
        return self.service.handle(
            peer or self.peer,
            {"request_id": request_id or "req-" + os.urandom(8).hex(),
             "action": action, "payload": payload})

    def propose(self, *, peer=None) -> dict:
        return self.request("propose", {
            "mission": svc._mission_mapping(self.mission),
            "model": MODEL, "environment": "SHADOW",
            "operational_digests": dict(DIGESTS),
            "max_transmissions": 1, "cost_ceiling_usd": "0",
            "free_only": True, "authorization_reference": APPROVAL,
        }, peer=peer)

    def authorize(self, *, signature: bytes | None = None,
                  request_id: str | None = None) -> dict:
        if signature is None:
            signature = self.signer.sign(
                p14ba.canonical_authorization_bytes(self.envelope),
                namespace=p14ba.AUTHORIZATION_NAMESPACE, directory=self.keys)
        return self.request("authorize", {
            "mission_id": MISSION_ID,
            "envelope": self.envelope.to_mapping(),
            "signature": _b64(signature),
        }, request_id=request_id)

    def reserve(self, *, request_id: str | None = None) -> dict:
        return self.request("reserve", {
            "mission_id": MISSION_ID, "authorization_digest": self.digest,
            "agent": "agent-a", "session_id": "ses-trust",
            "instance_id": "inst-1", "worktree": "/tmp/wt-trust",
        }, request_id=request_id)

    def assert_dispatch(self, lease_id: str, *, digest: str | None = None,
                        request_id: str | None = None) -> dict:
        return self.request("assert", {
            "mission_id": MISSION_ID,
            "authorization_digest": digest or self.digest,
            "lease_id": lease_id,
        }, request_id=request_id)

    def evidence(self) -> tuple[dict, dict]:
        receipt = {
            "source": "synthetic", "environment": "SHADOW",
            "observed_message_id": "msg-1",
            "operation_id": OPERATION_ID,
            "payload_sha256": self.mission.payload_sha256,
            "agent_id": "agent-a", "session_id": "ses-trust",
            "instance_id": "inst-1", "location": "/tmp/wt-trust",
        }
        evidence = {
            "source": "synthetic", "environment": "SHADOW",
            "service_contacted": False, "transport_real": False,
        }
        return receipt, evidence

    def report(self) -> dict:
        return {
            "project_id": PROJECT, "repository_id": REPO,
            "mission_id": MISSION_ID, "operation_id": OPERATION_ID,
            "agent_id": "agent-a", "session_id": "ses-trust",
            "message_id": "report-1", "reply_to": "msg-1",
            "status": "COMPLETED", "blockers": [],
            "acceptance_results": {c: True for c in CRITERIA},
            "payload_sha256": self.mission.payload_sha256,
            "completed_at": _iso(NOW),
        }

    def record(self, lease_id: str) -> dict:
        receipt, evidence = self.evidence()
        return self.request("record_evidence", {
            "mission_id": MISSION_ID, "authorization_digest": self.digest,
            "lease_id": lease_id, "receipt": receipt, "evidence": evidence,
        })

    def attestation(self, *, signer: p14b.SyntheticSigner | None = None,
                    report: dict | None = None,
                    evidence: dict | None = None) -> tuple[dict, bytes]:
        receipt, default_evidence = self.evidence()
        report = report or self.report()
        evidence = evidence or default_evidence
        attestation = p14ba.SignedReconciliation(
            schema=p14ba.RECONCILIATION_SCHEMA,
            authority_id="maintainer", approver_id="maintainer",
            approver_key_fingerprint=self.signer.fingerprint,
            trust_policy_sha256=self.allowlist.digest(),
            mission_id=MISSION_ID, authorization_digest=self.digest,
            payload_sha256=self.mission.payload_sha256,
            receipt_sha256=_digest(receipt), report_sha256=_digest(report),
            evidence_sha256=_digest(evidence), outcome="COMPLETED",
            environment="SHADOW", issued_at=NOW,
            expires_at=NOW + timedelta(minutes=10),
            nonce="nonce-recon-trust-001", mode="SHADOW")
        signer = signer or self.signer
        signature = signer.sign(
            p14ba.canonical_reconciliation_bytes(attestation),
            namespace=p14ba.RECONCILIATION_NAMESPACE, directory=self.keys)
        return attestation.to_mapping(), signature


class ServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _prepared(self) -> Fixture:
        fx = self.fx
        proposed = fx.propose()
        self.assertTrue(proposed["ok"], proposed)
        authorized = fx.authorize()
        self.assertTrue(authorized["ok"], authorized)
        return fx

    def _reserved(self) -> tuple[Fixture, str]:
        fx = self._prepared()
        reserved = fx.reserve()
        self.assertTrue(reserved["ok"], reserved)
        return fx, reserved["result"]["lease_id"]

    def _revoke(self, fx: Fixture) -> dict:
        statement = cas.SignedRevocation(
            schema=cas.REVOCATION_SCHEMA_VERSION,
            authorization_digest=fx.digest,
            approver_id=fx.signer.approver_id,
            approver_key_fingerprint=fx.signer.fingerprint,
            trust_policy_sha256=fx.allowlist.digest(),
            revoked_at=NOW - timedelta(seconds=1),
            reason="contramandamento", nonce="nonce-revoke-trust-001")
        signature = fx.signer.sign(
            cas.canonical_revocation_bytes(statement),
            namespace=cas.REVOCATION_NAMESPACE, directory=fx.keys)
        response = fx.request("revoke", {
            "statement": statement.canonical_material(),
            "signature": _b64(signature)})
        self.assertTrue(response["ok"], response)
        return response

    def test_local_socket_authenticates_peer_and_serves_status(self):
        self.assertTrue(self.fx.propose()["ok"])
        server = svc.LocalService(self.fx.service)
        server.start()
        try:
            response = svc.ServiceClient(
                self.fx.config.socket_path,
                service_uid=self.fx.config.service_uid,
                service_gid=self.fx.config.service_gid).request(
                    "status", {"mission_id": MISSION_ID})
        finally:
            server.stop()
        self.assertTrue(response["ok"], response)
        self.assertEqual("PROPOSED", response["result"]["state"])
        self.assertFalse(response["result"]["real_transport"])

    def test_socket_rejects_unauthorized_peer(self):
        with tempfile.TemporaryDirectory() as bad_root:
            bad = Fixture(bad_root, wrong_peer=True)
            server = svc.LocalService(bad.service)
            server.start()
            try:
                with self.assertRaises((ConnectionError, OSError)):
                    svc.ServiceClient(
                        bad.config.socket_path,
                        service_uid=bad.config.service_uid,
                        service_gid=bad.config.service_gid).request(
                        "status", {"mission_id": MISSION_ID})
            finally:
                server.stop()

    def test_replay_is_idempotent_and_divergence_rejected(self):
        self.assertTrue(self.fx.propose()["ok"])
        first = self.fx.request(
            "status", {"mission_id": MISSION_ID}, request_id="req-repeat")
        self.assertTrue(first["ok"])
        second = self.fx.request(
            "status", {"mission_id": MISSION_ID}, request_id="req-repeat")
        self.assertTrue(second["ok"])
        self.assertTrue(second.get("replayed"))
        divergent = self.fx.request(
            "status", {"mission_id": "MISS-OTHER"}, request_id="req-repeat")
        self.assertFalse(divergent["ok"])
        self.assertEqual("REQUEST_REPLAY", divergent["error"]["code"])

    def test_replay_assert_after_revocation_is_stale(self):
        fx, lease = self._reserved()
        first = fx.assert_dispatch(lease, request_id="req-assert-stale")
        self.assertTrue(first["ok"], first)
        self.assertTrue(first["authorization_current"])
        self._revoke(fx)
        replay = fx.assert_dispatch(lease, request_id="req-assert-stale")
        self.assertFalse(replay["ok"], replay)
        self.assertEqual("REPLAY_STALE", replay["error"]["code"])
        self.assertNotIn("result", replay)
        self.assertEqual("DISPATCHING", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])

    def test_replay_reserve_after_revocation_is_stale(self):
        fx = self._prepared()
        first = fx.reserve(request_id="req-reserve-stale")
        self.assertTrue(first["ok"], first)
        self._revoke(fx)
        replay = fx.reserve(request_id="req-reserve-stale")
        self.assertFalse(replay["ok"], replay)
        self.assertEqual("REPLAY_STALE", replay["error"]["code"])
        self.assertNotIn("result", replay)

    def test_replay_authorize_after_revocation_is_stale(self):
        fx = self.fx
        self.assertTrue(fx.propose()["ok"])
        first = fx.authorize(request_id="req-authorize-stale")
        self.assertTrue(first["ok"], first)
        self._revoke(fx)
        replay = fx.authorize(request_id="req-authorize-stale")
        self.assertFalse(replay["ok"], replay)
        self.assertEqual("REPLAY_STALE", replay["error"]["code"])
        self.assertNotIn("result", replay)

    def test_replay_assert_after_unknown_and_expiry_is_stale(self):
        fx, lease = self._reserved()
        self.assertTrue(fx.assert_dispatch(
            lease, request_id="req-assert-unknown")["ok"])
        interrupted = fx.request("interrupt", {
            "mission_id": MISSION_ID, "cause": "process_loss"})
        self.assertTrue(interrupted["ok"], interrupted)
        replay = fx.assert_dispatch(lease, request_id="req-assert-unknown")
        self.assertFalse(replay["ok"], replay)
        self.assertEqual("REPLAY_STALE", replay["error"]["code"])

        with tempfile.TemporaryDirectory() as other_root:
            fx2 = Fixture(other_root)
            self.assertTrue(fx2.propose()["ok"])
            self.assertTrue(fx2.authorize()["ok"])
            reserved2 = fx2.reserve()
            self.assertTrue(reserved2["ok"], reserved2)
            lease2 = reserved2["result"]["lease_id"]
            self.assertTrue(fx2.assert_dispatch(
                lease2, request_id="req-assert-expired")["ok"])
            future = NOW + timedelta(minutes=30)
            fx2.service.clock = lambda: future
            fx2.service.ledger.clock = lambda: future
            expired = fx2.assert_dispatch(
                lease2, request_id="req-assert-expired")
            self.assertFalse(expired["ok"], expired)
            self.assertEqual("REPLAY_STALE", expired["error"]["code"])

    def test_replay_assert_after_terminal_is_stale_and_status_is_historical(self):
        fx, lease = self._reserved()
        self.assertTrue(fx.assert_dispatch(
            lease, request_id="req-assert-terminal")["ok"])
        self.assertTrue(fx.record(lease)["ok"])
        attestation, signature = fx.attestation()
        completed = fx.request("reconcile", {
            "mission_id": MISSION_ID, "authorization_digest": fx.digest,
            "lease_id": lease, "target_state": "COMPLETED",
            "attestation": attestation,
            "attestation_signature": _b64(signature),
            "report": fx.report(), "evidence": fx.evidence()[1],
        }, request_id="req-reconcile-terminal")
        self.assertTrue(completed["ok"], completed)
        replay = fx.assert_dispatch(lease, request_id="req-assert-terminal")
        self.assertFalse(replay["ok"], replay)
        self.assertEqual("REPLAY_STALE", replay["error"]["code"])
        historical = fx.request(
            "status", {"mission_id": MISSION_ID},
            request_id="req-status-historical")
        self.assertTrue(historical["ok"], historical)
        repeated = fx.request(
            "status", {"mission_id": MISSION_ID},
            request_id="req-status-historical")
        self.assertTrue(repeated["ok"], repeated)
        self.assertTrue(repeated["historical"])
        self.assertFalse(repeated["authorization_current"])

    def test_self_grant_fields_are_rejected_and_unsigned_authorization_fails(self):
        self.assertTrue(self.fx.propose()["ok"])
        forged = self.fx.request("status", {
            "mission_id": MISSION_ID, "consumer_id": "maintainer"})
        self.assertFalse(forged["ok"])
        self.assertEqual("PERMISSION_DENIED", forged["error"]["code"])
        unsigned = self.fx.authorize(signature=b"not-a-signature")
        self.assertFalse(unsigned["ok"])
        self.assertEqual("PROPOSED", self.fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])
        unknown = self.fx.request("read_store", {"path": "ledger.db"})
        self.assertFalse(unknown["ok"])
        self.assertEqual("BAD_REQUEST", unknown["error"]["code"])

    def test_least_privilege_observer_cannot_execute(self):
        with tempfile.TemporaryDirectory() as observer_root:
            observer_fx = Fixture(observer_root, observer=True)
            self.assertTrue(observer_fx.propose()["ok"])
            denied = observer_fx.propose(peer=observer_fx.observer_peer)
            self.assertFalse(denied["ok"])
            self.assertEqual("PERMISSION_DENIED", denied["error"]["code"])
            status = observer_fx.request(
                "status", {"mission_id": MISSION_ID},
                peer=observer_fx.observer_peer)
            self.assertTrue(status["ok"])

    def test_storage_security_rejects_permissions_and_symlink(self):
        config = self.fx.config
        os.chmod(config.state_dir, 0o755)
        with self.assertRaises(svc.StorageSecurityError):
            config.prepare()
        os.chmod(config.state_dir, 0o700)
        os.chmod(config.trust_store_path, 0o644)
        with self.assertRaises(svc.StorageSecurityError):
            svc.TrustStore.load(config.trust_store_path)
        os.chmod(config.trust_store_path, 0o600)
        link = os.path.join(config.state_dir, "linked-store")
        os.symlink(config.trust_store_path, link)
        linked = svc.ServiceConfig(
            state_dir=config.state_dir, project_id=PROJECT,
            repository_id=REPO, service_uid=os.geteuid(),
            service_gid=os.getegid(), trust_store_path=link,
            consumer_registry_path=config.consumer_registry_path,
            socket_path=config.socket_path, allow_same_uid=True, test_mode=True)
        with self.assertRaises(svc.StorageSecurityError):
            linked.prepare()

    def test_transport_real_is_disabled_by_default(self):
        config = svc.ServiceConfig(
            state_dir=self.fx.config.state_dir, project_id=PROJECT,
            repository_id=REPO, service_uid=os.geteuid(),
            service_gid=os.getegid(), trust_store_path=self.fx.trust_path,
            consumer_registry_path=self.fx.consumer_path,
            socket_path=self.fx.config.socket_path, allow_same_uid=True,
            test_mode=True, real_transport=True)
        with self.assertRaises(svc.TransportDisabled):
            config.prepare()
        response = self.fx.request("transmit", {})
        self.assertFalse(response["ok"])
        self.assertEqual("TRANSPORT_DISABLED", response["error"]["code"])
        source = Path(svc.__file__).read_text(encoding="utf-8")
        self.assertNotIn("AF_INET", source)
        self.assertNotIn("create_connection", source)

    def test_same_uid_deployment_is_rejected_unless_explicit_test_mode(self):
        config = svc.ServiceConfig(
            state_dir=self.fx.config.state_dir, project_id=PROJECT,
            repository_id=REPO, service_uid=os.geteuid(),
            service_gid=os.getegid(), trust_store_path=self.fx.trust_path,
            consumer_registry_path=self.fx.consumer_path,
            socket_path=self.fx.config.socket_path, allow_same_uid=False)
        registry = svc.ConsumerRegistry.from_mapping(
            self.fx.registry.to_mapping(), allow_same_uid=False)
        with self.assertRaises(svc.StorageSecurityError):
            config.validate_consumers(registry)

    def test_propose_authorize_reserve_assert_evidence_reconcile(self):
        fx = self._prepared()
        reserved = fx.reserve()
        self.assertTrue(reserved["ok"], reserved)
        lease = reserved["result"]["lease_id"]
        self.assertTrue(fx.assert_dispatch(lease)["ok"])
        self.assertTrue(fx.record(lease)["ok"])
        attestation, signature = fx.attestation()
        receipt, evidence = fx.evidence()
        result = fx.request("reconcile", {
            "mission_id": MISSION_ID, "authorization_digest": fx.digest,
            "lease_id": lease, "target_state": "COMPLETED",
            "attestation": attestation,
            "attestation_signature": _b64(signature),
            "report": fx.report(), "evidence": evidence,
        })
        self.assertTrue(result["ok"], result)
        self.assertEqual("COMPLETED", result["result"]["state"])
        self.assertFalse(fx.service.ledger.is_reserved(MISSION_ID))


    def test_lease_duplicate_and_request_replay_do_not_reserve_twice(self):
        fx = self._prepared()
        first = fx.reserve(request_id="req-reserve-once")
        self.assertTrue(first["ok"], first)
        same = fx.reserve(request_id="req-reserve-once")
        self.assertTrue(same["ok"], same)
        self.assertTrue(same.get("replayed"))
        self.assertEqual(first["result"]["lease_id"],
                         same["result"]["lease_id"])
        duplicate = fx.reserve()
        self.assertFalse(duplicate["ok"])
        self.assertEqual("DISPATCHING", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])

    def test_wrong_lease_and_digest_are_rejected(self):
        fx = self._prepared()
        reserved = fx.reserve()
        lease = reserved["result"]["lease_id"]
        wrong_lease = fx.assert_dispatch("lease-" + "0" * 32)
        self.assertFalse(wrong_lease["ok"])
        wrong_digest = fx.assert_dispatch(lease, digest="f" * 64)
        self.assertFalse(wrong_digest["ok"])
        self.assertTrue(fx.assert_dispatch(lease)["ok"])

    def test_revocation_blocks_assert_without_resend(self):
        fx, lease = self._reserved()
        statement = cas.SignedRevocation(
            schema=cas.REVOCATION_SCHEMA_VERSION,
            authorization_digest=fx.digest,
            approver_id=fx.signer.approver_id,
            approver_key_fingerprint=fx.signer.fingerprint,
            trust_policy_sha256=fx.allowlist.digest(),
            revoked_at=NOW - timedelta(seconds=1),
            reason="contramandamento", nonce="nonce-revoke-trust-001")
        signature = fx.signer.sign(
            cas.canonical_revocation_bytes(statement),
            namespace=cas.REVOCATION_NAMESPACE, directory=fx.keys)
        revoked = fx.request("revoke", {
            "statement": statement.canonical_material(),
            "signature": _b64(signature)})
        self.assertTrue(revoked["ok"], revoked)
        blocked = fx.assert_dispatch(lease)
        self.assertFalse(blocked["ok"])
        self.assertEqual("SERVICE_REJECTED", blocked["error"]["code"])
        self.assertEqual("DISPATCHING", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])

    def test_interrupt_preserves_lease_and_blocks_resend(self):
        fx, lease = self._reserved()
        self.assertTrue(fx.assert_dispatch(lease)["ok"])
        interrupted = fx.request("interrupt", {
            "mission_id": MISSION_ID, "cause": "process_loss"})
        self.assertTrue(interrupted["ok"], interrupted)
        self.assertEqual("UNKNOWN", interrupted["result"]["state"])
        self.assertTrue(interrupted["result"]["reserved"])
        self.assertFalse(fx.assert_dispatch(lease)["ok"])
        again = fx.reserve()
        self.assertFalse(again["ok"])
        self.assertEqual("UNKNOWN", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])

    def test_fabricated_completion_is_rejected(self):
        fx, lease = self._reserved()
        self.assertTrue(fx.record(lease)["ok"])
        other = p14b.SyntheticSigner(os.path.join(fx.root, "attacker"),
                                     persist=True)
        attestation, signature = fx.attestation(signer=other)
        forged = fx.request("reconcile", {
            "mission_id": MISSION_ID, "authorization_digest": fx.digest,
            "lease_id": lease, "target_state": "COMPLETED",
            "attestation": attestation,
            "attestation_signature": _b64(signature),
            "report": fx.report(), "evidence": fx.evidence()[1],
        })
        self.assertFalse(forged["ok"])
        self.assertEqual("DISPATCHING", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])

    def test_tampered_report_is_rejected(self):
        fx, lease = self._reserved()
        self.assertTrue(fx.record(lease)["ok"])
        report = fx.report()
        attestation, signature = fx.attestation(report=report)
        tampered = dict(report, message_id="report-forged")
        result = fx.request("reconcile", {
            "mission_id": MISSION_ID, "authorization_digest": fx.digest,
            "lease_id": lease, "target_state": "COMPLETED",
            "attestation": attestation,
            "attestation_signature": _b64(signature),
            "report": tampered, "evidence": fx.evidence()[1],
        })
        self.assertFalse(result["ok"])
        self.assertEqual("DISPATCHING", fx.request(
            "status", {"mission_id": MISSION_ID})["result"]["state"])


    def test_service_state_tampering_is_detected(self):
        self.assertTrue(self.fx.propose()["ok"])
        path = self.fx.config.service_db_path
        with sqlite3.connect(path) as conn:
            row = conn.execute(
                "SELECT mission_json FROM service_missions WHERE mission_id=?",
                (MISSION_ID,)).fetchone()
            mission = json.loads(row[0])
            mission["payload"]["message"] = "tampered"
            conn.execute(
                "UPDATE service_missions SET mission_json=? WHERE mission_id=?",
                (json.dumps(mission, sort_keys=True), MISSION_ID))
        result = self.fx.request("status", {"mission_id": MISSION_ID})
        self.assertFalse(result["ok"])
        self.assertEqual("STORAGE_SECURITY", result["error"]["code"])

    def test_ledger_tampering_is_detected_by_service(self):
        fx, lease = self._reserved()
        with sqlite3.connect(fx.config.ledger_path) as conn:
            row = conn.execute(
                "SELECT mission_snapshot_json FROM missions WHERE mission_id=?",
                (MISSION_ID,)).fetchone()
            snapshot = json.loads(row[0])
            snapshot["model"] = "other/model"
            conn.execute(
                "UPDATE missions SET mission_snapshot_json=? WHERE mission_id=?",
                (json.dumps(snapshot, sort_keys=True), MISSION_ID))
        result = fx.assert_dispatch(lease)
        self.assertFalse(result["ok"])
        self.assertEqual("SERVICE_REJECTED", result["error"]["code"])

    def test_client_cannot_read_store_or_get_paths(self):
        self.assertTrue(self.fx.propose()["ok"])
        status = self.fx.request("status", {"mission_id": MISSION_ID})
        serialized = json.dumps(status)
        self.assertNotIn(self.fx.config.state_dir, serialized)
        self.assertNotIn("ledger.db", serialized)
        self.assertNotIn("trust-store.json", serialized)
        denied = self.fx.request("storage", {"path": self.fx.config.state_dir})
        self.assertFalse(denied["ok"])
        self.assertEqual("BAD_REQUEST", denied["error"]["code"])


    def test_kernel_credentials_reject_missing_divergent_and_truncated(self):
        expected = svc.PeerIdentity(
            pid=os.getpid(), uid=os.geteuid(), gid=os.getegid(),
            source="SO_PEERCRED")
        valid = (socket.SOL_SOCKET, socket.SCM_CREDENTIALS,
                 struct.pack("3i", os.getpid(), os.geteuid(), os.getegid()))
        peer = svc._credentials_from_ancillary([valid], 0, expected=expected)
        self.assertEqual((peer.uid, peer.gid), (os.geteuid(), os.getegid()))
        self.assertEqual("SCM_CREDENTIALS", peer.source)

        divergent = (socket.SOL_SOCKET, socket.SCM_CREDENTIALS,
                     struct.pack("3i", os.getpid(), os.geteuid() + 1,
                                 os.getegid()))
        cases = (
            ([], 0),
            ([valid, valid], 0),
            ([(socket.SOL_SOCKET, socket.SCM_CREDENTIALS, b"\0" * 8)], 0),
            ([(socket.SOL_SOCKET, socket.SCM_RIGHTS, b"\0\0\0\0")], 0),
            ([valid], getattr(socket, "MSG_CTRUNC", 0)),
            ([divergent], 0),
        )
        for ancdata, flags in cases:
            with self.assertRaises(svc.AuthenticationError):
                svc._credentials_from_ancillary(
                    ancdata, flags, expected=expected)

    def test_uid_registry_rejects_duplicate_and_unknown(self):
        policy = svc.ConsumerPolicy(
            consumer_id="a", uid=os.geteuid() + 10,
            allowed_actions=frozenset({"status"}))
        with self.assertRaises(svc.ServiceConfigurationError):
            svc.ConsumerRegistry((policy, svc.ConsumerPolicy(
                consumer_id="b", uid=policy.uid,
                allowed_actions=frozenset({"status"}))), allow_same_uid=True)
        registry = svc.ConsumerRegistry((policy,), allow_same_uid=False)
        unknown = svc.PeerIdentity(
            pid=os.getpid(), uid=policy.uid + 1, gid=os.getegid(),
            source="SCM_CREDENTIALS")
        with self.assertRaises(svc.AuthenticationError):
            registry.resolve(unknown)

    def test_same_uid_requires_explicit_test_mode(self):
        config = svc.ServiceConfig(
            state_dir=self.fx.state, project_id=PROJECT,
            repository_id=REPO, service_uid=os.geteuid(),
            service_gid=os.getegid(), trust_store_path=self.fx.trust_path,
            consumer_registry_path=self.fx.consumer_path,
            socket_path=self.fx.config.socket_path,
            allow_same_uid=True, test_mode=False)
        with self.assertRaises(svc.StorageSecurityError):
            config.prepare()

    def test_hardlink_trust_store_and_sqlite_are_rejected(self):
        store_link = self.fx.trust_path + ".hardlink"
        os.link(self.fx.trust_path, store_link)
        with self.assertRaises(svc.StorageSecurityError):
            svc.TrustStore.load(store_link)
        self.assertTrue(self.fx.propose()["ok"])
        ledger_link = self.fx.config.ledger_path + ".hardlink"
        os.link(self.fx.config.ledger_path, ledger_link)
        with self.assertRaises(svc.StorageSecurityError):
            self.fx.config.prepare()

    def test_socket_path_substitution_is_rejected(self):
        os.makedirs(os.path.dirname(self.fx.config.socket_path), exist_ok=True)
        with open(self.fx.config.socket_path, "w", encoding="utf-8") as handle:
            handle.write("not-a-socket")
        with self.assertRaises(svc.StorageSecurityError):
            svc.LocalService(self.fx.service).start()
        os.unlink(self.fx.config.socket_path)
        os.symlink(self.fx.trust_path, self.fx.config.socket_path)
        with self.assertRaises(svc.StorageSecurityError):
            svc.LocalService(self.fx.service).start()

    def test_client_authenticates_server_uid_gid_and_service_id(self):
        server = svc.LocalService(self.fx.service)
        server.start()
        try:
            with self.assertRaises(svc.AuthenticationError):
                svc.ServiceClient(
                    self.fx.config.socket_path,
                    expected_service_uid=os.geteuid() + 1,
                    expected_service_gid=os.getegid()).request(
                        "status", {"mission_id": MISSION_ID})
            with self.assertRaises(svc.AuthenticationError):
                svc.ServiceClient(
                    self.fx.config.socket_path,
                    expected_service_uid=os.geteuid(),
                    expected_service_gid=os.getegid(),
                    expected_service_id="wrong-service-id").request(
                        "status", {"mission_id": MISSION_ID})
        finally:
            server.stop()

    def test_total_request_deadline_closes_slow_client(self):
        with tempfile.TemporaryDirectory() as slow_root:
            fx = Fixture(slow_root, request_timeout_seconds=0.2)
            server = svc.LocalService(fx.service)
            server.start()
            try:
                conn = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
                conn.settimeout(2)
                conn.connect(fx.config.socket_path)
                svc._frame_recv_plain(conn, svc.MAX_FRAME_BYTES)
                time.sleep(0.35)
                with self.assertRaises((ConnectionError, OSError)):
                    svc._frame_send(
                        conn, {"type": "hello", "protocol": svc.PROTOCOL},
                        svc.MAX_FRAME_BYTES)
                    svc._frame_recv_plain(conn, svc.MAX_FRAME_BYTES)
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
                server.stop()

    def test_ledger_rollback_anchor_quarantines_old_restore(self):
        self.assertTrue(self.fx.propose()["ok"])
        self.assertTrue(self.fx.authorize()["ok"])
        svc.build_service(self.fx.config, clock=lambda: NOW)
        with sqlite3.connect(self.fx.config.ledger_path) as conn:
            conn.execute(
                "DELETE FROM authorizations WHERE mission_id=?",
                (MISSION_ID,))
        restored = svc.build_service(self.fx.config, clock=lambda: NOW)
        self.assertTrue(restored.quarantined)
        blocked = restored.handle(self.fx.peer, {
            "request_id": "req-quarantine", "action": "reserve",
            "payload": {}})
        self.assertFalse(blocked["ok"])
        self.assertEqual("REPLAY_STALE", blocked["error"]["code"])


    @staticmethod
    def _backup_db(source: str, target: str) -> None:
        src = sqlite3.connect(source)
        dst = sqlite3.connect(target)
        try:
            src.backup(dst)
        finally:
            dst.close()
            src.close()

    @staticmethod
    def _restore_db(source: str, target: str) -> None:
        for suffix in ("-wal", "-shm", "-journal"):
            try:
                os.unlink(target + suffix)
            except FileNotFoundError:
                pass
        src = sqlite3.connect(source)
        dst = sqlite3.connect(target)
        try:
            src.backup(dst)
        finally:
            dst.close()
            src.close()
        os.chmod(target, 0o600)

    def test_full_rollback_quarantines_and_signed_recovery_invalidates_epoch(self):
        fx = self._prepared()
        ledger_backup = os.path.join(fx.root, "ledger-before.db")
        service_backup = os.path.join(fx.root, "service-before.db")
        self._backup_db(fx.config.ledger_path, ledger_backup)
        self._backup_db(fx.config.service_db_path, service_backup)
        self.assertTrue(fx.reserve()["ok"])
        self._restore_db(ledger_backup, fx.config.ledger_path)
        self._restore_db(service_backup, fx.config.service_db_path)
        restarted = svc.build_service(fx.config, clock=lambda: NOW)
        self.assertTrue(restarted.quarantined)
        blocked = restarted.handle(fx.peer, {
            "request_id": "req-quarantine-restore", "action": "reserve",
            "payload": {
                "mission_id": MISSION_ID, "authorization_digest": fx.digest,
                "agent": "agent-a", "session_id": "ses-trust",
                "instance_id": "inst-1", "worktree": "/tmp/wt-trust",
            }})
        self.assertFalse(blocked["ok"])
        self.assertIn(blocked["error"]["code"], {"REPLAY_STALE"})

        statement = svc.RecoveryStatement(
            schema=svc.RECOVERY_SCHEMA,
            approver_id=fx.signer.approver_id,
            approver_key_fingerprint=fx.signer.fingerprint,
            trust_policy_sha256=fx.allowlist.digest(),
            new_epoch=restarted.epoch + 1,
            reason="rollback restaurado; invalidar epoch anterior",
            issued_at=NOW, expires_at=NOW + timedelta(minutes=10),
            nonce="nonce-recovery-test-001")
        signature = fx.signer.sign(
            svc.canonical_recovery_bytes(statement),
            namespace=svc.RECOVERY_NAMESPACE, directory=fx.keys)
        recovered = restarted.handle(fx.peer, {
            "request_id": "req-recovery-test", "action": "recover",
            "payload": {
                "statement": statement.to_mapping(),
                "signature": _b64(signature),
            }})
        self.assertTrue(recovered["ok"], recovered)
        self.assertFalse(restarted.quarantined)
        self.assertEqual(statement.new_epoch, restarted.epoch)
        old = restarted.handle(fx.peer, {
            "request_id": "req-old-epoch", "action": "reserve",
            "payload": {
                "mission_id": MISSION_ID, "authorization_digest": fx.digest,
                "agent": "agent-a", "session_id": "ses-trust",
                "instance_id": "inst-1", "worktree": "/tmp/wt-trust",
            }})
        self.assertFalse(old["ok"])
        self.assertEqual("REPLAY_STALE", old["error"]["code"])
        status = restarted.handle(fx.peer, {
            "request_id": "req-status-after-recovery", "action": "status",
            "payload": {"mission_id": MISSION_ID}})
        self.assertTrue(status["ok"], status)
        self.assertFalse(status["result"]["quarantined"])
        self.assertEqual(statement.new_epoch, status["result"]["epoch"])

    def test_missing_external_anchor_with_state_quarantines(self):
        self.assertTrue(self.fx.propose()["ok"])
        os.unlink(self.fx.config.anchor_path)
        restarted = svc.build_service(self.fx.config, clock=lambda: NOW)
        self.assertTrue(restarted.quarantined)
        status = restarted.handle(self.fx.peer, {
            "request_id": "req-missing-anchor", "action": "status",
            "payload": {"mission_id": MISSION_ID}})
        self.assertTrue(status["ok"], status)
        self.assertTrue(status["result"]["quarantined"])

    def test_crash_before_ledger_is_outcome_unknown_after_restart(self):
        fx = self._prepared()
        payload = {"mission_id": MISSION_ID}
        sha = svc.sha256_text(svc.canonical_json(
            {"action": "status", "payload": payload}))
        self.assertIsNone(fx.service.state.begin_request(
            "req-crash-before-ledger", "executor", "status", sha, NOW))
        restarted = svc.build_service(fx.config, clock=lambda: NOW)
        response = restarted.handle(fx.peer, {
            "request_id": "req-crash-before-ledger", "action": "status",
            "payload": payload})
        self.assertFalse(response["ok"])
        self.assertEqual("OUTCOME_UNKNOWN", response["error"]["code"])

    def test_crash_after_ledger_commit_quarantines_without_lease_recovery(self):
        fx = self._prepared()
        payload = {
            "mission_id": MISSION_ID, "authorization_digest": fx.digest,
            "agent": "agent-a", "session_id": "ses-trust",
            "instance_id": "inst-1", "worktree": "/tmp/wt-trust",
        }
        sha = svc.sha256_text(svc.canonical_json(
            {"action": "reserve", "payload": payload}))
        self.assertIsNone(fx.service.state.begin_request(
            "req-crash-after-ledger", "executor", "reserve", sha, NOW))
        fx.service.ledger.reserve_dispatch_signed(
            MISSION_ID, authorization_digest=fx.digest, agent="agent-a",
            session_id="ses-trust", instance_id="inst-1",
            worktree="/tmp/wt-trust", actor="executor")
        restarted = svc.build_service(fx.config, clock=lambda: NOW)
        self.assertTrue(restarted.quarantined)
        status = restarted.handle(fx.peer, {
            "request_id": "req-crash-status", "action": "status",
            "payload": {"mission_id": MISSION_ID}})
        self.assertTrue(status["ok"], status)
        self.assertEqual("DISPATCHING", status["result"]["state"])
        self.assertTrue(status["result"]["reserved"])
        self.assertNotIn("lease_id", status["result"])

    def test_propose_reconciles_service_mission_gap_by_full_material(self):
        fx = self.fx
        self.assertTrue(fx.propose()["ok"])
        with sqlite3.connect(fx.config.service_db_path) as conn:
            conn.execute(
                "DELETE FROM service_missions WHERE mission_id=?",
                (MISSION_ID,))
        recovered = fx.propose()
        self.assertTrue(recovered["ok"], recovered)
        self.assertEqual("PROPOSED", recovered["result"]["state"])
        self.assertEqual(
            MISSION_ID, fx.service.state.get_mission(MISSION_ID).mission_id)

    def test_ancestor_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            real = os.path.join(root, "real")
            os.makedirs(real, mode=0o700)
            link = os.path.join(root, "link")
            os.symlink(real, link)
            state = os.path.join(link, "state")
            os.makedirs(state, mode=0o700)
            trust = os.path.join(state, "trust.json")
            consumers = os.path.join(state, "consumers.json")
            svc.write_trust_store(trust, cas.ApproverAllowlist(
                (p14b.SyntheticSigner(os.path.join(root, "keys")).approver_key(NOW),)))
            svc.write_consumer_registry(consumers, svc.ConsumerRegistry(
                (svc.ConsumerPolicy(
                    consumer_id="executor", uid=os.geteuid(),
                    allowed_actions=ALL_ACTIONS, agent_id="agent-a"),),
                allow_same_uid=True))
            config = svc.ServiceConfig(
                state_dir=state, project_id=PROJECT, repository_id=REPO,
                service_uid=os.geteuid(), service_gid=os.getegid(),
                trust_store_path=trust, consumer_registry_path=consumers,
                socket_path=os.path.join(state, "service.sock"),
                allow_same_uid=True, test_mode=True)
            with self.assertRaises(svc.StorageSecurityError):
                config.prepare()


    def test_deployment_examples_and_cli_check(self):
        root = Path(__file__).resolve().parents[2]
        config_path = root / ".opencode/deploy/trust-service.example.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual("BT-COORD-TRUST-SERVICE-CONFIG-V1", config["schema"])
        self.assertFalse(config["real_transport"])
        self.assertFalse(config["allow_same_uid"])
        consumers = json.loads(
            (root / ".opencode/deploy/consumers.example.json").read_text(
                encoding="utf-8"))
        registry = svc.ConsumerRegistry.from_mapping(
            consumers, allow_same_uid=False)
        self.assertTrue(registry.consumers)
        unit = (root / ".opencode/deploy/coord-trust-service.service").read_text(
            encoding="utf-8")
        for line in ("User=bt-coord-trust", "NoNewPrivileges=yes",
                     "RestrictAddressFamilies=AF_UNIX",
                     "CapabilityBoundingSet=", "ProtectSystem=strict"):
            self.assertIn(line, unit)

        config = dict(config)
        config.update({
            "state_dir": self.fx.state,
            "service_uid": os.geteuid(), "service_gid": os.getegid(),
            "trust_store_path": self.fx.trust_path,
            "consumer_registry_path": self.fx.consumer_path,
            "socket_path": self.fx.config.socket_path,
            "anchor_path": os.path.join(self.fx.root, "anchor", "anchor.json"),
            "allow_same_uid": True, "test_mode": True, "real_transport": False,
        })
        cfg_path = os.path.join(self.fx.state, "check-config.json")
        with open(cfg_path, "w", encoding="utf-8") as handle:
            json.dump(config, handle)
        os.chmod(cfg_path, 0o600)
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, cli.main(
                ["--config", cfg_path, "--check"]))
        self.assertIn("CONFIG-OK real_transport=false", output.getvalue())

    def test_wrong_consumer_peer_is_unauthenticated(self):
        unknown = svc.PeerIdentity(
            pid=os.getpid(), uid=os.geteuid() + 2000, gid=os.getegid())
        with self.assertRaises(svc.AuthenticationError):
            self.fx.service.handle(
                unknown, {"request_id": "req-unauth", "action": "status",
                          "payload": {"mission_id": MISSION_ID}})

    def test_ledger_and_trust_store_are_private_files(self):
        self.assertTrue(self.fx.propose()["ok"])
        for path in (self.fx.config.ledger_path,
                     self.fx.trust_path, self.fx.consumer_path,
                     self.fx.config.service_db_path):
            self.assertTrue(os.path.isfile(path), path)
            self.assertEqual(0o600, os.stat(path).st_mode & 0o777, path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
