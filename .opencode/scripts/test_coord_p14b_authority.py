#!/usr/bin/env python3
"""P14B — testes host-only do adapter de autoridade (#77 → ledger 1.1 #80).

Chaves são sintéticas, descartáveis e temporárias; nenhuma chave pessoal é
lida. Não há transporte, POST, rede, LLM, credenciais ou operação real.
"""
from __future__ import annotations

import dataclasses
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import coord_authority as ca
import coord_authority_signed as cas
import coord_communication as cc
import coord_ledger as ledger
import coord_ledger_authority as la
import coord_p14b_authority as p14b

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)
HEAD = "a" * 40
PROJECT = "better-talker"
REPO = "guilhermemellojw/better-talker"
MISSION_ID = "MISS-P14B-001"
OPERATION_ID = "OP-P14B-001"
PAYLOAD = {"schema": "BT-COORD-PAYLOAD-V1", "message": "synthetic", "n": 1}
CRITERIA = ("criterion-a", "criterion-b")
SCOPE = "P14B-SHADOW"
MODEL = "opencode/hy4-flash-free"
DIGESTS = {
    "coord_p14b_authority.py": "b" * 64,
    "coord_authority_signed.py": "c" * 64,
}

_KEYS: "SyntheticKeys | None" = None
HUMAN_FP = HUMAN_PUB = ""
OTHER_FP = OTHER_PUB = ""
ATTACKER_FP = ATTACKER_PUB = ""


class SyntheticKeys:
    """Gera chaves descartáveis em diretório temporário isolado."""

    def __init__(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="p14b-keys-")

    def generate(self, name: str) -> tuple[str, str, str]:
        path = os.path.join(self.directory, name)
        result = subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "",
             "-C", f"synthetic-p14b-{name}", "-f", path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            env=self._env())
        if result.returncode != 0:
            raise RuntimeError(
                f"ssh-keygen falhou: {result.stderr.decode(errors='replace')}")
        with open(path + ".pub", "r", encoding="utf-8") as handle:
            public = handle.read().strip()
        return path, public, cas.public_key_fingerprint(public)

    def sign(self, name: str, data: bytes,
             namespace: str = p14b.AUTHORIZATION_NAMESPACE) -> bytes:
        fd, data_path = tempfile.mkstemp(
            dir=self.directory, prefix="data-", suffix=".bin")
        os.close(fd)
        try:
            with open(data_path, "wb") as handle:
                handle.write(data)
            result = subprocess.run(
                ["ssh-keygen", "-Y", "sign", "-f",
                 os.path.join(self.directory, name), "-n", namespace, data_path],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
                env=self._env(), cwd=self.directory)
            if result.returncode != 0:
                raise RuntimeError(
                    f"ssh-keygen sign falhou: {result.stderr.decode(errors='replace')}")
            with open(data_path + ".sig", "rb") as handle:
                return handle.read()
        finally:
            for path in (data_path, data_path + ".sig"):
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass

    def _env(self) -> dict[str, str]:
        return {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "LC_ALL": "C",
            "LANG": "C",
            "HOME": self.directory,
        }

    def cleanup(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)


def setUpModule() -> None:
    global _KEYS, HUMAN_FP, HUMAN_PUB, OTHER_FP, OTHER_PUB
    global ATTACKER_FP, ATTACKER_PUB
    _KEYS = SyntheticKeys()
    _, HUMAN_PUB, HUMAN_FP = _KEYS.generate("human")
    _, OTHER_PUB, OTHER_FP = _KEYS.generate("other")
    _, ATTACKER_PUB, ATTACKER_FP = _KEYS.generate("attacker")


def tearDownModule() -> None:
    global _KEYS
    if _KEYS is not None:
        _KEYS.cleanup()
        _KEYS = None


def _key(*, approver_id: str = "maintainer", fingerprint: str | None = None,
         public_key: str | None = None, **overrides) -> cas.ApproverKey:
    values = {
        "approver_id": approver_id,
        "fingerprint": HUMAN_FP if fingerprint is None else fingerprint,
        "public_key": HUMAN_PUB if public_key is None else public_key,
        "valid_from": NOW - timedelta(hours=2),
        "valid_until": NOW + timedelta(hours=2),
        "revoked_at": None,
    }
    values.update(overrides)
    return cas.ApproverKey(**values)


