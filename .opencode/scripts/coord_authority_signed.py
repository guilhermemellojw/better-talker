#!/usr/bin/env python3
"""COORD-06B/P1 — verificação experimental de autoridade assinada (offline).

Extensão do contrato do PR #72 (`coord_authority`), em branch dependente. O
módulo é SOMENTE VERIFICADOR: não gera chaves, não assina, não persiste
consumo, não altera ledger e não executa transporte. A autenticidade vem de
uma assinatura OpenSSH destacada produzida fora do processo do agente; o
digest sozinho é apenas integridade.

Separação deliberada de responsabilidades:

* `mission_identity` é OPACA aqui. Ela deve ser recalculada pelo adaptador
  P14B a partir de `MissionEnvelope`, usando `coord_communication`, e
  entregue em `ExpectedOperation`. Este módulo NÃO implementa uma segunda
  identidade canônica.
* `payload_sha256` é recalculado do payload efetivo; `ExpectedOperation`
  também recebe o payload e o recalcula, em vez de confiar em um hash
  declarado pelo chamador.
* `enforcement="not_implemented"`, `consumption_persisted=False` e
  `replay_protected=False`: a proteção completa depende do ledger #70.
* A verificação no mesmo UID do agente NÃO é uma fronteira de confiança
  implantada. Chave privada, trust store e ledger precisam estar fora do
  acesso de escrita/leitura do agente.
* SHADOW é o único modo aceito.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping, Optional, Sequence

import coord_authority as ca

SCHEMA_VERSION = "BT-COORD-AUTHORIZATION-V1"
REVOCATION_SCHEMA_VERSION = "BT-COORD-REVOCATION-V1"
SIGNATURE_NAMESPACE = "bt-coord-authorization-v1"
REVOCATION_NAMESPACE = "bt-coord-revocation-v1"
DOMAIN = b"BT-COORD-AUTHORIZATION-V1\n"
REVOCATION_DOMAIN = b"BT-COORD-REVOCATION-V1\n"
MAX_SIGNATURE_BYTES = 16 * 1024

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")
_MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*(?:#[A-Za-z0-9._-]+)?$")
_FINGERPRINT = re.compile(r"^SHA256:[A-Za-z0-9+/]+={0,2}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_HEAD = re.compile(r"^[0-9a-f]{40}$")
_KEY_LINE = re.compile(
    r"^(ssh-(?:ed25519|rsa|dss)|ecdsa-sha2-[A-Za-z0-9-]+|sk-[A-Za-z0-9@._-]+)"
    r"\s+([A-Za-z0-9+/=]+)(?:\s+.*)?$"
)


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def payload_sha256(payload: Any) -> str:
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label}: texto não vazio obrigatório")
    return value.strip()


def _ident(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _ID.fullmatch(value):
        raise ValueError(f"{label}: identificador inválido")
    return value


def _digest(value: Any, label: str) -> str:
    value = _text(value, label)
    if not _SHA256.fullmatch(value):
        raise ValueError(f"{label}: sha256 inválido")
    return value


def _utc(value: Any, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError(f"{label}: datetime UTC com fuso obrigatório")
    return value.astimezone(timezone.utc)


def _decimal(value: Any, label: str) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError(f"{label}: decimal obrigatório")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"{label}: decimal inválido") from exc
    if not result.is_finite() or result < 0:
        raise ValueError(f"{label}: decimal não-negativo finito obrigatório")
    return result


def _cost_text(value: Optional[Decimal]) -> Optional[str]:
    """Representação canônica: 0, 1.5, 0.01 — sem zeros à direita."""
    if value is None:
        return None
    result = _decimal(value, "cost_ceiling_usd").normalize()
    return format(result, "f")


def _public_key_line(value: Any, label: str = "public_key") -> str:
    line = _text(value, label)
    match = _KEY_LINE.fullmatch(line)
    if not match:
        raise ValueError(f"{label}: chave OpenSSH inválida")
    return line


def _key_material(line: str) -> str:
    """Linha allowed_signers sem comentário: tipo + base64."""
    match = _KEY_LINE.fullmatch(line)
    if not match:
        raise ValueError("chave OpenSSH inválida")
    return f"{match.group(1)} {match.group(2)}"


@dataclass(frozen=True)
class ApproverKey:
    """Chave pública autorizada; nunca contém chave privada."""

    approver_id: str
    fingerprint: str
    public_key: str
    valid_from: datetime
    valid_until: datetime
    revoked_at: Optional[datetime] = None

    def validate(self) -> None:
        _ident(self.approver_id, "approver_id")
        if not _FINGERPRINT.fullmatch(_text(self.fingerprint, "fingerprint")):
            raise ValueError("fingerprint: SHA256:* inválido")
        _public_key_line(self.public_key)
        start = _utc(self.valid_from, "valid_from")
        end = _utc(self.valid_until, "valid_until")
        if end <= start:
            raise ValueError("janela da chave inválida")
        if self.revoked_at is not None and _utc(self.revoked_at, "revoked_at") < start:
            raise ValueError("revoked_at anterior à validade")

    def allowed_signers_line(self) -> str:
        return f"{self.approver_id} {_key_material(self.public_key)}"

    def active_at(self, now: datetime) -> bool:
        now = _utc(now, "now")
        if now < _utc(self.valid_from, "valid_from") or now > _utc(self.valid_until, "valid_until"):
            return False
        return not (self.revoked_at is not None and _utc(self.revoked_at, "revoked_at") <= now)


@dataclass(frozen=True)
class ApproverAllowlist:
    """Trust store público de chaves; não é uma segunda política de autorização.

    O digest da allowlist é pinado no envelope assinado. Qualquer mudança
    (inclusive revogação de chave) altera o digest e invalida envelopes
    antigos: é fail-closed. Para janelas curtas de piloto isso é aceitável;
    o ledger #70 pode manter versões históricas se necessário.
    """

    entries: tuple[ApproverKey, ...]
    source: str = "operator-public-allowlist"

    def validate(self) -> None:
        if not isinstance(self.entries, tuple) or not self.entries:
            raise ValueError("allowlist vazia")
        seen = set()
        for entry in self.entries:
            entry.validate()
            marker = (entry.approver_id, entry.fingerprint)
            if marker in seen:
                raise ValueError("allowlist com chave duplicada")
            seen.add(marker)

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": "BT-COORD-ALLOWLIST-V1",
            "entries": [
                {
                    "approver_id": entry.approver_id,
                    "fingerprint": entry.fingerprint,
                    "public_key": _key_material(entry.public_key),
                    "valid_from": _utc(entry.valid_from, "valid_from").isoformat(),
                    "valid_until": _utc(entry.valid_until, "valid_until").isoformat(),
                    "revoked_at": None if entry.revoked_at is None else _utc(
                        entry.revoked_at, "revoked_at").isoformat(),
                }
                for entry in sorted(self.entries, key=lambda item: (item.approver_id, item.fingerprint))
            ],
        }

    def digest(self) -> str:
        return hashlib.sha256(canonical_json(self.canonical_material()).encode("utf-8")).hexdigest()

    def find(self, approver_id: str, fingerprint: str) -> Optional[ApproverKey]:
        for entry in self.entries:
            if entry.approver_id == approver_id and entry.fingerprint == fingerprint:
                return entry
        return None


@dataclass(frozen=True)
class SignedAuthorizationEnvelope:
    """Envelope canônico; a assinatura destacada é externa ao dataclass.

    `mission_identity` é opaca: deve vir do recálculo de #64/P14B. O envelope
    não tenta recalculá-la para não criar uma segunda identidade canônica.
    """

    schema: str
    authorization_id: str
    approval_reference: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    mission_identity: str
    payload: Mapping[str, Any]
    payload_sha256: str
    target: ca.Recipient
    model: str
    scope: str
    reviewed_head_sha: str
    operational_digests: Mapping[str, str]
    max_transmissions: int
    cost_ceiling_usd: Optional[Decimal]
    free_only: bool
    issued_at: datetime
    expires_at: datetime
    nonce: str
    mode: str = "SHADOW"

    def __post_init__(self) -> None:
        object.__setattr__(self, "payload", dict(self.payload))
        object.__setattr__(self, "operational_digests", dict(self.operational_digests))

    def validate(self) -> None:
        if self.schema != SCHEMA_VERSION:
            raise ValueError("schema de autorização inválido")
        if self.mode != "SHADOW":
            raise ValueError("SHADOW é o único modo permitido")
        for value, label in (
            (self.authorization_id, "authorization_id"),
            (self.approval_reference, "approval_reference"),
            (self.approver_id, "approver_id"),
            (self.mission_id, "mission_id"),
            (self.operation_id, "operation_id"),
            (self.project_id, "project_id"),
            (self.repository_id, "repository_id"),
            (self.coordinator_id, "coordinator_id"),
            (self.scope, "scope"),
            (self.nonce, "nonce"),
        ):
            _ident(value, label)
        if not _FINGERPRINT.fullmatch(_text(
                self.approver_key_fingerprint, "approver_key_fingerprint")):
            raise ValueError("approver_key_fingerprint inválido")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        _digest(self.mission_identity, "mission_identity")
        _digest(self.payload_sha256, "payload_sha256")
        self.target.validate()
        if not _MODEL.fullmatch(_text(self.model, "model")):
            raise ValueError("model inválido")
        if not _HEAD.fullmatch(self.reviewed_head_sha):
            raise ValueError("reviewed_head_sha inválido")
        if not isinstance(self.operational_digests, Mapping):
            raise ValueError("operational_digests inválido")
        for name, digest in self.operational_digests.items():
            _text(name, "operational_digests.name")
            _digest(digest, f"operational_digests.{name}")
        if not isinstance(self.max_transmissions, int) or isinstance(
                self.max_transmissions, bool) or self.max_transmissions < 1:
            raise ValueError("max_transmissions inválido")
        if not isinstance(self.free_only, bool):
            raise ValueError("free_only deve ser booleano")
        cost = None if self.cost_ceiling_usd is None else _decimal(
            self.cost_ceiling_usd, "cost_ceiling_usd")
        if self.free_only and cost not in (None, Decimal("0")):
            raise ValueError("free_only exige custo zero/ausente")
        issued = _utc(self.issued_at, "issued_at")
        expires = _utc(self.expires_at, "expires_at")
        if expires <= issued:
            raise ValueError("janela de autorização invertida")
        if self.payload_sha256 != payload_sha256(self.payload):
            raise ValueError("payload_sha256 não corresponde ao payload")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": self.schema,
            "authorization_id": self.authorization_id,
            "approval_reference": self.approval_reference,
            "approver_id": self.approver_id,
            "approver_key_fingerprint": self.approver_key_fingerprint,
            "trust_policy_sha256": self.trust_policy_sha256,
            "mission_id": self.mission_id,
            "operation_id": self.operation_id,
            "project_id": self.project_id,
            "repository_id": self.repository_id,
            "coordinator_id": self.coordinator_id,
            "mission_identity": self.mission_identity,
            "payload": self.payload,
            "payload_sha256": self.payload_sha256,
            "target": list(self.target.key()),
            "model": self.model,
            "scope": self.scope,
            "reviewed_head_sha": self.reviewed_head_sha,
            "operational_digests": dict(sorted(self.operational_digests.items())),
            "max_transmissions": self.max_transmissions,
            "cost_ceiling_usd": _cost_text(self.cost_ceiling_usd),
            "free_only": self.free_only,
            "issued_at": _utc(self.issued_at, "issued_at").isoformat(),
            "expires_at": _utc(self.expires_at, "expires_at").isoformat(),
            "nonce": self.nonce,
            "mode": self.mode,
        }

    def to_authority_objects(self) -> tuple[ca.Mission, ca.Authorization]:
        """Compatibilidade estrutural com #72; não avalia autoridade nem consome."""
        self.validate()
        mission = ca.Mission(
            mission_id=self.mission_id, operation_id=self.operation_id,
            project_id=self.project_id, repository_id=self.repository_id,
            mission_identity=self.mission_identity, payload_sha256=self.payload_sha256,
            recipient=self.target, head_sha=self.reviewed_head_sha, scope=self.scope,
            model=self.model, transmissions=0)
        authorization = ca.Authorization(
            reference=self.authorization_id, mission_id=self.mission_id,
            operation_id=self.operation_id, project_id=self.project_id,
            repository_id=self.repository_id, mission_identity=self.mission_identity,
            payload_sha256=self.payload_sha256, recipient=self.target,
            head_sha=self.reviewed_head_sha, scope=self.scope, model=self.model,
            max_transmissions=self.max_transmissions, issued_at=self.issued_at,
            expires_at=self.expires_at, approval_reference=self.approval_reference)
        return mission, authorization


