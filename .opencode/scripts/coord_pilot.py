#!/usr/bin/env python3
"""Adaptador OpenCode restrito e persistência mínima para o piloto COORD-04/P3.

Inativo por padrão: nenhum cliente real ou escrita HTTP é criado sem ativação
explícita. Esta versão prepara o circuito; não transmite nesta missão.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from abc import ABC, abstractmethod
from contextlib import contextmanager
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coord_checkpoint as coord_checkpoint
import coord_communication as cc

INFO_PATH = "/api/info"
LOCATION_PATH = "/api/location"
ACTIVE_PATH = "/api/session/active"
INBOX_SUFFIX = "/inbox"
PROMPT_SUFFIX = "/prompt"
MESSAGE_SUFFIX = "/message"
DEFAULT_PILOT_DIR = os.path.expanduser("~/.local/state/better-talker/coord-pilot")
# Fonte única e canônica do endpoint local autorizado (P3B.0).
DEFAULT_ENDPOINT = "http://127.0.0.1:49999"
# Identidade lógica verificada (P3B.2): não derivada de project.id nem do diretório.
EXPECTED_PROJECT_ID = "better-talker"
EXPECTED_REPOSITORY_ID = "guilhermemellojw/better-talker"
EXPECTED_CANONICAL_DIR = "/home/guilherme/Documentos/better-talker"
EXPERIMENTAL_WORKTREE = "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"
# Modelo experimental aprovado pelo mantenedor (substitui hungerg-free).
EXPERIMENTAL_MODEL = "opencode/hy4-flash-free"
# Estados de disponibilidade/admissão do contrato unificado (P10 adendo).
AVAILABILITY_STATES = ("FRESH_UNEXECUTED", "IDLE_TERMINAL", "BUSY", "UNKNOWN")
OUTCOME_VALUES = ("succeeded", "failed", "interrupted")


def verify_model_catalog(models: Any, *, provider_id: str, model_id: str,
                         variant: str, authorized_model: str = EXPERIMENTAL_MODEL) -> dict[str, Any]:
    """Verifica o catálogo atual sem escolher modelos: identidade, variante e custo."""
    rows = models.get("data") if isinstance(models, dict) else None
    if not isinstance(rows, list):
        return {"confirmed": False, "reason": "catálogo inválido", "source": "model-catalog"}
    match = None
    for row in rows:
        if (isinstance(row, dict) and row.get("providerID") == provider_id
                and row.get("id") == model_id):
            match = row
            break
    if match is None:
        return {"confirmed": False, "reason": "modelo ausente do catálogo", "source": "model-catalog"}
    canonical = canonical_model_ref(provider_id, model_id, variant)
    variants = {item.get("id") for item in (match.get("variants") or []) if isinstance(item, dict)}
    costs = match.get("cost") or []
    known_costs = isinstance(costs, list) and all(
        isinstance(item, dict) and all(item.get(k) is not None
                                       for k in ("input", "output")) for item in costs)
    zero_cost = known_costs and all(
        (item.get("input") or 0) == 0 and (item.get("output") or 0) == 0
        and all((item.get("cache") or {}).get(k, 0) == 0 for k in ("read", "write"))
        for item in costs)
    reasons = []
    if canonical != authorized_model:
        reasons.append("modelo canônico divergente do autorizado")
    if variant not in variants:
        reasons.append("variante autorizada ausente do catálogo")
    if not known_costs or not zero_cost:
        reasons.append("custo desconhecido ou divergente da gratuidade autorizada")
    if not match.get("active", True) or match.get("enabled") is False:
        reasons.append("modelo indisponível no catálogo")
    return {"confirmed": not reasons, "reasons": tuple(dict.fromkeys(reasons)),
            "provider_id": provider_id, "model_id": model_id, "variant": variant,
            "canonical": canonical, "source": "model-catalog"}


def classify_availability(*, active_observed: bool, target_in_active: bool,
                          session: Optional[dict[str, Any]] = None,
                          inbox_observed: bool = False, inbox_count: Optional[int] = None,
                          messages_observed: bool = False, message_count: Optional[int] = None,
                          part_count: Optional[int] = None,
                          pending_observed: bool = False, pending_count: Optional[int] = None,
                          idle_evidence: bool = False) -> dict[str, Any]:
    """Classificação conservadora; ausência de evidência nunca é resposta vazia."""
    reasons: list[str] = []
    session = session or {}
    times = session.get("time") or {}
    busy, idle = times.get("busy"), times.get("idle")
    if target_in_active:
        return {"state": "BUSY", "reasons": ("sessão presente em active",)}
    if (isinstance(busy, (int, float)) and isinstance(idle, (int, float)) and busy > idle):
        return {"state": "BUSY", "reasons": ("busy > idle",)}
    if not active_observed:
        reasons.append("active não observado")
    if not inbox_observed or inbox_count is None:
        reasons.append("inbox não observado como lista")
    elif inbox_count != 0:
        reasons.append("inbox ocupado")
    if not messages_observed or message_count is None or part_count is None:
        reasons.append("mensagens/parts não observados")
    elif message_count or part_count:
        reasons.append("mensagens/parts presentes")
    if not pending_observed or pending_count is None:
        reasons.append("permissões pendentes não observadas")
    elif pending_count:
        reasons.append("permissão pendente")
    if target_in_active:
        reasons.append("sessão presente em active")
    session = session or {}
    times = session.get("time") or {}
    busy, idle = times.get("busy"), times.get("idle")
    created, updated = times.get("created"), times.get("updated")
    if busy is not None and idle is not None and isinstance(busy, (int, float)) \
            and isinstance(idle, (int, float)) and busy > idle:
        reasons.append("busy > idle")
    outcome = session.get("outcome")
    if outcome is not None and outcome not in OUTCOME_VALUES:
        reasons.append("outcome fora do enum documentado")
    if reasons:
        return {"state": "UNKNOWN", "reasons": tuple(dict.fromkeys(reasons))}
    fresh = (created is not None and created == updated and outcome is None
             and busy is None and idle is None and idle_evidence is False)
    if fresh:
        return {"state": "FRESH_UNEXECUTED", "reasons": ()}
    if idle_evidence and outcome in OUTCOME_VALUES:
        return {"state": "IDLE_TERMINAL", "reasons": ()}
    return {"state": "UNKNOWN", "reasons": ("evidência insuficiente para estado terminal",)}


def canonical_policy_sha256(policy: Any) -> str:
    if policy is None:
        return ""
    return hashlib.sha256(cc.canonical_json(policy).encode("utf-8")).hexdigest()


def canonical_model_ref(provider_id: Optional[str], model_id: Optional[str],
                        variant: Optional[str] = None) -> str:
    """Representação canônica observada em `Model.Ref` (id + providerID).

    A API pode devolver `variant="none"`; isso não acrescenta sufixo. Variantes
    explícitas diferentes de ``none`` são preservadas. ``default`` é mantido
    como sufixo explícito quando informado, sem ser presumido pela ausência.
    """
    if not provider_id or not model_id:
        return ""
    base = f"{provider_id}/{model_id}"
    if variant and variant != "none":
        return f"{base}#{variant}"
    return base
PILOT_TRANSITIONS = {
    "AUTHORIZED": {"DISPATCHING", "UNKNOWN", "FAILED"},
    "DISPATCHING": {"UNKNOWN", "FAILED", "DELIVERED", "RUNNING", "COMPLETED"},
    "DELIVERED": {"RUNNING", "FAILED", "UNKNOWN"},
    "RUNNING": {"COMPLETED", "FAILED", "UNKNOWN"},
    "UNKNOWN": {"RUNNING", "COMPLETED", "FAILED"},
    "COMPLETED": set(),
    "FAILED": set(),
}
ALLOWED_METHODS = {"GET", "POST"}
ALLOWED_PATH_PREFIXES = ("/api/",)


def canonical_endpoint(value: str, *, allowed_port: Optional[int] = None) -> str:
    """Fonte única do endpoint: HTTP, 127.0.0.1, porta autorizada, sem excessos.

    Rejeita userinfo, query, fragmento, caminho e porta divergente. O valor
    retornado é sempre ``http://127.0.0.1:<porta>`` e é o único aceito pelo
    manifesto, pela autorização e pela operação.
    """
    if not isinstance(value, str) or not value:
        raise cc.ContractError("endpoint ausente")
    try:
        parsed = urllib.parse.urlparse(value)
    except ValueError as e:
        raise cc.ContractError("endpoint inválido") from e
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.username
            or parsed.password or parsed.fragment or parsed.query or parsed.params):
        raise cc.ContractError("endpoint inválido: exige http://127.0.0.1 sem userinfo/query/fragmento")
    try:
        port = parsed.port
    except ValueError as e:
        raise cc.ContractError("porta inválida") from e
    if port is None or not 1 <= port <= 65535:
        raise cc.ContractError("porta inválida")
    if allowed_port is not None and port != allowed_port:
        raise cc.ContractError("porta não autorizada")
    if parsed.path not in {"", "/"}:
        raise cc.ContractError("endpoint não pode conter caminho")
    return f"http://127.0.0.1:{port}"


def endpoint_port(endpoint: str) -> int:
    """Porta de um endpoint já canônico (para construir o cliente real)."""
    port = urllib.parse.urlparse(canonical_endpoint(endpoint)).port
    if port is None:
        raise cc.ContractError("endpoint sem porta")
    return port


def client_endpoint(client: "HttpClient") -> str:
    """Endpoint efetivo exposto pelo cliente; nunca informado pelo chamador."""
    raw = getattr(client, "endpoint", None)
    if raw is None:
        raw = getattr(client, "base_url", None)
    if not isinstance(raw, str):
        raise cc.ContractError("cliente não expõe endpoint efetivo")
    return canonical_endpoint(raw)

# Prompt único publicado em PILOT.md; compartilhado por executor, CLI e fixtures.
PILOT_PROMPT = (
    "MISS-COORD04-PILOT-001 / OP-COORD04-PILOT-001. Responda somente em texto "
    "estruturado, sem ferramentas, sem edição, sem shell, sem Git, sem operações em "
    "dispositivos e sem acesso a credenciais. Inclua o marcador <!-- BT-COORD-REPORT:v1 --> "
    "e uma linha contract: com JSON contendo schema_version, project_id, mission_id, "
    "operation_id, agent_id, session_id, status, head_sha null, branch null, "
    "test_evidence [], blockers [], artifacts [], completed_at e acceptance_results."
)
OPERATIONAL_FILES = ("coord_communication.py", "coord_checkpoint.py", "coord_pilot.py",
                     "coord_pilot_cli.py")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))


def operational_file_digests() -> dict[str, str]:
    """Digests recalculados dos arquivos realmente executados, não informados pelo chamador."""
    result: dict[str, str] = {}
    for name in OPERATIONAL_FILES:
        path = os.path.join(SCRIPT_DIR, name)
        if not os.path.isfile(path):
            raise cc.ContractError(f"arquivo operacional ausente: {name}")
        with open(path, "rb") as handle:
            result[name] = hashlib.sha256(handle.read()).hexdigest()
    return result


def executed_head_sha() -> str:
    """HEAD do repositório onde o código está sendo executado (comando git exato)."""
    import subprocess
    try:
        completed = subprocess.run(["git", "-C", REPO_ROOT, "rev-parse", "HEAD"],
                                   capture_output=True, text=True, check=False, timeout=10)
    except (OSError, subprocess.SubprocessError) as e:
        raise cc.ContractError("HEAD executado indisponível") from e
    value = completed.stdout.strip() if completed.returncode == 0 else ""
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise cc.ContractError("HEAD executado inválido")
    return value


class HttpClient(ABC):
    """Cliente mínimo; implementações reais são injetadas e inativas por padrão."""

    @abstractmethod
    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        raise NotImplementedError


class DisabledHttpClient(HttpClient):
    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        raise cc.TransportDisabled("cliente HTTP real desativado")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "redirecionamento proibido", headers, fp)


class ExperimentalAuth:
    """Credencial experimental fornecida explicitamente pelo operador.

    O segredo nunca é armazenado em atributo, log, manifesto ou comando: apenas
    um provedor (callable) é injetado. A credencial só é emitida para o endpoint
    privado ao qual este objeto foi vinculado.
    """

    def __init__(self, endpoint: str, *, password_provider):
        self.endpoint = canonical_endpoint(endpoint)
        if not callable(password_provider):
            raise cc.ContractError("provedor de credencial inválido")
        self._provider = password_provider

    def header(self, endpoint: str) -> dict[str, str]:
        if canonical_endpoint(endpoint) != self.endpoint:
            raise cc.ContractError("credencial não pode ser reutilizada em endpoint divergente")
        value = self._provider()
        if not isinstance(value, str) or not value:
            raise cc.AuthorizationError("credencial experimental ausente")
        token = base64.b64encode(f"opencode:{value}".encode("utf-8")).decode("ascii")
        return {"Authorization": f"Basic {token}"}


def repository_identity_from_git(root: Optional[str] = None) -> str:
    """Repository ID lido de metadados Git locais e validado pela política explícita."""
    import subprocess
    try:
        completed = subprocess.run(
            ["git", "-C", root or REPO_ROOT, "remote", "get-url", "origin"],
            capture_output=True, text=True, check=False, timeout=10)
    except (OSError, subprocess.SubprocessError) as e:
        raise cc.ContractError("metadados Git indisponíveis") from e
    url = completed.stdout.strip() if completed.returncode == 0 else ""
    repository_id = ""
    if url.startswith("git@github.com:"):
        repository_id = url.split(":", 1)[1].strip()
    else:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme in {"http", "https"} and parsed.hostname == "github.com":
            if parsed.username or parsed.password:
                raise cc.ContractError("URL do repositório contém credencial")
            repository_id = parsed.path.strip("/")
    if repository_id.endswith(".git"):
        repository_id = repository_id[:-4]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository_id):
        raise cc.ContractError("repositório GitHub inválido nos metadados Git")
    if repository_id != EXPECTED_REPOSITORY_ID:
        raise cc.ContractError("repositório divergente da política do Better Talker")
    return repository_id


@dataclass(frozen=True)
class ObservedProjectIdentity:
    """Identidades distintas observadas/validadas para o mesmo destino."""

    project_id: str          # identidade lógica do Better Talker (política)
    opencode_project_id: str # ID interno do OpenCode (hash), nunca comparado ao lógico
    repository_id: str       # identidade do repositório GitHub (metadados Git locais)
    project_canonical: str
    location: str
    repository_source: str = "git-local"
    repository_binding: Optional[dict[str, Any]] = None


def git_binding_evidence(root: Optional[str] = None) -> dict[str, Any]:
    """Vínculo Git independente: canonical + worktree + common-dir + origin + projeto."""
    import subprocess
    directory = root or EXPERIMENTAL_WORKTREE
    try:
        completed = subprocess.run(
            ["git", "-C", directory, "rev-parse", "--git-common-dir"],
            capture_output=True, text=True, check=False, timeout=10)
    except (OSError, subprocess.SubprocessError) as e:
        raise cc.ContractError("common-dir Git indisponível") from e
    raw = completed.stdout.strip() if completed.returncode == 0 else ""
    if not raw:
        raise cc.ContractError("common-dir Git ausente")
    common_dir = raw if os.path.isabs(raw) else os.path.join(directory, raw)
    expected = os.path.join(EXPECTED_CANONICAL_DIR, ".git")
    if os.path.realpath(common_dir) != os.path.realpath(expected):
        raise cc.ContractError("common-dir Git divergente do repositório canônico")
    repository_id = repository_identity_from_git(root=directory)
    return {
        "canonical": EXPECTED_CANONICAL_DIR,
        "worktree": directory,
        "common_dir": os.path.realpath(common_dir),
        "origin_repository_id": repository_id,
        "project_id": EXPECTED_PROJECT_ID,
        "repository_id": repository_id,
        "source": "git-local",
    }


def validate_observed_identity(location: dict[str, Any], *, project_id: str,
                               expected_location: str = EXPERIMENTAL_WORKTREE,
                               expected_canonical: str = EXPECTED_CANONICAL_DIR,
                               repository_id: Optional[str] = None) -> ObservedProjectIdentity:
    """Validação compartilhada: preflight, manifesto e confirmação de destino."""
    if not isinstance(location, dict):
        raise cc.ContractError("location observada inválida")
    directory = location.get("directory")
    if directory != expected_location:
        raise cc.ContractError("worktree experimental divergente")
    project = location.get("project") or {}
    internal = project.get("id")
    if not isinstance(internal, str) or not internal:
        raise cc.ContractError("ID interno do OpenCode ausente")
    allowed_canonical = ((expected_canonical,) if isinstance(expected_canonical, str)
                         else tuple(expected_canonical))
    if project.get("canonical") not in allowed_canonical:
        raise cc.ContractError("diretório canônico divergente")
    expected_repository = repository_id or EXPECTED_REPOSITORY_ID
    binding: Optional[dict[str, Any]] = None
    try:
        binding = git_binding_evidence(root=directory)
        observed_repository = binding["repository_id"]
        repository_source = "git-local"
    except cc.ContractError:
        # Somente em fixtures sintéticas (fora da worktree experimental) o
        # vínculo não é derivável; na worktree real isso é bloqueante.
        if expected_location == EXPERIMENTAL_WORKTREE:
            raise
        observed_repository = expected_repository
        repository_source = "indisponivel-fixture"
    if observed_repository != expected_repository:
        raise cc.ContractError("repositório Git divergente da política")
    return ObservedProjectIdentity(
        project_id=project_id, opencode_project_id=internal,
        repository_id=observed_repository, project_canonical=project["canonical"],
        location=directory, repository_source=repository_source,
        repository_binding=binding)


class LocalHttpClient(HttpClient):
    """Cliente opcional, restrito a base local fixa e caminhos /api/."""

    def __init__(self, base_url: str, *, allowed_port: int, allow_write: bool = False,
                 auth: Optional[ExperimentalAuth] = None):
        self._endpoint = canonical_endpoint(base_url, allowed_port=allowed_port)
        self._allowed_port = allowed_port
        self.allow_write = allow_write
        if auth is not None and auth.endpoint != self._endpoint:
            raise cc.ContractError("credencial divergente do endpoint do cliente")
        self.auth = auth
        # Proxies externos desativados e redirecionamentos proibidos.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)

    @property
    def endpoint(self) -> str:
        """Endpoint efetivo: única fonte validada, sem atributo mutável duplicado."""
        return self._endpoint

    @property
    def base_url(self) -> str:
        """Alias somente leitura do endpoint efetivo."""
        return self._endpoint

    def _effective_endpoint(self) -> str:
        """Revalida a fonte interna imediatamente antes de qualquer uso."""
        return canonical_endpoint(self._endpoint, allowed_port=self._allowed_port)

    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        # A validação precede até mesmo a consulta ao provedor de senha.
        endpoint = self._effective_endpoint()
        if self.auth is not None and self.auth.endpoint != endpoint:
            raise cc.ContractError("credencial não pode ser usada neste endpoint")
        method = method.upper()
        if method not in ALLOWED_METHODS:
            raise cc.ContractError("método HTTP não permitido")
        if not path.startswith(ALLOWED_PATH_PREFIXES):
            raise cc.ContractError("caminho HTTP não permitido")
        if method == "POST" and not self.allow_write:
            raise cc.TransportDisabled("escrita HTTP desativada")
        data = None
        headers = {"Accept": "application/json"}
        if self.auth is not None:
            # Nunca registrar o valor do cabeçalho; apenas anexá-lo à requisição.
            headers.update(self.auth.header(endpoint))
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(endpoint + path, data=data,
                                         headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=10) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as e:
            raw = e.read()
            status = e.code
        text = raw.decode("utf-8", errors="replace").strip()
        payload = json.loads(text) if text else {}
        if not isinstance(payload, dict):
            raise cc.ContractError("resposta HTTP inválida")
        return status, payload


class ReadOnlyHttpClient(LocalHttpClient):
    """Cliente real somente leitura: POST é bloqueado mecanicamente."""

    def __init__(self, base_url: str, *, allowed_port: int,
                 auth: Optional[ExperimentalAuth] = None):
        super().__init__(base_url, allowed_port=allowed_port, allow_write=False, auth=auth)

    def request(self, method: str, path: str, body: Optional[dict[str, Any]] = None) -> tuple[int, dict[str, Any]]:
        if method.upper() == "POST":
            raise cc.TransportDisabled("cliente real somente leitura: POST proibido")
        return super().request(method, path, body)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def instance_identity(info: dict[str, Any]) -> str:
    material = {"version": info.get("version"), "pid": info.get("pid"),
                "urls": info.get("urls"), "hostname": info.get("hostname")}
    return hashlib.sha256(cc.canonical_json(material).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class PilotAuthorization:
    """Contrato de autorização experimental; não é emitida nesta missão."""

    authorization_id: str
    maintainer_id: str
    approval_reference: str
    project_id: str
    repository_id: str
    instance_id: str
    target: cc.TargetIdentity
    mission_id: str
    mission_identity: str
    operation_id: str
    prompt_sha256: str
    scope: str
    issued_at: datetime
    expires_at: datetime
    approved: bool = False
    environment: str = "PROPOSED"
    max_transmissions: int = 1
    expected_model: str = EXPERIMENTAL_MODEL
    endpoint: str = DEFAULT_ENDPOINT

    def __post_init__(self) -> None:
        object.__setattr__(self, "endpoint", canonical_endpoint(self.endpoint))

    def validate(self) -> None:
        self.target.validate()
        for value, label in ((self.authorization_id, "authorization_id"),
                             (self.maintainer_id, "maintainer_id"),
                             (self.approval_reference, "approval_reference"),
                             (self.project_id, "project_id"),
                             (self.repository_id, "repository_id"),
                             (self.instance_id, "instance_id"),
                             (self.mission_id, "mission_id"),
                             (self.operation_id, "operation_id"),
                             (self.scope, "scope"),
                             (self.expected_model, "expected_model")):
            if not isinstance(value, str) or not value.strip():
                raise cc.ContractError(f"{label} ausente")
        if not cc.SHA256_PATTERN.fullmatch(self.prompt_sha256):
            raise cc.ContractError("hash do prompt inválido")
        if not cc.SHA256_PATTERN.fullmatch(self.mission_identity):
            raise cc.ContractError("identidade da missão inválida")
        if self.endpoint != canonical_endpoint(self.endpoint):
            raise cc.ContractError("endpoint divergente na autorização")
        cc._require_aware(self.issued_at, "issued_at")
        cc._require_aware(self.expires_at, "expires_at")
        if self.expires_at <= self.issued_at:
            raise cc.ContractError("validade da autorização inválida")
        if self.max_transmissions != 1:
            raise cc.ContractError("piloto permite no máximo uma transmissão")
        if self.maintainer_id.lower() in {"supervisor", "agent-s", "agente s"}:
            raise cc.ContractError("supervisor não pode emitir sua própria autorização")

    def validate_for_pilot(self, clock: Optional[datetime] = None, *, require_real: bool = False) -> None:
        self.validate()
        if not self.approved:
            raise cc.AuthorizationError("autorização experimental não aprovada")
        if self.environment in {"PROPOSED", "SYNTHETIC"}:
            raise cc.AuthorizationError("autorização proposta/sintética não habilita transmissão")
        if require_real:
            if self.environment != "APPROVED":
                raise cc.AuthorizationError("HTTP real exige autorização APPROVED")
            if self.maintainer_id.lower() in {"fixture", "fake", "test", "simulado"}:
                raise cc.AuthorizationError("aprovador fixtures não habilita HTTP real")
        elif self.environment not in {"FIXTURE", "APPROVED"}:
            raise cc.AuthorizationError("autorização não utilizável no transporte de teste")
        current = clock or cc.utc_now()
        if current >= self.expires_at:
            raise cc.AuthorizationError("autorização expirada")
        if current < self.issued_at:
            raise cc.AuthorizationError("autorização futura")

    def canonical(self) -> str:
        material = {
            "authorization_id": self.authorization_id,
            "maintainer_id": self.maintainer_id,
            "approval_reference": self.approval_reference,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "instance_id": self.instance_id,
            "target": list(self.target.key()),
            "mission_id": self.mission_id,
            "mission_identity": self.mission_identity,
            "operation_id": self.operation_id,
            "prompt_sha256": self.prompt_sha256,
            "scope": self.scope,
            "issued_at": cc.iso(self.issued_at),
            "expires_at": cc.iso(self.expires_at),
            "approved": self.approved,
            "environment": self.environment,
            "max_transmissions": self.max_transmissions,
            "expected_model": self.expected_model,
            "endpoint": self.endpoint,
        }
        return hashlib.sha256(cc.canonical_json(material).encode("utf-8")).hexdigest()

    def proposal(self) -> dict[str, Any]:
        return {
            "authorization_id": self.authorization_id,
            "maintainer_id": self.maintainer_id,
            "approval_reference": self.approval_reference,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "instance_id": self.instance_id,
            "target": list(self.target.key()),
            "mission_id": self.mission_id,
            "mission_identity": self.mission_identity,
            "operation_id": self.operation_id,
            "prompt_sha256": self.prompt_sha256,
            "scope": self.scope,
            "issued_at": cc.iso(self.issued_at),
            "expires_at": cc.iso(self.expires_at),
            "approved": self.approved,
            "environment": self.environment,
            "max_transmissions": self.max_transmissions,
            "expected_model": self.expected_model,
            "endpoint": self.endpoint,
            "canonical_sha256": self.canonical(),
        }


@dataclass(frozen=True)
class PilotManifest:
    """Representação canônica integral do que o mantenedor aprova."""

    reviewed_head_sha: str
    operational_digests: tuple[tuple[str, str], ...]
    mission_identity: str
    authorization_identity: str
    authorization_id: str
    maintainer_id: str
    approval_reference: str
    target_key: tuple[str, ...]
    instance_id: str
    model: str
    endpoint: str
    operation_id: str
    prompt_sha256: str
    issued_at: datetime
    expires_at: datetime
    max_transmissions: int
    # Evidências P3B.4 entram no material canônico: alteração invalida aprovação.
    permission_policy_sha256: str = ""
    admission_basis: str = "unavailable"
    inbox_evidence: str = "unobserved"
    repository_source: str = "git-local"
    admission_authorized: bool = False
    exclusive_consumer: bool = False
    exclusive_consumer_evidence_sha256: str = ""

    def __post_init__(self) -> None:
        # Nenhum endpoint externo/divergente entra no manifesto aprovado.
        object.__setattr__(self, "endpoint", canonical_endpoint(self.endpoint))

    def material(self) -> dict[str, Any]:
        cc._require_aware(self.issued_at, "issued_at")
        cc._require_aware(self.expires_at, "expires_at")
        return {
            "reviewed_head_sha": self.reviewed_head_sha,
            "operational_digests": dict(self.operational_digests),
            "mission_identity": self.mission_identity,
            "authorization_identity": self.authorization_identity,
            "authorization_id": self.authorization_id,
            "maintainer_id": self.maintainer_id,
            "approval_reference": self.approval_reference,
            "target_key": list(self.target_key),
            "instance_id": self.instance_id,
            "model": self.model,
            "endpoint": self.endpoint,
            "operation_id": self.operation_id,
            "prompt_sha256": self.prompt_sha256,
            "issued_at": cc.iso(self.issued_at),
            "expires_at": cc.iso(self.expires_at),
            "max_transmissions": self.max_transmissions,
            "permission_policy_sha256": self.permission_policy_sha256,
            "admission_basis": self.admission_basis,
            "inbox_evidence": self.inbox_evidence,
            "repository_source": self.repository_source,
            "admission_authorized": self.admission_authorized,
            "exclusive_consumer": self.exclusive_consumer,
            "exclusive_consumer_evidence_sha256": self.exclusive_consumer_evidence_sha256,
        }

    def canonical(self) -> str:
        return hashlib.sha256(cc.canonical_json(self.material()).encode("utf-8")).hexdigest()

    def proposal(self) -> dict[str, Any]:
        return {**self.material(), "canonical_sha256": self.canonical(), "approved": False}


def build_runtime_manifest(mission: cc.MissionEnvelope, authorization: PilotAuthorization,
                           prompt_sha256: str, endpoint: str, *,
                           permission_policy_sha256: str = "",
                           admission_basis: str = "unavailable",
                           inbox_evidence: str = "unobserved",
                           repository_source: str = "git-local",
                           admission_authorized: bool = False,
                           exclusive_consumer: bool = False,
                           exclusive_consumer_evidence_sha256: str = "") -> PilotManifest:
    """Manifesto reconstruído do estado real, sem confiar em campos informados."""
    return PilotManifest(
        reviewed_head_sha=executed_head_sha(),
        operational_digests=tuple(sorted(operational_file_digests().items())),
        mission_identity=cc.canonical_mission_identity(mission),
        authorization_identity=authorization.canonical(),
        authorization_id=authorization.authorization_id,
        maintainer_id=authorization.maintainer_id,
        approval_reference=authorization.approval_reference,
        target_key=tuple(authorization.target.key()),
        instance_id=authorization.instance_id,
        model=authorization.expected_model,
        endpoint=endpoint,
        operation_id=mission.operation_id,
        prompt_sha256=prompt_sha256,
        issued_at=authorization.issued_at,
        expires_at=authorization.expires_at,
        max_transmissions=authorization.max_transmissions,
        permission_policy_sha256=permission_policy_sha256,
        admission_basis=admission_basis,
        inbox_evidence=inbox_evidence,
        repository_source=repository_source,
        admission_authorized=admission_authorized,
        exclusive_consumer=exclusive_consumer,
        exclusive_consumer_evidence_sha256=exclusive_consumer_evidence_sha256,
    )


@dataclass(frozen=True)
class PilotApproval:
    """Ação humana explícita sobre um manifesto integral; não é emitida pelo agente."""

    approval_id: str
    approver_id: str
    approver_type: str
    environment: str
    manifest: PilotManifest

    @property
    def manifest_sha256(self) -> str:
        return self.manifest.canonical()

    def validate(self, clock: Optional[datetime] = None, *, require_real: bool = False) -> None:
        if self.approver_type != "human":
            raise cc.AuthorizationError("aprovação deve ser humana")
        if self.environment not in {"PROPOSED", "FIXTURE", "APPROVED"}:
            raise cc.AuthorizationError("ambiente de aprovação inválido")
        if require_real and self.environment != "APPROVED":
            raise cc.AuthorizationError("HTTP real exige aprovação APPROVED")
        manifest = self.manifest
        if not re.fullmatch(r"[0-9a-f]{40}", manifest.reviewed_head_sha):
            raise cc.ContractError("HEAD revisado inválido")
        if manifest.reviewed_head_sha != executed_head_sha():
            raise cc.AuthorizationError("HEAD revisado diverge do HEAD executado")
        if dict(manifest.operational_digests) != operational_file_digests():
            raise cc.AuthorizationError("arquivos operacionais divergentes")
        if not cc.SHA256_PATTERN.fullmatch(manifest.prompt_sha256):
            raise cc.ContractError("hash do prompt inválido")
        if manifest.endpoint != canonical_endpoint(manifest.endpoint):
            raise cc.ContractError("endpoint do manifesto inválido")
        if manifest.max_transmissions != 1:
            raise cc.ContractError("limite de transmissão inválido")
        cc._require_aware(manifest.issued_at, "issued_at")
        cc._require_aware(manifest.expires_at, "expires_at")
        if manifest.expires_at <= manifest.issued_at:
            raise cc.ContractError("validade da aprovação inválida")
        current = clock or cc.utc_now()
        if current < manifest.issued_at:
            raise cc.AuthorizationError("aprovação emitida no futuro")
        if current >= manifest.expires_at:
            raise cc.AuthorizationError("aprovação expirada")

    def matches(self, runtime: PilotManifest) -> bool:
        return self.manifest.canonical() == runtime.canonical()


def mission_report_from_dict(value: dict[str, Any]) -> Optional[cc.MissionReport]:
    try:
        completed = datetime.fromisoformat(str(value["completed_at"]).replace("Z", "+00:00"))
        return cc.MissionReport(
            schema_version=str(value["schema_version"]),
            project_id=str(value["project_id"]),
            mission_id=str(value["mission_id"]),
            operation_id=(str(value["operation_id"]) if value.get("operation_id") else None),
            agent_id=str(value["agent_id"]),
            session_id=str(value["session_id"]),
            message_id=str(value["message_id"]),
            status=str(value["status"]),
            head_sha=value.get("head_sha"),
            branch=value.get("branch"),
            pull_request=value.get("pull_request"),
            test_evidence=tuple(value.get("test_evidence", ())),
            blockers=tuple(value.get("blockers", ())),
            artifacts=tuple(value.get("artifacts", ())),
            completed_at=completed,
            acceptance_results=dict(value.get("acceptance_results", {})),
            untrusted_text=str(value.get("untrusted_text", "")),
        )
    except Exception:
        return None


def parse_pilot_report(text: str, operation_id: str,
                       observed_message_id: Optional[str] = None) -> Optional[cc.MissionReport]:
    """O ID do relatório vem do envelope da API, nunca do texto gerado pelo modelo."""
    if coord_checkpoint.REPORT_CONTRACT not in text:
        return None
    for line in text.splitlines():
        if line.startswith("contract:"):
            try:
                value = json.loads(line.split(":", 1)[1])
            except Exception:
                continue
            if not isinstance(value, dict) or value.get("operation_id") != operation_id:
                continue
            if isinstance(observed_message_id, str) and observed_message_id:
                value["message_id"] = observed_message_id
            return mission_report_from_dict(value)
    return None


@dataclass
class _OperationGuard:
    """Reserva + DISPATCHING persistido + autorização consumida, vinculados ao envio."""

    operation_id: str
    mission_identity: str
    authorization_canonical: str
    payload_sha256: str
    target_key: tuple[str, ...]
    store: Any
    reservation: dict[str, Any]
    endpoint: str = DEFAULT_ENDPOINT
    consumed: bool = False


class OpenCodeTransport(cc.Transport):
    """Adaptador restrito; GET permitido, POST somente com allow_write explícito."""

    def __init__(self, client: HttpClient, *, expected_instance_id: str,
                 registered_targets: tuple[cc.TargetIdentity, ...],
                 pilot_authorization: PilotAuthorization,
                 allow_write: bool = False,
                 clock=None, mode: str = "fixture"):
        if mode not in {"fixture", "real"}:
            raise cc.ContractError("modo de transporte inválido")
        self.mode = mode
        self.is_real = mode == "real"
        self.last_receipt: Optional[cc.TransportReceipt] = None
        self.client = client
        # Endpoint efetivo do cliente: fonte única, nunca argumento do chamador.
        self.endpoint = client_endpoint(client)
        self.expected_instance_id = expected_instance_id
        self.registered = {target.key(): target for target in registered_targets}
        self.pilot = pilot_authorization
        self.pilot.validate()
        self.allow_write = allow_write
        self.clock = clock or cc.utc_now
        self.prepared_bodies: dict[str, dict[str, Any]] = {}
        self.calls: list[tuple[str, str]] = []
        self._guard: Optional[_OperationGuard] = None

    def bind_operation(self, guard: _OperationGuard) -> None:
        self._guard = guard

    def release_operation(self) -> None:
        self._guard = None

    def confirm_instance(self) -> None:
        self._confirm_instance()

    def confirm_target(self, target: cc.TargetIdentity) -> None:
        self._confirm_target(target)

    def effective_endpoint(self) -> str:
        return self.endpoint

    def confirm_endpoint(self, expected: Optional[str] = None) -> str:
        """Revalida o endpoint do cliente contra a operação, imediatamente antes do envio."""
        actual = client_endpoint(self.client)
        if actual != self.endpoint:
            raise cc.TransportError("endpoint do cliente divergente", phase="pre_transmit")
        if expected is not None and actual != canonical_endpoint(expected):
            raise cc.TransportError("endpoint diverge da operação autorizada", phase="pre_transmit")
        if self.pilot.endpoint != actual:
            raise cc.TransportError("endpoint diverge da autorização", phase="pre_transmit")
        return actual

    def _get(self, path: str) -> dict[str, Any]:
        self.calls.append(("GET", path))
        status, payload = self.client.request("GET", path)
        if status != 200:
            raise cc.TransportError(f"GET falhou: {status}", phase="pre_transmit")
        return payload

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(("POST", path))
        if not self.allow_write:
            raise cc.TransportDisabled("escrita HTTP desativada")
        status, payload = self.client.request("POST", path, body)
        if status not in {200, 201, 202}:
            raise cc.TransportError(f"POST falhou: {status}", phase="during_transmit")
        return payload

    def _confirm_instance(self) -> None:
        info = self._get(INFO_PATH)
        instance_id = hashlib.sha256(cc.canonical_json({
            "version": info.get("version"), "pid": info.get("pid"),
            "urls": info.get("urls"), "hostname": info.get("hostname"),
        }).encode("utf-8")).hexdigest()
        if instance_id != self.expected_instance_id:
            raise cc.TransportError("instância divergente", phase="pre_transmit")

    def _confirm_target(self, target: cc.TargetIdentity) -> None:
        if target != self.pilot.target or target.key() not in self.registered:
            raise cc.TransportError("destino não autorizado", phase="pre_transmit")
        location = self._get(LOCATION_PATH)
        # Identidade lógica (better-talker), repositório Git e hash interno do
        # OpenCode são validados separadamente pela função compartilhada.
        try:
            identity = validate_observed_identity(
                location, project_id=target.project_id, expected_location=target.location,
                expected_canonical=(EXPECTED_CANONICAL_DIR, target.location),
                repository_id=target.repository_id)
        except cc.ContractError as e:
            raise cc.TransportError(f"identidade de destino divergente: {e}",
                                    phase="pre_transmit") from e
        session = (self._get(f"/api/session/{target.session_id}") or {}).get("data") or {}
        model = session.get("model") or {}
        model_ref = canonical_model_ref(model.get("providerID"), model.get("id"),
                                        model.get("variant"))
        if (session.get("id") != target.session_id
                or session.get("projectID") != identity.opencode_project_id
                or session.get("agent") != target.agent_id
                or (session.get("location") or {}).get("directory") != target.location
                or model_ref != self.pilot.expected_model):
            raise cc.TransportError("sessão/modelo divergente", phase="pre_transmit")

    def lookup_target(self, project_id: str, agent_id: str, session_id: str) -> cc.TargetIdentity:
        target = self.pilot.target
        if (project_id, agent_id, session_id) != (target.project_id, target.agent_id, target.session_id):
            raise cc.TransportError("destino diferente do autorizado", phase="pre_transmit")
        self._confirm_instance()
        self._confirm_target(target)
        return target

    def status(self, target: cc.TargetIdentity) -> cc.SessionStatus:
        self._confirm_target(target)
        active_payload = self._get(ACTIVE_PATH)
        active = active_payload.get("data") if isinstance(active_payload, dict) else None
        active_observed = isinstance(active, dict)
        target_in_active = active_observed and target.session_id in active
        inbox_payload = self._get(f"/api/session/{target.session_id}{INBOX_SUFFIX}")
        inbox = inbox_payload.get("data") if isinstance(inbox_payload, dict) else None
        inbox_observed = isinstance(inbox, list)
        inbox_count = len(inbox) if inbox_observed else None
        session_payload = self._get(f"/api/session/{target.session_id}")
        session = session_payload.get("data") if isinstance(session_payload, dict) else None
        session = session if isinstance(session, dict) else {}

        assessment = {"state": "UNKNOWN", "reasons": ("status não classificado",)}
        if self.is_real:
            messages_payload = self._get(
                f"/api/session/{target.session_id}{MESSAGE_SUFFIX}?limit=20&order=desc")
            message_data = messages_payload.get("data") if isinstance(messages_payload, dict) else None
            messages_observed = isinstance(message_data, list)
            message_count = len(message_data) if messages_observed else None
            part_count = (sum(len(item.get("content") or []) for item in message_data
                              if isinstance(item, dict)) if messages_observed else None)
            pending_payload = self._get(f"/api/session/{target.session_id}/permission")
            pending_data = pending_payload.get("data") if isinstance(pending_payload, dict) else None
            assessment = classify_availability(
                active_observed=active_observed, target_in_active=target_in_active,
                session=session, inbox_observed=inbox_observed, inbox_count=inbox_count,
                messages_observed=messages_observed, message_count=message_count,
                part_count=part_count, pending_observed=isinstance(pending_data, list),
                pending_count=len(pending_data) if isinstance(pending_data, list) else None)
            legacy = ("busy" if target_in_active or assessment["state"] == "BUSY"
                      else "idle" if assessment["state"] in {"FRESH_UNEXECUTED", "IDLE_TERMINAL"}
                      else "unknown")
        else:
            # Compatibilidade com fixtures certificadas: sem inferência nova para A–E.
            times = session.get("time") or {}
            idle_confirmed = (isinstance(times.get("idle"), (int, float))
                              and times.get("idle", 0) >= times.get("busy", 0))
            legacy = "busy" if target_in_active else ("idle" if idle_confirmed else "unknown")
            assessment = {"state": "UNKNOWN", "reasons": ("modo fixture; sem admissão real",)}
        return cc.SessionStatus(target, legacy, inbox_count or 0, {
            "active_observed": active_observed, "target_in_active": target_in_active,
            "inbox_observed": inbox_observed, "inbox_count": inbox_count,
            "availability_state": assessment["state"],
            "availability_reasons": list(assessment["reasons"]),
            "availability_claimed": False, "source": "opencode-api"})

    def fresh_evidence(self, target: Optional[cc.TargetIdentity] = None,
                       *, catalog: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        """Reobservação imediata (somente GET) para validação pré-POST."""
        target = target or self.pilot.target
        info = self._get(INFO_PATH)
        location = self._get(LOCATION_PATH)
        session_payload = self._get(f"/api/session/{target.session_id}")
        session = session_payload.get("data") if isinstance(session_payload, dict) else None
        session = session if isinstance(session, dict) else {}
        active_payload = self._get(ACTIVE_PATH)
        active = active_payload.get("data") if isinstance(active_payload, dict) else None
        inbox_payload = self._get(f"/api/session/{target.session_id}{INBOX_SUFFIX}")
        inbox = inbox_payload.get("data") if isinstance(inbox_payload, dict) else None
        messages_payload = self._get(
            f"/api/session/{target.session_id}{MESSAGE_SUFFIX}?limit=20&order=desc")
        messages = messages_payload.get("data") if isinstance(messages_payload, dict) else None
        pending_payload = self._get(f"/api/session/{target.session_id}/permission")
        pending = pending_payload.get("data") if isinstance(pending_payload, dict) else None
        model = session.get("model") or {}
        project = location.get("project") or {}
        tokens = session.get("tokens")
        cost = session.get("cost")
        evidence: dict[str, Any] = {
            "source": "fresh-observation", "endpoint": self.endpoint,
            "instance_id": hashlib.sha256(cc.canonical_json({
                "version": info.get("version"), "pid": info.get("pid"),
                "urls": info.get("urls"), "hostname": info.get("hostname"),
            }).encode("utf-8")).hexdigest(),
            "target_key": list(target.key()),
            "opencode_project_id": project.get("id"),
            "session_project_id": session.get("projectID"),
            "location": (location.get("directory")),
            "session_location": (session.get("location") or {}).get("directory"),
            "session_id": session.get("id"), "agent_id": session.get("agent"),
            "session_model": canonical_model_ref(model.get("providerID"), model.get("id"),
                                                 model.get("variant")),
            "permission_policy": session.get("permissions"),
            "permission_policy_sha256": canonical_policy_sha256(session.get("permissions")),
            "tokens": tokens, "cost": cost,
            "outcome": session.get("outcome"),
            "active_observed": isinstance(active, dict),
            "target_in_active": isinstance(active, dict) and target.session_id in active,
            "inbox_observed": isinstance(inbox, list),
            "inbox_count": len(inbox) if isinstance(inbox, list) else None,
            "messages_observed": isinstance(messages, list),
            "message_count": len(messages) if isinstance(messages, list) else None,
            "part_count": (sum(len(item.get("content") or []) for item in messages
                               if isinstance(item, dict)) if isinstance(messages, list) else None),
            "pending_observed": isinstance(pending, list),
            "pending_count": len(pending) if isinstance(pending, list) else None,
        }
        if catalog is not None:
            provider_id, _, rest = evidence["session_model"].partition("/")
            model_id, _, variant = rest.partition("#")
            evidence["model_catalog_evidence"] = verify_model_catalog(
                catalog, provider_id=provider_id, model_id=model_id,
                variant=variant or "none", authorized_model=self.pilot.expected_model)
        return evidence

    def prepare(self, envelope: cc.MissionEnvelope, authorization: cc.Authorization) -> cc.PreparedRequest:
        self.pilot.validate_for_pilot(self.clock(), require_real=self.is_real)
        self.confirm_endpoint(self.pilot.endpoint)
        envelope.validate()
        if (envelope.mission_id != self.pilot.mission_id or
                envelope.operation_id != self.pilot.operation_id or
                cc.canonical_mission_identity(envelope) != self.pilot.mission_identity):
            raise cc.ContractError("missão diferente da autorizada")
        if envelope.project_id != self.pilot.project_id or envelope.repository_id != self.pilot.repository_id:
            raise cc.ContractError("projeto diferente do autorizado")
        if envelope.scope != self.pilot.scope:
            raise cc.ContractError("escopo diferente do autorizado")
        if envelope.target != self.pilot.target:
            raise cc.ContractError("destinatário diferente do autorizado")
        prompt = envelope.payload.get("prompt")
        if not isinstance(prompt, str) or sha256_text(prompt) != self.pilot.prompt_sha256:
            raise cc.ContractError("prompt diferente do autorizado")
        self._confirm_instance()
        self._confirm_target(envelope.target)
        request_id = f"msg-pilot-{envelope.operation_id}"
        body = {"id": request_id, "text": prompt,
                "metadata": {"operation_id": envelope.operation_id,
                             "mission_id": envelope.mission_id,
                             "authorization_id": self.pilot.authorization_id}}
        self.prepared_bodies[request_id] = body
        return cc.PreparedRequest(request_id, envelope, authorization, cc.utc_now())

    def transmit(self, request: cc.PreparedRequest) -> cc.TransportReceipt:
        guard = self._guard
        if guard is None:
            raise cc.AuthorizationError("transmissão exige executor autorizado")
        if guard.consumed:
            raise cc.AuthorizationError("autorização já consumida; reenvio bloqueado")
        if request.envelope.operation_id != guard.operation_id:
            raise cc.AuthorizationError("operação diverge da reserva")
        if cc.canonical_mission_identity(request.envelope) != guard.mission_identity:
            raise cc.AuthorizationError("missão diverge da reserva")
        if cc.canonical_authorization_identity(request.authorization) != guard.authorization_canonical:
            raise cc.AuthorizationError("autorização diverge da reserva")
        if request.envelope.payload_sha256 != guard.payload_sha256:
            raise cc.AuthorizationError("payload diverge da reserva")
        if tuple(request.envelope.target.key()) != guard.target_key:
            raise cc.AuthorizationError("destino diverge da reserva")
        # Endpoint efetivo revalidado logo antes de qualquer POST.
        self.confirm_endpoint(guard.endpoint)
        record = guard.store.load()
        if record is None or record.state != "DISPATCHING":
            raise cc.AuthorizationError("DISPATCHING persistido ausente")
        if guard.store.read_reservation() != guard.reservation:
            raise cc.AuthorizationError("reserva inválida ou não confirmada")
        self.pilot.validate_for_pilot(self.clock(), require_real=self.is_real)
        self._confirm_instance()
        self._confirm_target(request.envelope.target)
        body = self.prepared_bodies.get(request.request_id)
        if body is None:
            raise cc.ContractError("requisição preparada não encontrada")
        # Consome antes do POST: qualquer reentrada não pode repetir a tentativa.
        guard.consumed = True
        target = request.envelope.target
        path = f"/api/session/{target.session_id}{PROMPT_SUFFIX}"
        response = self._post(path, body)
        observed_id = (response.get("data") or {}).get("id")
        evidence = {"operation_id": request.envelope.operation_id,
                    "expected_message_id": request.request_id,
                    "observed_message_id": observed_id,
                    "payload_sha256": request.envelope.payload_sha256,
                    "project_id": request.envelope.project_id,
                    "repository_id": request.envelope.repository_id,
                    "agent_id": request.envelope.target_agent,
                    "session_id": request.envelope.target_session_id,
                    "instance_id": request.envelope.target_instance_id,
                    "location": request.envelope.target_location,
                    "http_status": 202, "source": "opencode-api"}
        if (not isinstance(observed_id, str) or not observed_id
                or observed_id != request.request_id):
            receipt = cc.TransportReceipt(
                request.envelope.operation_id, "ambiguous", None,
                {**evidence, "message_id_unavailable": True,
                 "message_id_divergent": bool(observed_id)}, cc.utc_now())
        else:
            receipt = cc.TransportReceipt(request.envelope.operation_id, "accepted",
                                          observed_id, {**evidence, "queued_in_inbox": True},
                                          cc.utc_now())
        self.last_receipt = receipt
        return receipt

    def operation_status(self, operation_id: str) -> cc.OperationObservation:
        if operation_id != self.pilot.operation_id:
            raise cc.ContractError("operação não autorizada")
        target = self.pilot.target
        active = (self._get(ACTIVE_PATH) or {}).get("data") or {}
        inbox = (self._get(f"/api/session/{target.session_id}{INBOX_SUFFIX}") or {}).get("data") or []
        prompt_id = f"msg-pilot-{operation_id}"
        messages = (self._get(f"/api/session/{target.session_id}{MESSAGE_SUFFIX}?limit=20&order=desc") or {}).get("data") or []
        reports, report_links, recorded, queued = [], [], False, False
        for item in messages:
            item_id = item.get("id")
            text = "\n".join(p.get("text", "") for p in item.get("content", [])
                             if isinstance(p, dict) and p.get("type") == "text")
            if (item.get("type") == "user" and item_id == prompt_id
                    and sha256_text(text) == self.pilot.prompt_sha256):
                recorded = True
            if item.get("type") == "assistant" and item_id:
                parsed = parse_pilot_report(text, operation_id, observed_message_id=item_id)
                if parsed:
                    author = item.get("agent") or ""
                    reply_to = item.get("reply_to") or (item.get("metadata") or {}).get("reply_to")
                    reports.append(replace(
                        parsed, message_id=item_id, agent_id=author,
                        session_id=item.get("sessionID") or ""))
                    declared_id = None
                    try:
                        declared_id = json.loads(text.split("contract:", 1)[1]).get("message_id")
                    except Exception:
                        pass
                    report_links.append({
                        "envelope_message_id": item_id,
                        "declared_message_id": declared_id,
                        "author_observed": author,
                        "reply_to_observed": reply_to,
                        "content_sha256": sha256_text(text),
                        "status": parsed.status,
                        "acceptance_results": dict(parsed.acceptance_results),
                    })
        for item in inbox:
            item_id = item.get("id")
            inbox_text = str(item.get("text") or "")
            if item_id == prompt_id and (not inbox_text or sha256_text(inbox_text) == self.pilot.prompt_sha256):
                queued = True
        evidence = {"source": "opencode-api", "active_observed": target.session_id in active,
                    "queue_observed": bool(inbox), "availability_claimed": False,
                    "prompt_content_confirmed": recorded, "prompt_id_expected": prompt_id,
                    "report_links": tuple(report_links)}
        if reports:
            level, state, evidence["report_available"] = "report_received", "completed", True
        elif recorded and target.session_id in active:
            level, state, evidence["executing"] = "execution_started", "running", True
        elif recorded:
            level, state, evidence["recorded"] = "recorded_in_session", "queued", True
        elif queued:
            level, state = "queued_in_inbox", "queued"
        else:
            level, state = "not_observed", "not_observed"
        return cc.OperationObservation(operation_id, state, prompt_id if recorded or queued else None,
                                       tuple(reports), evidence, target, level, operation_id, None)

    def reports(self, operation_id: str) -> tuple[cc.MissionReport, ...]:
        if operation_id != self.pilot.operation_id:
            raise cc.ContractError("operação não autorizada")
        return self.operation_status(operation_id).reports


@dataclass
class PilotOperationRecord:
    mission_id: str
    operation_id: str
    authorization_id: str
    authorization_canonical: str
    target_key: tuple[str, ...]
    prompt_sha256: str
    state: str
    created_at: str
    updated_at: str
    evidence: dict[str, Any] = field(default_factory=dict)
    history: list[dict[str, Any]] = field(default_factory=list)
    mission_snapshot: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {"schema_version": "1.0", "mission_id": self.mission_id,
                "operation_id": self.operation_id, "authorization_id": self.authorization_id,
                "authorization_canonical": self.authorization_canonical,
                "target_key": list(self.target_key), "prompt_sha256": self.prompt_sha256,
                "state": self.state, "created_at": self.created_at,
                "updated_at": self.updated_at, "evidence": self.evidence,
                "history": self.history, "mission_snapshot": self.mission_snapshot}


def _record_from_dict(value: dict[str, Any]) -> PilotOperationRecord:
    return PilotOperationRecord(
        mission_id=value["mission_id"], operation_id=value["operation_id"],
        authorization_id=value["authorization_id"],
        authorization_canonical=value["authorization_canonical"],
        target_key=tuple(value["target_key"]), prompt_sha256=value["prompt_sha256"],
        state=value["state"], created_at=value["created_at"], updated_at=value["updated_at"],
        evidence=value.get("evidence", {}), history=value.get("history", []),
        mission_snapshot=value.get("mission_snapshot", {}),
    )


class PilotOperationStore:
    """Persistência privada mínima para uma única operação experimental."""

    def __init__(self, directory: str = DEFAULT_PILOT_DIR):
        self.directory = os.path.abspath(directory)
        self.path = os.path.join(self.directory, "operation.json")
        self.lock_path = os.path.join(self.directory, "operation.lock")

    def _ensure_private_dir(self) -> None:
        os.makedirs(self.directory, mode=0o700, exist_ok=True)
        if os.path.realpath(self.directory) != self.directory:
            raise cc.ContractError("diretório do piloto contém symlink")
        st = os.lstat(self.directory)
        if not stat.S_ISDIR(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o700:
            raise cc.ContractError("diretório do piloto não é privado")

    def _write(self, record: PilotOperationRecord) -> None:
        self._ensure_private_dir()
        raw = json.dumps(record.to_dict(), sort_keys=True, ensure_ascii=False).encode("utf-8")
        fd, temporary = tempfile.mkstemp(prefix=".pilot-", dir=self.directory)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(raw)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary, self.path)
            dir_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def create(self, record: PilotOperationRecord) -> None:
        if os.path.lexists(self.path):
            raise cc.ContractError("operação experimental já registrada")
        self._write(record)

    def load(self) -> Optional[PilotOperationRecord]:
        if not os.path.lexists(self.path):
            return None
        if os.path.realpath(self.path) != self.path:
            raise cc.ContractError("registro do piloto contém symlink")
        st = os.lstat(self.path)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
            raise cc.ContractError("registro do piloto não é privado")
        fd = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(fd, "rb") as f:
            return _record_from_dict(json.loads(f.read().decode("utf-8")))

    def update(self, state: str, evidence: dict[str, Any]) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if state != record.state and state not in PILOT_TRANSITIONS.get(record.state, set()):
            raise cc.ContractError(f"transição proibida: {record.state} → {state}")
        record.state = state
        record.updated_at = cc.iso(cc.utc_now())
        record.evidence.update(evidence)
        record.history.append({"utc": record.updated_at, "state": state, "evidence": evidence})
        self._write(record)
        return record

    def read_reservation(self) -> Optional[dict[str, Any]]:
        if not os.path.lexists(self.lock_path):
            return None
        if os.path.realpath(self.lock_path) != self.lock_path:
            raise cc.ContractError("reserva contém symlink")
        st = os.lstat(self.lock_path)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
            raise cc.ContractError("reserva não é privada")
        fd = os.open(self.lock_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(fd, "rb") as f:
            return json.loads(f.read().decode("utf-8"))

    @contextmanager
    def reserve(self):
        self._ensure_private_dir()
        try:
            fd = os.open(self.lock_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError as e:
            raise cc.ContractError("reserva exclusiva já ativa") from e
        reservation = {"pid": os.getpid(), "created_utc": cc.iso(cc.utc_now())}
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(json.dumps(reservation, sort_keys=True))
            f.flush()
            os.fsync(f.fileno())
        try:
            yield reservation
        finally:
            if os.path.lexists(self.lock_path):
                os.unlink(self.lock_path)

    def assert_transmittable(self) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"UNKNOWN", "DISPATCHING", "COMPLETED", "FAILED"}:
            raise cc.AuthorizationError(f"reenvio bloqueado em {record.state}")
        if record.state != "AUTHORIZED":
            raise cc.ContractError("operação não autorizada")
        return record

    def recover(self) -> PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"DISPATCHING", "AUTHORIZED"}:
            return self.update("UNKNOWN", {"interruption_unknown": True,
                                           "automatic_resend": False})
        return record


def assess_text_report(snapshot: dict[str, Any], report: cc.MissionReport,
                       link: dict[str, Any], *, prompt_id: str, expected_author: str,
                       clock: datetime) -> tuple[bool, tuple[str, ...]]:
    """Critérios proporcionais para missão textual; sem evidências fictícias de código."""
    try:
        report.validate()
    except cc.ContractError as e:
        return False, (f"estrutura: {e}",)
    reasons: list[str] = []
    checks = (
        (report.project_id == snapshot.get("project_id"), "projeto"),
        (report.mission_id == snapshot.get("mission_id"), "missão"),
        (report.operation_id == snapshot.get("operation_id"), "operation_id"),
        (report.agent_id == expected_author, "agente declarado"),
        (report.session_id == snapshot.get("target_session_id"), "sessão"),
        (link.get("author_observed") == expected_author, "autoria observada"),
        (link.get("reply_to_observed") == prompt_id, "associação prompt-resposta"),
        (report.message_id == link.get("envelope_message_id"), "message_id do envelope"),
        (not report.blockers, "bloqueios ausentes"),
        (report.completed_at >= datetime.fromisoformat(snapshot["created_at"].replace("Z", "+00:00")),
         "relatório posterior à missão"),
    )
    for passed, label in checks:
        if not passed:
            reasons.append(label)
    criteria = tuple(snapshot.get("acceptance_criteria", ()))
    missing = [criterion for criterion in criteria
               if report.acceptance_results.get(criterion) is not True]
    if missing:
        reasons.append("critérios de aceitação incompletos")
    if report.status != "COMPLETED":
        reasons.append(f"status={report.status}")
    if clock + timedelta(minutes=5) < report.completed_at:
        reasons.append("relatório no futuro")
    return (not reasons), tuple(reasons)


class PilotExecutor:
    """Executa uma única operação experimental pelo caminho protegido."""

    def __init__(self, transport: OpenCodeTransport, store: PilotOperationStore,
                 approval: PilotApproval, *, endpoint: Optional[str] = None,
                 allow_real: bool = False, clock=None, observer=None,
                 catalog: Optional[dict[str, Any]] = None):
        self.transport = transport
        self.store = store
        self.approval = approval
        # O endpoint vem do cliente efetivo; um valor declarado só é aceito se
        # for idêntico ao endpoint exposto pelo transporte.
        effective = transport.effective_endpoint()
        if endpoint is not None and canonical_endpoint(endpoint) != effective:
            raise cc.ContractError("endpoint declarado diverge do cliente efetivo")
        self.endpoint = effective
        self.allow_real = allow_real
        self.clock = clock or cc.utc_now
        self.catalog = catalog
        self.observer = observer or (lambda: self.transport.fresh_evidence(
            self.transport.pilot.target, catalog=self.catalog))
        self.last_observation: Optional[dict[str, Any]] = None

    def _validate(self, mission: cc.MissionEnvelope, authorization: cc.Authorization,
                  prompt: str) -> None:
        prompt_sha256 = sha256_text(prompt)
        self.approval.validate(self.clock(), require_real=self.allow_real)
        if self.allow_real:
            if not self.transport.is_real or self.approval.environment != "APPROVED":
                raise cc.AuthorizationError("execução real exige aprovação e transporte real")
            if self.transport.pilot.environment != "APPROVED":
                raise cc.AuthorizationError("autorização FIXTURE não habilita HTTP real")
        elif self.transport.is_real:
            raise cc.AuthorizationError("transporte real requer allow_real explícito")
        # Revalidação do endpoint cliente↔autorização↔operação antes de execute()
        # e novamente antes de transmit() (esta função é chamada nos dois pontos).
        confirmed = self.transport.confirm_endpoint(self.endpoint)
        if self.transport.pilot.endpoint != confirmed:
            raise cc.ContractError("endpoint diverge da autorização")
        approved = self.approval.manifest
        evidence = {
            "permission_policy_sha256": approved.permission_policy_sha256,
            "admission_basis": approved.admission_basis,
            "inbox_evidence": approved.inbox_evidence,
            "repository_source": approved.repository_source,
            "admission_authorized": approved.admission_authorized,
            "exclusive_consumer": approved.exclusive_consumer,
            "exclusive_consumer_evidence_sha256": approved.exclusive_consumer_evidence_sha256,
        }
        if self.allow_real:
            # Nenhum default permissivo habilita transmissão real.
            missing = []
            if not cc.SHA256_PATTERN.fullmatch(evidence["permission_policy_sha256"]):
                missing.append("hash da política de permissões")
            if evidence["admission_basis"] != "new_experimental_session":
                missing.append("base de admissão")
            if not str(evidence["inbox_evidence"]).startswith("empty:"):
                missing.append("inbox observado vazio")
            if evidence["repository_source"] != "git-local":
                missing.append("fonte do repositório")
            if evidence["admission_authorized"] is not True:
                missing.append("autorização humana de admissão")
            if evidence["exclusive_consumer"] is not True:
                missing.append("consumidor exclusivo")
            if not cc.SHA256_PATTERN.fullmatch(evidence["exclusive_consumer_evidence_sha256"]):
                missing.append("evidência verificável de exclusividade")
            if missing:
                raise cc.AuthorizationError(
                    "evidências obrigatórias ausentes: " + ", ".join(missing))
            # Igualdade de hash não é observação: reobservar imediatamente antes do POST.
            fresh = self.observer()
            self.last_observation = fresh
            divergent = []
            if fresh.get("permission_policy_sha256") != approved.permission_policy_sha256:
                divergent.append("permission_policy_sha256")
            if fresh.get("session_model") != approved.model:
                divergent.append("model")
            if fresh.get("instance_id") != approved.instance_id:
                divergent.append("instance")
            if fresh.get("target_key") != list(approved.target_key):
                divergent.append("target")
            tokens, cost = fresh.get("tokens"), fresh.get("cost")
            if not (isinstance(tokens, dict)
                    and all(isinstance(tokens.get(k), (int, float))
                            for k in ("input", "output", "reasoning"))
                    and tokens["input"] == tokens["output"] == tokens["reasoning"] == 0):
                divergent.append("tokens")
            if not (isinstance(cost, list) and cost
                    and all(isinstance(item, dict) and item.get("input") == 0
                            and item.get("output") == 0 for item in cost)):
                divergent.append("cost")
            catalog = fresh.get("model_catalog_evidence") or {}
            if catalog.get("confirmed") is not True:
                divergent.append("catalog")
            if fresh.get("target_in_active") is True or fresh.get("inbox_observed") is not True \
                    or fresh.get("inbox_count") != 0 or fresh.get("messages_observed") is not True \
                    or fresh.get("message_count") != 0 or fresh.get("part_count") != 0 \
                    or fresh.get("pending_observed") is not True or fresh.get("pending_count") != 0:
                divergent.append("active/inbox/messages/pending")
            exclusive_evidence = fresh.get("exclusive_consumer_evidence")
            if (not isinstance(exclusive_evidence, dict)
                    or canonical_policy_sha256(exclusive_evidence)
                    != approved.exclusive_consumer_evidence_sha256):
                divergent.append("exclusividade")
            if divergent:
                raise cc.AuthorizationError(
                    "reobservação divergente: " + ", ".join(sorted(set(divergent))))
        runtime = build_runtime_manifest(mission, self.transport.pilot, prompt_sha256,
                                         self.endpoint, **evidence)
        if not self.approval.matches(runtime):
            raise cc.ContractError("manifesto aprovado diverge da operação")
        if prompt_sha256 != self.transport.pilot.prompt_sha256:
            raise cc.ContractError("prompt diverge da autorização")

    @staticmethod
    def _snapshot(mission: cc.MissionEnvelope) -> dict[str, Any]:
        return {"project_id": mission.project_id, "repository_id": mission.repository_id,
                "mission_id": mission.mission_id, "operation_id": mission.operation_id,
                "mission_identity": cc.canonical_mission_identity(mission),
                "target_agent": mission.target_agent,
                "target_session_id": mission.target_session_id,
                "target_instance_id": mission.target_instance_id,
                "target_location": mission.target_location,
                "payload_sha256": mission.payload_sha256,
                "acceptance_criteria": list(mission.acceptance_criteria),
                "created_at": cc.iso(mission.created_at)}

    def execute(self, mission: cc.MissionEnvelope, authorization: cc.Authorization,
                prompt: str) -> dict[str, Any]:
        self._validate(mission, authorization, prompt)
        existing = self.store.load()
        canonical = self.transport.pilot.canonical()
        if existing is None:
            self.store.create(PilotOperationRecord(
                mission.mission_id, mission.operation_id,
                self.approval.approval_id, canonical,
                tuple(self.transport.pilot.target.key()), sha256_text(prompt),
                "AUTHORIZED", cc.iso(self.clock()), cc.iso(self.clock()),
                mission_snapshot=self._snapshot(mission)))
        else:
            if (existing.operation_id != mission.operation_id
                    or existing.authorization_canonical != canonical
                    or existing.mission_snapshot != self._snapshot(mission)):
                raise cc.ContractError("registro persistente divergente")
        self.store.assert_transmittable()
        with self.store.reserve() as reservation:
            self.store.assert_transmittable()
            # Cinco guardas da fundação; "schema" é excetuado porque o contrato
            # BT-COORD-REPORT:v1 exige a chave schema_version no relatório.
            policy = cc.ProjectPolicy(mission.project_id, mission.repository_id,
                                      (mission.target_location,), (mission.target_agent,),
                                      (mission.target,),
                                      forbidden_scope_terms=("merge", "push main", "release",
                                                             "adb", "credential"),
                                      mode="SHADOW")
            engine = cc.CommunicationEngine(policy, self.transport, clock=self.clock)
            engine.propose(mission)
            engine.authorize(mission.operation_id, authorization)
            # DISPATCHING é persistido antes de qualquer POST.
            persisted = self.store.update("DISPATCHING", {"pre_transmit": True})
            if persisted.state != "DISPATCHING":
                raise cc.ContractError("persistência de DISPATCHING falhou")
            guard = _OperationGuard(
                operation_id=mission.operation_id,
                mission_identity=cc.canonical_mission_identity(mission),
                authorization_canonical=cc.canonical_authorization_identity(authorization),
                payload_sha256=mission.payload_sha256,
                target_key=tuple(mission.target.key()),
                store=self.store, reservation=reservation, endpoint=self.endpoint)
            self.transport.bind_operation(guard)
            try:
                # Revalidação imediata antes da tentativa única.
                self._validate(mission, authorization, prompt)
                engine_record = engine.transmit(mission.operation_id)
            finally:
                self.transport.release_operation()
            evidence = {"engine_state": engine_record.state.value,
                        "message_id": engine_record.message_id,
                        "receipt": self.transport.last_receipt.evidence
                        if self.transport.last_receipt else {},
                        "transmit_called": True,
                        # Origem da evidência: a reconciliação por fixture nunca
                        # pode ser aplicada a um registro de operação real.
                        "environment": self.approval.environment,
                        "source": "real" if self.transport.is_real else "fixture",
                        "endpoint": self.endpoint,
                        "service_contacted": self.transport.is_real}
            if engine_record.state == cc.OperationState.UNKNOWN:
                state = "UNKNOWN"
            elif engine_record.state == cc.OperationState.FAILED:
                state = "FAILED"
            else:
                state = "DISPATCHING"
            self.store.update(state, evidence)
            return {"executed": True, "state": state,
                    "receipt_confirmed": engine_record.message_id is not None,
                    "engine_events": len(engine.events.events)}

    def reconcile(self) -> dict[str, Any]:
        record = self.store.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state in {"COMPLETED", "FAILED"}:
            return {"state": record.state, "changed": False}
        snapshot = record.mission_snapshot
        if not snapshot:
            return {"state": record.state, "changed": False, "reason": "snapshot ausente"}
        try:
            target = cc.TargetIdentity(*record.target_key)
            self.transport.confirm_instance()
            self.transport.confirm_target(target)
        except (cc.TransportError, cc.ContractError) as e:
            return {"state": record.state, "changed": False,
                    "reason": f"instância/destino divergente: {e}"}
        observation = self.transport.operation_status(record.operation_id)
        if (observation.operation_id != record.operation_id
                or observation.target != target
                or observation.payload_sha256 not in (None, snapshot.get("payload_sha256"))):
            return {"state": record.state, "changed": False, "reason": "observação divergente"}
        prompt_id = f"msg-pilot-{record.operation_id}"
        receipt = record.evidence.get("receipt") or {}
        observed_receipt_id = receipt.get("observed_message_id")
        if observed_receipt_id is not None and observed_receipt_id != prompt_id:
            return {"state": record.state, "changed": False, "reason": "receipt divergente"}
        if (observation.evidence.get("prompt_content_confirmed") is not True
                or observation.message_id != prompt_id):
            return {"state": record.state, "changed": False,
                    "reason": "prompt efetivamente observado ausente"}
        links = {link["envelope_message_id"]: link
                 for link in observation.evidence.get("report_links", ())}
        for report in observation.reports:
            link = links.get(report.message_id)
            if link is None:
                continue
            ok, reasons = assess_text_report(
                snapshot, report, link, prompt_id=prompt_id,
                expected_author=target.agent_id, clock=self.clock())
            if not ok:
                continue
            self.store.update("COMPLETED", {
                "observation": observation.evidence,
                "report_fingerprint": report.fingerprint(),
                "assessment_reasons": reasons,
                "automatic_resend": False, "receipt_confirmed": True})
            return {"state": "COMPLETED", "changed": True,
                    "reports": len(observation.reports), "reasons": reasons}
        if (observation.evidence_level == "execution_started"
                and record.state in {"DISPATCHING", "DELIVERED", "UNKNOWN"}):
            self.store.update("RUNNING", {"observation": observation.evidence,
                                          "automatic_resend": False})
            return {"state": "RUNNING", "changed": True}
        if (observation.evidence_level == "recorded_in_session"
                and record.state == "DISPATCHING"):
            self.store.update("DELIVERED", {"observation": observation.evidence,
                                            "automatic_resend": False})
            return {"state": "DELIVERED", "changed": True}
        return {"state": record.state, "changed": False, "reason": "sem evidência conclusiva"}