def _allowlist(*entries: cas.ApproverKey) -> cas.ApproverAllowlist:
    return cas.ApproverAllowlist(entries=tuple(entries) if entries else (_key(),))


def make_mission(**overrides) -> cc.MissionEnvelope:
    values = {
        "schema_version": cc.MISSION_SCHEMA_VERSION,
        "project_id": PROJECT,
        "repository_id": REPO,
        "mission_id": MISSION_ID,
        "operation_id": OPERATION_ID,
        "coordinator_id": "coordinator-a",
        "target_agent": "agent-a",
        "target_session_id": "ses-p14b",
        "target_instance_id": "inst-1",
        "target_location": "/tmp/wt-p14b",
        "objective": "adapter P14B sintético",
        "payload": PAYLOAD,
        "payload_sha256": cc.payload_sha256(PAYLOAD),
        "expected_deliverables": ("deliverable-a",),
        "acceptance_criteria": CRITERIA,
        "ownership_constraints": ("ownership-a",),
        "authorization_reference": "APP-P14B-001",
        "created_at": NOW - timedelta(hours=1),
        "expires_at": NOW + timedelta(hours=1),
        "timeout": 3600,
        "scope": SCOPE,
        "expected_head_sha": HEAD,
        "expected_branch": "feat/p14b-authority-adapter",
    }
    values.update(overrides)
    return cc.MissionEnvelope(**values)


def make_envelope(
    mission: cc.MissionEnvelope | None = None,
    allow: cas.ApproverAllowlist | None = None,
    **overrides,
) -> p14b.SignedAuthorizationV2:
    mission = mission or make_mission()
    allow = allow or _allowlist()
    values = {
        "schema": p14b.AUTHORIZATION_SCHEMA,
        "authorization_id": "AUTH-P14B-001",
        "approval_reference": mission.authorization_reference,
        "approver_id": "maintainer",
        "approver_key_fingerprint": HUMAN_FP,
        "trust_policy_sha256": allow.digest(),
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
        "operational_digests": dict(DIGESTS),
        "max_transmissions": 1,
        "cost_ceiling_usd": "0",
        "free_only": True,
        "issued_at": NOW - timedelta(minutes=5),
        "expires_at": NOW + timedelta(minutes=15),
        "nonce": "nonce-p14b-001",
        "mode": "SHADOW",
    }
    values.update(overrides)
    return p14b.SignedAuthorizationV2(**values)


def sign_authorization(envelope: p14b.SignedAuthorizationV2,
                       key: str = "human",
                       namespace: str = p14b.AUTHORIZATION_NAMESPACE) -> bytes:
    assert _KEYS is not None
    return _KEYS.sign(
        key, p14b.canonical_authorization_bytes(envelope), namespace)


def make_adapter(allow: cas.ApproverAllowlist | None = None,
                 mission: cc.MissionEnvelope | None = None,
                 **kwargs) -> p14b.AuthorityAdapter:
    mission = mission or make_mission()
    return p14b.AuthorityAdapter(
        allowlist=allow or _allowlist(),
        mission_source={mission.mission_id: mission},
        **kwargs)


def make_expected(mission: cc.MissionEnvelope | None = None,
                  envelope: p14b.SignedAuthorizationV2 | None = None
                  ) -> la.ExpectedOperation:
    mission = mission or make_mission()
    envelope = envelope or make_envelope(mission)
    snapshot = p14b.snapshot_from_envelope(mission, envelope)
    return snapshot.expected_operation()


def make_reconciliation(
    *, authorization_digest: str, payload_sha256: str,
    receipt_sha256: str = "1" * 64, report_sha256: str = "2" * 64,
    evidence_sha256: str = "3" * 64, outcome: str = "COMPLETED",
    allow: cas.ApproverAllowlist | None = None,
    **overrides,
) -> p14b.SignedReconciliation:
    allow = allow or _allowlist()
    values = {
        "schema": p14b.RECONCILIATION_SCHEMA,
        "authority_id": "maintainer",
        "approver_id": "maintainer",
        "approver_key_fingerprint": HUMAN_FP,
        "trust_policy_sha256": allow.digest(),
        "mission_id": MISSION_ID,
        "authorization_digest": authorization_digest,
        "payload_sha256": payload_sha256,
        "receipt_sha256": receipt_sha256,
        "report_sha256": report_sha256,
        "evidence_sha256": evidence_sha256,
        "outcome": outcome,
        "environment": "SHADOW",
        "issued_at": NOW,
        "expires_at": NOW + timedelta(minutes=10),
        "nonce": "nonce-recon-001",
        "mode": "SHADOW",
    }
    values.update(overrides)
    return p14b.SignedReconciliation(**values)


