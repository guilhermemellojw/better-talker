#!/usr/bin/env python3
"""CLI operacional mínima do piloto COORD-04/P3A.2.

Toda operação roda offline por padrão. Nenhum cliente HTTP real é construído e
nenhum POST é emitido nesta missão; `execute` permanece bloqueado até decisão
explícita do mantenedor e recertificação independente.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import coord_checkpoint as coord_checkpoint
import coord_communication as cc
import coord_ledger as ledger
import coord_model_policy as model_policy
import coord_p14_adapter as adapter
import coord_pilot as pilot

# Fonte única do endpoint: o mesmo módulo que valida e vincula a operação.
DEFAULT_ENDPOINT = pilot.DEFAULT_ENDPOINT
REAL_STORE_DIR = os.path.join(pilot.DEFAULT_PILOT_DIR, "real")
# ID fixado existe somente para fixtures; o caminho real exige destino cadastrado.
SESSION_ID = "ses_defc05853ffeej4fPXrp7frx4x"
SESSION_REGISTRY_FILE = "experimental-session.json"
NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)  # permitido exclusivamente em fixtures
COMMANDS = ("preflight", "prepare", "execute", "reconcile", "closeout", "dry-run",
            "session-register", "rebind-check", "permission-probe-plan")

# Matriz fechada da futura avaliação de permissões (Agente E). Nenhum recurso é executado.
PROBE_TEMP_RESOURCE = "/tmp/opencode/permission-probe-readonly"
PERMISSION_PROBE_MATRIX = (
    ("shell", ("git status",), "deny"),
    ("edit", (PROBE_TEMP_RESOURCE,), "deny"),
    ("read", (PROBE_TEMP_RESOURCE,), "deny"),
    ("external_directory", (PROBE_TEMP_RESOURCE,), "deny"),
    ("subagent", (PROBE_TEMP_RESOURCE,), "deny"),
    ("skill", (PROBE_TEMP_RESOURCE,), "deny"),
    ("browser", (PROBE_TEMP_RESOURCE,), "deny"),
    ("execute", (PROBE_TEMP_RESOURCE,), "deny"),
    ("question", ("*",), "allow"),
)


def build_permission_probe_plan(*, session_id: str, policy_sha256: str) -> dict[str, Any]:
    """Plano sintético para futura avaliação; não executa nada e não autoriza transmissão."""
    verified = bool(cc.SHA256_PATTERN.fullmatch(policy_sha256 or ""))
    limits = {
        "max_preflight_gets": 5,
        "max_evaluation_posts": len(PERMISSION_PROBE_MATRIX),
        "max_reply_posts": len(PERMISSION_PROBE_MATRIX),
    }
    return {
        "command": "permission-probe-plan", "source": "fixture", "service_contacted": False,
        "blocked": not verified,
        "reason": None if verified else "hash da política ausente ou inválido; probe bloqueado",
        "endpoint": f"POST /api/session/{session_id}/permission",
        "reply_endpoint": f"POST /api/session/{session_id}/permission/{{requestID}}/reply",
        "session_id": session_id, "policy_sha256": policy_sha256,
        "policy_preserved": verified, "no_save": True, "no_prompt": True, "no_llm": True,
        "no_tool_execution": True, "no_new_session": True,
        "question_is_tool": True, "deny_is_not_exhaustive": True,
        "agent_omitted": True,  # usa o agente efetivo da sessão (build)
        "one_action_per_request": True,
        "limits": limits,
        "max_requests": (limits["max_preflight_gets"] + limits["max_evaluation_posts"]
                         + limits["max_reply_posts"]),
        "stop_on_unexpected": True,
        "ask_handling": {
            "effect": "ask", "action": "reply", "decision": "reject",
            "requires_specific_authorization": True,
            "count_request": True, "record_sanitized": True,
            "synthetic_case": {"action": "read", "resources": [PROBE_TEMP_RESOURCE],
                               "expected": "ask", "reply": "reject"},
        },
        "expected_results": [
            {"action": action, "resources": list(resources), "effect": expected}
            for action, resources, expected in PERMISSION_PROBE_MATRIX
        ],
        "notice": "planoOffline; avaliador não comprova snapshot/call sites/plugins; não autoriza transmissão",
    }


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
    recommendation = fixture_model_recommendation()
    selected_model = selected_model_for_dispatch(recommendation)
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
        expected_model=selected_model,
        endpoint=pilot.DEFAULT_ENDPOINT)
    transport = pilot.OpenCodeTransport(
        client, expected_instance_id=client.instance, registered_targets=(auth.target,),
        pilot_authorization=auth, allow_write=True, clock=lambda: NOW, mode="fixture")
    # P14A.3: a CLI não usa mais PilotOperationStore como autoridade. O ledger
    # #70 é a única fonte operacional; JSON é apenas cache de evidências.
    db = ledger.Ledger(
        os.path.join(store_dir, "ledger.db"),
        project_id=mission.project_id, repository_id=mission.repository_id,
    )
    synthetic = adapter.SyntheticAuthorization(
        approval_reference=mission.authorization_reference,
        authorization_digest=hashlib.sha256(cc.canonical_json({
            "mission_id": mission.mission_id,
            "authorization_id": auth.authorization_id,
            "reference": mission.authorization_reference,
        }).encode("utf-8")).hexdigest(),
        consumer_id="cli-fixture-executor",
        environment="FIXTURE",
    )
    store = adapter.LedgerOperationStore(
        db, mission_id=mission.mission_id, operation_id=mission.operation_id,
        authorization_reference=mission.authorization_reference, payload=mission.payload,
        target=auth.target, clock=lambda: NOW,
        authorization_canonical=auth.canonical(),
        mission_snapshot=pilot.PilotExecutor._snapshot(mission),
        synthetic_authorization=synthetic, cache_dir=store_dir,
    )
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
            "approval": approval, "executor": executor,
            "model_recommendation": recommendation}


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
    # flags de confirmação de permissões não são evidência e são ignoradas.
    value.pop("permissions_confirmed", None)
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
    for key in ("permission_policy", "repository_source", "admission_basis", "inbox_evidence"):
        if key in fields:
            value[key] = fields[key]
    if fields.get("permission_policy") is not None:
        value["permission_policy_sha256"] = canonical_policy_sha256(fields["permission_policy"])
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


def canonical_policy_sha256(policy: Any) -> str:
    """Hash canônico da política de permissões efetivamente observada."""
    return pilot.canonical_policy_sha256(policy)


def observe_inbox(observed: dict[str, Any], payload: Optional[dict[str, Any]]) -> None:
    """Estado explícito do inbox: vazio observado ≠ ausente ≠ falha ≠ inválido ≠ ocupado."""
    result: dict[str, Any] = {
        "inbox_observed": False, "inbox_count": None,
        "state": "not_queried", "source": "opencode-api",
    }
    path = f"/api/session/{observed.get('session_id')}{pilot.INBOX_SUFFIX}"
    queried = any(call == f"GET {path}" for call in observed.get("calls", ()))
    if queried:
        kind = observed.get("status_kind")
        if kind in {"connection_error", "http_error", "unauthorized"}:
            result["state"] = "http_error"
        elif kind == "session_absent":
            result["state"] = "absent"
        elif payload is None:
            result["state"] = "absent"
        elif (not isinstance(payload, dict) or not isinstance(payload.get("data"), list)):
            result["state"] = "invalid_response"
        else:
            result["inbox_observed"] = True
            result["inbox_count"] = len(payload["data"])
            result["state"] = "empty" if result["inbox_count"] == 0 else "occupied"
    observed["inbox_observation"] = result
    observed["inbox_observed"] = result["inbox_observed"]
    observed["inbox_count"] = result["inbox_count"]


def assess_admission(observed: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
    """Admissão tri-state da sessão experimental recém-criada e de consumidor exclusivo.

    Não é heurística de disponibilidade para agentes produtivos: só é chamada
    depois de confirmada a identidade do destinatário experimental cadastrado.
    """
    basis: dict[str, Any] = {}
    blockers: list[str] = []

    def require(key: str, condition: bool, label: str) -> None:
        basis[key] = bool(condition)
        if not condition:
            blockers.append(label)

    require("authenticated_instance", observed.get("authentication_succeeded") is True
            and observed.get("service_contacted") is True, "instância não autenticada")
    require("authorized_endpoint", observed.get("endpoint") == session.get("endpoint"),
            "endpoint não autorizado")
    require("exactly_one_session", observed.get("session_count") == 1,
            "inventário não contém exatamente uma sessão")
    require("identity_complete", observed.get("identity_confirmed") is True
            and observed.get("session_identity_confirmed") is True,
            "identidade do destinatário incompleta")
    require("active_observed", observed.get("active_observed") is True,
            "active não observado")
    require("absent_from_active", observed.get("session_in_active") is False,
            "sessão presente em active")
    inbox = observed.get("inbox_observation") or {}
    require("inbox_observed_empty", inbox.get("state") == "empty",
            "inbox não observado como vazio")
    require("zero_messages_parts", observed.get("messages_observed") is True
            and observed.get("message_count") == 0 and observed.get("part_count") == 0,
            "mensagens/parts não observadas como zero")
    created = (observed.get("time") or {}).get("created")
    updated = (observed.get("time") or {}).get("updated")
    require("time_untouched", created is not None and created == updated,
            "time.created/updated divergentes ou ausentes")
    tokens = observed.get("tokens")
    cost = observed.get("cost")
    tokens_observed = (isinstance(tokens, dict)
                       and all(isinstance(tokens.get(k), (int, float))
                               and not isinstance(tokens.get(k), bool)
                               for k in ("input", "output", "reasoning")))
    # Session.Info.cost é Money.USD numérico; preços do catálogo são outro contrato.
    cost_observed = (isinstance(cost, (int, float)) and not isinstance(cost, bool))
    zero_cost_tokens = (tokens_observed and cost_observed
                        and all(tokens[k] == 0 for k in ("input", "output", "reasoning"))
                        and cost == 0)
    require("zero_cost_tokens", zero_cost_tokens,
            "tokens/custo não observados explicitamente como zero")
    require("outcome_absent", observed.get("outcome") is None,
            "outcome presente; execução anterior não excluída")
    repo = observed.get("repository_evidence") or {}
    require("repository_evidence", repo.get("confirmed") is True,
            "evidência independente do repositório insuficiente")
    require("permission_policy_resolved", bool(observed.get("permission_policy_sha256")),
            "política de permissões não observada")
    require("fresh_unexecuted", observed.get("availability_state") == "FRESH_UNEXECUTED",
            "estado não é FRESH_UNEXECUTED")
    require("model_catalog_confirmed", (observed.get("model_catalog_evidence") or {}).get("confirmed") is True,
            "catálogo do modelo não verificado")
    require("exclusive_consumer", observed.get("exclusive_consumer") is True,
            "consumidor exclusivo não comprovado")
    require("human_authorization", observed.get("human_authorization") is True,
            "autorização humana para transmissão ausente")
    require("admission_authorized", observed.get("admission_authorized") is True,
            "admissão operacional não autorizada externamente")

    if basis.get("identity_complete") and observed.get("session_in_active") is True:
        state = "busy"
    elif all(basis.get(k) for k in ("authenticated_instance", "authorized_endpoint",
                                    "exactly_one_session", "identity_complete",
                                    "active_observed", "absent_from_active",
                                    "inbox_observed_empty", "zero_messages_parts",
                                    "time_untouched", "zero_cost_tokens", "outcome_absent",
                                    "repository_evidence", "permission_policy_resolved",
                                    "fresh_unexecuted", "model_catalog_confirmed")):
        state = "new_experimental_session"
    elif basis.get("active_observed") and basis.get("absent_from_active"):
        state = "inactive_observed"
    else:
        state = "unknown"
    return {"admission_state": state, "admission_basis": basis,
            "availability_state": observed.get("availability_state", "UNKNOWN"),
            "authorized_admission": observed.get("admission_authorized") is True,
            "admission_blockers": list(dict.fromkeys(blockers))}


def _real_observe(endpoint: str, *, auth_env: Optional[str] = None,
                  session: Optional[dict[str, Any]] = None,
                  expect_instance_change: bool = False) -> dict[str, Any]:
    """GETs ordenados: cadastro/endpoint → instância → projeto → sessão → inbox."""
    canonical = pilot.canonical_endpoint(endpoint)
    seen: list[str] = []
    observed: dict[str, Any] = {
        "endpoint": canonical, "source": "declared-real", "read_only": True,
        "observed_at": cc.iso(cc.utc_now()), "expired": False,
        "connection_attempted": False, "service_contacted": False,
        "authentication_succeeded": False, "http_response_valid": False,
        "metadata_available": False, "session_found": False,
        "recipient_authorized": bool(session), "status_kind": "not_attempted",
        "http_status": None, "real_calls": 0, "calls": seen, "blockers": [],
        "fallback": "none", "instance_confirmed": False, "identity_confirmed": False,
        "session_identity_confirmed": False, "availability_confirmed": False,
        "agent_confirmed": False, "model_confirmed": False,
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
        # P14A: `source=real` só após contato efetivo com o serviço.
        observed["source"] = "real"
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
        if not expect_instance_change:
            observed["status_kind"] = "instance_divergent"
            observed["blockers"].append(
                "instância observada diverge do cadastro; nenhum GET de sessão/inbox")
            observed["real_calls"] = len(seen)
            return observed
        observed["instance_changed"] = True
        observed["blockers"].append(
            "instância mudou; revinculação exige proposta e autorização explícitas")
    else:
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
    active_data = active.get("data") if isinstance(active.get("data"), dict) else None
    observed["active_observed"] = active_data is not None
    observed["active_sessions"] = len(active_data or {})
    observed["active_session_ids"] = sorted(str(k) for k in (active_data or {}))

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
        observed["tokens"] = dict(value.get("tokens") or {})
        observed["cost"] = value.get("cost")
        observed["session_in_active"] = session_id in (active_data or {})
        model = value.get("model") or {}
        observed["session_model"] = ""
        if all(model.get(k) for k in ("providerID", "id")):
            observed["session_model"] = pilot.canonical_model_ref(
                model.get("providerID"), model.get("id"), model.get("variant"))
        observed["session_location"] = (value.get("location") or {}).get("directory")

        # J8: identidade é confirmada isoladamente de disponibilidade/permissões.
        reasons: list[str] = []
        if observed["session_id"] != session_id:
            reasons.append("session_id divergente")
        if observed["agent_id"] != session["agent_id"]:
            reasons.append("agente divergente")
        if observed["session_project_id"] != identity.opencode_project_id:
            reasons.append("projeto interno divergente")
        if observed["session_location"] != observed["location_directory"]:
            reasons.append("location da sessão divergente")
        if observed["session_model"] != session["model"]:
            reasons.append("modelo divergente")
        repository_field = observed["session_repository_id"]
        if repository_field not in (None, identity.repository_id):
            reasons.append("repositório divergente")
        observed["session_identity_confirmed"] = not reasons
        if reasons:
            observed["status_kind"] = "session_identity_divergent"
            observed["blockers"].extend(reasons)
            observed["real_calls"] = len(seen)
            return observed

        # J9: ausência de repositoryID é aceita somente com evidência Git local.
        observed["session_repository_status"] = (
            "returned" if repository_field else "not_returned")
        observed["repository_evidence"] = {
            "session_repository_id": repository_field,
            "session_repository_status": observed["session_repository_status"],
            "validated_repository_id": identity.repository_id,
            "binding": identity.repository_binding,
            "source": "session-field" if repository_field else identity.repository_source,
            "confirmed": identity.repository_id == pilot.EXPECTED_REPOSITORY_ID
                         and (identity.repository_binding is not None
                              or identity.repository_source == "indisponivel-fixture"),
        }
        observed["repository_source"] = observed["repository_evidence"]["source"]

        # J6: política é evidência observada; registry/flag nunca prova enforcement.
        policy = value.get("permissions")
        observed["permission_policy"] = policy
        observed["permission_policy_sha256"] = canonical_policy_sha256(policy)
        observed["permissions_evidence"] = {
            "returned": policy, "sha256": observed["permission_policy_sha256"],
            "documented_precedence": "last-match-wins (documentação pública; não observada em runtime)",
            "enforcement_observed": False,
            "restricted_scope_without_tools": False,
        }
        observed["model_confirmed"] = observed["session_model"] == ALLOWED_FREE_MODEL
        observed["agent_confirmed"] = observed["agent_id"] == session["agent_id"]
        observed["availability_confirmed"] = False  # nunca inferido de outcome/time

        # J7/J8: inbox observado após a identidade, antes de admissão/permissões.
        inbox = fetch(f"/api/session/{session_id}{pilot.INBOX_SUFFIX}", "inbox")
        observe_inbox(observed, inbox)
        if observed["inbox_observation"]["state"] == "occupied":
            observed["blockers"].append("inbox ocupado")

        # Evidência de zero mensagens/parts (somente contagem; conteúdo não registrado).
        messages = fetch(f"/api/session/{session_id}{pilot.MESSAGE_SUFFIX}?limit=20&order=desc",
                         "mensagens")
        if isinstance(messages, dict) and isinstance(messages.get("data"), list):
            items = messages["data"]
            observed["messages_observed"] = True
            observed["message_count"] = len(items)
            observed["part_count"] = sum(len(item.get("content") or [])
                                         if isinstance(item, dict) else 0 for item in items)
        else:
            observed["messages_observed"] = False
            observed["message_count"] = None
            observed["part_count"] = None

        # Permissões pendentes vazias não provam enforcement (J5 do adendo).
        pending = fetch(f"/api/session/{session_id}/permission", "permissões pendentes")
        pending_data = pending.get("data") if isinstance(pending, dict) else None
        observed["pending_permissions_observed"] = isinstance(pending_data, list)
        observed["pending_permission_count"] = (
            len(pending_data) if isinstance(pending_data, list) else None)
        observed["permissions_evidence"]["pending_observed"] = observed["pending_permissions_observed"]
        observed["permissions_evidence"]["pending_count"] = observed["pending_permission_count"]

        availability = pilot.classify_availability(
            active_observed=observed.get("active_observed") is True,
            target_in_active=observed.get("session_in_active") is True,
            session=observed.get("time") and {"time": observed["time"], "outcome": observed["outcome"]},
            inbox_observed=observed["inbox_observation"]["inbox_observed"],
            inbox_count=observed["inbox_observation"]["inbox_count"],
            messages_observed=observed["messages_observed"],
            message_count=observed["message_count"], part_count=observed["part_count"],
            pending_observed=observed["pending_permissions_observed"],
            pending_count=observed["pending_permission_count"])
        observed["availability_state"] = availability["state"]
        observed["availability_reasons"] = list(availability["reasons"])
        observed.update(assess_admission(observed, session))
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
    return list(dict.fromkeys(blockers))


def _manifest_readiness_blockers(observed: dict[str, Any],
                                 session: Optional[dict[str, Any]], *,
                                 for_proposal: bool = False) -> list[str]:
    """Bloqueios técnicos da proposta; autorização externa é gate separado."""
    authorization_only = {
        "admissão operacional não autorizada externamente",
        "enforcement de permissões não observado",
    }
    blockers: list[str] = []
    for key, label in (("instance_confirmed", "instância não confirmada"),
                       ("identity_confirmed", "projeto/location não confirmados"),
                       ("session_found", "sessão não encontrada"),
                       ("session_identity_confirmed", "identidade da sessão não confirmada"),
                       ("model_confirmed", "modelo gratuito não confirmado")):
        if observed.get(key) is not True:
            blockers.append(label)
    admission = observed.get("admission_state")
    allowed_admission = (("new_experimental_session", "inactive_observed")
                         if for_proposal else ("new_experimental_session",))
    if admission not in allowed_admission:
        blockers.append(f"admissão={admission or 'unknown'}; não pronta para transmissão")
    if observed.get("availability_state") != "FRESH_UNEXECUTED":
        blockers.append(f"disponibilidade={observed.get('availability_state', 'UNKNOWN')}")
    if observed.get("authorized_admission") is not True:
        blockers.append("admissão operacional não autorizada externamente")
    if (observed.get("model_catalog_evidence") or {}).get("confirmed") is not True:
        blockers.append("catálogo do modelo não verificado")
    if not observed.get("permission_policy_sha256"):
        blockers.append("hash da política de permissões ausente")
    elif observed.get("permissions_evidence", {}).get("enforcement_observed") is not True:
        blockers.append("enforcement de permissões não observado")
    inbox = observed.get("inbox_observation") or {}
    if inbox.get("state") != "empty":
        blockers.append(f"inbox {inbox.get('state', 'não observado')}")
    if observed.get("opencode_project_id") != observed.get("session_project_id"):
        blockers.append("projeto interno divergente")
    repo = observed.get("repository_evidence") or {}
    if repo.get("confirmed") is not True:
        blockers.append("evidência independente do repositório insuficiente")
    if observed.get("session_repository_id") not in (None, pilot.EXPECTED_REPOSITORY_ID):
        blockers.append("repositório declarado pela API divergente")
    if session is not None:
        for observed_key, expected_key in (("session_id", "session_id"),
                                           ("agent_id", "agent_id"),
                                           ("session_model", "model"),
                                           ("session_location", "location")):
            if observed.get(observed_key) != session.get(expected_key):
                blockers.append(f"{observed_key} divergente")
    unique = list(dict.fromkeys(blockers))
    return [item for item in unique if not (for_proposal and item in authorization_only)]


REBIND_EVIDENCE_FIELDS = ("permission_policy_sha256", "repository_source",
                          "admission_basis", "inbox_evidence")


def registry_gaps(registry: dict[str, Any]) -> list[str]:
    """Campos de evidência ausentes no cadastro: nunca preenchidos silenciosamente."""
    return [key for key in REBIND_EVIDENCE_FIELDS if not registry.get(key)]


def prepare_rebind(registry: dict[str, Any], observed: dict[str, Any],
                   *, endpoint: str) -> dict[str, Any]:
    """Prepara proposta de revinculação da MESMA sessão; nunca aplica nem cria outra."""
    endpoint_changed = registry.get("endpoint") != pilot.canonical_endpoint(endpoint)
    checks: dict[str, bool] = {
        "endpoint_authorized": observed.get("endpoint") == pilot.canonical_endpoint(endpoint),
        "endpoint_preserved_or_authorized": not endpoint_changed,
        "registry_endpoint_matches_evidence": registry.get("endpoint") == observed.get("endpoint"),
        "repository_bound": (observed.get("repository_evidence") or {}).get(
            "validated_repository_id") == registry.get("repository_id"),
        "evidence_fresh": bool(observed.get("observed_at")) and not observed.get("expired"),
        "authenticated_instance": observed.get("authentication_succeeded") is True,
        "same_session": observed.get("session_id") == registry.get("session_id"),
        "same_project": observed.get("project_id") == registry.get("project_id"),
        "same_internal_project": observed.get("session_project_id") == observed.get("opencode_project_id"),
        "same_agent": observed.get("agent_id") == registry.get("agent_id"),
        "same_model": observed.get("session_model") == registry.get("model"),
        "same_location": observed.get("session_location") == registry.get("location"),
        "repository_evidence": (observed.get("repository_evidence") or {}).get("confirmed") is True,
        "inbox_observed_empty": (observed.get("inbox_observation") or {}).get("state") == "empty",
        "no_prior_messages": observed.get("message_count") == 0
                             and observed.get("part_count") == 0,
        "permission_policy_preserved": bool(observed.get("permission_policy_sha256"))
                                       and observed.get("permission_policy_sha256")
                                       == registry.get("permission_policy_sha256"),
        "identity_confirmed": observed.get("session_identity_confirmed") is True,
    }
    previous = registry.get("instance_id")
    current = observed.get("instance_id")
    gaps = registry_gaps(registry)
    checks["registry_evidence_complete"] = not gaps
    allowed = all(checks.values())
    return {
        "command": "rebind-check", "source": "fixture", "service_contacted": False,
        "proposal_only": True, "applied": False, "requires_authorization": True,
        "blocked": not allowed,
        "checks": checks,
        "session_id": registry.get("session_id"),
        "previous_instance_id": previous, "new_instance_id": current,
        "instance_changed": previous != current,
        "registry_sha256": hashlib.sha256(
            cc.canonical_json(registry).encode("utf-8")).hexdigest(),
        "registry_gaps": gaps,
        "update_proposal": {
            "applied": False, "requires_replace": True, "requires_authorization": True,
            "preserved": {"session_id": registry.get("session_id"),
                          "project_id": registry.get("project_id"),
                          "repository_id": registry.get("repository_id"),
                          "authorization_reference": registry.get("authorization_reference"),
                          "agent_id": registry.get("agent_id"),
                          "model": registry.get("model"),
                          "location": registry.get("location")},
            "updatable": {"instance_id": current,
                          "permission_policy_sha256": observed.get("permission_policy_sha256", ""),
                          "repository_source": observed.get("repository_source", ""),
                          "admission_basis": observed.get("admission_state", "unknown"),
                          "inbox_evidence": f"{(observed.get('inbox_observation') or {}).get('state', 'unobserved')}:{(observed.get('inbox_observation') or {}).get('inbox_count')}"},
            "reason": "atualização controlada do mesmo registro; sem preenchimento silencioso",
        },
        "permission_policy_sha256": observed.get("permission_policy_sha256", ""),
        "reason": ("mesma sessão revinculável após autorização" if allowed
                   else "divergência impede revinculação; não criar outra sessão"),
    }


def refresh_admission(observed: dict[str, Any], session: Optional[dict[str, Any]]) -> None:
    """Recalcula admissão depois que catálogo/consumidor são anexados à observação."""
    if session is not None and observed.get("session_found"):
        observed.update(assess_admission(observed, session))


def apply_catalog_evidence(observed: dict[str, Any], path: Optional[str]) -> None:
    """Catálogo atual é evidência de prepare e preflight; nunca é presumido."""
    if not path:
        observed["model_catalog_evidence"] = {"confirmed": False,
                                              "reason": "catálogo não informado",
                                              "source": "model-catalog"}
        return
    try:
        age = time.time() - os.path.getmtime(path)
        if age > 1800:
            observed["model_catalog_evidence"] = {
                "confirmed": False, "reason": "catálogo desatualizado",
                "age_seconds": round(age, 1), "source": "model-catalog"}
            return
        with open(path, "rb") as handle:
            catalog = json.loads(handle.read().decode("utf-8"))
        model = observed.get("session_model", "")
        provider_id, _, rest = model.partition("/")
        model_id, _, variant = rest.partition("#")
        observed["model_catalog_evidence"] = pilot.verify_model_catalog(
            catalog, provider_id=provider_id, model_id=model_id,
            variant=variant or "none")
    except (OSError, ValueError, UnicodeError) as e:
        observed["model_catalog_evidence"] = {
            "confirmed": False, "reason": f"catálogo inválido: {type(e).__name__}",
            "source": "model-catalog"}


def fixture_model_recommendation():
    """Decisão #71 vinculante: a mesma recomendação alimenta evidência e despacho."""
    observed = cc.iso(NOW)
    inventory = {
        "source": "fixture-policy", "observed_at": observed,
        "models": [{
            "provider_id": "opencode", "id": "hy4-flash-free", "variant": "default",
            "context_window_tokens": 1_000_000, "tools": True,
            "modalities": ["text"], "capabilities": ["reasoning"],
            "cost": {"category": "free", "source": "fixture", "observed_at": observed},
        }],
    }
    mission_value = {
        "mission_id": "MISS-COORD04-PILOT-001", "state": "PLANNED",
        "scope": "piloto de comunicação experimental", "requirements": {},
    }
    availability = [{
        "provider_id": "opencode", "model_id": "hy4-flash-free", "variant": "default",
        "status": "available", "observed_at": observed, "source": "fixture",
    }]
    return adapter.select_with_policy(inventory, mission_value, None, availability, (), NOW)


