#!/usr/bin/env python3
"""Fail-first e regressões do executor integrado COORD-04/P3A.1."""
from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_pilot as pilot

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
TARGET = cc.TargetIdentity("better-talker", "guilhermemellojw/better-talker", "A",
                           "ses_defc05853ffeej4fPXrp7frx4x", "instance-1",
                           "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow")
PROMPT = ("MISS-COORD04-PILOT-001 / OP-COORD04-PILOT-001. Responda somente em texto "
          "estruturado, sem ferramentas, sem edição, sem shell, sem Git, sem operações em "
          "dispositivos e sem acesso a credenciais. Inclua o marcador <!-- BT-COORD-REPORT:v1 --> "
          "e uma linha contract: com JSON contendo schema_version, project_id, mission_id, "
          "operation_id, agent_id, session_id, status, head_sha null, branch null, "
          "test_evidence [], blockers [], artifacts [], completed_at e acceptance_results.")


def mission():
    payload = {"prompt": PROMPT}
    return cc.MissionEnvelope(
        schema_version=cc.MISSION_SCHEMA_VERSION, project_id=TARGET.project_id,
        repository_id=TARGET.repository_id, mission_id="MISS-COORD04-PILOT-001",
        operation_id="OP-COORD04-PILOT-001", coordinator_id="supervisor-shadow-experimental",
        target_agent=TARGET.agent_id, target_session_id=TARGET.session_id,
        target_instance_id=TARGET.instance_id, target_location=TARGET.location,
        objective="Validar comunicação bidirecional real em sessão experimental",
        payload=payload, payload_sha256=cc.payload_sha256(payload), scope="piloto de comunicação experimental",
        expected_deliverables=("resposta estruturada",),
        acceptance_criteria=("identidade da missão devolvida", "status explícito", "marcador v1"),
        ownership_constraints=("somente sessão experimental",),
        authorization_reference="AUTH-COORD04-PILOT-001", created_at=NOW,
        expires_at=NOW + timedelta(minutes=30), timeout=60,
        expected_head_sha=None, expected_branch=None)


def pilot_auth(m=None, **changes):
    m = m or mission()
    values = {"authorization_id": "AUTH-COORD04-PILOT-001", "maintainer_id": "mantenedor-humano",
              "approval_reference": "DECISAO-PILOT-001", "project_id": m.project_id,
              "repository_id": m.repository_id, "instance_id": m.target_instance_id,
              "target": m.target, "mission_id": m.mission_id,
              "mission_identity": cc.canonical_mission_identity(m),
              "operation_id": m.operation_id, "prompt_sha256": pilot.sha256_text(PROMPT),
              "scope": m.scope, "issued_at": NOW, "expires_at": NOW + timedelta(hours=1),
              "approved": True, "environment": "FIXTURE", "max_transmissions": 1,
              "expected_model": "opencode-go/hungerg-free#default"}
    values.update(changes)
    return pilot.PilotAuthorization(**values)


def core_auth(m=None, **changes):
    m = m or mission()
    values = {"reference": m.authorization_reference, "project_id": m.project_id,
              "repository_id": m.repository_id, "target": m.target,
              "payload_sha256": m.payload_sha256,
              "mission_identity": cc.canonical_mission_identity(m), "scope": m.scope,
              "issued_at": NOW, "expires_at": NOW + timedelta(hours=1), "synthetic": True}
    values.update(changes)
    return cc.Authorization(**values)


class FakeClient(pilot.HttpClient):
    def __init__(self):
        self.responses = {}
        self.calls = []

    def set(self, method, path, status, payload):
        self.responses[(method, path)] = (status, payload)

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return self.responses.get((method, path), (404, {"error": "not_found"}))


def fake_client(report=None, *, prompt_status=202, prompt_body=None, inbox=None, active=None):
    client = FakeClient()
    client.set("GET", pilot.INFO_PATH, 200,
               {"version": "2.3.2", "pid": 1, "hostname": "fixture",
                "urls": ["http://127.0.0.1:49999"]})
    instance = pilot.instance_identity({"version": "2.3.2", "pid": 1, "hostname": "fixture",
                                        "urls": ["http://127.0.0.1:49999"]})
    client.set("GET", pilot.LOCATION_PATH, 200,
               {"directory": TARGET.location,
                "project": {"id": TARGET.project_id, "directory": TARGET.location,
                            "canonical": TARGET.location}})
    client.set("GET", f"/api/session/{TARGET.session_id}", 200,
               {"data": {"id": TARGET.session_id, "projectID": TARGET.project_id,
                         "agent": TARGET.agent_id, "model": {"providerID": "opencode-go",
                         "id": "hungerg-free", "variant": "default"},
                         "time": {"busy": 1, "idle": 2}, "outcome": "succeeded",
                         "location": {"directory": TARGET.location}}})
    client.set("GET", pilot.ACTIVE_PATH, 200, {"data": active or {}})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.INBOX_SUFFIX}", 200,
               {"data": inbox or []})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc", 200,
               {"data": report or []})
    client.set("POST", f"/api/session/{TARGET.session_id}{pilot.PROMPT_SUFFIX}", prompt_status,
               prompt_body if prompt_body is not None else {"data": {"id": "msg-pilot-OP-COORD04-PILOT-001"}})
    return client, instance


