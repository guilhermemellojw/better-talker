#!/usr/bin/env python3
"""COORD-OPS/P9.2 — testes host-only do driver sintético.

Cliente do serviço é falso/in-process; nenhum teste aqui é certificação do
socket AF_UNIX real. O teste do helper do signer usa subprocesso OpenSSH
sintético apenas para validar o protocolo público, sem serviço e sem rede.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Optional

import coord_authority_signed as cas
import coord_communication as cc
import coord_p14b as p14b
import coord_p14b_authority as p14ba
import coord_trust_crossuid_common as common
import coord_trust_service as svc
import coord_ops_p9_pilot as p9

NOW = datetime(2026, 11, 28, 12, 0, tzinfo=timezone.utc)
HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "coord_ops_p9_pilot.py")


class TestSigner:
    """Signer separado do driver; o driver só recebe os mappings públicos."""

    def __init__(self, root: str) -> None:
        self.directory = os.path.join(root, "signer")
        os.makedirs(self.directory, mode=0o700, exist_ok=True)
        self.signer = p14b.SyntheticSigner(self.directory, persist=True)
        self.allowlist = cas.ApproverAllowlist(
            (self.signer.approver_key(NOW),))

    def authorization(self, *, mission: cc.MissionEnvelope, model: str,
                      environment: str, now: datetime) -> Mapping[str, Any]:
        envelope = common.make_envelope(
            mission, self.signer, self.allowlist, now,
            tag=mission.operation_id)
        signature = self.signer.sign(
            p14ba.canonical_authorization_bytes(envelope),
            namespace=p14ba.AUTHORIZATION_NAMESPACE,
            directory=self.directory)
        return {
            "envelope": envelope.to_mapping(),
            "signature": common.b64(signature),
            "authorization_digest": p14ba.authorization_digest(envelope),
            "approver_id": self.signer.approver_id,
            "fingerprint": self.signer.fingerprint,
            "trust_policy_sha256": self.allowlist.digest(),
        }

    def attestation(self, *, mission: cc.MissionEnvelope,
                    authorization_digest: str, receipt: Mapping[str, Any],
                    report: Mapping[str, Any], evidence: Mapping[str, Any],
                    outcome: str, now: datetime) -> Mapping[str, Any]:
        attestation = p14ba.SignedReconciliation(
            schema=p14ba.RECONCILIATION_SCHEMA,
            authority_id=self.signer.approver_id,
            approver_id=self.signer.approver_id,
            approver_key_fingerprint=self.signer.fingerprint,
            trust_policy_sha256=self.allowlist.digest(),
            mission_id=mission.mission_id,
            authorization_digest=authorization_digest,
            payload_sha256=mission.payload_sha256,
            receipt_sha256=common.digest(receipt),
            report_sha256=common.digest(report),
            evidence_sha256=common.digest(evidence),
            outcome=outcome, environment="SHADOW",
            issued_at=now, expires_at=now + timedelta(minutes=10),
            nonce="nonce-p9-test-" + mission.operation_id,
            mode="SHADOW")
        signature = self.signer.sign(
            p14ba.canonical_reconciliation_bytes(attestation),
            namespace=p14ba.RECONCILIATION_NAMESPACE,
            directory=self.directory)
        return {"attestation": attestation.to_mapping(),
                "signature": common.b64(signature),
                "approver_id": self.signer.approver_id,
                "fingerprint": self.signer.fingerprint,
                "trust_policy_sha256": self.allowlist.digest()}


ALLOWED = {
    "all": frozenset({
        "propose", "authorize", "reserve", "assert", "record_evidence",
        "reconcile", "interrupt", "status", "revoke", "recover",
    }),
    "coordinator": frozenset({"propose", "authorize", "status"}),
    "executor": frozenset({
        "reserve", "assert", "record_evidence", "reconcile", "interrupt",
        "status",
    }),
    "observer": frozenset({"status"}),
    "maintainer": frozenset({"revoke", "recover", "status"}),
}


class FakeCore:
    def __init__(self, scenario: str = "normal") -> None:
        self.scenario = scenario
        self.missions: dict[str, dict[str, Any]] = {}
        self.active: dict[tuple[str, str], str] = {}
        self.calls: list[tuple[str, str, Optional[str], str]] = []

    @staticmethod
    def _error(code: str, reason: Optional[str] = None) -> dict[str, Any]:
        error = {"code": code, "message": "synthetic"}
        if reason:
            error["reason"] = reason
        return {"ok": False, "error": error}

    @staticmethod
    def _ok(**result: Any) -> dict[str, Any]:
        return {"ok": True, "result": result}

    def handle(self, role: str, action: str, payload: Mapping[str, Any],
               request_id: Optional[str]) -> dict[str, Any]:
        self.calls.append((role, action, request_id, self.scenario))
        if action not in ALLOWED[role]:
            return self._error("PERMISSION_DENIED")
        mission_id = payload.get("mission_id")
        if action == "propose":
            mission = payload.get("mission") or {}
            mid = mission.get("mission_id")
            if not isinstance(mid, str):
                return self._error("BAD_REQUEST")
            if mid in self.missions:
                if self.missions[mid].get("propose_request") == request_id:
                    return self._ok(state=self.missions[mid]["state"])
                return self._error("REQUEST_REPLAY")
            self.missions[mid] = {
                "mission": mission, "state": "PROPOSED",
                "auth": None, "lease": None, "asserted": False,
                "evidence": None, "propose_request": request_id,
            }
            return self._ok(state="PROPOSED")
        if not isinstance(mission_id, str) or mission_id not in self.missions:
            return self._error("CONFLICT")
        record = self.missions[mission_id]
        if action == "authorize":
            if self.scenario == "invalid_auth":
                return self._error("SERVICE_REJECTED", "SIGNATURE_INVALID")
            if record["auth"] is not None:
                if record["auth"] == request_id:
                    return self._ok(state=record["state"])
                return self._error("REQUEST_REPLAY")
            if not payload.get("envelope") or not payload.get("signature"):
                return self._error("BAD_REQUEST")
            record["auth"] = request_id
            record["state"] = "AUTHORIZED"
            return self._ok(state="AUTHORIZED")
        if action == "reserve":
            if record["auth"] is None:
                return self._error("REPLAY_STALE")
            if record["lease"] is not None:
                if record["lease"][0] == request_id:
                    return self._ok(state=record["state"],
                                    lease_id=record["lease"][1])
                return self._error("REQUEST_REPLAY")
            target = (str(payload.get("agent")), str(payload.get("session_id")))
            if target in self.active and self.active[target] != mission_id:
                return self._error("CONFLICT")
            lease_id = "lease-" + mission_id
            record["lease"] = (request_id, lease_id)
            record["state"] = "DISPATCHING"
            self.active[target] = mission_id
            return self._ok(state="DISPATCHING", lease_id=lease_id)
        if action == "assert":
            lease = payload.get("lease_id")
            if record["lease"] is None or lease != record["lease"][1]:
                return self._error("REPLAY_STALE")
            if record["state"] == "UNKNOWN":
                return self._error("REPLAY_STALE")
            record["asserted"] = True
            return self._ok(state=record["state"])
        if action == "record_evidence":
            if not record["asserted"] or record["lease"] is None:
                return self._error("CONFLICT")
            if self.scenario == "internal":
                raise RuntimeError("/private/path audit-failure")
            record["evidence"] = {
                "receipt": payload.get("receipt"),
                "evidence": payload.get("evidence"),
            }
            return self._ok(state=record["state"])
        if action == "reconcile":
            if self.scenario == "divergent_attestation":
                return self._error("SERVICE_REJECTED", "SIGNATURE_INVALID")
            if record["evidence"] is None:
                return self._error("CONFLICT")
            record["state"] = "COMPLETED"
            return self._ok(state="COMPLETED")
        if action == "interrupt":
            record["state"] = "UNKNOWN"
            return self._ok(state="UNKNOWN", interrupted=True)
        if action == "status":
            if self.scenario == "internal":
                raise RuntimeError("/private/path status-failure")
            return self._ok(state=record["state"], quarantined=False,
                            real_transport=False)
        if action in {"revoke", "recover"}:
            return self._ok(state=record["state"])
        return self._error("BAD_REQUEST")


class FakeClient:
    def __init__(self, core: FakeCore, role: str = "all") -> None:
        self.core = core
        self.role = role

    def request(self, action: str, payload: Mapping[str, Any], *,
                request_id: Optional[str] = None) -> dict[str, Any]:
        return self.core.handle(self.role, action, payload, request_id)


class P9PilotTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.signer = TestSigner(self.root)
        self.work = os.path.join(self.root, "work")
        self.artifacts = os.path.join(self.root, "artifacts")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def driver(self, *, core: Optional[FakeCore] = None,
               role_clients: Optional[Mapping[str, Any]] = None,
               scenario: str = "positive") -> p9.PilotDriver:
        core = core or FakeCore()
        return p9.PilotDriver(
            client=None if role_clients else FakeClient(core),
            role_clients=role_clients, signer=self.signer,
            work_dir=self.work, artifacts_dir=self.artifacts,
            source_head="a" * 40, clock=lambda: NOW, scenario=scenario)

    def test_positive_journey_matrix_and_next_proposal(self):
        core = FakeCore()
        summary = self.driver(core=core).run(("H3",))
        self.assertEqual("PASS", summary["matrix"]["H3"]["status"])
        self.assertEqual("PASS", summary["execution_status"])
        self.assertEqual("BLOCKED", summary["overall"])
        self.assertEqual(set(p9.GATES), set(summary["matrix"]))
        self.assertEqual(8, len([c for c in summary["trace"]
                                 if c["ok"] and c["action"] in {
                                     "propose", "authorize", "reserve",
                                     "assert", "record_evidence",
                                     "reconcile", "status"}]))
        self.assertEqual("MISS-P9-SYNTH-001",
                         summary["matrix"]["H3"]["evidence"]["mission_id"])
        self.assertEqual("PROPOSED",
                         summary["matrix"]["H3"]["evidence"]["next_state"])
        self.assertFalse(summary["real_transport"])
        self.assertEqual(0, summary["http_posts"])
        self.assertTrue(summary["cleanup"]["work_dir_removed"])
        self.assertFalse(summary["cleanup"]["private_key_accessed"])
        self.assertTrue(os.path.isfile(
            os.path.join(self.artifacts, "summary.json")))
        self.assertFalse(os.path.exists(self.work))
        self.assertFalse(any(
            c[1] in {"transmit"} for c in core.calls))

    def test_summary_is_sanitized_and_runtime_is_zero(self):
        summary = self.driver().run(("H3",))
        text = common.canonical_json(summary)
        self.assertNotIn("/private/path", text)
        self.assertNotIn("BEGIN OPENSSH PRIVATE KEY", text)
        self.assertNotIn("Traceback", text)
        self.assertFalse(summary["real_transport"])
        self.assertEqual(0, summary["llm_calls"])
        self.assertEqual(0, summary["open_code_calls"])
        self.assertEqual(0, summary["http_posts"])

    def test_invalid_authorization_blocks_reserve(self):
        summary = self.driver(core=FakeCore("invalid_auth")).run(("H4",))
        self.assertEqual("PASS", summary["matrix"]["H4"]["status"])
        self.assertEqual("SERVICE_REJECTED",
                         summary["matrix"]["H4"]["evidence"]["authorize"])
        self.assertNotIn("COMPLETED", json.dumps(summary["trace"]))

    def test_duplicate_reserve_is_idempotent_or_rejected(self):
        summary = self.driver().run(("H5",))
        self.assertEqual("PASS", summary["matrix"]["H5"]["status"])
        self.assertIn("lease_id", summary["matrix"]["H5"]["evidence"])
        self.assertIn(summary["matrix"]["H5"]["code"],
                      {None, "REQUEST_REPLAY", "CONFLICT", "REPLAY_STALE",
                       "OUTCOME_UNKNOWN"})

    def test_missing_authorization_is_blocked(self):
        summary = self.driver().run(("H6",))
        self.assertEqual("PASS", summary["matrix"]["H6"]["status"])
        self.assertEqual("REPLAY_STALE", summary["matrix"]["H6"]["code"])

    def test_interrupt_marks_unknown_without_resend(self):
        summary = self.driver().run(("H7",))
        self.assertEqual("PASS", summary["matrix"]["H7"]["status"])
        self.assertEqual("UNKNOWN",
                         summary["matrix"]["H7"]["evidence"]["state"])
        self.assertNotEqual("PASS", summary["matrix"]["H7"]["evidence"].get(
            "resend"))

    def test_unknown_transport_does_not_complete(self):
        summary = self.driver().run(("H8",))
        self.assertEqual("PASS", summary["matrix"]["H8"]["status"])
        self.assertEqual("UNKNOWN",
                         summary["matrix"]["H8"]["evidence"]["state"])

    def test_ownership_conflict_blocks_second_reservation(self):
        summary = self.driver().run(("H9",))
        self.assertEqual("PASS", summary["matrix"]["H9"]["status"])
        self.assertEqual("CONFLICT", summary["matrix"]["H9"]["code"])

    def test_divergent_attestation_is_rejected(self):
        summary = self.driver(core=FakeCore("divergent_attestation")).run(("H10",))
        self.assertEqual("PASS", summary["matrix"]["H10"]["status"])
        self.assertEqual("SERVICE_REJECTED",
                         summary["matrix"]["H10"]["code"])

    def test_internal_failure_is_sanitized(self):
        summary = self.driver(core=FakeCore("internal")).run(("H12",))
        self.assertEqual("PASS", summary["matrix"]["H12"]["status"])
        self.assertEqual("SERVICE_ERROR", summary["matrix"]["H12"]["code"])
        self.assertEqual("INTERNAL",
                         summary["matrix"]["H12"]["evidence"]["reason"])
        text = common.canonical_json(summary)
        self.assertNotIn("/private/path", text)
        self.assertNotIn("Traceback", text)

    def test_role_separation_allows_and_denies_actions(self):
        core = FakeCore()
        roles = {
            "coordinator": FakeClient(core, "coordinator"),
            "executor": FakeClient(core, "executor"),
            "observer": FakeClient(core, "observer"),
            "maintainer": FakeClient(core, "maintainer"),
        }
        summary = self.driver(core=core, role_clients=roles).run(("H3b",))
        self.assertEqual("PASS", summary["matrix"]["H3b"]["status"])
        self.assertEqual("PERMISSION_DENIED",
                         summary["matrix"]["H3b"]["evidence"]["denied"][
                             "coordinator"])
        self.assertEqual("PERMISSION_DENIED",
                         summary["matrix"]["H3b"]["evidence"]["denied"][
                             "observer"])
        self.assertEqual("PERMISSION_DENIED",
                         summary["matrix"]["H3b"]["evidence"]["denied"][
                             "maintainer"])

    def test_signer_helper_protocol_has_no_private_key_in_output(self):
        trust_store = os.path.join(self.root, "trust-store.json")
        svc.write_trust_store(trust_store, self.signer.allowlist)
        command = [
            sys.executable, SCRIPT, "--mode", "synthetic",
            "--role", "signer-helper", "--key-dir", self.signer.directory,
            "--trust-store", trust_store,
        ]
        signer = p9.SubprocessSigner(command)
        mission = self.driver()._mission("SIGNER")
        artifact = signer.authorization(
            mission=mission, model=p9.MODEL, environment="SHADOW", now=NOW)
        self.assertIn("envelope", artifact)
        self.assertIn("signature", artifact)
        self.assertNotIn("private", json.dumps(artifact).lower())
        self.assertNotIn("BEGIN OPENSSH", json.dumps(artifact))
        self.assertEqual(64, len(artifact["authorization_digest"]))

    def test_cli_rejects_non_synthetic_mode(self):
        result = subprocess.run(
            [sys.executable, SCRIPT, "--mode", "real"],
            capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, result.returncode)

    def test_matrix_marks_environment_and_real_gates_blocked(self):
        summary = self.driver().run(("H3",))
        for gate in ("H0", "H1", "H2", "H11", "H13", "H15"):
            self.assertEqual("BLOCKED", summary["matrix"][gate]["status"])
        self.assertEqual("PASS", summary["matrix"]["H14"]["status"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
