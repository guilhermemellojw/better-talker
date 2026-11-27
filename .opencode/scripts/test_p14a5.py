#!/usr/bin/env python3
"""COORD-04/P14A.5 — concorrência controlada na CLI (DuplicateReservation)."""
from __future__ import annotations

import concurrent.futures
import json
import os
import subprocess
import sys
import tempfile
import unittest

import coord_ledger as ledger
import coord_p14_adapter as adapter
import coord_pilot_cli as cli

CLI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "coord_pilot_cli.py")
MISSION_ID = "MISS-COORD04-PILOT-001"
ITERATIONS = 5


def run_cli(store_dir: str):
    return subprocess.run(
        [sys.executable, CLI, "--store-dir", store_dir, "dry-run"],
        capture_output=True, text=True, timeout=120,
    )


class DuplicateReservationTests(unittest.TestCase):
    def test_five_concurrent_iterations_are_controlled(self):
        for iteration in range(ITERATIONS):
            with tempfile.TemporaryDirectory() as td:
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    results = list(pool.map(lambda _: run_cli(td), range(2)))
                winners = [r for r in results if r.returncode == 0]
                losers = [r for r in results if r.returncode != 0]
                with self.subTest(iteration=iteration):
                    self.assertEqual(1, len(winners))
                    self.assertEqual(1, len(losers))
                    winner = json.loads(winners[0].stdout)
                    self.assertEqual(1, winner["http_posts"])
                    message = losers[0].stdout + losers[0].stderr
                    self.assertTrue(message.startswith("erro:"), message)
                    # A falha controlada pode ocorrer antes da reserva
                    # (can_dispatch) ou nela (DuplicateReservation).
                    self.assertNotIn("Traceback", message)
                    self.assertNotIn("DuplicateReservation", message)
                    self.assertNotIn('"http_posts": 1', losers[0].stdout)

    def test_losing_process_does_not_change_winner_reservation(self):
        with tempfile.TemporaryDirectory() as td:
            first = run_cli(td)
            self.assertEqual(0, first.returncode)
            ctx = cli._fixture_context(store_dir=td)
            db = ledger.Ledger(
                os.path.join(td, "ledger.db"),
                project_id=ctx["mission"].project_id,
                repository_id=ctx["mission"].repository_id,
            )
            before = db.get(MISSION_ID)
            self.assertIsNotNone(before)
            second = run_cli(td)
            self.assertEqual(2, second.returncode)
            after = db.get(MISSION_ID)
            self.assertEqual(before.state, after.state)
            self.assertEqual(before.updated_at, after.updated_at)

    def test_reserve_duplicate_is_authorization_error(self):
        with tempfile.TemporaryDirectory() as td:
            from dataclasses import replace
            import coord_pilot as pilot
            from coord_p14b import SignedOperationStore
            base = cli._fixture_context(store_dir=os.path.join(td, "base"))["mission"]
            mission = replace(base, mission_id="M-DUP", operation_id="O-DUP",
                              payload={"x": 1},
                              payload_sha256=cli.cc.payload_sha256({"x": 1}),
                              authorization_reference="AUTH-DUP")
            store = SignedOperationStore(
                mission=mission, store_dir=os.path.join(td, "dup"),
                model=cli.selected_model_for_dispatch(
                    cli.fixture_model_recommendation()), now=cli.NOW,
                target=base.target, authorization_reference="AUTH-DUP",
                authorization_canonical="canonical", clock=lambda: cli.NOW)
            record = pilot.PilotOperationRecord(
                "M-DUP", "O-DUP", "AUTH-DUP", "canonical",
                base.target.key(), "hash", ledger.AUTHORIZED,
                cli.cc.iso(cli.NOW), cli.cc.iso(cli.NOW))
            store.create(record)
            with store.reserve():
                self.assertTrue(store.db.is_reserved("M-DUP"))
            with self.assertRaises(cli.cc.AuthorizationError) as caught:
                with store.reserve():
                    pass
            self.assertIn("sem reenvio", str(caught.exception))
            self.assertTrue(store.db.is_reserved("M-DUP"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
