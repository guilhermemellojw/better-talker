#!/usr/bin/env python3
"""Regressões focais P3B.2: compatibilidade com a API real observada (dados sanitizados)."""
from __future__ import annotations

import base64
import os
import stat
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli

EP = "http://127.0.0.1:51234"
SID = "ses_experimental001"
OPCODE_PROJECT_ID = "320134d76dfecf3ccd6e6939e946c9f18d32a4f3"
CLOCK = datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc)

# Cópias sanitizadas dos corpos observados na P3B.1 (sem segredos nem dados sensíveis).
INFO = {"version": "2.3.2", "pid": 391933, "hostname": "notebook", "urls": [EP],
        "paths": {"tmp": "/tmp/opencode"}, "capabilities": {"persistentPty": True}}
LOCATION = {"directory": pilot.EXPERIMENTAL_WORKTREE,
            "project": {"id": OPCODE_PROJECT_ID, "directory": pilot.EXPERIMENTAL_WORKTREE,
                        "canonical": pilot.EXPECTED_CANONICAL_DIR}}
INVENTORY = {"data": [], "cursor": {"previous": None, "next": None}}
ACTIVE = {"data": {}}
UNAUTHORIZED = {"_tag": "UnauthorizedError", "message": "Authentication required"}
SESSION_ABSENT = {"_tag": "SessionNotFoundError", "sessionID": SID,
                  "message": f"Session not found: {SID}"}
