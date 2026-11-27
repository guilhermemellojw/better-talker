#!/usr/bin/env python3
"""Contrato ponta a ponta PADRÃO: prepare → aprovação externa → executor → 1 POST sintético.

Simulação exclusivamente na fronteira de I/O (HttpClient). Nenhum método interno é
substituído; nenhum manifesto é montado à mão.
"""
from __future__ import annotations

import fcntl
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

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli

SID = "ses_de83b0620ffeMIQP5tvH7MLFEx"
ENDPOINT = "http://127.0.0.1:51321"
INTERNAL_PROJECT_ID = "320134d76dfecf3ccd6e6939e946c9f18d32a4f3"
INFO = {"version": "2.3.2", "pid": 972327, "hostname": "guilherme-vaiofe16",
        "urls": [ENDPOINT], "paths": {"tmp": "/tmp/opencode"},
        "capabilities": {"persistentPty": True}}
LOCATION = {"directory": pilot.EXPERIMENTAL_WORKTREE,
            "project": {"id": INTERNAL_PROJECT_ID,
                        "directory": pilot.EXPERIMENTAL_WORKTREE,
                        "canonical": pilot.EXPECTED_CANONICAL_DIR}}
POLICY = [{"action": "*", "resource": "*", "effect": "deny"},
          {"action": "question", "resource": "*", "effect": "allow"}]
SESSION = {"id": SID, "projectID": INTERNAL_PROJECT_ID, "agent": "build",
           "model": {"providerID": "opencode", "id": "hy4-flash-free", "variant": "none"},
           "time": {"created": 1795695114726, "updated": 1795695114726},
           "tokens": {"input": 0, "output": 0, "reasoning": 0,
                      "cache": {"read": 0, "write": 0}},
           "cost": 0,  # Session.Info.cost = Money.USD numérico (contrato real)
           "permissions": POLICY, "title": "COORD-04 experimental - HY4 Flash Free",
           "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}
CATALOG = {"data": [{"id": "hy4-flash-free", "modelID": "hy4-flash-free",
                     "providerID": "opencode", "active": True, "enabled": True,
                     "variants": [{"id": "none"}, {"id": "high"}],
                     "cost": [{"input": 0, "output": 0,
                               "cache": {"read": 0, "write": 0}}],
                     "limit": {"context": 1048576}}]}


class RealShapedClient(pilot.HttpClient):
    """Cliente falso somente na fronteira de I/O; respostas sanitizadas da v2.3.2."""

    def __init__(self, *, session=None, catalog=None, prompt_status=202):
        self.endpoint = ENDPOINT
        self.base_url = ENDPOINT
        self.calls = []
        value = dict(session or SESSION)
        self.responses = {
            ("GET", pilot.INFO_PATH): (200, INFO),
            ("GET", pilot.LOCATION_PATH): (200, LOCATION),
            ("GET", "/api/session"): (200, {"data": [value],
                                            "cursor": {"previous": None, "next": None}}),
            ("GET", f"/api/session/{SID}"): (200, {"data": value}),
            ("GET", pilot.ACTIVE_PATH): (200, {"data": {}}),
            ("GET", f"/api/session/{SID}{pilot.INBOX_SUFFIX}"): (200, {"data": []}),
            ("GET", f"/api/session/{SID}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc"):
                (200, {"data": []}),
            ("GET", f"/api/session/{SID}/permission"): (200, {"data": []}),
            ("GET", "/api/model"): (200, catalog or CATALOG),
            ("POST", f"/api/session/{SID}{pilot.PROMPT_SUFFIX}"):
                (prompt_status, {"data": {"id": "msg-pilot-OP-COORD04-PILOT-001"}}),
        }

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return self.responses.get((method, path), (404, {"error": "not_found"}))


def write_json(path, value):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False)


def authorization_from_proposal(proposal):
    fields = {k: v for k, v in proposal["authorization"].items()
              if k not in ("canonical_sha256",)}
    if isinstance(fields.get("target"), list):
        fields["target"] = cc.TargetIdentity(*fields["target"])
    for key in ("issued_at", "expires_at"):
        if isinstance(fields.get(key), str):
            fields[key] = datetime.fromisoformat(fields[key].replace("Z", "+00:00"))
    return pilot.PilotAuthorization(**fields)


def core_auth_from_proposal(proposal):
    fields = dict(proposal["core_authorization"])
    if isinstance(fields.get("target"), list):
        fields["target"] = cc.TargetIdentity(*fields["target"])
    for key in ("issued_at", "expires_at"):
        if isinstance(fields.get(key), str):
            fields[key] = datetime.fromisoformat(fields[key].replace("Z", "+00:00"))
    fields["synthetic"] = True  # somente neste teste sintético
    return cc.Authorization(**fields)


def mission_from_proposal(proposal):
    fields = dict(proposal["mission"])
    for key in ("created_at", "expires_at"):
        if isinstance(fields.get(key), str):
            fields[key] = datetime.fromisoformat(fields[key].replace("Z", "+00:00"))
    for key in ("expected_deliverables", "acceptance_criteria", "ownership_constraints"):
        if isinstance(fields.get(key), list):
            fields[key] = tuple(fields[key])
    return cc.MissionEnvelope(**fields)


