#!/usr/bin/env python3
"""COORD-04/P14A.3 — CLI efetiva com ledger, cache não autoritativo e reconciliação."""
from __future__ import annotations

import concurrent.futures
import json
import os
import subprocess
import sys
import tempfile
import unittest

import coord_communication as cc
import coord_ledger as ledger
import coord_p14_adapter as adapter
import coord_pilot as pilot
import coord_pilot_cli as cli


CLI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "coord_pilot_cli.py")


def cli_run(store_dir: str, command: str):
    return subprocess.run(
        [sys.executable, CLI, "--store-dir", store_dir, command],
        capture_output=True, text=True, timeout=120,
    )


class P14A3CliTests(unittest.TestCase):
    def test_dry_run_uses_ledger_and_canonical_model_policy(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "dry-run"])
            self.assertEqual("fixture", out["source"])
            self.assertEqual(1, out["http_posts"])
            self.assertFalse(os.path.exists(os.path.join(td, "operation.json")))
            self.assertTrue(os.path.exists(os.path.join(td, "ledger.db")))
            self.assertEqual("COMPLETED", out["final_state"])
            policy = out["model_policy"]
            self.assertEqual("coord_model_policy", policy["source"])
            self.assertEqual("RECOMMEND_FREE", policy["decision"])
            self.assertEqual("opencode/hy4-flash-free#default", policy["selected"])

    def test_ledger_authority_not_synthetic_string(self):
        with tempfile.TemporaryDirectory() as td:
            ctx = cli._fixture_context(store_dir=td)
            db = ledger.Ledger(
                os.path.join(td, "unauth.db"),
                project_id=ctx["mission"].project_id,
                repository_id=ctx["mission"].repository_id,
            )
            store = adapter.LedgerOperationStore(
                db, mission_id="MISS-UNAUTH", operation_id="OP-UNAUTH",
                authorization_reference="AUTH-STRING", payload={"x": 1},
                target=ctx["auth"].target, cache_dir=td,
            )
            record = pilot.PilotOperationRecord(
                "MISS-UNAUTH", "OP-UNAUTH", "AUTH-STRING", "canonical",
                ctx["auth"].target.key(), "hash", ledger.AUTHORIZED,
                cc.iso(cli.NOW), cc.iso(cli.NOW))
            with self.assertRaises(cc.AuthorizationError):
                store.create(record)
            approved = adapter.SyntheticAuthorization(
                approval_reference="AUTH-STRING", authorization_digest="a" * 64,
                consumer_id="fp", environment="APPROVED")
            store.synthetic_authorization = approved
            with self.assertRaises(cc.AuthorizationError):
                store.create(record)

    def test_context_survives_restart_without_external_reconstruction(self):
        with tempfile.TemporaryDirectory() as td:
            cli.run(["--store-dir", td, "dry-run"])
            ctx = cli._fixture_context(store_dir=td)
            db = ledger.Ledger(
                os.path.join(td, "ledger.db"),
                project_id=ctx["mission"].project_id,
                repository_id=ctx["mission"].repository_id,
            )
            restarted = adapter.LedgerOperationStore(
                db, mission_id=ctx["mission"].mission_id,
                operation_id=ctx["mission"].operation_id,
                authorization_reference=ctx["mission"].authorization_reference,
                payload=ctx["mission"].payload, target=ctx["auth"].target,
                cache_dir=td,
            )
            record = restarted.load()
            self.assertEqual(ledger.COMPLETED, record.state)
            self.assertTrue(record.mission_snapshot)
            self.assertTrue(record.authorization_canonical)

    def test_reconcile_after_restart_is_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            cli.run(["--store-dir", td, "dry-run"])
            first = cli.run(["--store-dir", td, "reconcile"])
            second = cli.run(["--store-dir", td, "reconcile"])
            self.assertEqual("COMPLETED", first["state"])
            self.assertEqual("COMPLETED", second["state"])
            self.assertFalse(first["changed"])
            self.assertFalse(second["changed"])

    def test_two_processes_do_not_dispatch_same_mission(self):
        with tempfile.TemporaryDirectory() as td:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: cli_run(td, "dry-run"), range(2)))
            succeeded = [r for r in results if r.returncode == 0]
            self.assertEqual(1, len(succeeded))
            payload = json.loads(succeeded[0].stdout)
            self.assertEqual(1, payload["http_posts"])
            failed = [r for r in results if r.returncode != 0]
            self.assertEqual(1, len(failed))
            message = failed[0].stdout + failed[0].stderr
            self.assertTrue(
                any(token in message for token in (
                    "sem reenvio", "não autoriza primeira transmissão", "reserva",
                )),
                message,
            )

    def test_real_path_keeps_synthetic_authority_closed(self):
        with tempfile.TemporaryDirectory() as td:
            out = cli.run(["--store-dir", td, "execute", "--real"])
            self.assertEqual("BLOCKED_BY_DEFAULT", out["state"])
            self.assertEqual(0, out["real_calls"])
            self.assertFalse(out["executed"])
            self.assertFalse(out["service_contacted"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
