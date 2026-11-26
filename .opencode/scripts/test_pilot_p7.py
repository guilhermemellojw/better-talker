#!/usr/bin/env python3
"""Regressões permanentes P7: F1/F2/F3/F4 do parecer issuecomment-7179955042."""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from datetime import timedelta
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli
import test_pilot_api_compat as ax

A = ax.EP
B = "http://127.0.0.1:51111"
SECRET = "segredo-sintetico"


class FakeResponse:
    status = 200

    def read(self):
        return b"{}"

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class CredentialEndpointTests(unittest.TestCase):
    def _client(self, provider):
        return pilot.LocalHttpClient(
            A, allowed_port=51234,
            auth=pilot.ExperimentalAuth(A, password_provider=provider))

    def test_base_url_and_endpoint_are_read_only_aliases(self):
        calls = []
        client = self._client(lambda: calls.append("provider") or SECRET)
        with self.assertRaises(AttributeError):
            client.base_url = B
        with self.assertRaises(AttributeError):
            client.endpoint = B
        with patch.object(client.opener, "open", return_value=FakeResponse()) as opener:
            status, _ = client.request("GET", "/api/info")
        self.assertEqual(status, 200)
        self.assertEqual(len(calls), 1)
        request = opener.call_args.args[0]
        self.assertTrue(request.full_url.startswith(A))
        self.assertIn("Authorization", request.headers)
        self.assertNotIn(B, request.full_url)

    def test_base_url_mutation_to_divergent_address_zero_request(self):
        calls = []
        client = self._client(lambda: calls.append("provider") or SECRET)
        client._endpoint = B  # mutação direta da fonte interna
        with patch.object(client.opener, "open", side_effect=AssertionError("request")) as opener:
            with self.assertRaises(cc.ContractError):
                client.request("GET", "/api/info")
        opener.assert_not_called()
        self.assertEqual(calls, [])

    def test_port_and_external_address_mutations_zero_request(self):
        for mutate in (lambda c: setattr(c, "_allowed_port", 51111),
                       lambda c: setattr(c, "_endpoint", "https://externo.example.com/api")):
            with self.subTest(mutate=mutate):
                calls = []
                client = self._client(lambda: calls.append("provider") or SECRET)
                mutate(client)
                with patch.object(client.opener, "open", side_effect=AssertionError("request")) as opener:
                    with self.assertRaises(cc.ContractError):
                        client.request("GET", "/api/info")
                opener.assert_not_called()
                self.assertEqual(calls, [])

    def test_credential_swap_zero_request_and_zero_header(self):
        calls = []
        auth = pilot.ExperimentalAuth(A, password_provider=lambda: calls.append("p") or SECRET)
        client = pilot.LocalHttpClient(A, allowed_port=51234, auth=auth)
        auth.endpoint = B  # credencial trocada para outro endpoint após construção
        with patch.object(client.opener, "open", side_effect=AssertionError("request")) as opener:
            with self.assertRaises(cc.ContractError):
                client.request("GET", "/api/info")
        opener.assert_not_called()
        self.assertEqual(calls, [])
        with self.assertRaises(cc.ContractError):
            pilot.LocalHttpClient(A, allowed_port=51234,
                                  auth=pilot.ExperimentalAuth(B, password_provider=lambda: SECRET))

    def test_readonly_client_still_blocks_post(self):
        client = cli._real_client(A)
        with self.assertRaises(cc.TransportDisabled):
            client.request("POST", "/api/session/x/prompt", {"id": "x"})


