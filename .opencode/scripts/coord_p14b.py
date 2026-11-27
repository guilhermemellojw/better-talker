#!/usr/bin/env python3
"""COORD-04/P14B — integração sintética: CLI → ledger 1.1 → autoridade #77.

VERIFIER-ONLY: nenhuma chave real, credencial, sessão, POST, LLM ou transporte
é utilizado. A chave OpenSSH é gerada em diretório temporário, usada apenas
para assinar o envelope canônico e descartada. O verificador é o módulo
certificado do PR #77; aqui não se cria autoridade nem assinatura nova.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from contextlib import contextmanager
from decimal import Decimal
from typing import Any, Mapping, Optional

import coord_authority as ca
import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger as ledger
import coord_ledger_authority as cla
import coord_p14_adapter as p14a
import coord_pilot as pilot
import coord_pilot_cli as cli

__all__ = ["SyntheticSigner", "AuthorityPort", "ReconciliationPort",
           "build_snapshot", "build_envelope", "run_synthetic_journey"]


def _parse_time(value: Any) -> datetime:
    if isinstance(value, datetime):
        result = value
    else:
        result = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return result.astimezone(timezone.utc)


def _decimal(value: Any) -> Optional[Decimal]:
    return None if value in (None, "", "None") else Decimal(str(value))


class SyntheticSigner:
    """Chave sintética descartável; nunca lê ~/.ssh ou agente de credenciais."""

    def __init__(self, directory: str, *, approver_id: str = "maintainer",
                 persist: bool = False) -> None:
        ssh_keygen = shutil.which("ssh-keygen")
        if not ssh_keygen:
            raise RuntimeError("ssh-keygen indisponível")
        self.approver_id = approver_id
        os.makedirs(directory, mode=0o700, exist_ok=True)
        self.private_path = os.path.join(directory, ".p14b-synthetic-key")
        ready = (os.path.isfile(self.private_path)
                 and os.path.isfile(self.private_path + ".pub"))
        if not persist or not ready:
            lock = self.private_path + ".lock"
            try:
                os.mkdir(lock, mode=0o700)
            except FileExistsError:
                # Outro processo está gerando a mesma chave sintética.
                import time
                for _ in range(250):
                    if (os.path.isfile(self.private_path)
                            and os.path.isfile(self.private_path + ".pub")):
                        break
                    time.sleep(0.02)
                if not (os.path.isfile(self.private_path)
                        and os.path.isfile(self.private_path + ".pub")):
                    raise RuntimeError("chave sintética indisponível após espera")
            else:
                try:
                    if not (os.path.isfile(self.private_path)
                            and os.path.isfile(self.private_path + ".pub")):
                        with tempfile.TemporaryDirectory(
                                prefix=".p14b-keygen-", dir=directory) as temporary:
                            candidate = os.path.join(temporary, "key")
                            self._generate(ssh_keygen, candidate)
                            os.replace(candidate, self.private_path)
                            os.replace(candidate + ".pub", self.private_path + ".pub")
                finally:
                    os.rmdir(lock)
        os.chmod(self.private_path, 0o600)
        with open(self.private_path + ".pub", encoding="utf-8") as handle:
            self.public_line = handle.read().strip()
        self.fingerprint = cas.public_key_fingerprint(self.public_line)

    @staticmethod
    def _generate(ssh_keygen: str, path: str) -> None:
        result = subprocess.run(
            [ssh_keygen, "-q", "-t", "ed25519", "-N", "", "-f", path],
            capture_output=True, timeout=30)
        if result.returncode != 0:
            raise RuntimeError("falha ao gerar chave sintética")

    def sign(self, data: bytes, *, namespace: str, directory: str) -> bytes:
        # Cada assinatura usa arquivos únicos: ssh-keygen se recusa a
        # sobrescrever um `.sig` existente (o que geraria assinatura obsoleta).
        descriptor, payload = tempfile.mkstemp(
            prefix=".p14b-payload-", dir=directory)
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
            result = subprocess.run(
                ["ssh-keygen", "-Y", "sign", "-q", "-f", self.private_path,
                 "-n", namespace, payload],
                capture_output=True, timeout=30)
            if result.returncode != 0:
                raise RuntimeError("falha ao assinar material sintético")
            with open(payload + ".sig", "rb") as handle:
                return handle.read()
        finally:
            for path in (payload, payload + ".sig"):
                if os.path.exists(path):
                    os.unlink(path)

    def approver_key(self, now: datetime) -> cas.ApproverKey:
        return cas.ApproverKey(
            approver_id=self.approver_id, fingerprint=self.fingerprint,
            public_key=self.public_line,
            valid_from=now - timedelta(minutes=5), valid_until=now + timedelta(days=1))


def _canonical_scope(scope: str) -> str:
    """Escopo legível → identificador canônico; o original permanece vinculado
    via `mission_identity`, que já cobre `mission.scope` integralmente."""
    import re
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*", scope):
        return scope
    return "scope-" + hashlib.sha256(scope.encode("utf-8")).hexdigest()[:16]


def build_snapshot(mission: cc.MissionEnvelope, *, model: str,
                   now: datetime) -> cla.MissionSnapshot:
    """Recompõe o snapshot canônico a partir da missão #64 e da política #71."""
    return cla.MissionSnapshot(
        mission_id=mission.mission_id, operation_id=mission.operation_id,
        project_id=mission.project_id, repository_id=mission.repository_id,
        coordinator_id=mission.coordinator_id,
        agent=mission.target_agent, session_id=mission.target_session_id,
        instance_id=mission.target_instance_id, worktree=mission.target_location,
        mission_identity=cc.canonical_mission_identity(mission),
        payload=dict(mission.payload), model=model,
        scope=_canonical_scope(mission.scope),
        acceptance_criteria=tuple(mission.acceptance_criteria),
        environment="FIXTURE", reviewed_head_sha=pilot.executed_head_sha(),
        operational_digests=pilot.operational_file_digests(),
        max_transmissions=1, cost_ceiling_usd="0", free_only=True)


