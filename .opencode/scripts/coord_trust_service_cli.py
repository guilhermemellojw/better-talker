#!/usr/bin/env python3
"""Entrada do serviço protegido; somente configuração/health check nesta rodada.

Nunca abre TCP, não instala unidade, não cria usuário e não executa transporte
real. A unidade systemd e o runbook são entregues como preparação.
"""
from __future__ import annotations

import argparse
import json
import signal
import sys
import time

import coord_trust_service as svc


def _load_config(path: str) -> svc.ServiceConfig:
    svc._require_config_file(path)
    try:
        with open(path, encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, ValueError) as exc:
        raise SystemExit("configuração inválida") from exc
    if not isinstance(value, dict):
        raise SystemExit("configuração deve ser objeto")
    value.pop("schema", None)
    try:
        return svc.ServiceConfig(**value)
    except TypeError as exc:
        raise SystemExit("campos de configuração inválidos") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="COORD trust boundary service")
    parser.add_argument("--config", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    config = _load_config(args.config)
    if args.check:
        config.prepare()
        registry = svc.ConsumerRegistry.from_mapping(
            svc._load_private_json(config.consumer_registry_path,
                                   "consumer registry"),
            allow_same_uid=config.allow_same_uid)
        config.validate_consumers(registry)
        svc.TrustStore.load(config.trust_store_path)
        print("CONFIG-OK real_transport=false")
        return 0

    service = svc.build_service(config)
    server = svc.LocalService(service)
    stop = False

    def _handle_signal(_signum, _frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)
    server.start()
    print("SERVICE-READY transport=disabled", flush=True)
    try:
        while not stop:
            time.sleep(0.2)
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
