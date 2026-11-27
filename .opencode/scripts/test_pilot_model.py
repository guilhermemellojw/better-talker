#!/usr/bin/env python3
"""Regressões focais P3B.3.1: modelo experimental HY4 Flash Free (A–I)."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from copy import deepcopy
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli
import test_pilot_api_compat as ax


def model_variant(provider_id, model_id, variant):
    return {"providerID": provider_id, "id": model_id, "variant": variant}


def register(td, *, model=None):
    return cli._register_session(td, {
        "session_id": ax.SID, "experimental": True, "endpoint": ax.EP,
        "instance_id": pilot.instance_identity(ax.INFO),
        "project_id": pilot.EXPECTED_PROJECT_ID,
        "repository_id": pilot.EXPECTED_REPOSITORY_ID, "agent_id": "agente-observado",
        "model": model or cli.ALLOWED_FREE_MODEL, "location": pilot.EXPERIMENTAL_WORKTREE,
        "authorization_reference": "AUTH-P3B3-001", "permissions_confirmed": True})


def observed_for(session, model_value, *, keep_model=True):
    responses = ax.full_responses()
    data = deepcopy(ax.SESSION_META["data"])
    if keep_model:
        data["model"] = model_value
    else:
        data.pop("model", None)
    responses[f"/api/session/{ax.SID}"] = (200, {"data": data})
    with patch.object(cli, "_real_client", return_value=ax.ScriptedClient(responses)):
        return cli._real_observe(ax.EP, session=session)


class ModelPolicyTests(unittest.TestCase):
    def test_a_canonical_variant_and_hy4_accepted(self):
        self.assertEqual(pilot.EXPERIMENTAL_MODEL, "opencode/hy4-flash-free")
        self.assertEqual(pilot.canonical_model_ref("opencode", "hy4-flash-free", "none"),
                         "opencode/hy4-flash-free")
        self.assertEqual(pilot.canonical_model_ref("opencode", "hy4-flash-free", None),
                         "opencode/hy4-flash-free")
        self.assertEqual(pilot.canonical_model_ref("opencode", "hy4-flash-free", "high"),
                         "opencode/hy4-flash-free#high")
        with tempfile.TemporaryDirectory() as td:
            session = register(td)
            observed = observed_for(session, model_variant("opencode", "hy4-flash-free", "none"))
            self.assertTrue(observed["model_confirmed"])
            self.assertTrue(observed["session_identity_confirmed"])
            ax.make_synthetic_ready(observed)  # manifesto sintético apenas em teste
            with patch.object(pilot, "repository_identity_from_git",
                              return_value=pilot.EXPECTED_REPOSITORY_ID), \
                 patch.object(pilot, "executed_head_sha", return_value="a" * 40), \
                 patch.object(pilot, "operational_file_digests", return_value={"x.py": "b" * 64}):
                result = cli._prepare_real(observed, session=session, clock=ax.CLOCK)
        self.assertFalse(result.get("blocked", False))
        self.assertEqual(result["manifest"]["model"], "opencode/hy4-flash-free")

    def test_b_hungerg_is_no_longer_authorized(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                register(td, model="opencode-go/hungerg-free#default")
            session = register(td)
            observed = observed_for(session, model_variant("opencode-go", "hungerg-free", "default"))
            self.assertFalse(observed["model_confirmed"])
            self.assertIn("modelo divergente", " ".join(observed["blockers"]))

    def test_c_divergent_variant_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            session = register(td)
            observed = observed_for(session, model_variant("opencode", "hy4-flash-free", "high"))
            self.assertFalse(observed["model_confirmed"])
            self.assertEqual(observed["session_model"], "opencode/hy4-flash-free#high")

    def test_d_paid_and_free_substring_models_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            session = register(td)
            for model in (model_variant("groq", "openai/gpt-oss-120b", "high"),
                          model_variant("opencode", "hy4-flash-free-paid", None)):
                with self.subTest(model=model):
                    observed = observed_for(session, model)
                    self.assertFalse(observed["model_confirmed"])

    def test_e_absent_model_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            session = register(td)
            observed = observed_for(session, None, keep_model=False)
            self.assertEqual(observed["session_model"], "")
            self.assertFalse(observed["model_confirmed"])

    def test_f_no_automatic_substitution(self):
        with tempfile.TemporaryDirectory() as td:
            session = register(td)
            observed = observed_for(session, model_variant("opencode", "seaglass-alpha-free", "minimal"))
            self.assertEqual(observed["session_model"], "opencode/seaglass-alpha-free#minimal")
            self.assertNotEqual(observed["session_model"], cli.ALLOWED_FREE_MODEL)

    def test_g_endpoint_and_auth_protections_intact(self):
        with self.assertRaises(cc.ContractError):
            pilot.canonical_endpoint("https://externo.example.com")
        auth = pilot.ExperimentalAuth(ax.EP, password_provider=lambda: "sintetico")
        with self.assertRaises(cc.ContractError):
            auth.header("http://127.0.0.1:51111")

    def test_h_readonly_client_blocks_post(self):
        client = cli._real_client(ax.EP)
        with self.assertRaises(cc.TransportDisabled):
            client.request("POST", "/api/session/x/prompt", {"id": "x"})

    def test_i_manifest_and_transmission_remain_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "execute"])
            self.assertEqual(out["state"], "BLOCKED_BY_DEFAULT")
            self.assertEqual(out["real_calls"], 0)
            prepared = cli.run(["--store-dir", td, "prepare", "--real", "--endpoint", ax.EP])
            self.assertTrue(prepared["blocked"])
            self.assertNotIn("manifest", prepared)


if __name__ == "__main__":
    unittest.main()
