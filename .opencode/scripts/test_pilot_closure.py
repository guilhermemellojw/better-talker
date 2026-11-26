#!/usr/bin/env python3
"""Fail-first e regressões de fechamento operacional COORD-04/P3A.2."""
from __future__ import annotations

import builtins
import hashlib
import json
import os
import socket
import sys
import tempfile
import unittest
import urllib.request
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
TARGET = cc.TargetIdentity("better-talker", "guilhermemellojw/better-talker", "A",
                           "ses_defc05853ffeej4fPXrp7frx4x", "instance-1",
                           "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow")
PROMPT = getattr(pilot, "PILOT_PROMPT", (
    "MISS-COORD04-PILOT-001 / OP-COORD04-PILOT-001. Responda somente em texto "
    "estruturado, sem ferramentas, sem edição, sem shell, sem Git, sem operações em "
    "dispositivos e sem acesso a credenciais. Inclua o marcador <!-- BT-COORD-REPORT:v1 --> "
    "e uma linha contract: com JSON contendo schema_version, project_id, mission_id, "
    "operation_id, agent_id, session_id, status, head_sha null, branch null, "
    "test_evidence [], blockers [], artifacts [], completed_at e acceptance_results."))
PROMPT_ID = "msg-pilot-OP-COORD04-PILOT-001"
MISSION_ID = "MISS-COORD04-PILOT-001"
OPERATION_ID = "OP-COORD04-PILOT-001"
ENDPOINT = "http://127.0.0.1:49999"


def mission(**changes):
    payload = {"prompt": PROMPT}
    values = {"schema_version": cc.MISSION_SCHEMA_VERSION, "project_id": TARGET.project_id,
              "repository_id": TARGET.repository_id, "mission_id": MISSION_ID,
              "operation_id": OPERATION_ID, "coordinator_id": "supervisor-shadow-experimental",
              "target_agent": TARGET.agent_id, "target_session_id": TARGET.session_id,
              "target_instance_id": TARGET.instance_id, "target_location": TARGET.location,
              "objective": "Validar comunicação bidirecional real em sessão experimental",
              "payload": payload, "payload_sha256": cc.payload_sha256(payload),
              "scope": "piloto de comunicação experimental",
              "expected_deliverables": ("resposta estruturada",),
              "acceptance_criteria": ("identidade da missão devolvida", "status explícito",
                                      "marcador v1"),
              "ownership_constraints": ("somente sessão experimental",),
              "authorization_reference": "AUTH-COORD04-PILOT-001", "created_at": NOW,
              "expires_at": NOW + timedelta(minutes=30), "timeout": 60,
              "expected_head_sha": None, "expected_branch": None}
    values.update(changes)
    return cc.MissionEnvelope(**values)


def core_auth(m=None, **changes):
    m = m or mission()
    values = {"reference": m.authorization_reference, "project_id": m.project_id,
              "repository_id": m.repository_id, "target": m.target,
              "payload_sha256": m.payload_sha256,
              "mission_identity": cc.canonical_mission_identity(m), "scope": m.scope,
              "issued_at": NOW, "expires_at": NOW + timedelta(hours=1), "synthetic": True}
    values.update(changes)
    return cc.Authorization(**values)


def pilot_auth(m=None, instance="instance-1", **changes):
    m = m or mission()
    values = {"authorization_id": "AUTH-COORD04-PILOT-001", "maintainer_id": "mantenedor-humano",
              "approval_reference": "DECISAO-PILOT-001", "project_id": m.project_id,
              "repository_id": m.repository_id, "instance_id": instance, "target": TARGET,
              "mission_id": m.mission_id, "mission_identity": cc.canonical_mission_identity(m),
              "operation_id": m.operation_id, "prompt_sha256": pilot.sha256_text(PROMPT),
              "scope": m.scope, "issued_at": NOW, "expires_at": NOW + timedelta(hours=1),
              "approved": True, "environment": "FIXTURE", "max_transmissions": 1,
              "expected_model": "opencode-go/hungerg-free#default"}  # fixture histórica sintética; não é a política HY4
    values.update(changes)
    return pilot.PilotAuthorization(**values)


class FakeClient(pilot.HttpClient):
    def __init__(self):
        self.responses = {}
        self.calls = []
        self.base_url = ENDPOINT

    def set(self, method, path, status, payload):
        self.responses[(method, path)] = (status, payload)

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return self.responses.get((method, path), (404, {"error": "not_found"}))


