#!/usr/bin/env python3
"""Testes offline do adaptador e persistência do piloto COORD-04/P3A."""
from __future__ import annotations

import builtins
import json
import os
import socket
import sys
import tempfile
import unittest
import urllib.request
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint  # noqa: F401 - compatibilidade integrada
import coord_communication as cc
import coord_pilot as pilot

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
TARGET = cc.TargetIdentity("better-talker", "guilhermemellojw/better-talker", "A",
                           "ses_pilot_experimental", "instance-fixture-1", "/fixture/better-talker")
PROMPT = ("Missão experimental COORD-04/P3B. Responda somente em texto estruturado com "
          "mission_id, operation_id e status. Não use ferramentas.")
MISSION_ID = "MISS-COORD04-PILOT-001"
OPERATION_ID = "OP-COORD04-PILOT-001"


def instance_id(info: dict) -> str:
    import hashlib
    material = {"version": info.get("version"), "pid": info.get("pid"),
                "urls": info.get("urls"), "hostname": info.get("hostname")}
    return hashlib.sha256(cc.canonical_json(material).encode()).hexdigest()


ENDPOINT = "http://127.0.0.1:49999"
INFO = {"version": "2.3.2", "pid": 4242, "hostname": "fixture", "urls": [ENDPOINT]}
INSTANCE_ID = instance_id(INFO)


class FakeHttpClient(pilot.HttpClient):
    endpoint = ENDPOINT

    def __init__(self):
        self.responses: dict[tuple[str, str], tuple[int, dict]] = {}
        self.calls: list[tuple[str, str, object]] = []

    def set(self, method: str, path: str, status: int, payload: dict) -> None:
        self.responses[(method.upper(), path)] = (status, payload)

    def request(self, method: str, path: str, body=None):
        self.calls.append((method.upper(), path, body))
        return self.responses.get((method.upper(), path), (404, {"error": "not_found"}))


def mission(**changes):
    payload = {"prompt": PROMPT}
    values = {"schema_version": cc.MISSION_SCHEMA_VERSION,
              "project_id": TARGET.project_id, "repository_id": TARGET.repository_id,
              "mission_id": MISSION_ID, "operation_id": OPERATION_ID,
              "coordinator_id": "supervisor-fixture", "target_agent": TARGET.agent_id,
              "target_session_id": TARGET.session_id, "target_instance_id": TARGET.instance_id,
              "target_location": TARGET.location,
              "objective": "Validar comunicação experimental", "payload": payload,
              "payload_sha256": cc.payload_sha256(payload), "scope": "piloto sintético",
              "expected_deliverables": ("resposta estruturada",),
              "acceptance_criteria": ("missão devolvida",),
              "ownership_constraints": ("somente sessão experimental",),
              "authorization_reference": "AUTH-PILOT-FIXTURE-001",
              "created_at": NOW, "expires_at": NOW + timedelta(minutes=30),
              "timeout": 60, "expected_head_sha": "a" * 40,
              "expected_branch": "fixture/pilot"}
    values.update(changes)
    return cc.MissionEnvelope(**values)


def authorization(m=None, **changes):
    m = m or mission()
    values = {"authorization_id": "AUTH-PILOT-FIXTURE-001", "maintainer_id": "maintainer-fixture",
              "approval_reference": "APPROVAL-PROPOSED-001", "project_id": m.project_id,
              "repository_id": m.repository_id, "instance_id": m.target_instance_id,
              "target": m.target, "mission_id": m.mission_id,
              "mission_identity": cc.canonical_mission_identity(m),
              "operation_id": m.operation_id, "prompt_sha256": pilot.sha256_text(PROMPT),
              "scope": m.scope, "issued_at": NOW, "expires_at": NOW + timedelta(hours=1),
              "approved": True, "environment": "FIXTURE", "max_transmissions": 1,
              "expected_model": "opencode-go/hungerg-free#default"}  # modelo sintético da fixture
    values.update(changes)
    return pilot.PilotAuthorization(**values)


def core_authorization(m=None):
    m = m or mission()
    return cc.Authorization(
        reference=m.authorization_reference, project_id=m.project_id,
        repository_id=m.repository_id, target=m.target,
        payload_sha256=m.payload_sha256, mission_identity=cc.canonical_mission_identity(m),
        scope=m.scope, issued_at=NOW, expires_at=NOW + timedelta(hours=1),
        synthetic=True)


