#!/usr/bin/env python3
"""Probe de isolamento de rede do CI Python da coordenação.

Modos:
  --mode os     executado dentro de `unshare -rn`: prova TCP, UDP, DNS e
                subprocesso externo bloqueados pelo namespace.
  --mode guard  executado com `PYTHONPATH=.../ci_netguard`: prova a defesa
                Python adicional (connect, getaddrinfo, sendto, sendmsg e
                subprocesso Python).

O probe falha com exit 1 se qualquer canal estiver alcançável. Nenhuma
credencial, LLM ou transporte real é usado.
"""
from __future__ import annotations

import argparse
import os
import socket
import subprocess
import sys

TCP_TARGET = ("1.1.1.1", 443)
UDP_TARGET = ("1.1.1.1", 53)
DNS_NAME = "example.invalid"
CHILD_TCP = (
    "import socket, sys\n"
    "try:\n"
    "    socket.create_connection(('1.1.1.1', 443), timeout=2).close()\n"
    "except OSError:\n"
    "    sys.exit(1)\n"
    "sys.exit(0)\n"
)
CHILD_DNS = (
    "import socket, sys\n"
    "try:\n"
    "    socket.getaddrinfo('example.invalid', 80, type=socket.SOCK_STREAM)\n"
    "except OSError:\n"
    "    sys.exit(1)\n"
    "sys.exit(0)\n"
)


def _fail(label: str, detail: str) -> bool:
    print(f"REACHABLE {label}: {detail}")
    return False


def _blocked(label: str, exc: BaseException) -> bool:
    print(f"BLOCKED {label}: {type(exc).__name__}: {exc}")
    return True


def probe_tcp() -> bool:
    try:
        with socket.create_connection(TCP_TARGET, timeout=2):
            pass
    except OSError as exc:
        return _blocked("tcp", exc)
    return _fail("tcp", "conexão estabelecida")


def probe_udp() -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.settimeout(2)
        sock.sendto(b"probe", UDP_TARGET)
    except OSError as exc:
        return _blocked("udp", exc)
    finally:
        sock.close()
    return _fail("udp", "datagrama enviado")


def probe_dns() -> bool:
    try:
        socket.getaddrinfo(DNS_NAME, 80, type=socket.SOCK_STREAM)
    except OSError as exc:
        return _blocked("dns", exc)
    return _fail("dns", "resolução retornou endereço")


def probe_child(label: str, code: str) -> bool:
    env = dict(os.environ)
    # No modo OS não deve haver guarda Python: o namespace é a barreira.
    if os.environ.get("COORD_NETPROBE_MODE") == "os":
        env.pop("PYTHONPATH", None)
    try:
        result = subprocess.run(
            [sys.executable, "-c", code], env=env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return _blocked(label, exc)
    if result.returncode != 0:
        return _blocked(label, OSError(f"exit={result.returncode}"))
    return _fail(label, "subprocesso alcançou a rede")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("os", "guard"), required=True)
    args = parser.parse_args(argv)
    os.environ["COORD_NETPROBE_MODE"] = args.mode
    checks = [
        probe_tcp(),
        probe_udp(),
        probe_dns(),
        probe_child("subprocess-tcp", CHILD_TCP),
        probe_child("subprocess-dns", CHILD_DNS),
    ]
    if all(checks):
        print(f"NETWORK-{args.mode.upper()}-BLOCKED")
        return 0
    print(f"NETWORK-{args.mode.upper()}-REACHABLE")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