INFO = {"version": "2.3.2", "pid": 1, "hostname": "fixture", "urls": [ENDPOINT]}


def fake_client(*, messages=None, prompt_status=202, prompt_body=None, inbox=None,
                active=None, info=None, instance="instance-1"):
    client = FakeClient()
    client.set("GET", pilot.INFO_PATH, 200, info or INFO)
    client.set("GET", pilot.LOCATION_PATH, 200,
               {"directory": TARGET.location,
                "project": {"id": TARGET.project_id, "directory": TARGET.location,
                            "canonical": TARGET.location}})
    client.set("GET", f"/api/session/{TARGET.session_id}", 200,
               {"data": {"id": TARGET.session_id, "projectID": TARGET.project_id,
                         "agent": TARGET.agent_id,
                         "model": {"providerID": "opencode-go", "id": "hungerg-free",
                                   "variant": "default"},
                         "time": {"busy": 1, "idle": 2}, "outcome": "succeeded",
                         "location": {"directory": TARGET.location}}})
    client.set("GET", pilot.ACTIVE_PATH, 200, {"data": active or {}})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.INBOX_SUFFIX}", 200,
               {"data": inbox or []})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc", 200,
               {"data": messages or []})
    client.set("POST", f"/api/session/{TARGET.session_id}{pilot.PROMPT_SUFFIX}", prompt_status,
               prompt_body if prompt_body is not None else {"data": {"id": PROMPT_ID}})
    return client


def build_manifest(m=None, auth=None, **changes):
    m = m or mission()
    auth = auth or pilot_auth(m)
    return pilot.PilotManifest(
        reviewed_head_sha=pilot.executed_head_sha(),
        operational_digests=tuple(sorted(pilot.operational_file_digests().items())),
        mission_identity=cc.canonical_mission_identity(m),
        authorization_identity=auth.canonical(),
        authorization_id=auth.authorization_id, maintainer_id=auth.maintainer_id,
        approval_reference=auth.approval_reference, target_key=tuple(auth.target.key()),
        instance_id=auth.instance_id, model=auth.expected_model, endpoint=ENDPOINT,
        operation_id=m.operation_id, prompt_sha256=pilot.sha256_text(PROMPT),
        issued_at=NOW, expires_at=NOW + timedelta(hours=1), max_transmissions=1)


def approval(m=None, auth=None, *, environment="FIXTURE", **changes):
    m = m or mission()
    auth = auth or pilot_auth(m)
    manifest = build_manifest(m, auth)
    values = {"approval_id": "APPROVAL-HUMAN-001", "approver_id": "mantenedor-humano",
              "approver_type": "human", "environment": environment, "manifest": manifest}
    values.update(changes)
    return pilot.PilotApproval(**values)


def build_executor(client, *, store_dir, mode="fixture", environment="FIXTURE", allow_real=False):
    m = mission()
    auth = pilot_auth(m, instance=INFO["pid"] and pilot.instance_identity(INFO))
    auth = replace(auth, instance_id=pilot.instance_identity(INFO),
                   environment="APPROVED" if environment == "APPROVED" else "FIXTURE")
    transport = pilot.OpenCodeTransport(
        client, expected_instance_id=pilot.instance_identity(INFO),
        registered_targets=(auth.target,), pilot_authorization=auth,
        allow_write=True, clock=lambda: NOW, mode=mode)
    store = pilot.PilotOperationStore(store_dir)
    appr = approval(m, auth, environment=environment)
    if environment == "APPROVED":
        appr = replace(appr, manifest=replace(appr.manifest, reviewed_head_sha=pilot.executed_head_sha()))
    ex = pilot.PilotExecutor(transport, store, appr, endpoint=ENDPOINT,
                             allow_real=allow_real, clock=lambda: NOW)
    return m, auth, transport, store, ex