def build_envelope(
    snapshot: cla.MissionSnapshot, *, signer: SyntheticSigner,
    allowlist: cas.ApproverAllowlist, now: datetime, directory: str,
    approval_reference: str = "APPROVAL-P14B-SYNTHETIC",
) -> tuple[cas.SignedAuthorizationEnvelope, bytes]:
    """Envelope #77 assinado sobre o material canônico #80."""
    envelope = cas.SignedAuthorizationEnvelope(
        schema=cas.SCHEMA_VERSION,
        authorization_id="AUTH-P14B-" + snapshot.operation_id,
        approval_reference=approval_reference,
        approver_id=signer.approver_id,
        approver_key_fingerprint=signer.fingerprint,
        trust_policy_sha256=allowlist.digest(),
        mission_id=snapshot.mission_id, operation_id=snapshot.operation_id,
        project_id=snapshot.project_id, repository_id=snapshot.repository_id,
        coordinator_id=snapshot.coordinator_id,
        mission_identity=snapshot.mission_identity,
        payload=dict(snapshot.payload), payload_sha256=snapshot.payload_sha256,
        target=ca.Recipient(snapshot.agent, snapshot.session_id,
                            snapshot.worktree, snapshot.instance_id),
        model=snapshot.model, scope=snapshot.scope,
        reviewed_head_sha=snapshot.reviewed_head_sha,
        operational_digests=dict(snapshot.operational_digests),
        max_transmissions=snapshot.max_transmissions,
        cost_ceiling_usd=Decimal(snapshot.cost_ceiling_usd),
        free_only=snapshot.free_only,
        issued_at=now - timedelta(minutes=1), expires_at=now + timedelta(minutes=30),
        nonce="nonce-p14b-" + snapshot.operation_id, mode="SHADOW")
    signature = signer.sign(cas.canonical_envelope_bytes(envelope),
                            namespace=cas.SIGNATURE_NAMESPACE, directory=directory)
    return envelope, signature


