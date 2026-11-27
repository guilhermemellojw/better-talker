#!/usr/bin/env python3
"""P13: fechamento dos bloqueantes de comunicação (fail-first host-only)."""
from __future__ import annotations

import fcntl
import json
import os
import sys
import tempfile
import unittest
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timedelta, timezone

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint
import coord_communication as cc
import coord_pilot as pilot
import test_pilot_contract as tc

SID = tc.SID
ENDPOINT = tc.ENDPOINT
OP = "OP-COORD04-PILOT-001"
MISSION = "MISS-COORD04-PILOT-001"


def write_json(path, value):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False)


def consumer_file(td, *, lock_path, auth="AUTH-P3B7", session_id=SID, endpoint=ENDPOINT):
    now = datetime.now(timezone.utc)
    path = os.path.join(td, "consumer-p13.json")
    write_json(path, {
        "consumer_id": "coord-pilot-executor",
        "authorization_reference": auth,
        "session_id": session_id,
        "endpoint": endpoint,
        "acquired_at": (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
        "expires_at": (now + timedelta(minutes=30)).isoformat().replace("+00:00", "Z"),
        "lock_scope": lock_path,
    })
    return path


def report_envelope(reply_to: str):
    payload = {
        "schema_version": cc.REPORT_SCHEMA_VERSION,
        "project_id": pilot.EXPECTED_PROJECT_ID,
        "mission_id": MISSION,
        "operation_id": OP,
        "agent_id": "build",
        "session_id": SID,
        "message_id": "declared-by-model",
        "status": "COMPLETED",
        "head_sha": None,
        "branch": None,
        "test_evidence": [],
        "blockers": [],
        "artifacts": [],
        "completed_at": cc.iso(datetime.now(timezone.utc) + timedelta(minutes=1)),
        "acceptance_results": {
            "identidade da missão devolvida": True,
            "status explícito": True,
            "marcador BT-COORD-REPORT:v1": True,
        },
    }
    text = coord_checkpoint.REPORT_CONTRACT + "\ncontract: " + cc.canonical_json(payload)
    return {"id": "msg-report-p13", "type": "assistant", "agent": "build",
            "sessionID": SID, "reply_to": reply_to,
            "content": [{"type": "text", "text": text}]}


class CatalogFailClosedTests(unittest.TestCase):
    def _catalog(self, mutate):
        value = deepcopy(tc.CATALOG)
        mutate(value["data"][0])
        return value

    def _check(self, mutate):
        return pilot.verify_model_catalog(
            self._catalog(mutate), provider_id="opencode", model_id="hy4-flash-free",
            variant="none")

    def test_empty_or_missing_cost_is_not_free(self):
        self.assertFalse(self._check(lambda row: row.__setitem__("cost", []))["confirmed"])
        self.assertFalse(self._check(lambda row: row.pop("cost"))["confirmed"])

    def test_boolean_or_invalid_cost_is_not_free(self):
        self.assertFalse(self._check(lambda row: row.__setitem__(
            "cost", [{"input": False, "output": False, "cache": {"read": 0, "write": 0}}]))[
            "confirmed"])
        self.assertFalse(self._check(lambda row: row.__setitem__("cost", "free"))["confirmed"])
        self.assertFalse(self._check(lambda row: row.__setitem__(
            "cost", [{"input": 0, "output": 1, "cache": {"read": 0, "write": 0}}]))[
            "confirmed"])

    def test_missing_or_invalid_active_fails_closed(self):
        self.assertFalse(self._check(lambda row: row.pop("active"))["confirmed"])
        self.assertFalse(self._check(lambda row: row.__setitem__("active", "yes"))["confirmed"])
        self.assertFalse(self._check(lambda row: row.__setitem__("active", False))["confirmed"])
        self.assertFalse(self._check(lambda row: row.__setitem__("enabled", False))["confirmed"])

    def test_valid_zero_cost_remains_confirmed(self):
        self.assertTrue(self._check(lambda row: None)["confirmed"])


class ApprovalBindingTests(unittest.TestCase):
    def test_approver_and_reference_are_material(self):
        with tempfile.TemporaryDirectory() as td:
            _, _, _, proposal = tc.StandardPathTests()._setup(td)
            auth, manifest, approval, binding = tc.approved_for_proposal(proposal)
            clock = datetime.now(timezone.utc)
            approval.validate(clock, require_real=True)
            with self.assertRaises(cc.AuthorizationError):
                replace(approval, approver_id="outro").validate(clock, require_real=True)
            with self.assertRaises(cc.AuthorizationError):
                replace(approval, approval_binding=dict(binding, approver_id="outro")).validate(
                    clock, require_real=True)
            with self.assertRaises(cc.AuthorizationError):
                replace(approval, approval_binding=dict(binding, approval_reference="OUTRA")).validate(
                    clock, require_real=True)
            with self.assertRaises(cc.AuthorizationError):
                replace(approval, approval_binding=dict(binding, manifest_sha256="0" * 64)).validate(
                    clock, require_real=True)
            changed = replace(manifest, session_cost=7)
            with self.assertRaises(cc.AuthorizationError):
                replace(approval, manifest=changed).validate(clock, require_real=True)


class AdmissionEnforcementTests(unittest.TestCase):
    def _executor_for(self, td, proposal, manifest, client):
        auth = replace(tc.authorization_from_proposal(proposal), maintainer_id="mantenedor")
        binding = {
            "approver_id": "mantenedor", "approver_type": "human",
            "environment": "APPROVED", "approval_reference": auth.approval_reference,
            "manifest_sha256": manifest.canonical(),
            "bound_material_sha256": manifest.canonical(),
        }
        approval = pilot.PilotApproval(
            "APP", "mantenedor", "human", "APPROVED", manifest,
            approval_binding=binding)
        transport = pilot.OpenCodeTransport(
            client, expected_instance_id=auth.instance_id,
            registered_targets=(auth.target,), pilot_authorization=auth,
            mode="real", allow_write=True, clock=lambda: datetime.now(timezone.utc))
        return pilot.PilotExecutor(
            transport, pilot.PilotOperationStore(os.path.join(td, "admission")), approval,
            endpoint=ENDPOINT, allow_real=True,
            consumer_evidence_path=os.path.join(td, "consumer.json"))

    def test_false_admission_and_exclusivity_have_zero_post(self):
        for field in ("admission_authorized", "exclusive_consumer"):
            with self.subTest(field=field):
                with tempfile.TemporaryDirectory() as td:
                    client, _, _, proposal = tc.StandardPathTests()._setup(td)
                    _, manifest, _, _ = tc.approved_for_proposal(proposal)
                    executor = self._executor_for(
                        td, proposal, replace(manifest, **{field: False}), client)
                    with self.assertRaises(cc.AuthorizationError):
                        executor.execute(tc.mission_from_proposal(proposal),
                                         tc.core_auth_from_proposal(proposal),
                                         pilot.PILOT_PROMPT)
                    self.assertEqual([c for c in client.calls if c[0] == "POST"], [])


class ConsumerEvidenceTests(unittest.TestCase):
    def test_lock_must_be_held_and_authorization_must_match(self):
        with tempfile.TemporaryDirectory() as td:
            lock_path = os.path.join(td, "consumer.lock")
            with open(lock_path, "w", encoding="utf-8") as handle:
                handle.write("lock")
            path = consumer_file(td, lock_path=lock_path)
            released = pilot.verify_consumer_evidence(
                path, session_id=SID, endpoint=ENDPOINT)
            self.assertFalse(released["verified"])
            self.assertIn("não adquirido", released["reason"])
            descriptor = os.open(lock_path, os.O_RDWR)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                held = pilot.verify_consumer_evidence(path, session_id=SID, endpoint=ENDPOINT)
                self.assertTrue(held["verified"])
                wrong = pilot.verify_consumer_evidence(
                    path, session_id=SID, endpoint=ENDPOINT,
                    authorization_reference="OUTRA-AUTORIZACAO")
                self.assertFalse(wrong["verified"])
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)
            after = pilot.verify_consumer_evidence(path, session_id=SID, endpoint=ENDPOINT)
            self.assertFalse(after["verified"])

    def test_directory_is_not_a_lock(self):
        with tempfile.TemporaryDirectory() as td:
            path = consumer_file(td, lock_path=td)
            result = pilot.verify_consumer_evidence(path, session_id=SID, endpoint=ENDPOINT)
            self.assertFalse(result["verified"])
            self.assertIn("não é arquivo regular", result["reason"])


