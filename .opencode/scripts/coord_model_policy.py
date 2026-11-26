#!/usr/bin/env python3
"""Deterministic model selection/recommendation engine (COORD-05B/P1).

Pure and offline: accepts an INJECTED inventory, policy, mission requirements and
availability evidence; returns a reasoned recommendation. It never calls an LLM,
never reads the clock, never contacts a service and never changes a session.

Cost categories come from explicit inventory fields — never from model names.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping

FREE = "free"
INCLUDED = "included"          # OpenCode Go franchise — not necessarily extra charge
ADDITIONAL = "additional"      # extra charge — blocked without explicit policy
UNKNOWN = "unknown"

AVAILABLE = "available"
UNAVAILABLE = "unavailable"
UNKNOWN_AVAILABILITY = "unknown"

OK = "RECOMMENDED"
NO_FREE_OPTION = "NO_FREE_OPTION"
BLOCKED_PAID = "BLOCKED_PAID"
BLOCKED_UNKNOWN_MISSION = "BLOCKED_UNKNOWN_MISSION"


@dataclass(frozen=True)
class Model:
    provider_id: str
    model_id: str
    variant: str | None = None
    cost: str = UNKNOWN
    context_tokens: int | None = None
    tools: frozenset[str] = frozenset()
    modalities: frozenset[str] = frozenset()
    capabilities: frozenset[str] = frozenset()
    availability: str = UNKNOWN_AVAILABILITY
    at_limit: bool = False
    cooldown_until: int | None = None
    cooldown_evidence: str | None = None

    @property
    def key(self) -> str:
        """Exact identity: providerID + model id + optional variant."""
        return f"{self.provider_id}/{self.model_id}" + (
            f"#{self.variant}" if self.variant else ""
        )


@dataclass(frozen=True)
class Policy:
    free_first: bool = True
    preferred_context: int = 1_000_000
    rotation_enabled: bool = True
    paid_escalation_authorized: bool = False
    paid_authorization_ref: str | None = None
    allow_unknown_availability: bool = False


@dataclass(frozen=True)
class Mission:
    mission_id: str
    min_context_tokens: int | None = None
    required_tools: frozenset[str] = frozenset()
    required_modalities: frozenset[str] = frozenset()
    required_capabilities: frozenset[str] = frozenset()
    current_model: str | None = None


@dataclass(frozen=True)
class Evidence:
    """Injected observations only — no wall clock, no invented reset time."""

    observed_at: int | None = None
    source: str | None = None
    notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class Recommendation:
    status: str
    model: str | None
    category: str
    reasons: tuple[str, ...]
    alternatives: tuple[str, ...]
    rotation: bool = False
    escalation_blocked: bool = False
    executed: bool = False  # always False: selection != execution/session switch


def _reason(code: str, key: str = "") -> str:
    return f"{code}:{key}" if key else code


def _eligible(model: Model, mission: Mission, policy: Policy, evidence: Evidence) -> list[str]:
    """Return ALL exclusion reason codes; empty means eligible for ranking."""
    misses: list[str] = []
    if model.availability == UNAVAILABLE:
        misses.append(_reason("UNAVAILABLE", model.key))
    if model.at_limit:
        misses.append(_reason("LIMIT_REACHED", model.key))
    if model.cooldown_until is not None:
        if evidence.observed_at is None or not model.cooldown_evidence:
            # Evidence absent: never invent a renewal time.
            misses.append(_reason("COOLDOWN_UNVERIFIED", model.key))
        elif evidence.observed_at < model.cooldown_until:
            misses.append(_reason("COOLDOWN_ACTIVE", model.key))
    if model.availability == UNKNOWN_AVAILABILITY and not policy.allow_unknown_availability:
        misses.append(_reason("AVAILABILITY_UNKNOWN", model.key))
    if model.cost not in (FREE, INCLUDED, ADDITIONAL):
        misses.append(_reason("COST_UNKNOWN", model.key))
    if mission.min_context_tokens is not None:
        if model.context_tokens is None:
            misses.append(_reason("CONTEXT_UNKNOWN", model.key))
        elif model.context_tokens < mission.min_context_tokens:
            misses.append(_reason("CONTEXT_INSUFFICIENT", model.key))
    if not mission.required_tools <= model.tools:
        misses.append(_reason("TOOLS_UNVERIFIED", model.key))
    if not mission.required_modalities <= model.modalities:
        misses.append(_reason("MODALITY_UNVERIFIED", model.key))
    if not mission.required_capabilities <= model.capabilities:
        misses.append(_reason("CAPABILITY_UNVERIFIED", model.key))
    return misses


def _rank_key(model: Model, policy: Policy) -> tuple:
    """Free first, then known >=1M context, then larger context, then identity."""
    cost_rank = {FREE: 0, INCLUDED: 1, ADDITIONAL: 2}[model.cost]
    big = model.context_tokens is not None and model.context_tokens >= policy.preferred_context
    context = model.context_tokens if model.context_tokens is not None else -1
    availability_rank = 0 if model.availability == AVAILABLE else 1
    return (cost_rank, 0 if big else 1, -context, availability_rank, model.key)


def recommend(
    inventory: Iterable[Model],
    policy: Policy,
    mission: Mission,
    evidence: Evidence,
) -> Recommendation:
    if mission.mission_id.upper() == "UNKNOWN":
        return Recommendation(
            status=BLOCKED_UNKNOWN_MISSION,
            model=None,
            category=UNKNOWN,
            reasons=(_reason("MISSION_UNKNOWN"),),
            alternatives=(),
            escalation_blocked=True,
        )

    excluded: list[str] = []
    eligible: list[Model] = []
    paid: list[Model] = []
    for model in inventory:
        misses = _eligible(model, mission, policy, evidence)
        if misses:
            excluded.extend(misses)
            continue
        if model.cost == ADDITIONAL:
            paid.append(model)
        else:
            eligible.append(model)

    eligible.sort(key=lambda m: _rank_key(m, policy))
    paid.sort(key=lambda m: _rank_key(m, policy))
    non_paid = [m for m in eligible if m.cost in (FREE, INCLUDED)]
    reasons = sorted(set(excluded))

    rotation = False
    if non_paid and mission.current_model:
        if non_paid[0].key == mission.current_model and policy.rotation_enabled:
            alternatives = [m for m in non_paid[1:] if m.cost == FREE]
            if alternatives:
                non_paid = [alternatives[0]] + [m for m in non_paid if m.key != alternatives[0].key]
                rotation = True
                reasons.append(_reason("ROTATION", alternatives[0].key))
        elif any(m.key == mission.current_model for m in eligible) is False:
            reasons.append(_reason("CURRENT_UNAVAILABLE", mission.current_model))

    if rotation:
        reasons.append(_reason("CURRENT_MODEL_ROTATED", mission.current_model or ""))

    big = [m for m in non_paid if m.cost == FREE and
           m.context_tokens is not None and m.context_tokens >= policy.preferred_context]
    if big:
        reasons.append(_reason("PREFERRED_1M", big[0].key))
    if any(m.cost == FREE and m.context_tokens is None for m in non_paid):
        reasons.append(_reason("CONTEXT_UNKNOWN_RANKED_LOW"))
    if non_paid and policy.free_first and non_paid[0].cost == FREE:
        reasons.append(_reason("FREE_FIRST", non_paid[0].key))

    if non_paid:
        chosen = non_paid[0]
        return Recommendation(
            status=OK,
            model=chosen.key,
            category=chosen.cost,
            reasons=tuple(sorted(set(reasons))),
            alternatives=tuple(m.key for m in non_paid[1:]),
            rotation=rotation,
            escalation_blocked=False,
        )

    if paid:
        authorized = policy.paid_escalation_authorized and policy.paid_authorization_ref
        if authorized:
            reasons.append(_reason("PAID_AUTHORIZED", str(policy.paid_authorization_ref)))
            return Recommendation(
                status=OK,
                model=paid[0].key,
                category=ADDITIONAL,
                reasons=tuple(sorted(set(reasons))),
                alternatives=tuple(m.key for m in paid[1:]),
                escalation_blocked=False,
            )
        reasons.append(_reason("NO_ELIGIBLE_FREE_OPTION"))
        reasons.append(_reason("PAID_BLOCKED"))
        return Recommendation(
            status=BLOCKED_PAID,
            model=None,
            category=ADDITIONAL,
            reasons=tuple(sorted(set(reasons))),
            alternatives=(),
            escalation_blocked=True,
        )

    reasons.append(_reason("NO_ELIGIBLE_FREE_OPTION"))
    return Recommendation(
        status=NO_FREE_OPTION,
        model=None,
        category=UNKNOWN,
        reasons=tuple(sorted(set(reasons))),
        alternatives=(),
        escalation_blocked=True,
    )


def describe(recommendation: Recommendation) -> str:
    """Stable, deterministic justification text — no clock, no model call."""
    head = f"{recommendation.status} | model={recommendation.model or '-'}"
    category = f"category={recommendation.category}"
    flags = "; ".join(recommendation.reasons) or "no-flags"
    return f"{head} | {category} | reasons={flags} | executed={recommendation.executed}"


if __name__ == "__main__":  # pragma: no cover - host-only demo, no inference
    example = recommend([], Policy(), Mission("UNKNOWN"), Evidence())
    print(describe(example))
