#!/usr/bin/env python3
"""COORD-06B — testes host-only da autoridade assinada (offline).

Chaves são sintéticas, descartáveis e geradas em diretório temporário isolado
por `setUpModule`; nenhuma chave pessoal/existente é lida. O módulo testado é
somente verificador: os testes também verificam que ele não assina e não
persiste consumo.
"""
from __future__ import annotations

import ast
import dataclasses
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coord_authority as ca
import coord_authority_signed as cas

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)
HEAD = "a" * 40
MISSION_IDENTITY = "d" * 64
PAYLOAD = {"schema": "BT-COORD-PAYLOAD-V1", "message": "synthetic", "n": 1}
TARGET = ca.Recipient("agent-a", "ses-coord06b", "/home/guilherme/bt-coord06b", "inst-1")
OTHER_TARGET = ca.Recipient("agent-b", "ses-other", "/home/guilherme/bt-other", "inst-2")
MODEL = "opencode/hy4-flash-free"
SCOPE = "COORD-06B-OFFLINE"
DIGESTS = {
    "coord_authority.py": "e" * 64,
    "coord_authority_signed.py": "f" * 64,
}

_KEYS: "SyntheticKeys | None" = None
HUMAN_FP = HUMAN_PUB = ""
OTHER_FP = OTHER_PUB = ""
ATTACKER_FP = ATTACKER_PUB = ""

_MISSING = object()


class SyntheticKeys:
    """Gera e usa chaves descartáveis em diretório temporário isolado."""

    def __init__(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="coord06b-keys-")

    def generate(self, name: str) -> tuple[str, str, str]:
        path = os.path.join(self.directory, name)
        result = subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C",
             f"synthetic-{name}", "-f", path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            env=self._env())
        if result.returncode != 0:
            raise RuntimeError(f"ssh-keygen falhou: {result.stderr.decode(errors='replace')}")
        with open(path + ".pub", "r", encoding="utf-8") as handle:
            public = handle.read().strip()
        return path, public, cas.public_key_fingerprint(public)

    def sign(self, name: str, data: bytes,
             namespace: str = cas.SIGNATURE_NAMESPACE) -> bytes:
        fd, data_path = tempfile.mkstemp(dir=self.directory, prefix="data-", suffix=".bin")
        os.close(fd)
        try:
            with open(data_path, "wb") as handle:
                handle.write(data)
            result = subprocess.run(
                ["ssh-keygen", "-Y", "sign", "-f", os.path.join(self.directory, name),
                 "-n", namespace, data_path],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
                env=self._env(), cwd=self.directory)
            if result.returncode != 0:
                raise RuntimeError(f"ssh-keygen sign falhou: {result.stderr.decode(errors='replace')}")
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
    global _KEYS, HUMAN_FP, HUMAN_PUB, OTHER_FP, OTHER_PUB, ATTACKER_FP, ATTACKER_PUB
    _KEYS = SyntheticKeys()
    _, HUMAN_PUB, HUMAN_FP = _KEYS.generate("human")
    _, OTHER_PUB, OTHER_FP = _KEYS.generate("other")
    _, ATTACKER_PUB, ATTACKER_FP = _KEYS.generate("attacker")


def tearDownModule() -> None:
    global _KEYS
    if _KEYS is not None:
        _KEYS.cleanup()
        _KEYS = None


def make_key(*, approver_id: str = "maintainer", fingerprint: str | None = None,
             public_key: str | None = None, **overrides) -> cas.ApproverKey:
    values = {
        "approver_id": approver_id,
        "fingerprint": HUMAN_FP if fingerprint is None else fingerprint,
        "public_key": HUMAN_PUB if public_key is None else public_key,
        "valid_from": NOW - timedelta(hours=1),
        "valid_until": NOW + timedelta(hours=1),
        "revoked_at": None,
    }
    values.update(overrides)
    return cas.ApproverKey(**values)


def allowlist(*entries: cas.ApproverKey) -> cas.ApproverAllowlist:
    return cas.ApproverAllowlist(entries=tuple(entries) if entries else (make_key(),))