class TargetIdentityTests(unittest.TestCase):
    def _transport(self, location=None, session=None):
        responses = {
            pilot.LOCATION_PATH: (200, location or ax.LOCATION),
            f"/api/session/{ax.SID}": (200, session or {"data": {
                "id": ax.SID, "projectID": ax.OPCODE_PROJECT_ID,
                "agent": "agente-observado",
                "model": {"providerID": "opencode-go", "id": "hungerg-free", "variant": "default"},
                "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}}),
        }
        client = ax.ScriptedClient(responses)
        target = cc.TargetIdentity(pilot.EXPECTED_PROJECT_ID, pilot.EXPECTED_REPOSITORY_ID,
                                   "agente-observado", ax.SID,
                                   pilot.instance_identity(ax.INFO), pilot.EXPERIMENTAL_WORKTREE)
        auth = pilot.PilotAuthorization(
            authorization_id="AUTH-X", maintainer_id="m", approval_reference="r",
            project_id=target.project_id, repository_id=target.repository_id,
            instance_id=target.instance_id, target=target, mission_id="m",
            mission_identity="a" * 64, operation_id="o", prompt_sha256="b" * 64,
            scope="s", issued_at=ax.CLOCK, expires_at=ax.CLOCK + timedelta(hours=1),
            approved=True, environment="FIXTURE", endpoint=A)
        return pilot.OpenCodeTransport(client, expected_instance_id=target.instance_id,
                                       registered_targets=(target,), pilot_authorization=auth,
                                       mode="fixture", clock=lambda: ax.CLOCK), target

    def test_legitimate_hash_identity_is_accepted(self):
        transport, target = self._transport()
        transport._confirm_target(target)

    def test_individual_divergences_are_rejected(self):
        cases = {
            "hash interno": {"data": {"id": ax.SID, "projectID": "hash-outro",
                "agent": "agente-observado",
                "model": {"providerID": "opencode-go", "id": "hungerg-free", "variant": "default"},
                "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}},
            "agente": {"data": {"id": ax.SID, "projectID": ax.OPCODE_PROJECT_ID,
                "agent": "outro",
                "model": {"providerID": "opencode-go", "id": "hungerg-free", "variant": "default"},
                "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}},
            "modelo": {"data": {"id": ax.SID, "projectID": ax.OPCODE_PROJECT_ID,
                "agent": "agente-observado",
                "model": {"providerID": "x", "id": "pago", "variant": "d"},
                "location": {"directory": pilot.EXPERIMENTAL_WORKTREE}}},
            "location": {"data": {"id": ax.SID, "projectID": ax.OPCODE_PROJECT_ID,
                "agent": "agente-observado",
                "model": {"providerID": "opencode-go", "id": "hungerg-free", "variant": "default"},
                "location": {"directory": "/tmp/outro"}}},
        }
        for label, session in cases.items():
            with self.subTest(label=label):
                transport, target = self._transport(session=session)
                with self.assertRaises(cc.TransportError):
                    transport._confirm_target(target)
        bad_location = {"directory": pilot.EXPERIMENTAL_WORKTREE,
                        "project": {"id": ax.OPCODE_PROJECT_ID,
                                    "canonical": "/tmp/outro-canonical"}}
        transport, target = self._transport(location=bad_location)
        with self.assertRaises(cc.TransportError):
            transport._confirm_target(target)


class RegistryOrderTests(unittest.TestCase):
    def test_no_session_or_inbox_get_before_instance_confirmation(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": A,
                "instance_id": "inst-A", "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH-A", "permissions_confirmed": True})
            responses = ax.full_responses()
            responses[pilot.INFO_PATH] = (200, dict(ax.INFO, pid=99999))
            client = ax.ScriptedClient(responses)
            with patch.object(cli, "_real_client", return_value=client):
                observed = cli._real_observe(A, session=session)
        self.assertEqual(observed["status_kind"], "instance_divergent")
        self.assertEqual(observed["calls"], ["GET /api/info"])
        self.assertNotIn(f"/api/session/{ax.SID}", observed["calls"])
        self.assertNotIn(f"/api/session/{ax.SID}{pilot.INBOX_SUFFIX}", observed["calls"])

    def test_silent_replacement_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            fields = {"session_id": ax.SID, "experimental": True, "endpoint": A,
                      "instance_id": "inst-A", "project_id": pilot.EXPECTED_PROJECT_ID,
                      "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                      "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                      "authorization_reference": "AUTH-A", "permissions_confirmed": True}
            cli._register_session(td, fields)
            with self.assertRaises(ValueError):
                cli._register_session(td, {**fields, "session_id": "ses_substituta"})
            updated = cli._register_session(td, {**fields, "session_id": "ses_substituta"},
                                            replace=True)
            self.assertEqual(updated["session_id"], "ses_substituta")

    def test_invalid_json_and_incomplete_registry_are_controlled(self):
        with tempfile.TemporaryDirectory() as td:
            path = cli._session_registry_path(td)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("{json inválido")
            with self.assertRaises(ValueError):
                cli._load_registered_session(td)
            out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", A])
            self.assertEqual(out["status_kind"], "registry_invalid")
            self.assertEqual(out["real_calls"], 0)
        with tempfile.TemporaryDirectory() as td:
            path = cli._session_registry_path(td)
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"session_id": ax.SID}, handle)
            with self.assertRaises(ValueError):
                cli._load_registered_session(td)


class ManifestEvidenceTests(unittest.TestCase):
    def _result(self, mutation=None):
        td = tempfile.TemporaryDirectory()
        session = ax.registered(td.name)
        responses = ax.full_responses()
        if mutation:
            mutation(responses)
        client = ax.ScriptedClient(responses)
        with patch.object(cli, "_real_client", return_value=client):
            observed = cli._real_observe(A, session=session)
        with patch.object(pilot, "repository_identity_from_git",
                          return_value=pilot.EXPECTED_REPOSITORY_ID), \
             patch.object(pilot, "executed_head_sha", return_value="a" * 40), \
             patch.object(pilot, "operational_file_digests", return_value={"coord_pilot.py": "c" * 64}):
            result = cli._prepare_real(observed, session=session, clock=ax.CLOCK)
        td.cleanup()
        return result

    def test_nine_divergences_block_manifest(self):
        meta = f"/api/session/{ax.SID}"
        inbox = f"/api/session/{ax.SID}{pilot.INBOX_SUFFIX}"
        cases = {
            "session_id divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], id="ses_outra")})),
            "agent divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], agent="outro")})),
            "modelo divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], model={"providerID": "x", "id": "pago", "variant": "d"})})),
            "projeto interno divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], projectID="hash-outro")})),
            "location da sessão divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], location={"directory": "/tmp/outro"})})),
            "repositório divergente": lambda r: r.__setitem__(meta, (200, {"data": dict(
                ax.SESSION_META["data"], repositoryID="outro/repo")})),
            "session_found=false": lambda r: r.__setitem__(meta, (404, ax.SESSION_ABSENT)),
            "inbox ocupado": lambda r: r.__setitem__(inbox, (200, {"data": [{"id": "msg-antiga"}]})),
            "disponibilidade desconhecida": lambda r: r.__setitem__(meta, (200, {"data": {
                k: v for k, v in ax.SESSION_META["data"].items() if k not in ("outcome", "time")}})),
        }
        for label, mutation in cases.items():
            with self.subTest(label=label):
                result = self._result(mutation)
                self.assertTrue(result.get("blocked", False), label)
                self.assertNotIn("manifest", result)

    def test_legitimate_complete_proposal_is_unauthorized(self):
        result = self._result()
        self.assertFalse(result.get("blocked", False))
        self.assertFalse(result["approved"])
        self.assertEqual(result["environment"], "PROPOSED")
        self.assertEqual(result["created_at"], cc.iso(ax.CLOCK))
        self.assertEqual(result["issued_at"], cc.iso(ax.CLOCK))
        self.assertEqual(result["expires_at"], cc.iso(ax.CLOCK + timedelta(minutes=30)))
        self.assertEqual(result["manifest"]["endpoint"], A)

    def test_infrastructure_preflight_without_recipient_blocks_manifest(self):
        with tempfile.TemporaryDirectory() as td:
            client = ax.ScriptedClient(ax.full_responses(with_session=False))
            with patch.object(cli, "_real_client", return_value=client):
                out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", A])
                prepared = cli.run(["--store-dir", td, "prepare", "--real", "--endpoint", A])
        self.assertTrue(out["service_contacted"])
        self.assertFalse(out["recipient_authorized"])
        self.assertTrue(prepared["blocked"])
        self.assertNotIn("manifest", prepared)


if __name__ == "__main__":
    unittest.main()
