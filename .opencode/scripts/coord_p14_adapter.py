#!/usr/bin/env python3
"""COORD-04/P14A — adaptadores OFFLINE entre piloto, ledger (#70) e política (#71).

Nenhuma autoridade nova é criada. O ledger é a única fonte operacional de
estado/reserva; o motor de modelos é o único decisor de seleção. O gate de
autorização (#72) permanece fechado e é apenas descrito por uma interface
contratual para P14B.
"""
from __future__ import annotations

import json
from contextlib import contextmanager
from datetime import datetime
from typing import Any, Iterator, Mapping, Optional

import coord_communication as cc
import coord_ledger as ledger
import coord_model_policy as model_policy
import coord_pilot as pilot

__all__ = [
    "LedgerOperationStore",
    "select_with_policy",
    "model_evidence",
    "authorization_interface_p14b",
    "synthetic_transport_evidence",
]


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
        self._evidence: dict[str, Any] = {}

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
        return pilot.PilotOperationRecord(
            mission_id=row.mission_id,
            operation_id=row.operation_id,
            authorization_id=self.authorization_reference,
            authorization_canonical=row.authorization_reference,
            target_key=self.target.key(),
            prompt_sha256=self.payload_hash,
            state=row.state,
            created_at=row.created_at,
            updated_at=row.updated_at,
            evidence=dict(self._evidence),
            history=history,
            mission_snapshot=dict(self._evidence.get("mission_snapshot", {})),
        )

    def create(self, record: pilot.PilotOperationRecord) -> pilot.PilotOperationRecord:
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
        if current is not None and current.state == ledger.PROPOSED:
            self.db.authorize(
                self.mission_id,
                authorization_reference=self.authorization_reference,
                payload_sha256_value=self.payload_hash,
                actor=self.actor,
            )
        return self.load() or record

    def load(self) -> Optional[pilot.PilotOperationRecord]:
        row = self.db.get(self.mission_id)
        return None if row is None else self._record(row)

    def _evidence_text(self, evidence: Mapping[str, Any]) -> str:
        return cc.canonical_json(dict(evidence))

    def update(self, state: str, evidence: Mapping[str, Any]) -> pilot.PilotOperationRecord:
        self._evidence.update(dict(evidence))
        if state == ledger.DISPATCHING:
            # reserve_dispatch já realizou a transição atômica.
            return self.load() or self._record(self.db.get(self.mission_id))
        text = self._evidence_text(evidence)
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
        handler(self.mission_id, evidence=text, actor=self.actor)
        return self.load()

    @contextmanager
    def reserve(self) -> Iterator[dict[str, Any]]:
        fields = self._target_fields()
        self.db.reserve_dispatch(self.mission_id, actor=self.actor, **fields)
        yield {
            "mission_id": self.mission_id,
            "agent": fields["agent"],
            "session_id": fields["session_id"],
            "source": "ledger-atomic-reservation",
        }

    def assert_transmittable(self) -> pilot.PilotOperationRecord:
        if not self.db.can_dispatch(self.mission_id):
            raise cc.AuthorizationError("ledger não autoriza primeira transmissão")
        return self.load()

    def read_reservation(self) -> Optional[dict[str, Any]]:
        if not self.db.is_reserved(self.mission_id):
            return None
        return {"mission_id": self.mission_id, "source": "ledger"}

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