def _envelope_from_mapping(value: Mapping[str, Any]) -> cas.SignedAuthorizationEnvelope:
    """Reconstrói o envelope a partir do mapping canônico persistido no ledger."""
    if not isinstance(value, Mapping):
        raise cla.AuthorizationContractError("envelope do ledger não é mapping")
    return cas.SignedAuthorizationEnvelope(
        schema=value["schema"], authorization_id=value["authorization_id"],
        approval_reference=value["approval_reference"],
        approver_id=value["approver_id"],
        approver_key_fingerprint=value["approver_key_fingerprint"],
        trust_policy_sha256=value["trust_policy_sha256"],
        mission_id=value["mission_id"], operation_id=value["operation_id"],
        project_id=value["project_id"], repository_id=value["repository_id"],
        coordinator_id=value["coordinator_id"],
        mission_identity=value["mission_identity"],
        payload=value["payload"], payload_sha256=value["payload_sha256"],
        target=ca.Recipient(*value["target"]),
        model=value["model"], scope=value["scope"],
        reviewed_head_sha=value["reviewed_head_sha"],
        operational_digests=value["operational_digests"],
        max_transmissions=value["max_transmissions"],
        cost_ceiling_usd=_decimal(value.get("cost_ceiling_usd")),
        free_only=value["free_only"],
        issued_at=_parse_time(value["issued_at"]),
        expires_at=_parse_time(value["expires_at"]),
        nonce=value["nonce"], mode=value.get("mode", "SHADOW"))


def _cas_expected(expected: cla.ExpectedOperation) -> cas.ExpectedOperation:
    """Converte o material esperado do ledger para o contrato do verificador #77."""
    material = expected.material
    return cas.ExpectedOperation(
        mission_id=expected.mission_id, operation_id=expected.operation_id,
        project_id=expected.project_id, repository_id=expected.repository_id,
        coordinator_id=expected.coordinator_id,
        mission_identity=expected.mission_identity, payload=expected.payload,
        target=ca.Recipient(*expected.target_key),
        model=material["model"], scope=material["scope"],
        reviewed_head_sha=material["reviewed_head_sha"],
        operational_digests=material["operational_digests"],
        max_transmissions=material["max_transmissions"],
        cost_ceiling_usd=_decimal(material.get("cost_ceiling_usd")),
        free_only=material["free_only"])


@dataclass
class AuthorityPort:
    """Adapta o verificador certificado #77 à interface do ledger #80."""

    allowlist: cas.ApproverAllowlist

    def verify_authorization(self, *, envelope: Any, signature: bytes,
                             payload: Mapping[str, Any],
                             expected: cla.ExpectedOperation,
                             now: datetime) -> cla.VerifiedAuthorization:
        parsed = _envelope_from_mapping(envelope)
        result = cas.verify_signed_envelope(
            parsed, signature, self.allowlist, now=now,
            expected=_cas_expected(expected))
        if not result.accepted:
            raise cla.AuthorizationRejected(f"{result.code}: {result.detail}")
        verified = result.verified
        assert verified is not None
        auth_digest = result.authorization_digest or cas.authorization_digest(parsed)
        material_sha = hashlib.sha256(
            cla.canonical_json(expected.material).encode("utf-8")).hexdigest()
        return cla.VerifiedAuthorization(
            authorization_digest=auth_digest,
            authorization_id=verified.authorization_id,
            approval_reference=parsed.approval_reference,
            mission_id=parsed.mission_id,
            mission_identity=verified.mission_identity,
            authority_material_sha256=material_sha,
            approver_id=verified.approver_id,
            approver_key_fingerprint=verified.approver_key_fingerprint,
            trust_policy_sha256=verified.trust_policy_sha256,
            environment=str(expected.material.get("environment", "FIXTURE")),
            issued_at=verified.issued_at, expires_at=verified.expires_at,
            nonce=parsed.nonce, signature=bytes(signature))

    def verify_revocation(self, *, statement: Any, signature: bytes,
                          now: datetime) -> cla.VerifiedRevocation:
        if not isinstance(statement, Mapping):
            raise cla.RevocationRejected("declaração de revogação não é mapping")
        parsed = cas.SignedRevocation(
            schema=statement["schema"],
            authorization_digest=statement["authorization_digest"],
            approver_id=statement["approver_id"],
            approver_key_fingerprint=statement["approver_key_fingerprint"],
            trust_policy_sha256=statement["trust_policy_sha256"],
            revoked_at=_parse_time(statement["revoked_at"]),
            reason=statement["reason"], nonce=statement["nonce"])
        result = cas.verify_signed_revocation(parsed, signature, self.allowlist, now=now)
        if not result.verified or not result.effective:
            raise cla.RevocationRejected(f"{result.code}: {result.detail}")
        return cla.VerifiedRevocation(
            authorization_digest=result.authorization_digest,
            approver_id=parsed.approver_id,
            approver_key_fingerprint=parsed.approver_key_fingerprint,
            trust_policy_sha256=parsed.trust_policy_sha256,
            revoked_at=parsed.revoked_at, reason=parsed.reason,
            nonce=parsed.nonce, signature=bytes(signature))