SESSION_META = {"data": {
    "id": SID, "projectID": OPCODE_PROJECT_ID,
    "repositoryID": pilot.EXPECTED_REPOSITORY_ID,
    "agent": "agente-observado", "model": {"providerID": "opencode-go",
    "id": "hungerg-free", "variant": "default"},
    "time": {"busy": 0, "idle": 9}, "outcome": "succeeded",
    "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}}


class ScriptedClient(pilot.HttpClient):
    def __init__(self, responses):
        self.endpoint = pilot.canonical_endpoint(EP)
        self.base_url = self.endpoint
        self.responses = responses
        self.calls = []

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return self.responses.get(path, (404, {"_tag": "NotFound"}))


def full_responses(*, with_session=True):
    values = {
        pilot.INFO_PATH: (200, INFO),
        pilot.LOCATION_PATH: (200, LOCATION),
        "/api/session": (200, INVENTORY),
        pilot.ACTIVE_PATH: (200, ACTIVE),
    }
    if with_session:
        values[f"/api/session/{SID}"] = (200, SESSION_META)
        values[f"/api/session/{SID}{pilot.INBOX_SUFFIX}"] = (200, {"data": []})
    return values


def registered(store_dir):
    return cli._register_session(store_dir, {
        "session_id": SID, "experimental": True, "endpoint": EP,
        "instance_id": pilot.instance_identity(INFO),
        "project_id": pilot.EXPECTED_PROJECT_ID,
        "repository_id": pilot.EXPECTED_REPOSITORY_ID,
        "agent_id": "agente-observado", "model": cli.ALLOWED_FREE_MODEL,
        "location": pilot.EXPERIMENTAL_WORKTREE, "permissions_confirmed": True,
        "authorization_reference": "AUTH-EXPERIMENTAL-001"})


class AuthenticationTests(unittest.TestCase):
    def test_experimental_auth_binding_and_no_secret_leak(self):
        secret = "segredo-experimental"
        auth = pilot.ExperimentalAuth(EP, password_provider=lambda: secret)
        header = auth.header(EP)
        expected = "Basic " + base64.b64encode(f"opencode:{secret}".encode()).decode("ascii")
        self.assertEqual(header, {"Authorization": expected})
        self.assertNotIn(secret, repr(auth))
        self.assertNotIn(secret, str(auth.__dict__))
        with self.assertRaises(cc.ContractError):
            auth.header("http://127.0.0.1:51111")

    def test_readonly_client_blocks_post_even_with_auth(self):
        os.environ["COORD_TEST_AUTH"] = "segredo-local"
        try:
            client = cli._real_client(EP, auth_env="COORD_TEST_AUTH")
            self.assertFalse(client.allow_write)
            self.assertIn("Authorization", client.auth.header(EP))
            with self.assertRaises(cc.TransportDisabled):
                client.request("POST", "/api/session/x/prompt", {"id": "x"})
        finally:
            os.environ.pop("COORD_TEST_AUTH", None)

    def test_401_is_service_contacted_auth_refused(self):
        client = ScriptedClient({pilot.INFO_PATH: (401, UNAUTHORIZED)})
        with patch.object(cli, "_real_client", return_value=client):
            observed = cli._real_observe(EP)
        self.assertTrue(observed["connection_attempted"])
        self.assertTrue(observed["service_contacted"])
        self.assertFalse(observed["authentication_succeeded"])
        self.assertFalse(observed["http_response_valid"])
        self.assertEqual(observed["status_kind"], "unauthorized")
        self.assertEqual(observed["http_status"], 401)
        self.assertEqual(observed["fallback"], "none")


class ResponseCompatibilityTests(unittest.TestCase):
    def test_404_session_absent_is_not_a_communication_failure(self):
        responses = full_responses(with_session=False)
        responses[f"/api/session/{SID}"] = (404, SESSION_ABSENT)
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            client = ScriptedClient(responses)
            with patch.object(cli, "_real_client", return_value=client):
                out = cli.run(["--store-dir", td, "preflight", "--real",
                               "--endpoint", EP, "--session", SID])
        self.assertTrue(out["service_contacted"])
        self.assertTrue(out["authentication_succeeded"])
        self.assertTrue(out["recipient_authorized"])
        self.assertFalse(out["session_found"])
        self.assertEqual(out["status_kind"], "session_absent")
        self.assertEqual(out["observed"]["http_status"], 404)

    def test_observed_json_shapes_are_understood(self):
        client = ScriptedClient(full_responses())
        with tempfile.TemporaryDirectory() as td:
            registered(td)
            with patch.object(cli, "_real_client", return_value=client):
                out = cli.run(["--store-dir", td, "preflight", "--real",
                               "--endpoint", EP, "--session", SID])
        observed = out["observed"]
        self.assertEqual(observed["api_version"], "2.3.2")
        self.assertEqual(observed["instance_id"], pilot.instance_identity(INFO))
        self.assertEqual(observed["opencode_project_id"], OPCODE_PROJECT_ID)
        self.assertEqual(observed["project_canonical"], pilot.EXPECTED_CANONICAL_DIR)
        self.assertEqual(observed["location_directory"], pilot.EXPERIMENTAL_WORKTREE)
        self.assertEqual(observed["session_count"], 0)
        self.assertEqual(observed["inventory_cursor"], {"previous": None, "next": None})
        self.assertEqual(observed["active_sessions"], 0)
        self.assertTrue(observed["session_found"])
        self.assertEqual(observed["session_model"], cli.ALLOWED_FREE_MODEL)
        self.assertTrue(out["http_response_valid"])

    def test_project_and_repository_identities_are_separate(self):
        client = ScriptedClient(full_responses())
        with tempfile.TemporaryDirectory() as td:
            with patch.object(cli, "_real_client", return_value=client):
                out = cli.run(["--store-dir", td, "preflight", "--real",
                               "--endpoint", EP, "--session", SID])
        observed = out["observed"]
        self.assertNotEqual(observed["opencode_project_id"], pilot.EXPECTED_PROJECT_ID)
        self.assertNotEqual(observed["project_canonical"], observed["location_directory"])
        self.assertEqual(pilot.repository_identity_from_git(), pilot.EXPECTED_REPOSITORY_ID)
        self.assertNotIn("segredo", str(out).lower())

    def test_repository_divergent_blocks_manifest(self):
        client = ScriptedClient(full_responses())
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            with patch.object(cli, "_real_client", return_value=client):
                observed = cli._real_observe(EP, session=session)
            with patch.object(pilot, "repository_identity_from_git",
                              side_effect=cc.ContractError("repositório divergente")):
                result = cli._prepare_real(observed, session=session, clock=CLOCK)
        self.assertTrue(result["blocked"])
        self.assertNotIn("manifest", result)


class SessionConfigurationTests(unittest.TestCase):
    def test_registration_and_explicit_selection(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "session-register", "--session", SID,
                           "--endpoint", EP, "--instance-id", "inst-1",
                           "--agent-id", "agente-observado",
                           "--authorization-reference", "AUTH-EXPERIMENTAL-001"])
            self.assertTrue(out["registered"])
            path = cli._session_registry_path(td)
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(cli._resolve_session(td, SID)["session_id"], SID)
            self.assertIsNone(cli._resolve_session(td, "ses_outra"))
            with tempfile.TemporaryDirectory() as empty_dir:
                self.assertIsNone(cli._resolve_session(empty_dir, None))

    def test_unregistered_session_is_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", EP,
                           "--session", "ses_nao_cadastrada"])
        self.assertFalse(out["recipient_authorized"])
        self.assertTrue(out["blockers"])

    def test_preflight_without_session_reports_infrastructure_only(self):
        client = ScriptedClient(full_responses(with_session=False))
        with tempfile.TemporaryDirectory() as td, \
                patch.object(cli, "_real_client", return_value=client):
            out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", EP])
        self.assertTrue(out["service_contacted"])
        self.assertFalse(out["recipient_authorized"])
        self.assertFalse(out["session_found"])
        self.assertIn("nenhum destinatário experimental cadastrado",
                      " ".join(out["blockers"]))


