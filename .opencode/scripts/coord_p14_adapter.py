#!/usr/bin/env python3
"""COORD-04/P14A — adaptadores OFFLINE entre piloto, ledger (#70) e política (#71).

Nenhuma autoridade nova é criada. O ledger é a única fonte operacional de
estado/reserva; o motor de modelos é o único decisor de seleção. O gate de
autorização (#72) permanece fechado e é apenas descrito por uma interface
contratual para P14B.
"""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Iterator, Mapping, Optional

import coord_communication as cc
import coord_ledger as ledger
import coord_model_policy as model_policy
import coord_pilot as pilot

__all__ = [
    "SyntheticAuthorization",
    "synthetic_fixture_authorization",
    "LedgerOperationStore",
    "select_with_policy",
    "model_evidence",
    "authorization_interface_p14b",
    "synthetic_transport_evidence",
]


@dataclass(frozen=True)
class SyntheticAuthorization:
    """Autorização de fixture EXPLÍCITA — nunca uma string do chamador.

    Não é autoridade humana, não habilita transporte real e não substitui
    #72/#77. Apenas permite o caminho sintético de testes/CLI.
    """

    approval_reference: str
    authorization_digest: str
    consumer_id: str
    environment: str = "FIXTURE"

    def validate(self) -> None:
        if self.environment != "FIXTURE":
            raise cc.AuthorizationError("autorização sintética não pode ser APPROVED/real")
        if not self.approval_reference.strip() or not self.consumer_id.strip():
            raise cc.AuthorizationError("autorização sintética incompleta")
        if not cc.SHA256_PATTERN.fullmatch(self.authorization_digest):
            raise cc.AuthorizationError("digest da autorização sintética inválido")


def synthetic_fixture_authorization(
    authorization_reference: str, *, consumer_id: str = "test-fixture",
) -> SyntheticAuthorization:
    """Autorização sintética determinística para testes — nunca é humana."""
    digest = hashlib.sha256(cc.canonical_json({
        "environment": "FIXTURE", "reference": authorization_reference,
        "consumer_id": consumer_id,
    }).encode("utf-8")).hexdigest()
    return SyntheticAuthorization(
        approval_reference=authorization_reference, authorization_digest=digest,
        consumer_id=consumer_id, environment="FIXTURE",
    )


