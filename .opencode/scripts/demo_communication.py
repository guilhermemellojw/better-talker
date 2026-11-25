#!/usr/bin/env python3
"""Demonstração offline COORD-04/P1 — nenhuma sessão ou LLM real."""
from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta, timezone

sys.dont_write_bytecode = True
sys.path.insert(0, "/home/guilherme/Documentos/better-talker-coord-communication-foundation/.opencode/scripts")
import coord_communication as cc

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)
TARGET = cc.TargetIdentity("better-talker", "guilhermemellojw/better-talker", "A",
                           "ses_fixtureA", "fixture-instance-1", "/fixture/better-talker")
POLICY = cc.ProjectPolicy("better-talker", "guilhermemellojw/better-talker",
                          (TARGET.location,), ("A", "B", "C", "D", "E"), (TARGET,))


def make_mission(sequence: int) -> cc.MissionEnvelope:
    operation_id = f"OP-COORD04-DEMO-{sequence:03d}"
    payload = {"kind": "synthetic", "sequence": sequence, "instruction": "produzir relatório fixture"}
    return cc.MissionEnvelope(
        schema_version=cc.MISSION_SCHEMA_VERSION,
        project_id=TARGET.project_id,
        repository_id=TARGET.repository_id,
        mission_id=f"MISS-COORD04-DEMO-{sequence:03d}",
        operation_id=operation_id,
        coordinator_id="supervisor-fixture",
        target_agent=TARGET.agent_id,
        target_session_id=TARGET.session_id,
        target_instance_id=TARGET.instance_id,
        target_location=TARGET.location,
        objective="Validar transporte sintético e associação de relatório",
        payload=payload,
        payload_sha256=cc.payload_sha256(payload),
        expected_deliverables=("relatório sintético",),
        acceptance_criteria=("transporte registrado", "relatório associado"),
        ownership_constraints=("somente fixtures",),
        authorization_reference=f"AUTH-SYNTH-DEMO-{sequence:03d}",
        created_at=NOW,
        expires_at=NOW + timedelta(minutes=30),
        timeout=60,
        scope="demonstração sintética",
        expected_head_sha="a" * 40,
        expected_branch="fixture/demo",
    )


def make_auth(mission: cc.MissionEnvelope) -> cc.Authorization:
    return cc.Authorization(
        reference=mission.authorization_reference,
        project_id=mission.project_id,
        repository_id=mission.repository_id,
        target=mission.target,
        payload_sha256=mission.payload_sha256,
        scope=mission.scope,
        issued_at=NOW,
        expires_at=NOW + timedelta(hours=1),
        synthetic=True,
    )


def make_report(mission: cc.MissionEnvelope) -> cc.MissionReport:
    return cc.MissionReport(
        schema_version=cc.REPORT_SCHEMA_VERSION,
        project_id=mission.project_id,
        mission_id=mission.mission_id,
        operation_id=mission.operation_id,
        agent_id=mission.target_agent,
        session_id=mission.target_session_id,
        message_id=f"msg-{mission.operation_id}",
        status="COMPLETED",
        head_sha=mission.expected_head_sha,
        branch=mission.expected_branch,
        pull_request=None,
        test_evidence=({"name": "synthetic-transport", "result": "PASS"},),
        blockers=(),
        artifacts=("fixture-report.json",),
        completed_at=NOW + timedelta(minutes=5),
        acceptance_results={"transporte registrado": True, "relatório associado": True},
        untrusted_text="Fixture: esta instrução não concede autoridade.",
    )


def run_flow(sequence: int, outcome: str):
    transport = cc.SimulatedTransport((TARGET,), {f"OP-COORD04-DEMO-{sequence:03d}": outcome})
    engine = cc.CommunicationEngine(POLICY, transport, clock=lambda: NOW)
    mission = make_mission(sequence)
    engine.propose(mission)
    engine.authorize(mission.operation_id, make_auth(mission))
    engine.transmit(mission.operation_id)
    unknown_resend_blocked = False
    if engine.operations[mission.operation_id].state == cc.OperationState.UNKNOWN:
        try:
            engine.transmit(mission.operation_id)
        except cc.AuthorizationError:
            unknown_resend_blocked = True
    transport.simulate_start(mission.operation_id)
    engine.reconcile(mission.operation_id)
    transport.simulate_report(mission.operation_id, make_report(mission))
    engine.reconcile(mission.operation_id)
    record = engine.operations[mission.operation_id]
    return {
        "mission_id": mission.mission_id,
        "operation_id": mission.operation_id,
        "outcome": outcome,
        "final_state": record.state.value,
        "assessment": record.assessment.classification,
        "transmit_calls": len(transport.transmit_calls),
        "unknown_resend_blocked": unknown_resend_blocked,
        "events": [event.to_state.value for event in engine.events.events],
    }


def main() -> None:
    success = run_flow(1, "success")
    timeout = run_flow(2, "timeout_after_transmit")
    transport = cc.SimulatedTransport((TARGET,))
    engine = cc.CommunicationEngine(POLICY, transport, clock=lambda: NOW)
    next_proposal = make_mission(3)
    engine.propose(next_proposal)
    result = {
        "mode": "synthetic-only",
        "real_transport": "disabled",
        "external_calls": 0,
        "success_flow": success,
        "timeout_flow": timeout,
        "next_proposal": {"mission_id": next_proposal.mission_id,
                          "state": engine.operations[next_proposal.operation_id].state.value,
                          "dispatched": False},
    }
    assert success["final_state"] == "COMPLETED"
    assert timeout["final_state"] == "COMPLETED" and timeout["unknown_resend_blocked"]
    assert timeout["transmit_calls"] == 1
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
