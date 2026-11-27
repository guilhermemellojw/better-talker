#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P4 — cliente descartável executado como consumidor.

Executa uma jornada real no socket AF_UNIX do serviço sob o UID do consumidor.
Nos modos de fronteira (fd/SCM_RIGHTS, GID divergente, saturação) usa o socket
diretamente; nos modos de autoridade usa `ServiceClient`, que autentica o
servidor por UID/GID/dono/service_id antes de enviar payload.

Nunca lê chave privada, ledger, trust store ou âncora. O job recebido contém
somente material assinado/público e o path do socket.
"""
from __future__ import annotations

import argparse
import array
import json
import os
import socket
import struct
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Mapping, Optional

import coord_trust_crossuid_common as common
import coord_trust_service as svc


def _result(value: Mapping[str, Any]) -> None:
    print(common.canonical_json(dict(value)), flush=True)


def _server_client(job: Mapping[str, Any]) -> svc.ServiceClient:
    return svc.ServiceClient(
        str(job["socket_path"]),
        expected_service_uid=int(job["service_uid"]),
        expected_service_gid=int(job["service_gid"]),
        expected_service_id=str(job["service_id"]),
        timeout=15.0,
    )


def _recv_packet(conn: socket.socket, max_bytes: int = svc.MAX_FRAME_BYTES,
                 timeout: float = 5.0) -> Optional[dict[str, Any]]:
    conn.settimeout(timeout)
    data, ancillary, flags, _ = conn.recvmsg(max_bytes + 4)
    if not data:
        return None
    if flags & getattr(socket, "MSG_TRUNC", 0):
        raise ValueError("frame truncado")
    if len(data) < 4:
        raise ValueError("frame inválido")
    size = struct.unpack(">I", data[:4])[0]
    if size <= 0 or size > max_bytes or len(data) != 4 + size:
        raise ValueError("frame inválido")
    value = json.loads(data[4:].decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("frame deve ser objeto")
    return value


def _packet(value: Mapping[str, Any]) -> bytes:
    raw = common.canonical_json(dict(value)).encode("utf-8")
    return struct.pack(">I", len(raw)) + raw


def _send_packet(conn: socket.socket, value: Mapping[str, Any]) -> None:
    packet = _packet(value)
    sent = conn.send(packet)
    if sent != len(packet):
        raise ConnectionError("frame parcial em SOCK_SEQPACKET")


def _connect_raw(job: Mapping[str, Any], timeout: float = 5.0) -> socket.socket:
    path = str(job["socket_path"])
    info = os.lstat(path)
    if not __import__("stat").S_ISSOCK(info.st_mode):
        raise ConnectionError("socket path não é socket")
    if info.st_uid != int(job["service_uid"]):
        raise ConnectionError("dono do socket divergente")
    if info.st_gid != int(job["service_gid"]):
        raise ConnectionError("grupo do socket divergente")
    if info.st_mode & 0o002:
        raise ConnectionError("socket world-writable")
    conn = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    conn.settimeout(timeout)
    conn.connect(path)
    pid, uid, gid = struct.unpack(
        "3i", conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                              struct.calcsize("3i")))
    if pid <= 0 or uid != int(job["service_uid"]) or gid != int(job["service_gid"]):
        conn.close()
        raise ConnectionError("credenciais do servidor divergentes")
    return conn


def _hello(job: Mapping[str, Any], conn: socket.socket) -> Optional[dict[str, Any]]:
    welcome = _recv_packet(conn)
    _send_packet(conn, {"type": "hello", "protocol": svc.PROTOCOL})
    return welcome


def _call(client: svc.ServiceClient, job: Mapping[str, Any], name: str,
          payload: Mapping[str, Any], *, request_id: Optional[str] = None,
          lease: Optional[str] = None) -> dict[str, Any]:
    body = dict(payload)
    if lease is not None and body.get("lease_id") == "__LEASE__":
        body["lease_id"] = lease
    rid = request_id or (job.get("request_ids", {}) or {}).get(name)
    return client.request(name, body, request_id=rid)


def _error_code(response: Mapping[str, Any]) -> Optional[str]:
    error = response.get("error")
    if isinstance(error, Mapping):
        return str(error.get("code"))
    return None


def prepare(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    results = {
        "propose": _call(client, job, "propose", job["actions"]["propose"]),
        "authorize": _call(
            client, job, "authorize", job["actions"]["authorize"]),
    }
    return {
        "mode": "prepare",
        "ok": all(bool(results[name].get("ok")) for name in results),
        "results": results,
    }


def reserve(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    response = _call(client, job, "reserve", job["actions"]["reserve"])
    return {
        "mode": "reserve",
        "ok": bool(response.get("ok")),
        "response": response,
        "lease_id": (response.get("result") or {}).get("lease_id"),
    }


def assert_current(job: Mapping[str, Any], *, lease_id: str,
                   request_id: str) -> dict[str, Any]:
    client = _server_client(job)
    payload = dict(job["actions"]["assert"])
    payload["lease_id"] = lease_id
    response = client.request(
        "assert", payload,
        request_id=request_id or job["request_ids"]["assert"])
    return {"mode": "assert", "ok": bool(response.get("ok")),
            "response": response}


def record_evidence(job: Mapping[str, Any], *, lease_id: str,
                    request_id: str) -> dict[str, Any]:
    client = _server_client(job)
    payload = dict(job["actions"]["record_evidence"])
    payload["lease_id"] = lease_id
    response = client.request(
        "record_evidence", payload,
        request_id=request_id or job["request_ids"]["record_evidence"])
    return {"mode": "record-evidence", "ok": bool(response.get("ok")),
            "response": response}


def reconcile(job: Mapping[str, Any], *, request_id: str) -> dict[str, Any]:
    client = _server_client(job)
    response = client.request(
        "reconcile", job["actions"]["reconcile"],
        request_id=request_id or job["request_ids"]["reconcile"])
    return {"mode": "reconcile", "ok": bool(response.get("ok")),
            "response": response, "code": _error_code(response)}


def forbidden(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    response = client.request(
        "status", job["actions"]["forbidden"],
        request_id=job["request_ids"]["status"] + "-forbidden")
    return {"mode": "forbidden",
            "ok": _error_code(response) == "PERMISSION_DENIED",
            "response": response, "code": _error_code(response)}


def observer(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    status = client.request(
        "status", {"mission_id": job["mission_id"]},
        request_id="observer-status")
    denied = client.request(
        "propose", job["actions"]["propose"],
        request_id="observer-propose")
    return {
        "mode": "observer",
        "ok": bool(status.get("ok")) and _error_code(denied) == "PERMISSION_DENIED",
        "status": status,
        "denied": denied,
    }


def unregistered(job: Mapping[str, Any]) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        try:
            _send_packet(conn, {
                "request_id": "unregistered-status",
                "action": "status",
                "payload": {"mission_id": job["mission_id"]},
            })
        except (BrokenPipeError, ConnectionError, OSError):
            pass
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "unregistered",
            "ok": response is None,
            "welcome_before_hello": welcome is not None,
            "closed_without_response": response is None,
        }
    finally:
        conn.close()


def auth_wrong(job: Mapping[str, Any], *, field: str) -> dict[str, Any]:
    kwargs = {
        "expected_service_uid": int(job["service_uid"]),
        "expected_service_gid": int(job["service_gid"]),
        "expected_service_id": str(job["service_id"]),
    }
    if field == "service_id":
        kwargs["expected_service_id"] = "wrong-service-id"
    elif field == "uid":
        kwargs["expected_service_uid"] = int(job["service_uid"]) + 1
    elif field == "gid":
        kwargs["expected_service_gid"] = int(job["service_gid"]) + 1
    else:
        raise ValueError("campo de autenticação inválido")
    client = svc.ServiceClient(str(job["socket_path"]), **kwargs, timeout=5.0)
    try:
        client.request("status", {"mission_id": job["mission_id"]})
        blocked = False
        error = None
    except Exception as exc:  # noqa: BLE001 - o resultado é a evidência
        blocked = True
        error = type(exc).__name__ + ": " + str(exc)
    return {"mode": "auth-wrong", "field": field, "ok": blocked,
            "blocked": blocked, "error": error}


def transmit(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    response = client.request(
        "transmit", {}, request_id="transmit-disabled")
    return {
        "mode": "transmit",
        "ok": _error_code(response) == "TRANSPORT_DISABLED",
        "code": _error_code(response),
        "response": response,
    }


def status(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    response = client.request(
        "status", {"mission_id": job["mission_id"]},
        request_id="status-current")
    return {"mode": "status", "ok": bool(response.get("ok")),
            "response": response}


def assert_old(job: Mapping[str, Any], *, lease_id: str,
               request_id: str) -> dict[str, Any]:
    client = _server_client(job)
    payload = dict(job["actions"]["assert"])
    payload["lease_id"] = lease_id
    response = client.request("assert", payload, request_id=request_id)
    return {"mode": "assert-old", "ok": bool(response.get("ok")),
            "code": _error_code(response), "response": response}


def reserve_old(job: Mapping[str, Any], *, request_id: str) -> dict[str, Any]:
    client = _server_client(job)
    response = client.request(
        "reserve", job["actions"]["reserve"], request_id=request_id)
    return {"mode": "reserve-old", "ok": bool(response.get("ok")),
            "code": _error_code(response), "response": response}


def recover(job: Mapping[str, Any], *, name: str) -> dict[str, Any]:
    client = _server_client(job)
    entry = job["recoveries"][name]
    response = client.request(
        "recover", entry, request_id="recover-" + name)
    return {"mode": "recover", "name": name, "ok": bool(response.get("ok")),
            "code": _error_code(response), "response": response}


def recover_negative(job: Mapping[str, Any]) -> dict[str, Any]:
    client = _server_client(job)
    results = {}
    for name, entry in job["recoveries"].items():
        response = client.request(
            "recover", entry, request_id="recover-negative-" + name)
        results[name] = {
            "ok": bool(response.get("ok")),
            "code": _error_code(response),
            "response": response,
        }
    status_response = client.request(
        "status", {"mission_id": job["mission_id"]},
        request_id="status-after-negative-recoveries")
    return {
        "mode": "recover-negative",
        "ok": all(not value["ok"] for value in results.values()),
        "results": results,
        "status": status_response,
    }


def gid_divergence(job: Mapping[str, Any]) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        changed = False
        change_error = None
        try:
            os.setresgid(int(job["remote_gid"]), int(job["remote_gid"]),
                         int(job["remote_gid"]))
            changed = True
        except (AttributeError, OSError) as exc:
            change_error = type(exc).__name__ + ": " + str(exc)
        _send_packet(conn, {
            "request_id": "gid-divergence-status",
            "action": "status",
            "payload": {"mission_id": job["mission_id"]},
        })
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "gid-divergence",
            "ok": changed and response is None,
            "welcome_before_hello": welcome is not None,
            "gid_changed": changed,
            "gid_change_error": change_error,
            "closed_without_response": response is None,
        }
    finally:
        conn.close()


def gid_socket_denied(job: Mapping[str, Any]) -> dict[str, Any]:
    try:
        conn = _connect_raw(job)
    except (ConnectionError, OSError, PermissionError) as exc:
        return {
            "mode": "gid-socket-denied",
            "ok": True,
            "blocked": True,
            "error": type(exc).__name__ + ": " + str(exc),
        }
    conn.close()
    return {"mode": "gid-socket-denied", "ok": False, "blocked": False}


def fd_attach(job: Mapping[str, Any]) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        fd = os.open("/dev/null", os.O_RDONLY)
        try:
            packet = _packet({
                "request_id": "fd-attach-status",
                "action": "status",
                "payload": {"mission_id": job["mission_id"]},
            })
            rights = array.array("i", [fd])
            try:
                conn.sendmsg([packet], [
                    (socket.SOL_SOCKET, socket.SCM_RIGHTS, rights)])
            except (BrokenPipeError, ConnectionError, OSError):
                pass
        finally:
            os.close(fd)
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "fd-attach",
            "ok": response is None,
            "welcome_before_hello": welcome is not None,
            "closed_without_response": response is None,
        }
    finally:
        conn.close()


def fd_relay_source(job: Mapping[str, Any], *, relay_socket: str,
                    ready_path: str) -> dict[str, Any]:
    conn = _connect_raw(job)
    relay = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    try:
        welcome = _hello(job, conn)
        if os.path.lexists(relay_socket):
            os.unlink(relay_socket)
        relay.bind(relay_socket)
        os.chmod(relay_socket, 0o666)
        relay.listen(1)
        relay.settimeout(10.0)
        with open(ready_path, "w", encoding="utf-8") as handle:
            handle.write("READY\n")
            handle.flush()
            os.fsync(handle.fileno())
        helper, _ = relay.accept()
        try:
            rights = array.array("i", [conn.fileno()])
            helper.sendmsg([b"FD"], [
                (socket.SOL_SOCKET, socket.SCM_RIGHTS, rights)])
            helper.settimeout(10.0)
            data, _ancillary, _flags, _ = helper.recvmsg(65536)
            helper_result = json.loads(data.decode("utf-8")) if data else None
        finally:
            helper.close()
        return {
            "mode": "fd-relay-source",
            "ok": isinstance(helper_result, Mapping)
            and bool(helper_result.get("service_closed")),
            "welcome_before_hello": welcome is not None,
            "helper": helper_result,
        }
    finally:
        relay.close()
        try:
            os.unlink(relay_socket)
        except FileNotFoundError:
            pass
        conn.close()


def fd_relay_helper(job: Mapping[str, Any], *, relay_socket: str) -> dict[str, Any]:
    relay = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    relay.settimeout(10.0)
    relay.connect(relay_socket)
    try:
        data, ancillary, flags, _ = relay.recvmsg(
            4096, socket.CMSG_SPACE(4))
        if flags & getattr(socket, "MSG_CTRUNC", 0):
            return {"mode": "fd-relay-helper", "ok": False,
                    "error": "ancillary truncado"}
        fds = []
        for level, kind, payload in ancillary:
            if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                raw = array.array("i")
                raw.frombytes(payload[:len(payload) - len(payload) % 4])
                fds.extend(raw.tolist())
        if len(fds) != 1:
            return {"mode": "fd-relay-helper", "ok": False,
                    "error": "fd ausente", "fds": len(fds)}
        fd = fds[0]
        conn = _FdSocket(fd)
        try:
            _send_packet(conn, {
                "request_id": "fd-relay-status",
                "action": "status",
                "payload": {"mission_id": job["mission_id"]},
            })
            try:
                response = _recv_packet(conn, timeout=2.0)
            except (ConnectionError, OSError, ValueError):
                response = None
            result = {
                "mode": "fd-relay-helper",
                "ok": response is None,
                "uid": os.getuid(),
                "gid": os.getgid(),
                "fd_received": True,
                "service_closed": response is None,
            }
        finally:
            conn.close()
            os.close(fd)
        relay.send(common.canonical_json(result).encode("utf-8"))
        return result
    finally:
        relay.close()


class _FdSocket:
    """Adaptador mínimo para usar um fd recebido como socket."""

    def __init__(self, fd: int) -> None:
        self._socket = socket.socket(fileno=os.dup(fd))

    def send(self, data: bytes) -> int:
        return self._socket.send(data)

    def recvmsg(self, *args, **kwargs):
        return self._socket.recvmsg(*args, **kwargs)

    def settimeout(self, value: float) -> None:
        self._socket.settimeout(value)

    def fileno(self) -> int:
        return self._socket.fileno()

    def close(self) -> None:
        self._socket.close()


def frame(job: Mapping[str, Any], *, size: int) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        packet = struct.pack(">I", size) + b"x" * size
        try:
            conn.send(packet)
        except (BrokenPipeError, ConnectionError, OSError):
            pass
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "frame",
            "ok": response is None,
            "welcome_before_hello": welcome is not None,
            "closed_without_response": response is None,
        }
    finally:
        conn.close()


def deadline(job: Mapping[str, Any], *, sleep_seconds: float) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        time.sleep(sleep_seconds)
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "deadline",
            "ok": response is None,
            "welcome_before_hello": welcome is not None,
            "closed": response is None,
        }
    finally:
        conn.close()


def hold(job: Mapping[str, Any], *, seconds: float,
         ready_path: str) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        welcome = _hello(job, conn)
        with open(ready_path, "w", encoding="utf-8") as handle:
            handle.write("READY\n")
            handle.flush()
            os.fsync(handle.fileno())
        time.sleep(seconds)
        try:
            response = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            response = None
        return {
            "mode": "hold",
            "ok": welcome is not None,
            "welcome_before_hello": welcome is not None,
            "closed": response is None,
        }
    finally:
        conn.close()


def saturation_probe(job: Mapping[str, Any]) -> dict[str, Any]:
    conn = _connect_raw(job)
    try:
        try:
            welcome = _recv_packet(conn, timeout=2.0)
        except (ConnectionError, OSError, ValueError):
            welcome = None
        return {
            "mode": "saturation-probe",
            "ok": welcome is None,
            "welcome_received": welcome is not None,
            "closed": welcome is None,
        }
    finally:
        conn.close()


def concurrent(job: Mapping[str, Any], *, count: int) -> dict[str, Any]:
    def one(index: int) -> dict[str, Any]:
        client = _server_client(job)
        return client.request(
            "status", {"mission_id": job["mission_id"]},
            request_id=f"concurrent-{index}")

    with ThreadPoolExecutor(max_workers=count) as pool:
        responses = list(pool.map(one, range(count)))
    return {
        "mode": "concurrent",
        "ok": all(bool(response.get("ok")) for response in responses),
        "responses": responses,
    }


def sandbox(job: Mapping[str, Any]) -> dict[str, Any]:
    blocked = {}

    def attempt_read(name: str, path: str) -> None:
        try:
            with open(path, "rb") as handle:
                handle.read(1)
            blocked[name] = False
        except (PermissionError, OSError) as exc:
            blocked[name] = type(exc).__name__

    attempt_read("state_dir", str(job["state_dir"]))
    attempt_read("ledger", str(job["ledger_path"]))
    attempt_read("trust_store", str(job["trust_store_path"]))
    attempt_read("consumer_registry", str(job["consumer_registry_path"]))
    attempt_read("anchor", str(job["anchor_path"]))
    attempt_read("keys_dir", str(job["keys_dir"]))

    status_values = {}
    try:
        with open("/proc/self/status", encoding="utf-8") as handle:
            for line in handle:
                key = line.split(":", 1)[0]
                if key in {"Uid", "Gid", "CapEff", "NoNewPrivs"}:
                    status_values[key] = line.split(":", 1)[1].strip()
    except OSError:
        pass

    network_error = None
    try:
        connection = socket.create_connection(("1.1.1.1", 443), timeout=1.0)
    except OSError as exc:
        network_error = type(exc).__name__ + ": " + str(exc)
    else:
        connection.close()

    kill_error = None
    try:
        os.kill(int(job["service_pid"]), 0)
    except OSError as exc:
        kill_error = type(exc).__name__

    info = os.lstat(str(job["socket_path"]))
    client = _server_client(job)
    status_response = client.request(
        "status", {"mission_id": job["mission_id"]},
        request_id="sandbox-status")
    state_read_blocked = all(
        blocked[name] in {"PermissionError", "FileNotFoundError"}
        for name in ("state_dir", "ledger", "trust_store", "anchor", "keys_dir"))
    return {
        "mode": "sandbox",
        "ok": state_read_blocked and network_error is not None
        and kill_error == "PermissionError"
        and bool(status_response.get("ok"))
        and status_values.get("CapEff", "0") in {"0000000000000000", "0"},
        "uid": os.getuid(),
        "gid": os.getgid(),
        "cap_eff": status_values.get("CapEff"),
        "no_new_privs": status_values.get("NoNewPrivs"),
        "read_results": blocked,
        "state_read_blocked": state_read_blocked,
        "network_error": network_error,
        "kill_error": kill_error,
        "status": status_response,
        "socket_owner": info.st_uid,
        "socket_group": info.st_gid,
        "socket_mode": oct(info.st_mode & 0o777),
    }


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="COORD trust cross-UID client")
    parser.add_argument("--job", required=True)
    parser.add_argument("--mode", required=True, choices=(
        "prepare", "reserve", "assert", "record-evidence",
        "reconcile", "forbidden", "observer", "unregistered", "auth-wrong",
        "transmit", "status", "assert-old", "reserve-old", "recover",
        "recover-negative", "gid-divergence", "gid-socket-denied",
        "fd-attach", "fd-relay-source", "fd-relay-helper", "frame",
        "deadline", "hold", "saturation-probe", "concurrent", "sandbox",
    ))
    parser.add_argument("--field", default="service_id")
    parser.add_argument("--lease", default="")
    parser.add_argument("--request-id", default="")
    parser.add_argument("--recovery", default="")
    parser.add_argument("--relay-socket", default="")
    parser.add_argument("--ready-path", default="")
    parser.add_argument("--size", type=int, default=8192)
    parser.add_argument("--seconds", type=float, default=3.0)
    parser.add_argument("--count", type=int, default=2)
    args = parser.parse_args(argv)
    job = common.read_json(args.job)
    common.validate_client_job(job) if job.get("schema") == common.CLIENT_SCHEMA \
        else common.validate_sandbox_job(job)
    if args.mode == "prepare":
        result = prepare(job)
    elif args.mode == "reserve":
        result = reserve(job)
    elif args.mode == "assert":
        result = assert_current(
            job, lease_id=args.lease, request_id=args.request_id)
    elif args.mode == "record-evidence":
        result = record_evidence(
            job, lease_id=args.lease, request_id=args.request_id)
    elif args.mode == "reconcile":
        result = reconcile(job, request_id=args.request_id)
    elif args.mode == "forbidden":
        result = forbidden(job)
    elif args.mode == "observer":
        result = observer(job)
    elif args.mode == "unregistered":
        result = unregistered(job)
    elif args.mode == "auth-wrong":
        result = auth_wrong(job, field=args.field)
    elif args.mode == "transmit":
        result = transmit(job)
    elif args.mode == "status":
        result = status(job)
    elif args.mode == "assert-old":
        result = assert_old(
            job, lease_id=args.lease, request_id=args.request_id)
    elif args.mode == "reserve-old":
        result = reserve_old(job, request_id=args.request_id)
    elif args.mode == "recover":
        result = recover(job, name=args.recovery)
    elif args.mode == "recover-negative":
        result = recover_negative(job)
    elif args.mode == "gid-divergence":
        result = gid_divergence(job)
    elif args.mode == "gid-socket-denied":
        result = gid_socket_denied(job)
    elif args.mode == "fd-attach":
        result = fd_attach(job)
    elif args.mode == "fd-relay-source":
        result = fd_relay_source(
            job, relay_socket=args.relay_socket, ready_path=args.ready_path)
    elif args.mode == "fd-relay-helper":
        result = fd_relay_helper(job, relay_socket=args.relay_socket)
    elif args.mode == "frame":
        result = frame(job, size=args.size)
    elif args.mode == "deadline":
        result = deadline(job, sleep_seconds=args.seconds)
    elif args.mode == "hold":
        result = hold(
            job, seconds=args.seconds, ready_path=args.ready_path)
    elif args.mode == "saturation-probe":
        result = saturation_probe(job)
    elif args.mode == "concurrent":
        result = concurrent(job, count=args.count)
    elif args.mode == "sandbox":
        result = sandbox(job)
    else:
        raise SystemExit("modo inválido")
    _result(result)
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
