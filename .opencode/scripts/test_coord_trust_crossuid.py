#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P4 — testes host-only do harness cross-UID.

Não executam sudo, não criam usuários e não fazem parte da certificação real:
validam o workflow opt-in, o layout/configuração, o job do cliente sem material
privado, o sanitizador e a persistência do nonce de recuperação.
"""
from __future__ import annotations

import base64
import json
import os
import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml

import coord_authority_signed as cas
import coord_p14b as p14b
import coord_trust_crossuid_common as common
import coord_trust_service as svc

ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 11, 27, 14, 0, tzinfo=timezone.utc)


def _backup_db(source: str, target: str) -> None:
    src = sqlite3.connect(source)
    dst = sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()


def _restore_db(source: str, target: str) -> None:
    for suffix in ("-wal", "-shm", "-journal"):
        try:
            os.unlink(target + suffix)
        except FileNotFoundError:
            pass
    src = sqlite3.connect(source)
    dst = sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    os.chmod(target, 0o600)


class CrossUidHarnessTest(unittest.TestCase):
    def test_workflow_is_opt_in_bounded_and_secretless(self):
        path = ROOT / ".github/workflows/coord-trust-crossuid.yml"
        raw = path.read_text(encoding="utf-8")
        document = yaml.safe_load(raw)
        triggers = document.get("on") or document.get(True)
        self.assertEqual({"workflow_dispatch"}, set(triggers))
        workflow_dispatch = triggers["workflow_dispatch"]
        self.assertIn("crossuid_full", workflow_dispatch["inputs"])
        self.assertNotIn("push", triggers)
        self.assertNotIn("pull_request", triggers)
        self.assertEqual({"contents": "read"}, document["permissions"])
        self.assertNotIn("secrets.", raw)
        job = document["jobs"]["crossuid"]
        self.assertEqual("${{ inputs.crossuid_full }}", job["if"])
        self.assertEqual(10, job["timeout-minutes"])
        self.assertIn(".opencode/scripts/coord_trust_crossuid_harness.py", raw)
        self.assertIn("sudo -n unshare -n", raw)
        self.assertIn("actions/upload-artifact@v4", raw)

    def test_plan_is_production_shaped_and_anchor_is_outside_state(self):
        with tempfile.TemporaryDirectory() as root:
            plan = common.build_plan(root)
            config = common.config_mapping(plan)
        self.assertEqual(
            "BT-COORD-TRUST-SERVICE-CONFIG-V1", config["schema"])
        self.assertFalse(config["allow_same_uid"])
        self.assertFalse(config["test_mode"])
        self.assertFalse(config["real_transport"])
        self.assertNotEqual(config["service_uid"], common.CONSUMER_UID)
        self.assertEqual(common.SERVICE_UID, config["service_uid"])
        anchor = os.path.abspath(plan.anchor_path)
        state = os.path.abspath(plan.state_dir)
        self.assertNotEqual(state, anchor)
        self.assertFalse(anchor.startswith(state + os.sep))
        self.assertTrue(1 <= config["max_workers"] <= 16)
        self.assertTrue(0 < config["request_timeout_seconds"] <= 60)
        self.assertTrue(0 < config["max_frame_bytes"] <= svc.MAX_FRAME_BYTES)

    def test_client_job_has_no_private_material(self):
        with tempfile.TemporaryDirectory() as root:
            plan = common.build_plan(root)
            os.makedirs(plan.keys_dir, mode=0o700)
            signer = p14b.SyntheticSigner(plan.keys_dir, persist=True)
            allowlist = cas.ApproverAllowlist((signer.approver_key(NOW),))
            artifacts = common.build_artifacts(
                NOW, signer, allowlist, tag="unit")
            job = common.client_job(plan, artifacts)
            common.validate_client_job(job)
            serialized = common.canonical_json(job)
            self.assertNotIn(signer.private_path, serialized)
            self.assertNotIn(plan.keys_dir, serialized)
            self.assertNotIn(plan.state_dir, serialized)
            self.assertNotIn("PRIVATE KEY", serialized)
            self.assertEqual(common.service_id(), job["service_id"])
            self.assertIn("authorize", job["actions"])
            self.assertNotIn("settings", job["actions"])

    def test_sanitizer_removes_paths_and_private_key_blocks(self):
        private = ("-----BEGIN OPENSSH PRIVATE KEY-----\n"
                   "AAAA\n-----END OPENSSH PRIVATE KEY-----")
        text = f"root=/tmp/state keys={private} repo=/repo/wt"
        clean = common.sanitize_text(
            text, ("/tmp/state", "/repo/wt"))
        self.assertNotIn("/tmp/state", clean)
        self.assertNotIn("/repo/wt", clean)
        self.assertNotIn("OPENSSH PRIVATE KEY", clean)
        self.assertIn("<redacted-private-key>", clean)

    def test_service_rejects_invalid_resource_limits(self):
        from test_coord_trust_service import Fixture

        with tempfile.TemporaryDirectory() as root:
            fx = Fixture(root)
            cases = (
                {"max_workers": 17},
                {"max_workers": 0},
                {"request_timeout_seconds": 61},
                {"request_timeout_seconds": 0},
                {"max_frame_bytes": svc.MAX_FRAME_BYTES + 1},
            )
            for overrides in cases:
                values = {
                    "state_dir": fx.state,
                    "project_id": common.PROJECT,
                    "repository_id": common.REPOSITORY,
                    "service_uid": os.geteuid(),
                    "service_gid": os.getegid(),
                    "trust_store_path": fx.trust_path,
                    "consumer_registry_path": fx.consumer_path,
                    "socket_path": fx.config.socket_path,
                    "anchor_path": os.path.join(root, "anchor", "anchor.json"),
                    "allow_same_uid": True,
                    "test_mode": True,
                    "real_transport": False,
                }
                values.update(overrides)
                config = svc.ServiceConfig(**values)
                with self.assertRaises(svc.ServiceConfigurationError):
                    config.prepare()

    def test_recovery_nonce_is_persisted_and_not_reused(self):
        from test_coord_trust_service import Fixture

        with tempfile.TemporaryDirectory() as root:
            fx = Fixture(root)
            self.assertTrue(fx.propose()["ok"])
            self.assertTrue(fx.authorize()["ok"])
            ledger_backup = os.path.join(root, "ledger-before.db")
            service_backup = os.path.join(root, "service-before.db")
            _backup_db(fx.config.ledger_path, ledger_backup)
            _backup_db(fx.config.service_db_path, service_backup)
            self.assertTrue(fx.reserve()["ok"])
            _restore_db(ledger_backup, fx.config.ledger_path)
            _restore_db(service_backup, fx.config.service_db_path)
            restarted = svc.build_service(fx.config, clock=lambda: NOW)
            self.assertTrue(restarted.quarantined)

            statement = svc.RecoveryStatement(
                schema=svc.RECOVERY_SCHEMA,
                approver_id=fx.signer.approver_id,
                approver_key_fingerprint=fx.signer.fingerprint,
                trust_policy_sha256=fx.allowlist.digest(),
                new_epoch=2,
                reason="recuperação de teste P4",
                issued_at=NOW,
                expires_at=NOW + timedelta(minutes=10),
                nonce="nonce-p4-reuse-001")
            signature = fx.signer.sign(
                svc.canonical_recovery_bytes(statement),
                namespace=svc.RECOVERY_NAMESPACE, directory=fx.keys)
            first = restarted.handle(fx.peer, {
                "request_id": "req-p4-recovery-1", "action": "recover",
                "payload": {
                    "statement": statement.to_mapping(),
                    "signature": base64.b64encode(signature).decode("ascii"),
                }})
            self.assertTrue(first["ok"], first)
            self.assertEqual(2, restarted.epoch)
            restarted_again = svc.build_service(
                fx.config, clock=lambda: NOW)
            self.assertEqual(2, restarted_again.epoch)
            self.assertFalse(restarted_again.quarantined)

            fresh = svc.RecoveryStatement(
                schema=svc.RECOVERY_SCHEMA,
                approver_id=fx.signer.approver_id,
                approver_key_fingerprint=fx.signer.fingerprint,
                trust_policy_sha256=fx.allowlist.digest(),
                new_epoch=3,
                reason="tentativa de reutilizar nonce",
                issued_at=NOW,
                expires_at=NOW + timedelta(minutes=10),
                nonce="nonce-p4-reuse-001")
            fresh_signature = fx.signer.sign(
                svc.canonical_recovery_bytes(fresh),
                namespace=svc.RECOVERY_NAMESPACE, directory=fx.keys)
            replay = restarted_again.handle(fx.peer, {
                "request_id": "req-p4-recovery-2", "action": "recover",
                "payload": {
                    "statement": fresh.to_mapping(),
                    "signature": base64.b64encode(fresh_signature).decode(
                        "ascii"),
                }})
            self.assertFalse(replay["ok"], replay)
            self.assertEqual("CONFLICT", replay["error"]["code"])
            self.assertEqual(2, restarted_again.epoch)

    def test_service_state_nonce_survives_restart(self):
        with tempfile.TemporaryDirectory() as root:
            state_dir = os.path.join(root, "state")
            os.makedirs(state_dir, mode=0o700)
            path = os.path.join(state_dir, "service.db")
            state = svc.ServiceState(path)
            state.consume_recovery_nonce("nonce-state", 2, NOW)
            with self.assertRaises(svc.ServiceConflict):
                state.consume_recovery_nonce("nonce-state", 3, NOW)
            restarted = svc.ServiceState(path)
            with self.assertRaises(svc.ServiceConflict):
                restarted.consume_recovery_nonce("nonce-state", 4, NOW)


if __name__ == "__main__":
    unittest.main(verbosity=2)
