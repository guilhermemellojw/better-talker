#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P4 — harness descartável de certificação cross-UID.

Executa somente em runner/VM descartável, como root, dentro de um network
namespace sem rede. O root é usado apenas para preparar arquivos, spawnar dois
UIDs numéricos efêmeros e matar/reiniciar o serviço; serviço e consumidores
rodam sem root e sem capabilities. Nenhum usuário é criado, nenhum secret é
lido e nenhum transporte real é habilitado.

O harness implementa os gates G1–G9 do parecer P5 de B. Ele não é disparado
por push/PR; o workflow é opt-in por workflow_dispatch.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pwd
import re
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
import traceback
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Optional

import coord_authority_signed as cas
import coord_p14b as p14b
import coord_trust_crossuid_common as common
import coord_trust_service as svc

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
CLIENT = os.path.join(SCRIPTS_DIR, "coord_trust_crossuid_client.py")
CRASH_SERVICE = os.path.join(SCRIPTS_DIR, "coord_trust_crossuid_crash_service.py")
SERVICE_CLI = os.path.join(SCRIPTS_DIR, "coord_trust_service_cli.py")
SETPRIV = shutil.which("setpriv") or "/usr/bin/setpriv"
UNSHARE = shutil.which("unshare") or "/usr/bin/unshare"

# Fecho mínimo de importação do serviço/cliente. A cópia é feita para o
# work_root 0711 do runner, fora dos ancestrais do checkout, com arquivos 0444
# e diretório 0555. Nada de estado/chaves entra no bundle.
RUNTIME_FILES = (
    "coord_trust_service.py",
    "coord_trust_service_cli.py",
    "coord_trust_crossuid_client.py",
    "coord_trust_crossuid_common.py",
    "coord_trust_crossuid_crash_service.py",
    "coord_authority.py",
    "coord_authority_signed.py",
    "coord_communication.py",
    "coord_ledger.py",
    "coord_ledger_authority.py",
    "coord_p14b_authority.py",
)

ACCESS_PROBE = r'''
import json
import os
import sys

result = {
    "uid": os.getuid(),
    "gid": os.getgid(),
    "imports": False,
    "paths": {},
    "ancestors": {},
}
try:
    import coord_trust_service
    import coord_trust_crossuid_client
    import coord_trust_crossuid_common
    import coord_trust_crossuid_crash_service
    result["imports"] = True
except Exception as exc:
    result["import_error"] = type(exc).__name__ + ": " + str(exc)
for spec in sys.argv[1:]:
    kind, path = spec.split(":", 1)
    try:
        if kind == "file":
            with open(path, "rb") as handle:
                handle.read(1)
            result["paths"][path] = "readable"
        elif kind == "dir":
            result["paths"][path] = bool(
                os.access(path, os.R_OK | os.X_OK))
        elif kind == "ancestor":
            result["ancestors"][path] = bool(os.access(path, os.X_OK))
    except OSError as exc:
        result["paths"][path] = type(exc).__name__
print(json.dumps(result, sort_keys=True))
'''


class HarnessError(RuntimeError):
    pass


@dataclass
class Check:
    gate: str
    name: str
    passed: bool
    detail: str
    blocked: bool = False

    def to_mapping(self) -> dict[str, Any]:
        return {
            "gate": self.gate,
            "name": self.name,
            "passed": self.passed,
            "blocked": self.blocked,
            "detail": self.detail,
        }


@dataclass
class ServiceProcess:
    proc: subprocess.Popen
    stdout_path: str
    stderr_path: str
    label: str
    config: Mapping[str, Any]


@dataclass
class Layout:
    plan: common.Plan
    config: dict[str, Any]


class Deadline:
    def __init__(self, seconds: float) -> None:
        self.end = time.monotonic() + float(seconds)

    def left(self) -> float:
        return self.end - time.monotonic()

    def check(self) -> None:
        if self.left() <= 0:
            raise HarnessError("P4-TIMEOUT")


