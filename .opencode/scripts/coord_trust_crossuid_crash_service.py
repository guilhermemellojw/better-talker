#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P4 — serviço com ponto de crash determinístico.

Executa o serviço real (mesmo build_service/LocalService) sob o UID do serviço,
mas encerra o processo com `os._exit(137)` no limite solicitado. O crash é real:
SIGKILL-equivalente no processo, sem limpeza de SQLite/WAL/âncora.

Pontos:
- ``after_begin``: depois de registrar service_requests 'in_progress';
- ``after_dispatch``: depois do commit do ledger, antes de finish_request;
- ``after_finish``: depois de finish_request, antes de audit/âncora;
- ``after_seal``: depois de selar a âncora, antes da resposta ao cliente.

Este wrapper é CI-only; não é instalado nem usado pelo serviço normal.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import sqlite3
import sys
import time

import coord_trust_service as svc

CRASH_POINTS = ("after_begin", "after_dispatch", "after_finish", "after_seal")


class _CrashConnection:
    """Proxy que mata o processo imediatamente após o COMMIT do limite.

    O `os._exit` ocorre antes do `conn.close()`, portanto o WAL do SQLite
    permanece em disco; o restart é quem faz o replay. É um crash real no
    processo, não uma simulação de reinício.
    """

    def __init__(self, connection: sqlite3.Connection, counter: dict[str, int],
                 target_commit: int) -> None:
        self._connection = connection
        self._counter = counter
        self._target = target_commit

    def execute(self, sql: str, *args, **kwargs):
        cursor = self._connection.execute(sql, *args, **kwargs)
        if str(sql).strip().upper() == "COMMIT":
            self._counter["n"] += 1
            if self._counter["n"] == self._target:
                os._exit(137)
        return cursor

    def __getattr__(self, name: str):
        return getattr(self._connection, name)


def _load_config(path: str) -> svc.ServiceConfig:
    svc._require_config_file(path)
    with open(path, encoding="utf-8") as handle:
        value = json.load(handle)
    value.pop("schema", None)
    return svc.ServiceConfig(**value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="COORD trust crash service")
    parser.add_argument("--config", required=True)
    parser.add_argument("--crash-point", required=True, choices=CRASH_POINTS)
    args = parser.parse_args(argv)
    config = _load_config(args.config)
    service = svc.build_service(config)
    point = args.crash_point
    # Mantém o WAL em disco após o crash: sem uma conexão viva, o último close
    # do SQLite faria checkpoint e removeria o WAL antes do os._exit.
    wal_keeper = []
    for path in (config.ledger_path, config.service_db_path):
        keeper = sqlite3.connect(path)
        keeper.execute("SELECT 1").fetchone()
        wal_keeper.append(keeper)

    if point in {"after_begin", "after_finish"}:
        original_state_connect = service.state._connect
        state_counter = {"n": 0}
        target_commit = 1 if point == "after_begin" else 2

        def state_connect():
            return _CrashConnection(
                original_state_connect(), state_counter, target_commit)

        service.state._connect = state_connect
    elif point == "after_dispatch":
        original_ledger_connect = service.ledger._connect
        ledger_counter = {"n": 0}

        def ledger_connect():
            return _CrashConnection(
                original_ledger_connect(), ledger_counter, 1)

        service.ledger._connect = ledger_connect
    else:
        original_seal = service._seal_anchor

        def seal_anchor(*call_args, **call_kwargs):
            result = original_seal(*call_args, **call_kwargs)
            os._exit(137)
            return result

        service._seal_anchor = seal_anchor

    server = svc.LocalService(service)
    stop = False

    def _stop(_signum, _frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)
    server.start()
    print(f"CRASH-SERVICE-READY transport=disabled point={point}", flush=True)
    try:
        while not stop:
            time.sleep(0.2)
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