@dataclass
class ReconciliationPort:
    """Atestação terminal sintética — NÃO é autoridade humana real."""

    key: bytes

    def verify_reconciliation(self, *, attestation: Any, signature: bytes,
                              expected: cla.ExpectedReconciliation,
                              now: datetime) -> cla.VerifiedReconciliation:
        material = {
            "authority_id": "synthetic-attestor", "mission_id": expected.mission_id,
            "authorization_digest": expected.authorization_digest,
            "payload_sha256": expected.payload_sha256,
            "receipt_sha256": expected.receipt_sha256,
            "report_sha256": expected.report_sha256,
            "evidence_sha256": expected.evidence_sha256,
            "outcome": expected.outcome,
        }
        digest = hashlib.sha256(cla.canonical_json(material).encode("utf-8")).hexdigest()
        if bytes(signature) != hashlib.sha256(
                self.key + digest.encode("utf-8")).digest():
            raise cla.ReconciliationRejected("atestação sintética divergente")
        return cla.VerifiedReconciliation(
            authority_id="synthetic-attestor", mission_id=expected.mission_id,
            authorization_digest=expected.authorization_digest,
            payload_sha256=expected.payload_sha256,
            receipt_sha256=expected.receipt_sha256,
            report_sha256=expected.report_sha256,
            evidence_sha256=expected.evidence_sha256,
            outcome=expected.outcome, issued_at=now,
            expires_at=now + timedelta(minutes=10), nonce="attest-" + digest[:16],
            signature=bytes(signature))


def _attestation_signature(*, key: bytes, mission_id: str, authorization_digest: str,
                           payload_sha256: str, receipt_sha256: str,
                           report_sha256: str, evidence_sha256: str,
                           outcome: str) -> bytes:
    material = {
        "authority_id": "synthetic-attestor", "mission_id": mission_id,
        "authorization_digest": authorization_digest,
        "payload_sha256": payload_sha256, "receipt_sha256": receipt_sha256,
        "report_sha256": report_sha256, "evidence_sha256": evidence_sha256,
        "outcome": outcome,
    }
    digest = hashlib.sha256(cla.canonical_json(material).encode("utf-8")).hexdigest()
    return hashlib.sha256(key + digest.encode("utf-8")).digest()