class Harness:
    def __init__(self, *, repo: str, output: str, deadline_seconds: float,
                 keep: bool = False, run_id: Optional[str] = None,
                 work_root: Optional[str] = None) -> None:
        self.repo = os.path.abspath(repo)
        self.output = os.path.abspath(output)
        os.makedirs(self.output, mode=0o755, exist_ok=True)
        self.run_id = run_id or str(os.getpid())
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", self.run_id):
            raise HarnessError("run_id inválido")
        if work_root is None:
            self.work_root = tempfile.mkdtemp(
                prefix="coord-trust-p4-work-", dir="/tmp")
        else:
            self.work_root = os.path.abspath(work_root)
            os.makedirs(self.work_root, mode=0o711, exist_ok=True)
        os.chmod(self.work_root, 0o711)
        if os.geteuid() == 0:
            os.chown(self.work_root, 0, 0)
        self.deadline_seconds = float(deadline_seconds)
        self.deadline = Deadline(deadline_seconds)
        self.keep = keep
        self.checks: list[Check] = []
        self.layouts: list[Layout] = []
        self.service: Optional[ServiceProcess] = None
        self.service_logs: dict[str, str] = {}
        self.signer_dir = tempfile.mkdtemp(
            prefix="keys-", dir=self.work_root)
        self.runtime_dir = SCRIPTS_DIR
        self.service_cli = SERVICE_CLI
        self.crash_service = CRASH_SERVICE
        self.client_script = CLIENT
        self.runtime_manifest: dict[str, str] = {}
        self.paths: list[str] = [self.work_root, self.signer_dir, self.output]
        self.process_groups: set[int] = set()
        self.cleanup_manifest = os.path.join(self.output, "cleanup.json")
        self.now = datetime.now(timezone.utc)
        self.signer: Optional[p14b.SyntheticSigner] = None
        self.allowlist: Optional[cas.ApproverAllowlist] = None
        self.before_hashes: dict[str, str] = {}
        self.after_hashes: dict[str, str] = {}
        self.setpriv_has_no_new_privs = self._supports_setpriv(
            "--no-new-privs")
        self.setpriv_has_ambient = self._supports_setpriv("--ambient-caps")
        self._write_cleanup_manifest()

    @staticmethod
    def _supports_setpriv(option: str) -> bool:
        try:
            result = subprocess.run(
                [SETPRIV, "--help"], capture_output=True, text=True,
                timeout=5)
        except (OSError, subprocess.SubprocessError):
            return False
        return option in (result.stdout + result.stderr)

    def stage(self, name: str) -> None:
        self.deadline.check()
        print(f"\n=== {name} ===", flush=True)

    def check(self, gate: str, name: str, passed: bool,
              detail: Any = "", *, blocked: bool = False) -> None:
        text = detail if isinstance(detail, str) else common.canonical_json(
            detail)
        text = common.sanitize_text(text, tuple(self.paths + [self.repo]))
        if len(text) > 1600:
            text = text[:1597] + "..."
        if blocked and passed:
            raise HarnessError("check bloqueado não pode ser PASS")
        check = Check(gate, name, bool(passed) and not blocked, text, blocked)
        self.checks.append(check)
        status = "BLOCKED" if blocked else ("PASS" if check.passed else "FAIL")
        print(f"{status} [{gate}] {name}"
              + (f" — {text[:240]}" if text else ""), flush=True)

    def _write_cleanup_manifest(self) -> None:
        """Manifesto exato para a limpeza independente do workflow.

        Não contém chave ou credencial; contém somente paths do runner, run_id
        e process groups criados por esta execução. A etapa de limpeza valida o
        marcador `COORD_TRUST_RUN_ID` antes de sinalizar qualquer grupo.
        """
        common.write_json(
            self.cleanup_manifest,
            {
                "schema": "BT-COORD-TRUST-CROSSUID-CLEANUP-V1",
                "run_id": self.run_id,
                "work_root": self.work_root,
                "output": self.output,
                "process_groups": sorted(self.process_groups),
                "updated_at": common.iso(datetime.now(timezone.utc)),
            },
            mode=0o644,
        )

    def prepare_runtime_bundle(self, source_dir: Optional[str] = None) -> None:
        """Copia o fecho mínimo para um caminho atravessável pelos UIDs.

        O checkout do runner pode ter ancestrais 0700/inacessíveis ao UID
        efêmero. A cópia fica sob o work_root 0711, com diretório 0555 e
        arquivos 0444 (root), fora do checkout, HOME e estado protegido.
        """
        source = os.path.abspath(source_dir or SCRIPTS_DIR)
        runtime = os.path.join(self.work_root, "runtime")
        if os.path.lexists(runtime):
            os.chmod(runtime, 0o700)
            shutil.rmtree(runtime)
        os.makedirs(runtime, mode=0o700)
        manifest: dict[str, str] = {}
        for name in RUNTIME_FILES:
            source_path = os.path.join(source, name)
            info = os.lstat(source_path)
            if (stat.S_ISLNK(info.st_mode)
                    or not stat.S_ISREG(info.st_mode)
                    or info.st_nlink != 1):
                raise HarnessError("script de runtime inválido: " + name)
            with open(source_path, "rb") as handle:
                payload = handle.read()
            target = os.path.join(runtime, name)
            with open(target, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(target, 0o444)
            manifest[name] = hashlib.sha256(payload).hexdigest()
        manifest_path = os.path.join(runtime, "runtime-manifest.json")
        common.write_json(
            manifest_path,
            {
                "schema": "BT-COORD-TRUST-CROSSUID-RUNTIME-V1",
                "files": manifest,
                "source_head": self._repo_head(),
            },
            mode=0o444,
        )
        os.chmod(manifest_path, 0o444)
        os.chmod(runtime, 0o555)
        if os.geteuid() == 0:
            os.chown(runtime, 0, 0)
            for name in RUNTIME_FILES + ("runtime-manifest.json",):
                os.chown(os.path.join(runtime, name), 0, 0)
        self.runtime_dir = runtime
        self.service_cli = os.path.join(runtime, "coord_trust_service_cli.py")
        self.crash_service = os.path.join(
            runtime, "coord_trust_crossuid_crash_service.py")
        self.client_script = os.path.join(
            runtime, "coord_trust_crossuid_client.py")
        self.runtime_manifest = manifest

    def verify_runtime_bundle(self, source_dir: Optional[str] = None) -> bool:
        source = os.path.abspath(source_dir or SCRIPTS_DIR)
        runtime = self.runtime_dir
        details: dict[str, Any] = {
            "runtime_outside_source": False,
            "directory_mode": None,
            "files": {},
        }
        ok = True
        try:
            common_path = os.path.commonpath([runtime, source])
            details["runtime_outside_source"] = common_path != source
        except ValueError:
            details["runtime_outside_source"] = True
        if not details["runtime_outside_source"]:
            ok = False
        try:
            info = os.lstat(runtime)
            details["directory_mode"] = oct(info.st_mode & 0o777)
            if (not stat.S_ISDIR(info.st_mode)
                    or info.st_mode & 0o222
                    or (info.st_mode & 0o777) != 0o555
                    or (os.geteuid() == 0 and info.st_uid != 0)):
                ok = False
        except OSError:
            details["directory_mode"] = "missing"
            ok = False
        for name, expected in self.runtime_manifest.items():
            path = os.path.join(runtime, name)
            try:
                info = os.lstat(path)
                with open(path, "rb") as handle:
                    actual = hashlib.sha256(handle.read()).hexdigest()
                file_ok = (
                    stat.S_ISREG(info.st_mode)
                    and info.st_nlink == 1
                    and (info.st_mode & 0o777) == 0o444
                    and (os.geteuid() != 0 or info.st_uid == 0)
                    and actual == expected)
            except OSError:
                file_ok = False
                actual = "missing"
            details["files"][name] = {
                "ok": file_ok, "sha256": actual}
            ok = ok and file_ok
        self.check("G0", "runtime bundle mínimo/read-only/fora do checkout",
                   ok, details)
        return ok

    def _probe_env(self, plan: common.Plan, uid: int) -> dict[str, str]:
        if uid == common.SERVICE_UID:
            return self.service_env(plan)
        return self.client_env(plan, uid)

    def _identity_probe(self, plan: common.Plan, uid: int, gid: int,
                        specs: list[str]) -> dict[str, Any]:
        cmd = self._setpriv_prefix(uid, gid) + [
            sys.executable, "-c", ACCESS_PROBE, *specs]
        stdout, stderr, timed_out, rc, _elapsed = self._run_foreground(
            cmd, self._probe_env(plan, uid), 20.0)
        if timed_out:
            return {"ok": False, "error": "probe-timeout"}
        for line in reversed((stdout or "").splitlines()):
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict):
                value["ok"] = rc == 0
                return value
        return {"ok": False, "error": "probe-no-json",
                "stderr": common.sanitize_text(
                    stderr or "", tuple(self.paths + [self.repo]))[-1000:]}

    def verify_runtime_access(self, layout: Layout) -> bool:
        """Comprova filesystem sob os UIDs efetivos antes de iniciar o serviço."""
        plan = layout.plan
        script_specs = [
            "file:" + os.path.join(self.runtime_dir, name)
            for name in RUNTIME_FILES
        ]
        ancestor_specs = [
            "ancestor:" + self.work_root,
            "ancestor:" + self.runtime_dir,
            "ancestor:/tmp",
            "ancestor:/",
        ]
        base_specs = script_specs + ancestor_specs
        service_specs = base_specs + [
            "dir:" + plan.state_dir,
            "file:" + plan.trust_store_path,
            "file:" + plan.registry_path,
            "dir:" + plan.anchor_dir,
            "dir:" + self.signer_dir,
        ]
        consumer_specs = base_specs + [
            "dir:" + plan.state_dir,
            "file:" + plan.trust_store_path,
            "file:" + plan.registry_path,
            "file:" + os.path.join(plan.state_dir, "ledger.db"),
            "dir:" + plan.anchor_dir,
            "file:" + plan.anchor_path,
            "dir:" + self.signer_dir,
        ]
        service = self._identity_probe(
            plan, common.SERVICE_UID, common.SERVICE_GID, service_specs)
        consumer = self._identity_probe(
            plan, common.CONSUMER_UID, common.CONSUMER_GID, consumer_specs)

        def scripts_ok(probe: Mapping[str, Any]) -> bool:
            paths = probe.get("paths", {}) if isinstance(probe, Mapping) else {}
            return (bool(probe.get("imports"))
                    and all(paths.get(os.path.join(self.runtime_dir, name))
                            == "readable" for name in RUNTIME_FILES))

        def ancestors_ok(probe: Mapping[str, Any]) -> bool:
            values = probe.get("ancestors", {}) if isinstance(probe, Mapping) else {}
            return bool(values) and all(values.values())

        def state_ok(probe: Mapping[str, Any]) -> bool:
            paths = probe.get("paths", {}) if isinstance(probe, Mapping) else {}
            return (paths.get(plan.state_dir) is True
                    and paths.get(plan.trust_store_path) == "readable"
                    and paths.get(plan.registry_path) == "readable"
                    and paths.get(plan.anchor_dir) is True)

        def denied(probe: Mapping[str, Any]) -> bool:
            paths = probe.get("paths", {}) if isinstance(probe, Mapping) else {}
            return (paths.get(plan.state_dir) is False
                    and paths.get(plan.trust_store_path) != "readable"
                    and paths.get(plan.registry_path) != "readable"
                    and paths.get(plan.anchor_dir) is False
                    and paths.get(plan.anchor_path) != "readable"
                    and paths.get(os.path.join(
                        plan.state_dir, "ledger.db")) != "readable"
                    and paths.get(self.signer_dir) is False)

        identity_ok = (
            service.get("uid") == common.SERVICE_UID
            and service.get("gid") == common.SERVICE_GID
            and consumer.get("uid") == common.CONSUMER_UID
            and consumer.get("gid") == common.CONSUMER_GID
        )
        self.check(
            "G0", "runtime bundle lido/importado pelos UIDs efetivos",
            identity_ok and scripts_ok(service) and scripts_ok(consumer),
            {"service": {"uid": service.get("uid"), "gid": service.get("gid"),
                         "imports": service.get("imports"),
                         "import_error": service.get("import_error")},
             "consumer": {"uid": consumer.get("uid"),
                          "gid": consumer.get("gid"),
                          "imports": consumer.get("imports"),
                          "import_error": consumer.get("import_error")}},
        )
        self.check(
            "G0", "ancestrais do bundle atravessáveis pelos UIDs",
            ancestors_ok(service) and ancestors_ok(consumer),
            {"service": service.get("ancestors"),
             "consumer": consumer.get("ancestors")},
        )
        self.check(
            "G0", "serviço lê state/trust/anchor; chaves negadas",
            state_ok(service)
            and service.get("paths", {}).get(self.signer_dir) is False,
            {"uid": service.get("uid"), "paths": service.get("paths")},
        )
        self.check(
            "G0", "consumidor não lê state/trust/anchor/chaves",
            denied(consumer),
            {"uid": consumer.get("uid"), "paths": consumer.get("paths")},
        )
        consumers = [
            identity_ok, scripts_ok(service), scripts_ok(consumer),
            ancestors_ok(service), ancestors_ok(consumer),
            state_ok(service), denied(consumer),
        ]
        if not all(consumers):
            return False
        return True

    # ---------- layout / ownership ----------

    def new_layout(self, tag: str) -> Layout:
        self.deadline.check()
        root = tempfile.mkdtemp(
            prefix=f"coord-trust-p4-{tag}-", dir=self.work_root)
        plan = common.build_plan(root)
        self.paths.append(root)
        self._create_layout(plan)
        self.layouts.append(Layout(plan=plan, config={}))
        return self.layouts[-1]

    @staticmethod
    def _chown(path: str, uid: int, gid: int, mode: int) -> None:
        os.chown(path, uid, gid)
        os.chmod(path, mode)

    def _create_layout(self, plan: common.Plan) -> None:
        os.chown(plan.root, 0, 0)
        os.chmod(plan.root, 0o711)
        os.makedirs(plan.state_dir, exist_ok=True)
        self._chown(plan.state_dir, common.SERVICE_UID,
                    common.SERVICE_GID, 0o700)
        os.makedirs(plan.anchor_dir, exist_ok=True)
        self._chown(plan.anchor_dir, common.SERVICE_UID,
                    common.SERVICE_GID, 0o700)
        os.makedirs(plan.runtime_dir, exist_ok=True)
        self._chown(plan.runtime_dir, common.SERVICE_UID,
                    common.SERVICE_GID, 0o750)
        os.makedirs(plan.artifacts_dir, exist_ok=True)
        self._chown(plan.artifacts_dir, 0, 0, 0o755)
        os.makedirs(plan.relay_dir, exist_ok=True)
        self._chown(plan.relay_dir, 0, 0, 0o777)
        os.makedirs(plan.logs_dir, exist_ok=True)
        self._chown(plan.logs_dir, 0, 0, 0o755)
        os.makedirs(plan.keys_dir, exist_ok=True)
        self._chown(plan.keys_dir, 0, 0, 0o700)
        for path, uid, gid in (
            (plan.service_home, common.SERVICE_UID, common.SERVICE_GID),
            (plan.service_tmp, common.SERVICE_UID, common.SERVICE_GID),
            (plan.consumer_home, common.CONSUMER_UID, common.CONSUMER_GID),
            (plan.consumer_tmp, common.CONSUMER_UID, common.CONSUMER_GID),
            (plan.observer_home, common.OBSERVER_UID, common.OBSERVER_GID),
            (plan.observer_tmp, common.OBSERVER_UID, common.OBSERVER_GID),
            (plan.unregistered_home, common.UNREGISTERED_UID,
             common.UNREGISTERED_GID),
            (plan.unregistered_tmp, common.UNREGISTERED_UID,
             common.UNREGISTERED_GID),
        ):
            os.makedirs(path, exist_ok=True)
            self._chown(path, uid, gid, 0o700)

    def write_config(self, layout: Layout, mapping: Optional[Mapping[str, Any]] = None) -> None:
        config = dict(mapping or common.config_mapping(layout.plan))
        common.write_json(layout.plan.config_path, config, mode=0o600)
        self._chown(layout.plan.config_path, common.SERVICE_UID,
                    common.SERVICE_GID, 0o600)
        layout.config = config

    def prepare_trust(self, layout: Layout) -> None:
        assert self.signer is not None and self.allowlist is not None
        plan = layout.plan
        svc.write_trust_store(plan.trust_store_path, self.allowlist)
        self._chown(plan.trust_store_path, common.SERVICE_UID,
                    common.SERVICE_GID, 0o600)
        registry = svc.ConsumerRegistry((
            svc.ConsumerPolicy(
                consumer_id="executor", uid=common.CONSUMER_UID,
                allowed_actions=common.ALL_ACTIONS,
                agent_id="agent-crossuid", role="executor"),
            svc.ConsumerPolicy(
                consumer_id="observer", uid=common.OBSERVER_UID,
                allowed_actions=frozenset({"status"}),
                agent_id=None, role="observer"),
        ), allow_same_uid=False)
        registry.validate_for_service(common.SERVICE_UID)
        svc.write_consumer_registry(plan.registry_path, registry)
        self._chown(plan.registry_path, common.SERVICE_UID,
                    common.SERVICE_GID, 0o600)

    # ---------- environment / spawn ----------

    def service_env(self, plan: common.Plan) -> dict[str, str]:
        return {
            "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
            "HOME": plan.service_home,
            "TMPDIR": plan.service_tmp,
            "LANG": "C",
            "LC_ALL": "C",
            "PYTHONPATH": self.runtime_dir,
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONUNBUFFERED": "1",
            "COORD_TRUST_RUN_ID": self.run_id,
        }

    def client_env(self, plan: common.Plan, uid: int) -> dict[str, str]:
        if uid == common.OBSERVER_UID:
            home, tmp = plan.observer_home, plan.observer_tmp
        elif uid == common.UNREGISTERED_UID:
            home, tmp = plan.unregistered_home, plan.unregistered_tmp
        else:
            home, tmp = plan.consumer_home, plan.consumer_tmp
        return {
            "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
            "HOME": home,
            "TMPDIR": tmp,
            "LANG": "C",
            "LC_ALL": "C",
            "PYTHONPATH": self.runtime_dir,
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONUNBUFFERED": "1",
            "COORD_TRUST_RUN_ID": self.run_id,
        }

    def _setpriv_prefix(self, uid: int, gid: int, *,
                        ambient_setgid: bool = False) -> list[str]:
        cmd = [
            SETPRIV,
            f"--reuid={uid}",
            f"--regid={gid}",
            "--clear-groups",
        ]
        if self.setpriv_has_no_new_privs:
            cmd.append("--no-new-privs")
        if ambient_setgid:
            if not self.setpriv_has_ambient:
                raise HarnessError("setpriv sem --ambient-caps")
            cmd.extend([
                "--inh-caps", "+setgid",
                "--ambient-caps", "+setgid",
            ])
        return cmd

    def _client_command(self, uid: int, gid: int, job: str, mode: str,
                        extra: tuple[str, ...], *,
                        ambient_setgid: bool = False) -> list[str]:
        return self._setpriv_prefix(uid, gid, ambient_setgid=ambient_setgid) + [
            sys.executable, self.client_script, "--job", job,
            "--mode", mode, *extra]

    def start_process(self, cmd: list[str], env: Mapping[str, str],
                      label: str, plan: common.Plan) -> ServiceProcess:
        stdout_path = os.path.join(plan.logs_dir, label + "-stdout.log")
        stderr_path = os.path.join(plan.logs_dir, label + "-stderr.log")
        out_fd = os.open(
            stdout_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        err_fd = os.open(
            stderr_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        out = os.fdopen(out_fd, "wb")
        err = os.fdopen(err_fd, "wb")
        try:
            proc = subprocess.Popen(
                cmd, env=dict(env), cwd=self.runtime_dir,
                stdout=out, stderr=err,
                start_new_session=True, close_fds=True)
        finally:
            out.close()
            err.close()
        self.process_groups.add(proc.pid)
        self._write_cleanup_manifest()
        return ServiceProcess(
            proc=proc, stdout_path=stdout_path, stderr_path=stderr_path,
            label=label, config={})

    def wait_for_socket(self, plan: common.Plan, proc: subprocess.Popen,
                        timeout: float = 10.0) -> None:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            self.deadline.check()
            if proc.poll() is not None:
                raise HarnessError(
                    f"serviço encerrou antes do socket (rc={proc.returncode})")
            if os.path.exists(plan.socket_path):
                try:
                    info = os.lstat(plan.socket_path)
                except OSError:
                    pass
                else:
                    if stat.S_ISSOCK(info.st_mode):
                        return
            time.sleep(0.05)
        raise HarnessError("timeout aguardando socket do serviço")

    def start_service(self, layout: Layout, *, crash_point: Optional[str] = None,
                      label: Optional[str] = None) -> ServiceProcess:
        plan = layout.plan
        self.deadline.check()
        if os.path.lexists(plan.socket_path):
            try:
                os.unlink(plan.socket_path)
            except OSError:
                pass
        script = self.crash_service if crash_point else self.service_cli
        args = [sys.executable, script, "--config", plan.config_path]
        if crash_point:
            args.extend(["--crash-point", crash_point])
        label = label or ("crash-" + crash_point if crash_point else "service")
        cmd = self._setpriv_prefix(
            common.SERVICE_UID, common.SERVICE_GID) + args
        sp = self.start_process(
            cmd, self.service_env(plan), label, plan)
        sp.config = dict(layout.config)
        self.service = sp
        try:
            self.wait_for_socket(plan, sp.proc)
        except Exception:
            self.stop_service()
            raise
        identity = self.proc_status(sp.proc.pid)
        self.check(
            "G1", "serviço roda em UID/GID dedicado e sem capabilities",
            identity.get("uid") == common.SERVICE_UID
            and identity.get("gid") == common.SERVICE_GID
            and identity.get("cap_eff") in {"0000000000000000", "0"}
            and (not self.setpriv_has_no_new_privs
                 or identity.get("no_new_privs") == "1"),
            identity)
        return sp

    def stop_service(self) -> None:
        sp = self.service
        if sp is None:
            return
        self.service = None
        try:
            if sp.proc.poll() is None:
                try:
                    os.killpg(sp.proc.pid, signal.SIGTERM)
                except OSError:
                    sp.proc.terminate()
                try:
                    sp.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(sp.proc.pid, signal.SIGKILL)
                    except OSError:
                        sp.proc.kill()
                    sp.proc.wait(timeout=5)
        finally:
            self.process_groups.discard(sp.proc.pid)
            self._write_cleanup_manifest()
            self._collect_logs(sp)

    @staticmethod
    def proc_status(pid: int) -> dict[str, Any]:
        values: dict[str, Any] = {}
        try:
            with open(f"/proc/{pid}/status", encoding="utf-8") as handle:
                for line in handle:
                    key, _, rest = line.partition(":")
                    rest = rest.strip()
                    if key == "Uid":
                        values["uid"] = int(rest.split()[0])
                    elif key == "Gid":
                        values["gid"] = int(rest.split()[0])
                    elif key == "CapEff":
                        values["cap_eff"] = rest
                    elif key == "NoNewPrivs":
                        values["no_new_privs"] = rest
        except OSError as exc:
            values["error"] = type(exc).__name__
        return values

    def _collect_logs(self, sp: ServiceProcess) -> None:
        chunks = []
        for kind, path in (("stdout", sp.stdout_path),
                           ("stderr", sp.stderr_path)):
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    chunks.append(f"--- {sp.label} {kind} ---\n" + handle.read())
            except OSError:
                pass
        self.service_logs[sp.label] = common.sanitize_text(
            "\n".join(chunks), tuple(self.paths + [self.repo]))

    def _run_foreground(self, cmd: list[str], env: Mapping[str, str],
                        timeout: float) -> tuple[str, str, bool, int, float]:
        started = time.monotonic()
        try:
            proc = subprocess.Popen(
                cmd, env=dict(env), cwd=self.runtime_dir,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                start_new_session=True)
        except OSError as exc:
            return "", type(exc).__name__ + ": " + str(exc), False, -1, 0.0
        self.process_groups.add(proc.pid)
        self._write_cleanup_manifest()
        try:
            stdout, stderr = proc.communicate(
                timeout=min(timeout, max(1.0, self.deadline.left())))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                proc.kill()
            stdout, stderr = proc.communicate()
            self.process_groups.discard(proc.pid)
            self._write_cleanup_manifest()
            return stdout, stderr, True, proc.returncode, round(
                time.monotonic() - started, 3)
        self.process_groups.discard(proc.pid)
        self._write_cleanup_manifest()
        return stdout, stderr, False, proc.returncode, round(
            time.monotonic() - started, 3)

    def run_client(self, layout: Layout, job_path: str, mode: str, *,
                   uid: int = common.CONSUMER_UID,
                   gid: int = common.CONSUMER_GID,
                   extra: tuple[str, ...] = (),
                   ambient_setgid: bool = False,
                   use_unshare: bool = False,
                   timeout: float = 30.0) -> dict[str, Any]:
        self.deadline.check()
        cmd = self._client_command(
            uid, gid, job_path, mode, extra,
            ambient_setgid=ambient_setgid)
        if use_unshare:
            cmd = [UNSHARE, "-n", "--"] + cmd
        env = self.client_env(layout.plan, uid)
        paths = tuple(self.paths + [self.repo])
        stdout, stderr, timed_out, rc, elapsed = self._run_foreground(
            cmd, env, timeout)
        if timed_out:
            return {"ok": False, "error": "client-timeout",
                    "stdout": common.sanitize_text(stdout or "", paths),
                    "stderr": "timeout", "elapsed": elapsed}
        if rc < 0 and not stdout:
            return {"ok": False, "error": common.sanitize_text(
                stderr or "client-launch-error", paths)}
        parsed = self._parse_client_stdout(stdout)
        parsed.update({
            "rc": rc,
            "elapsed": elapsed,
            "stderr": common.sanitize_text(stderr or "", paths)[-4000:],
        })
        parsed = common.sanitize_value(parsed, paths)
        if parsed.get("error") == "no-json" and use_unshare:
            # O runner pode negar um netns aninhado; o harness já executa no
            # netns externo sem rede. Registra o fallback sem mascarar o teste.
            fallback_cmd = self._client_command(
                uid, gid, job_path, mode, extra,
                ambient_setgid=ambient_setgid)
            stdout, stderr, timed_out, rc, elapsed = self._run_foreground(
                fallback_cmd, env, timeout)
            if timed_out:
                return {"ok": False, "error": "fallback-timeout",
                        "unshare_fallback": True}
            parsed = self._parse_client_stdout(stdout)
            parsed.update({
                "rc": rc,
                "elapsed": elapsed,
                "unshare_fallback": True,
                "stderr": common.sanitize_text(stderr or "", paths)[-4000:],
            })
            parsed = common.sanitize_value(parsed, paths)
        return parsed

    def start_client(self, layout: Layout, job_path: str, mode: str, *,
                     uid: int = common.CONSUMER_UID,
                     gid: int = common.CONSUMER_GID,
                     extra: tuple[str, ...] = (),
                     label: str = "client") -> tuple[subprocess.Popen, str, str]:
        self.deadline.check()
        cmd = self._client_command(uid, gid, job_path, mode, extra)
        stdout_path = os.path.join(layout.plan.logs_dir, label + "-stdout.log")
        stderr_path = os.path.join(layout.plan.logs_dir, label + "-stderr.log")
        out_fd = os.open(
            stdout_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        err_fd = os.open(
            stderr_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        out = os.fdopen(out_fd, "wb")
        err = os.fdopen(err_fd, "wb")
        try:
            proc = subprocess.Popen(
                cmd, env=self.client_env(layout.plan, uid),
                cwd=self.runtime_dir, stdout=out, stderr=err,
                start_new_session=True, close_fds=True)
        finally:
            out.close()
            err.close()
        self.process_groups.add(proc.pid)
        self._write_cleanup_manifest()
        return proc, stdout_path, stderr_path

    def wait_client(self, proc: subprocess.Popen, stdout_path: str,
                    stderr_path: str, timeout: float = 30.0) -> dict[str, Any]:
        try:
            proc.wait(timeout=min(timeout, max(1.0, self.deadline.left())))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                proc.kill()
            proc.wait(timeout=5)
        self.process_groups.discard(proc.pid)
        self._write_cleanup_manifest()
        try:
            with open(stdout_path, encoding="utf-8", errors="replace") as handle:
                stdout = handle.read()
        except OSError:
            stdout = ""
        try:
            with open(stderr_path, encoding="utf-8", errors="replace") as handle:
                stderr = handle.read()
        except OSError:
            stderr = ""
        paths = tuple(self.paths + [self.repo])
        parsed = self._parse_client_stdout(stdout)
        parsed.update({
            "rc": proc.returncode,
            "stderr": common.sanitize_text(stderr, paths)[-4000:],
        })
        return common.sanitize_value(parsed, paths)

    @staticmethod
    def _parse_client_stdout(stdout: str) -> dict[str, Any]:
        for line in reversed(stdout.splitlines()):
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict):
                return value
        return {"ok": False, "error": "no-json", "stdout": stdout[-2000:]}

    @staticmethod
    def wait_for_path(path: str, timeout: float = 10.0) -> None:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if os.path.exists(path):
                return
            time.sleep(0.02)
        raise HarnessError("timeout aguardando " + os.path.basename(path))

    # ---------- database backup / restore ----------

    @staticmethod
    def backup_db(source: str, target: str) -> None:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        src = sqlite3.connect(source)
        dst = sqlite3.connect(target)
        try:
            src.backup(dst)
        finally:
            dst.close()
            src.close()

    @staticmethod
    def restore_db(source: str, target: str, uid: int, gid: int) -> None:
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
        os.chown(target, uid, gid)
        os.chmod(target, 0o600)

    # ---------- checks ----------

    def check_layout_permissions(self, layout: Layout) -> None:
        plan = layout.plan
        def info(path: str) -> tuple[int, int, int]:
            value = os.lstat(path)
            return value.st_uid, value.st_gid, value.st_mode & 0o777
        checks = {
            "state_dir": (info(plan.state_dir), (common.SERVICE_UID,
                                                 common.SERVICE_GID, 0o700)),
            "anchor_dir": (info(plan.anchor_dir), (common.SERVICE_UID,
                                                   common.SERVICE_GID, 0o700)),
            "runtime_dir": (info(plan.runtime_dir), (common.SERVICE_UID,
                                                     common.SERVICE_GID, 0o750)),
            "socket": (info(plan.socket_path), (common.SERVICE_UID,
                                                common.SERVICE_GID, 0o660)),
            "trust_store": (info(plan.trust_store_path),
                            (common.SERVICE_UID, common.SERVICE_GID, 0o600)),
            "registry": (info(plan.registry_path),
                         (common.SERVICE_UID, common.SERVICE_GID, 0o600)),
            "config": (info(plan.config_path),
                       (common.SERVICE_UID, common.SERVICE_GID, 0o600)),
            "keys_dir": (info(plan.keys_dir), (0, 0, 0o700)),
        }
        ok = all(actual == expected for actual, expected in checks.values())
        self.check("G1", "layout/UIDs/permissões canônicas", ok, checks)
        anchor = os.path.abspath(plan.anchor_path)
        state = os.path.abspath(plan.state_dir)
        try:
            anchor_outside = os.path.commonpath([anchor, state]) != state
        except ValueError:
            anchor_outside = True
        self.check(
            "G1", "âncora fora do state_dir", anchor_outside,
            {"anchor": "<anchor>", "state": "<state>"})
        self.check(
            "G1", "config real_transport=false e test_mode=false",
            layout.config.get("real_transport") is False
            and layout.config.get("test_mode") is False
            and layout.config.get("allow_same_uid") is False,
            {"real_transport": layout.config.get("real_transport"),
             "test_mode": layout.config.get("test_mode"),
             "allow_same_uid": layout.config.get("allow_same_uid")})

    @staticmethod
    def _hash_files(paths: tuple[str, ...]) -> dict[str, str]:
        result = {}
        for path in paths:
            try:
                with open(path, "rb") as handle:
                    result[path] = hashlib.sha256(handle.read()).hexdigest()
            except OSError:
                result[path] = "UNAVAILABLE"
        return result

    def check_passwd_unchanged(self) -> None:
        paths = ("/etc/passwd", "/etc/group", "/etc/shadow")
        self.after_hashes = self._hash_files(paths)
        self.check(
            "G0", "nenhum usuário persistente criado/alterado",
            self.before_hashes == self.after_hashes,
            {"before": self.before_hashes, "after": self.after_hashes})

    def check_source_transport_disabled(self,
                                        sandbox_value: Optional[Mapping[str, Any]] = None
                                        ) -> None:
        service_sources = {}
        for name, path in (
            ("coord_trust_service.py", svc.__file__),
            ("coord_trust_crossuid_crash_service.py", self.crash_service),
        ):
            with open(path, encoding="utf-8") as handle:
                service_sources[name] = handle.read()
        forbidden = {}
        for name, source in service_sources.items():
            tokens = [token for token in ("AF_INET", "create_connection")
                      if token in source]
            if tokens:
                forbidden[name] = tokens
        self.check("G9", "serviço/crash service sem AF_INET/create_connection",
                   not forbidden, {"forbidden": forbidden})
        with open(self.client_script, encoding="utf-8") as handle:
            client_source = handle.read()
        client_network_tokens = [
            token for token in ("AF_INET", "create_connection")
            if token in client_source
        ]
        self.check(
            "G9", "cliente só usa rede como probe negativa em netns",
            (not client_network_tokens)
            or (sandbox_value is not None
                and sandbox_value.get("network_error") is not None),
            {"client_tokens": client_network_tokens,
             "network_error": (sandbox_value or {}).get("network_error")},
        )
        config = self.layouts[0].config if self.layouts else {}
        self.check("G9", "configuração real_transport=false",
                   config.get("real_transport") is False,
                   {"real_transport": config.get("real_transport")})

    # ---------- G1/G2/G8/G9 ----------

    def check_runtime_files(self, layout: Layout) -> None:
        runtime_files = {}
        for name, path in (
            ("ledger", os.path.join(layout.plan.state_dir, "ledger.db")),
            ("service_db", os.path.join(layout.plan.state_dir, "service.db")),
            ("anchor", layout.plan.anchor_path),
        ):
            try:
                info = os.lstat(path)
                runtime_files[name] = {
                    "uid": info.st_uid, "gid": info.st_gid,
                    "mode": oct(info.st_mode & 0o777),
                    "nlink": info.st_nlink,
                }
            except OSError as exc:
                runtime_files[name] = type(exc).__name__
        self.check(
            "G1", "ledger/service state/âncora privados e sem hardlink",
            all(
                isinstance(value, Mapping)
                and value.get("uid") == common.SERVICE_UID
                and value.get("gid") == common.SERVICE_GID
                and value.get("mode") == "0o600"
                and value.get("nlink") == 1
                for value in runtime_files.values()),
            runtime_files)

    def check_g6(self, layout: Layout) -> None:
        gid_socket = self.run_client(
            layout, layout.plan.client_job_path, "gid-socket-denied",
            gid=common.WRONG_GID)
        gid_socket_value = gid_socket.get("json") if "json" in gid_socket \
            else gid_socket
        if not self.setpriv_has_ambient:
            self.check(
                "G6", "GID divergente com UID correto é recusado pelo serviço",
                False,
                {"blocked": "setpriv sem --ambient-caps; EACCES de socket "
                            "não certifica a rejeição de credencial",
                 "socket_fallback": gid_socket_value},
                blocked=True,
            )
            return
        gid_div = self.run_client(
            layout, layout.plan.client_job_path, "gid-divergence",
            ambient_setgid=True)
        gid_value = gid_div.get("json") if "json" in gid_div else gid_div
        if gid_value.get("ok"):
            self.check(
                "G6", "GID divergente com UID correto é recusado pelo serviço",
                True,
                {"per_message": gid_value,
                 "socket_fallback": gid_socket_value},
            )
        elif (gid_value.get("gid_changed") is False
              and gid_value.get("gid_change_error")):
            self.check(
                "G6", "GID divergente com UID correto é recusado pelo serviço",
                False,
                {"blocked": "não foi possível mudar o GID no consumidor",
                 "per_message": gid_value,
                 "socket_fallback": gid_socket_value},
                blocked=True,
            )
        else:
            self.check(
                "G6", "GID divergente com UID correto é recusado pelo serviço",
                False,
                {"per_message": gid_value,
                 "socket_fallback": gid_socket_value},
            )

    def run_main_gates(self) -> None:
        self.stage("G1 — jornada cross-UID completa")
        layout = self.new_layout("main")
        self.prepare_trust(layout)
        if not self.verify_runtime_access(layout):
            raise HarnessError("preflight de filesystem falhou antes do serviço")
        assert self.signer is not None and self.allowlist is not None
        artifacts = common.build_artifacts(
            self.now, self.signer, self.allowlist, tag="main")
        job = common.client_job(layout.plan, artifacts)
        common.validate_client_job(job)
        common.write_json(layout.plan.client_job_path, job)
        self.write_config(layout)
        sp = self.start_service(layout, label="main")
        self.check_layout_permissions(layout)
        prepare = self.run_client(
            layout, layout.plan.client_job_path, "prepare")
        prepare_value = prepare.get("json") if "json" in prepare else prepare
        self.check(
            "G1", "propose/authorize cross-UID",
            prepare_value.get("ok"),
            {"prepare": prepare_value.get("results", {})})
        reserve = self.run_client(
            layout, layout.plan.client_job_path, "reserve")
        reserve_value = reserve.get("json") if "json" in reserve else reserve
        lease = reserve_value.get("lease_id")
        self.check("G1", "reserve devolve lease", reserve_value.get("ok")
                   and isinstance(lease, str), reserve_value)
        asserted = self.run_client(
            layout, layout.plan.client_job_path, "assert",
            extra=("--lease", str(lease)))
        asserted_value = asserted.get("json") if "json" in asserted else asserted
        self.check("G1", "assert cross-UID", asserted_value.get("ok"),
                   asserted_value)
        evidence = self.run_client(
            layout, layout.plan.client_job_path, "record-evidence",
            extra=("--lease", str(lease)))
        evidence_value = evidence.get("json") if "json" in evidence else evidence
        self.check("G1", "record_evidence cross-UID", evidence_value.get("ok"),
                   evidence_value)

        reconcile_time = datetime.now(timezone.utc)
        reconcile_payload = common.reconcile_payload(
            reconcile_time, self.signer, self.allowlist, artifacts,
            lease_id=str(lease))
        reconcile_job = dict(job)
        reconcile_job["actions"] = dict(job["actions"])
        reconcile_job["actions"]["reconcile"] = reconcile_payload
        reconcile_path = os.path.join(
            layout.plan.artifacts_dir, "reconcile-job.json")
        common.write_json(reconcile_path, reconcile_job)
        reconciled = self.run_client(
            layout, reconcile_path, "reconcile")
        reconciled_value = reconciled.get("json") if "json" in reconciled \
            else reconciled
        self.check("G1", "reconcile assinado dinamicamente", reconciled_value.get("ok"),
                   reconciled_value)
        status = self.run_client(layout, reconcile_path, "status")
        status_value = status.get("json") if "json" in status else status
        status_result = (status_value.get("response", {}) or {}).get("result", {})
        self.check("G1", "status final atual e sem transporte real",
                   status_result.get("state") == "COMPLETED"
                   and status_result.get("real_transport") is False
                   and status_result.get("reserved") is False,
                   status_result)
        transmit = self.run_client(layout, reconcile_path, "transmit")
        transmit_value = transmit.get("json") if "json" in transmit else transmit
        self.check("G1", "transmit retorna TRANSPORT_DISABLED",
                   transmit_value.get("code") == "TRANSPORT_DISABLED",
                   transmit_value)
        forbidden = self.run_client(layout, reconcile_path, "forbidden")
        forbidden_value = forbidden.get("json") if "json" in forbidden \
            else forbidden
        self.check("G1", "campo de identidade autodeclarado recusado",
                   forbidden_value.get("code") == "PERMISSION_DENIED",
                   forbidden_value)
        replay_assert = self.run_client(
            layout, reconcile_path, "assert",
            extra=("--lease", str(lease)))
        replay_value = replay_assert.get("json") if "json" in replay_assert \
            else replay_assert
        self.check("G1", "replay de assert terminal não é permissão",
                   ((replay_value.get("response", {}) or {}).get("error") or {}
                    ).get("code") == "REPLAY_STALE",
                   replay_value)
        self.check("G1", "jornada completa propose→reconcile",
                   bool(prepare_value.get("ok"))
                   and bool(reserve_value.get("ok"))
                   and bool(asserted_value.get("ok"))
                   and bool(evidence_value.get("ok"))
                   and bool(reconciled_value.get("ok"))
                   and bool(status_value.get("ok")),
                   {"status": "COMPLETED" if status_result.get("state") == "COMPLETED" else status_result})
        self.check_runtime_files(layout)

        observer = self.run_client(
            layout, layout.plan.client_job_path, "observer",
            uid=common.OBSERVER_UID, gid=common.OBSERVER_GID)
        observer_value = observer.get("json") if "json" in observer else observer
        self.check("G1", "observer status sim / propose não",
                   observer_value.get("ok"), observer_value)
        unregistered = self.run_client(
            layout, layout.plan.client_job_path, "unregistered",
            uid=common.UNREGISTERED_UID, gid=common.UNREGISTERED_GID)
        unregistered_value = unregistered.get("json") if "json" in unregistered \
            else unregistered
        self.check("G1", "UID não registrado fechado sem resposta",
                   unregistered_value.get("closed_without_response"),
                   unregistered_value)
        auth_results = {}
        for field in ("service_id", "uid", "gid"):
            auth = self.run_client(
                layout, layout.plan.client_job_path, "auth-wrong",
                extra=("--field", field))
            auth_results[field] = auth.get("json") if "json" in auth else auth
        self.check("G1", "cliente autentica servidor por UID/GID/service_id",
                   all(item.get("blocked") for item in auth_results.values()),
                   auth_results)

        self.stage("G2/G6 — SCM_RIGHTS, credenciais divergentes e GID")
        fd_attach = self.run_client(
            layout, layout.plan.client_job_path, "fd-attach")
        fd_attach_value = fd_attach.get("json") if "json" in fd_attach else fd_attach
        self.check("G2", "SCM_RIGHTS anexado a request é recusado",
                   fd_attach_value.get("closed_without_response"),
                   fd_attach_value)

        ready = os.path.join(layout.plan.relay_dir, "fd-relay.ready")
        relay_socket = os.path.join(layout.plan.relay_dir, "fd-relay.sock")
        source, source_out, source_err = self.start_client(
            layout, layout.plan.client_job_path, "fd-relay-source",
            extra=("--relay-socket", relay_socket, "--ready-path", ready),
            label="fd-relay-source")
        self.wait_for_path(ready)
        helper = self.run_client(
            layout, layout.plan.client_job_path, "fd-relay-helper",
            uid=common.UNREGISTERED_UID, gid=common.UNREGISTERED_GID,
            extra=("--relay-socket", relay_socket))
        source_value = self.wait_client(source, source_out, source_err)
        helper_value = helper.get("json") if "json" in helper else helper
        self.check("G2", "fd real transferido por SCM_RIGHTS entre UIDs",
                   helper_value.get("fd_received") is True,
                   {"helper_uid": helper_value.get("uid"),
                    "helper_gid": helper_value.get("gid")})
        self.check("G2", "credencial divergente após SCM_RIGHTS recusada",
                   source_value.get("ok") is True
                   and helper_value.get("service_closed") is True,
                   {"source": source_value, "helper": helper_value})

        self.check_g6(layout)

        self.stage("G8 — sandbox do consumidor")
        sandbox = common.sandbox_job(
            layout.plan, artifacts, service_pid=sp.proc.pid,
            keys_dir=self.signer_dir)
        common.validate_sandbox_job(sandbox)
        common.write_json(layout.plan.sandbox_job_path, sandbox)
        sandbox_run = self.run_client(
            layout, layout.plan.sandbox_job_path, "sandbox",
            use_unshare=True)
        sandbox_value = sandbox_run.get("json") if "json" in sandbox_run \
            else sandbox_run
        self.check("G8", "sandbox bloqueia estado/chaves, rede e sinal",
                   sandbox_value.get("ok"), sandbox_value)
        self.check("G8", "consumer sem capabilities e com no_new_privs",
                   sandbox_value.get("cap_eff") in {
                       "0000000000000000", "0"}
                   and sandbox_value.get("no_new_privs") == "1",
                   {"cap_eff": sandbox_value.get("cap_eff"),
                    "no_new_privs": sandbox_value.get("no_new_privs")})
        self.check("G8", "consumer não lê ledger/trust/anchor/keys",
                   sandbox_value.get("state_read_blocked"),
                   sandbox_value.get("read_results"))
        self.check("G8", "consumer em network namespace sem rede",
                   sandbox_value.get("network_error") is not None,
                   {"network_error": sandbox_value.get("network_error"),
                    "unshare_fallback": sandbox_run.get("unshare_fallback")})

        self.stage("G9 — transporte real desabilitado")
        self.check_source_transport_disabled(sandbox_value)
        self.check("G9", "status reporta real_transport=false",
                   status_result.get("real_transport") is False,
                   status_result)
        self.check("G9", "transmit recusado durante a execução",
                   transmit_value.get("code") == "TRANSPORT_DISABLED",
                   transmit_value)
        self.check(
            "G9", "execução cross-UID comprovadamente sem rede",
            sandbox_value.get("network_error") is not None,
            {"network_error": sandbox_value.get("network_error"),
             "unshare_fallback": sandbox_run.get("unshare_fallback")},
        )
        self.stop_service()

    # ---------- G3/G5 ----------

    def _recovery_payloads(self, layout: Layout, artifacts: common.MissionArtifacts,
                           *, valid_nonce: str) -> dict[str, Any]:
        assert self.signer is not None and self.allowlist is not None
        now = datetime.now(timezone.utc)
        negatives: dict[str, Any] = {}
        statement, signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=2,
            nonce="nonce-neg-bad")
        raw = bytearray(__import__("base64").b64decode(signature))
        # A assinatura é blindada em ASCII; alterar o newline final pode ser
        # tolerado. Corrompe um byte do corpo, que o ssh-keygen rejeita.
        raw[len(raw) // 2] ^= 1
        negatives["bad_signature"] = {
            "statement": statement,
            "signature": __import__("base64").b64encode(bytes(raw)).decode(),
        }
        statement, signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=2,
            nonce="nonce-neg-expired",
            issued_at=now - timedelta(minutes=20),
            expires_at=now - timedelta(minutes=10))
        negatives["expired"] = {"statement": statement, "signature": signature}
        statement, signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=2,
            nonce="nonce-neg-digest", trust_policy_sha256="f" * 64)
        negatives["digest_mismatch"] = {
            "statement": statement, "signature": signature}
        statement, signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=2,
            nonce="nonce-neg-unknown", approver_id="intruder")
        negatives["unknown_approver"] = {
            "statement": statement, "signature": signature}
        valid_statement, valid_signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=2,
            nonce=valid_nonce, reason="rollback cross-UID")
        nonce_statement, nonce_signature = common.recovery_statement(
            now, self.signer, self.allowlist, new_epoch=3,
            nonce=valid_nonce, reason="tentativa de reutilizar nonce")
        return {
            "negatives": negatives,
            "valid": {"statement": valid_statement,
                      "signature": valid_signature},
            "same_statement": {"statement": valid_statement,
                               "signature": valid_signature},
            "nonce_reuse": {"statement": nonce_statement,
                            "signature": nonce_signature},
        }

    def run_rollback_gates(self) -> None:
        self.stage("G3/G5 — rollback, quarentena, recuperação assinada e nonce")
        layout = self.new_layout("rollback")
        self.prepare_trust(layout)
        assert self.signer is not None and self.allowlist is not None
        artifacts = common.build_artifacts(
            self.now, self.signer, self.allowlist, tag="rollback")
        job = common.client_job(layout.plan, artifacts)
        common.validate_client_job(job)
        common.write_json(layout.plan.client_job_path, job)
        self.write_config(layout)
        self.start_service(layout, label="rollback-initial")
        prepared = self.run_client(
            layout, layout.plan.client_job_path, "prepare")
        prepared_value = prepared.get("json") if "json" in prepared else prepared
        self.check("G3", "prepare cross-UID antes do backup",
                   prepared_value.get("ok"), prepared_value)
        self.stop_service()

        backup_ledger = os.path.join(layout.plan.root, "backup", "ledger.db")
        backup_service = os.path.join(
            layout.plan.root, "backup", "service.db")
        self.backup_db(
            os.path.join(layout.plan.state_dir, "ledger.db"), backup_ledger)
        self.backup_db(
            os.path.join(layout.plan.state_dir, "service.db"), backup_service)

        self.start_service(layout, label="rollback-reserve")
        reserved = self.run_client(
            layout, layout.plan.client_job_path, "reserve")
        reserved_value = reserved.get("json") if "json" in reserved else reserved
        lease = reserved_value.get("lease_id")
        self.check("G3", "reserva real antes do rollback",
                   reserved_value.get("ok") and isinstance(lease, str),
                   reserved_value)
        asserted = self.run_client(
            layout, layout.plan.client_job_path, "assert",
            extra=("--lease", str(lease), "--request-id",
                   job["request_ids"]["assert"]))
        asserted_value = asserted.get("json") if "json" in asserted else asserted
        self.check("G3", "assert real antes do rollback",
                   asserted_value.get("ok"), asserted_value)
        self.stop_service()

        self.restore_db(backup_ledger, os.path.join(
            layout.plan.state_dir, "ledger.db"),
            common.SERVICE_UID, common.SERVICE_GID)
        self.restore_db(backup_service, os.path.join(
            layout.plan.state_dir, "service.db"),
            common.SERVICE_UID, common.SERVICE_GID)
        self.start_service(layout, label="rollback-quarantine")
        status = self.run_client(layout, layout.plan.client_job_path, "status")
        status_value = status.get("json") if "json" in status else status
        status_result = (status_value.get("response", {}) or {}).get("result", {})
        self.check("G3", "rollback restaurado entra em quarentena",
                   status_result.get("quarantined") is True,
                   status_result)

        payloads = self._recovery_payloads(
            layout, artifacts, valid_nonce="nonce-recovery-rollback")
        negative_job = dict(job)
        negative_job["recoveries"] = payloads["negatives"]
        common.write_json(
            os.path.join(layout.plan.artifacts_dir, "negative-job.json"),
            negative_job)
        negative = self.run_client(
            layout, os.path.join(layout.plan.artifacts_dir, "negative-job.json"),
            "recover-negative")
        negative_value = negative.get("json") if "json" in negative \
            else negative
        self.check("G5", "recuperação rejeita assinatura inválida",
                   (negative_value.get("results", {}).get(
                       "bad_signature", {}).get("code")) == "UNAUTHENTICATED_PEER",
                   negative_value.get("results", {}).get("bad_signature"))
        self.check("G5", "recuperação rejeita declaração expirada",
                   (negative_value.get("results", {}).get(
                       "expired", {}).get("code")) == "UNAUTHENTICATED_PEER",
                   negative_value.get("results", {}).get("expired"))
        self.check("G5", "recuperação rejeita digest divergente",
                   (negative_value.get("results", {}).get(
                       "digest_mismatch", {}).get("code")) == "UNAUTHENTICATED_PEER",
                   negative_value.get("results", {}).get("digest_mismatch"))
        self.check("G5", "recuperação rejeita aprovador desconhecido",
                   (negative_value.get("results", {}).get(
                       "unknown_approver", {}).get("code")) == "UNAUTHENTICATED_PEER",
                   negative_value.get("results", {}).get("unknown_approver"))
        neg_status = negative_value.get("status", {})
        self.check("G5", "quarentena permanece após tentativas negativas",
                   ((neg_status.get("result") or {}).get("quarantined")) is True,
                   neg_status)

        recovery_job = dict(job)
        recovery_job["recoveries"] = payloads
        common.write_json(
            os.path.join(layout.plan.artifacts_dir, "recovery-job.json"),
            recovery_job)
        recovery_path = os.path.join(
            layout.plan.artifacts_dir, "recovery-job.json")
        valid = self.run_client(
            layout, recovery_path, "recover", extra=("--recovery", "valid"))
        valid_value = valid.get("json") if "json" in valid else valid
        self.check("G3", "recuperação assinada abre epoch nova",
                   valid_value.get("ok")
                   and (valid_value.get("response", {}).get("result") or {}
                        ).get("epoch") == 2,
                   valid_value)

        old_assert = self.run_client(
            layout, layout.plan.client_job_path, "assert-old",
            extra=("--lease", str(lease), "--request-id",
                   job["request_ids"]["assert"]))
        old_assert_value = old_assert.get("json") if "json" in old_assert \
            else old_assert
        self.check("G3", "lease antigo não é reutilizado após recuperação",
                   old_assert_value.get("code") == "REPLAY_STALE",
                   old_assert_value)
        old_reserve = self.run_client(
            layout, layout.plan.client_job_path, "reserve-old",
            extra=("--request-id", job["request_ids"]["reserve"]))
        old_reserve_value = old_reserve.get("json") if "json" in old_reserve \
            else old_reserve
        self.check("G3", "request_id/reserva antiga não é reutilizado",
                   old_reserve_value.get("code") == "REPLAY_STALE",
                   old_reserve_value)
        after = self.run_client(layout, layout.plan.client_job_path, "status")
        after_value = after.get("json") if "json" in after else after
        after_result = (after_value.get("response", {}) or {}).get("result", {})
        self.check("G3", "status pós-recuperação reflete epoch atual",
                   after_result.get("epoch") == 2
                   and after_result.get("quarantined") is False,
                   after_result)

        same = self.run_client(
            layout, recovery_path, "recover",
            extra=("--recovery", "same_statement"))
        same_value = same.get("json") if "json" in same else same
        self.check("G5", "epoch obsoleta/declaração de recuperação repetida",
                   same_value.get("code") == "CONFLICT", same_value)
        nonce = self.run_client(
            layout, recovery_path, "recover",
            extra=("--recovery", "nonce_reuse"))
        nonce_value = nonce.get("json") if "json" in nonce else nonce
        self.check("G3", "nonce de recuperação não é reutilizado em epoch futura",
                   nonce_value.get("code") == "CONFLICT", nonce_value)
        self.stop_service()

    # ---------- G4 ----------

    def _run_crash_case(self, point: str) -> None:
        self.stage(f"G4 — crash real em {point}")
        layout = self.new_layout("crash-" + point)
        self.prepare_trust(layout)
        assert self.signer is not None and self.allowlist is not None
        artifacts = common.build_artifacts(
            self.now, self.signer, self.allowlist, tag="crash-" + point)
        job = common.client_job(layout.plan, artifacts)
        common.validate_client_job(job)
        common.write_json(layout.plan.client_job_path, job)
        self.write_config(layout)
        self.start_service(layout, label="crash-setup-" + point)
        prepared = self.run_client(
            layout, layout.plan.client_job_path, "prepare")
        prepared_value = prepared.get("json") if "json" in prepared else prepared
        if not prepared_value.get("ok"):
            self.check("G4", f"prepare {point}", False, prepared_value)
            self.stop_service()
            return
        self.stop_service()

        sp = self.start_service(
            layout, crash_point=point, label="crash-" + point)
        triggered = self.run_client(
            layout, layout.plan.client_job_path, "reserve", timeout=15.0)
        try:
            sp.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            sp.proc.kill()
            sp.proc.wait(timeout=5)
        crash_rc = sp.proc.returncode
        ledger_wal = os.path.join(layout.plan.state_dir, "ledger.db-wal")
        service_wal = os.path.join(layout.plan.state_dir, "service.db-wal")
        wal = {
            "ledger_wal_bytes": os.path.getsize(ledger_wal)
            if os.path.exists(ledger_wal) else 0,
            "service_wal_bytes": os.path.getsize(service_wal)
            if os.path.exists(service_wal) else 0,
        }
        self.check("G4", f"kill real no limite {point} (rc=137)",
                   crash_rc == 137, {"rc": crash_rc,
                                     "client": triggered.get("error")})
        self.service = None
        self._collect_logs(sp)
        self.start_service(layout, label="crash-restart-" + point)
        status = self.run_client(layout, layout.plan.client_job_path, "status")
        status_value = status.get("json") if "json" in status else status
        status_result = (status_value.get("response", {}) or {}).get("result", {})
        if point == "after_begin":
            retry = self.run_client(
                layout, layout.plan.client_job_path, "reserve-old",
                extra=("--request-id", job["request_ids"]["reserve"]))
            retry_value = retry.get("json") if "json" in retry else retry
            self.check("G4", "crash antes do ledger = OUTCOME_UNKNOWN",
                       retry_value.get("code") == "OUTCOME_UNKNOWN",
                       retry_value)
            self.check("G4", "WAL do service state presente no crash",
                       wal["service_wal_bytes"] > 0, wal)
        elif point in {"after_dispatch", "after_finish"}:
            self.check("G4", f"crash {point} entra em quarentena",
                       status_result.get("quarantined") is True,
                       status_result)
            retry = self.run_client(
                layout, layout.plan.client_job_path, "reserve-old",
                extra=("--request-id", job["request_ids"]["reserve"]))
            retry_value = retry.get("json") if "json" in retry else retry
            self.check("G4", f"reserva incerta em {point} não é liberada",
                       retry_value.get("code") == "REPLAY_STALE",
                       retry_value)
            self.check("G4", "WAL do ledger/service presente",
                       wal["ledger_wal_bytes"] > 0 or wal["service_wal_bytes"] > 0,
                       wal)
            self.check("G4", "status preserva DISPATCHING/reserva",
                       status_result.get("state") == "DISPATCHING"
                       and status_result.get("reserved") is True,
                       status_result)
        else:
            retry = self.run_client(
                layout, layout.plan.client_job_path, "reserve-old",
                extra=("--request-id", job["request_ids"]["reserve"]))
            retry_value = retry.get("json") if "json" in retry else retry
            response = retry_value.get("response", {}) if isinstance(
                retry_value, dict) else {}
            self.check("G4", "crash após selar âncora permite replay idempotente",
                       response.get("ok") is True
                       and response.get("replayed") is True,
                       response)
            lease = (response.get("result") or {}).get("lease_id")
            asserted = self.run_client(
                layout, layout.plan.client_job_path, "assert",
                extra=("--lease", str(lease), "--request-id",
                       job["request_ids"]["assert"] + "-after-crash"))
            asserted_value = asserted.get("json") if "json" in asserted \
                else asserted
            self.check("G4", "lease recuperado do WAL ainda é válido",
                       asserted_value.get("ok"), asserted_value)
            self.check("G4", "âncora selada no crash pós-âncora",
                       status_result.get("quarantined") is False, status_result)
        self.stop_service()

    def run_crash_gates(self) -> None:
        for point in ("after_begin", "after_dispatch", "after_finish",
                      "after_seal"):
            self._run_crash_case(point)

    # ---------- G7 ----------

    def run_limits_gates(self) -> None:
        self.stage("G7 — concorrência, saturação, deadline e frame")
        layout = self.new_layout("limits")
        self.prepare_trust(layout)
        assert self.signer is not None and self.allowlist is not None
        artifacts = common.build_artifacts(
            self.now, self.signer, self.allowlist, tag="limits")
        job = common.client_job(layout.plan, artifacts)
        common.validate_client_job(job)
        common.write_json(layout.plan.client_job_path, job)
        mapping = common.config_mapping(
            layout.plan, max_workers=2, request_timeout_seconds=2.0,
            max_frame_bytes=4096)
        self.write_config(layout, mapping)
        self.start_service(layout, label="limits")
        prepared = self.run_client(
            layout, layout.plan.client_job_path, "prepare")
        prepared_value = prepared.get("json") if "json" in prepared else prepared
        self.check("G7", "prepare com limites reduzidos",
                   prepared_value.get("ok"), prepared_value)

        concurrent = self.run_client(
            layout, layout.plan.client_job_path, "concurrent",
            extra=("--count", "2"))
        concurrent_value = concurrent.get("json") if "json" in concurrent \
            else concurrent
        self.check("G7", "2 requisições concorrentes com 2 workers",
                   concurrent_value.get("ok"), concurrent_value)

        ready1 = os.path.join(layout.plan.relay_dir, "hold-1.ready")
        ready2 = os.path.join(layout.plan.relay_dir, "hold-2.ready")
        h1, h1_out, h1_err = self.start_client(
            layout, layout.plan.client_job_path, "hold",
            extra=("--seconds", "2.5", "--ready-path", ready1),
            label="hold-1")
        h2, h2_out, h2_err = self.start_client(
            layout, layout.plan.client_job_path, "hold",
            extra=("--seconds", "2.5", "--ready-path", ready2),
            label="hold-2")
        self.wait_for_path(ready1)
        self.wait_for_path(ready2)
        probe = self.run_client(
            layout, layout.plan.client_job_path, "saturation-probe",
            timeout=5.0)
        probe_value = probe.get("json") if "json" in probe else probe
        self.check("G7", "saturação de slots recusa conexão excedente",
                   probe_value.get("welcome_received") is False,
                   probe_value)
        h1_value = self.wait_client(h1, h1_out, h1_err)
        h2_value = self.wait_client(h2, h2_out, h2_err)
        self.check("G7", "deadline fecha conexões lentas",
                   h1_value.get("closed") and h2_value.get("closed"),
                   {"hold1": h1_value, "hold2": h2_value})
        after = self.run_client(layout, layout.plan.client_job_path, "status")
        after_value = after.get("json") if "json" in after else after
        self.check("G7", "slots liberados após deadline", after_value.get("ok"),
                   after_value)

        frame = self.run_client(
            layout, layout.plan.client_job_path, "frame",
            extra=("--size", "8192"))
        frame_value = frame.get("json") if "json" in frame else frame
        self.check("G7", "frame acima de max_frame_bytes é recusado",
                   frame_value.get("closed_without_response"), frame_value)
        deadline = self.run_client(
            layout, layout.plan.client_job_path, "deadline",
            extra=("--seconds", "3.0"), timeout=10.0)
        deadline_value = deadline.get("json") if "json" in deadline else deadline
        self.check("G7", "deadline total do request fecha cliente lento",
                   deadline_value.get("closed"), deadline_value)
        self.stop_service()

    # ---------- output ----------

    def write_output(self, error: Optional[str] = None) -> None:
        deadline_left = max(0.0, self.deadline.left())
        blocked = [check.to_mapping() for check in self.checks if check.blocked]
        summary = {
            "schema": common.REPORT_SCHEMA,
            "generated_at": common.iso(datetime.now(timezone.utc)),
            "repo_head": self._repo_head(),
            "base": "main cc7b4522 (#83 fa89ebb2)",
            "workflow": ".github/workflows/coord-python.yml",
            "root_execution": os.geteuid() == 0,
            "network_namespace": "external unshare -n (harness)",
            "service_uid": common.SERVICE_UID,
            "service_gid": common.SERVICE_GID,
            "consumer_uid": common.CONSUMER_UID,
            "consumer_gid": common.CONSUMER_GID,
            "observer_uid": common.OBSERVER_UID,
            "unregistered_uid": common.UNREGISTERED_UID,
            "wrong_gid": common.WRONG_GID,
            "deadline_seconds": self.deadline_seconds,
            "deadline_remaining_seconds": round(deadline_left, 3),
            "checks": [check.to_mapping() for check in self.checks],
            "blocked": blocked,
            "result": ("BLOCKED" if blocked else
                       "PASS" if self.checks and all(
                           check.passed and not check.blocked
                           for check in self.checks) else "FAIL"),
            "error": error,
            "sanitization": (
                "paths/private-key blocks redigidos; SHAs, UIDs/GIDs e "
                "fingerprints sintéticos preservados para rastreabilidade"),
        }
        paths = tuple(self.paths + [self.repo])
        summary = common.sanitize_value(summary, paths)
        common.write_json(os.path.join(self.output, "summary.json"), summary)
        lines = [
            f"COORD-TRUST-BOUNDARY/P4 — {summary['result']}",
            f"HEAD: {summary['repo_head']}",
            f"UIDs: service={common.SERVICE_UID}:{common.SERVICE_GID} "
            f"consumer={common.CONSUMER_UID}:{common.CONSUMER_GID} "
            f"observer={common.OBSERVER_UID} "
            f"unregistered={common.UNREGISTERED_UID}",
            f"deadline remaining: {summary['deadline_remaining_seconds']}s",
        ]
        for check in self.checks:
            lines.append(
                f"{'PASS' if check.passed else 'FAIL'} [{check.gate}] "
                f"{check.name}: {check.detail}")
        if error:
            lines.append("ERROR: " + error)
        text = common.sanitize_text(
            "\n".join(lines) + "\n", tuple(self.paths + [self.repo]))
        with open(os.path.join(self.output, "crossuid.log"), "w",
                  encoding="utf-8") as handle:
            handle.write(text)
        if self.service_logs:
            logs = common.sanitize_text(
                "\n".join(self.service_logs.values()),
                tuple(self.paths + [self.repo]))
            with open(os.path.join(self.output, "service-logs.log"), "w",
                      encoding="utf-8") as handle:
                handle.write(logs)
        print("\n" + text, flush=True)

    def _repo_head(self) -> str:
        try:
            result = subprocess.run(
                ["git", "-C", self.repo, "rev-parse", "HEAD"],
                capture_output=True, text=True, timeout=5)
            if result.returncode == 0:
                return result.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            pass
        return os.environ.get("GITHUB_SHA", "UNKNOWN")

    def _terminate_process_groups(self) -> None:
        own = os.getpgrp()
        for pgid in sorted(self.process_groups):
            if pgid == own:
                continue
            try:
                os.killpg(pgid, signal.SIGTERM)
            except OSError:
                continue
        time.sleep(0.1)
        for pgid in sorted(self.process_groups):
            if pgid == own:
                continue
            try:
                os.killpg(pgid, signal.SIGKILL)
            except OSError:
                pass

    def cleanup(self) -> None:
        self.stop_service()
        self._terminate_process_groups()
        if self.keep:
            return
        for layout in self.layouts:
            shutil.rmtree(layout.plan.root, ignore_errors=True)
        runtime = os.path.join(self.work_root, "runtime")
        if os.path.isdir(runtime):
            os.chmod(runtime, 0o700)
        shutil.rmtree(self.work_root, ignore_errors=True)
        # Mantém self.cleanup_manifest no OUT até a etapa always() do workflow.

    def run(self) -> int:
        error = None
        try:
            self.stage("G0 — pré-condições e inventário")
            if os.geteuid() != 0:
                raise HarnessError("P4-BLOCKED: root descartável obrigatório")
            for tool, path in (("setpriv", SETPRIV), ("unshare", UNSHARE),
                               ("ssh-keygen", shutil.which("ssh-keygen"))):
                self.check("G0", "ferramenta disponível: " + tool,
                           bool(path and os.path.exists(path)), path)
            self.check(
                "G0", "setpriv suporta no-new-privs",
                self.setpriv_has_no_new_privs,
                {"no_new_privs": self.setpriv_has_no_new_privs,
                 "ambient_optional": self.setpriv_has_ambient})
            self.prepare_runtime_bundle()
            if not self.verify_runtime_bundle():
                raise HarnessError("runtime bundle sem verificação de integridade")
            self.before_hashes = self._hash_files(
                ("/etc/passwd", "/etc/group", "/etc/shadow"))
            def _uid_exists(uid: int) -> bool:
                try:
                    pwd.getpwuid(uid)
                    return True
                except KeyError:
                    return False

            self.check(
                "G0", "sem usuários persistentes; UIDs numéricos efêmeros",
                not _uid_exists(common.SERVICE_UID),
                {"service_uid": common.SERVICE_UID,
                 "service_uid_exists": _uid_exists(common.SERVICE_UID),
                 "consumer_uid": common.CONSUMER_UID,
                 "consumer_uid_exists": _uid_exists(common.CONSUMER_UID)})
            self.signer = p14b.SyntheticSigner(self.signer_dir, persist=True)
            self.allowlist = cas.ApproverAllowlist(
                (self.signer.approver_key(self.now),))
            self.check("G0", "chave sintética exclusiva gerada",
                       bool(self.signer.fingerprint), {
                           "approver": self.signer.approver_id,
                           "fingerprint": self.signer.fingerprint})

            self.run_main_gates()
            self.run_rollback_gates()
            self.run_crash_gates()
            self.run_limits_gates()
            self.check_passwd_unchanged()
            self.stage("resultado")
        except Exception as exc:  # noqa: BLE001 - falha é evidência
            paths = tuple(self.paths + [self.repo])
            error = common.sanitize_text(
                type(exc).__name__ + ": " + str(exc), paths)
            trace = common.sanitize_text(traceback.format_exc()[-4000:], paths)
            self.check("X", "harness concluiu sem exceção", False, trace)
            print(trace, file=sys.stderr, flush=True)
        finally:
            self.stop_service()
            self.write_output(error)
            self.cleanup()
        return 0 if (not error
                     and not any(check.blocked for check in self.checks)
                     and self.checks
                     and all(check.passed for check in self.checks)) else 1


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="COORD trust cross-UID disposable certification")
    parser.add_argument("--repo", default=os.getcwd())
    parser.add_argument("--output", required=True)
    parser.add_argument("--deadline-seconds", type=float, default=480.0)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--work-root", default=None)
    args = parser.parse_args(argv)
    harness = Harness(
        repo=args.repo, output=args.output,
        deadline_seconds=args.deadline_seconds, keep=args.keep,
        run_id=args.run_id, work_root=args.work_root)
    return harness.run()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001 - último anteparo de sanitização
        print(common.sanitize_text(
            traceback.format_exc(), (os.getcwd(), "/tmp/coord-trust-p4-")),
            file=sys.stderr, flush=True)
        sys.exit(1)