def assistant_report(*, status="COMPLETED", author=TARGET.agent_id, reply_to=PROMPT_ID,
                     operation_id=OPERATION_ID, message_id="msg-report-pilot-001",
                     acceptance=None, include_contract=True):
    results = {"identidade da missão devolvida": True, "status explícito": True,
               "marcador v1": True}
    if acceptance is not None:
        results = acceptance
    payload = {"schema_version": cc.REPORT_SCHEMA_VERSION, "project_id": TARGET.project_id,
               "mission_id": MISSION_ID, "operation_id": operation_id, "agent_id": author,
               "session_id": TARGET.session_id, "message_id": "model-invented-id",
               "status": status, "head_sha": None, "branch": None, "test_evidence": [],
               "blockers": [], "artifacts": [],
               "completed_at": cc.iso(NOW + timedelta(minutes=1)),
               "acceptance_results": results}
    text = (coord_checkpoint.REPORT_CONTRACT + "\ncontract: " + cc.canonical_json(payload)) \
        if include_contract else "resposta sem contrato"
    item = {"id": message_id, "type": "assistant", "agent": author,
            "sessionID": TARGET.session_id,
            "content": [{"type": "text", "text": text}]}
    if reply_to is not None:
        item["reply_to"] = reply_to
    return item


class F2ManifestTests(unittest.TestCase):
    def test_f2_future_issued_at_rejected(self):
        m = mission()
        manifest = build_manifest(m)
        future = replace(manifest, issued_at=NOW + timedelta(hours=1),
                         expires_at=NOW + timedelta(hours=2))
        appr = approval(m, environment="FIXTURE", manifest=future)
        with tempfile.TemporaryDirectory() as td:
            client = fake_client()
            _, _, _, _, ex = build_executor(client, store_dir=td)
            ex.approval = appr
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, core_auth(m), PROMPT)

    def test_f2_divergent_head_rejected(self):
        m = mission()
        appr = approval(m, environment="FIXTURE",
                        manifest=replace(build_manifest(m), reviewed_head_sha="b" * 40))
        with tempfile.TemporaryDirectory() as td:
            _, _, _, _, ex = build_executor(fake_client(), store_dir=td)
            ex.approval = appr
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, core_auth(m), PROMPT)

    def test_f2_changed_authorization_identity_fields_rejected(self):
        m = mission()
        base = build_manifest(m)
        for field in ("authorization_id", "maintainer_id", "approval_reference",
                      "authorization_identity", "mission_identity", "model", "endpoint"):
            with self.subTest(field=field):
                if field == "endpoint":
                    # P3B.0: endpoint inválido é rejeitado na construção do manifesto.
                    with self.assertRaises(cc.ContractError):
                        replace(base, endpoint="http://externo.example.com/prompt")
                    continue
                changed = replace(base, **{field: "0" * 64 if field.endswith("identity")
                                           else "alterado"})
                appr = approval(m, environment="FIXTURE", manifest=changed)
                with tempfile.TemporaryDirectory() as td:
                    _, _, _, _, ex = build_executor(fake_client(), store_dir=td)
                    ex.approval = appr
                    with self.assertRaises(cc.ContractError):
                        ex.execute(m, core_auth(m), PROMPT)

    def test_f2_extended_expires_at_rejected(self):
        m = mission()
        manifest = replace(build_manifest(m), expires_at=NOW + timedelta(hours=5))
        appr = approval(m, environment="FIXTURE", manifest=manifest)
        with tempfile.TemporaryDirectory() as td:
            _, _, _, _, ex = build_executor(fake_client(), store_dir=td)
            ex.approval = appr
            with self.assertRaises((cc.AuthorizationError, cc.ContractError)):
                ex.execute(m, core_auth(m), PROMPT)

    def test_f2_operational_files_bound(self):
        digests = pilot.operational_file_digests()
        self.assertIn("coord_pilot.py", digests)
        self.assertEqual(len(digests["coord_pilot.py"]), 64)
        self.assertEqual(pilot.executed_head_sha(), pilot.executed_head_sha())