class SignedOperationStore(p14a.LedgerOperationStore):
    """Store da CLI integralmente assentado no ledger 1.1 + autoridade #77.

    Preserva a interface de `LedgerOperationStore` usada por `PilotExecutor`,
    mas substitui TODAS as transições legadas por:
      register_signed_proposed → authorize_signed → reserve_dispatch_signed →
      assert_dispatchable → record_dispatch_evidence → reconcile_signed.
    Nenhum cache é autoridade; `UNKNOWN` não reenvia nem libera reserva.
    """

    def __init__(self, *, mission: cc.MissionEnvelope, store_dir: str,
                 model: str, now: datetime, target: Any,
                 authorization_reference: str, authorization_canonical: str,
                 clock: Optional[Any] = None,
                 cache_dir: Optional[str] = None) -> None:
        clock = clock or (lambda: now)
        signer = SyntheticSigner(store_dir, persist=True)
        allowlist = cas.ApproverAllowlist((signer.approver_key(now),))
        snapshot = build_snapshot(mission, model=model, now=now)
        envelope, signature = build_envelope(
            snapshot, signer=signer, allowlist=allowlist, now=now,
            directory=store_dir, approval_reference=authorization_reference)
        db = ledger.Ledger(
            os.path.join(store_dir, "ledger.db"),
            project_id=mission.project_id, repository_id=mission.repository_id,
            authority_verifier=AuthorityPort(allowlist=allowlist),
            reconciliation_verifier=ReconciliationPort(key=b"p14b-synthetic"),
            clock=clock,
            policy={"allow_legacy_dispatch": False, "allow_real_transport": False})
        super().__init__(
            db, mission_id=mission.mission_id, operation_id=mission.operation_id,
            authorization_reference=authorization_reference,
            payload=dict(mission.payload), target=target, clock=clock,
            authorization_canonical=authorization_canonical,
            mission_snapshot=pilot.PilotExecutor._snapshot(mission),
            synthetic_authorization=p14a.synthetic_fixture_authorization(
                authorization_reference, consumer_id="cli-p14b"),
            cache_dir=cache_dir)
        self._mission = mission
        self._signer = signer
        self._allowlist = allowlist
        self._snapshot = snapshot
        self._envelope = envelope
        self._envelope_mapping = envelope.canonical_material()
        self._signature = signature
        self._digest = cas.authorization_digest(envelope)
        self._lease_id: Optional[str] = None

    # ---------- helpers ----------
    def _current_lease_id(self) -> str:
        """Recupera o lease persistido após reinício; nunca é reconstruído."""
        if self._lease_id:
            return self._lease_id
        with self.db._read() as conn:
            row = conn.execute(
                "SELECT lease_id FROM reservations WHERE mission_id=?",
                (self.mission_id,)).fetchone()
        if row is not None:
            self._lease_id = row["lease_id"]
        return self._lease_id or ""

    def _clean_evidence(self, evidence: Mapping[str, Any]) -> dict[str, Any]:
        return {"source": "fixture", "environment": "FIXTURE",
                "service_contacted": False, "transport_real": False,
                "detail": cla.canonical_json(dict(evidence))[:4096]}

    def _receipt_from_evidence(self, evidence: Mapping[str, Any]) -> dict[str, Any]:
        nested = evidence.get("receipt")
        raw = dict(nested) if isinstance(nested, Mapping) else {}
        return {
            "operation_id": self.operation_id,
            "payload_sha256": self.payload_hash,
            "agent_id": self.target.agent_id,
            "session_id": self.target.session_id,
            "instance_id": self.target.instance_id,
            "location": self.target.location,
            "observed_message_id": (raw.get("observed_message_id")
                                    or evidence.get("message_id")
                                    or f"msg-p14b-{self.operation_id}"),
            "source": "fixture",
        }

    def _persisted_receipt(self) -> dict[str, Any]:
        """Receipt autoritativo: vem do ledger, nunca do cache ou do chamador."""
        with self.db._read() as conn:
            row = conn.execute(
                "SELECT receipt_json FROM missions WHERE mission_id=?",
                (self.mission_id,)).fetchone()
        if row is not None and row["receipt_json"]:
            try:
                value = json.loads(row["receipt_json"])
                if isinstance(value, dict):
                    return value
            except ValueError:
                pass
        return self._receipt_from_evidence(self._evidence)

    def _report(self, receipt: Mapping[str, Any]) -> dict[str, Any]:
        snapshot = self._snapshot
        return {
            "project_id": snapshot.project_id, "repository_id": snapshot.repository_id,
            "mission_id": self.mission_id, "operation_id": self.operation_id,
            "agent_id": snapshot.agent, "session_id": snapshot.session_id,
            "message_id": f"msg-report-{self.operation_id}",
            "reply_to": receipt["observed_message_id"], "status": "COMPLETED",
            "blockers": [],
            "acceptance_results": {c: True for c in snapshot.acceptance_criteria},
            "payload_sha256": snapshot.payload_sha256,
            "completed_at": cc.iso(self.clock()),
        }

    def _reconcile_terminal(self, target: str, evidence: Mapping[str, Any]) -> None:
        if target not in (ledger.COMPLETED, ledger.FAILED):
            raise cc.AuthorizationError("reconciliação assinada exige COMPLETED/FAILED")
        receipt = self._persisted_receipt()
        clean = self._clean_evidence(evidence)
        report = self._report(receipt) if target == ledger.COMPLETED else None
        receipt_sha = hashlib.sha256(
            cla.canonical_json(receipt).encode("utf-8")).hexdigest()
        report_sha = hashlib.sha256(
            cla.canonical_json(report if report is not None else None).encode("utf-8")).hexdigest()
        evidence_sha = hashlib.sha256(cla.canonical_json(clean).encode("utf-8")).hexdigest()
        signature = _attestation_signature(
            key=b"p14b-synthetic", mission_id=self.mission_id,
            authorization_digest=self._digest, payload_sha256=self.payload_hash,
            receipt_sha256=receipt_sha, report_sha256=report_sha,
            evidence_sha256=evidence_sha, outcome=target)
        self.db.reconcile_signed(
            self.mission_id, authorization_digest=self._digest,
            lease_id=self._current_lease_id(), target_state=target,
            attestation={"authority": "synthetic-attestor", "outcome": target},
            attestation_signature=signature, report=report, evidence=clean,
            actor=self.actor)

    # ---------- interface do store ----------
    def create(self, record: pilot.PilotOperationRecord) -> pilot.PilotOperationRecord:
        current = self.db.get(self.mission_id)
        if current is not None and current.state not in (ledger.PROPOSED, ledger.AUTHORIZED):
            raise cc.AuthorizationError("operação já iniciada no ledger; sem reenvio")
        self.db.register_signed_proposed(
            self._snapshot, authorization_reference=self.authorization_reference,
            timeout_seconds=1)
        try:
            self.db.authorize_signed(
                self.mission_id, envelope=self._envelope_mapping,
                signature=self._signature, payload=self.payload, actor=self.actor)
        except (ledger.LedgerError, cla.AuthorityContractError) as exc:
            raise cc.AuthorizationError(
                "autorização assinada não aceita; sem reenvio") from exc
        self._write_cache()
        return self.load()

    def assert_transmittable(self) -> pilot.PilotOperationRecord:
        row = self.db.get(self.mission_id)
        if row is None or row.state != ledger.AUTHORIZED:
            raise cc.AuthorizationError("ledger não autoriza primeira transmissão")
        if self.db.is_reserved(self.mission_id):
            raise cc.AuthorizationError("reserva já ativa; sem reenvio")
        return self.load()

    @contextmanager
    def reserve(self):
        try:
            lease = self.db.reserve_dispatch_signed(
                self.mission_id, authorization_digest=self._digest,
                agent=self.target.agent_id, session_id=self.target.session_id,
                instance_id=self.target.instance_id, worktree=self.target.location,
                actor=self.actor)
        except (ledger.LedgerError, cla.AuthorityContractError) as exc:
            raise cc.AuthorizationError("reserva já ativa; sem reenvio") from exc
        self._lease_id = lease.lease_id
        self._evidence["reservation"] = self._reservation_view()
        self._write_cache()
        yield self._reservation_view()

    def assert_reserved(self) -> pilot.PilotOperationRecord:
        row = self.db.get(self.mission_id)
        if row is None or row.state != ledger.DISPATCHING:
            raise cc.AuthorizationError("reserva de despacho não confirmada pelo ledger")
        if not self.db.is_reserved(self.mission_id):
            raise cc.AuthorizationError("reserva de despacho ausente no ledger")
        return self.load()

    def update(self, state: str, evidence: Mapping[str, Any]) -> pilot.PilotOperationRecord:
        self._evidence.update(dict(evidence))
        if state == ledger.DISPATCHING and "receipt" in evidence:
            # O marker `pre_transmit` não é receipt: somente a observação
            # pós-transmissão pode persistir receipt/evidência no ledger.
            self.db.record_dispatch_evidence(
                self.mission_id, authorization_digest=self._digest,
                lease_id=self._current_lease_id(),
                receipt=self._receipt_from_evidence(evidence),
                evidence=self._clean_evidence(evidence), actor=self.actor)
        elif state == ledger.UNKNOWN:
            self.db.recover_stale_dispatching(cause="process_loss", actor=self.actor)
            self._evidence["automatic_resend"] = False
        elif state in (ledger.COMPLETED, ledger.FAILED):
            current = self.db.get(self.mission_id)
            if current is not None and current.state in (ledger.COMPLETED, ledger.FAILED):
                raise cc.ContractError("missão já terminal; relatório duplicado sem nova transição")
            self._reconcile_terminal(state, evidence)
        # DELIVERED/RUNNING são apenas marcos de evidência no ledger 1.1;
        # a conclusão é sempre autenticada via reconcile_signed.
        self._write_cache()
        return self.load()

    def recover(self) -> pilot.PilotOperationRecord:
        self.db.recover_stale_dispatching(cause="process_loss", actor=self.actor)
        self._evidence["automatic_resend"] = False
        self._write_cache()
        return self.load()

    def reconcile_unknown(self, target: str, *, evidence: str = "",
                          human_decision: bool = False) -> pilot.PilotOperationRecord:
        row = self.db.get(self.mission_id)
        if row is None or row.state != ledger.UNKNOWN:
            raise cc.AuthorizationError("reconciliação exige estado UNKNOWN no ledger")
        self._reconcile_terminal(target, {"reason": evidence})
        self._write_cache()
        return self.load()