def approval():
    m = mission()
    manifest = cc.canonical_json({
        "mission_identity": cc.canonical_mission_identity(m),
        "operation_id": m.operation_id, "session_id": TARGET.session_id,
        "prompt_sha256": pilot.sha256_text(PROMPT)})
    return pilot.PilotApproval(
        approval_id="APPROVAL-HUMAN-001", approver_id="mantenedor-humano",
        approver_type="human", approval_reference="DECISAO-PILOT-001",
        reviewed_head_sha="a" * 40,
        manifest_sha256=hashlib.sha256(manifest.encode("utf-8")).hexdigest(),
        operation_id=m.operation_id, session_id=TARGET.session_id,
        prompt_sha256=pilot.sha256_text(PROMPT),
        issued_at=NOW, expires_at=NOW + timedelta(hours=1), environment="PROPOSED")


def report_message(operation_id: str, *, status: str = "COMPLETED",
                   agent_id: str = TARGET.agent_id) -> dict:
    text = coord_checkpoint.REPORT_CONTRACT + "\ncontract: " + cc.canonical_json({
        "schema_version": cc.REPORT_SCHEMA_VERSION, "project_id": TARGET.project_id,
        "mission_id": "MISS-COORD04-PILOT-001", "operation_id": operation_id,
        "agent_id": agent_id, "session_id": TARGET.session_id,
        "message_id": "msg-report-pilot-001", "status": status, "head_sha": None,
        "branch": None, "test_evidence": [], "blockers": [], "artifacts": [],
        "completed_at": cc.iso(NOW + timedelta(minutes=1)),
        "acceptance_results": {"identidade da missão devolvida": True,
                               "status explícito": True, "marcador v1": True}})
    return {"id": "msg-report-pilot-001", "type": "assistant",
            "agent": agent_id, "content": [{"type": "text", "text": text}]}


def build_executor(client, instance, *, store_dir, mode="fixture", allow_real=False,
                   auth_env="FIXTURE"):
    m = mission()
    transport = pilot.OpenCodeTransport(
        client, expected_instance_id=instance, registered_targets=(TARGET,),
        pilot_authorization=pilot_auth(m, instance_id=instance, environment=auth_env),
        allow_write=True, clock=lambda: NOW, mode=mode)
    store = pilot.PilotOperationStore(store_dir)
    appr = approval()
    if auth_env == "APPROVED":
        appr = replace(appr, environment="APPROVED")
    return m, transport, store, pilot.PilotExecutor(transport, store, appr,
                                                    allow_real=allow_real, clock=lambda: NOW)