class F4ReconcileTests(unittest.TestCase):
    def _run_with_report(self, item):
        user = {"id": PROMPT_ID, "type": "user",
                "content": [{"type": "text", "text": PROMPT}]}
        with tempfile.TemporaryDirectory() as td:
            m, auth, transport, store, ex = build_executor(
                fake_client(messages=[user, item]), store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            return ex.reconcile(), store.load()

    def test_f4_in_progress_status_not_completed(self):
        result, record = self._run_with_report(assistant_report(status="IN_PROGRESS"))
        self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_failed_blocked_status_not_completed(self):
        for status in ("FAILED", "BLOCKED"):
            with self.subTest(status=status):
                result, record = self._run_with_report(assistant_report(status=status))
                self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_incomplete_acceptance_not_completed(self):
        item = assistant_report(acceptance={"identidade da missão devolvida": True,
                                            "status explícito": False, "marcador v1": True})
        result, record = self._run_with_report(item)
        self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_divergent_operation_not_completed(self):
        item = assistant_report(operation_id="OP-OUTRA")
        result, record = self._run_with_report(item)
        self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_divergent_author_not_completed(self):
        item = assistant_report(author="B")
        result, record = self._run_with_report(item)
        self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_missing_prompt_association_not_completed(self):
        item = assistant_report(reply_to=None)
        result, record = self._run_with_report(item)
        self.assertNotEqual(record.state, "COMPLETED")

    def test_f4_receipt_divergent_message_id_not_confirmed(self):
        with tempfile.TemporaryDirectory() as td:
            client = fake_client(prompt_body={"data": {"id": "msg-divergente"}})
            m, auth, transport, store, ex = build_executor(client, store_dir=td)
            result = ex.execute(m, core_auth(m), PROMPT)
            self.assertFalse(result["receipt_confirmed"])


class F5ParserTests(unittest.TestCase):
    def test_f5_parser_uses_envelope_message_id(self):
        item = assistant_report()
        text = item["content"][0]["text"]
        report = pilot.parse_pilot_report(text, OPERATION_ID, observed_message_id=item["id"])
        self.assertEqual(report.message_id, item["id"])

    def test_f5_exact_prompt_fixture_completes(self):
        item = assistant_report()
        result, record = F4ReconcileTests()._run_with_report(item)
        self.assertEqual(record.state, "COMPLETED")
        self.assertEqual(result["reports"], 1)


class F1ProtectionTests(unittest.TestCase):
    def test_f1_direct_transmit_without_executor_blocked(self):
        client = fake_client()
        m = mission()
        with tempfile.TemporaryDirectory() as td:
            _, _, transport, _, _ = build_executor(client, store_dir=td)
            request = transport.prepare(m, core_auth(m))
            with self.assertRaises(cc.AuthorizationError):
                transport.transmit(request)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_f1_duplicate_request_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            client = fake_client()
            m, auth, transport, store, ex = build_executor(client, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            request = transport.prepare(m, core_auth(m))
            with self.assertRaises(cc.AuthorizationError):
                transport.transmit(request)
            self.assertEqual(len([c for c in client.calls if c[0] == "POST"]), 1)


class F6CliTests(unittest.TestCase):
    def test_f6_cli_commands_offline(self):
        import coord_pilot_cli
        with tempfile.TemporaryDirectory() as td:
            for command in ("preflight", "prepare", "execute", "reconcile", "closeout", "dry-run"):
                with self.subTest(command=command):
                    out = coord_pilot_cli.run(["--store-dir", td, command])
                    self.assertIsInstance(out, dict)
                    self.assertFalse(out.get("real_calls", 0))

    def test_f6_no_real_network(self):
        with tempfile.TemporaryDirectory() as td, \
             patch.object(urllib.request.OpenerDirector, "open", side_effect=AssertionError("rede real")), \
             patch.object(socket, "socket", side_effect=AssertionError("socket real")):
            result = coord_pilot_cli.run(["--store-dir", td, "dry-run"])
            self.assertEqual(result["real_calls"], 0)
            self.assertEqual(result["http_posts"], 1)  # somente cliente falso

    def test_f6_execute_requires_manifest_bound_approval(self):
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "approval.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"environment": "APPROVED", "manifest_sha256": "0" * 64}, handle)
            out = coord_pilot_cli.run(["--store-dir", td, "execute",
                                       "--activate", "--approval", path])
            self.assertFalse(out["executed"])
            self.assertEqual(out["state"], "BLOCKED_APPROVAL_MANIFEST_DIVERGENT")

    def test_f6_reconcile_never_applies_fixture_to_real_record(self):
        with tempfile.TemporaryDirectory() as td:
            m = mission()
            store = pilot.PilotOperationStore(td)
            store.create(pilot.PilotOperationRecord(
                m.mission_id, m.operation_id, "AUTH-REAL", "c" * 64,
                tuple(TARGET.key()), pilot.sha256_text(PROMPT), "DISPATCHING",
                cc.iso(NOW), cc.iso(NOW)))
            out = coord_pilot_cli.run(["--store-dir", td, "reconcile"])
            self.assertFalse(out["changed"])
            self.assertEqual(out["state"], "DISPATCHING")
            self.assertIn("não-fixture", out["reason"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