class UnknownReconciliationTests(unittest.TestCase):
    def test_divergent_receipt_can_reconcile_with_observed_prompt(self):
        with tempfile.TemporaryDirectory() as td:
            client, _, _, proposal = tc.StandardPathTests()._setup(td)
            observed_id = "msg-pilot-observed"
            client.responses[("POST", f"/api/session/{SID}{pilot.PROMPT_SUFFIX}")] = (
                202, {"data": {"id": observed_id}})
            auth, manifest, approval, _ = tc.approved_for_proposal(proposal)
            transport = pilot.OpenCodeTransport(
                client, expected_instance_id=auth.instance_id,
                registered_targets=(auth.target,), pilot_authorization=auth,
                mode="real", allow_write=True, clock=lambda: datetime.now(timezone.utc))
            store = pilot.PilotOperationStore(os.path.join(td, "unknown"))
            executor = pilot.PilotExecutor(
                transport, store, approval, endpoint=ENDPOINT, allow_real=True,
                consumer_evidence_path=os.path.join(td, "consumer.json"))
            first = executor.execute(tc.mission_from_proposal(proposal),
                                     tc.core_auth_from_proposal(proposal),
                                     pilot.PILOT_PROMPT)
            self.assertEqual(first["state"], "UNKNOWN")
            messages = [
                {"id": observed_id, "type": "user",
                 "content": [{"type": "text", "text": pilot.PILOT_PROMPT}]},
                report_envelope(observed_id),
            ]
            client.responses[("GET",
                              f"/api/session/{SID}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc")] = (
                200, {"data": messages})
            reconciled = executor.reconcile()
            self.assertEqual(reconciled["state"], "COMPLETED")
            self.assertEqual(reconciled["reports"], 1)
            self.assertEqual(store.load().state, "COMPLETED")
            self.assertEqual(len([c for c in client.calls if c[0] == "POST"]), 1)


if __name__ == "__main__":
    unittest.main()