def fake_client(*, active=None, inbox=None, messages=None, location=None, session=None, info=None):
    client = FakeHttpClient()
    client.set("GET", pilot.INFO_PATH, 200, info or INFO)
    client.set("GET", pilot.LOCATION_PATH, 200,
               location or {"directory": TARGET.location,
                            "project": {"id": TARGET.project_id,
                                        "directory": TARGET.location,
                                        "canonical": TARGET.location}})
    client.set("GET", f"/api/session/{TARGET.session_id}", 200,
               {"data": session or {"id": TARGET.session_id, "projectID": TARGET.project_id,
                                    "agent": TARGET.agent_id, "outcome": "succeeded",
                                    "model": {"providerID": "opencode-go", "id": "hungerg-free",
                                              "variant": "default"},
                                    "time": {"busy": 1, "idle": 2},
                                    "location": {"directory": TARGET.location}}})
    client.set("GET", pilot.ACTIVE_PATH, 200, {"data": active or {}})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.INBOX_SUFFIX}", 200,
               {"data": inbox or []})
    client.set("GET", f"/api/session/{TARGET.session_id}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc", 200,
               {"data": messages or []})
    client.set("POST", f"/api/session/{TARGET.session_id}{pilot.PROMPT_SUFFIX}", 202,
               {"data": {"id": f"msg-pilot-{OPERATION_ID}", "type": "user"}})
    return client


def transport(client=None, auth=None, *, allow_write=False, instance_id_value=INSTANCE_ID):
    m = mission()
    return pilot.OpenCodeTransport(client or fake_client(), expected_instance_id=instance_id_value,
                                   registered_targets=(TARGET,), pilot_authorization=auth or authorization(m),
                                   allow_write=allow_write, clock=lambda: NOW)


def bind_guard(t, m=None, auth=None):
    """Vincula reserva + DISPATCHING ao teste de recebimento do adaptador.

    F1 exige que `transmit()` só opere pelo executor autorizado; estes testes
    de evidência de transporte exercitam o caminho protegido com uma guarda
    mínima, sem reabrir a chamada direta.
    """
    m = m or mission()
    auth = auth or core_authorization(m)
    reservation = {"pid": os.getpid(), "created_utc": cc.iso(NOW)}
    store = SimpleNamespace(
        load=lambda: SimpleNamespace(state="DISPATCHING"),
        read_reservation=lambda: reservation,
    )
    t.bind_operation(pilot._OperationGuard(
        operation_id=m.operation_id,
        mission_identity=cc.canonical_mission_identity(m),
        authorization_canonical=cc.canonical_authorization_identity(auth),
        payload_sha256=m.payload_sha256,
        target_key=tuple(m.target.key()),
        store=store, reservation=reservation))
    return t


class AdapterIdentityTests(unittest.TestCase):
    def test_a_correct_identity(self):
        t = transport()
        self.assertEqual(t.lookup_target(TARGET.project_id, TARGET.agent_id, TARGET.session_id), TARGET)

    def test_b_instance_divergent(self):
        t = transport(instance_id_value="0" * 64)
        with self.assertRaises(cc.TransportError):
            t.lookup_target(TARGET.project_id, TARGET.agent_id, TARGET.session_id)

    def test_c_location_divergent(self):
        client = fake_client(location={"directory": "/other", "project": {"id": TARGET.project_id}})
        with self.assertRaises(cc.TransportError):
            transport(client).lookup_target(TARGET.project_id, TARGET.agent_id, TARGET.session_id)

    def test_d_session_divergent(self):
        client = fake_client(session={"id": TARGET.session_id, "projectID": TARGET.project_id,
                                      "agent": "B", "location": {"directory": TARGET.location}})
        with self.assertRaises(cc.TransportError):
            transport(client).lookup_target(TARGET.project_id, TARGET.agent_id, TARGET.session_id)

    def test_h_busy_or_unknown_availability(self):
        busy = transport(fake_client(active={TARGET.session_id: {"type": "running"}}))
        self.assertEqual(busy.status(TARGET).state, "busy")
        unknown = transport(fake_client(session={"id": TARGET.session_id, "projectID": TARGET.project_id,
                                                "agent": TARGET.agent_id, "time": {},
                                                "model": {"providerID": "opencode-go",
                                                          "id": "hungerg-free", "variant": "default"},
                                                "location": {"directory": TARGET.location}}))
        self.assertEqual(unknown.status(TARGET).state, "unknown")

    def test_i_inbox_occupied(self):
        t = transport(fake_client(inbox=[{"id": "msg-pending"}]))
        self.assertEqual(t.status(TARGET).inbox_count, 1)


class AuthorizationTests(unittest.TestCase):
    def test_e_absent_authorization(self):
        t = transport(auth=authorization(approved=False, environment="PROPOSED"))
        with self.assertRaises(cc.AuthorizationError):
            t.prepare(mission(), core_authorization())

    def test_f_synthetic_authorization_cannot_enable_real_transport(self):
        t = transport(auth=authorization(environment="SYNTHETIC", approved=True))
        with self.assertRaises(cc.AuthorizationError):
            t.prepare(mission(), core_authorization())

    def test_g_expired_or_changed_authorization(self):
        expired = transport(auth=authorization(
            issued_at=NOW - timedelta(hours=2), expires_at=NOW - timedelta(hours=1)))
        with self.assertRaises(cc.AuthorizationError):
            expired.prepare(mission(), core_authorization())
        changed = transport(auth=authorization(scope="outro escopo"))
        with self.assertRaises(cc.ContractError):
            changed.prepare(mission(), core_authorization())

    def test_t_destination_change_is_fail_closed(self):
        other = replace(TARGET, session_id="ses_other")
        t = transport(auth=authorization(target=other))
        with self.assertRaises(cc.ContractError):
            t.prepare(mission(), core_authorization())


