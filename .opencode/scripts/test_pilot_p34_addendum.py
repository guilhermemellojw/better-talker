#!/usr/bin/env python3
"""Regressões do adendo P10: disponibilidade unificada, respostas malformadas, Git e catálogo."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli
import test_pilot_api_compat as ax
import test_pilot_p34 as t34

SID = ax.SID


def observed(responses=None, session=None, td=None):
    ctx = td or tempfile.TemporaryDirectory()
    sess = session or t34.registered(ctx.name if hasattr(ctx, "name") else ctx)
    with patch.object(cli, "_real_client", return_value=ax.ScriptedClient(
            responses or ax.full_responses())):
        out = cli._real_observe(ax.EP, session=sess)
    return out, sess, ctx


class AvailabilityTests(unittest.TestCase):
    def test_real_form_is_fresh_unexecuted(self):
        out, _, td = observed()
        try:
            self.assertEqual(out["availability_state"], "FRESH_UNEXECUTED")
            self.assertIsNone(out["outcome"])
            self.assertEqual(out["session_repository_status"], "not_returned")
            self.assertTrue(out["repository_evidence"]["binding"] is not None)
        finally:
            td.cleanup()

    def test_active_target_blocks_admission(self):
        responses = ax.full_responses()
        responses[pilot.ACTIVE_PATH] = (200, {"data": {SID: {"type": "running"}}})
        out, _, td = observed(responses)
        try:
            self.assertEqual(out["availability_state"], "BUSY")
            self.assertTrue(out["session_in_active"])
            self.assertNotIn("new_experimental_session", (out["admission_state"],))
        finally:
            td.cleanup()

    def test_active_target_with_outcome_is_busy(self):
        responses = ax.full_responses()
        responses[pilot.ACTIVE_PATH] = (200, {"data": {SID: {"type": "running"}}})
        responses[f"/api/session/{SID}"] = (200, {"data": dict(
            ax.SESSION_META["data"], outcome="succeeded")})
        out, _, td = observed(responses)
        try:
            self.assertEqual(out["availability_state"], "BUSY")
        finally:
            td.cleanup()

    def test_busy_greater_than_idle_blocks(self):
        responses = ax.full_responses()
        responses[f"/api/session/{SID}"] = (200, {"data": dict(
            ax.SESSION_META["data"], time={"created": 1, "updated": 1, "busy": 7, "idle": 0})})
        out, _, td = observed(responses)
        try:
            self.assertEqual(out["availability_state"], "BUSY")
            self.assertIn("busy > idle", out["availability_reasons"])
        finally:
            td.cleanup()

    def test_arbitrary_outcome_is_unknown(self):
        responses = ax.full_responses()
        responses[f"/api/session/{SID}"] = (200, {"data": dict(
            ax.SESSION_META["data"], outcome="arbitrario")})
        out, _, td = observed(responses)
        try:
            self.assertEqual(out["availability_state"], "UNKNOWN")
            self.assertIn("outcome fora do enum documentado", out["availability_reasons"])
        finally:
            td.cleanup()


class MalformedResponseTests(unittest.TestCase):
    def test_active_200_without_data(self):
        responses = ax.full_responses()
        responses[pilot.ACTIVE_PATH] = (200, {})
        out, _, td = observed(responses)
        try:
            self.assertFalse(out["active_observed"])
            self.assertEqual(out["availability_state"], "UNKNOWN")
        finally:
            td.cleanup()

    def test_inbox_200_without_data_and_missing_count(self):
        responses = ax.full_responses()
        responses[f"/api/session/{SID}{pilot.INBOX_SUFFIX}"] = (200, {})
        out, _, td = observed(responses)
        try:
            self.assertFalse(out["inbox_observation"]["inbox_observed"])
            self.assertIsNone(out["inbox_observation"]["inbox_count"])
            self.assertEqual(out["availability_state"], "UNKNOWN")
        finally:
            td.cleanup()

    def test_inbox_failure_is_not_empty(self):
        responses = ax.full_responses()
        responses[f"/api/session/{SID}{pilot.INBOX_SUFFIX}"] = (500, {"error": "boom"})
        out, _, td = observed(responses)
        try:
            self.assertEqual(out["inbox_observation"]["state"], "http_error")
            self.assertIsNone(out["inbox_count"])
        finally:
            td.cleanup()


class GitBindingTests(unittest.TestCase):
    def test_common_dir_origin_canonical_worktree_project(self):
        with tempfile.TemporaryDirectory() as td:
            session = t34.registered(td)
            out, _, ctx = observed(session=session)
            try:
                binding = out["repository_evidence"]["binding"]
                self.assertEqual(binding["canonical"], pilot.EXPECTED_CANONICAL_DIR)
                self.assertEqual(binding["worktree"], pilot.EXPERIMENTAL_WORKTREE)
                self.assertTrue(binding["common_dir"].endswith("/.git"))
                self.assertEqual(binding["origin_repository_id"], pilot.EXPECTED_REPOSITORY_ID)
                self.assertEqual(binding["project_id"], pilot.EXPECTED_PROJECT_ID)
                self.assertEqual(out["session_repository_status"], "not_returned")
            finally:
                ctx.cleanup()

    def test_divergent_common_dir_blocks_identity(self):
        with tempfile.TemporaryDirectory() as td:
            session = t34.registered(td)
            with patch.object(pilot, "git_binding_evidence",
                              side_effect=cc.ContractError("common-dir divergente")):
                with self.assertRaises(cc.ContractError):
                    pilot.validate_observed_identity(
                        ax.LOCATION, project_id=pilot.EXPECTED_PROJECT_ID)


class ModelCatalogTests(unittest.TestCase):
    CATALOG = {"data": [{"providerID": "opencode", "id": "hy4-flash-free", "active": True,
                         "enabled": True, "variants": [{"id": "none"}, {"id": "high"}],
                         "cost": [{"input": 0, "output": 0,
                                   "cache": {"read": 0, "write": 0}}]}]}

    def test_exact_free_catalog_confirmed(self):
        result = pilot.verify_model_catalog(self.CATALOG, provider_id="opencode",
                                            model_id="hy4-flash-free", variant="none")
        self.assertTrue(result["confirmed"])
        self.assertEqual(result["canonical"], "opencode/hy4-flash-free")

    def test_paid_or_unknown_cost_blocked(self):
        paid = {"data": [dict(self.CATALOG["data"][0],
                              cost=[{"input": 1, "output": 2, "cache": {}}])]}
        result = pilot.verify_model_catalog(paid, provider_id="opencode",
                                            model_id="hy4-flash-free", variant="none")
        self.assertFalse(result["confirmed"])
        self.assertIn("custo desconhecido ou divergente", " ".join(result["reasons"]))

    def test_wrong_variant_and_absent_model_blocked(self):
        self.assertFalse(pilot.verify_model_catalog(
            self.CATALOG, provider_id="opencode", model_id="hy4-flash-free",
            variant="ultra")["confirmed"])
        self.assertFalse(pilot.verify_model_catalog(
            self.CATALOG, provider_id="opencode", model_id="outro", variant="none")["confirmed"])


class TransportStatusTests(unittest.TestCase):
    def test_status_does_not_claim_idle_from_time_heuristics(self):
        with tempfile.TemporaryDirectory() as td:
            ctx = cli._fixture_context(store_dir=td)
            sid = cli.SESSION_ID
            ctx["client"].responses[("GET", f"/api/session/{sid}/permission")] = (200, {"data": []})
            transport = ctx["transport"]
            transport.mode = "real"
            transport.is_real = True
            status = transport.status(transport.pilot.target)
        self.assertNotEqual(status.state, "idle")
        self.assertEqual(status.evidence["availability_state"], "UNKNOWN")

    def test_status_fresh_unexecuted_is_admissible(self):
        with tempfile.TemporaryDirectory() as td:
            ctx = cli._fixture_context(store_dir=td)
            sid = cli.SESSION_ID
            key = ("GET", f"/api/session/{sid}")
            response = ctx["client"].responses[key][1]["data"]
            response.pop("time", None)
            response["time"] = {"created": 1, "updated": 1}
            response.pop("outcome", None)
            ctx["client"].responses[("GET", f"/api/session/{sid}/permission")] = (200, {"data": []})
            transport = ctx["transport"]
            transport.mode = "real"
            transport.is_real = True
            status = transport.status(transport.pilot.target)
        self.assertEqual(status.evidence["availability_state"], "FRESH_UNEXECUTED")
        self.assertEqual(status.state, "idle")


class PermissionsAndHygieneTests(unittest.TestCase):
    def test_pending_empty_does_not_prove_enforcement(self):
        out, session, td = observed()
        try:
            self.assertTrue(out["pending_permissions_observed"])
            self.assertEqual(out["pending_permission_count"], 0)
            self.assertFalse(out["permissions_evidence"]["enforcement_observed"])
            self.assertIn("enforcement de permissões não observado",
                          cli._manifest_readiness_blockers(out, session))
        finally:
            td.cleanup()

    def test_blockers_are_deduplicated(self):
        out, session, td = observed()
        try:
            blockers = cli._manifest_readiness_blockers(out, session)
            self.assertEqual(len(blockers), len(set(blockers)))
        finally:
            td.cleanup()

    def test_historical_hungerg_fixtures_are_labeled(self):
        for name in ("test_pilot_executor.py", "test_pilot_closure.py"):
            with open(os.path.join(HERE, name), encoding="utf-8") as handle:
                text = handle.read()
            self.assertTrue("sintético" in text or "sintética" in text)
            self.assertIn("hungerg", text)


if __name__ == "__main__":
    unittest.main()