def make_expected_reconciliation(
    attestation: p14b.SignedReconciliation,
) -> la.ExpectedReconciliation:
    return la.ExpectedReconciliation(
        mission_id=attestation.mission_id,
        authorization_digest=attestation.authorization_digest,
        payload_sha256=attestation.payload_sha256,
        receipt_sha256=attestation.receipt_sha256,
        report_sha256=attestation.report_sha256,
        evidence_sha256=attestation.evidence_sha256,
        outcome=attestation.outcome,
    )


class AdapterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.allow = _allowlist()
        self.mission = make_mission()
        self.envelope = make_envelope(self.mission, self.allow)
        self.snapshot = p14b.snapshot_from_envelope(self.mission, self.envelope)
        self.expected = self.snapshot.expected_operation()
        self.adapter = make_adapter(self.allow, self.mission)

    def verify(self, *, envelope=None, signature=None, expected=None, now=NOW):
        envelope = envelope or self.envelope
        signature = signature or sign_authorization(envelope)
        expected = expected or self.expected
        return self.adapter.verify_authorization(
            envelope=envelope.to_mapping(), signature=signature,
            payload=self.mission.payload, expected=expected, now=now)

    def assert_rejected(self, code: str, **kwargs) -> None:
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.verify(**kwargs)
        self.assertIn(code, str(caught.exception))

    # 1. Caminho válido e material completo.
    def test_valid_authorization_binds_ledger_material(self):
        result = self.verify()
        self.assertEqual(p14b.authorization_digest(self.envelope),
                         result.authorization_digest)
        self.assertEqual(self.snapshot.authority_material_sha256(),
                         result.authority_material_sha256)
        self.assertEqual(self.mission.mission_id, result.mission_id)
        self.assertEqual("SHADOW", result.environment)
        self.assertEqual(HUMAN_FP, result.approver_key_fingerprint)
        self.assertTrue(result.signature)

    # 2. Assinatura forjada e namespace incorreto.
    def test_forged_signature_and_wrong_namespace_are_rejected(self):
        self.assert_rejected(
            "SIGNATURE_INVALID",
            signature=sign_authorization(self.envelope, key="attacker"))
        self.assert_rejected(
            "SIGNATURE_INVALID",
            signature=sign_authorization(
                self.envelope, namespace=cas.SIGNATURE_NAMESPACE))

    # 3. V1 é insuficiente; schema desconhecido e campos extras falham.
    def test_v1_and_unknown_or_extra_fields_are_rejected(self):
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.adapter.verify_authorization(
                envelope={"schema": cas.SCHEMA_VERSION},
                signature=b"x", payload=self.mission.payload,
                expected=self.expected, now=NOW)
        self.assertIn("INSUFFICIENT_ENVELOPE", str(caught.exception))
        unknown = self.envelope.to_mapping()
        unknown["schema"] = "BT-COORD-AUTHORIZATION-V999"
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.adapter.verify_authorization(
                envelope=unknown, signature=b"x", payload=self.mission.payload,
                expected=self.expected, now=NOW)
        self.assertIn("UNKNOWN_SCHEMA", str(caught.exception))
        extra = self.envelope.to_mapping()
        extra["unexpected"] = "field"
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.adapter.verify_authorization(
                envelope=extra, signature=b"x", payload=self.mission.payload,
                expected=self.expected, now=NOW)
        self.assertIn("campos inválidos", str(caught.exception))

    # 4. Material divergente: critérios, ambiente, modelo e payload.
    def test_divergent_material_is_rejected(self):
        cases = (
            ("criteria", dataclasses.replace(
                self.snapshot, acceptance_criteria=("other",))),
            ("environment", dataclasses.replace(
                self.snapshot, environment="FIXTURE")),
            ("model", dataclasses.replace(self.snapshot, model="other/model")),
            ("payload", dataclasses.replace(
                self.snapshot, payload={"schema": "other"})),
        )
        for label, snapshot in cases:
            with self.subTest(label=label):
                with self.assertRaises(la.AuthorizationRejected) as caught:
                    self.verify(expected=snapshot.expected_operation())
                self.assertIn("OPERATION_MISMATCH", str(caught.exception))

    # 5. Identidade incompatível com a MissionEnvelope #64.
    def test_incompatible_identity_is_rejected(self):
        tampered_mission = make_mission(payload={"schema": "tampered"})
        tampered_mission = dataclasses.replace(
            tampered_mission,
            payload={"schema": "tampered"},
            payload_sha256=cc.payload_sha256({"schema": "tampered"}),
        )
        adapter = make_adapter(self.allow, tampered_mission)
        with self.assertRaises(la.AuthorizationRejected) as caught:
            adapter.verify_authorization(
                envelope=self.envelope.to_mapping(),
                signature=sign_authorization(self.envelope),
                payload=self.mission.payload, expected=self.expected, now=NOW)
        self.assertIn("MISSION_IDENTITY_MISMATCH", str(caught.exception))

    # 6. Trust policy, chave desconhecida e fingerprint divergente.
    def test_trust_policy_and_fingerprint_fail_closed(self):
        other = _key(approver_id="other", fingerprint=OTHER_FP,
                     public_key=OTHER_PUB)
        changed_allow = _allowlist(other)
        adapter = make_adapter(changed_allow, self.mission)
        with self.assertRaises(la.AuthorizationRejected) as caught:
            adapter.verify_authorization(
                envelope=self.envelope.to_mapping(),
                signature=sign_authorization(self.envelope),
                payload=self.mission.payload, expected=self.expected, now=NOW)
        self.assertIn("TRUST_POLICY_MISMATCH", str(caught.exception))

        ghost = make_envelope(
            self.mission, self.allow, approver_id="ghost",
            approver_key_fingerprint=HUMAN_FP)
        ghost_expected = p14b.snapshot_from_envelope(
            self.mission, self.envelope).expected_operation()
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.adapter.verify_authorization(
                envelope=ghost.to_mapping(),
                signature=sign_authorization(ghost),
                payload=self.mission.payload, expected=ghost_expected, now=NOW)
        self.assertIn("APPROVER_KEY_UNKNOWN", str(caught.exception))

        wrong_fp = _key(fingerprint=OTHER_FP)
        wrong_allow = _allowlist(wrong_fp)
        wrong_envelope = make_envelope(
            self.mission, wrong_allow, approver_key_fingerprint=OTHER_FP)
        adapter = make_adapter(wrong_allow, self.mission)
        with self.assertRaises(la.AuthorizationRejected) as caught:
            adapter.verify_authorization(
                envelope=wrong_envelope.to_mapping(),
                signature=sign_authorization(wrong_envelope),
                payload=self.mission.payload, expected=self.expected, now=NOW)
        self.assertIn("FINGERPRINT_MISMATCH", str(caught.exception))

    # 7. Expiração da autorização e inatividade da chave.
    def test_expiration_and_inactive_key_are_rejected(self):
        expired = make_envelope(
            self.mission, self.allow,
            issued_at=NOW - timedelta(minutes=10),
            expires_at=NOW - timedelta(minutes=1))
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.verify(envelope=expired, now=NOW)
        self.assertIn("AUTHORIZATION_EXPIRED", str(caught.exception))

        future = make_envelope(
            self.mission, self.allow,
            issued_at=NOW + timedelta(minutes=1),
            expires_at=NOW + timedelta(minutes=10))
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.verify(envelope=future, now=NOW)
        self.assertIn("AUTHORIZATION_NOT_YET_VALID", str(caught.exception))

        inactive = _key(valid_until=NOW - timedelta(seconds=1))
        inactive_allow = _allowlist(inactive)
        inactive_envelope = make_envelope(self.mission, inactive_allow)
        adapter = make_adapter(inactive_allow, self.mission)
        with self.assertRaises(la.AuthorizationRejected) as caught:
            adapter.verify_authorization(
                envelope=inactive_envelope.to_mapping(),
                signature=sign_authorization(inactive_envelope),
                payload=self.mission.payload, expected=self.expected, now=NOW)
        self.assertIn("APPROVER_KEY_INACTIVE", str(caught.exception))

    # 8. Fixture não entra no caminho de autoridade humana.
    def test_fixture_environment_is_rejected_by_human_adapter(self):
        mapping = self.envelope.to_mapping()
        mapping["environment"] = "FIXTURE"
        signature = _KEYS.sign(
            "human", p14b.AUTHORIZATION_DOMAIN
            + p14b.canonical_json(mapping).encode("utf-8"),
            p14b.AUTHORIZATION_NAMESPACE)
        with self.assertRaises(la.AuthorizationRejected) as caught:
            self.adapter.verify_authorization(
                envelope=mapping, signature=signature,
                payload=self.mission.payload, expected=self.expected, now=NOW)
        self.assertIn("environment", str(caught.exception))

    # 9. Revogação válida, forjada e futura.
    def test_revocation_valid_forged_and_future(self):
        digest = p14b.authorization_digest(self.envelope)
        statement = cas.SignedRevocation(
            schema=cas.REVOCATION_SCHEMA_VERSION,
            authorization_digest=digest,
            approver_id="maintainer",
            approver_key_fingerprint=HUMAN_FP,
            trust_policy_sha256=self.allow.digest(),
            revoked_at=NOW - timedelta(minutes=1),
            reason="contramandamento",
            nonce="nonce-rev-p14b-001",
        )
        signature = _KEYS.sign(
            "human", cas.canonical_revocation_bytes(statement),
            cas.REVOCATION_NAMESPACE)
        verified = self.adapter.verify_revocation(
            statement=statement.canonical_material(),
            signature=signature, now=NOW)
        self.assertEqual(digest, verified.authorization_digest)

        with self.assertRaises(la.RevocationRejected):
            self.adapter.verify_revocation(
                statement=statement.canonical_material(),
                signature=b"forged", now=NOW)
        wrong_namespace = _KEYS.sign(
            "human", cas.canonical_revocation_bytes(statement),
            p14b.AUTHORIZATION_NAMESPACE)
        with self.assertRaises(la.RevocationRejected):
            self.adapter.verify_revocation(
                statement=statement.canonical_material(),
                signature=wrong_namespace, now=NOW)

        future = dataclasses.replace(
            statement, revoked_at=NOW + timedelta(hours=1),
            nonce="nonce-rev-p14b-future")
        future_signature = _KEYS.sign(
            "human", cas.canonical_revocation_bytes(future),
            cas.REVOCATION_NAMESPACE)
        future_result = self.adapter.verify_revocation(
            statement=future.canonical_material(),
            signature=future_signature, now=NOW)
        self.assertGreater(future_result.revoked_at, NOW)

    # 10. Atestação terminal válida e adulterações/namespace.
    def test_reconciliation_valid_and_tampered(self):
        digest = p14b.authorization_digest(self.envelope)
        attestation = make_reconciliation(
            authorization_digest=digest,
            payload_sha256=self.snapshot.payload_sha256)
        expected = make_expected_reconciliation(attestation)
        signature = _KEYS.sign(
            "human", p14b.canonical_reconciliation_bytes(attestation),
            p14b.RECONCILIATION_NAMESPACE)
        verified = self.adapter.verify_reconciliation(
            attestation=attestation.to_mapping(), signature=signature,
            expected=expected, now=NOW)
        self.assertEqual("COMPLETED", verified.outcome)
        self.assertEqual(digest, verified.authorization_digest)
        with self.assertRaises(la.ReconciliationRejected) as caught:
            self.adapter.verify_reconciliation(
                attestation=attestation.to_mapping(), signature=signature,
                expected=expected, now=NOW + timedelta(hours=1))
        self.assertIn("RECONCILIATION_EXPIRED", str(caught.exception))

        wrong = dataclasses.replace(attestation, report_sha256="f" * 64)
        with self.assertRaises(la.ReconciliationRejected) as caught:
            self.adapter.verify_reconciliation(
                attestation=wrong.to_mapping(),
                signature=signature,
                expected=expected, now=NOW)
        self.assertIn("RECONCILIATION_MISMATCH", str(caught.exception))

        wrong_namespace = _KEYS.sign(
            "human", p14b.canonical_reconciliation_bytes(attestation),
            p14b.AUTHORIZATION_NAMESPACE)
        with self.assertRaises(la.ReconciliationRejected) as caught:
            self.adapter.verify_reconciliation(
                attestation=attestation.to_mapping(),
                signature=wrong_namespace,
                expected=expected, now=NOW)
        self.assertIn("SIGNATURE_INVALID", str(caught.exception))

        tampered = attestation.to_mapping()
        tampered["evidence_sha256"] = "e" * 64
        with self.assertRaises(la.ReconciliationRejected):
            self.adapter.verify_reconciliation(
                attestation=tampered, signature=signature,
                expected=expected, now=NOW)

    # 11. Atestação de fixture é recusada.
    def test_reconciliation_fixture_is_rejected(self):
        digest = p14b.authorization_digest(self.envelope)
        attestation = make_reconciliation(
            authorization_digest=digest,
            payload_sha256=self.snapshot.payload_sha256)
        mapping = attestation.to_mapping()
        mapping["environment"] = "FIXTURE"
        signature = _KEYS.sign(
            "human", p14b.RECONCILIATION_DOMAIN
            + p14b.canonical_json(mapping).encode("utf-8"),
            p14b.RECONCILIATION_NAMESPACE)
        with self.assertRaises(la.ReconciliationRejected) as caught:
            self.adapter.verify_reconciliation(
                attestation=mapping, signature=signature,
                expected=make_expected_reconciliation(attestation), now=NOW)
        self.assertIn("environment", str(caught.exception))

    # 12. Integração completa no ledger 1.1.
    def test_ledger_end_to_end_authorization_and_reconciliation(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = ledger.Ledger(
                os.path.join(tmp, "ledger.db"), project_id=PROJECT,
                repository_id=REPO, authority_verifier=self.adapter,
                reconciliation_verifier=self.adapter, clock=lambda: NOW,
                policy={"allow_legacy_dispatch": False})
            db.register_signed_proposed(
                self.snapshot, authorization_reference=self.envelope.approval_reference)
            db.authorize_signed(
                self.mission.mission_id,
                envelope=self.envelope.to_mapping(),
                signature=sign_authorization(self.envelope),
                payload=self.mission.payload, actor="human")
            lease = db.reserve_dispatch_signed(
                self.mission.mission_id,
                authorization_digest=p14b.authorization_digest(self.envelope),
                agent=self.snapshot.agent, session_id=self.snapshot.session_id,
                instance_id=self.snapshot.instance_id,
                worktree=self.snapshot.worktree, actor="coord")
            db.assert_dispatchable(
                self.mission.mission_id,
                authorization_digest=p14b.authorization_digest(self.envelope),
                lease_id=lease.lease_id, actor="exec")

            receipt = {
                "source": "synthetic", "environment": "SHADOW",
                "observed_message_id": "msg-1",
                "operation_id": self.mission.operation_id,
                "payload_sha256": self.snapshot.payload_sha256,
                "agent_id": self.snapshot.agent,
                "session_id": self.snapshot.session_id,
                "instance_id": self.snapshot.instance_id,
                "location": self.snapshot.worktree,
            }
            evidence = {
                "source": "synthetic", "environment": "SHADOW",
                "service_contacted": False, "transport_real": False,
            }
            db.record_dispatch_evidence(
                self.mission.mission_id,
                authorization_digest=p14b.authorization_digest(self.envelope),
                lease_id=lease.lease_id, receipt=receipt,
                evidence=evidence, actor="exec")
            report = {
                "project_id": self.mission.project_id,
                "repository_id": self.mission.repository_id,
                "mission_id": self.mission.mission_id,
                "operation_id": self.mission.operation_id,
                "agent_id": self.mission.target_agent,
                "session_id": self.mission.target_session_id,
                "message_id": "report-1", "reply_to": "msg-1",
                "status": "COMPLETED", "blockers": [],
                "acceptance_results": {
                    criterion: True for criterion in self.mission.acceptance_criteria},
                "payload_sha256": self.snapshot.payload_sha256,
                "completed_at": ledger.iso(NOW),
            }
            def digest(value):
                return hashlib.sha256(
                    ledger.canonical_json(value).encode("utf-8")).hexdigest()
            attestation = make_reconciliation(
                authorization_digest=p14b.authorization_digest(self.envelope),
                payload_sha256=self.snapshot.payload_sha256,
                receipt_sha256=digest(receipt),
                report_sha256=digest(report),
                evidence_sha256=digest(evidence))
            reconciliation_signature = _KEYS.sign(
                "human", p14b.canonical_reconciliation_bytes(attestation),
                p14b.RECONCILIATION_NAMESPACE)
            record = db.reconcile_signed(
                self.mission.mission_id,
                authorization_digest=p14b.authorization_digest(self.envelope),
                lease_id=lease.lease_id, target_state=ledger.COMPLETED,
                report=report, evidence=evidence,
                attestation=attestation.to_mapping(),
                attestation_signature=reconciliation_signature, actor="qa")
            self.assertEqual(ledger.COMPLETED, record.state)
            self.assertFalse(db.is_reserved(self.mission.mission_id))

    # 13. Replay no ledger: mesmo authorization_id/nonce/identidade não pode
    # autorizar uma segunda missão.
    def test_ledger_blocks_authorization_replay_across_missions(self):
        with tempfile.TemporaryDirectory() as tmp:
            second_mission = make_mission(
                mission_id="MISS-P14B-002", operation_id="OP-P14B-002")
            second_envelope = make_envelope(
                second_mission, self.allow,
                approval_reference=second_mission.authorization_reference)
            second_snapshot = p14b.snapshot_from_envelope(
                second_mission, second_envelope)
            source = {
                self.mission.mission_id: self.mission,
                second_mission.mission_id: second_mission,
            }
            adapter = p14b.AuthorityAdapter(
                allowlist=self.allow, mission_source=source)
            db = ledger.Ledger(
                os.path.join(tmp, "ledger.db"), project_id=PROJECT,
                repository_id=REPO, authority_verifier=adapter,
                reconciliation_verifier=adapter, clock=lambda: NOW,
                policy={"allow_legacy_dispatch": False})
            db.register_signed_proposed(
                self.snapshot, authorization_reference=self.envelope.approval_reference)
            db.authorize_signed(
                self.mission.mission_id,
                envelope=self.envelope.to_mapping(),
                signature=sign_authorization(self.envelope),
                payload=self.mission.payload, actor="human")
            db.register_signed_proposed(
                second_snapshot,
                authorization_reference=second_envelope.approval_reference)
            with self.assertRaises(ledger.DuplicateMission):
                db.authorize_signed(
                    second_mission.mission_id,
                    envelope=second_envelope.to_mapping(),
                    signature=sign_authorization(second_envelope),
                    payload=second_mission.payload, actor="human")

    # 14. Revogação integrada bloqueia o assert sem reenvio.
    def test_ledger_revocation_blocks_assert(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = ledger.Ledger(
                os.path.join(tmp, "ledger.db"), project_id=PROJECT,
                repository_id=REPO, authority_verifier=self.adapter,
                reconciliation_verifier=self.adapter, clock=lambda: NOW,
                policy={"allow_legacy_dispatch": False})
            db.register_signed_proposed(
                self.snapshot, authorization_reference=self.envelope.approval_reference)
            db.authorize_signed(
                self.mission.mission_id,
                envelope=self.envelope.to_mapping(),
                signature=sign_authorization(self.envelope),
                payload=self.mission.payload, actor="human")
            digest = p14b.authorization_digest(self.envelope)
            lease = db.reserve_dispatch_signed(
                self.mission.mission_id, authorization_digest=digest,
                agent=self.snapshot.agent, session_id=self.snapshot.session_id,
                instance_id=self.snapshot.instance_id,
                worktree=self.snapshot.worktree, actor="coord")
            statement = cas.SignedRevocation(
                schema=cas.REVOCATION_SCHEMA_VERSION,
                authorization_digest=digest,
                approver_id="maintainer",
                approver_key_fingerprint=HUMAN_FP,
                trust_policy_sha256=self.allow.digest(),
                revoked_at=NOW - timedelta(seconds=1),
                reason="contramandamento",
                nonce="nonce-rev-ledger-001",
            )
            db.revoke_authorization(
                statement=statement.canonical_material(),
                signature=_KEYS.sign(
                    "human", cas.canonical_revocation_bytes(statement),
                    cas.REVOCATION_NAMESPACE), actor="human")
            with self.assertRaises(ledger.AuthorizationRevoked):
                db.assert_dispatchable(
                    self.mission.mission_id, authorization_digest=digest,
                    lease_id=lease.lease_id, actor="exec")


if __name__ == "__main__":
    unittest.main(verbosity=2)
