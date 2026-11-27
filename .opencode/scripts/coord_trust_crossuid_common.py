#!/usr/bin/env python3
"""COORD-TRUST-BOUNDARY/P4 — fixtures compartilhadas da certificação cross-UID.

Este módulo é importado pelo harness (root, somente no runner descartável) e
pelo cliente (UID do consumidor). Ele nunca contém chave privada, path de estado
ou credencial real. As chaves sintéticas são geradas e mantidas fora do alcance
do consumidor; o job do cliente recebe apenas material assinado e o socket.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping, Optional

import coord_authority as ca
import coord_authority_signed as cas
import coord_communication as cc
import coord_p14b as p14b
import coord_p14b_authority as p14ba
import coord_trust_service as svc

PROJECT = "better-talker"
REPOSITORY = "guilhermemellojw/better-talker"
MODEL = "opencode/hy4-flash-free"
HEAD = "a" * 40
SCOPE = "TRUST-CROSSUID"
PAYLOAD_SCHEMA = "BT-COORD-PAYLOAD-V1"

# UIDs numéricos efêmeros; nenhum usuário é criado. O grupo comum (SERVICE_GID)
# existe somente para o socket 0660. O helper não registrado também é numérico.
SERVICE_UID = 65533
SERVICE_GID = 65533
CONSUMER_UID = 65534
CONSUMER_GID = SERVICE_GID
OBSERVER_UID = 65531
OBSERVER_GID = SERVICE_GID
UNREGISTERED_UID = 65532
# O helper não registrado precisa do grupo do socket para alcançar o runtime;
# a divergência testada é de UID. Para GID exclusivo usamos WRONG_GID.
UNREGISTERED_GID = SERVICE_GID
WRONG_GID = 65532

CLIENT_SCHEMA = "BT-COORD-TRUST-CROSSUID-CLIENT-JOB-V1"
SANDBOX_SCHEMA = "BT-COORD-TRUST-CROSSUID-SANDBOX-JOB-V1"
REPORT_SCHEMA = "BT-COORD-TRUST-CROSSUID-SUMMARY-V1"
ALL_ACTIONS = frozenset({
    "propose", "authorize", "reserve", "assert", "record_evidence",
    "reconcile", "interrupt", "status", "recover", "revoke",
})
FORBIDDEN_CLIENT_KEYS = frozenset({
    "private_key", "private_key_path", "keys_dir", "key_dir", "state_dir",
    "ledger_path", "anchor_path", "trust_store_path", "consumer_registry_path",
    "config_path", "service_db_path", "seed", "secret", "token",
})


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def service_id() -> str:
    return hashlib.sha256((PROJECT + REPOSITORY).encode("utf-8")).hexdigest()[:16]


@dataclass(frozen=True)
class Plan:
    """Layout lógico da execução; paths reais só existem no runner descartável."""

    root: str
    state_dir: str
    anchor_dir: str
    anchor_path: str
    runtime_dir: str
    socket_path: str
    keys_dir: str
    artifacts_dir: str
    relay_dir: str
    logs_dir: str
    service_home: str
    service_tmp: str
    consumer_home: str
    consumer_tmp: str
    observer_home: str
    observer_tmp: str
    unregistered_home: str
    unregistered_tmp: str
    config_path: str
    trust_store_path: str
    registry_path: str
    client_job_path: str
    sandbox_job_path: str


def build_plan(root: str) -> Plan:
    root = os.path.abspath(root)
    state = os.path.join(root, "state")
    anchor_dir = os.path.join(root, "anchor")
    runtime = os.path.join(root, "run")
    artifacts = os.path.join(root, "artifacts")
    return Plan(
        root=root,
        state_dir=state,
        anchor_dir=anchor_dir,
        anchor_path=os.path.join(anchor_dir, "anchor.json"),
        runtime_dir=runtime,
        socket_path=os.path.join(runtime, "service.sock"),
        keys_dir=os.path.join(root, "keys"),
        artifacts_dir=artifacts,
        relay_dir=os.path.join(root, "relay"),
        logs_dir=os.path.join(root, "logs"),
        service_home=os.path.join(root, "service-home"),
        service_tmp=os.path.join(root, "service-tmp"),
        consumer_home=os.path.join(root, "consumer-home"),
        consumer_tmp=os.path.join(root, "consumer-tmp"),
        observer_home=os.path.join(root, "observer-home"),
        observer_tmp=os.path.join(root, "observer-tmp"),
        unregistered_home=os.path.join(root, "unregistered-home"),
        unregistered_tmp=os.path.join(root, "unregistered-tmp"),
        config_path=os.path.join(state, "service-config.json"),
        trust_store_path=os.path.join(state, "trust-store.json"),
        registry_path=os.path.join(state, "consumers.json"),
        client_job_path=os.path.join(artifacts, "client-job.json"),
        sandbox_job_path=os.path.join(artifacts, "sandbox-job.json"),
    )


def config_mapping(plan: Plan, *, max_workers: int = 4,
                   request_timeout_seconds: float = 15.0,
                   max_frame_bytes: int = svc.MAX_FRAME_BYTES,
                   real_transport: bool = False) -> dict[str, Any]:
    """Configuração de produção simulada; nunca test_mode/allow_same_uid."""
    return {
        "schema": "BT-COORD-TRUST-SERVICE-CONFIG-V1",
        "state_dir": plan.state_dir,
        "project_id": PROJECT,
        "repository_id": REPOSITORY,
        "service_uid": SERVICE_UID,
        "service_gid": SERVICE_GID,
        "trust_store_path": plan.trust_store_path,
        "consumer_registry_path": plan.registry_path,
        "socket_path": plan.socket_path,
        "anchor_path": plan.anchor_path,
        "allow_same_uid": False,
        "test_mode": False,
        "real_transport": real_transport,
        "max_frame_bytes": max_frame_bytes,
        "request_timeout_seconds": request_timeout_seconds,
        "max_workers": max_workers,
    }


def make_mission(now: datetime, *, tag: str) -> cc.MissionEnvelope:
    payload = {
        "schema": PAYLOAD_SCHEMA,
        "message": "crossuid-" + tag,
        "tag": tag,
    }
    return cc.MissionEnvelope(
        schema_version=cc.MISSION_SCHEMA_VERSION,
        project_id=PROJECT,
        repository_id=REPOSITORY,
        mission_id="MISS-CROSSUID-" + tag.upper(),
        operation_id="OP-CROSSUID-" + tag.upper(),
        coordinator_id="coordinator-crossuid",
        target_agent="agent-crossuid",
        target_session_id="ses-crossuid-" + tag,
        target_instance_id="inst-crossuid-" + tag,
        target_location="/tmp/wt-crossuid-" + tag,
        objective="certificação cross-UID descartável " + tag,
        payload=payload,
        payload_sha256=cc.payload_sha256(payload),
        expected_deliverables=("deliverable-crossuid",),
        acceptance_criteria=("criterion-crossuid-a", "criterion-crossuid-b"),
        ownership_constraints=("ownership-crossuid",),
        authorization_reference="APP-CROSSUID-" + tag.upper(),
        created_at=now - timedelta(hours=1),
        expires_at=now + timedelta(hours=2),
        timeout=3600,
        scope=SCOPE,
        expected_head_sha=HEAD,
        expected_branch="feat/coord-trust-crossuid-cert",
    )


def make_envelope(mission: cc.MissionEnvelope, signer: p14b.SyntheticSigner,
                  allowlist: cas.ApproverAllowlist, now: datetime,
                  *, tag: str) -> p14ba.SignedAuthorizationV2:
    payload = {
        "schema": p14ba.AUTHORIZATION_SCHEMA,
        "authorization_id": "AUTH-CROSSUID-" + tag.upper(),
        "approval_reference": mission.authorization_reference,
        "approver_id": signer.approver_id,
        "approver_key_fingerprint": signer.fingerprint,
        "trust_policy_sha256": allowlist.digest(),
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "project_id": mission.project_id,
        "repository_id": mission.repository_id,
        "coordinator_id": mission.coordinator_id,
        "mission_identity": cc.canonical_mission_identity(mission),
        "payload": dict(mission.payload),
        "payload_sha256": mission.payload_sha256,
        "target": ca.Recipient(
            mission.target_agent, mission.target_session_id,
            mission.target_location, mission.target_instance_id),
        "model": MODEL,
        "scope": mission.scope,
        "acceptance_criteria": tuple(mission.acceptance_criteria),
        "environment": "SHADOW",
        "reviewed_head_sha": mission.expected_head_sha,
        "operational_digests": {
            "coord_trust_service.py": "d" * 64,
            "coord_ledger.py": "e" * 64,
        },
        "max_transmissions": 1,
        "cost_ceiling_usd": "0",
        "free_only": True,
        "issued_at": now - timedelta(minutes=5),
        "expires_at": now + timedelta(minutes=30),
        "nonce": "nonce-auth-" + tag,
        "mode": "SHADOW",
    }
    return p14ba.SignedAuthorizationV2(**payload)


@dataclass(frozen=True)
class MissionArtifacts:
    tag: str
    mission: cc.MissionEnvelope
    envelope: p14ba.SignedAuthorizationV2
    authorization_digest: str
    authorize_signature: str
    propose: dict[str, Any]
    authorize: dict[str, Any]
    reserve: dict[str, Any]
    assert_payload: dict[str, Any]
    record_evidence: dict[str, Any]
    receipt: dict[str, Any]
    evidence: dict[str, Any]
    report: dict[str, Any]

    def status_payload(self) -> dict[str, Any]:
        return {"mission_id": self.mission.mission_id}


def build_artifacts(now: datetime, signer: p14b.SyntheticSigner,
                    allowlist: cas.ApproverAllowlist, *, tag: str) -> MissionArtifacts:
    mission = make_mission(now, tag=tag)
    envelope = make_envelope(mission, signer, allowlist, now, tag=tag)
    signature = signer.sign(
        p14ba.canonical_authorization_bytes(envelope),
        namespace=p14ba.AUTHORIZATION_NAMESPACE,
        directory=os.path.dirname(signer.private_path))
    authorization_digest = p14ba.authorization_digest(envelope)
    receipt = {
        "source": "synthetic",
        "environment": "SHADOW",
        "observed_message_id": "msg-crossuid-" + tag,
        "operation_id": mission.operation_id,
        "payload_sha256": mission.payload_sha256,
        "agent_id": mission.target_agent,
        "session_id": mission.target_session_id,
        "instance_id": mission.target_instance_id,
        "location": mission.target_location,
    }
    evidence = {
        "source": "synthetic",
        "environment": "SHADOW",
        "service_contacted": False,
        "transport_real": False,
    }
    report = {
        "project_id": PROJECT,
        "repository_id": REPOSITORY,
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "agent_id": mission.target_agent,
        "session_id": mission.target_session_id,
        "message_id": "report-crossuid-" + tag,
        "reply_to": "msg-crossuid-" + tag,
        "status": "COMPLETED",
        "blockers": [],
        "acceptance_results": {
            "criterion-crossuid-a": True,
            "criterion-crossuid-b": True,
        },
        "payload_sha256": mission.payload_sha256,
        "completed_at": iso(now),
    }
    target = {
        "agent": mission.target_agent,
        "session_id": mission.target_session_id,
        "instance_id": mission.target_instance_id,
        "worktree": mission.target_location,
    }
    return MissionArtifacts(
        tag=tag,
        mission=mission,
        envelope=envelope,
        authorization_digest=authorization_digest,
        authorize_signature=b64(signature),
        propose={
            "mission": svc._mission_mapping(mission),
            "model": MODEL,
            "environment": "SHADOW",
            "operational_digests": {
                "coord_trust_service.py": "d" * 64,
                "coord_ledger.py": "e" * 64,
            },
            "max_transmissions": 1,
            "cost_ceiling_usd": "0",
            "free_only": True,
            "authorization_reference": mission.authorization_reference,
        },
        authorize={
            "mission_id": mission.mission_id,
            "envelope": envelope.to_mapping(),
            "signature": b64(signature),
        },
        reserve={
            "mission_id": mission.mission_id,
            "authorization_digest": authorization_digest,
            **target,
        },
        assert_payload={
            "mission_id": mission.mission_id,
            "authorization_digest": authorization_digest,
            "lease_id": "__LEASE__",
        },
        record_evidence={
            "mission_id": mission.mission_id,
            "authorization_digest": authorization_digest,
            "lease_id": "__LEASE__",
            "receipt": receipt,
            "evidence": evidence,
        },
        receipt=receipt,
        evidence=evidence,
        report=report,
    )


def reconcile_payload(now: datetime, signer: p14b.SyntheticSigner,
                      allowlist: cas.ApproverAllowlist,
                      artifacts: MissionArtifacts, *, lease_id: str) -> dict[str, Any]:
    """Assina a atestação terminal DEPOIS do dispatch/evidence reais.

    O ledger recusa relatório anterior ao dispatch e atestação anterior ao
    relatório. Por isso a atestação não pode ser pré-assinada na criação do job;
    o harness a produz após `record_evidence`, sem entregar a chave ao cliente.
    """
    report = dict(artifacts.report)
    report["completed_at"] = iso(now)
    attestation = p14ba.SignedReconciliation(
        schema=p14ba.RECONCILIATION_SCHEMA,
        authority_id="maintainer",
        approver_id=signer.approver_id,
        approver_key_fingerprint=signer.fingerprint,
        trust_policy_sha256=allowlist.digest(),
        mission_id=artifacts.mission.mission_id,
        authorization_digest=artifacts.authorization_digest,
        payload_sha256=artifacts.mission.payload_sha256,
        receipt_sha256=digest(artifacts.receipt),
        report_sha256=digest(report),
        evidence_sha256=digest(artifacts.evidence),
        outcome="COMPLETED",
        environment="SHADOW",
        issued_at=now,
        expires_at=now + timedelta(minutes=10),
        nonce="nonce-recon-dynamic-" + artifacts.tag,
        mode="SHADOW",
    )
    signature = signer.sign(
        p14ba.canonical_reconciliation_bytes(attestation),
        namespace=p14ba.RECONCILIATION_NAMESPACE,
        directory=os.path.dirname(signer.private_path))
    return {
        "mission_id": artifacts.mission.mission_id,
        "authorization_digest": artifacts.authorization_digest,
        "lease_id": lease_id,
        "target_state": "COMPLETED",
        "attestation": attestation.to_mapping(),
        "attestation_signature": b64(signature),
        "report": report,
        "evidence": dict(artifacts.evidence),
    }


def recovery_statement(now: datetime, signer: p14b.SyntheticSigner,
                       allowlist: cas.ApproverAllowlist, *, new_epoch: int,
                       nonce: str, reason: str = "certificação cross-UID",
                       issued_at: Optional[datetime] = None,
                       expires_at: Optional[datetime] = None,
                       trust_policy_sha256: Optional[str] = None,
                       approver_id: Optional[str] = None,
                       ) -> tuple[dict[str, Any], str]:
    statement = svc.RecoveryStatement(
        schema=svc.RECOVERY_SCHEMA,
        approver_id=approver_id or signer.approver_id,
        approver_key_fingerprint=signer.fingerprint,
        trust_policy_sha256=trust_policy_sha256 or allowlist.digest(),
        new_epoch=int(new_epoch),
        reason=reason,
        issued_at=issued_at or now,
        expires_at=expires_at or (now + timedelta(minutes=10)),
        nonce=nonce,
    )
    signature = signer.sign(
        svc.canonical_recovery_bytes(statement),
        namespace=svc.RECOVERY_NAMESPACE,
        directory=os.path.dirname(signer.private_path))
    return statement.to_mapping(), b64(signature)


def client_job(plan: Plan, artifacts: MissionArtifacts, *,
               request_prefix: str = "crossuid") -> dict[str, Any]:
    """Job do consumidor: apenas socket, IDs públicos e material assinado."""
    return {
        "schema": CLIENT_SCHEMA,
        "socket_path": plan.socket_path,
        "service_uid": SERVICE_UID,
        "service_gid": SERVICE_GID,
        "service_id": service_id(),
        "project_id": PROJECT,
        "repository_id": REPOSITORY,
        "mission_id": artifacts.mission.mission_id,
        "remote_uid": UNREGISTERED_UID,
        "remote_gid": WRONG_GID,
        "request_ids": {
            "propose": request_prefix + "-propose",
            "authorize": request_prefix + "-authorize",
            "reserve": request_prefix + "-reserve",
            "assert": request_prefix + "-assert",
            "record_evidence": request_prefix + "-evidence",
            "reconcile": request_prefix + "-reconcile",
            "status": request_prefix + "-status",
            "transmit": request_prefix + "-transmit",
        },
        "actions": {
            "propose": artifacts.propose,
            "authorize": artifacts.authorize,
            "reserve": artifacts.reserve,
            "assert": artifacts.assert_payload,
            "record_evidence": artifacts.record_evidence,
            "status": artifacts.status_payload(),
            "transmit": {},
            "forbidden": {
                "mission_id": artifacts.mission.mission_id,
                "consumer_id": "maintainer",
            },
        },
    }


def sandbox_job(plan: Plan, artifacts: MissionArtifacts, *,
                service_pid: int, keys_dir: Optional[str] = None
                ) -> dict[str, Any]:
    """Job de diagnóstico do sandbox; não é entregue ao cliente normal."""
    return {
        "schema": SANDBOX_SCHEMA,
        "socket_path": plan.socket_path,
        "service_uid": SERVICE_UID,
        "service_gid": SERVICE_GID,
        "service_id": service_id(),
        "service_pid": int(service_pid),
        "mission_id": artifacts.mission.mission_id,
        "state_dir": plan.state_dir,
        "ledger_path": os.path.join(plan.state_dir, "ledger.db"),
        "trust_store_path": plan.trust_store_path,
        "consumer_registry_path": plan.registry_path,
        "anchor_path": plan.anchor_path,
        "keys_dir": keys_dir or plan.keys_dir,
        "runtime_dir": plan.runtime_dir,
        "consumer_uid": CONSUMER_UID,
        "consumer_gid": CONSUMER_GID,
    }


def write_json(path: str, value: Mapping[str, Any], *, mode: int = 0o644) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(canonical_json(dict(value)) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(target, mode)


def read_json(path: str) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def validate_client_job(value: Mapping[str, Any]) -> None:
    if not isinstance(value, Mapping):
        raise ValueError("job deve ser mapping")
    if value.get("schema") != CLIENT_SCHEMA:
        raise ValueError("schema do job do cliente inválido")
    required = {
        "schema", "socket_path", "service_uid", "service_gid", "service_id",
        "project_id", "repository_id", "mission_id", "request_ids", "actions",
        "remote_uid", "remote_gid",
    }
    missing = required - set(value)
    if missing:
        raise ValueError("job sem campos: " + ", ".join(sorted(missing)))

    def walk(item: Any, path: str) -> None:
        if isinstance(item, Mapping):
            for key, child in item.items():
                if not isinstance(key, str):
                    raise ValueError(f"{path}: chave inválida")
                if key in FORBIDDEN_CLIENT_KEYS or key.startswith("private_"):
                    raise ValueError(f"{path}.{key}: material privado proibido")
                walk(child, f"{path}.{key}")
        elif isinstance(item, (list, tuple)):
            for index, child in enumerate(item):
                walk(child, f"{path}[{index}]")

    walk(value, "job")


def validate_sandbox_job(value: Mapping[str, Any]) -> None:
    if not isinstance(value, Mapping) or value.get("schema") != SANDBOX_SCHEMA:
        raise ValueError("schema do job de sandbox inválido")
    required = {
        "schema", "socket_path", "service_uid", "service_gid", "service_id",
        "service_pid", "mission_id", "state_dir", "ledger_path",
        "trust_store_path", "consumer_registry_path", "anchor_path",
        "keys_dir", "runtime_dir", "consumer_uid", "consumer_gid",
    }
    if required - set(value):
        raise ValueError("job de sandbox incompleto")


def sanitize_text(value: str, paths: tuple[str, ...] = ()) -> str:
    text = value
    for path in sorted((p for p in paths if p), key=len, reverse=True):
        text = text.replace(path, "<path>")
    text = re.sub(
        r"-----BEGIN OPENSSH PRIVATE KEY-----.*?-----END OPENSSH PRIVATE KEY-----",
        "<redacted-private-key>", text, flags=re.S)
    text = re.sub(
        r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        "<redacted-private-key>", text, flags=re.S)
    return text