class ExecutorIntegrationTests(unittest.TestCase):
    def test_f1_persists_dispatching_before_post(self):
        client, instance = fake_client()
        m = mission()
        with tempfile.TemporaryDirectory() as td:
            transport = pilot.OpenCodeTransport(client, expected_instance_id=instance,
                                                registered_targets=(TARGET,),
                                                pilot_authorization=pilot_auth(m, instance_id=instance),
                                                allow_write=True, clock=lambda: NOW)
            store = pilot.PilotOperationStore(td)
            ex = pilot.PilotExecutor(transport, store, approval(), allow_real=False, clock=lambda: NOW)
            result = ex.execute(m, core_auth(m), PROMPT)
            events = [h["state"] for h in store.load().history]
            posts = [c for c in client.calls if c[0] == "POST"]
            self.assertLess(events.index("DISPATCHING"), len(events))
            self.assertEqual(len(posts), 1)
            self.assertTrue(result["executed"])

    def test_f2_fixture_cannot_enable_real_http(self):
        client, instance = fake_client()
        m = mission()
        with tempfile.TemporaryDirectory() as td:
            transport = pilot.OpenCodeTransport(client, expected_instance_id=instance,
                                                registered_targets=(TARGET,),
                                                pilot_authorization=pilot_auth(m, instance_id=instance),
                                                allow_write=True, clock=lambda: NOW, mode="real")
            ex = pilot.PilotExecutor(transport, pilot.PilotOperationStore(td), approval(),
                                     allow_real=True, clock=lambda: NOW)
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_f3_url_validation(self):
        for base in ("https://127.0.0.1:1", "http://localhost:1", "http://user@127.0.0.1:1",
                     "http://127.0.0.1:1#frag", "http://127.0.0.1:1?x=1", "http://127.0.0.1:1/api"):
            with self.subTest(base=base), self.assertRaises(cc.ContractError):
                pilot.LocalHttpClient(base, allowed_port=1)

    def test_f4_incomplete_response_stays_unknown(self):
        client, instance = fake_client(prompt_body={"data": {}})
        m = mission()
        with tempfile.TemporaryDirectory() as td:
            transport = pilot.OpenCodeTransport(client, expected_instance_id=instance,
                                                registered_targets=(TARGET,),
                                                pilot_authorization=pilot_auth(m, instance_id=instance),
                                                allow_write=True, clock=lambda: NOW)
            store = pilot.PilotOperationStore(td)
            ex = pilot.PilotExecutor(transport, store, approval(), allow_real=False, clock=lambda: NOW)
            result = ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual(result["state"], "UNKNOWN")
            self.assertFalse(result["receipt_confirmed"])
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, core_auth(m), PROMPT)

    def test_f5_prompt_parser_matches_exact_prompt(self):
        text = coord_checkpoint.REPORT_CONTRACT + "\ncontract: " + cc.canonical_json({
            "schema_version": cc.REPORT_SCHEMA_VERSION, "project_id": TARGET.project_id,
            "mission_id": "MISS-COORD04-PILOT-001", "operation_id": "OP-COORD04-PILOT-001",
            "agent_id": TARGET.agent_id, "session_id": TARGET.session_id,
            "message_id": "model-invented", "status": "COMPLETED", "head_sha": None,
            "branch": None, "test_evidence": [], "blockers": [], "artifacts": [],
            "completed_at": cc.iso(NOW + timedelta(minutes=1)),
            "acceptance_results": {"identidade da missão devolvida": True,
                                   "status explícito": True, "marcador v1": True}})
        parsed = pilot.parse_pilot_report(text, "OP-COORD04-PILOT-001")
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.operation_id, "OP-COORD04-PILOT-001")

    def test_f6_cli_blocks_execution_by_default(self):
        import coord_pilot_cli
        with tempfile.TemporaryDirectory() as td:
            out = coord_pilot_cli.run(["--store-dir", td, "execute", "--blocked"])
            self.assertFalse(out["executed"])
            self.assertEqual(out["state"], "BLOCKED_BY_DEFAULT")


    def test_no_post_without_persisted_record(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            with patch.object(store, "create", side_effect=OSError("sem disco")):
                with self.assertRaises(OSError):
                    ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_no_post_without_reservation(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            with patch.object(store, "reserve", side_effect=cc.ContractError("reserva ocupada")):
                with self.assertRaises(cc.ContractError):
                    ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_no_post_with_invalid_authorization(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, core_auth(m, revoked=True), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_no_post_for_divergent_destination(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            other = replace(m, target_session_id="ses_outra")
            with self.assertRaises(cc.ContractError):
                ex.execute(other, core_auth(m), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_disk_failure_before_post_blocks_transmission(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            with patch.object(store, "_write", side_effect=OSError("disco")):
                with self.assertRaises(OSError):
                    ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_single_authorized_attempt(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            posts = [c for c in client.calls if c[0] == "POST"]
            self.assertEqual(len(posts), 1)

    def test_timeout_after_post_yields_unknown(self):
        client, instance = fake_client(prompt_status=504, prompt_body={"error": "gateway"})
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            result = ex.execute(m, core_auth(m), PROMPT)
            self.assertEqual(result["state"], "UNKNOWN")
            self.assertEqual(store.load().state, "UNKNOWN")

    def test_interruption_after_dispatching_blocks_resend(self):
        with tempfile.TemporaryDirectory() as td:
            store = pilot.PilotOperationStore(td)
            a = approval()
            store.create(pilot.PilotOperationRecord(
                "MISS-COORD04-PILOT-001", "OP-COORD04-PILOT-001", a.approval_id,
                pilot_auth().canonical(), tuple(TARGET.key()), pilot.sha256_text(PROMPT),
                "DISPATCHING", cc.iso(NOW), cc.iso(NOW)))
            recovered = store.recover()
            self.assertEqual(recovered.state, "UNKNOWN")
            with self.assertRaises(cc.AuthorizationError):
                store.assert_transmittable()

    def test_second_process_cannot_consume_authorization(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            second = pilot.PilotOperationStore(td)
            with self.assertRaises(cc.AuthorizationError):
                second.assert_transmittable()
            self.assertEqual(len([c for c in client.calls if c[0] == "POST"]), 1)

    def test_incomplete_evidence_does_not_promote(self):
        client, instance = fake_client()
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            result = ex.reconcile()
            self.assertFalse(result["changed"])
            self.assertEqual(result["state"], "DISPATCHING")

    def test_matching_report_recovered_and_associated(self):
        client, instance = fake_client(report=[report_message("OP-COORD04-PILOT-001")])
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            result = ex.reconcile()
            self.assertEqual(result["state"], "COMPLETED")
            self.assertEqual(result["reports"], 1)

    def test_divergent_report_yields_no_conclusion(self):
        client, instance = fake_client(report=[report_message("OP-OUTRA")])
        with tempfile.TemporaryDirectory() as td:
            m, transport, store, ex = build_executor(client, instance, store_dir=td)
            ex.execute(m, core_auth(m), PROMPT)
            result = ex.reconcile()
            self.assertFalse(result["changed"])

    def test_core_authorization_binding_preserved(self):
        m = mission()
        auth = core_auth(m)
        divergent = replace(auth, payload_sha256="0" * 64)
        with tempfile.TemporaryDirectory() as td:
            client, instance = fake_client()
            _, transport, _, ex = build_executor(client, instance, store_dir=td)
            with self.assertRaises(cc.AuthorizationError):
                ex.execute(m, divergent, PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