@dataclass(frozen=True)
class ExpectedOperation:
    """Material recomputado pelo chamador confiável (adaptador P14B/ledger).

    `mission_identity` deve vir do recálculo de `MissionEnvelope` em #64; o
    payload é o conteúdo efetivo, não um hash declarado. O verificador compara
    todos os campos com o envelope assinado; nenhum booleano do agente entra
    como permissão.
    """

    mission_id: str
    operation_id: str
    project_id: str
    repository_id: str
    coordinator_id: str
    mission_identity: str
    payload: Mapping[str, Any]
    target: ca.Recipient
    model: str
    scope: str
    reviewed_head_sha: str
    operational_digests: Mapping[str, str]
    max_transmissions: int
    cost_ceiling_usd: Optional[Decimal]
    free_only: bool

    def __post_init__(self) -> None:
        object.__setattr__(self, "payload", dict(self.payload))
        object.__setattr__(self, "operational_digests", dict(self.operational_digests))

    @property
    def payload_sha256(self) -> str:
        return payload_sha256(self.payload)

    def validate(self) -> None:
        for value, label in (
            (self.mission_id, "mission_id"),
            (self.operation_id, "operation_id"),
            (self.project_id, "project_id"),
            (self.repository_id, "repository_id"),
            (self.coordinator_id, "coordinator_id"),
            (self.scope, "scope"),
        ):
            _ident(value, label)
        _digest(self.mission_identity, "mission_identity")
        self.target.validate()
        if not _MODEL.fullmatch(_text(self.model, "model")):
            raise ValueError("model inválido")
        if not _HEAD.fullmatch(self.reviewed_head_sha):
            raise ValueError("reviewed_head_sha inválido")
        if not isinstance(self.operational_digests, Mapping):
            raise ValueError("operational_digests inválido")
        for name, digest in self.operational_digests.items():
            _text(name, "operational_digests.name")
            _digest(digest, f"operational_digests.{name}")
        if not isinstance(self.max_transmissions, int) or isinstance(
                self.max_transmissions, bool) or self.max_transmissions < 1:
            raise ValueError("max_transmissions inválido")
        if not isinstance(self.free_only, bool):
            raise ValueError("free_only deve ser booleano")
        cost = None if self.cost_ceiling_usd is None else _decimal(
            self.cost_ceiling_usd, "cost_ceiling_usd")
        if self.free_only and cost not in (None, Decimal("0")):
            raise ValueError("free_only exige custo zero/ausente")
        if not isinstance(self.payload, Mapping):
            raise ValueError("payload deve ser mapping")
        # Falha cedo se o payload não for canonicamente serializável.
        payload_sha256(self.payload)

    def mismatches(self, envelope: SignedAuthorizationEnvelope) -> tuple[str, ...]:
        self.validate()
        comparisons = (
            ("mission_id", self.mission_id, envelope.mission_id),
            ("operation_id", self.operation_id, envelope.operation_id),
            ("project_id", self.project_id, envelope.project_id),
            ("repository_id", self.repository_id, envelope.repository_id),
            ("coordinator_id", self.coordinator_id, envelope.coordinator_id),
            ("mission_identity", self.mission_identity, envelope.mission_identity),
            ("payload", canonical_json(self.payload), canonical_json(envelope.payload)),
            ("payload_sha256", self.payload_sha256, envelope.payload_sha256),
            ("target", self.target.key(), envelope.target.key()),
            ("model", self.model, envelope.model),
            ("scope", self.scope, envelope.scope),
            ("reviewed_head_sha", self.reviewed_head_sha, envelope.reviewed_head_sha),
            ("operational_digests", dict(sorted(self.operational_digests.items())),
             dict(sorted(envelope.operational_digests.items()))),
            ("max_transmissions", self.max_transmissions, envelope.max_transmissions),
            ("cost_ceiling_usd", _cost_text(self.cost_ceiling_usd),
             _cost_text(envelope.cost_ceiling_usd)),
            ("free_only", self.free_only, envelope.free_only),
        )
        return tuple(name for name, expected, actual in comparisons if expected != actual)


