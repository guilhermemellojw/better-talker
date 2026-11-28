#!/usr/bin/env python3
"""COORD-OPS/P9.2 — driver sintético do piloto e protocolo do signer.

O modo `pilot` orquestra os nove passos no socket local (ou em um cliente
injetado nos testes) e nunca abre rede, nunca chama transporte real e nunca lê
chave privada. O modo `signer-helper` é executado separadamente, sob o UID do
signer, e produz envelopes/atestações; o driver recebe apenas material público
assinado.

Uso no futuro (não executado nesta rodada):

    coord_ops_p9_pilot.py --mode synthetic --scenario positive \
      --socket /run/better-talker-coord/service.sock \
      --service-uid 9101 --service-gid 9101 --service-id <id> \
      --signer-helper "runuser -u bt-coord-signer -- python3 ... --role signer-helper ..." \
      --work-dir ... --artifacts ...

Testes host-only injetam cliente falso; nenhum teste é certificação do socket
real.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Optional, Protocol, Sequence

import coord_authority_signed as cas
import coord_communication as cc
import coord_trust_crossuid_common as common
import coord_trust_service as svc

MODE = "synthetic"
MODEL = "opencode/hy4-flash-free"
PAYLOAD_SCHEMA = "BT-COORD-P9-PAYLOAD-V1"
PROJECT = "better-talker"
REPOSITORY = "guilhermemellojw/better-talker"
TARGET_AGENT = "agent-s-synth"
TARGET_INSTANCE = "p9-synth-1"
TARGET_LOCATION = "/srv/p9/fixtures/agent-s"
GATES = tuple(
    ["H0", "H1", "H2", "H3", "H3b"]
    + [f"H{i}" for i in range(4, 16)]
)
NEGATIVE_GATES = ("H4", "H5", "H6", "H7", "H8", "H9", "H10", "H12")
SUMMARY_SCHEMA = "BT-COORD-P9-PILOT-SUMMARY-V1"


class DriverError(RuntimeError):
    """Falha interna do driver; a mensagem não contém material sensível."""


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _code(response: Mapping[str, Any]) -> Optional[str]:
    error = response.get("error")
    if isinstance(error, Mapping):
        value = error.get("code")
        return str(value) if value else None
    return None


def _reason(response: Mapping[str, Any]) -> Optional[str]:
    error = response.get("error")
    if isinstance(error, Mapping):
        value = error.get("reason")
        return str(value) if value else None
    return None


def _state(response: Mapping[str, Any]) -> Optional[str]:
    result = response.get("result")
    if isinstance(result, Mapping):
        value = result.get("state")
        return str(value) if value else None
    return None


def _ok(response: Mapping[str, Any]) -> bool:
    return response.get("ok") is True


def _signature_b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


class SignerPort(Protocol):
    """Porta pública: o driver nunca recebe chave privada."""

    def authorization(self, *, mission: cc.MissionEnvelope, model: str,
                      environment: str, now: datetime) -> Mapping[str, Any]:
        ...

    def attestation(self, *, mission: cc.MissionEnvelope,
                    authorization_digest: str, receipt: Mapping[str, Any],
                    report: Mapping[str, Any], evidence: Mapping[str, Any],
                    outcome: str, now: datetime) -> Mapping[str, Any]:
        ...


class SubprocessSigner:
    """Chama um helper externo; não lê chave nem aceita caminho de chave."""

    def __init__(self, command: Sequence[str], *, timeout: float = 30.0) -> None:
        if not command:
            raise ValueError("signer helper vazio")
        self.command = list(command)
        self.timeout = float(timeout)

    def _call(self, request: Mapping[str, Any]) -> Mapping[str, Any]:
        try:
            result = subprocess.run(
                self.command,
                input=common.canonical_json(dict(request)),
                text=True,
                capture_output=True,
                timeout=self.timeout,
                check=False,
                env={
                    "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                    "LANG": "C",
                    "LC_ALL": "C",
                },
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise DriverError("signer helper indisponível") from exc
        if result.returncode != 0:
            raise DriverError("signer helper recusou a operação")
        try:
            value = json.loads(result.stdout)
        except ValueError as exc:
            raise DriverError("signer helper devolveu JSON inválido") from exc
        if not isinstance(value, Mapping):
            raise DriverError("signer helper devolveu resposta inválida")
        return value

    @staticmethod
    def _validate_artifact(value: Mapping[str, Any]) -> Mapping[str, Any]:
        signature = value.get("signature")
        if not isinstance(signature, str) or not signature:
            raise DriverError("assinatura ausente")
        try:
            base64.b64decode(signature, validate=True)
        except (ValueError, TypeError) as exc:
            raise DriverError("assinatura não é base64") from exc
        return value

    def authorization(self, *, mission: cc.MissionEnvelope, model: str,
                      environment: str, now: datetime) -> Mapping[str, Any]:
        value = self._call({
            "op": "authorize", "mission": svc._mission_mapping(mission),
            "model": model, "environment": environment, "now": _iso(now),
        })
        for key in ("envelope", "signature", "authorization_digest"):
            if key not in value:
                raise DriverError("artefato de autorização incompleto")
        if not isinstance(value["envelope"], Mapping):
            raise DriverError("envelope não é mapping")
        digest = value["authorization_digest"]
        if not isinstance(digest, str) or len(digest) != 64:
            raise DriverError("digest de autorização inválido")
        return self._validate_artifact(value)

    def attestation(self, *, mission: cc.MissionEnvelope,
                    authorization_digest: str, receipt: Mapping[str, Any],
                    report: Mapping[str, Any], evidence: Mapping[str, Any],
                    outcome: str, now: datetime) -> Mapping[str, Any]:
        value = self._call({
            "op": "attest", "mission": svc._mission_mapping(mission),
            "authorization_digest": authorization_digest,
            "receipt": dict(receipt), "report": dict(report),
            "evidence": dict(evidence), "outcome": outcome, "now": _iso(now),
        })
        for key in ("attestation", "signature"):
            if key not in value:
                raise DriverError("artefato de atestação incompleto")
        if not isinstance(value["attestation"], Mapping):
            raise DriverError("atestação não é mapping")
        return self._validate_artifact(value)


class PilotDriver:
    """Orquestra a homologação sintética; cliente/transporte são injetados."""

    def __init__(
        self,
        *,
        client: Optional[Any] = None,
        role_clients: Optional[Mapping[str, Any]] = None,
        signer: SignerPort,
        work_dir: str,
        artifacts_dir: str,
        source_head: str = "UNKNOWN",
        clock=_now,
        socket_real: bool = False,
        scenario: str = "positive",
    ) -> None:
        if client is None and role_clients is None:
            raise ValueError("cliente do serviço é obrigatório")
        self.client = client
        self.role_clients = dict(role_clients or {})
        self.signer = signer
        self.work_dir = os.path.abspath(work_dir)
        self.artifacts_dir = os.path.abspath(artifacts_dir)
        self.source_head = source_head
        self.clock = clock
        self.socket_real = bool(socket_real)
        self.scenario = scenario
        os.makedirs(self.work_dir, mode=0o700, exist_ok=True)
        os.makedirs(self.artifacts_dir, mode=0o700, exist_ok=True)
        self.trace: list[dict[str, Any]] = []
        self.results: dict[str, dict[str, Any]] = {
            gate: {"gate": gate, "status": "NOT_RUN",
                   "detail": "não executado pelo driver"}
            for gate in GATES
        }
        self.hashes: dict[str, str] = {}
        self.receipt: Optional[dict[str, Any]] = None
        self.report: Optional[dict[str, Any]] = None
        self.evidence: Optional[dict[str, Any]] = None

    # ---------- infraestrutura de cenário ----------

    def _client_for(self, role: str):
        if self.role_clients:
            client = self.role_clients.get(role)
            if client is None:
                raise DriverError("papel sem cliente: " + role)
            return client
        if self.client is None:
            raise DriverError("cliente ausente")
        return self.client

    def _request(self, role: str, action: str,
                 payload: Mapping[str, Any], *,
                 request_id: str) -> dict[str, Any]:
        client = self._client_for(role)
        try:
            response = client.request(
                action, dict(payload), request_id=request_id)
        except Exception as exc:  # noqa: BLE001 - falha fechada e sanitizada
            self.trace.append({
                "role": role, "action": action, "request_id": request_id,
                "ok": False, "code": "SERVICE_ERROR",
                "reason": "INTERNAL", "exception": type(exc).__name__,
            })
            return {"ok": False,
                    "error": {"code": "SERVICE_ERROR",
                              "message": "falha interna do serviço",
                              "reason": "INTERNAL"}}
        if not isinstance(response, Mapping):
            return {"ok": False,
                    "error": {"code": "SERVICE_ERROR",
                              "message": "resposta inválida",
                              "reason": "INTERNAL"}}
        mapping = dict(response)
        self.trace.append({
            "role": role, "action": action, "request_id": request_id,
            "ok": _ok(mapping), "code": _code(mapping),
            "reason": _reason(mapping), "state": _state(mapping),
        })
        return mapping

    def _set(self, gate: str, status: str, detail: str, *,
             code: Optional[str] = None,
             evidence: Optional[Mapping[str, Any]] = None) -> None:
        self.results[gate] = {
            "gate": gate, "status": status, "detail": detail,
            "code": code, "evidence": dict(evidence or {}),
        }

    def _pass(self, gate: str, detail: str, *,
              code: Optional[str] = None,
              evidence: Optional[Mapping[str, Any]] = None) -> None:
        self._set(gate, "PASS", detail, code=code, evidence=evidence)

    def _fail(self, gate: str, detail: str, *,
              code: Optional[str] = None,
              evidence: Optional[Mapping[str, Any]] = None) -> None:
        self._set(gate, "FAIL", detail, code=code, evidence=evidence)

    def _blocked(self, gate: str, detail: str,
                 code: Optional[str] = None) -> None:
        self._set(gate, "BLOCKED", detail, code=code)

    # ---------- fixtures ----------

    def _mission(self, tag: str, *, session: Optional[str] = None) -> cc.MissionEnvelope:
        now = self.clock()
        payload = {
            "schema": PAYLOAD_SCHEMA,
            "fixture": "P9-REPORT-001",
            "tag": tag,
            "synthetic": True,
        }
        return cc.MissionEnvelope(
            schema_version=cc.MISSION_SCHEMA_VERSION,
            project_id=PROJECT, repository_id=REPOSITORY,
            mission_id="MISS-P9-SYNTH-" + tag.upper(),
            operation_id="OP-P9-SYNTH-" + tag.upper(),
            coordinator_id="coord-ops-p9-synthetic",
            target_agent=TARGET_AGENT,
            target_session_id=session or ("ses-p9-" + tag.lower()),
            target_instance_id=TARGET_INSTANCE,
            target_location=TARGET_LOCATION,
            objective="classificar relatório sintético e propor o próximo encaminhamento",
            payload=payload, payload_sha256=cc.payload_sha256(payload),
            expected_deliverables=("relatório sintético classificado",),
            acceptance_criteria=("identidade da missão devolvida",
                                 "dependência pendente identificada",
                                 "próxima proposta registrada"),
            ownership_constraints=("somente fixtures sintéticas",),
            authorization_reference="APP-P9-SYNTH-" + tag.upper(),
            created_at=now, expires_at=now + timedelta(minutes=30),
            timeout=60, scope="p9-synthetic",
            expected_head_sha="b" * 40, expected_branch="fixture/p9",
        )

    def _propose_payload(self, mission: cc.MissionEnvelope) -> dict[str, Any]:
        return {
            "mission": svc._mission_mapping(mission),
            "model": MODEL, "environment": "SHADOW",
            "operational_digests": {
                "coord_trust_service.py": "d" * 64,
                "coord_ledger.py": "e" * 64,
            },
            "max_transmissions": 1, "cost_ceiling_usd": "0",
            "free_only": True,
            "authorization_reference": mission.authorization_reference,
        }

    def _target_payload(self, mission: cc.MissionEnvelope) -> dict[str, Any]:
        return {
            "mission_id": mission.mission_id,
            "agent": mission.target_agent,
            "session_id": mission.target_session_id,
            "instance_id": mission.target_instance_id,
            "worktree": mission.target_location,
        }

    def _report(self, mission: cc.MissionEnvelope) -> cc.MissionReport:
        now = self.clock()
        return cc.MissionReport(
            schema_version=cc.REPORT_SCHEMA_VERSION,
            project_id=mission.project_id, mission_id=mission.mission_id,
            operation_id=mission.operation_id,
            agent_id=mission.target_agent,
            session_id=mission.target_session_id,
            message_id="msg-report-" + mission.operation_id,
            status="COMPLETED",
            head_sha=mission.expected_head_sha,
            branch=mission.expected_branch, pull_request=None,
            test_evidence=({"name": "p9-synthetic", "result": "PASS"},),
            blockers=(), artifacts=("p9-report.json",),
            completed_at=now + timedelta(minutes=1),
            acceptance_results={c: True for c in mission.acceptance_criteria},
            untrusted_text="Fixture P9; dado não confiável.",
        )

    @staticmethod
    def _report_mapping(mission: cc.MissionEnvelope,
                        report: cc.MissionReport,
                        receipt: Mapping[str, Any]) -> dict[str, Any]:
        """Relatório completo exigido pelo ledger, sem confiar no agente."""
        value = report.to_dict()
        value.update({
            "repository_id": mission.repository_id,
            "payload_sha256": mission.payload_sha256,
            "reply_to": receipt.get("observed_message_id"),
        })
        return value

    def _inbox_report(self) -> dict[str, Any]:
        return {
            "schema": "BT-COORD-P9-INBOX-V1",
            "mission_id": "MISS-P9-SYNTH-000",
            "status": "BLOCKED",
            "task": "P9-DOC-001",
            "blockers": ["P9-DECISION-002"],
            "dependencies": ["P9-DECISION-002"],
            "synthetic": True,
        }

    def _authorization(self, mission: cc.MissionEnvelope, *,
                       tamper: bool = False) -> dict[str, Any]:
        value = dict(self.signer.authorization(
            mission=mission, model=MODEL, environment="SHADOW",
            now=self.clock()))
        if tamper:
            value = dict(value)
            value["signature"] = _signature_b64(b"tampered-signature")
        return value

    def _attestation(self, mission: cc.MissionEnvelope, digest: str,
                     *, tamper: bool = False) -> dict[str, Any]:
        if self.receipt is None or self.report is None or self.evidence is None:
            raise DriverError("evidências ausentes para atestação")
        value = dict(self.signer.attestation(
            mission=mission, authorization_digest=digest,
            receipt=self.receipt, report=self.report,
            evidence=self.evidence, outcome="COMPLETED",
            now=self.clock()))
        if tamper:
            value = dict(value)
            attestation = dict(value["attestation"])
            attestation["report_sha256"] = "0" * 64
            value["attestation"] = attestation
        return value

    def _synthetic_dispatch(self, mission: cc.MissionEnvelope,
                            *, outcome: str = "success"
                            ) -> tuple[Optional[dict[str, Any]],
                                       Optional[cc.MissionReport],
                                       Optional[dict[str, Any]]]:
        auth = cc.Authorization(
            reference=mission.authorization_reference,
            project_id=mission.project_id, repository_id=mission.repository_id,
            target=mission.target, payload_sha256=mission.payload_sha256,
            mission_identity=cc.canonical_mission_identity(mission),
            scope=mission.scope, issued_at=self.clock(),
            expires_at=self.clock() + timedelta(hours=1), synthetic=True)
        transport = cc.SimulatedTransport(
            (mission.target,), {mission.operation_id: outcome})
        prepared = transport.prepare(mission, auth)
        try:
            receipt = transport.transmit(prepared)
        except cc.TransportError:
            return None, None, None
        report = self._report(mission)
        transport.simulate_deliver(mission.operation_id)
        transport.simulate_start(mission.operation_id)
        transport.simulate_report(mission.operation_id, report)
        evidence = dict(receipt.evidence)
        evidence.update({
            "source": "synthetic", "environment": "SHADOW",
            "service_contacted": False, "transport_real": False,
            "http_posts": 0,
        })
        receipt_mapping = {
            "source": "synthetic", "environment": "SHADOW",
            "service_contacted": False, "transport_real": False,
            "operation_id": mission.operation_id,
            "payload_sha256": mission.payload_sha256,
            "agent_id": mission.target_agent,
            "session_id": mission.target_session_id,
            "instance_id": mission.target_instance_id,
            "location": mission.target_location,
            "observed_message_id": receipt.message_id,
            "outcome": receipt.outcome,
        }
        return receipt_mapping, report, evidence

    # ---------- nove passos ----------

    def run_positive(self, *, roles: bool = False) -> bool:
        gate = "H3b" if roles else "H3"
        mission = self._mission("001", session="ses-p9-synth-001")
        inbox = self._inbox_report()
        if inbox.get("mission_id") != "MISS-P9-SYNTH-000":
            self._fail(gate, "relatório sintético ausente")
            return False
        dependencies = tuple(inbox.get("dependencies") or ())
        if "P9-DECISION-002" not in dependencies:
            self._fail(gate, "dependência pendente não detectada")
            return False
        self.hashes["inbox_report_sha256"] = common.digest(inbox)

        proposed = self._request(
            "coordinator" if roles else "executor", "propose",
            self._propose_payload(mission),
            request_id=("roles-propose" if roles else "p9-propose"))
        if not _ok(proposed) or _state(proposed) != "PROPOSED":
            self._fail(gate, "propose não registrou PROPOSED",
                       code=_code(proposed), evidence={"response": proposed})
            return False

        auth = self._authorization(mission)
        digest = auth["authorization_digest"]
        self.hashes["authorization_digest"] = digest
        authorized = self._request(
            "coordinator" if roles else "executor", "authorize",
            {"mission_id": mission.mission_id,
             "envelope": auth["envelope"],
             "signature": auth["signature"]},
            request_id=("roles-authorize" if roles else "p9-authorize"))
        if not _ok(authorized) or _state(authorized) != "AUTHORIZED":
            self._fail(gate, "authorize não aceitou envelope",
                       code=_code(authorized), evidence={"response": authorized})
            return False

        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id=("roles-reserve" if roles else "p9-reserve"))
        lease = (reserved.get("result") or {}).get("lease_id") \
            if isinstance(reserved.get("result"), Mapping) else None
        if not _ok(reserved) or not isinstance(lease, str) or not lease:
            self._fail(gate, "reserva não devolveu lease",
                       code=_code(reserved), evidence={"response": reserved})
            return False
        self.hashes["lease_id"] = lease

        asserted = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id,
             "authorization_digest": digest, "lease_id": lease},
            request_id=("roles-assert" if roles else "p9-assert"))
        if not _ok(asserted):
            self._fail(gate, "assert não aceitou lease",
                       code=_code(asserted), evidence={"response": asserted})
            return False

        receipt, report, evidence = self._synthetic_dispatch(mission)
        if receipt is None or report is None or evidence is None:
            self._fail(gate, "despacho sintético não produziu receipt")
            return False
        assessed = cc.assess_report(mission, report)
        if assessed.classification != "completed":
            self._fail(gate, "relatório sintético não foi associado/completo",
                       evidence={"assessment": assessed.classification})
            return False
        report_mapping = self._report_mapping(mission, report, receipt)
        self.receipt, self.report, self.evidence = receipt, report_mapping, evidence
        self.hashes["receipt_sha256"] = common.digest(receipt)
        self.hashes["report_sha256"] = common.digest(report_mapping)
        self.hashes["evidence_sha256"] = common.digest(evidence)

        recorded = self._request(
            "executor", "record_evidence",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease, "receipt": receipt, "evidence": evidence},
            request_id=("roles-evidence" if roles else "p9-evidence"))
        if not _ok(recorded):
            self._fail(gate, "record_evidence recusado",
                       code=_code(recorded), evidence={"response": recorded})
            return False

        attestation = self._attestation(mission, digest)
        reconciled = self._request(
            "executor", "reconcile",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease, "target_state": "COMPLETED",
             "attestation": attestation["attestation"],
             "attestation_signature": attestation["signature"],
             "report": report_mapping, "evidence": evidence},
            request_id=("roles-reconcile" if roles else "p9-reconcile"))
        if not _ok(reconciled) or _state(reconciled) != "COMPLETED":
            self._fail(gate, "reconciliação não concluiu",
                       code=_code(reconciled),
                       evidence={"response": reconciled})
            return False

        status = self._request(
            "observer" if roles else "executor", "status",
            {"mission_id": mission.mission_id},
            request_id=("roles-status" if roles else "p9-status"))
        status_result = (status.get("result") or {}) \
            if isinstance(status.get("result"), Mapping) else {}
        if not _ok(status) or status_result.get("quarantined") is not False:
            self._fail(gate, "status pós-reconciliação divergente",
                       code=_code(status), evidence={"response": status})
            return False

        next_mission = self._mission("002", session="ses-p9-next")
        next_proposal = self._request(
            "coordinator" if roles else "executor", "propose",
            self._propose_payload(next_mission),
            request_id=("roles-next" if roles else "p9-next"))
        if not _ok(next_proposal) or _state(next_proposal) != "PROPOSED":
            self._fail(gate, "próxima missão não ficou PROPOSED",
                       code=_code(next_proposal),
                       evidence={"response": next_proposal})
            return False
        if any(item["action"] in {"authorize", "reserve", "assert"}
               and item["request_id"].endswith("next") for item in self.trace):
            self._fail(gate, "próxima missão foi despachada")
            return False

        self._pass(gate, "9 passos concluídos; próxima missão PROPOSED",
                   evidence={
                       "mission_id": mission.mission_id,
                       "lease_id": lease,
                       "next_mission_id": next_mission.mission_id,
                       "next_state": "PROPOSED",
                       "transport_real": False,
                       "http_posts": 0,
                       "signer_separate": True,
                   })
        return True

    # ---------- negativos ----------

    def run_invalid_authorization(self) -> bool:
        mission = self._mission("NEG-AUTH")
        proposed = self._request(
            "executor", "propose", self._propose_payload(mission),
            request_id="neg-auth-propose")
        if not _ok(proposed):
            self._fail("H4", "propose falhou", code=_code(proposed))
            return False
        auth = self._authorization(mission, tamper=True)
        authorized = self._request(
            "executor", "authorize",
            {"mission_id": mission.mission_id, "envelope": auth["envelope"],
             "signature": auth["signature"]},
            request_id="neg-auth-authorize")
        if _ok(authorized) or _code(authorized) not in {
                "SERVICE_REJECTED", "UNAUTHENTICATED_PEER"}:
            self._fail("H4", "autorização inválida não foi recusada",
                       code=_code(authorized))
            return False
        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": auth.get("authorization_digest", "0" * 64),
             **self._target_payload(mission)},
            request_id="neg-auth-reserve")
        if _ok(reserved) or (reserved.get("result") or {}).get("lease_id"):
            self._fail("H4", "reserva ocorreu após autorização inválida",
                       code=_code(reserved))
            return False
        self._pass("H4", "autorização inválida recusada; sem reserva",
                   code=_code(authorized),
                   evidence={"authorize": _code(authorized),
                             "reserve": _code(reserved)})
        return True

    def run_duplicate(self) -> bool:
        mission = self._mission("NEG-DUP")
        if not self._prepare_authorized(mission, "neg-dup"):
            self._fail("H5", "preparação da duplicidade falhou")
            return False
        auth = self._authorization(mission)
        digest = auth["authorization_digest"]
        first = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id="neg-dup-reserve-1")
        lease = (first.get("result") or {}).get("lease_id") \
            if isinstance(first.get("result"), Mapping) else None
        if not _ok(first) or not isinstance(lease, str):
            self._fail("H5", "primeira reserva falhou", code=_code(first))
            return False
        second = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id="neg-dup-reserve-2")
        second_lease = (second.get("result") or {}).get("lease_id") \
            if isinstance(second.get("result"), Mapping) else None
        if _ok(second) and second_lease != lease:
            self._fail("H5", "segunda reserva criou outro lease",
                       code=_code(second))
            return False
        if _ok(second) and second_lease == lease:
            detail = "reserva idempotente devolveu o mesmo lease"
        elif not _ok(second) and not second_lease and _code(second):
            detail = "segunda reserva recusada com código registrado"
        else:
            self._fail("H5", "duplicidade não foi bloqueada",
                       code=_code(second))
            return False
        self._pass("H5", detail, code=_code(second),
                   evidence={"lease_id": lease})
        return True

    def run_missing_authorization(self) -> bool:
        mission = self._mission("NEG-MISSING")
        proposed = self._request(
            "executor", "propose", self._propose_payload(mission),
            request_id="neg-missing-propose")
        if not _ok(proposed):
            self._fail("H6", "propose falhou", code=_code(proposed))
            return False
        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": "1" * 64, **self._target_payload(mission)},
            request_id="neg-missing-reserve")
        if _ok(reserved):
            self._fail("H6", "reserva aceita sem autorização",
                       code=_code(reserved))
            return False
        self._pass("H6", "reserva recusada sem autorização",
                   code=_code(reserved),
                   evidence={"code": _code(reserved)})
        return True

    def run_interrupt(self) -> bool:
        mission = self._mission("NEG-INTERRUPT")
        if not self._prepare_authorized(mission, "neg-int"):
            self._fail("H7", "preparação da interrupção falhou")
            return False
        auth = self._authorization(mission)
        digest = auth["authorization_digest"]
        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id="neg-int-reserve")
        lease = (reserved.get("result") or {}).get("lease_id") \
            if isinstance(reserved.get("result"), Mapping) else None
        asserted = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease}, request_id="neg-int-assert")
        if not _ok(reserved) or not _ok(asserted):
            self._fail("H7", "preparação de lease/assert falhou")
            return False
        interrupted = self._request(
            "executor", "interrupt",
            {"mission_id": mission.mission_id, "cause": "process_loss"},
            request_id="neg-int-interrupt")
        state = _state(interrupted)
        if not _ok(interrupted) or state != "UNKNOWN":
            self._fail("H7", "interrupt não levou a UNKNOWN",
                       code=_code(interrupted), evidence={"state": state})
            return False
        resend = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease}, request_id="neg-int-resend")
        if _ok(resend):
            self._fail("H7", "assert aceito após UNKNOWN")
            return False
        self._pass("H7", "interrupção levou a UNKNOWN; reenvio recusado",
                   code=_code(resend),
                   evidence={"state": state, "resend": _code(resend)})
        return True

    def run_unknown_transport(self) -> bool:
        mission = self._mission("NEG-UNKNOWN")
        if not self._prepare_authorized(mission, "neg-unknown"):
            self._fail("H8", "preparação do UNKNOWN falhou")
            return False
        auth = self._authorization(mission)
        digest = auth["authorization_digest"]
        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id="neg-unknown-reserve")
        lease = (reserved.get("result") or {}).get("lease_id") \
            if isinstance(reserved.get("result"), Mapping) else None
        asserted = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease}, request_id="neg-unknown-assert")
        if not _ok(reserved) or not _ok(asserted):
            self._fail("H8", "preparação de lease/assert falhou")
            return False
        receipt, report, evidence = self._synthetic_dispatch(
            mission, outcome="timeout_after_transmit")
        if receipt is not None or report is not None or evidence is not None:
            self._fail("H8", "timeout sintético produziu receipt")
            return False
        interrupted = self._request(
            "executor", "interrupt",
            {"mission_id": mission.mission_id, "cause": "process_loss"},
            request_id="neg-unknown-interrupt")
        if not _ok(interrupted) or _state(interrupted) != "UNKNOWN":
            self._fail("H8", "timeout não foi classificado como UNKNOWN",
                       code=_code(interrupted))
            return False
        resend = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease}, request_id="neg-unknown-resend")
        if _ok(resend):
            self._fail("H8", "reexecução após UNKNOWN foi aceita")
            return False
        self._pass("H8", "timeout sintético ficou UNKNOWN; sem reenvio",
                   code=_code(resend),
                   evidence={"state": "UNKNOWN", "resend": _code(resend)})
        return True

    def run_ownership_conflict(self) -> bool:
        first = self._mission("NEG-OWN-A", session="ses-p9-owner")
        second = self._mission("NEG-OWN-B", session="ses-p9-owner")
        if not self._prepare_authorized(first, "neg-own-a"):
            self._fail("H9", "primeira proposta/autorização falhou")
            return False
        if not self._prepare_authorized(second, "neg-own-b"):
            self._fail("H9", "segunda proposta/autorização falhou")
            return False
        auth_a = self._authorization(first)
        auth_b = self._authorization(second)
        reserved_a = self._request(
            "executor", "reserve",
            {"authorization_digest": auth_a["authorization_digest"],
             **self._target_payload(first)}, request_id="neg-own-reserve-a")
        if not _ok(reserved_a):
            self._fail("H9", "primeira reserva falhou", code=_code(reserved_a))
            return False
        reserved_b = self._request(
            "executor", "reserve",
            {"authorization_digest": auth_b["authorization_digest"],
             **self._target_payload(second)}, request_id="neg-own-reserve-b")
        if _ok(reserved_b):
            self._fail("H9", "segunda reserva no mesmo ownership foi aceita",
                       code=_code(reserved_b))
            return False
        self._pass("H9", "segunda reserva recusada por ownership",
                   code=_code(reserved_b),
                   evidence={"first_lease": (reserved_a.get("result") or {}).get(
                       "lease_id"), "second": _code(reserved_b)})
        return True

    def run_divergent_attestation(self) -> bool:
        mission = self._mission("NEG-ATT")
        if not self._prepare_authorized(mission, "neg-att"):
            self._fail("H10", "preparação da atestação falhou")
            return False
        auth = self._authorization(mission)
        digest = auth["authorization_digest"]
        reserved = self._request(
            "executor", "reserve",
            {"authorization_digest": digest, **self._target_payload(mission)},
            request_id="neg-att-reserve")
        lease = (reserved.get("result") or {}).get("lease_id") \
            if isinstance(reserved.get("result"), Mapping) else None
        asserted = self._request(
            "executor", "assert",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease}, request_id="neg-att-assert")
        if not _ok(reserved) or not _ok(asserted):
            self._fail("H10", "preparação de lease/assert falhou")
            return False
        receipt, report, evidence = self._synthetic_dispatch(mission)
        if receipt is None or report is None or evidence is None:
            self._fail("H10", "despacho sintético falhou")
            return False
        report_mapping = self._report_mapping(mission, report, receipt)
        self.receipt, self.report, self.evidence = receipt, report_mapping, evidence
        attestation = self._attestation(mission, digest, tamper=True)
        reconciled = self._request(
            "executor", "reconcile",
            {"mission_id": mission.mission_id, "authorization_digest": digest,
             "lease_id": lease, "target_state": "COMPLETED",
             "attestation": attestation["attestation"],
             "attestation_signature": attestation["signature"],
             "report": report_mapping, "evidence": evidence},
            request_id="neg-att-reconcile")
        if _ok(reconciled) or _state(reconciled) == "COMPLETED":
            self._fail("H10", "atestação divergente concluiu a missão",
                       code=_code(reconciled))
            return False
        self._pass("H10", "atestação divergente recusada",
                   code=_code(reconciled),
                   evidence={"code": _code(reconciled)})
        return True

    def run_internal_failure(self) -> bool:
        mission = self._mission("NEG-INTERNAL")
        proposed = self._request(
            "executor", "propose", self._propose_payload(mission),
            request_id="neg-internal-propose")
        if not _ok(proposed):
            self._fail("H12", "propose falhou", code=_code(proposed))
            return False
        response = self._request(
            "executor", "status", {"mission_id": mission.mission_id},
            request_id="neg-internal-status")
        if _ok(response) or _code(response) != "SERVICE_ERROR" \
                or _reason(response) != "INTERNAL":
            self._fail("H12", "falha interna não foi sanitizada",
                       code=_code(response))
            return False
        self._pass("H12", "falha interna retornou SERVICE_ERROR/INTERNAL",
                   code=_code(response),
                   evidence={"reason": _reason(response)})
        return True

    def run_roles(self) -> bool:
        if not self.role_clients:
            self._blocked("H3b", "registry dividido exige clientes por papel")
            return False
        if not self.run_positive(roles=True):
            self._fail("H3b", "jornada positiva com papéis falhou")
            return False
        # Ações proibidas por papel devem ser recusadas.
        probe_mission = self._mission("001", session="ses-p9-synth-001")
        denied = {}
        for role in ("coordinator", "observer", "maintainer"):
            response = self._request(
                role, "reserve",
                {"authorization_digest": "2" * 64,
                 **self._target_payload(probe_mission)},
                request_id="neg-role-" + role)
            denied[role] = _code(response)
            if _ok(response) or _code(response) != "PERMISSION_DENIED":
                self._fail("H3b", f"ação de reserve não recusada para {role}",
                           code=_code(response))
                return False
        observer_status = self._request(
            "observer", "status", {"mission_id": probe_mission.mission_id},
            request_id="neg-role-observer-status")
        maintainer_status = self._request(
            "maintainer", "status", {"mission_id": probe_mission.mission_id},
            request_id="neg-role-maintainer-status")
        if not _ok(observer_status) or not _ok(maintainer_status):
            self._fail("H3b", "status de observer/maintainer falhou")
            return False
        self._pass("H3b", "papéis separados; ações proibidas recusadas",
                   evidence={"denied": denied,
                             "signer_separate": True})
        return True

    def _prepare_authorized(self, mission: cc.MissionEnvelope,
                            prefix: str) -> bool:
        proposed = self._request(
            "executor", "propose", self._propose_payload(mission),
            request_id=prefix + "-propose")
        if not _ok(proposed):
            return False
        auth = self._authorization(mission)
        authorized = self._request(
            "executor", "authorize",
            {"mission_id": mission.mission_id, "envelope": auth["envelope"],
             "signature": auth["signature"]},
            request_id=prefix + "-authorize")
        return _ok(authorized)

    # ---------- resumo e limpeza ----------

    def _matrix(self) -> dict[str, dict[str, Any]]:
        matrix = {gate: dict(value) for gate, value in self.results.items()}
        pending = {
            "H0": "preflight de VM/UID/rede fora do driver",
            "H1": "health check do serviço fora do driver",
            "H2": "permissões/socket fora do driver",
            "H11": "rollback/quarentena exige estado real; fora do driver",
            "H13": "limpeza da VM e do notebook exige operador",
            "H15": "preflight OpenCode real é futuro e exige autorização",
        }
        for gate, reason in pending.items():
            if matrix[gate]["status"] == "NOT_RUN":
                matrix[gate] = {"gate": gate, "status": "BLOCKED",
                                "detail": reason, "code": None,
                                "evidence": {}}
        if matrix["H3b"]["status"] == "NOT_RUN":
            matrix["H3b"] = {
                "gate": "H3b", "status": "BLOCKED",
                "detail": "H3b host-only com fake; socket real multi-UID futuro",
                "code": None, "evidence": {},
            }
        if matrix["H14"]["status"] == "NOT_RUN":
            matrix["H14"] = {
                "gate": "H14", "status": "PASS",
                "detail": "driver exclusivamente synthetic; sem OpenCode/transporte real",
                "code": None,
                "evidence": {"real_transport": False, "http_posts": 0},
            }
        return matrix

    def _cleanup(self) -> dict[str, Any]:
        removed = False
        if os.path.isdir(self.work_dir):
            try:
                shutil.rmtree(self.work_dir)
                removed = True
            except OSError:
                removed = False
        return {
            "work_dir_removed": removed,
            "private_key_accessed": False,
            "private_key_returned": False,
            "artifacts_preserved": os.path.isdir(self.artifacts_dir),
        }

    def run(self, scenarios: Sequence[str] = ("H3",)) -> dict[str, Any]:
        executed: list[str] = []
        for scenario in scenarios:
            try:
                if scenario == "H3":
                    executed.append("H3")
                    self.run_positive()
                elif scenario == "H3b":
                    executed.append("H3b")
                    self.run_roles()
                elif scenario == "H4":
                    executed.append("H4")
                    self.run_invalid_authorization()
                elif scenario == "H5":
                    executed.append("H5")
                    self.run_duplicate()
                elif scenario == "H6":
                    executed.append("H6")
                    self.run_missing_authorization()
                elif scenario == "H7":
                    executed.append("H7")
                    self.run_interrupt()
                elif scenario == "H8":
                    executed.append("H8")
                    self.run_unknown_transport()
                elif scenario == "H9":
                    executed.append("H9")
                    self.run_ownership_conflict()
                elif scenario == "H10":
                    executed.append("H10")
                    self.run_divergent_attestation()
                elif scenario == "H12":
                    if self.socket_real:
                        self._blocked(
                            "H12", "falha interna/auditoria exige cliente falso host-only")
                    else:
                        executed.append("H12")
                        self.run_internal_failure()
                elif scenario == "all":
                    return self.run(("H3",) + NEGATIVE_GATES + ("H3b",))
                else:
                    raise DriverError("cenário desconhecido: " + scenario)
            except Exception as exc:  # noqa: BLE001 - falha fechada no resumo
                gate = scenario if scenario in GATES else "H3"
                self._fail(
                    gate, "falha interna do driver",
                    code="SERVICE_ERROR",
                    evidence={"exception": type(exc).__name__})

        matrix = self._matrix()
        statuses = [item["status"] for item in matrix.values()]
        if "FAIL" in statuses:
            overall = "FAIL"
        elif all(item == "PASS" for item in statuses):
            overall = "PASS"
        else:
            overall = "BLOCKED"
        executed_status = "PASS"
        for gate in executed:
            if matrix[gate]["status"] != "PASS":
                executed_status = ("FAIL" if matrix[gate]["status"] == "FAIL"
                                   else "BLOCKED")
                break
        summary = {
            "schema": SUMMARY_SCHEMA,
            "mode": MODE,
            "scenario": self.scenario,
            "source_head": self.source_head,
            "generated_at": _iso(self.clock()),
            "real_transport": False,
            "http_posts": 0,
            "llm_calls": 0,
            "open_code_calls": 0,
            "socket_real": self.socket_real,
            "signer_separate": True,
            "matrix": matrix,
            "executed": list(executed),
            "execution_status": executed_status,
            "overall": overall,
            "hashes": dict(self.hashes),
            "trace": list(self.trace),
            "cleanup": self._cleanup(),
        }
        sanitized = common.sanitize_value(
            summary, (self.work_dir, self.artifacts_dir, os.getcwd()))
        common.write_json(
            os.path.join(self.artifacts_dir, "summary.json"), sanitized, mode=0o600)
        return sanitized


# ---------- signer helper (processo separado) ----------

def _load_trust_store(path: str) -> tuple[Any, Any]:
    try:
        value = common.read_json(path)
    except (OSError, ValueError) as exc:
        raise DriverError("trust store público inválido") from exc
    entries = value.get("entries") if isinstance(value, Mapping) else None
    if not isinstance(entries, list) or not entries:
        raise DriverError("trust store sem entradas")
    keys = []
    for entry in entries:
        try:
            keys.append(cas.ApproverKey(
                approver_id=entry["approver_id"],
                fingerprint=entry["fingerprint"],
                public_key=entry["public_key"],
                valid_from=_parse_iso(entry["valid_from"]),
                valid_until=_parse_iso(entry["valid_until"]),
                revoked_at=(_parse_iso(entry["revoked_at"])
                            if entry.get("revoked_at") else None),
            ))
        except (KeyError, TypeError, ValueError) as exc:
            raise DriverError("entrada do trust store inválida") from exc
    allowlist = cas.ApproverAllowlist(tuple(keys))
    allowlist.validate()
    return allowlist, keys


def _parse_iso(value: Any) -> datetime:
    if isinstance(value, datetime):
        parsed = value
    else:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("datetime sem fuso")
    return parsed.astimezone(timezone.utc)


def signer_helper_main(args: argparse.Namespace) -> int:
    import coord_p14b as p14b
    import coord_p14b_authority as p14ba

    try:
        request = json.load(sys.stdin)
        if not isinstance(request, Mapping):
            raise DriverError("requisição do signer inválida")
        allowlist, _ = _load_trust_store(args.trust_store)
        signer = p14b.SyntheticSigner(args.key_dir, persist=True)
        if not allowlist.find(signer.approver_id, signer.fingerprint):
            raise DriverError("chave do signer não está no trust store")
        now = _parse_iso(request["now"])
        mission = svc._mission_from_mapping(request["mission"])
        if request["op"] == "authorize":
            from coord_trust_crossuid_common import make_envelope
            envelope = make_envelope(
                mission, signer, allowlist, now,
                tag=mission.operation_id)
            signature = signer.sign(
                p14ba.canonical_authorization_bytes(envelope),
                namespace=p14ba.AUTHORIZATION_NAMESPACE,
                directory=os.path.dirname(signer.private_path))
            output = {
                "envelope": envelope.to_mapping(),
                "signature": common.b64(signature),
                "authorization_digest": p14ba.authorization_digest(envelope),
                "approver_id": signer.approver_id,
                "fingerprint": signer.fingerprint,
                "trust_policy_sha256": allowlist.digest(),
            }
        elif request["op"] == "attest":
            receipt = request["receipt"]
            report = request["report"]
            evidence = request["evidence"]
            attestation = p14ba.SignedReconciliation(
                schema=p14ba.RECONCILIATION_SCHEMA,
                authority_id=signer.approver_id,
                approver_id=signer.approver_id,
                approver_key_fingerprint=signer.fingerprint,
                trust_policy_sha256=allowlist.digest(),
                mission_id=mission.mission_id,
                authorization_digest=request["authorization_digest"],
                payload_sha256=mission.payload_sha256,
                receipt_sha256=common.digest(receipt),
                report_sha256=common.digest(report),
                evidence_sha256=common.digest(evidence),
                outcome=request["outcome"],
                environment="SHADOW",
                issued_at=now,
                expires_at=now + timedelta(minutes=10),
                nonce="nonce-p9-attest-" + mission.operation_id,
                mode="SHADOW")
            signature = signer.sign(
                p14ba.canonical_reconciliation_bytes(attestation),
                namespace=p14ba.RECONCILIATION_NAMESPACE,
                directory=os.path.dirname(signer.private_path))
            output = {
                "attestation": attestation.to_mapping(),
                "signature": common.b64(signature),
                "approver_id": signer.approver_id,
                "fingerprint": signer.fingerprint,
                "trust_policy_sha256": allowlist.digest(),
            }
        else:
            raise DriverError("operação do signer desconhecida")
        print(common.canonical_json(output), flush=True)
        return 0
    except Exception as exc:  # noqa: BLE001 - helper sanitiza a saída
        print(common.canonical_json({
            "ok": False, "error": "SIGNER_HELPER_BLOCKED",
            "reason": type(exc).__name__,
        }), file=sys.stderr, flush=True)
        return 2


# ---------- CLI ----------

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="COORD-OPS/P9.2 synthetic pilot driver")
    parser.add_argument("--mode", choices=("synthetic",), required=True)
    parser.add_argument("--role", choices=("pilot", "signer-helper"),
                        default="pilot")
    parser.add_argument("--scenario", choices=("positive", "all"),
                        default="positive")
    parser.add_argument("--socket")
    parser.add_argument("--service-uid", type=int)
    parser.add_argument("--service-gid", type=int)
    parser.add_argument("--service-id")
    parser.add_argument("--signer-helper")
    parser.add_argument("--work-dir")
    parser.add_argument("--artifacts")
    parser.add_argument("--source-head", default="UNKNOWN")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--key-dir")
    parser.add_argument("--trust-store")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _parser().parse_args(list(sys.argv[1:] if argv is None else argv))
    if args.role == "signer-helper":
        if not args.key_dir or not args.trust_store:
            print("SIGNER-HELPER-CONFIG-MISSING", file=sys.stderr)
            return 2
        return signer_helper_main(args)
    if args.mode != "synthetic":
        print("MODE-NOT-SYNTHETIC", file=sys.stderr)
        return 2
    if not args.socket or not args.signer_helper or not args.work_dir \
            or not args.artifacts or args.service_uid is None:
        print("PILOT-CONFIG-MISSING", file=sys.stderr)
        return 2
    if args.service_gid is None:
        args.service_gid = args.service_uid
    command = shlex.split(args.signer_helper)
    if not command:
        print("SIGNER-HELPER-EMPTY", file=sys.stderr)
        return 2
    client = svc.ServiceClient(
        args.socket, expected_service_uid=args.service_uid,
        expected_service_gid=args.service_gid,
        expected_service_id=args.service_id, timeout=args.timeout)
    signer = SubprocessSigner(command, timeout=args.timeout)
    driver = PilotDriver(
        client=client, signer=signer, work_dir=args.work_dir,
        artifacts_dir=args.artifacts, source_head=args.source_head,
        socket_real=True, scenario=args.scenario)
    scenarios = ("H3",) if args.scenario == "positive" \
        else ("H3",) + NEGATIVE_GATES
    try:
        summary = driver.run(scenarios)
    except Exception:  # noqa: BLE001 - falha fechada
        return 1
    print(common.canonical_json(summary), flush=True)
    return 0 if summary.get("execution_status") == "PASS" else (
        1 if summary.get("execution_status") == "FAIL" else 2)


if __name__ == "__main__":
    sys.exit(main())
