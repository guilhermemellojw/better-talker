#!/usr/bin/env python3
"""COORD-04/P14A.4 — cache não autoritativo, ledger como âncora e gates CLI."""
from __future__ import annotations

import concurrent.futures
import json
import os
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from unittest.mock import patch

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_ledger as ledger
import coord_p14_adapter as adapter
import coord_pilot as pilot
import coord_pilot_cli as cli

CLI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "coord_pilot_cli.py")
MISSION_ID = "MISS-COORD04-PILOT-001"
SID = cli.SESSION_ID


def cli_run(store_dir, *args):
    return subprocess.run([sys.executable, CLI, "--store-dir", store_dir, *args],
                          capture_output=True, text=True, timeout=120)


def read_cache(store_dir):
    with open(os.path.join(store_dir, "evidence-cache.json"), encoding="utf-8") as handle:
        return json.load(handle)


def write_cache(store_dir, value):
    with open(os.path.join(store_dir, "evidence-cache.json"), "w", encoding="utf-8") as handle:
        json.dump(value, handle, sort_keys=True)


def message_key():
    return ("GET", f"/api/session/{SID}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc")


def alter_report(client, *, drop_acceptance=False, wrong_reply_to=False):
    key = message_key()
    status, payload = client.responses[key]
    items = json.loads(json.dumps(payload["data"]))
    for item in items:
        if item.get("type") != "assistant":
            continue
        text = item["content"][0]["text"]
        head, body = text.split("contract:", 1)
        value = json.loads(body)
        if drop_acceptance:
            value.pop("acceptance_results", None)
        if wrong_reply_to:
            item["reply_to"] = "msg-outra"
        item["content"][0]["text"] = head + "contract: " + cc.canonical_json(value)
    client.responses[key] = (status, {"data": items})


def alter_prompt(client, text="prompt adulterado"):
    key = message_key()
    status, payload = client.responses[key]
    items = json.loads(json.dumps(payload["data"]))
    for item in items:
        if item.get("type") == "user":
            item["content"][0]["text"] = text
    client.responses[key] = (status, {"data": items})


class CacheIntegrityTests(unittest.TestCase):
    def test_acceptance_criteria_tamper_never_completes(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            cache = read_cache(td)
            cache["mission_snapshot"]["acceptance_criteria"] = []
            write_cache(td, cache)
            second = cli._fixture_context(store_dir=td, with_report=True)
            alter_report(second["client"], drop_acceptance=True)
            result = second["executor"].reconcile()
            self.assertNotEqual("COMPLETED", result["state"])

    def test_payload_sha256_tamper_never_completes(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            divergent = cc.payload_sha256({"prompt": "prompt adulterado"})
            cache = read_cache(td)
            cache["mission_snapshot"]["payload_sha256"] = divergent
            write_cache(td, cache)
            second = cli._fixture_context(store_dir=td, with_report=True)
            alter_prompt(second["client"])
            result = second["executor"].reconcile()
            self.assertNotEqual("COMPLETED", result["state"])

    def test_environment_and_receipt_tamper_never_completes(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            cache = read_cache(td)
            cache["evidence"]["environment"] = "APPROVED"
            cache["evidence"]["receipt"] = {"observed_message_id": "msg-pilot-fixture"}
            write_cache(td, cache)
            second = cli._fixture_context(store_dir=td, with_report=True)
            alter_report(second["client"], wrong_reply_to=True)
            result = second["executor"].reconcile()
            self.assertNotEqual("COMPLETED", result["state"])
            # environment é derivado da autorização sintética, não do cache.
            self.assertEqual("FIXTURE", second["store"].fixture_environment())

    def test_cache_corrupt_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            with open(os.path.join(td, "evidence-cache.json"), "w", encoding="utf-8") as handle:
                handle.write("{corrompido")
            second = cli._fixture_context(store_dir=td, with_report=True)
            result = second["executor"].reconcile()
            self.assertNotEqual("COMPLETED", result["state"])


class RecoveryTests(unittest.TestCase):
    def test_interrupt_after_dispatch_then_restart_reconciles(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            posts = [c for c in first["client"].calls if c[0] == "POST"]
            self.assertEqual(1, len(posts))
            restarted = cli._fixture_context(store_dir=td, with_report=True)
            snapshot = restarted["store"].load()
            self.assertEqual(ledger.DISPATCHING, snapshot.state)
            self.assertTrue(snapshot.evidence)
            result = restarted["executor"].reconcile()
            self.assertEqual("COMPLETED", result["state"])
            # A reconciliação não reenvia: nenhum POST no cliente reiniciado.
            self.assertEqual(0, len([c for c in restarted["client"].calls if c[0] == "POST"]))

    def test_unknown_and_failed_via_cli(self):
        with tempfile.TemporaryDirectory() as td:
            first = cli._fixture_context(store_dir=td)
            first["executor"].execute(first["mission"], first["core_auth"], pilot.PILOT_PROMPT)
            first["store"].recover()
            out = cli_run(td, "reconcile", "--target", "FAILED", "--reason", "evidência insuficiente")
            self.assertEqual(0, out.returncode, out.stderr)
            data = json.loads(out.stdout)
            self.assertEqual("FAILED", data["state"])
            self.assertTrue(data["changed"])

    def test_duplicate_report_has_no_second_transition(self):
        with tempfile.TemporaryDirectory() as td:
            ctx = cli._fixture_context(store_dir=td, with_report=True)
            ctx["executor"].execute(ctx["mission"], ctx["core_auth"], pilot.PILOT_PROMPT)
            first = ctx["executor"].reconcile()
            duplicate = ctx["executor"].reconcile()
            self.assertEqual("COMPLETED", first["state"])
            self.assertEqual("COMPLETED", duplicate["state"])
            self.assertFalse(duplicate["changed"])
            self.assertEqual(1, len([c for c in ctx["client"].calls if c[0] == "POST"]))

    def test_two_processes_single_post(self):
        with tempfile.TemporaryDirectory() as td:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: cli_run(td, "dry-run"), range(2)))
            succeeded = [r for r in results if r.returncode == 0]
            self.assertEqual(1, len(succeeded))
            self.assertEqual(1, json.loads(succeeded[0].stdout)["http_posts"])


class ModelBindingTests(unittest.TestCase):
    def test_selected_model_divergent_from_effective_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            ctx = cli._fixture_context(store_dir=td)
            recommendation = replace(ctx["model_recommendation"],
                                     selected=replace(ctx["model_recommendation"].selected,
                                                      model_id="outro-free"))
            with patch.object(cli, "fixture_model_recommendation", return_value=recommendation):
                context = cli._fixture_context(store_dir=os.path.join(td, "divergente"))
                with self.assertRaises(cc.TransportError):
                    context["executor"].execute(context["mission"], context["core_auth"],
                                                pilot.PILOT_PROMPT)
            self.assertEqual(0, len([c for c in context["client"].calls if c[0] == "POST"]))

    def test_synthetic_authorization_is_fixture_only(self):
        for environment in ("APPROVED", "REAL", "PRODUCTION"):
            auth = adapter.SyntheticAuthorization("AUTH", "a" * 64, "consumer", environment)
            with self.assertRaises(cc.AuthorizationError):
                auth.validate()


if __name__ == "__main__":
    unittest.main()