def make_envelope(allow: cas.ApproverAllowlist | None = None, **overrides) -> cas.SignedAuthorizationEnvelope:
    allow = allow or allowlist()
    values = {
        "schema": cas.SCHEMA_VERSION,
        "authorization_id": "AUTH-COORD06B-001",
        "approval_reference": "APP-COORD06B-001",
        "approver_id": "maintainer",
        "approver_key_fingerprint": HUMAN_FP,
        "trust_policy_sha256": allow.digest(),
        "mission_id": "MISS-COORD06B-001",
        "operation_id": "OP-COORD06B-001",
        "project_id": "better-talker",
        "repository_id": "guilhermemellojw/better-talker",
        "coordinator_id": "coordinator-a",
        "mission_identity": MISSION_IDENTITY,
        "payload": PAYLOAD,
        "payload_sha256": cas.payload_sha256(PAYLOAD),
        "target": TARGET,
        "model": MODEL,
        "scope": SCOPE,
        "reviewed_head_sha": HEAD,
        "operational_digests": DIGESTS,
        "max_transmissions": 1,
        "cost_ceiling_usd": Decimal("0.00"),
        "free_only": True,
        "issued_at": NOW - timedelta(minutes=5),
        "expires_at": NOW + timedelta(minutes=15),
        "nonce": "nonce-coord06b-001",
        "mode": "SHADOW",
    }
    values.update(overrides)
    return cas.SignedAuthorizationEnvelope(**values)


def make_expected(envelope: cas.SignedAuthorizationEnvelope,
                  **overrides) -> cas.ExpectedOperation:
    values = {
        "mission_id": envelope.mission_id,
        "operation_id": envelope.operation_id,
        "project_id": envelope.project_id,
        "repository_id": envelope.repository_id,
        "coordinator_id": envelope.coordinator_id,
        "mission_identity": envelope.mission_identity,
        "payload": envelope.payload,
        "target": envelope.target,
        "model": envelope.model,
        "scope": envelope.scope,
        "reviewed_head_sha": envelope.reviewed_head_sha,
        "operational_digests": envelope.operational_digests,
        "max_transmissions": envelope.max_transmissions,
        "cost_ceiling_usd": envelope.cost_ceiling_usd,
        "free_only": envelope.free_only,
    }
    values.update(overrides)
    return cas.ExpectedOperation(**values)


def sign(envelope: cas.SignedAuthorizationEnvelope, key: str = "human",
         namespace: str = cas.SIGNATURE_NAMESPACE) -> bytes:
    assert _KEYS is not None
    return _KEYS.sign(key, cas.canonical_envelope_bytes(envelope), namespace)


def make_revocation(digest: str, allow: cas.ApproverAllowlist | None = None,
                    **overrides) -> cas.SignedRevocation:
    allow = allow or allowlist()
    values = {
        "schema": cas.REVOCATION_SCHEMA_VERSION,
        "authorization_digest": digest,
        "approver_id": "maintainer",
        "approver_key_fingerprint": HUMAN_FP,
        "trust_policy_sha256": allow.digest(),
        "revoked_at": NOW - timedelta(minutes=1),
        "reason": "contramandamento",
        "nonce": "nonce-rev-001",
    }
    values.update(overrides)
    return cas.SignedRevocation(**values)


def sign_revocation(statement: cas.SignedRevocation, key: str = "human",
                    namespace: str = cas.REVOCATION_NAMESPACE) -> bytes:
    assert _KEYS is not None
    return _KEYS.sign(key, cas.canonical_revocation_bytes(statement), namespace)


def verify(envelope: cas.SignedAuthorizationEnvelope, signature=_MISSING,
           allow: cas.ApproverAllowlist | None = None,
           expected: cas.ExpectedOperation | None = None,
           revocations=(), now: datetime = NOW,
           expected_trust_policy_sha256: str | None = None) -> cas.VerificationResult:
    allow = allow or allowlist()
    if signature is _MISSING:
        signature = sign(envelope)
    if expected is None:
        expected = make_expected(envelope)
    return cas.verify_signed_envelope(
        envelope, signature, allow, now=now, expected=expected,
        revocations=revocations,
        expected_trust_policy_sha256=expected_trust_policy_sha256)