def canonical_envelope_bytes(envelope: SignedAuthorizationEnvelope) -> bytes:
    """Bytes assinados; o assinador humano usa exatamente estes bytes."""
    return DOMAIN + canonical_json(envelope.canonical_material()).encode("utf-8")


def authorization_digest(envelope: SignedAuthorizationEnvelope) -> str:
    return hashlib.sha256(canonical_envelope_bytes(envelope)).hexdigest()


@dataclass(frozen=True)
class SignedRevocation:
    schema: str
    authorization_digest: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    revoked_at: datetime
    reason: str
    nonce: str

    def validate(self) -> None:
        if self.schema != REVOCATION_SCHEMA_VERSION:
            raise ValueError("schema de revogação inválido")
        _digest(self.authorization_digest, "authorization_digest")
        _digest(self.trust_policy_sha256, "trust_policy_sha256")
        _ident(self.approver_id, "approver_id")
        if not _FINGERPRINT.fullmatch(_text(
                self.approver_key_fingerprint, "approver_key_fingerprint")):
            raise ValueError("approver_key_fingerprint inválido")
        _utc(self.revoked_at, "revoked_at")
        _text(self.reason, "reason")
        _ident(self.nonce, "nonce")

    def canonical_material(self) -> dict[str, Any]:
        self.validate()
        return {
            "schema": self.schema,
            "authorization_digest": self.authorization_digest,
            "approver_id": self.approver_id,
            "approver_key_fingerprint": self.approver_key_fingerprint,
            "trust_policy_sha256": self.trust_policy_sha256,
            "revoked_at": _utc(self.revoked_at, "revoked_at").isoformat(),
            "reason": self.reason,
            "nonce": self.nonce,
        }


