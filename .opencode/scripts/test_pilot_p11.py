#!/usr/bin/env python3
"""P11: proposta sem aprovação, reobservação pré-POST e sondas de mutação."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli
import test_pilot_api_compat as ax
import test_pilot_executor as hx

NOW = datetime(2026, 11, 26, 12, tzinfo=timezone.utc)
EXCLUSIVE = {"consumer_id": "coord-pilot-executor", "session_id": ax.SID,
             "authorization_reference": "AUTH-P11", "endpoint": pilot.DEFAULT_ENDPOINT,
             "acquired_at": "2026-11-26T11:00:00Z", "expires_at": "2026-11-26T13:00:00Z",
             "lock_scope": "/tmp/opencode/p11.lock", "verified": True}
POLICY = ax.SESSION_META["data"]["permissions"]
POLICY_SHA = pilot.canonical_policy_sha256(POLICY)
EXCLUSIVE_SHA = pilot.consumer_evidence_sha256(EXCLUSIVE)


def fresh_evidence():
    ctx = cli._fixture_context(store_dir=tempfile.mkdtemp())
    target = ctx["auth"].target
    return {
        "permission_policy_sha256": POLICY_SHA,
        "session_model": pilot.EXPERIMENTAL_MODEL,
        "instance_id": ctx["auth"].instance_id,
        "target_key": list(target.key()),
        "tokens": {"input": 0, "output": 0, "reasoning": 0},
        "cost": 0,
        "model_catalog_evidence": {"confirmed": True, "source": "model-catalog"},
        "target_in_active": False, "inbox_observed": True, "inbox_count": 0,
        "messages_observed": True, "message_count": 0, "part_count": 0,
        "pending_observed": True, "pending_count": 0,
        "exclusive_consumer_evidence": deepcopy(EXCLUSIVE),
    }


class ProposalVsApprovalTests(unittest.TestCase):
    def test_proposal_is_produced_without_authorization(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": pilot.instance_identity(ax.INFO),
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH-P11"})
            observed = ax.full_responses()
            with patch.object(cli, "_real_client",
                              return_value=ax.ScriptedClient(observed)):
                raw = cli._real_observe(ax.EP, session=session)
            ax.make_synthetic_ready(raw)
            raw["model_catalog_evidence"] = {"confirmed": True}
            proposal = cli._prepare_real(raw, session=session, clock=NOW)
        self.assertFalse(proposal.get("blocked", False))
        self.assertIn("manifest", proposal)
        self.assertFalse(proposal["manifest"]["approved"])
        self.assertEqual(proposal["environment"], "PROPOSED")
        self.assertFalse(proposal["manifest"]["admission_authorized"])
        binding = cli.external_approval_binding(
            proposal["manifest"], approver_id="mantenedor", reference="AUTH-EXTERNAL")
        self.assertEqual(binding["manifest_sha256"], proposal["manifest"]["canonical_sha256"])

    def test_material_change_invalidates_external_binding(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": pilot.instance_identity(ax.INFO),
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH-P11"})
            with patch.object(cli, "_real_client",
                              return_value=ax.ScriptedClient(ax.full_responses())):
                raw = cli._real_observe(ax.EP, session=session)
            ax.make_synthetic_ready(raw)
            raw["model_catalog_evidence"] = {"confirmed": True}
            proposal = cli._prepare_real(raw, session=session, clock=NOW)["manifest"]
        changed = dict(proposal, permission_policy_sha256="b" * 64)
        with self.assertRaises(ValueError):
            cli.external_approval_binding(changed, approver_id="m", reference="AUTH")


class PrePostValidationTests(unittest.TestCase):
    def _executor(self, evidence):
        ctx = cli._fixture_context(store_dir=tempfile.mkdtemp())
        m = replace(ctx["mission"], created_at=NOW, expires_at=NOW + timedelta(hours=1))
        auth = replace(ctx["auth"], environment="APPROVED", approved=True,
                       issued_at=NOW, expires_at=NOW + timedelta(hours=1),
                       mission_identity=cc.canonical_mission_identity(m),
                       endpoint=pilot.DEFAULT_ENDPOINT)
        client, _ = hx.fake_client()
        manifest = pilot.build_runtime_manifest(
            m, auth, pilot.sha256_text(pilot.PILOT_PROMPT), pilot.DEFAULT_ENDPOINT,
            permission_policy_sha256=POLICY_SHA, admission_basis="new_experimental_session",
            inbox_evidence="empty:0", repository_source="git-local",
            admission_authorized=True, exclusive_consumer=True,
            exclusive_consumer_evidence_sha256=EXCLUSIVE_SHA, session_cost=0)
        manifest = replace(manifest, issued_at=NOW, expires_at=NOW + timedelta(hours=1))
        binding = {"manifest_sha256": manifest.canonical(),
                   "bound_material_sha256": manifest.canonical(),
                   "approver_type": "human", "approver_id": auth.maintainer_id,
                   "approval_reference": auth.approval_reference}
        approval = pilot.PilotApproval("APP", auth.maintainer_id, "human", "APPROVED", manifest,
                                       approval_binding=binding)
        transport = pilot.OpenCodeTransport(
            client, expected_instance_id=auth.instance_id, registered_targets=(auth.target,),
            pilot_authorization=auth, mode="real", allow_write=True, clock=lambda: NOW)
        store = pilot.PilotOperationStore(tempfile.mkdtemp())
        ex = pilot.PilotExecutor(transport, store, approval, endpoint=pilot.DEFAULT_ENDPOINT,
                                 allow_real=True, clock=lambda: NOW, observer=lambda: evidence)
        return m, ex, transport, manifest

    def test_full_synthetic_path_one_transmit(self):
        m, ex, transport, _ = self._executor(fresh_evidence())
        with patch.object(transport, "status", return_value=cc.SessionStatus(
                transport.pilot.target, "idle", 0, {})), \
             patch.object(transport, "lookup_target", return_value=transport.pilot.target), \
             patch.object(transport, "prepare", return_value=cc.PreparedRequest(
                 "msg-pilot-1", m, replace(hx.core_auth(m), issued_at=NOW,
                 expires_at=NOW + timedelta(hours=1)), NOW)), \
             patch.object(transport, "transmit", return_value=cc.TransportReceipt(
                 m.operation_id, "accepted", "msg-pilot-1", {}, NOW)) as transmit, \
             patch.object(cc.CommunicationEngine, "_validate_prepared_request",
                          return_value=None):
            out = ex.execute(m, replace(hx.core_auth(m), issued_at=NOW, expires_at=NOW + timedelta(hours=1)), pilot.PILOT_PROMPT)
        self.assertTrue(out["executed"])
        self.assertEqual(transmit.call_count, 1)

    def test_p0_mutations_produce_zero_post(self):
        mutations = {
            "policy": lambda e: e.update(permission_policy_sha256="b" * 64),
            "tokens": lambda e: e.update(tokens={"input": 1, "output": 0, "reasoning": 0}),
            "catalog": lambda e: e.update(model_catalog_evidence={"confirmed": False}),
            "exclusivity": lambda e: e.update(exclusive_consumer_evidence={
                "source": "multiple-consumers", "session_id": ax.SID}),
        }
        for name, mutate in mutations.items():
            with self.subTest(mutation=name):
                evidence = fresh_evidence()
                mutate(evidence)
                m, ex, transport, _ = self._executor(evidence)
                with patch.object(transport, "prepare", return_value=object()) as prepare, \
                     patch.object(transport, "transmit", side_effect=AssertionError("POST")):
                    with self.assertRaises(cc.AuthorizationError):
                        ex.execute(m, replace(hx.core_auth(m), issued_at=NOW, expires_at=NOW + timedelta(hours=1)), pilot.PILOT_PROMPT)
                prepare.assert_not_called()


class CatalogInPrepareTests(unittest.TestCase):
    def test_prepare_without_catalog_blocks_and_with_catalog_emits_proposal(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": pilot.instance_identity(ax.INFO),
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH-P11"})
            with patch.object(cli, "_real_client",
                              return_value=ax.ScriptedClient(ax.full_responses())):
                blocked = cli.run(["--store-dir", td, "prepare", "--real", "--endpoint", ax.EP,
                                   "--session", ax.SID])
            self.assertTrue(blocked["blocked"])
            catalog_path = os.path.join(td, "catalog.json")
            with open(catalog_path, "w", encoding="utf-8") as handle:
                handle.write('{"data":[{"providerID":"opencode","id":"hy4-flash-free",'
                             '"active":true,"enabled":true,"variants":[{"id":"none"}],'
                             '"cost":[{"input":0,"output":0,"cache":{"read":0,"write":0}}]}]}')
            with patch.object(cli, "_real_client",
                              return_value=ax.ScriptedClient(ax.full_responses())):
                out = cli.run(["--store-dir", td, "prepare", "--real", "--endpoint", ax.EP,
                               "--session", ax.SID, "--catalog", catalog_path])
            self.assertFalse(out.get("blocked", False))
            self.assertIn("manifest", out)


class ProbeAndRebindTests(unittest.TestCase):
    def test_probe_requires_verified_hash_and_offline_session(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": "inst", "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "build",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH"})
            bad = cli.run(["--store-dir", td, "permission-probe-plan"])
            self.assertTrue(bad["blocked"])
            session["permission_policy_sha256"] = POLICY_SHA
            with open(cli._session_registry_path(td), "w", encoding="utf-8") as handle:
                import json
                json.dump(session, handle)
            policy_path = os.path.join(td, "policy.json")
            with open(policy_path, "w", encoding="utf-8") as handle:
                import json as _json
                _json.dump({"policy": ax.SESSION_META["data"]["permissions"]}, handle)
            good = cli.run(["--store-dir", td, "permission-probe-plan",
                            "--policy-evidence", policy_path])
            self.assertFalse(good["blocked"])
            self.assertTrue(good["policy_preserved"])
            self.assertTrue(good["ask_handling"]["requires_specific_authorization"])
            self.assertEqual(good["limits"]["max_evaluation_posts"],
                             len(cli.PERMISSION_PROBE_MATRIX))

    def test_rebind_preserves_full_legacy_identity(self):
        legacy = {"session_id": ax.SID, "instance_id": "inst-antiga", "endpoint": ax.EP,
                  "project_id": pilot.EXPECTED_PROJECT_ID,
                  "repository_id": pilot.EXPECTED_REPOSITORY_ID,
                  "authorization_reference": "AUTH-LEGADO", "agent_id": "build",
                  "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE}
        with patch.object(cli, "_real_client",
                          return_value=ax.ScriptedClient(ax.full_responses())):
            observed = cli._real_observe(ax.EP, session=legacy, expect_instance_change=True)
        proposal = cli.prepare_rebind(legacy, observed, endpoint=ax.EP)
        preserved = proposal["update_proposal"]["preserved"]
        self.assertEqual(preserved["session_id"], ax.SID)
        self.assertEqual(preserved["repository_id"], pilot.EXPECTED_REPOSITORY_ID)
        self.assertEqual(preserved["authorization_reference"], "AUTH-LEGADO")
        self.assertFalse(proposal["applied"])


if __name__ == "__main__":
    unittest.main()
