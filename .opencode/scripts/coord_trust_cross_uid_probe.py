#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P3 — probe cross-UID de SCM_CREDENTIALS (CI only).

Não cria usuários nem altera o host. Em ambiente descartável com sudo/setpriv,
executa um cliente como UID 65534 (nobody) contra um socket AF_UNIX
SOCK_SEQPACKET 0666 em diretório temporário e confirma que o kernel entrega
UID/GID distintos no SCM_CREDENTIALS. Sem privilégio, imprime
CROSS-UID-BLOCKED e sai 0.

Este probe valida o mecanismo de kernel usado pelo serviço; a certificação
completa da unidade/socket/registry é o procedimento do runbook.
"""
from __future__ import annotations

import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile

CHILD_CODE = r"""
import socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
s.connect(sys.argv[1])
s.send(b"cross-uid-scm")
s.close()
"""


def main() -> int:
    if not shutil.which("sudo") or not shutil.which("setpriv"):
        print("CROSS-UID-BLOCKED sudo/setpriv ausente")
        return 0
    root = tempfile.mkdtemp(prefix="coord-cross-uid-")
    path = os.path.join(root, "probe.sock")
    server = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    try:
        os.chmod(root, 0o777)
        server.bind(path)
        os.chmod(path, 0o666)
        server.listen(1)
        server.settimeout(10)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
        try:
            process = subprocess.Popen(
                ["sudo", "-n", "setpriv",
                 "--reuid=65534", "--regid=65534", "--clear-groups",
                 sys.executable, "-c", CHILD_CODE, path],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except (OSError, subprocess.SubprocessError) as exc:
            print("CROSS-UID-BLOCKED não foi possível iniciar setpriv:", exc)
            return 0
        try:
            conn, _ = server.accept()
            try:
                data, ancillary, flags, _ = conn.recvmsg(
                    128, socket.CMSG_SPACE(struct.calcsize("3i")))
            finally:
                conn.close()
            process.wait(timeout=10)
        except (OSError, subprocess.SubprocessError) as exc:
            try:
                process.wait(timeout=2)
            except subprocess.SubprocessError:
                process.kill()
            err = process.stderr.read().decode("utf-8", "replace").strip()
            print("CROSS-UID-BLOCKED accept/child:", exc, err[:200])
            return 0
        if process.returncode != 0:
            err = process.stderr.read().decode("utf-8", "replace").strip()
            print("CROSS-UID-BLOCKED child rc=", process.returncode, err[:200])
            return 0
        creds = []
        for level, kind, payload in ancillary:
            if level == socket.SOL_SOCKET and kind == socket.SCM_CREDENTIALS:
                if len(payload) == struct.calcsize("3i"):
                    creds.append(struct.unpack("3i", payload))
        if flags & getattr(socket, "MSG_CTRUNC", 0):
            print("CROSS-UID-FAIL credenciais truncadas")
            return 1
        if len(creds) != 1:
            print("CROSS-UID-FAIL credenciais ausentes/duplicadas")
            return 1
        pid, uid, gid = creds[0]
        if uid != 65534 or gid != 65534 or pid <= 0:
            print(f"CROSS-UID-FAIL uid={uid} gid={gid} pid={pid}")
            return 1
        if data != b"cross-uid-scm":
            print(f"CROSS-UID-FAIL payload={data!r}")
            return 1
        print(f"CROSS-UID-SCM-OK uid={uid} gid={gid}")
        return 0
    finally:
        server.close()
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
        try:
            os.rmdir(root)
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())
