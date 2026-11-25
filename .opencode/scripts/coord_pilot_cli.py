#!/usr/bin/env python3
"""CLI operacional mínima do piloto COORD-04/P3A.2.

Toda operação roda offline por padrão. Nenhum cliente HTTP real é construído e
nenhum POST é emitido nesta missão; `execute` permanece bloqueado até decisão
explícita do mantenedor e recertificação independente.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_pilot as pilot

DEFAULT_ENDPOINT = "http://127.0.0.1:49999"
SESSION_ID = "ses_defc05853ffeej4fPXrp7frx4x"
NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
COMMANDS = ("preflight", "prepare", "execute", "reconcile", "closeout", "dry-run")


class FixtureClient(pilot.HttpClient):
    """Cliente HTTP falso: registra chamadas e devolve respostas sintéticas."""

    def __init__(self, *, with_report: bool = False, prompt_id: str = "msg-pilot-OP-COORD04-PILOT-001"):
        self.calls: list[tuple[str, str, Any]] = []
        self.base_url = DEFAULT_ENDPOINT
        info = {"version": "2.3.2", "pid": 1, "hostname": "fixture",
                "urls": [DEFAULT_ENDPOINT]}
        instance = pilot.instance_identity(info)
        target = cc.TargetIdentity("better-talker", "guilhermemellojw/better-talker", "A",
                                   SESSION_ID, instance,
                                   "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow")
        messages = [] if not with_report else [
            {"id": prompt_id, "type": "user",
             "content": [{"type": "text", "text": pilot.PILOT_PROMPT}]},
            _report_envelope(prompt_id),
        ]
        self.responses = {
            ("GET", pilot.INFO_PATH): (200, info),
            ("GET", pilot.LOCATION_PATH): (200, {
                "directory": target.location,
                "project": {"id": target.project_id, "directory": target.location,
                            "canonical": target.location}}),
            ("GET", f"/api/session/{SESSION_ID}"): (200, {"data": {
                "id": SESSION_ID, "projectID": target.project_id, "agent": target.agent_id,
                "model": {"providerID": "opencode-go", "id": "hungerg-free", "variant": "default"},
                "time": {"busy": 1, "idle": 2}, "outcome": "succeeded",
                "location": {"directory": target.location}}}),
            ("GET", pilot.ACTIVE_PATH): (200, {"data": {}}),
            ("GET", f"/api/session/{SESSION_ID}{pilot.INBOX_SUFFIX}"): (200, {"data": []}),
            ("GET", f"/api/session/{SESSION_ID}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc"):
                (200, {"data": messages}),
            ("POST", f"/api/session/{SESSION_ID}{pilot.PROMPT_SUFFIX}"):
                (202, {"data": {"id": prompt_id}}),
        }
        self.instance = instance
        self.target = target

    def request(self, method: str, path: str, body: Any = None) -> tuple[int, dict[str, Any]]:
        self.calls.append((method, path, body))
        return self.responses.get((method, path), (404, {"error": "not_found"}))


def _report_envelope(prompt_id: str) -> dict[str, Any]:
    payload = {"schema_version": cc.REPORT_SCHEMA_VERSION, "project_id": "better-talker",
               "mission_id": "MISS-COORD04-PILOT-001", "operation_id": "OP-COORD04-PILOT-001",
               "agent_id": "A", "session_id": SESSION_ID, "message_id": "id-inventado-pelo-modelo",
               "status": "COMPLETED", "head_sha": None, "branch": None, "test_evidence": [],
               "blockers": [], "artifacts": [],
               "completed_at": cc.iso(NOW + timedelta(minutes=1)),
               "acceptance_results": {"identidade da missão devolvida": True,
                                      "status explícito": True, "marcador v1": True}}
    text = coord_checkpoint.REPORT_CONTRACT + "\ncontract: " + cc.canonical_json(payload)
    return {"id": "msg-report-pilot-001", "type": "assistant", "agent": "A",
            "sessionID": SESSION_ID, "reply_to": prompt_id,
            "content": [{"type": "text", "text": text}]}


def _fixture_context(*, store_dir: str, endpoint: str, with_report: bool = False):
    client = FixtureClient(with_report=with_report)
    prompt_sha256 = pilot.sha256_text(pilot.PILOT_PROMPT)
    payload = {"prompt": pilot.PILOT_PROMPT}
    mission = cc.MissionEnvelope(
        schema_version=cc.MISSION_SCHEMA_VERSION, project_id="better-talker",
        repository_id="guilhermemellojw/better-talker", mission_id="MISS-COORD04-PILOT-001",
        operation_id="OP-COORD04-PILOT-001", coordinator_id="supervisor-shadow-experimental",
        target_agent="A", target_session_id=SESSION_ID, target_instance_id=client.instance,
        target_location=client.target.location,
        objective="Validar comunicação bidirecional real em sessão experimental",
        payload=payload, payload_sha256=cc.payload_sha256(payload),
        scope="piloto de comunicação experimental",
        expected_deliverables=("resposta estruturada",),
        acceptance_criteria=("identidade da missão devolvida", "status explícito", "marcador v1"),
        ownership_constraints=("somente sessão experimental",),
        authorization_reference="AUTH-COORD04-PILOT-001", created_at=NOW,
        expires_at=NOW + timedelta(minutes=30), timeout=60,
        expected_head_sha=None, expected_branch=None)
    auth = pilot.PilotAuthorization(
        authorization_id="AUTH-COORD04-PILOT-001", maintainer_id="mantenedor-humano",
        approval_reference="DECISAO-PILOT-001", project_id=mission.project_id,
        repository_id=mission.repository_id, instance_id=client.instance,
        target=replace(client.target, instance_id=client.instance),
        mission_id=mission.mission_id,
        mission_identity=cc.canonical_mission_identity(mission),
        operation_id=mission.operation_id, prompt_sha256=prompt_sha256,
        scope=mission.scope, issued_at=NOW, expires_at=NOW + timedelta(hours=1),
        approved=True, environment="FIXTURE", max_transmissions=1,
        expected_model="opencode-go/hungerg-free#default")
    transport = pilot.OpenCodeTransport(
        client, expected_instance_id=client.instance, registered_targets=(auth.target,),
        pilot_authorization=auth, allow_write=True, clock=lambda: NOW, mode="fixture")
    store = pilot.PilotOperationStore(store_dir)
    manifest = pilot.build_runtime_manifest(mission, auth, prompt_sha256, endpoint)
    approval = pilot.PilotApproval("APPROVAL-FIXTURE-001", "operador-fixture", "human",
                                   "FIXTURE", manifest)
    core_auth = cc.Authorization(
        reference=mission.authorization_reference, project_id=mission.project_id,
        repository_id=mission.repository_id, target=auth.target,
        payload_sha256=mission.payload_sha256,
        mission_identity=cc.canonical_mission_identity(mission), scope=mission.scope,
        issued_at=NOW, expires_at=NOW + timedelta(hours=1), synthetic=True)
    executor = pilot.PilotExecutor(transport, store, approval, endpoint=endpoint,
                                   allow_real=False, clock=lambda: NOW)
    return {"client": client, "mission": mission, "auth": auth, "core_auth": core_auth,
            "transport": transport, "store": store, "manifest": manifest,
            "approval": approval, "executor": executor}


def _parse(argv: list[str]) -> tuple[str, dict[str, Any]]:
    options: dict[str, Any] = {"store_dir": pilot.DEFAULT_PILOT_DIR,
                               "endpoint": DEFAULT_ENDPOINT, "activate": False,
                               "approval": None}
    command = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in {"--store-dir", "--endpoint", "--approval"}:
            index += 1
            if index >= len(argv):
                raise ValueError(f"{arg} sem valor")
            options[arg[2:].replace("-", "_")] = argv[index]
        elif arg == "--activate":
            options["activate"] = True
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
        context = _fixture_context(store_dir=options["store_dir"], endpoint=options["endpoint"])
        target = context["transport"].lookup_target("better-talker", "A", SESSION_ID)
        status = context["transport"].status(target)
        return {"command": command, "read_only": True, "service_contacted": False,
                "real_calls": 0, "observed": {
                    "instance_id": context["client"].instance,
                    "project_id": target.project_id, "repository_id": target.repository_id,
                    "location": target.location, "session_id": target.session_id,
                    "agent_id": target.agent_id,
                    "model": "opencode-go/hungerg-free#default",
                    "session_state": status.state, "inbox_count": status.inbox_count,
                    "api_version": "2.3.2", "endpoint": options["endpoint"]},
                "assumptions": ["agent_id informado como A, pendente de confirmação em P3B"],
                "limits": ["permissões efetivas da sessão não observadas",
                           "disponibilidade não comprovada por ausência de busy"]}

    if command == "prepare":
        context = _fixture_context(store_dir=options["store_dir"], endpoint=options["endpoint"])
        return {"command": command, "read_only": True, "real_calls": 0,
                "manifest": context["manifest"].proposal(),
                "notice": "manifesto proposto; nenhuma aprovação humana é criada pela CLI"}

    if command == "execute":
        if not options["activate"]:
            return {"command": command, "executed": False,
                    "state": "BLOCKED_BY_DEFAULT", "real_calls": 0,
                    "reason": "ativação explícita do mantenedor ausente"}
        if not options["approval"]:
            return {"command": command, "executed": False,
                    "state": "BLOCKED_NO_APPROVAL", "real_calls": 0,
                    "reason": "aprovação humana vinculada ao manifesto ausente"}
        with open(options["approval"], "rb") as handle:
            declared = json.loads(handle.read().decode("utf-8"))
        if declared.get("environment") != "APPROVED":
            return {"command": command, "executed": False,
                    "state": "BLOCKED_APPROVAL_NOT_APPROVED", "real_calls": 0}
        context = _fixture_context(store_dir=options["store_dir"], endpoint=options["endpoint"])
        declared_manifest = declared.get("manifest_sha256")
        if declared_manifest is None and isinstance(declared.get("manifest"), dict):
            declared_manifest = declared["manifest"].get("canonical_sha256")
        if declared_manifest != context["manifest"].canonical():
            return {"command": command, "executed": False,
                    "state": "BLOCKED_APPROVAL_MANIFEST_DIVERGENT", "real_calls": 0,
                    "reason": "aprovação não vinculada ao manifesto preparado"}
        return {"command": command, "executed": False,
                "state": "BLOCKED_PENDING_REAL_ACTIVATION", "real_calls": 0,
                "reason": "P3A.2 não autoriza POST real; ativação depende de P3B"}

    if command == "reconcile":
        record = store.load()
        if record is None:
            return {"command": command, "read_only": True, "real_calls": 0,
                    "state": None, "changed": False}
        # Evidência de fixture nunca é aplicada a registro de operação real.
        if record.evidence.get("environment") != "FIXTURE":
            return {"command": command, "read_only": True, "real_calls": 0,
                    "state": record.state, "changed": False,
                    "reason": "registro não-fixture; reconciliação real exige P3B"}
        context = _fixture_context(store_dir=options["store_dir"],
                                   endpoint=options["endpoint"], with_report=True)
        result = context["executor"].reconcile()
        return {"command": command, "read_only": True, "real_calls": 0,
                "transmit_calls": 0, **result}

    if command == "closeout":
        record = store.load()
        return {"command": command, "read_only": True, "real_calls": 0,
                "state": record.state if record else None,
                "evidence": record.evidence if record else {},
                "limits": ["transporte real desativado", "sem exactly-once",
                           "sem certificação independente de código"]}

    if command == "dry-run":
        context = _fixture_context(store_dir=options["store_dir"],
                                   endpoint=options["endpoint"], with_report=True)
        execute = context["executor"].execute(context["mission"], context["core_auth"],
                                              pilot.PILOT_PROMPT)
        reconcile = context["executor"].reconcile()
        posts = [call for call in context["client"].calls if call[0] == "POST"]
        return {"command": command, "read_only": True, "real_calls": 0,
                "source": "fixture", "execute": execute, "reconcile": reconcile,
                "http_posts": len(posts), "final_state": context["store"].load().state,
                "registered": len(context["store"].load().history)}
    raise ValueError(f"comando não suportado: {command}")


def main(argv: list[str] | None = None) -> int:
    try:
        result = run(list(sys.argv[1:] if argv is None else argv))
    except ValueError as e:
        print(f"erro: {e}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, ensure_ascii=False, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
