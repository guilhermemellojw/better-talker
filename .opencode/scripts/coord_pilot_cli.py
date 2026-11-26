#!/usr/bin/env python3
"""CLI operacional mínima do piloto COORD-04/P3A.2.

Toda operação roda offline por padrão. Nenhum cliente HTTP real é construído e
nenhum POST é emitido nesta missão; `execute` permanece bloqueado até decisão
explícita do mantenedor e recertificação independente.
"""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_pilot as pilot

# Fonte única do endpoint: o mesmo módulo que valida e vincula a operação.
DEFAULT_ENDPOINT = pilot.DEFAULT_ENDPOINT
REAL_STORE_DIR = os.path.join(pilot.DEFAULT_PILOT_DIR, "real")
# ID fixado existe somente para fixtures; o caminho real exige destino cadastrado.
SESSION_ID = "ses_defc05853ffeej4fPXrp7frx4x"
SESSION_REGISTRY_FILE = "experimental-session.json"
NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)  # permitido exclusivamente em fixtures
COMMANDS = ("preflight", "prepare", "execute", "reconcile", "closeout", "dry-run",
            "session-register")


class FixtureClient(pilot.HttpClient):
    """Cliente HTTP falso: registra chamadas e devolve respostas sintéticas."""

    def __init__(self, *, with_report: bool = False, prompt_id: str = "msg-pilot-OP-COORD04-PILOT-001"):
        self.calls: list[tuple[str, str, Any]] = []
        self.base_url = pilot.DEFAULT_ENDPOINT
        self.endpoint = pilot.DEFAULT_ENDPOINT
        self.source = "fixture"
        self.service_contacted = False
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
                "model": {"providerID": "opencode", "id": "hy4-flash-free", "variant": "none"},
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


def _fixture_context(*, store_dir: str, endpoint: Optional[str] = None,
                     with_report: bool = False):
    # Fixture nunca usa endpoint informado pelo chamador: fonte única canônica.
    endpoint = pilot.DEFAULT_ENDPOINT
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
        expected_model=pilot.EXPERIMENTAL_MODEL,
        endpoint=pilot.DEFAULT_ENDPOINT)
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


ALLOWED_FREE_MODEL = pilot.EXPERIMENTAL_MODEL  # fonte única da política experimental


def _real_client(endpoint: str, *, auth_env: Optional[str] = None) -> pilot.ReadOnlyHttpClient:
    """Cliente real somente leitura; POST é bloqueado mecanicamente."""
    canonical = pilot.canonical_endpoint(endpoint)
    auth = None
    if auth_env:
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", auth_env):
            raise ValueError("nome de variável de credencial inválido")
        # Apenas o provedor é injetado; o valor nunca é lido para logs ou saída.
        auth = pilot.ExperimentalAuth(
            canonical, password_provider=lambda name=auth_env: os.environ.get(name, ""))
    return pilot.ReadOnlyHttpClient(canonical, allowed_port=pilot.endpoint_port(canonical),
                                    auth=auth)


def _session_registry_path(store_dir: str) -> str:
    return os.path.join(store_dir, SESSION_REGISTRY_FILE)


REQUIRED_SESSION_FIELDS = ("schema_version", "session_id", "experimental", "endpoint",
                           "instance_id", "project_id", "repository_id", "agent_id",
                           "model", "location", "authorization_reference", "registered_at")


def _validate_registry(value: Any) -> dict[str, Any]:
    """Valida integridade do cadastro local; erro controlado e fail-closed."""
    if not isinstance(value, dict):
        raise ValueError("cadastro de sessão inválido")
    missing = [key for key in REQUIRED_SESSION_FIELDS if key not in value]
    if missing:
        raise ValueError(f"cadastro incompleto: {', '.join(missing)}")
    if not re.fullmatch(r"ses_[A-Za-z0-9]+", str(value["session_id"])):
        raise ValueError("session_id inválido")
    if value["experimental"] is not True:
        raise ValueError("destino não marcado como experimental")
    value["endpoint"] = pilot.canonical_endpoint(str(value["endpoint"]))
    if value["project_id"] != pilot.EXPECTED_PROJECT_ID:
        raise ValueError("projeto cadastrado diverge da política")
    if value["repository_id"] != pilot.EXPECTED_REPOSITORY_ID:
        raise ValueError("repositório cadastrado diverge da política")
    if value["location"] != pilot.EXPERIMENTAL_WORKTREE:
        raise ValueError("location cadastrada diverge da worktree experimental")
    if value["model"] != ALLOWED_FREE_MODEL:
        raise ValueError("modelo cadastrado não é o gratuito autorizado")
    if "permissions_confirmed" in value and not isinstance(value["permissions_confirmed"], bool):
        raise ValueError("confirmação de permissões inválida")
    return value