def selected_model_for_dispatch(result) -> str:
    """ModelRef #71 é vinculante ao autorização/transporte, não só evidência."""
    selected = result.selected
    if selected is None or (result.requires_authorization and not result.authorized):
        raise cc.AuthorizationError("política #71 não autorizou modelo para despacho")
    if selected.variant not in (None, "none", "default"):
        raise cc.AuthorizationError("variante selecionada não é a autorizada")
    return pilot.canonical_model_ref(selected.provider_id, selected.model_id, None)


def fixture_model_policy_evidence() -> dict[str, Any]:
    return adapter.model_evidence(fixture_model_recommendation())


def external_approval_binding(proposal: dict[str, Any], *, approver_id: str,
                              reference: str) -> dict[str, Any]:
    """Vincula aprovação externa ao digest da proposta, sem alterar seu material."""
    if proposal.get("approved") is not False or proposal.get("environment", "PROPOSED") != "PROPOSED":
        raise ValueError("somente proposta não autorizada pode ser vinculada")
    material = {k: v for k, v in proposal.items()
                if k not in ("approved", "canonical_sha256")}
    digest = hashlib.sha256(cc.canonical_json(material).encode("utf-8")).hexdigest()
    if digest != proposal.get("canonical_sha256"):
        raise ValueError("material da proposta alterado; aprovação inválida")
    return {"approver_id": approver_id, "approver_type": "human",
            "environment": "APPROVED", "approval_reference": reference,
            "manifest_sha256": digest, "bound_material_sha256": digest}