def canonical_revocation_bytes(statement: SignedRevocation) -> bytes:
    return REVOCATION_DOMAIN + canonical_json(statement.canonical_material()).encode("utf-8")


@dataclass(frozen=True)
class VerificationResult:
    accepted: bool
    code: str
    detail: str
    authorization_digest: Optional[str] = None
    verified: Optional["VerifiedEnvelope"] = None
    revocation_checked: bool = False
    mismatches: tuple[str, ...] = ()


@dataclass(frozen=True)
class RevocationResult:
    verified: bool
    effective: bool
    code: str
    detail: str
    authorization_digest: str


@dataclass(frozen=True)
class VerifiedEnvelope:
    envelope_digest: str
    authorization_id: str
    approver_id: str
    approver_key_fingerprint: str
    trust_policy_sha256: str
    mission_identity: str
    payload_sha256: str
    target_key: tuple[str, ...]
    model: str
    cost_ceiling_usd: Optional[str]
    issued_at: datetime
    expires_at: datetime
    signature_verified: bool = True
    revocation_checked: bool = True
    revoked: bool = False
    expected_checked: bool = True
    operation_bound: bool = True
    mode: str = "SHADOW"
    # Limites explícitos: este componente não é o ledger.
    enforcement: str = "not_implemented"
    consumption_persisted: bool = False
    replay_protected: bool = False


