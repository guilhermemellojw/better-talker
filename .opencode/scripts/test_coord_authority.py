#!/usr/bin/env python3
"""COORD-06A — testes host-only do validador de autorização/revogação."""
from __future__ import annotations

import ast
import os
import sys
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coord_authority as ca

HEAD = "a" * 40
PAYLOAD = "b" * 64
PIN_DIGEST = "c" * 64
APPROVAL = "APP-DECISIONS-D006"
NOW = datetime(2026, 11, 26, 12, 0, tzinfo=timezone.utc)


def trust(mode=ca.Mode.ASSISTED, *, heads=(HEAD,), models=("opencode/hy4-flash-free",),
          scopes=("COORD-06A-FOUNDATION",), agents=("A",), limit=2, pins=None):
    return ca.TrustContract(
        project_id="better-talker",
        repository_id="guilhermemellojw/better-talker",
        mode=mode,
        authorized_heads=frozenset(heads),
        allowed_models=frozenset(models),
        allowed_scopes=frozenset(scopes),
        allowed_agents=frozenset(agents),
        max_transmissions=limit,
        authority_pins=pins if pins is not None else {},
        allowed_approvers=frozenset({"MAINTAINER"}),
        max_evidence_age_seconds=86_400,
    )


def recipient(agent="A"):
    return ca.Recipient(agent, "ses-coord06", "/home/guilherme/bt-coord06", "inst-1")


def mission(**overrides):
    base = ca.Mission(
        mission_id="MISS-COORD06-P1-001",
        operation_id="OP-COORD06-P1-001",
        project_id="better-talker",
        repository_id="guilhermemellojw/better-talker",
        mission_identity="IDENT-COORD06-P1-001",
        payload_sha256=PAYLOAD,
        recipient=recipient(),
        head_sha=HEAD,
        scope="COORD-06A-FOUNDATION",
        model="opencode/hy4-flash-free",
        transmissions=0,
    )
    return replace(base, **overrides)


def authorization(**overrides):
    base = ca.Authorization(
        reference="AUTH-COORD06-001",
        mission_id="MISS-COORD06-P1-001",
        operation_id="OP-COORD06-P1-001",
        project_id="better-talker",
        repository_id="guilhermemellojw/better-talker",
        mission_identity="IDENT-COORD06-P1-001",
        payload_sha256=PAYLOAD,
        recipient=recipient(),
        head_sha=HEAD,
        scope="COORD-06A-FOUNDATION",
        model="opencode/hy4-flash-free",
        max_transmissions=2,
        issued_at=NOW - timedelta(minutes=5),
        expires_at=NOW + timedelta(minutes=30),
        approval_reference=APPROVAL,
    )
    return replace(base, **overrides)


def evidence(**overrides):
    base = ca.ExternalAuthorityEvidence(
        reference=APPROVAL,
        digest_sha256=None,
        source=ca.AuthoritySource.EXTERNAL_ARTIFACT,
        approver_id="MAINTAINER",
        observed_at=NOW - timedelta(minutes=2),
        authorization_digest=None,
    )
    return replace(base, **overrides)


def evaluation(**overrides):
    base = ca.Evaluation(
        mission=mission(),
        authorization=authorization(),
        revocations=(),
        trust=trust(),
        evidence=evidence(),
        evaluated_at=NOW,
        operation_state=ca.OperationState.NOT_DISPATCHED,
    )
    result = replace(base, **overrides)
    # P2: por padrão, pin e evidência cobrem o material canônico da autorização.
    pin = (ca.canonical_authorization_digest(result.authorization)
           if result.authorization is not None else PIN_DIGEST)
    if not result.trust.authority_pins:
        result = replace(result, trust=replace(result.trust, authority_pins={APPROVAL: pin}))
    if result.evidence is not None:
        patches = {}
        if result.evidence.authorization_digest is None:
            patches["authorization_digest"] = pin
        if result.evidence.digest_sha256 is None:
            patches["digest_sha256"] = pin
        if patches:
            result = replace(result, evidence=replace(result.evidence, **patches))
    return result