def _parse(argv: list[str]) -> tuple[str, dict[str, Any]]:
    options: dict[str, Any] = {"store_dir": pilot.DEFAULT_PILOT_DIR,
                               "endpoint": DEFAULT_ENDPOINT, "activate": False,
                               "approval": None, "real": False, "session": None,
                               "auth_env": None, "replace": False, "evidence": None,
                               "catalog": None, "consumer_evidence": None,
                               "policy_evidence": None, "permission_policy": None,
                               "instance_id": None, "agent_id": None,
                               "authorization_reference": None,
                               "target": None, "reason": None}
    command = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in {"--store-dir", "--endpoint", "--approval", "--session", "--auth-env",
                   "--instance-id", "--agent-id", "--authorization-reference", "--evidence",
                   "--catalog", "--consumer-evidence", "--policy-evidence",
                   "--permission-policy", "--target", "--reason"}:
            index += 1
            if index >= len(argv):
                raise ValueError(f"{arg} sem valor")
            options[arg[2:].replace("-", "_")] = argv[index]
        elif arg == "--activate":
            options["activate"] = True
        elif arg == "--replace":
            options["replace"] = True
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
        return {"command": "prepare", "mode": "real",
                "source": observed.get("source", "declared-real"),
                "approved": False, "environment": "PROPOSED", "blocked": True,
                "blockers": [f"cadastro inválido: {e}"],
                "reason": "manifesto não emitido"}
    result: dict[str, Any] = {
        "command": "prepare", "mode": "real",
        "source": observed.get("source", "declared-real"),
        "service_contacted": observed["service_contacted"], "read_only": True,
        "real_calls": observed["real_calls"], "observed": observed,
        "approved": False, "environment": "PROPOSED",
        "notice": "proposta não autorizada; nenhuma PilotApproval humana é emitida pela CLI",
    }
    blockers = _observe_blockers(observed)
    blockers.extend(_manifest_readiness_blockers(observed, session, for_proposal=True))
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
        approval_reference=session["authorization_reference"], project_id=project_id,
        repository_id=repository_id, instance_id=observed["instance_id"], target=target,
        mission_id=mission.mission_id, mission_identity=cc.canonical_mission_identity(mission),
        operation_id=mission.operation_id, prompt_sha256=prompt_sha256, scope=mission.scope,
        issued_at=issued_at, expires_at=expires_at, approved=False,
        environment="PROPOSED", max_transmissions=1, expected_model=session["model"],
        endpoint=observed["endpoint"])
    inbox = observed.get("inbox_observation") or {}
    exclusive_evidence = observed.get("exclusive_consumer_evidence") or {}
    manifest = pilot.build_runtime_manifest(
        mission, authorization, prompt_sha256, observed["endpoint"],
        permission_policy_sha256=observed.get("permission_policy_sha256", ""),
        admission_basis=observed.get("admission_state", "unknown"),
        inbox_evidence=f"{inbox.get('state', 'unobserved')}:{inbox.get('inbox_count')}",
        repository_source=observed.get("repository_source", "git-local"),
        admission_authorized=False,  # proposta nunca é autorização externa
        exclusive_consumer=exclusive_evidence.get("verified") is True,
        exclusive_consumer_evidence_sha256=pilot.consumer_evidence_sha256(
            exclusive_evidence),
        session_cost=(observed.get("cost") if isinstance(observed.get("cost"), (int, float))
                      and not isinstance(observed.get("cost"), bool) else None))
    result.update({
        "manifest": manifest.proposal(), "authorization": authorization.proposal(),
        "mission": {**{k: (cc.iso(v) if isinstance(v, datetime) else v)
                        for k, v in vars(mission).items()}},
        "core_authorization": {
            "reference": authorization.approval_reference,
            "project_id": project_id, "repository_id": repository_id,
            "target": list(target.key()), "payload_sha256": mission.payload_sha256,
            "mission_identity": cc.canonical_mission_identity(mission), "scope": mission.scope,
            "issued_at": cc.iso(issued_at), "expires_at": cc.iso(expires_at),
            "synthetic": False},
        "created_at": cc.iso(created_at), "issued_at": cc.iso(issued_at),
        "expires_at": cc.iso(expires_at),
        "opencode_project_id": observed.get("opencode_project_id"),
        "repository_source": observed.get("repository_source", "git-local"),
        "permission_policy_sha256": observed.get("permission_policy_sha256", ""),
        "admission_basis": observed.get("admission_state", "unknown"),
        "inbox_evidence": f"{inbox.get('state', 'unobserved')}:{inbox.get('inbox_count')}",
        "assumptions": ["maintainer_id e approval_reference dependem de decisão humana"],
        "limits": ["HEAD e digests recalculados no momento desta proposta",
                   "alterar arquivos operacionais invalida o manifesto"],
    })
    return result


