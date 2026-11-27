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


_INET_FAMILIES = frozenset({
    getattr(socket, "AF_INET", -1),
    getattr(socket, "AF_INET6", -1),
})
_ORIGINAL_CONNECT = socket.socket.connect
_ORIGINAL_SENDTO = socket.socket.sendto
_ORIGINAL_SENDMSG = getattr(socket.socket, "sendmsg", None)


def _connect(self, address):
    # Serviços locais da coordenação usam AF_UNIX; TCP/UDP continuam proibidos.
    if self.family in _INET_FAMILIES:
        raise NetworkDisabled("rede bloqueada pelo CI Python host-only")
    return _ORIGINAL_CONNECT(self, address)


def _sendto(self, data, *args, **kwargs):
    if self.family in _INET_FAMILIES:
        raise NetworkDisabled("rede bloqueada pelo CI Python host-only")
    return _ORIGINAL_SENDTO(self, data, *args, **kwargs)


socket.socket.connect = _connect  # type: ignore[assignment]
socket.socket.sendto = _sendto  # type: ignore[assignment]
if _ORIGINAL_SENDMSG is not None:
    def _sendmsg(self, *args, **kwargs):
        if self.family in _INET_FAMILIES:
            raise NetworkDisabled("rede bloqueada pelo CI Python host-only")
        return _ORIGINAL_SENDMSG(self, *args, **kwargs)
    socket.socket.sendmsg = _sendmsg  # type: ignore[assignment]
socket.create_connection = _denied  # type: ignore[assignment]
socket.getaddrinfo = _denied  # type: ignore[assignment]
if hasattr(socket, "gethostbyname"):
    socket.gethostbyname = _denied  # type: ignore[assignment]