def _load_registered_session(store_dir: str) -> Optional[dict[str, Any]]:
    path = _session_registry_path(store_dir)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "rb") as handle:
            value = json.loads(handle.read().decode("utf-8"))
        return _validate_registry(value)
    except (OSError, ValueError, UnicodeError, json.JSONDecodeError) as e:
        raise ValueError(f"cadastro de sessão corrompido: {type(e).__name__}") from e


def _register_session(store_dir: str, fields: dict[str, Any], *, replace: bool = False) -> dict[str, Any]:
    """Registra localmente um destino experimental já autorizado; não cria sessão real."""
    path = _session_registry_path(store_dir)
    if os.path.exists(path) and not replace:
        raise ValueError("cadastro existente; substituição exige --replace explícito")
    session_id = str(fields.get("session_id") or "")
    if not re.fullmatch(r"ses_[A-Za-z0-9]+", session_id):
        raise ValueError("session_id inválido")
    if fields.get("experimental") is not True:
        raise ValueError("destino não marcado como experimental")
    endpoint = pilot.canonical_endpoint(str(fields.get("endpoint") or ""))
    for key in ("instance_id", "project_id", "repository_id", "agent_id", "model",
                "location", "authorization_reference"):
        if not isinstance(fields.get(key), str) or not fields[key].strip():
            raise ValueError(f"{key} ausente no cadastro")
    if fields["project_id"] != pilot.EXPECTED_PROJECT_ID:
        raise ValueError("projeto cadastrado diverge da política do Better Talker")
    if fields["location"] != pilot.EXPERIMENTAL_WORKTREE:
        raise ValueError("location cadastrada diverge da worktree experimental")
    if fields["model"] != ALLOWED_FREE_MODEL:
        raise ValueError("modelo cadastrado não é o gratuito autorizado")
    value = {
        "schema_version": "1.0", "session_id": session_id, "experimental": True,
        "endpoint": endpoint, "instance_id": fields["instance_id"],
        "project_id": fields["project_id"], "repository_id": fields["repository_id"],
        "agent_id": fields["agent_id"], "model": fields["model"],
        "location": fields["location"],
        "authorization_reference": fields["authorization_reference"],
        "registered_at": cc.iso(cc.utc_now()),
    }
    for key in ("permissions_confirmed", "permission_policy"):
        if key in fields:
            value[key] = fields[key]
    value = _validate_registry(value)
    os.makedirs(store_dir, mode=0o700, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(value, handle, sort_keys=True, ensure_ascii=False)
    return value


def _resolve_session(store_dir: str, requested: Optional[str]) -> Optional[dict[str, Any]]:
    registered = _load_registered_session(store_dir)
    if requested and (not registered or registered.get("session_id") != requested):
        return None
    return registered


def _validate_proposal_window(created_at: datetime, issued_at: datetime,
                              expires_at: datetime, clock: datetime) -> None:
    cc._require_aware(created_at, "created_at")
    cc._require_aware(issued_at, "issued_at")
    cc._require_aware(expires_at, "expires_at")
    if created_at > clock or issued_at > clock:
        raise ValueError("proposta com data futura indevida")
    if expires_at <= issued_at:
        raise ValueError("validade invertida")
    if expires_at <= clock:
        raise ValueError("proposta expirada")


def _real_observe(endpoint: str, *, auth_env: Optional[str] = None,
                  session: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    """GETs ordenados: cadastro/endpoint → instância → projeto → sessão → inbox."""
    canonical = pilot.canonical_endpoint(endpoint)
    seen: list[str] = []
    observed: dict[str, Any] = {
        "endpoint": canonical, "source": "real", "read_only": True,
        "connection_attempted": False, "service_contacted": False,
        "authentication_succeeded": False, "http_response_valid": False,
        "metadata_available": False, "session_found": False,
        "recipient_authorized": bool(session), "status_kind": "not_attempted",
        "http_status": None, "real_calls": 0, "calls": seen, "blockers": [],
        "fallback": "none", "instance_confirmed": False, "identity_confirmed": False,
        "session_identity_confirmed": False, "availability_confirmed": False,
        "agent_confirmed": False, "model_confirmed": False, "permissions_confirmed": False,
        "limits": ["busy/idle não comprovam disponibilidade",
                   "conteúdo de mensagens não consultado nesta missão"],
        "hypotheses": [],
    }
    # Nenhuma consulta é feita antes de validar cadastro e endpoint.
    if session is not None:
        try:
            session = _validate_registry(session)
        except ValueError as e:
            observed["status_kind"] = "registry_invalid"
            observed["blockers"].append(f"cadastro inválido: {e}")
            return observed
        if session["endpoint"] != canonical:
            observed["status_kind"] = "registry_endpoint_divergent"
            observed["blockers"].append("cadastro de outra instância/endpoint; nenhum GET")
            return observed
    client = _real_client(canonical, auth_env=auth_env)

    def fetch(path: str, label: str) -> Optional[dict[str, Any]]:
        seen.append(f"GET {path}")
        observed["connection_attempted"] = True
        try:
            status, payload = client.request("GET", path)
        except Exception as e:
            observed["status_kind"] = "connection_error"
            observed["error"] = f"{type(e).__name__}: {e}"
            observed["blockers"].append("falha de conexão; nenhum fallback para fixture")
            return None
        observed["http_status"] = status
        observed["service_contacted"] = True
        if status in {401, 403}:
            observed["authentication_succeeded"] = False
            observed["http_response_valid"] = False
            observed["status_kind"] = "unauthorized"
            observed["blockers"].append("autenticação recusada pelo serviço")
            return None
        observed["authentication_succeeded"] = True
        if (status == 404 and isinstance(payload, dict)
                and payload.get("_tag") == "SessionNotFoundError"):
            observed["http_response_valid"] = False
            observed["status_kind"] = "session_absent"
            observed["session_absence_confirmed"] = True
            return None
        if status != 200:
            observed["http_response_valid"] = False
            observed["status_kind"] = "http_error"
            observed["blockers"].append(f"GET {label} falhou: {status}")
            return None
        observed["http_response_valid"] = True
        return payload

    # 1) identidade da instância; nenhuma consulta de sessão antes desta validação.
    info = fetch(pilot.INFO_PATH, "info")
    if info is None:
        observed["real_calls"] = len(seen)
        return observed
    observed["status_kind"] = "ok"
    observed["metadata_available"] = True
    observed["api_version"] = info.get("version")
    observed["pid"] = info.get("pid")
    observed["hostname"] = info.get("hostname")
    observed["urls"] = list(info.get("urls") or [])
    observed["instance_id"] = pilot.instance_identity(info)
    if session is not None and observed["instance_id"] != session["instance_id"]:
        observed["status_kind"] = "instance_divergent"
        observed["blockers"].append(
            "instância observada diverge do cadastro; nenhum GET de sessão/inbox")
        observed["real_calls"] = len(seen)
        return observed
    observed["instance_confirmed"] = True

    # 2) projeto/location/repositório pela validação compartilhada.
    location = fetch(pilot.LOCATION_PATH, "location")
    if location is None:
        observed["real_calls"] = len(seen)
        return observed
    project = location.get("project") or {}
    observed["location_directory"] = location.get("directory")
    observed["opencode_project_id"] = project.get("id")
    observed["project_canonical"] = project.get("canonical")
    observed["opencode_repository_field"] = project.get("repository")
    try:
        identity = pilot.validate_observed_identity(
            location,
            project_id=(session["project_id"] if session else pilot.EXPECTED_PROJECT_ID),
            expected_location=(session["location"] if session else pilot.EXPERIMENTAL_WORKTREE),
            repository_id=(session["repository_id"] if session else None))
    except cc.ContractError as e:
        observed["status_kind"] = "identity_divergent"
        observed["blockers"].append(f"identidade de projeto divergente: {e}")
        observed["real_calls"] = len(seen)
        return observed
    observed["identity_confirmed"] = True
    observed["project_id"] = identity.project_id
    observed["repository_id"] = identity.repository_id

    inventory = fetch("/api/session", "inventário")
    if inventory is None:
        observed["real_calls"] = len(seen)
        return observed
    data = inventory.get("data")
    if isinstance(data, list):
        observed["session_count"] = len(data)
        observed["inventory_fields"] = sorted(data[0].keys()) if data else []
        observed["inventory_cursor"] = dict(inventory.get("cursor") or {})

    active = fetch(pilot.ACTIVE_PATH, "active")
    if active is None:
        observed["real_calls"] = len(seen)
        return observed
    active_data = active.get("data") or {}
    observed["active_sessions"] = len(active_data)

    # 3) somente após instância e projeto confirmados, metadados da sessão.
    if session is not None:
        session_id = session["session_id"]
        metadata = fetch(f"/api/session/{session_id}", "metadados da sessão")
        if metadata is None:
            observed["real_calls"] = len(seen)
            return observed
        value = metadata.get("data") or {}
        observed["session_found"] = True
        observed["session_id"] = value.get("id")
        observed["agent_id"] = value.get("agent")
        observed["session_project_id"] = value.get("projectID")
        observed["session_repository_id"] = value.get("repositoryID")
        observed["outcome"] = value.get("outcome")
        observed["time"] = dict(value.get("time") or {})
        model = value.get("model") or {}
        observed["session_model"] = ""
        if all(model.get(k) for k in ("providerID", "id")):
            observed["session_model"] = pilot.canonical_model_ref(
                model.get("providerID"), model.get("id"), model.get("variant"))
        observed["session_location"] = (value.get("location") or {}).get("directory")
        reasons: list[str] = []
        if observed["session_id"] != session_id:
            reasons.append("session_id divergente")
        if observed["agent_id"] != session["agent_id"]:
            reasons.append("agente divergente")
        if observed["session_project_id"] != identity.opencode_project_id:
            reasons.append("projeto interno divergente")
        if observed["session_location"] != observed["location_directory"]:
            reasons.append("location da sessão divergente")
        if observed["session_repository_id"] not in (None, identity.repository_id):
            reasons.append("repositório divergente")
        if observed["session_model"] != session["model"]:
            reasons.append("modelo divergente")
        observed["model_confirmed"] = observed["session_model"] == ALLOWED_FREE_MODEL
        observed["agent_confirmed"] = observed["agent_id"] == session["agent_id"]
        observed["availability_confirmed"] = bool(observed["outcome"]) and bool(observed["time"])
        observed["permissions_confirmed"] = session.get("permissions_confirmed") is True
        if not observed["availability_confirmed"]:
            reasons.append("disponibilidade desconhecida")
        if not observed["model_confirmed"]:
            reasons.append("modelo gratuito não confirmado")
        if not observed["permissions_confirmed"]:
            reasons.append("permissões efetivas não confirmadas")
        if reasons:
            observed["status_kind"] = "session_identity_divergent"
            observed["blockers"].extend(reasons)
            observed["real_calls"] = len(seen)
            return observed
        observed["session_identity_confirmed"] = True
        # 4) inbox somente após identidade efetiva confirmada.
        inbox = fetch(f"/api/session/{session_id}{pilot.INBOX_SUFFIX}", "inbox")
        if inbox is not None:
            observed["inbox_count"] = len(inbox.get("data") or [])
            if observed["inbox_count"] > 0:
                observed["blockers"].append("inbox ocupado")
    else:
        observed["recipient_authorized"] = False
        observed["blockers"].append(
            "nenhum destinatário experimental cadastrado; somente infraestrutura observada")
    observed["real_calls"] = len(seen)
    return observed


def _observe_blockers(observed: dict[str, Any]) -> list[str]:
    """Bloqueios derivados somente do que foi efetivamente observado."""
    blockers = list(observed.get("blockers") or [])
    if observed.get("api_version") not in (None, "2.3.2"):
        blockers.append("versão da API observada diverge de 2.3.2")
    for key, label in (("instance_id", "instância"), ("location_directory", "location")):
        if not observed.get(key):
            blockers.append(f"{label} não observado; não presumir valor")
    if observed.get("location_directory") and observed["location_directory"] != pilot.EXPERIMENTAL_WORKTREE:
        blockers.append("location observada diverge da worktree experimental")
    if observed.get("urls") and observed["endpoint"] not in observed["urls"]:
        blockers.append("endpoint observado diverge do endpoint efetivo do cliente")
    if observed.get("service_contacted") is False:
        blockers.append("serviço real não contactado")
    if observed.get("status_kind") == "session_absent":
        blockers.append("sessão cadastrada ausente no banco isolado; não criar automaticamente")
    if not observed.get("recipient_authorized"):
        blockers.append("destinatário experimental não autorizado")
    return blockers


def _manifest_readiness_blockers(observed: dict[str, Any],
                                 session: Optional[dict[str, Any]]) -> list[str]:
    """Exige correspondência explícita entre cadastro (esperado) e API (observado)."""
    blockers: list[str] = []
    for key, label in (("instance_confirmed", "instância não confirmada"),
                       ("identity_confirmed", "projeto/location não confirmados"),
                       ("session_found", "sessão não encontrada"),
                       ("session_identity_confirmed", "identidade da sessão não confirmada"),
                       ("availability_confirmed", "disponibilidade desconhecida"),
                       ("model_confirmed", "modelo gratuito não confirmado"),
                       ("permissions_confirmed", "permissões efetivas não confirmadas")):
        if observed.get(key) is not True:
            blockers.append(label)
    if observed.get("inbox_count", 0) > 0:
        blockers.append("inbox ocupado")
    if observed.get("opencode_project_id") != observed.get("session_project_id"):
        blockers.append("projeto interno divergente")
    if observed.get("repository_id") != pilot.EXPECTED_REPOSITORY_ID:
        blockers.append("repositório divergente")
    if session is not None:
        for observed_key, expected_key in (("session_id", "session_id"),
                                           ("agent_id", "agent_id"),
                                           ("session_model", "model"),
                                           ("session_location", "location"),
                                           ("session_repository_id", "repository_id")):
            if observed.get(observed_key) != session.get(expected_key):
                blockers.append(f"{observed_key} divergente")
    return blockers


def _parse(argv: list[str]) -> tuple[str, dict[str, Any]]:
    options: dict[str, Any] = {"store_dir": pilot.DEFAULT_PILOT_DIR,
                               "endpoint": DEFAULT_ENDPOINT, "activate": False,
                               "approval": None, "real": False, "session": None,
                               "auth_env": None, "replace": False,
                               "permissions_confirmed": False,
                               "instance_id": None, "agent_id": None,
                               "authorization_reference": None}
    command = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in {"--store-dir", "--endpoint", "--approval", "--session", "--auth-env",
                   "--instance-id", "--agent-id", "--authorization-reference"}:
            index += 1
            if index >= len(argv):
                raise ValueError(f"{arg} sem valor")
            options[arg[2:].replace("-", "_")] = argv[index]
        elif arg == "--activate":
            options["activate"] = True
        elif arg == "--replace":
            options["replace"] = True
        elif arg == "--permissions-confirmed":
            options["permissions_confirmed"] = True
        elif arg == "--real":
            options["real"] = True
            if options["store_dir"] == pilot.DEFAULT_PILOT_DIR:
                options["store_dir"] = REAL_STORE_DIR
        elif arg in COMMANDS and command is None:
            command = arg
        else:
            raise ValueError(f"argumento não reconhecido: {arg}")
        index += 1
    if command is None:
        raise ValueError("comando ausente")
    return command, options


def _prepare_real(observed: dict[str, Any], *, session: Optional[dict[str, Any]] = None,
                  clock: Optional[datetime] = None) -> dict[str, Any]:
    """Proposta real com relógio UTC corrente; nunca emite PilotApproval."""
    clock = clock or cc.utc_now()
    try:
        if session is not None:
            session = _validate_registry(session)
    except ValueError as e:
        return {"command": "prepare", "mode": "real", "source": "real",
                "approved": False, "environment": "PROPOSED", "blocked": True,
                "blockers": [f"cadastro inválido: {e}"],
                "reason": "manifesto não emitido"}
    result: dict[str, Any] = {
        "command": "prepare", "mode": "real", "source": "real",
        "service_contacted": observed["service_contacted"], "read_only": True,
        "real_calls": observed["real_calls"], "observed": observed,
        "approved": False, "environment": "PROPOSED",
        "notice": "proposta não autorizada; nenhuma PilotApproval humana é emitida pela CLI",
    }
    blockers = _observe_blockers(observed)
    blockers.extend(_manifest_readiness_blockers(observed, session))
    if not observed.get("authentication_succeeded") or not observed.get("metadata_available"):
        blockers.append("metadados reais incompletos")
    if session is None:
        blockers.append("destinatário experimental não cadastrado")
    if blockers:
        result.update({"blocked": True, "blockers": blockers,
                       "reason": "metadados/destinatário insuficientes; manifesto não emitido"})
        return result
    if (session["endpoint"] != observed["endpoint"]
            or session["instance_id"] != observed["instance_id"]):
        result.update({"blocked": True,
                       "reason": "destino cadastrado diverge do endpoint/instância observados"})
        return result
    if observed.get("project_canonical") != pilot.EXPECTED_CANONICAL_DIR:
        result.update({"blocked": True,
                       "reason": "diretório canônico observado diverge da política"})
        return result
    try:
        repository_id = pilot.repository_identity_from_git()
    except cc.ContractError:
        result.update({"blocked": True,
                       "reason": "repositório Git local divergente ou indisponível"})
        return result
    created_at = clock
    issued_at = clock
    expires_at = clock + timedelta(minutes=30)
    try:
        _validate_proposal_window(created_at, issued_at, expires_at, clock)
    except ValueError as e:
        result.update({"blocked": True, "reason": str(e)})
        return result
    project_id = pilot.EXPECTED_PROJECT_ID
    target = cc.TargetIdentity(project_id, repository_id, session["agent_id"],
                               session["session_id"], observed["instance_id"],
                               observed["location_directory"])
    payload = {"prompt": pilot.PILOT_PROMPT}
    mission = cc.MissionEnvelope(
        schema_version=cc.MISSION_SCHEMA_VERSION, project_id=project_id,
        repository_id=repository_id, mission_id="MISS-COORD04-PILOT-001",
        operation_id="OP-COORD04-PILOT-001", coordinator_id="supervisor-shadow-experimental",
        target_agent=session["agent_id"], target_session_id=session["session_id"],
        target_instance_id=observed["instance_id"], target_location=observed["location_directory"],
        objective="Validar comunicação bidirecional real em sessão experimental",
        payload=payload, payload_sha256=cc.payload_sha256(payload),
        scope="piloto de comunicação experimental",
        expected_deliverables=("resposta estruturada com mission_id e operation_id",),
        acceptance_criteria=("identidade da missão devolvida", "status explícito",
                             "marcador BT-COORD-REPORT:v1"),
        ownership_constraints=("somente sessão experimental; nenhuma edição de arquivo",),
        authorization_reference=session["authorization_reference"], created_at=created_at,
        expires_at=expires_at, timeout=60, expected_head_sha=None, expected_branch=None)
    prompt_sha256 = pilot.sha256_text(pilot.PILOT_PROMPT)
    authorization = pilot.PilotAuthorization(
        authorization_id="AUTH-COORD04-PILOT-001", maintainer_id="<mantenedor>",
        approval_reference="<decisao-pendente>", project_id=project_id,
        repository_id=repository_id, instance_id=observed["instance_id"], target=target,
        mission_id=mission.mission_id, mission_identity=cc.canonical_mission_identity(mission),
        operation_id=mission.operation_id, prompt_sha256=prompt_sha256, scope=mission.scope,
        issued_at=issued_at, expires_at=expires_at, approved=False,
        environment="PROPOSED", max_transmissions=1, expected_model=session["model"],
        endpoint=observed["endpoint"])
    manifest = pilot.build_runtime_manifest(mission, authorization, prompt_sha256,
                                            observed["endpoint"])
    result.update({
        "manifest": manifest.proposal(), "authorization": authorization.proposal(),
        "created_at": cc.iso(created_at), "issued_at": cc.iso(issued_at),
        "expires_at": cc.iso(expires_at),
        "opencode_project_id": observed.get("opencode_project_id"),
        "repository_source": "git-local",
        "assumptions": ["maintainer_id e approval_reference dependem de decisão humana"],
        "limits": ["HEAD e digests recalculados no momento desta proposta",
                   "alterar arquivos operacionais invalida o manifesto"],
    })
    return result


def run(argv: list[str]) -> dict[str, Any]:
    """Executa uma operação de controle; nunca faz POST real."""
    command, options = _parse(argv)
    store = pilot.PilotOperationStore(options["store_dir"])
    mode = "real" if options["real"] else "fixture"
    source = "real" if options["real"] else "fixture"
    try:
        session = _resolve_session(options["store_dir"], options["session"]) if options["real"] else None
    except ValueError as e:
        return {"command": command, "mode": "real", "source": "real",
                "service_contacted": False, "recipient_authorized": False,
                "status_kind": "registry_invalid", "blocked": True,
                "blockers": [f"cadastro inválido: {e}"], "real_calls": 0}

    if command == "session-register":
        try:
            registered = _register_session(options["store_dir"], {
                "session_id": options["session"], "experimental": True,
                "endpoint": options["endpoint"], "instance_id": options["instance_id"],
                "project_id": pilot.EXPECTED_PROJECT_ID,
                "repository_id": pilot.EXPECTED_REPOSITORY_ID,
                "agent_id": options["agent_id"], "model": ALLOWED_FREE_MODEL,
                "location": pilot.EXPERIMENTAL_WORKTREE,
                "authorization_reference": options["authorization_reference"],
                "permissions_confirmed": options["permissions_confirmed"],
            }, replace=options["replace"])
        except (ValueError, cc.ContractError) as e:
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "registered": False, "reason": str(e)}
        return {"command": command, "source": "fixture", "service_contacted": False,
                "registered": True, "session": registered["session_id"],
                "endpoint": registered["endpoint"],
                "notice": "cadastro local; nenhuma sessão real foi criada"}

    if command == "preflight":
        if options["real"]:
            observed = _real_observe(options["endpoint"], auth_env=options["auth_env"],
                                     session=session)
            return {"command": command, "mode": "real", "source": "real",
                    "read_only": True, "service_contacted": observed["service_contacted"],
                    "connection_attempted": observed["connection_attempted"],
                    "authentication_succeeded": observed["authentication_succeeded"],
                    "http_response_valid": observed["http_response_valid"],
                    "metadata_available": observed["metadata_available"],
                    "session_found": observed["session_found"],
                    "recipient_authorized": observed["recipient_authorized"],
                    "status_kind": observed["status_kind"], "http_status": observed["http_status"],
                    "real_calls": observed["real_calls"], "observed": observed,
                    "blockers": _observe_blockers(observed),
                    "limits": observed["limits"], "fallback": observed.get("fallback", "none")}
        context = _fixture_context(store_dir=options["store_dir"])
        target = context["transport"].lookup_target("better-talker", "A", SESSION_ID)
        status = context["transport"].status(target)
        return {"command": command, "mode": "fixture", "source": "fixture",
                "read_only": True, "service_contacted": False,
                "real_calls": 0, "fallback": "none", "observed": {
                    "instance_id": context["client"].instance,
                    "project_id": target.project_id, "repository_id": target.repository_id,
                    "location": target.location, "session_id": target.session_id,
                    "agent_id": target.agent_id,
                    "model": pilot.EXPERIMENTAL_MODEL,
                    "session_state": status.state, "inbox_count": status.inbox_count,
                    "api_version": "2.3.2", "endpoint": pilot.DEFAULT_ENDPOINT},
                "assumptions": ["agent_id informado como A, pendente de confirmação em P3B"],
                "limits": ["permissões efetivas da sessão não observadas",
                           "disponibilidade não comprovada por ausência de busy"]}

    if command == "prepare":
        if options["real"]:
            observed = _real_observe(options["endpoint"], auth_env=options["auth_env"],
                                     session=session)
            return _prepare_real(observed, session=session)
        context = _fixture_context(store_dir=options["store_dir"])
        return {"command": command, "mode": "fixture", "source": "fixture",
                "service_contacted": False, "read_only": True, "real_calls": 0,
                "fallback": "none", "manifest": context["manifest"].proposal(),
                "notice": "manifesto proposto; nenhuma aprovação humana é criada pela CLI"}

    if command == "execute":
        if not options["activate"]:
            return {"command": command, "mode": mode, "source": source,
                    "executed": False, "service_contacted": False,
                    "state": "BLOCKED_BY_DEFAULT", "real_calls": 0,
                    "reason": "ativação explícita do mantenedor ausente"}
        if not options["approval"]:
            return {"command": command, "mode": mode, "source": source,
                    "executed": False, "service_contacted": False,
                    "state": "BLOCKED_NO_APPROVAL", "real_calls": 0,
                    "reason": "aprovação humana vinculada ao manifesto ausente"}
        with open(options["approval"], "rb") as handle:
            declared = json.loads(handle.read().decode("utf-8"))
        if declared.get("environment") != "APPROVED":
            return {"command": command, "mode": mode, "source": source,
                    "executed": False, "service_contacted": False,
                    "state": "BLOCKED_APPROVAL_NOT_APPROVED", "real_calls": 0}
        context = _fixture_context(store_dir=options["store_dir"])
        declared_manifest = declared.get("manifest_sha256")
        if declared_manifest is None and isinstance(declared.get("manifest"), dict):
            declared_manifest = declared["manifest"].get("canonical_sha256")
        if declared_manifest != context["manifest"].canonical():
            return {"command": command, "mode": mode, "source": source,
                    "executed": False, "service_contacted": False,
                    "state": "BLOCKED_APPROVAL_MANIFEST_DIVERGENT", "real_calls": 0,
                    "reason": "aprovação não vinculada ao manifesto preparado"}
        # Caminho futuro: cliente real somente leitura é construído e vinculado,
        # mas nenhuma transmissão é iniciada nesta missão.
        endpoint_state = "not_requested"
        if options["real"]:
            try:
                endpoint_state = _real_client(options["endpoint"],
                                              auth_env=options["auth_env"]).endpoint
            except cc.ContractError as e:
                endpoint_state = f"blocked: {e}"
        return {"command": command, "mode": mode, "source": source,
                "executed": False, "service_contacted": False,
                "state": "BLOCKED_PENDING_REAL_ACTIVATION", "real_calls": 0,
                "endpoint_bound": endpoint_state,
                "reason": "P3B.0 não autoriza POST real; decisão separada do mantenedor"}

    if command == "reconcile":
        record = store.load()
        if record is None:
            return {"command": command, "mode": mode, "source": source,
                    "service_contacted": False, "read_only": True, "real_calls": 0,
                    "state": None, "changed": False}
        # Evidência de fixture nunca é aplicada a registro de operação real.
        if record.evidence.get("environment") != "FIXTURE":
            return {"command": command, "mode": mode, "source": source,
                    "service_contacted": False, "read_only": True, "real_calls": 0,
                    "state": record.state, "changed": False,
                    "reason": "registro não-fixture; reconciliação real exige P3B"}
        context = _fixture_context(store_dir=options["store_dir"], with_report=True)
        result = context["executor"].reconcile()
        return {"command": command, "mode": mode, "source": source,
                "service_contacted": False, "read_only": True, "real_calls": 0,
                "transmit_calls": 0, **result}

    if command == "closeout":
        record = store.load()
        return {"command": command, "mode": mode, "source": source,
                "service_contacted": False, "read_only": True, "real_calls": 0,
                "state": record.state if record else None,
                "evidence": record.evidence if record else {},
                "limits": ["transporte real desativado", "sem exactly-once",
                           "sem certificação independente de código"]}

    if command == "dry-run":
        context = _fixture_context(store_dir=options["store_dir"], with_report=True)
        execute = context["executor"].execute(context["mission"], context["core_auth"],
                                              pilot.PILOT_PROMPT)
        reconcile = context["executor"].reconcile()
        posts = [call for call in context["client"].calls if call[0] == "POST"]
        return {"command": command, "mode": "fixture", "source": "fixture",
                "service_contacted": False, "read_only": True, "real_calls": 0,
                "fallback": "none", "execute": execute, "reconcile": reconcile,
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