class LedgerOperationStore:
    """Interface de store do piloto integralmente apoiada no ledger persistente.

    Substitui o `PilotOperationStore` JSON como autoridade operacional no
    caminho integrado. Nenhum lock de arquivo é usado como exclusividade:
    a reserva atômica é `Ledger.reserve_dispatch`.
    """

    def __init__(
        self,
        db: ledger.Ledger,
        *,
        mission_id: str,
        operation_id: str,
        authorization_reference: str,
        payload: Mapping[str, Any],
        target: cc.TargetIdentity,
        actor: str = "p14a",
        clock: Optional[Any] = None,
        authorization_canonical: Optional[str] = None,
        mission_snapshot: Optional[Mapping[str, Any]] = None,
        synthetic_authorization: Optional[SyntheticAuthorization] = None,
        cache_dir: Optional[str] = None,
    ) -> None:
        self.db = db
        self.mission_id = mission_id
        self.operation_id = operation_id
        self.authorization_reference = authorization_reference
        self.payload = dict(payload)
        self.payload_hash = ledger.payload_sha256(self.payload)
        self.target = target
        self.actor = actor
        self.clock = clock or cc.utc_now
        self.synthetic_authorization = synthetic_authorization
        self.cache_dir = os.path.abspath(cache_dir) if cache_dir else None
        self._evidence: dict[str, Any] = {}
        self._cache_invalid = False
        cached = self._read_cache()
        self.authorization_canonical = (
            authorization_canonical or cached.get("authorization_canonical")
            or authorization_reference
        )
        self.mission_snapshot = dict(
            mission_snapshot or cached.get("mission_snapshot") or {}
        )
        self._authoritative_snapshot = dict(mission_snapshot) if mission_snapshot else None
        cached_snapshot = cached.get("mission_snapshot")
        self._cache_divergent = bool(
            self._authoritative_snapshot and cached_snapshot
            and cached_snapshot != self._authoritative_snapshot
        )
        if cached.get("evidence"):
            self._evidence.update(cached["evidence"])
        # O cache NÃO é autoridade de environment: isso vem da autorização sintética explícita.
        self._evidence.pop("environment", None)

    def _target_fields(self) -> dict[str, str]:
        return {
            "agent": self.target.agent_id,
            "session_id": self.target.session_id,
            "instance_id": self.target.instance_id,
            "worktree": self.target.location,
        }

    def _record(self, row: ledger.MissionRecord) -> pilot.PilotOperationRecord:
        transitions = self.db.history(self.mission_id)
        history = [
            {"utc": item.at, "state": item.to_state, "evidence": item.evidence}
            for item in transitions
        ]
        # Recuperação após reinício: o evidence viaja na transição do ledger.
        for item in reversed(transitions):
            text = str(item.evidence)
            if text.startswith("{"):
                try:
                    self._evidence.update(json.loads(text))
                except ValueError:
                    pass
                break
        evidence = dict(self._evidence)
        if self.synthetic_authorization is not None:
            evidence["environment"] = self.synthetic_authorization.environment
        snapshot = (self._authoritative_snapshot
                    or ({} if self._cache_divergent else dict(self.mission_snapshot)))
        return pilot.PilotOperationRecord(
            mission_id=row.mission_id,
            operation_id=row.operation_id,
            authorization_id=self.authorization_reference,
            authorization_canonical=self.authorization_canonical,
            target_key=self.target.key(),
            prompt_sha256=row.payload_sha256,
            state=row.state,
            created_at=row.created_at,
            updated_at=row.updated_at,
            evidence=evidence,
            history=history,
            mission_snapshot=snapshot,
        )

    def _cache_path(self) -> Optional[str]:
        if not self.cache_dir:
            return None
        return os.path.join(self.cache_dir, "evidence-cache.json")

    def _read_cache(self) -> dict[str, Any]:
        """Cache NÃO autoritativo: apenas reconstrói contexto após reinício."""
        path = self._cache_path()
        if not path or not os.path.isfile(path):
            return {}
        try:
            with open(path, "rb") as handle:
                value = json.loads(handle.read().decode("utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError, UnicodeError):
            self._cache_invalid = True
            return {}

    def _write_cache(self) -> None:
        path = self._cache_path()
        if not path:
            return
        os.makedirs(self.cache_dir, mode=0o700, exist_ok=True)
        payload = {
            "authorization_canonical": self.authorization_canonical,
            "mission_snapshot": dict(self.mission_snapshot),
            "evidence": dict(self._evidence),
        }
        descriptor, temporary = tempfile.mkstemp(
            prefix=".evidence-cache-", dir=self.cache_dir)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, sort_keys=True, ensure_ascii=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def create(self, record: pilot.PilotOperationRecord) -> pilot.PilotOperationRecord:
        # O ledger persistiu o material operacional; estes campos descritivos
        # acompanham a operação e permitem revalidação idempotente do executor.
        if self.synthetic_authorization is None:
            raise cc.AuthorizationError("autorização sintética explícita ausente")
        self.synthetic_authorization.validate()
        if self.synthetic_authorization.approval_reference != self.authorization_reference:
            raise cc.AuthorizationError("autorização sintética diverge da referência da missão")
        self._evidence["authorization_canonical"] = record.authorization_canonical
        self._evidence["mission_snapshot"] = dict(record.mission_snapshot)
        self._evidence["synthetic_authorization_digest"] = \
            self.synthetic_authorization.authorization_digest
        self._write_cache()
        fields = self._target_fields()
        self.db.register_proposed(
            mission_id=self.mission_id,
            operation_id=self.operation_id,
            coordinator_id=record.authorization_id or self.actor,
            agent=fields["agent"],
            session_id=fields["session_id"],
            instance_id=fields["instance_id"],
            worktree=fields["worktree"],
            authorization_reference=self.authorization_reference,
            payload=self.payload,
        )
        current = self.db.get(self.mission_id)
        if current is not None and current.state != ledger.PROPOSED:
            raise cc.AuthorizationError("operação já iniciada no ledger; sem reenvio")
        if current is not None:
            try:
                self.db.authorize(
                    self.mission_id,
                    authorization_reference=self.authorization_reference,
                    payload_sha256_value=self.payload_hash,
                    actor=self.actor,
                )
            except ledger.InvalidTransition as exc:
                # Corrida entre processos: apenas um vence a transição.
                raise cc.AuthorizationError(
                    "operação já iniciada no ledger; sem reenvio") from exc
        return self.load() or record

    def load(self) -> Optional[pilot.PilotOperationRecord]:
        row = self.db.get(self.mission_id)
        return None if row is None else self._record(row)

    def anchored_snapshot(self) -> dict[str, Any]:
        """Snapshot canônico: ledger é âncora de payload/identidade; cache é contexto."""
        row = self.db.get(self.mission_id)
        if row is None:
            raise cc.ContractError("missão ausente no ledger")
        if self._cache_invalid:
            raise cc.ContractError("cache corrompido; reconciliação bloqueada")
        if self._authoritative_snapshot is None:
            raise cc.ContractError("âncora de reconciliação ausente; cache não é autoridade")
        snapshot = dict(self._authoritative_snapshot)
        if snapshot.get("payload_sha256") != row.payload_sha256:
            raise cc.ContractError("payload do snapshot diverge do ledger")
        # A ordem canônica é (project, repository, agent, session, instance, location).
        expected = [
            row.project_id, row.repository_id, row.agent,
            row.session_id, row.instance_id, row.worktree,
        ]
        if list(self.target.key()) != expected:
            raise cc.ContractError("destino do snapshot diverge do ledger")
        if row.authorization_reference != self.authorization_reference:
            raise cc.ContractError("referência de autorização diverge do ledger")
        snapshot["payload_sha256"] = row.payload_sha256
        snapshot["target_key"] = list(self.target.key())
        snapshot["acceptance_criteria"] = tuple(
            self._authoritative_snapshot.get("acceptance_criteria", ()))
        return snapshot

    def anchored_payload_sha256(self) -> str:
        row = self.db.get(self.mission_id)
        if row is None:
            raise cc.ContractError("missão ausente no ledger")
        return row.payload_sha256

    def fixture_environment(self) -> Optional[str]:
        """Environment vem da autorização sintética, nunca do cache adulterado."""
        if self.synthetic_authorization is None:
            return None
        return self.synthetic_authorization.environment

    def _evidence_text(self, evidence: Mapping[str, Any]) -> str:
        return cc.canonical_json(dict(evidence))

    def update(self, state: str, evidence: Mapping[str, Any]) -> pilot.PilotOperationRecord:
        self._evidence.update(dict(evidence))
        text = self._evidence_text(evidence)
        if state == ledger.DISPATCHING:
            # reserve_dispatch já realizou a transição atômica; a evidência
            # pós-transmit é gravada no ledger (não apenas no cache) para
            # sobreviver a interrupção/reinício sem reconstrução externa.
            self.db.append_evidence(self.mission_id, evidence=text, actor=self.actor)
            self._write_cache()
            return self.load() or self._record(self.db.get(self.mission_id))
        transitions = {
            ledger.UNKNOWN: self.db.mark_unknown,
            ledger.FAILED: self.db.fail,
            ledger.DELIVERED: self.db.mark_delivered,
            ledger.RUNNING: self.db.mark_running,
            ledger.COMPLETED: self.db.complete,
        }
        handler = transitions.get(state)
        if handler is None:
            raise cc.ContractError(f"transição de ledger desconhecida: {state}")
        current = self.db.get(self.mission_id)
        if state == ledger.COMPLETED and current and current.state == ledger.DISPATCHING:
            # Reconciliação sintética com evidência positiva: o relatório
            # confirmado percorre as transições canônicas do ledger.
            self.db.mark_delivered(self.mission_id, evidence=text, actor=self.actor)
            self.db.mark_running(self.mission_id, evidence=text, actor=self.actor)
        if state == ledger.FAILED and current and current.state == ledger.DISPATCHING:
            self.db.mark_unknown(self.mission_id, evidence=text, actor=self.actor)
            self.db.reconcile(
                self.mission_id, target=ledger.RUNNING, evidence=text,
                authority_reference=self.authorization_reference, actor=self.actor,
            )
        handler(self.mission_id, evidence=text, actor=self.actor)
        self._write_cache()
        return self.load()

    def _reservation_view(self) -> dict[str, Any]:
        fields = self._target_fields()
        return {
            "mission_id": self.mission_id,
            "agent": fields["agent"],
            "session_id": fields["session_id"],
            "source": "ledger-atomic-reservation",
        }

    @contextmanager
    def reserve(self) -> Iterator[dict[str, Any]]:
        fields = self._target_fields()
        try:
            self.db.reserve_dispatch(self.mission_id, actor=self.actor, **fields)
        except (ledger.DuplicateReservation, ledger.InvalidTransition) as exc:
            # Processo perdedor: erro controlado, nenhuma gravação adicional
            # e nenhuma alteração da reserva do vencedor.
            raise cc.AuthorizationError("reserva já ativa; sem reenvio") from exc
        self._evidence["reservation"] = self._reservation_view()
        self._write_cache()
        # A mesma visão é devolvida por read_reservation(): o transporte
        # compara as duas imediatamente antes do POST.
        yield self._reservation_view()

    def assert_transmittable(self) -> pilot.PilotOperationRecord:
        """Elegibilidade ANTERIOR à reserva: AUTHORIZED e sem reserva ativa."""
        if not self.db.can_dispatch(self.mission_id):
            raise cc.AuthorizationError("ledger não autoriza primeira transmissão")
        return self.load()

    def assert_reserved(self) -> pilot.PilotOperationRecord:
        """Validação da reserva imediatamente antes do despacho.

        DISPATCHING é esperado AQUI; aceitar o estado globalmente seria burlar
        o controle de reserva. Exige também a reserva persistida no ledger.
        """
        row = self.db.get(self.mission_id)
        if row is None or row.state not in {ledger.AUTHORIZED, ledger.DISPATCHING}:
            raise cc.AuthorizationError("reserva de despacho não confirmada pelo ledger")
        if not self.db.is_reserved(self.mission_id):
            raise cc.AuthorizationError("reserva de despacho ausente no ledger")
        return self.load()

    def read_reservation(self) -> Optional[dict[str, Any]]:
        if not self.db.is_reserved(self.mission_id):
            return None
        return self._reservation_view()

    def recover(self) -> pilot.PilotOperationRecord:
        record = self.load()
        if record is None:
            raise cc.ContractError("registro ausente")
        if record.state == ledger.DISPATCHING:
            self.db.mark_unknown(
                self.mission_id,
                evidence=self._evidence_text({"interruption_unknown": True,
                                              "automatic_resend": False}),
                actor=self.actor,
            )
        return self.load()

    def reconcile_unknown(self, target: str, *, evidence: str = "",
                          human_decision: bool = False) -> pilot.PilotOperationRecord:
        """Saída de UNKNOWN pelo Ledger.reconcile, nunca por transição direta."""
        row = self.db.get(self.mission_id)
        if row is None or row.state != ledger.UNKNOWN:
            raise cc.AuthorizationError("reconciliação exige estado UNKNOWN no ledger")
        result = self.db.reconcile(
            self.mission_id, target=target,
            evidence=evidence or "cli-reconcile-explicit",
            authority_reference=self.authorization_reference,
            actor=self.actor, human_decision=human_decision)
        self._evidence["reconciled_from_unknown"] = target
        self._write_cache()
        return self._record(result)


def select_with_policy(
    inventory: Any,
    mission: Any,
    policy: Any = None,
    availability: Any = (),
    benchmark: Any = (),
    evaluated_at: Optional[datetime] = None,
) -> model_policy.Recommendation:
    """Delega a seleção ao motor canônico #71; nenhuma regra é duplicada."""
    return model_policy.recommend(
        inventory=inventory,
        mission=mission,
        policy=policy,
        availability=availability,
        benchmark=benchmark,
        evaluated_at=evaluated_at,
    )


def model_evidence(result: model_policy.Recommendation) -> dict[str, Any]:
    return {
        "source": "coord_model_policy",
        "decision": result.decision.value,
        "selected": None if result.selected is None else str(result.selected),
        "authorization_required": result.requires_authorization,
        "authorized": result.authorized,
        "reason_codes": tuple(sorted(result.reason_codes)),
    }


def authorization_interface_p14b() -> dict[str, Any]:
    """Contrato mínimo do gate #72 — SEM autoridade concedida nesta rodada."""
    return {
        "mode": "SHADOW",
        "transport": "synthetic",
        "external_authority": None,
        "authority_digest_required": True,
        "approver_allowlist_required": True,
        "consumer_identity_proven": False,
        "flock_is_not_consumer_identity": True,
        "operational_authorization": False,
        "defer_to": "P14B",
    }


def synthetic_transport_evidence(operation_state: str, *, contacted: bool = False) -> dict[str, Any]:
    """Evidência coerente de transporte sintético — nunca se passa por real."""
    return {
        "source": "synthetic",
        "transport_real": False,
        "service_contacted": contacted,
        "operation_state": operation_state,
        "authoritative_transport": False,
    }