def codes(decision):
    return {blocker.code for blocker in decision.blockers}


class CoordinationAuthorityTest(unittest.TestCase):
    def test_estrutura_valida_sofre_apenas_bloqueio_de_modo(self):
        d = ca.evaluate(evaluation())
        self.assertFalse(d.eligible)
        self.assertEqual(codes(d), {"MODE_DISABLED"})
        self.assertEqual(d.state, ca.OperationState.NOT_DISPATCHED)
        self.assertIn("NO_EXTERNAL_EXECUTION", d.notes)

    def test_autorizacao_ausente(self):
        d = ca.evaluate(evaluation(authorization=None))
        self.assertIn("AUTHORIZATION_ABSENT", codes(d))
        self.assertIn("AUTHORIZATION", d.required_evidence)

    def test_vencida_e_ainda_nao_valida(self):
        d = ca.evaluate(evaluation(authorization=authorization(
            issued_at=NOW - timedelta(hours=2), expires_at=NOW - timedelta(hours=1))))
        self.assertIn("AUTHORIZATION_EXPIRED", codes(d))
        d = ca.evaluate(evaluation(authorization=authorization(
            issued_at=NOW + timedelta(minutes=10), expires_at=NOW + timedelta(hours=1))))
        self.assertIn("AUTHORIZATION_NOT_YET_VALID", codes(d))

    def test_revogada_e_evidencia_de_revogacao_insuficiente(self):
        auth = authorization()
        pin = ca.canonical_authorization_digest(auth)
        rev = ca.Revocation(pin, APPROVAL, pin,
                            NOW - timedelta(minutes=1), "contramandamento")
        d = ca.evaluate(evaluation(revocations=(rev,)))
        self.assertIn("AUTHORIZATION_REVOKED", codes(d))
        self.assertIn("NO_RETROACTIVE_CANCELLATION", d.notes)
        self.assertEqual(d.state, ca.OperationState.NOT_DISPATCHED)
        bad = replace(rev, digest_sha256="d" * 64)
        d = ca.evaluate(evaluation(revocations=(bad,)))
        self.assertIn("REVOCATION_EVIDENCE_UNPROVEN", codes(d))

    def test_adulteracoes_bloqueadas(self):
        cases = (
            (mission(payload_sha256="e" * 64),),
            (mission(head_sha="f" * 40),),
            (mission(recipient=recipient(agent="B")),),
            (mission(scope="OUTRO-ESCOPO"),),
            (mission(model="premium/model"),),
            (mission(mission_identity="IDENT-OUTRA"),),
            (mission(mission_id="MISS-OUTRA"),),
            (mission(operation_id="OP-OUTRA"),),
            (mission(project_id="outro-projeto"),),
            (mission(repository_id="outro/repo"),),
        )
        for (changed,) in cases:
            with self.subTest(changed=changed):
                d = ca.evaluate(evaluation(mission=changed))
                self.assertFalse(d.eligible)
                self.assertTrue(codes(d) & {
                    "PAYLOAD_DIVERGENT", "HEAD_DIVERGENT", "HEAD_NOT_AUTHORIZED",
                    "RECIPIENT_DIVERGENT", "RECIPIENT_NOT_ALLOWED", "SCOPE_DIVERGENT",
                    "SCOPE_NOT_AUTHORIZED", "MODEL_DIVERGENT", "MODEL_NOT_AUTHORIZED",
                    "MISSION_IDENTITY_DIVERGENT", "MISSION_ID_DIVERGENT",
                    "OPERATION_ID_DIVERGENT", "PROJECT_DIVERGENT", "PROJECT_UNTRUSTED",
                    "REPOSITORY_DIVERGENT", "REPOSITORY_UNTRUSTED",
                })

    def test_consumida(self):
        d = ca.evaluate(evaluation(authorization=authorization(consumed=True)))
        self.assertIn("AUTHORIZATION_CONSUMED", codes(d))

    def test_limite_de_transmissoes(self):
        d = ca.evaluate(evaluation(mission=mission(transmissions=2)))
        self.assertIn("TRANSMISSION_LIMIT_EXCEEDED", codes(d))

    def test_estados_operacionais(self):
        expected = {
            ca.OperationState.UNKNOWN: "STATE_UNKNOWN_BLOCKED",
            ca.OperationState.TRANSMITTED: "STATE_ALREADY_TRANSMITTED",
            ca.OperationState.RUNNING: "STATE_RUNNING_BLOCKED",
            ca.OperationState.TERMINAL: "STATE_TERMINAL_BLOCKED",
        }
        for state, code in expected.items():
            with self.subTest(state=state):
                d = ca.evaluate(evaluation(operation_state=state))
                self.assertIn(code, codes(d))
                self.assertEqual(d.state, state)

    def test_shadow_e_modos_desativados(self):
        d = ca.evaluate(evaluation(trust=trust(ca.Mode.SHADOW)))
        self.assertEqual(codes(d), {"MODE_SHADOW_BLOCKED"})
        self.assertFalse(d.eligible)
        for mode in (ca.Mode.ASSISTED, ca.Mode.DELEGATED):
            with self.subTest(mode=mode):
                self.assertEqual(codes(ca.evaluate(evaluation(trust=trust(mode)))), {"MODE_DISABLED"})

    def test_autoridade_nao_comprovada(self):
        cases = (
            evaluation(evidence=None),
            evaluation(evidence=evidence(source=ca.AuthoritySource.SELF_REPORT)),
            evaluation(evidence=evidence(source=ca.AuthoritySource.BOOLEAN)),
            evaluation(evidence=evidence(source=ca.AuthoritySource.NONE)),
            evaluation(evidence=evidence(digest_sha256="9" * 64)),
            evaluation(evidence=evidence(reference="APP-OUTRA")),
            evaluation(trust=trust(pins={"APP-OUTRA": PIN_DIGEST})),
        )
        for case in cases:
            with self.subTest(case=case):
                result = codes(ca.evaluate(case))
                self.assertTrue(result & {
                    "AUTHORITY_UNPROVEN", "AUTHORITY_DIVERGENT",
                    "AUTHORITY_REFERENCE_DIVERGENT", "AUTHORITY_MATERIAL_DIVERGENT",
                })

    def test_bool_autodeclarado_nunca_prova_aprovacao(self):
        d = ca.evaluate(evaluation(authorization=authorization(self_declared_approved=True),
                                   evidence=None))
        self.assertIn("AUTHORITY_UNPROVEN", codes(d))
        self.assertIn("SELF_DECLARED_APPROVAL_IGNORED", d.notes)

    def test_evidencia_observada_no_futuro(self):
        d = ca.evaluate(evaluation(evidence=evidence(observed_at=NOW + timedelta(minutes=1))))
        self.assertIn("AUTHORITY_NOT_YET_OBSERVED", codes(d))

    def test_determinismo_e_ordenacao(self):
        ev = evaluation(authorization=authorization(consumed=True),
                        mission=mission(payload_sha256="1" * 64),
                        operation_state=ca.OperationState.UNKNOWN)
        a, b = ca.evaluate(ev), ca.evaluate(ev)
        self.assertEqual(a, b)
        self.assertEqual(a.blockers, tuple(sorted(a.blockers)))
        self.assertEqual(len(a.blockers), len(set(a.blockers)))

    def test_entrada_invalida_sem_efeito(self):
        bad = evaluation(mission=mission(head_sha="curto"), authorization=None)
        d = ca.evaluate(bad)
        self.assertIn("INVALID_INPUT", codes(d))
        self.assertFalse(d.eligible)

    def test_resultado_nao_executa_operacao_externa(self):
        d = ca.evaluate(evaluation())
        values = [d.eligible, d.blockers, d.required_evidence, d.state,
                  d.authorization_reference, d.notes]
        stack = list(values)
        while stack:
            item = stack.pop()
            self.assertFalse(callable(item), "resultado contém callable")
            if isinstance(item, (str, bool, type(None), ca.OperationState)):
                continue
            if isinstance(item, (tuple, list, frozenset, set)):
                stack.extend(item)
                continue
            if isinstance(item, ca.Blocker):
                stack.extend((item.code, item.detail))
                continue
            self.fail(f"campo não primitivo no resultado: {type(item)}")

    def test_modulo_sem_imports_de_rede_sessao_ou_processo(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "coord_authority.py")
        with open(path, "r", encoding="utf-8") as handle:
            tree = ast.parse(handle.read(), filename=path)
        banned_modules = {"os", "sys", "socket", "subprocess", "urllib", "http",
                          "requests", "sqlite3", "shutil", "pathlib", "tempfile"}
        imported = set()
        calls = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module.split(".")[0])
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                calls.add(node.func.id)
        self.assertFalse(imported & banned_modules, imported)
        self.assertFalse(calls & {"eval", "exec", "open", "input", "system",
                                  "Popen", "urlopen", "request"}, calls)


    # ---- P2: achados do parecer QA-COORD-72 (7193826549) -------------------

    def test_p2_evidencia_inalterada_com_material_alterado(self):
        original = authorization()
        pin = ca.canonical_authorization_digest(original)
        ev = evidence(authorization_digest=pin, digest_sha256=pin)
        tr = trust(pins={APPROVAL: pin})
        cases = (
            authorization(max_transmissions=5),
            authorization(recipient=ca.Recipient(
                "A", "ses-other", "/home/guilherme/bt-other", "inst-1")),
            authorization(scope="COORD-06A-OTHER"),
            authorization(model="opencode/premium"),
        )
        for tampered in cases:
            with self.subTest(tampered=tampered):
                d = ca.evaluate(evaluation(
                    authorization=tampered, evidence=ev, trust=tr,
                ))
                self.assertIn("AUTHORITY_MATERIAL_DIVERGENT", codes(d))

    def test_p2_revogacao_indexada_por_digest_imutavel(self):
        auth = authorization(reference="AUTH-TROCADA")
        pin = ca.canonical_authorization_digest(auth)
        rev = ca.Revocation(pin, APPROVAL, pin, NOW - timedelta(minutes=1), "contramandamento")
        d = ca.evaluate(evaluation(authorization=auth, revocations=(rev,)))
        self.assertIn("AUTHORIZATION_REVOKED", codes(d))

    def test_p2_aprovador_e_frescor(self):
        d = ca.evaluate(evaluation(evidence=evidence(approver_id="OUTRO")))
        self.assertIn("APPROVER_NOT_ALLOWED", codes(d))
        d = ca.evaluate(evaluation(evidence=evidence(observed_at=NOW - timedelta(days=2))))
        self.assertIn("AUTHORITY_STALE", codes(d))

    def test_p2_consumo_estado_e_transmissoes_sao_consultivos(self):
        d = ca.evaluate(evaluation(authorization=authorization(consumed=True)))
        for note in ("CONSUMPTION_ADVISORY", "STATE_ADVISORY", "TRANSMISSION_ADVISORY"):
            self.assertIn(note, d.notes)
        d = ca.evaluate(evaluation(mission=mission(transmissions=1)))
        self.assertIn("TRANSMISSION_STATE_INCONSISTENT", codes(d))

    def test_p2_variante_de_modelo_compativel_com_71(self):
        variant = "opencode/hy4-flash-free#ranking"
        d = ca.evaluate(evaluation(
            mission=mission(model=variant),
            authorization=authorization(model=variant),
            trust=trust(models=(variant,)),
        ))
        self.assertEqual({"MODE_DISABLED"}, codes(d))

    def test_p2_sessao_e_worktree_validados(self):
        bad_session = ca.Recipient("A", "sessao-livre", "/home/guilherme/bt", "inst-1")
        bad_worktree = ca.Recipient("A", "ses-ok", "relativo/bt", "inst-1")
        for recipient_value in (bad_session, bad_worktree):
            with self.subTest(recipient=recipient_value):
                d = ca.evaluate(evaluation(mission=mission(recipient=recipient_value)))
                self.assertIn("INVALID_INPUT", codes(d))


if __name__ == "__main__":
    unittest.main(verbosity=2)
