#!/usr/bin/env python3
"""Regressões focais P3B.5: manifesto em runtime, evidência explícita, cadastro e probe."""
from __future__ import annotations

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
import test_pilot_api_compat as ax
import test_pilot_executor as hx

CLOCK = datetime(2026, 11, 26, 12, 0, tzinfo=timezone.utc)
EVIDENCE = {
    "permission_policy_sha256": cli.canonical_policy_sha256(ax.SESSION_META["data"]["permissions"]),
    "admission_basis": "new_experimental_session",
    "inbox_evidence": "empty:0",
    "repository_source": "git-local",
    "admission_authorized": True,
    "exclusive_consumer": True,
}


def approved_manifest(**changes):
    ctx = cli._fixture_context(store_dir=tempfile.mkdtemp())
    return pilot.build_runtime_manifest(
        ctx["mission"], ctx["auth"], pilot.sha256_text(pilot.PILOT_PROMPT),
        pilot.DEFAULT_ENDPOINT, **{**EVIDENCE, **changes})


class RuntimeManifestTests(unittest.TestCase):
    def test_prepared_manifest_reconstructs_identically(self):
        manifest = approved_manifest()
        rebuilt = pilot.build_runtime_manifest(
            cli._fixture_context(store_dir=tempfile.mkdtemp())["mission"],
            cli._fixture_context(store_dir=tempfile.mkdtemp())["auth"],
            pilot.sha256_text(pilot.PILOT_PROMPT), pilot.DEFAULT_ENDPOINT,
            **{k: getattr(manifest, k) for k in EVIDENCE})
        self.assertEqual(manifest.canonical(), rebuilt.canonical())
        approval = pilot.PilotApproval("APP", "humano", "human", "FIXTURE", manifest)
        self.assertTrue(approval.matches(rebuilt))

    def test_material_change_invalidates_approval(self):
        manifest = approved_manifest()
        approval = pilot.PilotApproval("APP", "humano", "human", "FIXTURE", manifest)
        changed = pilot.build_runtime_manifest(
            cli._fixture_context(store_dir=tempfile.mkdtemp())["mission"],
            cli._fixture_context(store_dir=tempfile.mkdtemp())["auth"],
            pilot.sha256_text(pilot.PILOT_PROMPT), pilot.DEFAULT_ENDPOINT,
            **{**EVIDENCE, "permission_policy_sha256": "b" * 64})
        self.assertFalse(approval.matches(changed))
        self.assertNotEqual(manifest.canonical(), changed.canonical())

    def test_missing_evidence_blocks_real_execution(self):
        m = hx.mission()
        auth = hx.pilot_auth(m, environment="APPROVED", instance_id=hx.INSTANCE)
        client, _ = hx.fake_client()
        transport = pilot.OpenCodeTransport(client, expected_instance_id=hx.INSTANCE,
                                            registered_targets=(hx.TARGET,),
                                            pilot_authorization=auth, mode="real",
                                            allow_write=False, clock=lambda: CLOCK)
        manifest = replace(approved_manifest(permission_policy_sha256="",
                                             admission_authorized=False),
                           issued_at=CLOCK, expires_at=CLOCK + timedelta(hours=1))
        binding = {"manifest_sha256": manifest.canonical(),
                   "bound_material_sha256": manifest.canonical(),
                   "approver_type": "human", "approver_id": "humano"}
        approval = pilot.PilotApproval("APP", "humano", "human", "APPROVED", manifest,
                                       approval_binding=binding)
        with tempfile.TemporaryDirectory() as td:
            ex = pilot.PilotExecutor(transport, pilot.PilotOperationStore(td), approval,
                                     endpoint=pilot.DEFAULT_ENDPOINT, allow_real=True,
                                     clock=lambda: CLOCK)
            with self.assertRaises(cc.AuthorizationError) as ctx:
                ex._validate(m, hx.core_auth(m), pilot.PILOT_PROMPT)
        self.assertIn("evidências obrigatórias ausentes", str(ctx.exception))
        self.assertEqual([c for c in client.calls if c[0] == "POST"], [])