class ProposalWindowTests(unittest.TestCase):
    def test_real_manifest_uses_current_clock_and_is_not_approval(self):
        client = ScriptedClient(full_responses())
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            with patch.object(cli, "_real_client", return_value=client):
                observed = cli._real_observe(EP, session=session)
            result = cli._prepare_real(observed, session=session, clock=CLOCK)
        self.assertFalse(result.get("blocked", False))
        self.assertEqual(result["created_at"], cc.iso(CLOCK))
        self.assertEqual(result["issued_at"], cc.iso(CLOCK))
        self.assertEqual(result["expires_at"], cc.iso(CLOCK + timedelta(minutes=30)))
        self.assertFalse(result["approved"])
        self.assertEqual(result["environment"], "PROPOSED")
        self.assertNotIn("approval", [k for k in result if k.endswith("approval")])
        self.assertFalse(result["authorization"]["approved"])
        self.assertEqual(result["manifest"]["endpoint"], EP)
        self.assertEqual(result["manifest"]["issued_at"], cc.iso(CLOCK))
        self.assertEqual(result["repository_source"], "git-local")

    def test_expired_and_future_windows_rejected(self):
        expires = CLOCK + timedelta(minutes=30)
        cli._validate_proposal_window(CLOCK, CLOCK, expires, CLOCK)
        with self.assertRaises(ValueError):
            cli._validate_proposal_window(CLOCK, CLOCK, expires,
                                          CLOCK + timedelta(hours=1))
        with self.assertRaises(ValueError):
            cli._validate_proposal_window(CLOCK + timedelta(minutes=1),
                                          CLOCK + timedelta(minutes=1), expires, CLOCK)

    def test_incomplete_metadata_blocks_manifest(self):
        client = ScriptedClient({pilot.INFO_PATH: (401, UNAUTHORIZED)})
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            with patch.object(cli, "_real_client", return_value=client):
                observed = cli._real_observe(EP, session=session)
            result = cli._prepare_real(observed, session=session, clock=CLOCK)
        self.assertTrue(result["blocked"])
        self.assertNotIn("manifest", result)


if __name__ == "__main__":
    unittest.main()