class TransportEvidenceTests(unittest.TestCase):
    def test_l_inbox_acceptance_is_not_delivery(self):
        t = bind_guard(transport(allow_write=True))
        request = t.prepare(mission(), core_authorization())
        receipt = t.transmit(request)
        self.assertEqual(receipt.outcome, "accepted")
        self.assertTrue(receipt.evidence["queued_in_inbox"])
        self.assertTrue(any(call[0] == "POST" for call in t.client.calls))

    def test_m_recorded_in_session(self):
        user_message = {"id": f"msg-pilot-{OPERATION_ID}", "type": "user",
                        "content": [{"type": "text", "text": PROMPT}]}
        t = transport(fake_client(messages=[user_message]))
        observation = t.operation_status(OPERATION_ID)
        self.assertEqual(observation.evidence_level, "recorded_in_session")
        self.assertTrue(observation.evidence["recorded"])

    def test_n_matching_report(self):
        report = cc.MissionReport(
            schema_version=cc.REPORT_SCHEMA_VERSION, project_id=TARGET.project_id,
            mission_id=MISSION_ID, operation_id=OPERATION_ID, agent_id=TARGET.agent_id,
            session_id=TARGET.session_id, message_id="msg-report-pilot", status="COMPLETED",
            head_sha="a" * 40, branch="fixture/pilot", pull_request=None,
            test_evidence=({"name": "pilot", "result": "PASS"},), blockers=(),
            artifacts=(), completed_at=NOW + timedelta(minutes=1),
            acceptance_results={"missão devolvida": True})
        text = f"{coord_checkpoint.REPORT_CONTRACT}\ncontract: {json.dumps(report.to_dict())}"
        assistant = {"id": "msg-report-pilot", "type": "assistant",
                     "content": [{"type": "text", "text": text}]}
        t = transport(fake_client(messages=[assistant]))
        reports = t.reports(OPERATION_ID)
        self.assertEqual(len(reports), 1)
        self.assertEqual(reports[0].operation_id, OPERATION_ID)

    def test_o_divergent_report_ignored(self):
        t = transport(fake_client(messages=[{"id": "x", "type": "assistant",
                                             "content": [{"type": "text",
                                                          "text": f"{coord_checkpoint.REPORT_CONTRACT}\ncontract: {{}}"}]}]))
        self.assertEqual(t.reports(OPERATION_ID), ())

    def test_p_timeout_after_transmission(self):
        client = fake_client()
        client.set("POST", f"/api/session/{TARGET.session_id}{pilot.PROMPT_SUFFIX}", 500,
                   {"error": "timeout"})
        t = bind_guard(transport(client, allow_write=True))
        request = t.prepare(mission(), core_authorization())
        with self.assertRaises(cc.TransportError):
            t.transmit(request)


class PersistenceTests(unittest.TestCase):
    def _record(self, state="AUTHORIZED"):
        a = authorization()
        return pilot.PilotOperationRecord(MISSION_ID, OPERATION_ID, a.authorization_id,
                                          a.canonical(), tuple(TARGET.key()), a.prompt_sha256,
                                          state, cc.iso(NOW), cc.iso(NOW))

    def test_j_persistence_failure_before_send(self):
        with tempfile.TemporaryDirectory() as td:
            store = pilot.PilotOperationStore(td)
            with patch.object(store, "_write", side_effect=OSError("disk")):
                with self.assertRaises(OSError):
                    store.create(self._record())

    def test_k_concurrent_reservation(self):
        with tempfile.TemporaryDirectory() as td:
            store = pilot.PilotOperationStore(td)
            with store.reserve():
                with self.assertRaises(cc.ContractError):
                    with store.reserve():
                        pass

    def test_q_dispatching_interruption_becomes_unknown(self):
        with tempfile.TemporaryDirectory() as td:
            store = pilot.PilotOperationStore(td)
            store.create(self._record("DISPATCHING"))
            recovered = store.recover()
            self.assertEqual(recovered.state, "UNKNOWN")
            self.assertTrue(recovered.evidence["interruption_unknown"])

    def test_r_restart_recovery_and_s_no_resend(self):
        with tempfile.TemporaryDirectory() as td:
            pilot.PilotOperationStore(td).create(self._record("DISPATCHING"))
            restarted = pilot.PilotOperationStore(td)
            record = restarted.recover()
            self.assertEqual(record.state, "UNKNOWN")
            with self.assertRaises(cc.AuthorizationError):
                restarted.assert_transmittable()

    def test_no_real_http_or_external_calls(self):
        with patch.object(urllib.request.OpenerDirector, "open", side_effect=AssertionError("real HTTP")), \
             patch.object(socket, "socket", side_effect=AssertionError("socket")), \
             patch.object(builtins, "open", side_effect=AssertionError("open")):
            t = transport()
            t.lookup_target(TARGET.project_id, TARGET.agent_id, TARGET.session_id)
            self.assertTrue(all(call[0] == "GET" for call in t.client.calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