class AdmissionEvidenceTests(unittest.TestCase):
    def test_missing_tokens_or_cost_are_not_zero(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": pilot.instance_identity(ax.INFO),
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH"})
            responses = ax.full_responses()
            value = dict(ax.SESSION_META["data"])
            value.pop("tokens")
            responses[f"/api/session/{ax.SID}"] = (200, {"data": value})
            with patch.object(cli, "_real_client", return_value=ax.ScriptedClient(responses)):
                observed = cli._real_observe(ax.EP, session=session)
        self.assertFalse(observed["admission_basis"]["zero_cost_tokens"])
        self.assertIn("tokens/custo não observados explicitamente como zero",
                      observed["admission_blockers"])

    def test_descriptive_fresh_is_not_authorized_admission(self):
        with tempfile.TemporaryDirectory() as td:
            session = cli._register_session(td, {
                "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
                "instance_id": pilot.instance_identity(ax.INFO),
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
                "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": "AUTH"})
            with patch.object(cli, "_real_client", return_value=ax.ScriptedClient(ax.full_responses())):
                observed = cli._real_observe(ax.EP, session=session)
        self.assertEqual(observed["availability_state"], "FRESH_UNEXECUTED")
        self.assertFalse(observed["authorized_admission"])
        self.assertIn("admissão operacional não autorizada externamente",
                      cli._manifest_readiness_blockers(observed, session))


class RegistryCompatibilityTests(unittest.TestCase):
    def test_legacy_registry_gaps_are_reported_not_filled(self):
        legacy = {"session_id": "ses_de83b0620ffeMIQP5tvH7MLFEx",
                  "instance_id": "instance-antiga", "endpoint": ax.EP,
                  "project_id": pilot.EXPECTED_PROJECT_ID, "agent_id": "build",
                  "model": cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE}
        gaps = cli.registry_gaps(legacy)
        self.assertIn("permission_policy_sha256", gaps)
        with patch.object(cli, "_real_client",
                          return_value=ax.ScriptedClient(ax.full_responses())):
            observed = cli._real_observe(ax.EP, session=legacy, expect_instance_change=True)
        proposal = cli.prepare_rebind(legacy, observed, endpoint=ax.EP)
        self.assertTrue(proposal["blocked"])
        self.assertEqual(proposal["registry_gaps"], gaps)
        self.assertFalse(proposal["update_proposal"]["applied"])
        self.assertTrue(proposal["update_proposal"]["requires_replace"])
        self.assertEqual(proposal["update_proposal"]["preserved"]["session_id"],
                         legacy["session_id"])


class PermissionProbeTests(unittest.TestCase):
    def test_plan_is_closed_and_harmless(self):
        plan = cli.build_permission_probe_plan(session_id="ses_probe", policy_sha256="a" * 64)
        self.assertEqual(plan["session_id"], "ses_probe")
        self.assertTrue(plan["no_prompt"] and plan["no_llm"] and plan["no_tool_execution"])
        self.assertTrue(plan["no_save"] and plan["question_is_tool"])
        self.assertTrue(plan["deny_is_not_exhaustive"])
        limits = plan["limits"]
        self.assertEqual(limits["max_evaluation_posts"], len(cli.PERMISSION_PROBE_MATRIX))
        self.assertEqual(plan["max_requests"], sum(limits.values()))
        text = json.dumps(plan, ensure_ascii=False)
        self.assertNotIn("rm -rf", text)
        self.assertEqual(plan["ask_handling"]["decision"], "reject")
        effects = {row["action"]: row["effect"] for row in plan["expected_results"]}
        self.assertEqual(effects["shell"], "deny")
        self.assertEqual(effects["question"], "allow")

    def test_cli_plan_requires_registered_session(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "permission-probe-plan"])
        self.assertTrue(out["blocked"])
        self.assertNotIn("expected_results", out)


if __name__ == "__main__":
    unittest.main()