class SignedAuthorityTest(unittest.TestCase):
    def assertAccepted(self, result: cas.VerificationResult) -> None:
        self.assertTrue(result.accepted, result)
        self.assertEqual(result.code, "OK", result)
        self.assertIsNotNone(result.verified, result)

    def assertRejected(self, result: cas.VerificationResult, code: str) -> None:
        self.assertFalse(result.accepted, result)
        self.assertEqual(result.code, code, result)
        self.assertIsNone(result.verified, result)

    # 1. Caminho válido e limites explícitos.
    def test_valid_signed_envelope_is_accepted(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        result = verify(envelope, sign(envelope), allow)
        self.assertAccepted(result)
        self.assertEqual(result.authorization_digest, cas.authorization_digest(envelope))
        verified = result.verified
        assert verified is not None
        self.assertEqual(verified.mission_identity, MISSION_IDENTITY)
        self.assertEqual(verified.payload_sha256, cas.payload_sha256(PAYLOAD))
        self.assertEqual(verified.target_key, TARGET.key())
        self.assertEqual(verified.model, MODEL)
        self.assertEqual(verified.cost_ceiling_usd, "0")
        self.assertEqual(verified.mode, "SHADOW")
        self.assertTrue(verified.signature_verified)
        self.assertTrue(verified.expected_checked)
        self.assertTrue(verified.operation_bound)
        self.assertEqual(verified.enforcement, "not_implemented")
        self.assertFalse(verified.consumption_persisted)
        self.assertFalse(verified.replay_protected)
        self.assertFalse(result.revocation_checked)

    # 2. Canonicalização: determinística, sensível e sem ambiguidade de custo.
    def test_digest_is_canonical_and_sensitive(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        self.assertEqual(cas.authorization_digest(envelope), cas.authorization_digest(envelope))
        self.assertTrue(cas.canonical_envelope_bytes(envelope).startswith(cas.DOMAIN))
        variants = (
            dataclasses.replace(envelope, nonce="nonce-other"),
            dataclasses.replace(envelope, model=MODEL + "#ranking"),
            dataclasses.replace(envelope, max_transmissions=2),
            dataclasses.replace(envelope, cost_ceiling_usd=Decimal("1.00"), free_only=False),
        )
        for variant in variants:
            with self.subTest(variant=variant):
                self.assertNotEqual(cas.authorization_digest(envelope),
                                    cas.authorization_digest(variant))
        same_zero = dataclasses.replace(envelope, cost_ceiling_usd=Decimal("0"))
        self.assertEqual(cas.authorization_digest(envelope), cas.authorization_digest(same_zero))
        reordered = dict(reversed(list(envelope.operational_digests.items())))
        same_digests = dataclasses.replace(envelope, operational_digests=reordered)
        self.assertEqual(cas.authorization_digest(envelope), cas.authorization_digest(same_digests))

    # 3. Assinatura ausente, inválida ou de outro material.
    def test_missing_and_malformed_signature_rejected(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        self.assertRejected(verify(envelope, b"", allow), "SIGNATURE_INVALID")
        self.assertRejected(verify(envelope, b"not-a-signature", allow), "SIGNATURE_INVALID")
        other = dataclasses.replace(envelope, nonce="nonce-other")
        self.assertRejected(verify(envelope, sign(other), allow), "SIGNATURE_INVALID")

    # 4. Chave não autorizada não pode assinar em nome do aprovador.
    def test_signature_by_unauthorized_key_rejected(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        self.assertRejected(verify(envelope, sign(envelope, "attacker"), allow),
                            "SIGNATURE_INVALID")

    # 5. Namespace de assinatura separado.
    def test_signature_namespace_is_separated(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope, namespace=cas.REVOCATION_NAMESPACE)
        self.assertRejected(verify(envelope, signature, allow), "SIGNATURE_INVALID")

    # 6. Chave/identidade do aprovador fora da allowlist.
    def test_unknown_key_and_approver_mismatch_rejected(self):
        allow_other = allowlist(make_key(approver_id="other", fingerprint=OTHER_FP,
                                         public_key=OTHER_PUB))
        envelope = make_envelope(allow_other)
        self.assertRejected(verify(envelope, sign(envelope), allow_other),
                            "APPROVER_KEY_UNKNOWN")
        allow_wrong_id = allowlist(make_key(approver_id="other"))
        envelope = make_envelope(allow_wrong_id)
        self.assertRejected(verify(envelope, sign(envelope), allow_wrong_id),
                            "APPROVER_KEY_UNKNOWN")

    # 7. Fingerprint declarado não corresponde à chave pública.
    def test_allowlist_fingerprint_mismatch_rejected(self):
        allow = allowlist(make_key(fingerprint=OTHER_FP, public_key=HUMAN_PUB))
        envelope = make_envelope(allow, approver_key_fingerprint=OTHER_FP)
        self.assertRejected(verify(envelope, sign(envelope), allow),
                            "FINGERPRINT_MISMATCH")

    # 8. Adulteração de qualquer material coberto pela assinatura.
    def test_tampered_material_rejected(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        cases = (
            ("mission_identity", {"mission_identity": "1" * 64}, "SIGNATURE_INVALID"),
            ("target", {"target": OTHER_TARGET}, "SIGNATURE_INVALID"),
            ("model", {"model": "opencode/premium"}, "SIGNATURE_INVALID"),
            ("head", {"reviewed_head_sha": "b" * 40}, "SIGNATURE_INVALID"),
            ("digests", {"operational_digests": {"coord_authority.py": "1" * 64}},
             "SIGNATURE_INVALID"),
            ("max_transmissions", {"max_transmissions": 2}, "SIGNATURE_INVALID"),
            ("expires_at", {"expires_at": NOW + timedelta(minutes=30)}, "SIGNATURE_INVALID"),
            ("scope", {"scope": "OTHER-SCOPE"}, "SIGNATURE_INVALID"),
            ("cost", {"cost_ceiling_usd": Decimal("1.00")}, "INVALID_ENVELOPE"),
            ("payload", {"payload": {"message": "tampered"}}, "INVALID_ENVELOPE"),
        )
        for name, changes, code in cases:
            with self.subTest(field=name):
                tampered = dataclasses.replace(envelope, **changes)
                self.assertRejected(verify(tampered, signature, allow), code)
        tampered_payload = {"message": "tampered"}
        tampered = dataclasses.replace(
            envelope, payload=tampered_payload,
            payload_sha256=cas.payload_sha256(tampered_payload))
        self.assertRejected(verify(tampered, signature, allow), "SIGNATURE_INVALID")

    # 9. Hash do payload não pode ser declarado; é recalculado.
    def test_payload_hash_must_match_payload(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        bad = dataclasses.replace(envelope, payload_sha256="0" * 64)
        self.assertRejected(verify(bad, signature, allow), "INVALID_ENVELOPE")

    # 10. Janela de validade.
    def test_expiration_and_future_window_rejected(self):
        allow = allowlist()
        expired = make_envelope(allow, issued_at=NOW - timedelta(hours=2),
                                expires_at=NOW - timedelta(hours=1))
        self.assertRejected(verify(expired, sign(expired), allow),
                            "AUTHORIZATION_EXPIRED")
        future = make_envelope(allow, issued_at=NOW + timedelta(hours=1),
                               expires_at=NOW + timedelta(hours=2))
        self.assertRejected(verify(future, sign(future), allow),
                            "AUTHORIZATION_NOT_YET_VALID")
        inverted = make_envelope(allow, issued_at=NOW + timedelta(hours=1),
                                 expires_at=NOW)
        self.assertRejected(verify(inverted, b"x", allow), "INVALID_ENVELOPE")

    # 11. Validade/revogação da chave do aprovador.
    def test_inactive_or_revoked_key_rejected(self):
        future_allow = allowlist(make_key(
            valid_from=NOW + timedelta(hours=1), valid_until=NOW + timedelta(hours=2)))
        future = make_envelope(future_allow)
        self.assertRejected(verify(future, sign(future), future_allow),
                            "APPROVER_KEY_INACTIVE")
        past_allow = allowlist(make_key(
            valid_from=NOW - timedelta(hours=2), valid_until=NOW - timedelta(hours=1)))
        past = make_envelope(past_allow)
        self.assertRejected(verify(past, sign(past), past_allow),
                            "APPROVER_KEY_INACTIVE")
        revoked_allow = allowlist(make_key(revoked_at=NOW - timedelta(minutes=1)))
        revoked = make_envelope(revoked_allow)
        self.assertRejected(verify(revoked, sign(revoked), revoked_allow),
                            "APPROVER_KEY_INACTIVE")

    # 12. Trust policy pinada; rotação/revogação é fail-closed.
    def test_trust_policy_mismatch_rejected(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        rotated = allowlist(make_key(), make_key(
            approver_id="other", fingerprint=OTHER_FP, public_key=OTHER_PUB))
        self.assertRejected(verify(envelope, signature, rotated), "TRUST_POLICY_MISMATCH")
        self.assertRejected(verify(envelope, signature, allow,
                                   expected_trust_policy_sha256="0" * 64),
                            "TRUST_POLICY_MISMATCH")
        tampered = dataclasses.replace(envelope, trust_policy_sha256="0" * 64)
        self.assertRejected(verify(tampered, signature, allow), "TRUST_POLICY_MISMATCH")

    # 13. Vinculação integral à operação esperada.
    def test_expected_operation_binds_all_material(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        expected = make_expected(envelope)
        cases = (
            ("mission_id", {"mission_id": "MISS-OTHER"}),
            ("operation_id", {"operation_id": "OP-OTHER"}),
            ("project_id", {"project_id": "other-project"}),
            ("repository_id", {"repository_id": "other/repo"}),
            ("coordinator_id", {"coordinator_id": "coordinator-b"}),
            ("mission_identity", {"mission_identity": "1" * 64}),
            ("payload", {"payload": {"message": "other"}}),
            ("target", {"target": OTHER_TARGET}),
            ("model", {"model": MODEL + "#variant"}),
            ("scope", {"scope": "OTHER-SCOPE"}),
            ("reviewed_head_sha", {"reviewed_head_sha": "b" * 40}),
            ("operational_digests", {"operational_digests": {"x": "1" * 64}}),
            ("max_transmissions", {"max_transmissions": 2}),
            ("cost_ceiling_usd", {"cost_ceiling_usd": Decimal("1.00"),
                                  "free_only": False}),
            ("free_only", {"free_only": False}),
        )
        for field, changes in cases:
            with self.subTest(field=field):
                tampered_expected = dataclasses.replace(expected, **changes)
                result = verify(envelope, signature, allow, expected=tampered_expected)
                self.assertRejected(result, "OPERATION_MISMATCH")
                self.assertIn(field, result.mismatches)
        reordered = dict(reversed(list(PAYLOAD.items())))
        result = verify(envelope, signature, allow,
                        expected=make_expected(envelope, payload=reordered))
        self.assertAccepted(result)

    # 14. Assinatura válida isolada não é autorização: expected é obrigatório.
    def test_expected_is_mandatory(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        with self.assertRaises(TypeError):
            cas.verify_signed_envelope(envelope, sign(envelope), allow, now=NOW)

    # 15. Revogação assinada por digest.
    def test_authorization_revocation_is_effective(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        digest = cas.authorization_digest(envelope)
        statement = make_revocation(digest, allow)
        revocation_signature = sign_revocation(statement)
        result = verify(envelope, signature, allow,
                        revocations=((statement, revocation_signature),))
        self.assertRejected(result, "AUTHORIZATION_REVOKED")
        self.assertTrue(result.revocation_checked)
        direct = cas.verify_signed_revocation(statement, revocation_signature, allow, now=NOW)
        self.assertTrue(direct.verified)
        self.assertTrue(direct.effective)
        self.assertEqual(direct.code, "OK")

    # 16. Revogação de outro digest não afeta.
    def test_revocation_for_other_digest_is_not_effective(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        statement = make_revocation("0" * 64, allow, nonce="nonce-rev-other")
        result = verify(envelope, sign(envelope), allow,
                        revocations=((statement, sign_revocation(statement)),))
        self.assertAccepted(result)
        self.assertTrue(result.revocation_checked)
        assert result.verified is not None
        self.assertFalse(result.verified.revoked)

    # 17. Revogação inválida não bloqueia nem é aceita.
    def test_invalid_revocation_is_not_effective(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        statement = make_revocation(cas.authorization_digest(envelope), allow)
        bad_signature = sign_revocation(statement, "attacker")
        direct = cas.verify_signed_revocation(statement, bad_signature, allow, now=NOW)
        self.assertFalse(direct.verified)
        self.assertEqual(direct.code, "REVOCATION_SIGNATURE_INVALID")
        result = verify(envelope, sign(envelope), allow,
                        revocations=((statement, bad_signature),))
        self.assertAccepted(result)
        self.assertTrue(result.revocation_checked)
        tampered = dataclasses.replace(statement, reason="adulterado")
        direct = cas.verify_signed_revocation(
            tampered, sign_revocation(statement), allow, now=NOW)
        self.assertFalse(direct.verified)
        self.assertEqual(direct.code, "REVOCATION_SIGNATURE_INVALID")

    # 18. Revogação com chave desconhecida ou ainda não efetiva.
    def test_revocation_unknown_key_and_future(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        digest = cas.authorization_digest(envelope)
        unknown = make_revocation(
            digest, allow, approver_id="unknown", approver_key_fingerprint=OTHER_FP,
            nonce="nonce-rev-unknown")
        direct = cas.verify_signed_revocation(
            unknown, sign_revocation(unknown, "other"), allow, now=NOW)
        self.assertFalse(direct.verified)
        self.assertEqual(direct.code, "APPROVER_KEY_UNKNOWN")
        future = make_revocation(
            digest, allow, revoked_at=NOW + timedelta(minutes=5), nonce="nonce-rev-future")
        future_signature = sign_revocation(future)
        direct = cas.verify_signed_revocation(future, future_signature, allow, now=NOW)
        self.assertTrue(direct.verified)
        self.assertFalse(direct.effective)
        self.assertEqual(direct.code, "REVOCATION_NOT_YET")
        result = verify(envelope, sign(envelope), allow,
                        revocations=((future, future_signature),))
        self.assertAccepted(result)

    # 19. Adulteração de revogação e separação de namespace.
    def test_revocation_tampering_and_namespace(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        digest = cas.authorization_digest(envelope)
        statement = make_revocation(digest, allow)
        signature = sign_revocation(statement)
        tampered = dataclasses.replace(statement, authorization_digest="1" * 64)
        direct = cas.verify_signed_revocation(tampered, signature, allow, now=NOW)
        self.assertFalse(direct.verified)
        self.assertEqual(direct.code, "REVOCATION_SIGNATURE_INVALID")
        wrong_namespace = sign_revocation(statement, namespace=cas.SIGNATURE_NAMESPACE)
        direct = cas.verify_signed_revocation(statement, wrong_namespace, allow, now=NOW)
        self.assertFalse(direct.verified)
        self.assertEqual(direct.code, "REVOCATION_SIGNATURE_INVALID")

    # 20. SHADOW é o único modo aceito.
    def test_shadow_is_the_only_mode(self):
        allow = allowlist()
        for mode in ("ASSISTED", "DELEGATED", "REAL"):
            with self.subTest(mode=mode):
                envelope = make_envelope(allow, mode=mode)
                self.assertRejected(verify(envelope, b"x", allow), "INVALID_ENVELOPE")

    # 21. Limites e custo fazem parte do material assinado.
    def test_limits_and_cost_are_bound(self):
        allow = allowlist()
        two = make_envelope(allow, max_transmissions=2)
        self.assertAccepted(verify(two, sign(two), allow))
        zero = make_envelope(allow, max_transmissions=0)
        self.assertRejected(verify(zero, b"x", allow), "INVALID_ENVELOPE")
        bad_cost = make_envelope(allow, cost_ceiling_usd=Decimal("1.00"))
        self.assertRejected(verify(bad_cost, b"x", allow), "INVALID_ENVELOPE")
        paid = make_envelope(allow, cost_ceiling_usd=Decimal("5.50"), free_only=False)
        result = verify(paid, sign(paid), allow)
        self.assertAccepted(result)
        assert result.verified is not None
        self.assertEqual(result.verified.cost_ceiling_usd, "5.5")
        negative = make_envelope(allow, cost_ceiling_usd=Decimal("-1"),
                                 free_only=False)
        self.assertRejected(verify(negative, b"x", allow), "INVALID_ENVELOPE")

    # 22. Identidade de missão é opaca e vinculada externamente, sem duplicar #64.
    def test_mission_identity_is_opaque_and_externally_bound(self):
        allow = allowlist()
        first = make_envelope(allow, mission_identity="a" * 64)
        second = make_envelope(allow, mission_identity="b" * 64)
        first_signature = sign(first)
        self.assertAccepted(verify(first, first_signature, allow,
                                   expected=make_expected(first)))
        self.assertRejected(verify(first, first_signature, allow,
                                   expected=make_expected(second)),
                            "OPERATION_MISMATCH")
        self.assertAccepted(verify(second, sign(second), allow,
                                   expected=make_expected(second)))
        self.assertFalse(hasattr(cas, "canonical_mission_identity"))

    # 23. Compatibilidade estrutural com #72, sem avaliar autoridade.
    def test_structural_compatibility_with_72(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        mission, authorization = envelope.to_authority_objects()
        mission.validate()
        authorization.validate()
        self.assertEqual(mission.mission_identity, envelope.mission_identity)
        self.assertEqual(mission.payload_sha256, envelope.payload_sha256)
        self.assertEqual(mission.recipient.key(), envelope.target.key())
        self.assertEqual(authorization.approval_reference, envelope.approval_reference)
        self.assertEqual(authorization.max_transmissions, envelope.max_transmissions)
        self.assertEqual(ca.canonical_authorization_digest(authorization),
                         ca.canonical_authorization_digest(authorization))

    # 24. Somente verificação: não assina, não persiste e não usa rede/ledger.
    def test_verifier_is_offline_non_signing_and_non_persistent(self):
        source = Path(cas.__file__).read_text(encoding="utf-8")
        for forbidden in ("sqlite3", "requests", "urllib", "socket", "coord_ledger"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)
        self.assertNotIn('"-Y", "sign"', source)
        self.assertNotIn("'-Y', 'sign'", source)
        tree = ast.parse(source)
        imports = {
            alias.name for node in ast.walk(tree)
            if isinstance(node, (ast.Import, ast.ImportFrom))
            for alias in node.names
        }
        self.assertNotIn("socket", imports)
        self.assertNotIn("sqlite3", imports)
        self.assertNotIn("coord_ledger", imports)

    # 25. Limitação explícita: replay só é protegido pelo ledger #70.
    def test_replay_is_not_protected_offline(self):
        allow = allowlist()
        envelope = make_envelope(allow)
        signature = sign(envelope)
        first = verify(envelope, signature, allow)
        second = verify(envelope, signature, allow)
        self.assertAccepted(first)
        self.assertAccepted(second)
        assert first.verified is not None and second.verified is not None
        self.assertFalse(first.verified.replay_protected)
        self.assertFalse(first.verified.consumption_persisted)
        self.assertFalse(second.verified.replay_protected)


if __name__ == "__main__":
    unittest.main(verbosity=2)
