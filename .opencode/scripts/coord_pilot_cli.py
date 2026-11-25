#!/usr/bin/env python3
"""CLI mínima do piloto COORD-04/P3A.1.

A execução real é bloqueada por padrão. Nenhum comando desta CLI executa um
POST real nesta missão; `execute` apenas relata o bloqueio e exige uma decisão
humana separada para qualquer ativação futura.
"""
from __future__ import annotations

import os
import sys
from typing import Any

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_pilot as pilot


COMMANDS = ("preflight", "dry-run", "prepare", "execute", "reconcile", "closeout")


def _parse(argv: list[str]) -> tuple[str, dict[str, Any]]:
    options: dict[str, Any] = {"store_dir": pilot.DEFAULT_PILOT_DIR, "blocked": False}
    command = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--store-dir":
            index += 1
            if index >= len(argv):
                raise ValueError("--store-dir sem valor")
            options["store_dir"] = argv[index]
        elif arg == "--blocked":
            options["blocked"] = True
        elif arg in COMMANDS and command is None:
            command = arg
        else:
            raise ValueError(f"argumento não reconhecido: {arg}")
        index += 1
    if command is None:
        raise ValueError("comando ausente")
    return command, options


def run(argv: list[str]) -> dict[str, Any]:
    """Executa uma operação de controle; nunca faz POST real."""
    command, options = _parse(argv)
    store = pilot.PilotOperationStore(options["store_dir"])

    if command == "preflight":
        record = store.load()
        return {"command": command, "read_only": True,
                "record_state": record.state if record else None,
                "writes": 0, "notice": "preflight não altera estado"}

    if command == "dry-run":
        return {"command": command, "read_only": True, "http_calls": 0,
                "executed": False, "notice": "simulação offline; nenhum cliente HTTP criado"}

    if command == "prepare":
        record = store.load()
        return {"command": command, "read_only": True,
                "record_present": record is not None,
                "notice": "preparação exige aprovação humana explícita fora desta CLI"}

    if command == "execute":
        # Caminho de escrita permanece bloqueado: nenhuma autorização real é
        # criada, nenhum cliente é construído e nenhum POST é emitido.
        return {"command": command, "executed": False,
                "state": "BLOCKED_BY_DEFAULT", "http_calls": 0,
                "reason": "execução real depende de aprovação explícita e ativação separada"}

    if command == "reconcile":
        record = store.load()
        state = record.state if record else None
        return {"command": command, "read_only": True, "state": state,
                "changed": False, "http_calls": 0,
                "notice": "reconciliação exige evidência positiva; sem reenvio automático"}

    if command == "closeout":
        record = store.load()
        return {"command": command, "read_only": True,
                "final_state": record.state if record else None,
                "notice": "encerramento não altera registros terminais"}
    raise ValueError(f"comando não suportado: {command}")


def main(argv: list[str] | None = None) -> int:
    import json
    try:
        result = run(list(sys.argv[1:] if argv is None else argv))
    except ValueError as e:
        print(f"erro: {e}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
