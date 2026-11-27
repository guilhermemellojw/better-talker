"""Guarda de rede host-only do CI Python da coordenação.

Importada automaticamente via PYTHONPATH. Nenhuma conexão de rede é
permitida nos processos de teste, inclusive subprocessos da CLI.
"""
from __future__ import annotations

import socket


class NetworkDisabled(OSError):
    pass


def _denied(*args, **kwargs):
    raise NetworkDisabled("rede bloqueada pelo CI Python host-only")


socket.socket.connect = _denied  # type: ignore[assignment]
socket.create_connection = _denied  # type: ignore[assignment]