def _failure(code: str, detail: str, digest: Optional[str] = None,
             revocation_checked: bool = False,
             mismatches: tuple[str, ...] = ()) -> VerificationResult:
    return VerificationResult(False, code, detail, digest, None, revocation_checked, mismatches)


def _run_ssh(args: Sequence[str], *, input_bytes: Optional[bytes] = None,
             cwd: Optional[str] = None) -> subprocess.CompletedProcess[bytes]:
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LC_ALL": "C",
        "LANG": "C",
    }
    return subprocess.run(
        list(args), input=input_bytes, cwd=cwd, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=False)


def public_key_fingerprint(public_key: str) -> str:
    """Fingerprint SHA256 da chave pública; nunca lê chave privada."""
    line = _public_key_line(public_key)
    ssh_keygen = shutil.which("ssh-keygen")
    if not ssh_keygen:
        raise ValueError("ssh-keygen indisponível")
    with tempfile.TemporaryDirectory(prefix="coord06b-fp-") as td:
        path = os.path.join(td, "key.pub")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(line + "\n")
        result = _run_ssh((ssh_keygen, "-lf", path, "-E", "sha256"))
    if result.returncode != 0:
        raise ValueError("falha ao calcular fingerprint da chave pública")
    match = re.search(rb"(SHA256:[A-Za-z0-9+/]+={0,2})", result.stdout)
    if not match:
        raise ValueError("fingerprint SHA256 ausente")
    return match.group(1).decode("ascii")


