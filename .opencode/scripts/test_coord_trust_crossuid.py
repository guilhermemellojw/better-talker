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
import socket
import shutil
import sqlite3
import struct
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
        path = ROOT / ".github/workflows/coord-python.yml"
        raw = path.read_text(encoding="utf-8")
        document = yaml.safe_load(raw)
        triggers = document.get("on") or document.get(True)
        self.assertEqual({"push", "pull_request", "workflow_dispatch"}, set(triggers))
        dispatch = triggers["workflow_dispatch"]["inputs"]
        self.assertEqual(True, dispatch["crossuid_full"]["required"])
        self.assertEqual(False, dispatch["crossuid_full"]["default"])
        self.assertEqual("boolean", dispatch["crossuid_full"]["type"])
        self.assertEqual("", dispatch["certified_sha"]["default"])
        self.assertEqual("string", dispatch["certified_sha"]["type"])
        self.assertNotIn("coord-trust-crossuid.yml", raw)
        self.assertFalse(
            (ROOT / ".github/workflows/coord-trust-crossuid.yml").exists())
        self.assertEqual({"contents": "read"}, document["permissions"])
        self.assertNotIn("secrets.", raw)
        jobs = document["jobs"]
        self.assertEqual({"coord-python", "crossuid"}, set(jobs))
        ordinary = jobs["coord-python"]
        self.assertIn("github.event_name != 'workflow_dispatch'", ordinary["if"])
        self.assertIn("inputs.crossuid_full == false", ordinary["if"])
        ordinary_checkout = next(
            step for step in ordinary["steps"]
            if step.get("uses") == "actions/checkout@v4")
        self.assertEqual(False, ordinary_checkout["with"]["persist-credentials"])
        job = jobs["crossuid"]
        self.assertEqual(
            "${{ github.event_name == 'workflow_dispatch' && "
            "inputs.crossuid_full == true && github.run_attempt == 1 }}",
            job["if"])
        self.assertEqual(10, job["timeout-minutes"])
        self.assertEqual("coord-trust-crossuid", job["concurrency"]["group"])
        self.assertEqual("${{ inputs.certified_sha }}", job["env"]["CERTIFIED_SHA"])
        self.assertIn("${{ github.run_id }}-${{ github.run_attempt }}",
                      job["env"]["RUN_ID"])
        checkout = next(
            step for step in job["steps"]
            if step.get("uses") == "actions/checkout@v4")
        self.assertEqual(False, checkout["with"]["persist-credentials"])
        self.assertEqual("${{ env.CERTIFIED_SHA }}", checkout["with"]["ref"])
        names = [step.get("name", "") for step in job["steps"]]
        self.assertIn("Validar SHA certificado informado", names)
        self.assertIn("Verificar SHA certificado após checkout", names)
        validation = next(
            step for step in job["steps"]
            if step.get("name") == "Validar SHA certificado informado")
        self.assertIn('certified="${CERTIFIED_SHA:-}"', validation["run"])
        self.assertIn('"$GITHUB_SHA" = "$certified"', validation["run"])
        self.assertNotIn("${{ inputs.certified_sha }}", validation["run"])
        guard = next(
            step for step in job["steps"]
            if step.get("name") == "Verificar SHA certificado após checkout")
        self.assertIn("rev-parse HEAD", guard["run"])
        self.assertIn("CERTIFIED-SHA-DIVERGENT", guard["run"])
        self.assertIn("RUN-ATTEMPT-BLOCKED", raw)
        cleanup = next(
            step for step in job["steps"]
            if step.get("name") == "Limpeza independente do runner")
        self.assertEqual("always()", cleanup["if"])
        self.assertIn("CROSSUID-CLEANUP-PGRPS", cleanup["run"])
        self.assertIn("COORD_TRUST_RUN_ID", cleanup["run"])
        self.assertNotIn("pkill", cleanup["run"])
        self.assertNotIn("killall", cleanup["run"])
        self.assertIn(".opencode/scripts/coord_trust_crossuid_harness.py", raw)
        self.assertIn("sudo -n setsid --wait unshare -n", raw)
        self.assertIn("command -v setsid", raw)
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
        mapped = common.sanitize_value({
            "detail": "traceback /tmp/state/ledger.db",
            "fingerprint": "SHA256:synthetic",
            "private": private,
        }, ("/tmp/state",))
        self.assertNotIn("/tmp/state", mapped["detail"])
        self.assertEqual("SHA256:synthetic", mapped["fingerprint"])
        self.assertNotIn("OPENSSH PRIVATE KEY", mapped["private"])
        self.assertIn("<redacted-private-key>", mapped["private"])

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

    def test_credentials_reject_gid_only_divergence(self):
        expected = svc.PeerIdentity(
            pid=os.getpid(), uid=os.geteuid(), gid=os.getegid(),
            source="SO_PEERCRED")
        valid = (socket.SOL_SOCKET, socket.SCM_CREDENTIALS,
                 struct.pack("3i", os.getpid(), os.geteuid(), os.getegid()))
        peer = svc._credentials_from_ancillary([valid], 0, expected=expected)
        self.assertEqual((peer.uid, peer.gid), (os.geteuid(), os.getegid()))
        wrong_gid = (socket.SOL_SOCKET, socket.SCM_CREDENTIALS,
                     struct.pack("3i", os.getpid(), os.geteuid(),
                                 os.getegid() + 1))
        with self.assertRaises(svc.AuthenticationError):
            svc._credentials_from_ancillary([wrong_gid], 0, expected=expected)

    def test_run_main_gates_executes_transmit_path(self):
        """Regressão A1: exercita run_main_gates até o check de transmit.

        Não é certificação cross-UID nem usa root; os processos e o socket são
        substituídos por resultados determinísticos para executar o caminho
        Python real que antes referenciava `results` indefinido.
        """
        import types

        import coord_trust_crossuid_harness as harness_module

        with tempfile.TemporaryDirectory() as root:
            output = os.path.join(root, "out")
            harness = harness_module.Harness(
                repo=str(ROOT), output=output, deadline_seconds=30)
            self.addCleanup(harness.cleanup)
            layout_plan = common.build_plan(os.path.join(root, "layout"))
            os.makedirs(layout_plan.artifacts_dir, exist_ok=True)
            signer = p14b.SyntheticSigner(
                harness.signer_dir, persist=True)
            allowlist = cas.ApproverAllowlist(
                (signer.approver_key(NOW),))
            harness.signer = signer
            harness.allowlist = allowlist
            layout = harness_module.Layout(
                plan=layout_plan,
                config={
                    "real_transport": False,
                    "test_mode": False,
                    "allow_same_uid": False,
                })
            harness.paths.append(layout_plan.root)
            harness.layouts.append(layout)
            harness.new_layout = lambda tag: layout
            harness.prepare_trust = lambda value: None
            harness.verify_runtime_access = lambda value: True
            harness.write_config = lambda value, mapping=None: None
            harness.start_service = lambda value, **kwargs: types.SimpleNamespace(
                proc=types.SimpleNamespace(pid=1234))
            harness.check_layout_permissions = lambda value: None
            harness.check_runtime_files = lambda value: None
            harness.stop_service = lambda: None
            harness.wait_for_path = lambda path, timeout=10: None
            harness.start_client = lambda *args, **kwargs: (
                types.SimpleNamespace(), "/dev/null", "/dev/null")
            harness.wait_client = lambda *args, **kwargs: {"ok": True}
            harness.setpriv_has_ambient = True
            assert_calls = {"count": 0}

            def fake_client(layout_value, job_path, mode, **kwargs):
                if mode == "prepare":
                    return {
                        "ok": True,
                        "results": {
                            "propose": {"ok": True},
                            "authorize": {"ok": True},
                        },
                    }
                if mode == "reserve":
                    return {"ok": True, "lease_id": "lease-test"}
                if mode == "assert":
                    assert_calls["count"] += 1
                    if assert_calls["count"] == 1:
                        return {"ok": True}
                    return {
                        "ok": False,
                        "response": {"error": {"code": "REPLAY_STALE"}},
                    }
                if mode == "record-evidence":
                    return {"ok": True}
                if mode == "reconcile":
                    return {"ok": True}
                if mode == "status":
                    return {
                        "ok": True,
                        "response": {
                            "ok": True,
                            "result": {
                                "state": "COMPLETED",
                                "real_transport": False,
                                "reserved": False,
                            },
                        },
                    }
                if mode == "transmit":
                    return {"ok": True, "code": "TRANSPORT_DISABLED"}
                if mode == "forbidden":
                    return {"ok": True, "code": "PERMISSION_DENIED"}
                if mode == "observer":
                    return {"ok": True}
                if mode == "unregistered":
                    return {"closed_without_response": True}
                if mode == "auth-wrong":
                    return {"blocked": True}
                if mode == "fd-attach":
                    return {"closed_without_response": True}
                if mode == "fd-relay-helper":
                    return {
                        "fd_received": True,
                        "service_closed": True,
                        "uid": common.UNREGISTERED_UID,
                        "gid": common.UNREGISTERED_GID,
                    }
                if mode == "gid-divergence":
                    return {
                        "ok": True,
                        "gid_changed": True,
                        "closed_without_response": True,
                    }
                if mode == "gid-socket-denied":
                    return {"blocked": True}
                if mode == "sandbox":
                    return {
                        "ok": True,
                        "cap_eff": "0",
                        "no_new_privs": "1",
                        "state_read_blocked": True,
                        "network_error": "OSError",
                        "read_results": {},
                    }
                raise AssertionError("modo inesperado: " + mode)

            harness.run_client = fake_client
            harness.run_main_gates()
            transmit_checks = [
                check for check in harness.checks
                if check.gate == "G9" and "transmit" in check.name]
            self.assertTrue(transmit_checks, harness.checks)
            self.assertTrue(transmit_checks[0].passed, transmit_checks)
            self.assertTrue(all(check.passed for check in harness.checks),
                            [check.to_mapping() for check in harness.checks
                             if not check.passed])

    def test_g6_requires_service_rejection_not_socket_eacces(self):
        import types

        import coord_trust_crossuid_harness as harness_module

        with tempfile.TemporaryDirectory() as root:
            harness = harness_module.Harness(
                repo=str(ROOT), output=os.path.join(root, "out"),
                deadline_seconds=30)
            self.addCleanup(harness.cleanup)
            layout = types.SimpleNamespace(
                plan=types.SimpleNamespace(client_job_path="job"))
            harness.setpriv_has_ambient = False
            harness.run_client = lambda *args, **kwargs: {
                "blocked": True, "ok": True}
            harness.check_g6(layout)
            blocked = harness.checks[-1]
            self.assertTrue(blocked.blocked)
            self.assertFalse(blocked.passed)
            self.assertIn("EACCES", blocked.detail)

            harness.setpriv_has_ambient = True
            harness.run_client = lambda *args, **kwargs: {
                "ok": False, "gid_changed": True,
                "closed_without_response": False}
            harness.check_g6(layout)
            accepted = harness.checks[-1]
            self.assertFalse(accepted.blocked)
            self.assertFalse(accepted.passed)

            harness.run_client = lambda *args, **kwargs: {
                "ok": True, "gid_changed": True,
                "closed_without_response": True}
            harness.check_g6(layout)
            self.assertTrue(harness.checks[-1].passed)

    def test_summary_and_traceback_are_sanitized(self):
        import coord_trust_crossuid_harness as harness_module

        private = ("-----BEGIN OPENSSH PRIVATE KEY-----\n"
                   "AAAA\n-----END OPENSSH PRIVATE KEY-----")
        with tempfile.TemporaryDirectory() as root:
            output = os.path.join(root, "out")
            harness = harness_module.Harness(
                repo=str(ROOT), output=output, deadline_seconds=30)
            self.addCleanup(harness.cleanup)
            harness.paths.append("/tmp/state")
            harness.check(
                "X", "detalhe com path", False,
                {"path": "/tmp/state/ledger.db", "private": private,
                 "fingerprint": "SHA256:synthetic"})
            harness.write_output("traceback /tmp/state/ledger.db")
            summary = common.read_json(os.path.join(output, "summary.json"))
            serialized = common.canonical_json(summary)
            self.assertNotIn("/tmp/state", serialized)
            self.assertNotIn("OPENSSH PRIVATE KEY", serialized)
            self.assertIn("<redacted-private-key>", serialized)
            self.assertIn("SHA256:synthetic", serialized)
            self.assertEqual("FAIL", summary["result"])

    def test_runtime_bundle_is_minimal_read_only_and_outside_source(self):
        import coord_trust_crossuid_harness as harness_module

        with tempfile.TemporaryDirectory() as root:
            source = os.path.join(root, "source")
            os.makedirs(source, mode=0o700)
            for name in harness_module.RUNTIME_FILES:
                shutil.copyfile(
                    os.path.join(harness_module.SCRIPTS_DIR, name),
                    os.path.join(source, name))
            harness = harness_module.Harness(
                repo=str(ROOT), output=os.path.join(root, "out"),
                deadline_seconds=30)
            self.addCleanup(harness.cleanup)
            harness.prepare_runtime_bundle(source)
            self.assertTrue(harness.verify_runtime_bundle(source))
            runtime = harness.runtime_dir
            plan = common.build_plan(root)
            self.assertFalse(runtime.startswith(source + os.sep))
            self.assertEqual(0o700, os.stat(source).st_mode & 0o777)
            self.assertEqual(runtime,
                             harness.service_env(plan)["PYTHONPATH"])
            self.assertEqual(runtime,
                             harness.client_env(
                                 plan, common.CONSUMER_UID)["PYTHONPATH"])
            self.assertTrue(harness.service_cli.startswith(runtime + os.sep))
            self.assertTrue(
                harness.client_script.startswith(runtime + os.sep))
            self.assertEqual(0o555, os.stat(runtime).st_mode & 0o777)
            manifest = common.read_json(
                os.path.join(runtime, "runtime-manifest.json"))
            self.assertEqual(set(harness_module.RUNTIME_FILES),
                             set(manifest["files"]))
            for name in harness_module.RUNTIME_FILES:
                path = os.path.join(runtime, name)
                self.assertEqual(0o444, os.stat(path).st_mode & 0o777)
                with open(path, "rb") as handle:
                    actual = __import__("hashlib").sha256(
                        handle.read()).hexdigest()
                self.assertEqual(manifest["files"][name], actual)
            self.assertNotIn("state_dir", manifest["files"])
            self.assertNotIn("ledger", manifest["files"])
            imported = __import__("subprocess").run(
                [__import__("sys").executable, "-c",
                 "import coord_trust_service; "
                 "import coord_trust_crossuid_client; "
                 "import coord_trust_crossuid_common; "
                 "import coord_trust_crossuid_crash_service"],
                env={"PATH": os.environ.get("PATH", ""),
                     "PYTHONPATH": runtime,
                     "PYTHONDONTWRITEBYTECODE": "1"},
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, imported.returncode, imported.stderr)

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
