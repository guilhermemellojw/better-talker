#!/usr/bin/env python3
"""Regressões P3B.0: endpoint canônico, separação fixture/real e cliente real somente leitura."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from dataclasses import replace

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint  # noqa: F401  (contrato BT-COORD-REPORT:v1)
import coord_communication as cc
import coord_pilot as pilot
import coord_pilot_cli as cli
import test_pilot_executor as h

NOW = h.NOW
PROMPT = h.PROMPT
ENDPOINT = pilot.DEFAULT_ENDPOINT


class EndpointCanonicalTests(unittest.TestCase):
    def test_canonical_rules(self):
        self.assertEqual(pilot.canonical_endpoint("http://127.0.0.1:49999/"), ENDPOINT)
        self.assertEqual(pilot.canonical_endpoint(ENDPOINT, allowed_port=49999), ENDPOINT)
        for value in ("https://127.0.0.1:49999", "http://user@127.0.0.1:49999",
                      "http://127.0.0.1:49999/?q=1", "http://127.0.0.1:49999/#f",
                      "http://127.0.0.1:49999/path", "http://127.0.0.1:70000",
                      "http://localhost:49999", "http://127.0.0.1", ""):
            with self.subTest(value=value), self.assertRaises(cc.ContractError):
                pilot.canonical_endpoint(value)
        with self.assertRaises(cc.ContractError):
            pilot.canonical_endpoint("http://127.0.0.1:51111", allowed_port=49999)

    def test_manifest_rejects_external_endpoint(self):
        manifest = h.approval().manifest
        with self.assertRaises(cc.ContractError):
            replace(manifest, endpoint="https://externo.example.com/api")
        with self.assertRaises(cc.ContractError):
            replace(manifest, endpoint="http://externo.example.com/prompt")

    def test_authorization_endpoint_is_canonical_and_bound(self):
        base = h.pilot_auth()
        self.assertEqual(base.endpoint, ENDPOINT)
        self.assertNotEqual(base.canonical(), replace(base, endpoint="http://127.0.0.1:51111").canonical())
        with self.assertRaises(cc.ContractError):
            replace(base, endpoint="https://externo.example.com")

    def test_manifest_endpoint_follows_effective_client(self):
        context = self._context(ENDPOINT)
        self.assertEqual(context["manifest"].endpoint, context["client"].endpoint)
        self.assertTrue(context["approval"].matches(context["manifest"]))

    @staticmethod
    def _context(endpoint):
        client = h.fake_client()[0]
        client.endpoint = endpoint
        client.base_url = endpoint
        m = h.mission()
        auth = h.pilot_auth(m, instance_id=h.INSTANCE, endpoint=endpoint)
        transport = pilot.OpenCodeTransport(client, expected_instance_id=h.INSTANCE,
                                            registered_targets=(h.TARGET,),
                                            pilot_authorization=auth, allow_write=True,
                                            clock=lambda: NOW, mode="fixture")
        ap = h.approval(auth)
        store = pilot.PilotOperationStore(tempfile.mkdtemp())
        ex = pilot.PilotExecutor(transport, store, ap, endpoint=endpoint,
                                 allow_real=False, clock=lambda: NOW)
        return {"client": client, "mission": m, "executor": ex,
                "manifest": ap.manifest, "approval": ap}


class EndpointBindingTests(unittest.TestCase):
    def _attempt(self, *, declared=ENDPOINT, client_endpoint=ENDPOINT,
                 auth_endpoint=None, mutate=None):
        client = h.fake_client()[0]
        client.endpoint = client_endpoint
        client.base_url = client_endpoint
        try:
            m = h.mission()
            auth = h.pilot_auth(m, instance_id=h.INSTANCE)
            if auth_endpoint:
                auth = replace(auth, endpoint=auth_endpoint)
            transport = pilot.OpenCodeTransport(client, expected_instance_id=h.INSTANCE,
                                                registered_targets=(h.TARGET,),
                                                pilot_authorization=auth, allow_write=True,
                                                clock=lambda: NOW, mode="fixture")
            ap = h.approval(auth)
            ex = pilot.PilotExecutor(transport,
                                     pilot.PilotOperationStore(tempfile.mkdtemp()), ap,
                                     endpoint=declared, allow_real=False, clock=lambda: NOW)
            if mutate:
                mutate(client)
            out = ex.execute(m, h.core_auth(m), PROMPT)
            state = out["state"]
        except Exception as e:
            state = f"blocked:{type(e).__name__}"
        posts = [p for method, p, _ in client.calls if method == "POST"]
        return state, posts

    def test_divergent_declared_port_zero_post(self):
        state, posts = self._attempt(declared="http://127.0.0.1:51111",
                                     client_endpoint=ENDPOINT)
        self.assertTrue(state.startswith("blocked:"))
        self.assertEqual(posts, [])

    def test_external_manifest_endpoint_zero_post(self):
        state, posts = self._attempt(declared="https://externo.example.com/api")
        self.assertTrue(state.startswith("blocked:"))
        self.assertEqual(posts, [])

    def test_external_authorization_endpoint_zero_post(self):
        state, posts = self._attempt(auth_endpoint="https://externo.example.com")
        self.assertTrue(state.startswith("blocked:"))
        self.assertEqual(posts, [])

    def test_endpoint_mutated_before_post_zero_post(self):
        state, posts = self._attempt(
            mutate=lambda c: setattr(c, "endpoint", "http://127.0.0.1:51111"))
        self.assertTrue(state.startswith("blocked:"))
        self.assertEqual(posts, [])

    def test_authorized_simulated_case_single_post(self):
        state, posts = self._attempt()
        self.assertEqual(state, "DISPATCHING")
        self.assertEqual(len(posts), 1)


class FixtureRealSeparationTests(unittest.TestCase):
    def test_readonly_client_blocks_post_mechanically(self):
        client = pilot.ReadOnlyHttpClient(ENDPOINT, allowed_port=49999)
        self.assertEqual(client.endpoint, ENDPOINT)
        self.assertFalse(client.allow_write)
        with self.assertRaises(cc.TransportDisabled):
            client.request("POST", "/api/session/x/prompt", {"id": "x"})

    def test_all_fixture_outputs_are_labeled(self):
        with tempfile.TemporaryDirectory() as td:
            for command in ("preflight", "prepare", "execute", "reconcile",
                            "closeout", "dry-run"):
                with self.subTest(command=command):
                    out = cli.run(["--store-dir", td, command])
                    self.assertEqual(out["source"], "fixture")
                    self.assertFalse(out["service_contacted"])
                    if command != "execute":
                        self.assertEqual(out["mode"], "fixture")

    def test_real_preflight_failure_has_no_fixture_fallback(self):
        # Isolamento: nunca ler o registry real persistente.
        with tempfile.TemporaryDirectory() as isolated_store:
            out = cli.run(["--store-dir", isolated_store, "preflight", "--real",
                           "--endpoint", "http://127.0.0.1:51111"])
        # P14A: falha de conexão não é evidência de transporte real.
        self.assertEqual(out["source"], "declared-real")
        self.assertEqual(out["mode"], "real")
        self.assertFalse(out["service_contacted"])
        self.assertEqual(out["fallback"], "none")
        self.assertIn("falha de conexão", " ".join(out["blockers"]))
        # Nenhum valor de fixture é reapresentado como observação real.
        self.assertNotIn("agent_id", out["observed"])

    def test_real_prepare_without_service_does_not_emit_manifest(self):
        with tempfile.TemporaryDirectory() as isolated_store:
            out = cli.run(["--store-dir", isolated_store, "prepare", "--real",
                           "--endpoint", "http://127.0.0.1:51111"])
        # P14A: modo real declarado sem contato não é evidência de serviço real.
        self.assertEqual(out["source"], "declared-real")
        self.assertTrue(out["blocked"])
        self.assertNotIn("manifest", out)
        self.assertFalse(out["approved"])

    def test_real_store_is_separate_from_fixture(self):
        _, fixture_options = cli._parse(["preflight"])
        _, real_options = cli._parse(["preflight", "--real"])
        self.assertNotEqual(fixture_options["store_dir"], real_options["store_dir"])
        self.assertEqual(real_options["store_dir"], cli.REAL_STORE_DIR)

    def test_dry_run_does_not_touch_real_store(self):
        with tempfile.TemporaryDirectory() as real_dir, tempfile.TemporaryDirectory() as fx_dir:
            out = cli.run(["--store-dir", fx_dir, "dry-run"])
            self.assertEqual(out["source"], "fixture")
            self.assertEqual(out["http_posts"], 1)
            self.assertEqual(out["final_state"], "COMPLETED")
            self.assertFalse(os.path.exists(os.path.join(real_dir, "operation.json")))


if __name__ == "__main__":
    unittest.main()