def verify_openssh_signature(*, data: bytes, signature: Any, approver: ApproverKey,
                             namespace: str) -> tuple[bool, str]:
    """Verifica assinatura OpenSSH destacada; não assina, não lê chave privada."""
    if not signature:
        return False, "assinatura ausente"
    if isinstance(signature, str):
        signature = signature.encode("utf-8")
    if not isinstance(signature, (bytes, bytearray)):
        return False, "assinatura inválida"
    if len(signature) > MAX_SIGNATURE_BYTES:
        return False, "assinatura excessiva"
    ssh_keygen = shutil.which("ssh-keygen")
    if not ssh_keygen:
        return False, "ssh-keygen indisponível"
    try:
        with tempfile.TemporaryDirectory(prefix="coord06b-verify-") as td:
            allowed = os.path.join(td, "allowed_signers")
            sig_path = os.path.join(td, "signature.sig")
            with open(allowed, "w", encoding="utf-8") as handle:
                handle.write(approver.allowed_signers_line() + "\n")
            with open(sig_path, "wb") as handle:
                handle.write(bytes(signature))
            result = _run_ssh(
                (ssh_keygen, "-Y", "verify", "-f", allowed, "-I", approver.approver_id,
                 "-n", namespace, "-s", sig_path),
                input_bytes=data, cwd=td)
    except (OSError, subprocess.SubprocessError) as exc:
        return False, f"falha ao verificar assinatura: {type(exc).__name__}"
    if result.returncode != 0:
        return False, "assinatura OpenSSH inválida"
    return True, "assinatura OpenSSH válida"