def run_synthetic_journey(mission: cc.MissionEnvelope, *, store_dir: str,
                          now: Optional[datetime] = None,
                          interrupt_before_receipt: bool = False,
                          revoke_after_interrupt: bool = False,
                          expire_after_interrupt: bool = False) -> dict[str, Any]:
    """Executa a jornada P14B com transporte sintético; nunca faz POST real."""
    now = now or datetime.now(timezone.utc)
    result_recommendation = cli.fixture_model_recommendation()
    model = cli.selected_model_for_dispatch(result_recommendation)

    with tempfile.TemporaryDirectory(prefix="p14b-synthetic-") as keys:
        signer = SyntheticSigner(keys)
        allowlist = cas.ApproverAllowlist((signer.approver_key(now),))
        snapshot = build_snapshot(mission, model=model, now=now)
        envelope, signature = build_envelope(
            snapshot, signer=signer, allowlist=allowlist, now=now, directory=keys)
        envelope_mapping = envelope.canonical_material()
        digest = cas.authorization_digest(envelope)
        authority = AuthorityPort(allowlist=allowlist)
        reconciliation = ReconciliationPort(key=b"p14b-synthetic")
        db = ledger.Ledger(
            os.path.join(store_dir, "ledger.db"), project_id=mission.project_id,
            repository_id=mission.repository_id, authority_verifier=authority,
            reconciliation_verifier=reconciliation, clock=lambda: now,
            policy={"allow_legacy_dispatch": False, "allow_real_transport": False})
        db.register_signed_proposed(
            snapshot, authorization_reference=envelope.approval_reference,
            timeout_seconds=1)
        db.authorize_signed(
            mission.mission_id, envelope=envelope_mapping, signature=signature,
            payload=dict(snapshot.payload), actor="human-synthetic")
        lease = db.reserve_dispatch_signed(
            mission.mission_id, authorization_digest=digest, agent=snapshot.agent,
            session_id=snapshot.session_id, instance_id=snapshot.instance_id,
            worktree=snapshot.worktree, actor="coordinator")
        checked = db.assert_dispatchable(
            mission.mission_id, authorization_digest=digest,
            lease_id=lease.lease_id, actor="executor")
        checked_state = db.get(mission.mission_id).state

        result: dict[str, Any] = {
            "source": "synthetic", "transport_real": False, "service_contacted": False,
            "model": model, "authorization_digest": digest,
            "lease_id": lease.lease_id, "checked_state": checked_state,
            "basis": "coord_authority_signed+coord_ledger_1_1",
        }
        if interrupt_before_receipt:
            # POST sintético já ocorreu (último gate passou), mas receipt não persistiu.
            db.recover_stale_dispatching(cause="process_loss", actor="executor")
            record = db.get(mission.mission_id)
            result.update({
                "state": record.state, "receipt_sha256": record.receipt_sha256,
                "reserved": db.is_reserved(mission.mission_id),
                "can_dispatch": db.can_dispatch(mission.mission_id),
            })
            late_clock = now + timedelta(hours=1) if expire_after_interrupt else now
            late = ledger.Ledger(
                os.path.join(store_dir, "ledger.db"), project_id=mission.project_id,
                repository_id=mission.repository_id, authority_verifier=authority,
                reconciliation_verifier=reconciliation, clock=lambda: late_clock,
                policy={"allow_legacy_dispatch": False, "allow_real_transport": False})
            if revoke_after_interrupt or expire_after_interrupt:
                statement = cas.SignedRevocation(
                    schema=cas.REVOCATION_SCHEMA_VERSION,
                    authorization_digest=digest, approver_id=signer.approver_id,
                    approver_key_fingerprint=signer.fingerprint,
                    trust_policy_sha256=allowlist.digest(),
                    revoked_at=now, reason="contramandamento pós-despacho",
                    nonce="revocation-p14b-" + snapshot.operation_id)
                rev_sig = signer.sign(cas.canonical_revocation_bytes(statement),
                                      namespace=cas.REVOCATION_NAMESPACE,
                                      directory=keys)
                late.revoke_authorization(
                    statement=statement.canonical_material(), signature=rev_sig,
                    actor="human-synthetic")
            attempt = "blocked"
            try:
                late.terminate_signed_before_dispatch(
                    mission.mission_id, authorization_digest=digest,
                    lease_id=lease.lease_id,
                    evidence={"source": "fixture", "environment": "FIXTURE",
                              "reason": "expired_or_revoked_before_dispatch"},
                    actor="qa")
                attempt = "accepted"
            except Exception:
                # Qualquer falha (estado UNKNOWN, autorização expirada ou
                # revalidação fail-closed) mantém a reserva preservada.
                pass
            result.update({
                "late_state": late.get(mission.mission_id).state,
                "late_reserved": late.is_reserved(mission.mission_id),
                "late_can_dispatch": late.can_dispatch(mission.mission_id),
                "post_interrupt_terminalize_attempt": attempt,
            })
            return result

        receipt = {
            "operation_id": mission.operation_id, "payload_sha256": snapshot.payload_sha256,
            "agent_id": snapshot.agent, "session_id": snapshot.session_id,
            "instance_id": snapshot.instance_id, "location": snapshot.worktree,
            "observed_message_id": "msg-p14b-synthetic", "source": "fixture",
        }
        evidence = {"source": "fixture", "environment": "FIXTURE",
                    "service_contacted": False, "transport_real": False}
        db.record_dispatch_evidence(
            mission.mission_id, authorization_digest=digest, lease_id=lease.lease_id,
            receipt=receipt, evidence=evidence, actor="executor")
        report = {
            "project_id": mission.project_id, "repository_id": mission.repository_id,
            "mission_id": mission.mission_id, "operation_id": mission.operation_id,
            "agent_id": snapshot.agent, "session_id": snapshot.session_id,
            "message_id": "msg-report-p14b", "reply_to": receipt["observed_message_id"],
            "status": "COMPLETED", "blockers": [],
            "acceptance_results": {c: True for c in snapshot.acceptance_criteria},
            "payload_sha256": snapshot.payload_sha256,
            "completed_at": cc.iso(now),
        }
        attestation = {"authority": "synthetic-attestor", "outcome": "COMPLETED"}
        att_sig = _attestation_signature(
            key=b"p14b-synthetic", mission_id=mission.mission_id,
            authorization_digest=digest, payload_sha256=snapshot.payload_sha256,
            receipt_sha256=hashlib.sha256(
                cla.canonical_json(receipt).encode("utf-8")).hexdigest(),
            report_sha256=hashlib.sha256(
                cla.canonical_json(report).encode("utf-8")).hexdigest(),
            evidence_sha256=hashlib.sha256(
                cla.canonical_json(evidence).encode("utf-8")).hexdigest(),
            outcome="COMPLETED")
        final = db.reconcile_signed(
            mission.mission_id, authorization_digest=digest, lease_id=lease.lease_id,
            target_state=ledger.COMPLETED, attestation=attestation,
            attestation_signature=att_sig, report=report, evidence=evidence, actor="qa")
        result.update({
            "state": final.state, "reserved": db.is_reserved(mission.mission_id),
            "receipt_sha256": final.receipt_sha256,
        })
        return result