def run(argv: list[str]) -> dict[str, Any]:
    """Executa uma operação de controle; nunca faz POST real."""
    command, options = _parse(argv)
    # Nenhuma autoridade JSON é criada pela CLI. Comandos que leem estado
    # usam o ledger via adaptador; os demais nunca criam store.
    store = None
    mode = "real" if options["real"] else "fixture"
    source = "real" if options["real"] else "fixture"
    needs_session = options["real"] or command in ("permission-probe-plan", "rebind-check")
    try:
        session = _resolve_session(options["store_dir"], options["session"]) if needs_session else None
    except ValueError as e:
        return {"command": command, "mode": "real", "source": "declared-real",
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
                "permission_policy": (json.loads(options["permission_policy"])
                                      if options["permission_policy"] else None),
            }, replace=options["replace"])
        except (ValueError, cc.ContractError) as e:
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "registered": False, "reason": str(e)}
        return {"command": command, "source": "fixture", "service_contacted": False,
                "registered": True, "session": registered["session_id"],
                "endpoint": registered["endpoint"],
                "notice": "cadastro local; nenhuma sessão real foi criada"}

    if command == "permission-probe-plan":
        if session is None:
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "blocked": True, "reason": "destinatário experimental cadastrado obrigatório"}
        plan = build_permission_probe_plan(
            session_id=session["session_id"],
            policy_sha256=session.get("permission_policy_sha256", ""))
        if not options["policy_evidence"]:
            plan["blocked"] = True
            plan["reason"] = "evidência observada da política obrigatória"
            return plan
        try:
            with open(options["policy_evidence"], "rb") as handle:
                evidence = json.loads(handle.read().decode("utf-8"))
            observed_policy = evidence["policy"]
            observed_digest = pilot.canonical_policy_sha256(observed_policy)
        except (OSError, ValueError, UnicodeError, KeyError) as e:
            plan["blocked"] = True
            plan["policy_preserved"] = False
            plan["reason"] = f"evidência de política inválida: {type(e).__name__}"
            return plan
        plan["policy_preserved"] = (
            observed_digest == session.get("permission_policy_sha256"))
        plan["observed_policy_sha256"] = observed_digest
        if not plan["policy_preserved"]:
            plan["blocked"] = True
            plan["reason"] = "política observada difere do hash registrado"
        return plan

    if command == "rebind-check":
        if not options["evidence"] or session is None:
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "blocked": True, "applied": False,
                    "reason": "--evidence e destinatário cadastrado são obrigatórios"}
        try:
            with open(options["evidence"], "rb") as handle:
                evidence = json.loads(handle.read().decode("utf-8"))
        except (OSError, ValueError, UnicodeError) as e:
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "blocked": True, "applied": False,
                    "reason": f"evidência inválida: {type(e).__name__}"}
        try:
            seen = datetime.fromisoformat(str(evidence["observed_at"]).replace("Z", "+00:00"))
        except (KeyError, ValueError):
            return {"command": command, "source": "fixture", "service_contacted": False,
                    "blocked": True, "applied": False,
                    "reason": "evidência sem observed_at válido"}
        evidence["expired"] = cc.utc_now() - seen > timedelta(minutes=30)
        proposal = prepare_rebind(session, evidence, endpoint=options["endpoint"])
        if session.get("endpoint") != pilot.canonical_endpoint(options["endpoint"]):
            proposal["blocked"] = True
            proposal["endpoint_change_requires_explicit_authorization"] = True
            proposal["reason"] = "mudança de endpoint exige autorização expressa"
        return proposal

    if command == "preflight":
        if options["real"]:
            observed = _real_observe(options["endpoint"], auth_env=options["auth_env"],
                                     session=session)
            apply_catalog_evidence(observed, options["catalog"])
            refresh_admission(observed, session)
            return {"command": command, "mode": "real",
                    "source": observed.get("source", "declared-real"),
                    "read_only": True, "service_contacted": observed["service_contacted"],
                    "connection_attempted": observed["connection_attempted"],
                    "authentication_succeeded": observed["authentication_succeeded"],
                    "http_response_valid": observed["http_response_valid"],
                    "metadata_available": observed["metadata_available"],
                    "session_found": observed["session_found"],
                    "recipient_authorized": observed["recipient_authorized"],
                    "status_kind": observed["status_kind"], "http_status": observed["http_status"],
                    "availability_state": observed.get("availability_state", "UNKNOWN"),
                    "availability_reasons": observed.get("availability_reasons", []),
                    "model_catalog_evidence": observed.get("model_catalog_evidence", {}),
                    "admission_state": observed.get("admission_state", "unknown"),
                    "admission_basis": observed.get("admission_basis", {}),
                    "admission_blockers": observed.get("admission_blockers", []),
                    "permission_policy_sha256": observed.get("permission_policy_sha256", ""),
                    "repository_source": observed.get("repository_source", ""),
                    "inbox_observation": observed.get("inbox_observation", {}),
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
            apply_catalog_evidence(observed, options["catalog"])
            if options["consumer_evidence"]:
                observed["exclusive_consumer_evidence"] = pilot.verify_consumer_evidence(
                    options["consumer_evidence"],
                    session_id=(session or {}).get("session_id", ""),
                    endpoint=observed["endpoint"], clock=cc.utc_now(),
                    authorization_reference=(session or {}).get("authorization_reference"))
            refresh_admission(observed, session)
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

    if command in {"reconcile", "closeout"}:
        store = _fixture_context(store_dir=options["store_dir"])["store"]

    if command == "reconcile":
        record = store.load()
        if record is None:
            return {"command": command, "mode": mode, "source": source,
                    "service_contacted": False, "read_only": True, "real_calls": 0,
                    "state": None, "changed": False}
        # Environment vem da autorização sintética explícita, nunca do cache.
        if store.fixture_environment() is None:
            return {"command": command, "mode": mode, "source": source,
                    "service_contacted": False, "read_only": True, "real_calls": 0,
                    "state": record.state, "changed": False,
                    "reason": "registro não-fixture; reconciliação real exige P3B"}
        if record.state == ledger.UNKNOWN:
            target = options["target"]
            if target not in {ledger.COMPLETED, ledger.FAILED}:
                return {"command": command, "mode": mode, "source": source,
                        "read_only": True, "real_calls": 0, "state": record.state,
                        "changed": False,
                        "reason": "UNKNOWN exige --target COMPLETED|FAILED ou evidência"}
            try:
                resolved = store.reconcile_unknown(
                    target, evidence=options["reason"] or "reconciliação CLI explícita")
            except (ledger.LedgerError, cc.AuthorizationError) as e:
                return {"command": command, "mode": mode, "source": source,
                        "read_only": True, "real_calls": 0, "state": record.state,
                        "changed": False, "reason": f"reconciliação bloqueada: {e}"}
            return {"command": command, "mode": mode, "source": source,
                    "read_only": True, "real_calls": 0, "state": resolved.state,
                    "changed": True, "from": ledger.UNKNOWN, "target": target}
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
                "registered": len(context["store"].load().history),
                "model_policy": fixture_model_policy_evidence()}
    raise ValueError(f"comando não suportado: {command}")


def main(argv: list[str] | None = None) -> int:
    try:
        result = run(list(sys.argv[1:] if argv is None else argv))
    except (ValueError, ledger.LedgerError) as e:
        # Nenhum traceback para falha de reserva/concorrência: erro controlado.
        print(f"erro: {e}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, ensure_ascii=False, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