_HELD_LOCKS = []


def hold_lock(path):
    """Mantém um flock real durante o teste (host Linux)."""
    descriptor = os.open(path, os.O_RDWR)
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    _HELD_LOCKS.append(descriptor)


def approved_for_proposal(proposal):
    """Aprovação externa sintética com os campos materiais coerentes.

    O caminho padrão emite proposta não autorizada; este helper representa a
    decisão humana que promove admissão/exclusividade e reconstrói o manifesto
    a partir da autorização aprovada. Nenhuma autoridade real é criada.
    """
    auth = replace(authorization_from_proposal(proposal), maintainer_id="mantenedor")
    manifest_data = proposal["manifest"]
    evidence = {
        "permission_policy_sha256": manifest_data["permission_policy_sha256"],
        "admission_basis": manifest_data["admission_basis"],
        "inbox_evidence": manifest_data["inbox_evidence"],
        "repository_source": manifest_data["repository_source"],
        "admission_authorized": True,
        "exclusive_consumer": True,
        "exclusive_consumer_evidence_sha256": manifest_data[
            "exclusive_consumer_evidence_sha256"],
        "session_cost": manifest_data["session_cost"],
    }
    manifest = pilot.build_runtime_manifest(
        mission_from_proposal(proposal), auth, pilot.sha256_text(pilot.PILOT_PROMPT),
        ENDPOINT, **evidence)
    binding = {
        "approver_id": "mantenedor", "approver_type": "human",
        "environment": "APPROVED", "approval_reference": auth.approval_reference,
        "manifest_sha256": manifest.canonical(),
        "bound_material_sha256": manifest.canonical(),
    }
    approval = pilot.PilotApproval(
        "APP", "mantenedor", "human", "APPROVED", manifest,
        approval_binding=binding)
    return auth, manifest, approval, binding