def verify_signed_envelope(
    envelope: SignedAuthorizationEnvelope,
    signature: Any,
    allowlist: ApproverAllowlist,
    *,
    now: datetime,
    expected: ExpectedOperation,
    revocations: Sequence[tuple[SignedRevocation, Any]] = (),
    expected_trust_policy_sha256: Optional[str] = None,
) -> VerificationResult:
    """Verifica assinatura e vínculo integral; não consome nem persiste.

    `expected` é obrigatório: uma assinatura válida isolada não é autorização.
    O chamador confiável (adaptador P14B/ledger) recalcula `mission_identity`
    em #64, o payload efetivo, o alvo, o modelo, o custo e o HEAD; este módulo
    compara tudo com o envelope assinado.
    """
    now = _utc(now, "now")
    digest = None
    try:
        envelope.validate()
        allowlist.validate()
        expected.validate()
        digest = authorization_digest(envelope)
        trust_digest = allowlist.digest()
        if envelope.trust_policy_sha256 != trust_digest:
            return _failure("TRUST_POLICY_MISMATCH",
                            "envelope não vincula a allowlist atual", digest)
        if (expected_trust_policy_sha256 is not None
                and expected_trust_policy_sha256 != trust_digest):
            return _failure("TRUST_POLICY_MISMATCH",
                            "digest esperado da allowlist divergente", digest)
        approver = allowlist.find(envelope.approver_id,
                                  envelope.approver_key_fingerprint)
        if approver is None:
            return _failure("APPROVER_KEY_UNKNOWN",
                            "chave do aprovador ausente da allowlist", digest)
        actual_fingerprint = public_key_fingerprint(approver.public_key)
        if actual_fingerprint != approver.fingerprint or actual_fingerprint != (
                envelope.approver_key_fingerprint):
            return _failure("FINGERPRINT_MISMATCH",
                            "fingerprint da chave não corresponde ao envelope",
                            digest)
        if not approver.active_at(now):
            return _failure("APPROVER_KEY_INACTIVE",
                            "chave do aprovador expirada/revogada/futura", digest)
        if now < _utc(envelope.issued_at, "issued_at"):
            return _failure("AUTHORIZATION_NOT_YET_VALID",
                            "autorização ainda não válida", digest)
        if now > _utc(envelope.expires_at, "expires_at"):
            return _failure("AUTHORIZATION_EXPIRED",
                            "autorização expirada", digest)
        verified_signature, reason = verify_openssh_signature(
            data=canonical_envelope_bytes(envelope), signature=signature,
            approver=approver, namespace=SIGNATURE_NAMESPACE)
        if not verified_signature:
            return _failure("SIGNATURE_INVALID", reason, digest)
        mismatches = expected.mismatches(envelope)
        if mismatches:
            return _failure(
                "OPERATION_MISMATCH",
                "envelope não corresponde à operação esperada: " + ", ".join(mismatches),
                digest, mismatches=mismatches)
        revocation_checked = False
        for statement, revocation_signature in revocations:
            revocation = verify_signed_revocation(
                statement, revocation_signature, allowlist, now=now)
            revocation_checked = True
            if revocation.verified and revocation.effective and (
                    statement.authorization_digest == digest):
                return _failure("AUTHORIZATION_REVOKED",
                                "autorização revogada por declaração assinada",
                                digest, True)
        verified = VerifiedEnvelope(
            envelope_digest=digest,
            authorization_id=envelope.authorization_id,
            approver_id=envelope.approver_id,
            approver_key_fingerprint=envelope.approver_key_fingerprint,
            trust_policy_sha256=trust_digest,
            mission_identity=envelope.mission_identity,
            payload_sha256=envelope.payload_sha256,
            target_key=envelope.target.key(),
            model=envelope.model,
            cost_ceiling_usd=_cost_text(envelope.cost_ceiling_usd),
            issued_at=_utc(envelope.issued_at, "issued_at"),
            expires_at=_utc(envelope.expires_at, "expires_at"),
            revocation_checked=revocation_checked,
        )
        return VerificationResult(True, "OK", "assinatura e material válidos",
                                  digest, verified, revocation_checked)
    except (ValueError, TypeError, OSError) as exc:
        return _failure("INVALID_ENVELOPE", str(exc), digest)


def verify_signed_revocation(statement: SignedRevocation, signature: Any,
                             allowlist: ApproverAllowlist, *, now: datetime
                             ) -> RevocationResult:
    """Verifica revogação assinada; não persiste nem aplica efeito externo."""
    now = _utc(now, "now")
    try:
        statement.validate()
        allowlist.validate()
        if statement.trust_policy_sha256 != allowlist.digest():
            return RevocationResult(False, False, "TRUST_POLICY_MISMATCH",
                                    "allowlist divergente", statement.authorization_digest)
        approver = allowlist.find(statement.approver_id,
                                  statement.approver_key_fingerprint)
        if approver is None:
            return RevocationResult(False, False, "APPROVER_KEY_UNKNOWN",
                                    "chave do aprovador ausente", statement.authorization_digest)
        actual = public_key_fingerprint(approver.public_key)
        if actual != approver.fingerprint or actual != statement.approver_key_fingerprint:
            return RevocationResult(False, False, "FINGERPRINT_MISMATCH",
                                    "fingerprint divergente", statement.authorization_digest)
        if not approver.active_at(now):
            return RevocationResult(False, False, "APPROVER_KEY_INACTIVE",
                                    "chave inativa", statement.authorization_digest)
        ok, reason = verify_openssh_signature(
            data=canonical_revocation_bytes(statement), signature=signature,
            approver=approver, namespace=REVOCATION_NAMESPACE)
        if not ok:
            return RevocationResult(False, False, "REVOCATION_SIGNATURE_INVALID",
                                    reason, statement.authorization_digest)
        effective = _utc(statement.revoked_at, "revoked_at") <= now
        return RevocationResult(True, effective,
                                "OK" if effective else "REVOCATION_NOT_YET",
                                "revogação verificada" if effective else "ainda não efetiva",
                                statement.authorization_digest)
    except (ValueError, TypeError, OSError) as exc:
        return RevocationResult(False, False, "INVALID_REVOCATION", str(exc),
                                getattr(statement, "authorization_digest", ""))
