#!/usr/bin/env python3
"""Regressões focais P3B.4 (J5–J11): admissão, inbox, repositório e revinculação."""
from __future__ import annotations

import os
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
import test_pilot_api_compat as ax

A = ax.EP
OTHER = "http://127.0.0.1:51111"
SECRET = "segredo-sintetico"


def registered(td, *, endpoint=A, instance=None, session_id=None):
    return cli._register_session(td, {
        "session_id": session_id or ax.SID, "experimental": True, "endpoint": endpoint,
        "instance_id": instance or pilot.instance_identity(ax.INFO),
        "project_id": pilot.EXPECTED_PROJECT_ID,
        "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
        "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
        "authorization_reference": "AUTH-P34",
        "permission_policy": ax.SESSION_META["data"]["permissions"],
        "permission_policy_sha256": cli.canonical_policy_sha256(
            ax.SESSION_META["data"]["permissions"]),
        "repository_source": "git-local", "admission_basis": "new_experimental_session",
        "inbox_evidence": "empty:0"})


def observe(responses, session, *, endpoint=A, expect_instance_change=False):
    with patch.object(cli, "_real_client",
                      return_value=ax.ScriptedClient(responses)):
        return cli._real_observe(endpoint, session=session,
                                 expect_instance_change=expect_instance_change)


class AdmissionTests(unittest.TestCase):
    def test_inbox_absent_is_not_zero(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            responses = ax.full_responses()
            responses.pop(f"/api/session/{ax.SID}{pilot.INBOX_SUFFIX}")
            observed = observe(responses, session)
        inbox = observed["inbox_observation"]
        self.assertNotEqual(inbox["state"], "empty")
        self.assertFalse(inbox["inbox_observed"])
        self.assertIsNone(inbox["inbox_count"])
        self.assertIn("inbox", " ".join(cli._manifest_readiness_blockers(observed, session)))

    def test_inbox_occupied_blocks_readiness(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            responses = ax.full_responses()
            responses[f"/api/session/{ax.SID}{pilot.INBOX_SUFFIX}"] = (
                200, {"data": [{"id": "msg"}]})
            observed = observe(responses, session)
        self.assertEqual(observed["inbox_observation"]["state"], "occupied")
        self.assertNotEqual(observed["admission_state"], "new_experimental_session")

    def test_repository_absent_with_git_evidence_is_accepted(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            observed = observe(ax.full_responses(), session)
        self.assertIsNone(observed["session_repository_id"])
        self.assertEqual(observed["repository_source"], "git-local")
        self.assertTrue(observed["repository_evidence"]["confirmed"])
        blockers = cli._manifest_readiness_blockers(observed, session)
        self.assertNotIn("repositório declarado pela API divergente", blockers)

    def test_repository_returned_divergent_blocks_identity(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            responses = ax.full_responses()
            responses[f"/api/session/{ax.SID}"] = (200, {"data": dict(
                ax.SESSION_META["data"], repositoryID="outro/repo")})
            observed = observe(responses, session)
        self.assertFalse(observed["session_identity_confirmed"])
        self.assertIn("repositório divergente", observed["blockers"])

    def test_requested_permissions_without_enforcement_do_not_prove_readiness(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            observed = observe(ax.full_responses(), session)
            ax.make_synthetic_ready(observed)
            self.assertTrue(observed["permission_policy_sha256"])
            blockers = cli._manifest_readiness_blockers(observed, session)
        self.assertNotIn("enforcement de permissões não observado", blockers)  # sintético em teste
        observed["permissions_evidence"]["enforcement_observed"] = False
        self.assertIn("enforcement de permissões não observado",
                      cli._manifest_readiness_blockers(observed, session))

    def test_availability_unknown_when_active_not_observed(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td)
            responses = ax.full_responses()
            responses[pilot.ACTIVE_PATH] = (404, {"_tag": "NotFound"})
            observed = observe(responses, session)
        self.assertEqual(observed.get("admission_state", "unknown"), "unknown")
        self.assertFalse(observed.get("active_observed", False))

    def test_instance_divergent_blocks_before_session_get(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td, instance="instance-antiga")
            observed = observe(ax.full_responses(), session)
        self.assertEqual(observed["status_kind"], "instance_divergent")
        self.assertEqual(observed["calls"], ["GET /api/info"])

    def test_registry_endpoint_divergent_produces_zero_get(self):
        with tempfile.TemporaryDirectory() as td:
            registered(td, endpoint=A)
            out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", OTHER,
                           "--auth-env", "COORD_TEST_AUTH"])
        self.assertEqual(out["status_kind"], "registry_endpoint_divergent")
        self.assertEqual(out["real_calls"], 0)
        self.assertEqual(out["observed"]["calls"], [])


class RebindingTests(unittest.TestCase):
    def test_same_session_rebinding_proposal(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td, instance="instance-antiga")
            responses = ax.full_responses()
            responses[pilot.INFO_PATH] = (200, dict(ax.INFO, pid=99999))
            observed = observe(responses, session, expect_instance_change=True)
            proposal = cli.prepare_rebind(session, observed, endpoint=A)
        self.assertFalse(proposal["blocked"])
        self.assertTrue(proposal["instance_changed"])
        self.assertTrue(proposal["proposal_only"])
        self.assertFalse(proposal["applied"])
        self.assertTrue(proposal["requires_authorization"])

    def test_other_session_rebinding_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            session = registered(td, session_id="ses_outra")
            observed = observe(ax.full_responses(), session)
            proposal = cli.prepare_rebind(session, observed, endpoint=A)
        self.assertTrue(proposal["blocked"])
        self.assertFalse(proposal["checks"]["same_session"])
        self.assertFalse(proposal["applied"])


class ManifestBindingTests(unittest.TestCase):
    def test_permission_hash_change_invalidates_approval(self):
        m = cli._fixture_context(store_dir=tempfile.mkdtemp())["mission"]
        auth = cli._fixture_context(store_dir=tempfile.mkdtemp())["auth"]
        base = dict(permission_policy_sha256="a" * 64, admission_basis="new_experimental_session",
                    inbox_evidence="empty:0", repository_source="git-local")
        first = pilot.build_runtime_manifest(m, auth, pilot.sha256_text(pilot.PILOT_PROMPT), A, **base)
        second = pilot.build_runtime_manifest(m, auth, pilot.sha256_text(pilot.PILOT_PROMPT), A,
                                              **{**base, "permission_policy_sha256": "b" * 64})
        approval = pilot.PilotApproval("APP", "humano", "human", "FIXTURE", first)
        self.assertNotEqual(first.canonical(), second.canonical())
        self.assertFalse(approval.matches(second))
        self.assertIn("permission_policy_sha256", first.material())

    def test_no_fallback_to_fixture(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "preflight", "--real", "--endpoint", OTHER])
        # P14A: --real sem contato efetivo é apenas declaração de modo, não
        # evidência de transporte real.
        self.assertEqual(out["source"], "declared-real")
        self.assertEqual(out["fallback"], "none")
        self.assertFalse(out["service_contacted"])
        self.assertNotIn("agent_id", out["observed"])


if __name__ == "__main__":
    unittest.main()