class StandardPathTests(unittest.TestCase):
    def _setup(self, td, *, client=None, catalog=None):
        client = client or RealShapedClient()
        catalog_path = os.path.join(td, "catalog.json")
        write_json(catalog_path, catalog or CATALOG)
        lock_path = os.path.join(td, "consumer.lock")
        with open(lock_path, "w", encoding="utf-8") as handle:
            handle.write(str(os.getpid()))
        hold_lock(lock_path)
        consumer_path = os.path.join(td, "consumer.json")
        now = datetime.now(timezone.utc)
        write_json(consumer_path, {
            "consumer_id": "coord-pilot-executor", "authorization_reference": "AUTH-P3B7",
            "session_id": SID, "endpoint": ENDPOINT,
            "acquired_at": (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
            "expires_at": (now + timedelta(minutes=30)).isoformat().replace("+00:00", "Z"),
            "lock_scope": lock_path})
        registered = cli._register_session(td, {
            "session_id": SID, "experimental": True, "endpoint": ENDPOINT,
            "instance_id": pilot.instance_identity(INFO),
            "project_id": pilot.EXPECTED_PROJECT_ID,
            "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "build",
            "model": "opencode/hy4-flash-free", "location": pilot.EXPERIMENTAL_WORKTREE,
            "authorization_reference": "AUTH-P3B7",
            "permission_policy_sha256": pilot.canonical_policy_sha256(POLICY)})
        with patch.object(cli, "_real_client", return_value=client):
            proposal = cli.run(["--store-dir", td, "prepare", "--real",
                                "--endpoint", ENDPOINT, "--session", SID,
                                "--catalog", catalog_path,
                                "--consumer-evidence", consumer_path])
        return client, catalog_path, registered, proposal

    def test_standard_path_produces_proposal_approval_and_one_post(self):
        with tempfile.TemporaryDirectory() as td:
            client, catalog_path, _, proposal = self._setup(td)
            self.assertFalse(proposal.get("blocked", False), proposal.get("blockers"))
            self.assertFalse(proposal["manifest"]["admission_authorized"])
            self.assertTrue(proposal["manifest"]["exclusive_consumer"])
            auth, manifest, approval, binding = approved_for_proposal(proposal)
            self.assertEqual(binding["manifest_sha256"], manifest.canonical())
            self.assertTrue(manifest.admission_authorized)
            self.assertTrue(manifest.exclusive_consumer)
            transport = pilot.OpenCodeTransport(
                client, expected_instance_id=auth.instance_id,
                registered_targets=(auth.target,), pilot_authorization=auth,
                mode="real", allow_write=True, clock=lambda: datetime.now(timezone.utc))
            executor = pilot.PilotExecutor(
                transport, pilot.PilotOperationStore(os.path.join(td, "run")), approval,
                endpoint=ENDPOINT, allow_real=True, catalog_path=catalog_path,
                consumer_evidence_path=os.path.join(td, "consumer.json"))
            result = executor.execute(
                mission_from_proposal(proposal), core_auth_from_proposal(proposal),
                pilot.PILOT_PROMPT)
            posts = [call for call in client.calls if call[0] == "POST"]
            self.assertEqual(len(posts), 1)
            self.assertTrue(result["executed"])
            self.assertEqual(result["state"], "DISPATCHING")
            self.assertTrue(result["receipt_confirmed"])


class ContractNegativeTests(unittest.TestCase):
    def _approved(self, td):
        client, catalog_path, _, proposal = StandardPathTests()._setup(td)
        auth, manifest, approval, binding = approved_for_proposal(proposal)
        transport = pilot.OpenCodeTransport(
            client, expected_instance_id=auth.instance_id,
            registered_targets=(auth.target,), pilot_authorization=auth,
            mode="real", allow_write=True, clock=lambda: datetime.now(timezone.utc))
        executor = pilot.PilotExecutor(
            transport, pilot.PilotOperationStore(os.path.join(td, "negative")), approval,
            endpoint=ENDPOINT, allow_real=True, catalog_path=catalog_path,
            consumer_evidence_path=os.path.join(td, "consumer.json"))
        return client, proposal, executor

    def _assert_zero_post(self, td, mutate):
        client, proposal, executor = self._approved(td)
        mutate(client, proposal, td)
        with self.assertRaises(cc.AuthorizationError):
            executor.execute(mission_from_proposal(proposal),
                             core_auth_from_proposal(proposal), pilot.PILOT_PROMPT)
        self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_policy_change_after_approval_has_zero_post(self):
        def mutate(client, proposal, td):
            sid = f"/api/session/{SID}"
            value = dict(client.responses[("GET", sid)][1]["data"])
            value["permissions"] = [{"action": "*", "resource": "*", "effect": "allow"}]
            client.responses[("GET", sid)] = (200, {"data": value})
        with tempfile.TemporaryDirectory() as td:
            self._assert_zero_post(td, mutate)

    def test_cost_change_after_approval_has_zero_post(self):
        def mutate(client, proposal, td):
            sid = f"/api/session/{SID}"
            value = dict(client.responses[("GET", sid)][1]["data"])
            value["cost"] = 7
            client.responses[("GET", sid)] = (200, {"data": value})
        with tempfile.TemporaryDirectory() as td:
            self._assert_zero_post(td, mutate)

    def test_catalog_change_after_approval_has_zero_post(self):
        def mutate(client, proposal, td):
            catalog = json.loads(json.dumps(CATALOG))
            catalog["data"][0]["cost"] = [{"input": 1, "output": 2, "cache": {}}]
            client.responses[("GET", "/api/model")] = (200, catalog)
        with tempfile.TemporaryDirectory() as td:
            self._assert_zero_post(td, mutate)

    def test_exclusivity_loss_after_approval_has_zero_post(self):
        def mutate(client, proposal, td):
            path = os.path.join(td, "consumer.json")
            with open(path, encoding="utf-8") as handle:
                value = json.load(handle)
            value["expires_at"] = "2020-01-01T00:00:00Z"
            write_json(path, value)
        with tempfile.TemporaryDirectory() as td:
            self._assert_zero_post(td, mutate)

    def test_approval_binding_tamper_has_zero_post(self):
        with tempfile.TemporaryDirectory() as td:
            client, proposal, executor = self._approved(td)
            executor.approval = replace(executor.approval,
                                        approval_binding={"manifest_sha256": "0" * 64})
            with self.assertRaises(cc.AuthorizationError):
                executor.execute(mission_from_proposal(proposal),
                                 core_auth_from_proposal(proposal), pilot.PILOT_PROMPT)
            self.assertEqual([c for c in client.calls if c[0] == "POST"], [])

    def test_unknown_has_no_resend(self):
        with tempfile.TemporaryDirectory() as td:
            client, catalog_path, _, proposal = StandardPathTests()._setup(
                td, client=RealShapedClient(prompt_status=504))
            auth, manifest, approval, binding = approved_for_proposal(proposal)
            transport = pilot.OpenCodeTransport(
                client, expected_instance_id=auth.instance_id,
                registered_targets=(auth.target,), pilot_authorization=auth,
                mode="real", allow_write=True, clock=lambda: datetime.now(timezone.utc))
            executor = pilot.PilotExecutor(
                transport, pilot.PilotOperationStore(os.path.join(td, "unknown")), approval,
                endpoint=ENDPOINT, allow_real=True, catalog_path=catalog_path,
                consumer_evidence_path=os.path.join(td, "consumer.json"))
            first = executor.execute(mission_from_proposal(proposal),
                                     core_auth_from_proposal(proposal), pilot.PILOT_PROMPT)
            self.assertEqual(first["state"], "UNKNOWN")
            with self.assertRaises(cc.AuthorizationError):
                executor.execute(mission_from_proposal(proposal),
                                 core_auth_from_proposal(proposal), pilot.PILOT_PROMPT)
            self.assertEqual(len([c for c in client.calls if c[0] == "POST"]), 1)


if __name__ == "__main__":
    unittest.main()
