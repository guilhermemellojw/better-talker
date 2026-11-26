#!/usr/bin/env python3
"""COORD-05B/P1 — deterministic model selection and recommendation.

This module is deliberately offline and side-effect free:

* the inventory, policy, mission requirements, availability evidence and
  benchmark observations are injected by the caller;
* it never calls an LLM, the network, an OpenCode API, a session, ADB or
  Android;
* it never changes a model, a session or a credential;
* it returns a recommendation plus a separate, non-executed session-change
  intent.

The ranking and the explanation are deterministic for the same inputs and
the same injected ``evaluated_at`` instant.  See ``.opencode/MODEL_POLICY.md``.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from enum import Enum
from typing import Any, Protocol

sys.dont_write_bytecode = True

MILLION = 1_000_000


# ---------------------------------------------------------------------------
# Vocabulary
# ---------------------------------------------------------------------------


class CostCategory(str, Enum):
    FREE = "free"
    INCLUDED = "included"
    ADDITIONAL = "additional"
    UNKNOWN = "unknown"


class AvailabilityStatus(str, Enum):
    AVAILABLE = "available"
    UNAVAILABLE = "unavailable"
    LIMITS_REACHED = "limits_reached"
    UNKNOWN = "unknown"


class MissionState(str, Enum):
    PLANNED = "PLANNED"
    READY = "READY"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"


class CoordinationMode(str, Enum):
    SHADOW = "SHADOW"
    ASSISTED = "ASSISTED"
    DELEGATED = "DELEGATED"


class BenchmarkStatus(str, Enum):
    VERIFIED = "verified"
    UNVERIFIED = "unverified"


class Decision(str, Enum):
    RECOMMEND_FREE = "RECOMMEND_FREE"
    KEEP_CURRENT = "KEEP_CURRENT"
    RECOMMEND_AUTHORIZED_FALLBACK = "RECOMMEND_AUTHORIZED_FALLBACK"
    NEEDS_AUTHORIZATION = "NEEDS_AUTHORIZATION"
    NO_ELIGIBLE_FREE = "NO_ELIGIBLE_FREE"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    MISSION_UNKNOWN = "MISSION_UNKNOWN"


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------


def _utc(value: Any, field: str) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise ValueError(f"{field}: timezone-aware datetime required")
        return value.astimezone(timezone.utc)
    if isinstance(value, str):
        raw = value.strip()
        if not raw:
            raise ValueError(f"{field}: empty timestamp")
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        try:
            parsed = datetime.fromisoformat(raw)
        except ValueError as exc:
            raise ValueError(f"{field}: invalid ISO-8601 timestamp") from exc
        if parsed.tzinfo is None:
            raise ValueError(f"{field}: timezone-aware timestamp required")
        return parsed.astimezone(timezone.utc)
    raise ValueError(f"{field}: datetime or ISO-8601 string required")


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _decimal(value: Any, field: str, *, nonnegative: bool = True) -> Decimal | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError(f"{field}: decimal required")
    try:
        parsed = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"{field}: invalid decimal") from exc
    if not parsed.is_finite():
        raise ValueError(f"{field}: finite decimal required")
    if nonnegative and parsed < 0:
        raise ValueError(f"{field}: must be >= 0")
    return parsed


def _bool(value: Any, field: str) -> bool | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    raise ValueError(f"{field}: boolean required")


def _text(value: Any, field: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field}: string required")
    text = value.strip()
    if not text and not allow_empty:
        raise ValueError(f"{field}: non-empty string required")
    return text


def _string_set(value: Any, field: str) -> frozenset[str] | None:
    if value is None:
        return None
    if isinstance(value, str):
        values: Sequence[Any] = (value,)
    elif isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        values = value
    else:
        raise ValueError(f"{field}: string or list of strings required")
    out: list[str] = []
    for item in values:
        text = _text(item, field).lower()
        out.append(text)
    return frozenset(out)


def _int(value: Any, field: str, *, minimum: int = 1) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field}: integer required")
    if value < minimum:
        raise ValueError(f"{field}: must be >= {minimum}")
    return value


def _alias(mapping: Mapping[str, Any], canonical: str, alias: str) -> Any:
    first = mapping.get(canonical)
    second = mapping.get(alias)
    if first is not None and second is not None and first != second:
        raise ValueError(f"{canonical}/{alias}: conflicting values")
    return first if first is not None else second


# ---------------------------------------------------------------------------
# Model identity and inventory
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ModelRef:
    """Exact provider/model/variant identity. Never normalised by name."""

    provider_id: str
    model_id: str
    variant: str | None = None

    def __post_init__(self) -> None:
        for field_name, value in (
            ("provider_id", self.provider_id),
            ("model_id", self.model_id),
        ):
            if not isinstance(value, str) or not value or value != value.strip():
                raise ValueError(f"{field_name}: exact non-empty string required")
        if self.variant is not None:
            if not isinstance(self.variant, str):
                raise ValueError("variant: string or null required")
            if self.variant != self.variant.strip():
                raise ValueError("variant: outer whitespace is not allowed")
            if self.variant == "":
                raise ValueError("variant: empty string is not allowed; use null")

    @property
    def sort_key(self) -> tuple[str, str, str]:
        return (self.provider_id, self.model_id, self.variant or "")

    def as_dict(self) -> dict[str, Any]:
        return {
            "provider_id": self.provider_id,
            "model_id": self.model_id,
            "variant": self.variant,
        }

    def __str__(self) -> str:
        # COORD-05B/P2 F5: variant="none" is the canonical absence marker;
        # an absent variant never implies "#default".
        base = f"{self.provider_id}/{self.model_id}"
        return base if self.variant is None or self.variant.lower() == "none" else f"{base}#{self.variant}"

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "ModelRef":
        if not isinstance(value, Mapping):
            raise ValueError("model ref: mapping required")
        provider = _alias(value, "provider_id", "providerID")
        model = _alias(value, "model_id", "id")
        variant = value.get("variant")
        if provider is None or model is None:
            raise ValueError("model ref: provider_id and model_id required")
        return cls(
            provider_id=_text(provider, "provider_id"),
            model_id=_text(model, "model_id"),
            variant=None if variant is None else _text(variant, "variant"),
        )


def canonical_model_ref(value: ModelRef | Mapping[str, Any] | str) -> str:
    """Canonical identity: provider/id, without suffix for absent or 'none' variant.

    Explicit variants other than 'none' remain distinct. Contract aligned with
    the documented PR #64 convention; no code from that PR is imported.
    """
    if isinstance(value, ModelRef):
        ref = value
    elif isinstance(value, str):
        base, separator, variant = value.partition("#")
        provider, slash, model = base.partition("/")
        if not slash or not provider or not model:
            raise ValueError("model ref: 'provider/model[#variant]' required")
        if separator and variant.lower() == "none":
            variant = ""
        ref = ModelRef(provider, model, variant or None)
    elif isinstance(value, Mapping):
        ref = ModelRef.from_mapping(value)
    else:
        raise ValueError("model ref: ModelRef, mapping or string required")
    return str(ref)


@dataclass(frozen=True)
class Model:
    ref: ModelRef
    context_window_tokens: int | None
    tools: bool | None
    modalities: frozenset[str] | None
    capabilities: frozenset[str] | None
    cost_category: CostCategory
    cost_source: str | None = None
    cost_observed_at: datetime | None = None
    input_usd_per_million: Decimal | None = None
    output_usd_per_million: Decimal | None = None
    notes: str = ""

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "Model":
        if not isinstance(value, Mapping):
            raise ValueError("model: mapping required")
        ref_value = value.get("ref", value)
        if not isinstance(ref_value, Mapping):
            raise ValueError("model.ref: mapping required")
        ref = ModelRef.from_mapping(ref_value)

        context = _alias(value, "context_window_tokens", "context")
        context_int = _int(context, "context_window_tokens")

        tools = _bool(value.get("tools"), "tools")
        modalities = _string_set(value.get("modalities"), "modalities")
        capabilities = _string_set(value.get("capabilities"), "capabilities")

        cost = value.get("cost")
        if cost is None:
            category_raw = value.get("cost_category")
            cost_source = None
            cost_observed_at = None
            input_cost = None
            output_cost = None
        elif isinstance(cost, Mapping):
            category_raw = cost.get("category", value.get("cost_category"))
            cost_source = cost.get("source")
            cost_observed_at = _utc(cost.get("observed_at"), "cost.observed_at")
            input_cost = _decimal(
                cost.get("input_usd_per_million"), "cost.input_usd_per_million"
            )
            output_cost = _decimal(
                cost.get("output_usd_per_million"), "cost.output_usd_per_million"
            )
        else:
            raise ValueError("cost: mapping required (do not infer from the model name)")

        if category_raw is None:
            category = CostCategory.UNKNOWN
        else:
            try:
                category = CostCategory(_text(category_raw, "cost.category").lower())
            except ValueError as exc:
                raise ValueError("cost.category: unknown value") from exc

        return cls(
            ref=ref,
            context_window_tokens=context_int,
            tools=tools,
            modalities=modalities,
            capabilities=capabilities,
            cost_category=category,
            cost_source=None if cost_source is None else _text(cost_source, "cost.source"),
            cost_observed_at=cost_observed_at,
            input_usd_per_million=input_cost,
            output_usd_per_million=output_cost,
            notes=str(value.get("notes", "")),
        )

    @property
    def is_free(self) -> bool:
        return self.cost_category is CostCategory.FREE


@dataclass(frozen=True)
class Inventory:
    models: tuple[Model, ...]
    source: str | None = None
    observed_at: datetime | None = None
    version: str | None = None

    @classmethod
    def from_value(cls, value: Any) -> "Inventory":
        if isinstance(value, Inventory):
            return value
        if isinstance(value, Mapping):
            raw_models = value.get("models")
            source = value.get("source")
            observed_at = _utc(value.get("observed_at"), "inventory.observed_at")
            version = value.get("version")
        elif isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
            raw_models = value
            source = None
            observed_at = None
            version = None
        else:
            raise ValueError("inventory: mapping or sequence required")
        if raw_models is None:
            raise ValueError("inventory.models: required")
        if not isinstance(raw_models, Sequence) or isinstance(raw_models, (str, bytes, bytearray)):
            raise ValueError("inventory.models: list required")
        models = tuple(Model.from_mapping(item) for item in raw_models)
        seen: set[ModelRef] = set()
        for model in models:
            if model.ref in seen:
                raise ValueError(f"duplicate_model_ref:{model.ref}")
            seen.add(model.ref)
        return cls(
            models=models,
            source=None if source is None else _text(source, "inventory.source"),
            observed_at=observed_at,
            version=None if version is None else _text(version, "inventory.version"),
        )


# ---------------------------------------------------------------------------
# Paid authorization, policy and mission
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PaidAuthorization:
    """Explicit, bounded authorization for included/additional consumption."""

    authorized: bool
    authorized_by: str
    scope: str
    categories: frozenset[CostCategory]
    model_refs: frozenset[ModelRef]
    max_cost_usd: Decimal | None
    expires_at: datetime | None
    evidence: str
    mission_id: str | None = None
    purpose: str | None = None

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "PaidAuthorization":
        if not isinstance(value, Mapping):
            raise ValueError("paid_authorization: mapping required")
        authorized = _bool(value.get("authorized"), "paid_authorization.authorized")
        if authorized is None:
            raise ValueError("paid_authorization.authorized: required")
        categories_raw = value.get("categories", [])
        if not isinstance(categories_raw, Sequence) or isinstance(
            categories_raw, (str, bytes, bytearray)
        ):
            raise ValueError("paid_authorization.categories: list required")
        categories: set[CostCategory] = set()
        for item in categories_raw:
            try:
                categories.add(CostCategory(_text(item, "authorization.category").lower()))
            except ValueError as exc:
                raise ValueError("paid_authorization.categories: unknown value") from exc
        refs_raw = value.get("model_refs", [])
        if not isinstance(refs_raw, Sequence) or isinstance(refs_raw, (str, bytes, bytearray)):
            raise ValueError("paid_authorization.model_refs: list required")
        refs = frozenset(ModelRef.from_mapping(item) for item in refs_raw)
        max_cost = _decimal(value.get("max_cost_usd"), "paid_authorization.max_cost_usd")
        expires_at = _utc(value.get("expires_at"), "paid_authorization.expires_at")
        authorized_by = _text(value.get("authorized_by", ""), "paid_authorization.authorized_by", allow_empty=True)
        scope = _text(value.get("scope", ""), "paid_authorization.scope", allow_empty=True)
        evidence = _text(value.get("evidence", ""), "paid_authorization.evidence", allow_empty=True)
        mission_id = value.get("mission_id")
        purpose = value.get("purpose")

        if authorized:
            for field_name, field_value in (
                ("authorized_by", authorized_by),
                ("scope", scope),
                ("evidence", evidence),
            ):
                if not field_value:
                    raise ValueError(f"paid_authorization.{field_name}: required when authorized")
            if not categories:
                raise ValueError("paid_authorization.categories: required when authorized")
            if not refs:
                raise ValueError("paid_authorization.model_refs: required when authorized")
            if max_cost is None:
                raise ValueError("paid_authorization.max_cost_usd: required when authorized")
            if expires_at is None:
                raise ValueError("paid_authorization.expires_at: required when authorized")

        return cls(
            authorized=bool(authorized),
            authorized_by=authorized_by,
            scope=scope,
            categories=frozenset(categories),
            model_refs=refs,
            max_cost_usd=max_cost,
            expires_at=expires_at,
            evidence=evidence,
            mission_id=None if mission_id is None else _text(mission_id, "paid_authorization.mission_id"),
            purpose=None if purpose is None else _text(purpose, "paid_authorization.purpose"),
        )

    def validate(
        self,
        now: datetime,
        mission: "Mission",
        candidate: "Model",
        estimated_extra_cost_usd: Decimal | None,
    ) -> tuple[bool, str]:
        if not self.authorized:
            return False, "authorization_not_granted"
        if self.mission_id is not None and self.mission_id != mission.mission_id:
            return False, "authorization_mission_mismatch"
        if candidate.cost_category not in self.categories:
            return False, "authorization_category_not_covered"
        if candidate.ref not in self.model_refs:
            return False, "authorization_model_not_covered"
        if self.expires_at is None or now >= self.expires_at:
            return False, "authorization_expired"
        if estimated_extra_cost_usd is None:
            return False, "authorization_estimated_cost_unknown"
        if self.max_cost_usd is None:
            return False, "authorization_max_cost_missing"
        if estimated_extra_cost_usd > self.max_cost_usd:
            return False, "authorization_cost_exceeds_max"
        return True, "authorization_valid"


@dataclass(frozen=True)
class Policy:
    free_first: bool = True
    preferred_context_tokens: int = MILLION
    allow_unknown_context: bool = True
    allow_unknown_availability: bool = False
    allow_included_franchise: bool = True
    allow_additional_consumption: bool = False
    rotation_enabled: bool = True
    paid_authorization: PaidAuthorization | None = None
    benchmark_suite: str | None = None
    use_verified_benchmark: bool = True
    coordination_mode: CoordinationMode = CoordinationMode.SHADOW

    @classmethod
    def from_value(cls, value: Any) -> "Policy":
        if isinstance(value, Policy):
            return value
        if not isinstance(value, Mapping):
            raise ValueError("policy: mapping required")
        free_first = _bool(value.get("free_first", True), "policy.free_first")
        if free_first is not True:
            raise ValueError("policy.free_first: Free First must be true")
        preferred = _int(
            value.get("preferred_context_tokens", MILLION),
            "policy.preferred_context_tokens",
        )
        assert preferred is not None
        allow_unknown_context = _bool(
            value.get("allow_unknown_context", True), "policy.allow_unknown_context"
        )
        allow_unknown_availability = _bool(
            value.get("allow_unknown_availability", False),
            "policy.allow_unknown_availability",
        )
        allow_included = _bool(
            value.get("allow_included_franchise", True),
            "policy.allow_included_franchise",
        )
        allow_additional = _bool(
            value.get("allow_additional_consumption", False),
            "policy.allow_additional_consumption",
        )
        rotation_enabled = _bool(
            value.get("rotation_enabled", True), "policy.rotation_enabled"
        )
        mode_raw = _text(value.get("coordination_mode", "SHADOW"), "policy.coordination_mode")
        try:
            mode = CoordinationMode(mode_raw.upper())
        except ValueError as exc:
            raise ValueError("policy.coordination_mode: unknown value") from exc
        if mode is not CoordinationMode.SHADOW:
            raise ValueError(
                "policy.coordination_mode: ASSISTED/DELEGATED disabled; SHADOW only"
            )
        suite = value.get("benchmark_suite")
        auth = value.get("paid_authorization")
        return cls(
            free_first=True,
            preferred_context_tokens=preferred,
            allow_unknown_context=bool(allow_unknown_context),
            allow_unknown_availability=bool(allow_unknown_availability),
            allow_included_franchise=bool(allow_included),
            allow_additional_consumption=bool(allow_additional),
            rotation_enabled=bool(rotation_enabled),
            paid_authorization=None
            if auth is None
            else PaidAuthorization.from_mapping(auth),
            benchmark_suite=None if suite is None else _text(suite, "policy.benchmark_suite"),
            use_verified_benchmark=bool(
                _bool(value.get("use_verified_benchmark", True), "policy.use_verified_benchmark")
            ),
            coordination_mode=mode,
        )


@dataclass(frozen=True)
class MissionRequirements:
    min_context_tokens: int | None = None
    requires_tools: bool = False
    required_modalities: frozenset[str] = frozenset()
    required_capabilities: frozenset[str] = frozenset()

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any] | None) -> "MissionRequirements":
        if value is None:
            return cls()
        if not isinstance(value, Mapping):
            raise ValueError("mission.requirements: mapping required")
        min_context = _int(value.get("min_context_tokens"), "mission.requirements.min_context_tokens")
        requires_tools = _bool(
            value.get("requires_tools", False),
            "mission.requirements.requires_tools",
        )
        modalities = _string_set(
            value.get("required_modalities"), "mission.requirements.required_modalities"
        )
        capabilities = _string_set(
            value.get("required_capabilities"),
            "mission.requirements.required_capabilities",
        )
        return cls(
            min_context_tokens=min_context,
            requires_tools=bool(requires_tools),
            required_modalities=modalities or frozenset(),
            required_capabilities=capabilities or frozenset(),
        )


@dataclass(frozen=True)
class Mission:
    mission_id: str
    state: MissionState
    requirements: MissionRequirements
    delivery_state: str | None = None
    current_model: ModelRef | None = None
    estimated_extra_cost_usd: Decimal | None = None
    notes: str = ""

    @property
    def is_unknown(self) -> bool:
        return self.state is MissionState.UNKNOWN or (
            self.delivery_state is not None
            and self.delivery_state.strip().upper() == "UNKNOWN"
        )

    @classmethod
    def from_value(cls, value: Any) -> "Mission":
        if isinstance(value, Mission):
            return value
        if not isinstance(value, Mapping):
            raise ValueError("mission: mapping required")
        mission_id = _text(value.get("mission_id"), "mission.mission_id")
        state_raw = value.get("state", MissionState.PLANNED.value)
        try:
            state = MissionState(_text(state_raw, "mission.state").upper())
        except ValueError as exc:
            raise ValueError("mission.state: unknown value") from exc
        delivery = value.get("delivery_state")
        current_raw = value.get("current_model")
        current = None if current_raw is None else ModelRef.from_mapping(current_raw)
        return cls(
            mission_id=mission_id,
            state=state,
            requirements=MissionRequirements.from_mapping(value.get("requirements")),
            delivery_state=None if delivery is None else _text(delivery, "mission.delivery_state"),
            current_model=current,
            estimated_extra_cost_usd=_decimal(
                value.get("estimated_extra_cost_usd"),
                "mission.estimated_extra_cost_usd",
            ),
            notes=str(value.get("notes", "")),
        )


# ---------------------------------------------------------------------------
# Availability and benchmark evidence
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class AvailabilityEvidence:
    ref: ModelRef
    status: AvailabilityStatus
    observed_at: datetime
    source: str
    cooldown_until: datetime | None = None
    note: str = ""

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "AvailabilityEvidence":
        if not isinstance(value, Mapping):
            raise ValueError("availability evidence: mapping required")
        ref_value = value.get("ref", value)
        if not isinstance(ref_value, Mapping):
            raise ValueError("availability.ref: mapping required")
        ref = ModelRef.from_mapping(ref_value)
        try:
            status = AvailabilityStatus(_text(value.get("status"), "availability.status").lower())
        except ValueError as exc:
            raise ValueError("availability.status: unknown value") from exc
        observed_at = _utc(value.get("observed_at"), "availability.observed_at")
        if observed_at is None:
            raise ValueError("availability.observed_at: required")
        source = _text(value.get("source"), "availability.source")
        cooldown = _utc(value.get("cooldown_until"), "availability.cooldown_until")
        if cooldown is not None and status is not AvailabilityStatus.LIMITS_REACHED:
            raise ValueError("availability.cooldown_until: only valid with limits_reached")
        return cls(
            ref=ref,
            status=status,
            observed_at=observed_at,
            source=source,
            cooldown_until=cooldown,
            note=str(value.get("note", "")),
        )


@dataclass(frozen=True)
class BenchmarkObservation:
    ref: ModelRef
    suite: str
    score: Decimal
    sample_size: int
    status: BenchmarkStatus
    source: str
    observed_at: datetime

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "BenchmarkObservation":
        if not isinstance(value, Mapping):
            raise ValueError("benchmark observation: mapping required")
        ref_value = value.get("ref", value)
        if not isinstance(ref_value, Mapping):
            raise ValueError("benchmark.ref: mapping required")
        ref = ModelRef.from_mapping(ref_value)
        suite = _text(value.get("suite"), "benchmark.suite")
        score = _decimal(value.get("score"), "benchmark.score", nonnegative=False)
        if score is None:
            raise ValueError("benchmark.score: required")
        sample_size = _int(value.get("sample_size"), "benchmark.sample_size")
        assert sample_size is not None
        try:
            status = BenchmarkStatus(_text(value.get("status"), "benchmark.status").lower())
        except ValueError as exc:
            raise ValueError("benchmark.status: unknown value") from exc
        source = _text(value.get("source"), "benchmark.source")
        observed_at = _utc(value.get("observed_at"), "benchmark.observed_at")
        if observed_at is None:
            raise ValueError("benchmark.observed_at: required")
        return cls(
            ref=ref,
            suite=suite,
            score=score,
            sample_size=sample_size,
            status=status,
            source=source,
            observed_at=observed_at,
        )


class BenchmarkProvider(Protocol):
    """Future interface. The engine only consumes observations; it never calls a model."""

    def observations(self) -> Sequence[BenchmarkObservation]:
        ...


# ---------------------------------------------------------------------------
# Evaluation and result
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CandidateEvaluation:
    ref: ModelRef
    cost_category: CostCategory
    context_window_tokens: int | None
    availability: str
    eligible: bool
    reasons: tuple[str, ...]
    uncertainties: tuple[str, ...]
    benchmark_score: Decimal | None
    rank_key: tuple[Any, ...] | None
    rank_reason: tuple[str, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "ref": self.ref.as_dict(),
            "ref_string": str(self.ref),
            "cost_category": self.cost_category.value,
            "context_window_tokens": self.context_window_tokens,
            "availability": self.availability,
            "eligible": self.eligible,
            "reasons": list(self.reasons),
            "uncertainties": list(self.uncertainties),
            "benchmark_score": None if self.benchmark_score is None else str(self.benchmark_score),
            "rank": list(self.rank_reason),
        }


@dataclass(frozen=True)
class SessionChangeIntent:
    model: ModelRef | None
    reason: str
    requires_separate_authorization: bool = True
    executed: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {
            "model": None if self.model is None else self.model.as_dict(),
            "reason": self.reason,
            "requires_separate_authorization": self.requires_separate_authorization,
            "executed": self.executed,
        }


@dataclass(frozen=True)
class Recommendation:
    decision: Decision
    selected: ModelRef | None
    current_model: ModelRef | None
    change_required: bool
    fallback_candidate: ModelRef | None
    requires_authorization: bool
    authorized: bool
    authorization_reason: str | None
    ranked: tuple[CandidateEvaluation, ...]
    excluded: tuple[CandidateEvaluation, ...]
    reason_codes: tuple[str, ...]
    uncertainties: tuple[str, ...]
    explanation: str
    mission_id: str
    mission_state: str
    policy_summary: dict[str, Any]
    inventory_source: str | None
    inventory_observed_at: datetime | None
    evaluated_at: datetime
    benchmark_used: bool
    session_change_intent: SessionChangeIntent | None
    selection_only: bool = True
    execution_performed: bool = False
    session_change_performed: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {
            "decision": self.decision.value,
            "selected": None if self.selected is None else self.selected.as_dict(),
            "selected_string": None if self.selected is None else str(self.selected),
            "current_model": None if self.current_model is None else self.current_model.as_dict(),
            "change_required": self.change_required,
            "fallback_candidate": None
            if self.fallback_candidate is None
            else self.fallback_candidate.as_dict(),
            "fallback_candidate_string": None
            if self.fallback_candidate is None
            else str(self.fallback_candidate),
            "requires_authorization": self.requires_authorization,
            "authorized": self.authorized,
            "authorization_reason": self.authorization_reason,
            "mission_id": self.mission_id,
            "mission_state": self.mission_state,
            "policy": self.policy_summary,
            "ranked": [item.as_dict() for item in self.ranked],
            "excluded": [item.as_dict() for item in self.excluded],
            "reason_codes": list(self.reason_codes),
            "uncertainties": list(self.uncertainties),
            "explanation": self.explanation,
            "selection_only": self.selection_only,
            "execution_performed": self.execution_performed,
            "session_change_performed": self.session_change_performed,
            "session_change_intent": None
            if self.session_change_intent is None
            else self.session_change_intent.as_dict(),
            "inventory_source": self.inventory_source,
            "inventory_observed_at": _iso(self.inventory_observed_at),
            "evaluated_at": _iso(self.evaluated_at),
            "benchmark_used": self.benchmark_used,
        }


# ---------------------------------------------------------------------------
# Deterministic engine
# ---------------------------------------------------------------------------


class ModelPolicyEngine:
    """Pure recommendation engine. One instance holds only an immutable policy."""

    def __init__(self, policy: Policy | Mapping[str, Any] | None = None) -> None:
        self.policy = Policy.from_value(policy or {})

    def recommend(
        self,
        inventory: Inventory | Mapping[str, Any] | Sequence[Mapping[str, Any]],
        mission: Mission | Mapping[str, Any],
        availability: Sequence[AvailabilityEvidence | Mapping[str, Any]] = (),
        benchmark: Sequence[BenchmarkObservation | Mapping[str, Any]] = (),
        evaluated_at: datetime | str | None = None,
    ) -> Recommendation:
        inv = Inventory.from_value(inventory)
        mis = Mission.from_value(mission)
        now = _utc(evaluated_at, "evaluated_at") or datetime.now(timezone.utc)
        if isinstance(availability, Mapping):
            availability = (availability,)
        if isinstance(benchmark, Mapping):
            benchmark = (benchmark,)
        evidence = tuple(
            item if isinstance(item, AvailabilityEvidence) else AvailabilityEvidence.from_mapping(item)
            for item in availability
        )
        observations = tuple(
            item if isinstance(item, BenchmarkObservation) else BenchmarkObservation.from_mapping(item)
            for item in benchmark
        )
        policy_summary = self._policy_summary()

        if mis.is_unknown:
            reason = (
                "mission_state_unknown"
                if mis.state is MissionState.UNKNOWN
                else "delivery_unknown"
            )
            return Recommendation(
                decision=Decision.MISSION_UNKNOWN,
                selected=None,
                current_model=mis.current_model,
                change_required=False,
                fallback_candidate=None,
                requires_authorization=False,
                authorized=False,
                authorization_reason=None,
                ranked=(),
                excluded=(),
                reason_codes=(reason, "no_resend_until_reconciled"),
                uncertainties=(),
                explanation=(
                    f"decision=MISSION_UNKNOWN; mission={mis.mission_id}; "
                    f"reason={reason}; no model recommendation and no resend until "
                    "delivery state is reconciled with positive evidence"
                ),
                mission_id=mis.mission_id,
                mission_state=mis.state.value,
                policy_summary=policy_summary,
                inventory_source=inv.source,
                inventory_observed_at=inv.observed_at,
                evaluated_at=now,
                benchmark_used=False,
                session_change_intent=None,
            )

        benchmark_scores, benchmark_unverified, benchmark_used = self._benchmark_scores(
            observations, now
        )
        evaluations = tuple(
            self._evaluate(
                model,
                mis,
                evidence,
                now,
                benchmark_scores,
                benchmark_unverified,
            )
            for model in inv.models
        )
        eligible = tuple(
            sorted((item for item in evaluations if item.eligible), key=lambda item: item.rank_key)
        )
        excluded = tuple(sorted((item for item in evaluations if not item.eligible), key=lambda item: item.ref.sort_key))
        free = tuple(item for item in eligible if item.cost_category is CostCategory.FREE)
        fallback = tuple(
            item
            for item in eligible
            if item.cost_category in (CostCategory.INCLUDED, CostCategory.ADDITIONAL)
        )

        if free:
            return self._recommend_free(
                inv, mis, now, eligible, excluded, free, benchmark_used, policy_summary
            )
        if fallback:
            return self._recommend_fallback(
                inv, mis, now, eligible, excluded, fallback, benchmark_used, policy_summary
            )
        return self._recommend_none(
            inv, mis, now, eligible, excluded, evaluations, benchmark_used, policy_summary
        )

    # -- evidence resolution -------------------------------------------------

    def _benchmark_scores(
        self,
        observations: Sequence[BenchmarkObservation],
        now: datetime,
    ) -> tuple[dict[ModelRef, Decimal], set[ModelRef], bool]:
        if not self.policy.use_verified_benchmark or not self.policy.benchmark_suite:
            return {}, set(), False
        relevant = [
            item
            for item in observations
            if item.suite == self.policy.benchmark_suite and item.observed_at <= now
        ]
        unverified: set[ModelRef] = set()
        grouped: dict[ModelRef, list[BenchmarkObservation]] = {}
        for item in relevant:
            if item.status is BenchmarkStatus.UNVERIFIED:
                unverified.add(item.ref)
                continue
            grouped.setdefault(item.ref, []).append(item)
        scores: dict[ModelRef, Decimal] = {}
        for ref, items in grouped.items():
            latest_time = max(item.observed_at for item in items)
            latest = [item for item in items if item.observed_at == latest_time]
            latest_scores = {item.score for item in latest}
            if len(latest_scores) == 1:
                scores[ref] = latest_scores.pop()
            else:
                unverified.add(ref)
        return scores, unverified, bool(scores)

    def _resolve_availability(
        self,
        ref: ModelRef,
        evidence: Sequence[AvailabilityEvidence],
        now: datetime,
    ) -> tuple[str, str, AvailabilityEvidence | None]:
        items = [item for item in evidence if item.ref == ref]
        if not items:
            return "unknown", "availability_unknown", None
        past_items = [item for item in items if item.observed_at <= now]
        if not past_items:
            return "unknown", "availability_evidence_future", None
        items = past_items
        latest_time = max(item.observed_at for item in items)
        latest = [item for item in items if item.observed_at == latest_time]
        statuses = {item.status for item in latest}
        if len(statuses) > 1:
            return "unknown", "conflicting_availability_evidence", None
        if len(latest) > 1 and len({item.cooldown_until for item in latest}) > 1:
            return "unknown", "conflicting_availability_evidence", None
        item = sorted(latest, key=lambda entry: (entry.source, entry.note))[0]
        if item.status is AvailabilityStatus.AVAILABLE:
            return "available", "available", item
        if item.status is AvailabilityStatus.UNAVAILABLE:
            return "unavailable", "unavailable_confirmed", item
        if item.status is AvailabilityStatus.LIMITS_REACHED:
            if item.cooldown_until is None:
                return "unavailable", "limits_reached_no_renewal_evidence", item
            if now < item.cooldown_until:
                return "unavailable", "cooldown_active", item
            return "unknown", "cooldown_elapsed_without_renewal_evidence", item
        return "unknown", "availability_unknown", item

    # -- per-model evaluation ------------------------------------------------

    def _evaluate(
        self,
        model: Model,
        mission: Mission,
        evidence: Sequence[AvailabilityEvidence],
        now: datetime,
        benchmark_scores: Mapping[ModelRef, Decimal],
        benchmark_unverified: set[ModelRef],
    ) -> CandidateEvaluation:
        reasons: list[str] = []
        uncertainties: list[str] = []

        availability, availability_reason, _ = self._resolve_availability(
            model.ref, evidence, now
        )
        if availability == "unavailable":
            reasons.append(availability_reason)
        elif availability == "unknown":
            if self.policy.allow_unknown_availability:
                uncertainties.append(availability_reason)
            else:
                reasons.append(availability_reason)

        if model.cost_category is CostCategory.UNKNOWN:
            reasons.append("cost_unknown")
        elif model.cost_category is CostCategory.INCLUDED and not self.policy.allow_included_franchise:
            reasons.append("included_not_allowed")
        elif model.cost_category is CostCategory.ADDITIONAL and not self.policy.allow_additional_consumption:
            reasons.append("additional_not_allowed")

        requirements = mission.requirements
        if requirements.min_context_tokens is not None:
            if model.context_window_tokens is None:
                reasons.append("context_unknown_required")
            elif model.context_window_tokens < requirements.min_context_tokens:
                reasons.append("context_below_requirement")
        elif model.context_window_tokens is None:
            if self.policy.allow_unknown_context:
                uncertainties.append("context_unknown")
            else:
                reasons.append("context_unknown_not_allowed")

        preferred = (
            model.context_window_tokens is not None
            and model.context_window_tokens >= self.policy.preferred_context_tokens
        )
        if model.context_window_tokens is not None and not preferred:
            uncertainties.append("context_below_preferred")

        if requirements.requires_tools:
            if model.tools is None:
                reasons.append("tools_unknown")
            elif not model.tools:
                reasons.append("tools_not_supported")

        if requirements.required_modalities:
            if model.modalities is None:
                reasons.append("modality_unknown")
            else:
                missing_modalities = sorted(requirements.required_modalities - model.modalities)
                if missing_modalities:
                    reasons.append("modality_missing:" + ",".join(missing_modalities))

        if requirements.required_capabilities:
            if model.capabilities is None:
                reasons.append("capability_unknown")
            else:
                missing_capabilities = sorted(
                    requirements.required_capabilities - model.capabilities
                )
                if missing_capabilities:
                    reasons.append("capability_missing:" + ",".join(missing_capabilities))

        if model.ref in benchmark_unverified:
            uncertainties.append("benchmark_unverified_ignored")

        benchmark_score = benchmark_scores.get(model.ref)
        reasons_sorted = tuple(sorted(set(reasons)))
        uncertainties_sorted = tuple(sorted(set(uncertainties)))
        eligible = not reasons_sorted

        rank_key: tuple[Any, ...] | None = None
        rank_reason: tuple[str, ...] = ()
        if eligible:
            cost_rank = {
                CostCategory.FREE: 0,
                CostCategory.INCLUDED: 1,
                CostCategory.ADDITIONAL: 2,
            }[model.cost_category]
            availability_rank = 0 if availability == "available" else 1
            preferred_rank = 0 if preferred else 1
            known_context_rank = 0 if model.context_window_tokens is not None else 1
            benchmark_key = (
                (0, -benchmark_score)
                if benchmark_score is not None
                else (1, Decimal("0"))
            )
            rank_key = (
                cost_rank,
                availability_rank,
                preferred_rank,
                known_context_rank,
                benchmark_key,
                model.ref.sort_key,
            )
            rank_reason = (
                f"cost={model.cost_category.value}",
                f"availability={availability}",
                f"preferred_context={'yes' if preferred else 'no'}",
                f"context_known={'yes' if model.context_window_tokens is not None else 'no'}",
                "benchmark=" + (str(benchmark_score) if benchmark_score is not None else "absent"),
                f"ref={model.ref}",
            )

        return CandidateEvaluation(
            ref=model.ref,
            cost_category=model.cost_category,
            context_window_tokens=model.context_window_tokens,
            availability=availability,
            eligible=eligible,
            reasons=reasons_sorted,
            uncertainties=uncertainties_sorted,
            benchmark_score=benchmark_score,
            rank_key=rank_key,
            rank_reason=rank_reason,
        )

    # -- decision branches ---------------------------------------------------

    def _recommend_free(
        self,
        inv: Inventory,
        mission: Mission,
        now: datetime,
        eligible: tuple[CandidateEvaluation, ...],
        excluded: tuple[CandidateEvaluation, ...],
        free: tuple[CandidateEvaluation, ...],
        benchmark_used: bool,
        policy_summary: dict[str, Any],
    ) -> Recommendation:
        top = free[0]
        current = mission.current_model
        # F1 — preserve an adequate, available free current model even when a
        # larger context is ranked higher: no unnecessary context reduction.
        current_free = next((item for item in free if item.ref == current), None)
        if current_free is not None and current_free.availability == "available":
            top = current_free
        keep = current is not None and current == top.ref
        if not self.policy.rotation_enabled and current is not None and current != top.ref:
            # Rotation disabled: never auto-replace; ask for authorization.
            return Recommendation(
                decision=Decision.NEEDS_AUTHORIZATION,
                selected=None,
                current_model=current,
                change_required=False,
                fallback_candidate=top.ref,
                requires_authorization=True,
                authorized=False,
                authorization_reason="rotation_disabled",
                ranked=eligible,
                excluded=excluded,
                reason_codes=("automatic_rotation_disabled", "rotation_requires_authorization"),
                uncertainties=tuple(top.uncertainties),
                explanation=(
                    f"rotation_disabled:current={canonical_model_ref(current)};"
                    f"fallback={canonical_model_ref(top.ref)}"
                ),
                mission_id=mission.mission_id,
                mission_state=mission.state.value,
                policy_summary=policy_summary,
                inventory_source=inv.source,
                inventory_observed_at=inv.observed_at,
                evaluated_at=now,
                benchmark_used=benchmark_used,
                session_change_intent=None,
            )
        decision = Decision.KEEP_CURRENT if keep else Decision.RECOMMEND_FREE
        reasons: list[str] = []
        if keep:
            reasons.append("kept_current")
        uncertainties = list(top.uncertainties)
        reasons.append(
            "selected_free_preferred_context"
            if top.context_window_tokens is not None
            and top.context_window_tokens >= self.policy.preferred_context_tokens
            else "selected_free_best_available"
        )
        if top.availability != "available":
            reasons.append("selected_free_unverified_availability")
        if current is not None and current != top.ref:
            reasons.append("current_not_selected")
            if any(item.ref == current for item in excluded):
                reasons.append("current_not_eligible")
                current_eval = next(item for item in excluded if item.ref == current)
                if any(
                    reason
                    in {
                        "unavailable_confirmed",
                        "cooldown_active",
                        "limits_reached_no_renewal_evidence",
                    }
                    for reason in current_eval.reasons
                ):
                    reasons.append("current_unavailable")
                elif any(
                    reason
                    in {
                        "availability_unknown",
                        "availability_evidence_future",
                        "conflicting_availability_evidence",
                        "cooldown_elapsed_without_renewal_evidence",
                    }
                    for reason in current_eval.reasons
                ):
                    reasons.append("current_availability_unknown")
            else:
                reasons.append("better_free_available")
        if current is not None and not any(item.ref == current for item in eligible):
            if not any(item.ref == current for item in excluded):
                uncertainties.append("current_not_in_inventory")
        change_required = current is None or current != top.ref
        intent = None
        if change_required:
            intent = SessionChangeIntent(
                model=top.ref,
                reason="selected_model_requires_separate_session_change",
            )
        return Recommendation(
            decision=decision,
            selected=top.ref,
            current_model=current,
            change_required=change_required,
            fallback_candidate=None,
            requires_authorization=False,
            authorized=False,
            authorization_reason=None,
            ranked=eligible,
            excluded=excluded,
            reason_codes=tuple(sorted(set(reasons))),
            uncertainties=tuple(sorted(set(uncertainties))),
            explanation=self._explain(
                decision,
                mission,
                selected=top.ref,
                fallback=None,
                ranked=eligible,
                excluded=excluded,
            ),
            mission_id=mission.mission_id,
            mission_state=mission.state.value,
            policy_summary=policy_summary,
            inventory_source=inv.source,
            inventory_observed_at=inv.observed_at,
            evaluated_at=now,
            benchmark_used=benchmark_used,
            session_change_intent=intent,
        )

    def _recommend_fallback(
        self,
        inv: Inventory,
        mission: Mission,
        now: datetime,
        eligible: tuple[CandidateEvaluation, ...],
        excluded: tuple[CandidateEvaluation, ...],
        fallback: tuple[CandidateEvaluation, ...],
        benchmark_used: bool,
        policy_summary: dict[str, Any],
    ) -> Recommendation:
        top = fallback[0]
        authorization = self.policy.paid_authorization
        if authorization is None:
            valid, auth_reason = False, "authorization_missing"
        else:
            model = next(
                model
                for model in inv.models
                if model.ref == top.ref
            )
            valid, auth_reason = authorization.validate(
                now, mission, model, mission.estimated_extra_cost_usd
            )
        current = mission.current_model
        if valid:
            change_required = current is None or current != top.ref
            intent = None
            if change_required:
                intent = SessionChangeIntent(
                    model=top.ref,
                    reason="authorized_fallback_requires_separate_session_change",
                )
            return Recommendation(
                decision=Decision.RECOMMEND_AUTHORIZED_FALLBACK,
                selected=top.ref,
                current_model=current,
                change_required=change_required,
                fallback_candidate=top.ref,
                requires_authorization=True,
                authorized=True,
                authorization_reason=auth_reason,
                ranked=eligible,
                excluded=excluded,
                reason_codes=("selected_authorized_fallback",),
                uncertainties=top.uncertainties,
                explanation=self._explain(
                    Decision.RECOMMEND_AUTHORIZED_FALLBACK,
                    mission,
                    selected=top.ref,
                    fallback=top.ref,
                    ranked=eligible,
                    excluded=excluded,
                    authorization_reason=auth_reason,
                ),
                mission_id=mission.mission_id,
                mission_state=mission.state.value,
                policy_summary=policy_summary,
                inventory_source=inv.source,
                inventory_observed_at=inv.observed_at,
                evaluated_at=now,
                benchmark_used=benchmark_used,
                session_change_intent=intent,
            )
        return Recommendation(
            decision=Decision.NEEDS_AUTHORIZATION,
            selected=None,
            current_model=current,
            change_required=False,
            fallback_candidate=top.ref,
            requires_authorization=True,
            authorized=False,
            authorization_reason=auth_reason,
            ranked=eligible,
            excluded=excluded,
            reason_codes=("needs_authorization", "fallback_candidate_requires_authorization"),
            uncertainties=top.uncertainties,
            explanation=self._explain(
                Decision.NEEDS_AUTHORIZATION,
                mission,
                selected=None,
                fallback=top.ref,
                ranked=eligible,
                excluded=excluded,
                authorization_reason=auth_reason,
            ),
            mission_id=mission.mission_id,
            mission_state=mission.state.value,
            policy_summary=policy_summary,
            inventory_source=inv.source,
            inventory_observed_at=inv.observed_at,
            evaluated_at=now,
            benchmark_used=benchmark_used,
            session_change_intent=SessionChangeIntent(
                model=top.ref,
                reason="fallback_requires_authorization_before_session_change",
            ),
        )

    def _recommend_none(
        self,
        inv: Inventory,
        mission: Mission,
        now: datetime,
        eligible: tuple[CandidateEvaluation, ...],
        excluded: tuple[CandidateEvaluation, ...],
        evaluations: tuple[CandidateEvaluation, ...],
        benchmark_used: bool,
        policy_summary: dict[str, Any],
    ) -> Recommendation:
        unknown_reasons = {
            "availability_unknown",
            "availability_evidence_future",
            "conflicting_availability_evidence",
            "cooldown_elapsed_without_renewal_evidence",
            "context_unknown_required",
            "context_unknown_not_allowed",
            "cost_unknown",
            "modality_unknown",
            "capability_unknown",
            "tools_unknown",
            "benchmark_unverified_ignored",
        }
        has_unknown_evidence = any(
            any(reason in unknown_reasons for reason in item.reasons)
            for item in excluded
        )
        if not inv.models:
            decision = Decision.NO_ELIGIBLE_FREE
            reasons = ("inventory_empty",)
        elif has_unknown_evidence:
            decision = Decision.INSUFFICIENT_EVIDENCE
            reasons = ("insufficient_evidence", "unknown_evidence_present")
        else:
            decision = Decision.NO_ELIGIBLE_FREE
            reasons = ("no_eligible_free",)
        return Recommendation(
            decision=decision,
            selected=None,
            current_model=mission.current_model,
            change_required=False,
            fallback_candidate=None,
            requires_authorization=False,
            authorized=False,
            authorization_reason=None,
            ranked=eligible,
            excluded=excluded,
            reason_codes=tuple(sorted(set(reasons))),
            uncertainties=tuple(
                sorted({unc for item in evaluations for unc in item.uncertainties})
            ),
            explanation=self._explain(
                decision,
                mission,
                selected=None,
                fallback=None,
                ranked=eligible,
                excluded=excluded,
            ),
            mission_id=mission.mission_id,
            mission_state=mission.state.value,
            policy_summary=policy_summary,
            inventory_source=inv.source,
            inventory_observed_at=inv.observed_at,
            evaluated_at=now,
            benchmark_used=benchmark_used,
            session_change_intent=None,
        )

    # -- explanation / summary ----------------------------------------------

    def _policy_summary(self) -> dict[str, Any]:
        return {
            "free_first": True,
            "preferred_context_tokens": self.policy.preferred_context_tokens,
            "allow_unknown_context": self.policy.allow_unknown_context,
            "allow_unknown_availability": self.policy.allow_unknown_availability,
            "allow_included_franchise": self.policy.allow_included_franchise,
            "allow_additional_consumption": self.policy.allow_additional_consumption,
            "authorization_present": self.policy.paid_authorization is not None,
            "coordination_mode": self.policy.coordination_mode.value,
            "benchmark_suite": self.policy.benchmark_suite,
        }

    def _explain(
        self,
        decision: Decision,
        mission: Mission,
        *,
        selected: ModelRef | None,
        fallback: ModelRef | None,
        ranked: tuple[CandidateEvaluation, ...],
        excluded: tuple[CandidateEvaluation, ...],
        authorization_reason: str | None = None,
    ) -> str:
        parts = [f"decision={decision.value}", f"mission={mission.mission_id}"]
        if selected is not None:
            item = next((candidate for candidate in ranked if candidate.ref == selected), None)
            if item is not None:
                parts.append(
                    f"selected={selected} cost={item.cost_category.value} "
                    f"availability={item.availability} "
                    f"context={item.context_window_tokens if item.context_window_tokens is not None else 'unknown'}"
                )
            else:
                parts.append(f"selected={selected}")
        if fallback is not None:
            parts.append(f"fallback={fallback}")
        if authorization_reason:
            parts.append(f"authorization={authorization_reason}")
        if ranked:
            parts.append(
                "ranked="
                + ", ".join(
                    f"{item.ref}[{item.cost_category.value},{item.availability}]"
                    for item in ranked
                )
            )
        if excluded:
            parts.append(
                "excluded="
                + ", ".join(
                    f"{item.ref}[{'+'.join(item.reasons)}]"
                    for item in excluded
                )
            )
        parts.append(
            f"policy=free_first,preferred_context={self.policy.preferred_context_tokens},"
            f"unknown_availability={self.policy.allow_unknown_availability},"
            f"paid_included={self.policy.allow_included_franchise},"
            f"paid_additional={self.policy.allow_additional_consumption}"
        )
        return "; ".join(parts)


def recommend(
    inventory: Inventory | Mapping[str, Any] | Sequence[Mapping[str, Any]],
    mission: Mission | Mapping[str, Any],
    policy: Policy | Mapping[str, Any] | None = None,
    availability: Sequence[AvailabilityEvidence | Mapping[str, Any]] = (),
    benchmark: Sequence[BenchmarkObservation | Mapping[str, Any]] = (),
    evaluated_at: datetime | str | None = None,
) -> Recommendation:
    """Pure convenience wrapper. Same inputs + same evaluated_at => same result."""

    return ModelPolicyEngine(policy).recommend(
        inventory=inventory,
        mission=mission,
        availability=availability,
        benchmark=benchmark,
        evaluated_at=evaluated_at,
    )


# ---------------------------------------------------------------------------
# CLI (files only; no API, session, credential or LLM)
# ---------------------------------------------------------------------------


def _load_json(path: str) -> Any:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Deterministic Free First model selection (offline; no LLM calls)."
    )
    parser.add_argument("--inventory", required=True, help="inventory JSON file")
    parser.add_argument("--policy", required=True, help="policy JSON file")
    parser.add_argument("--mission", required=True, help="mission JSON file")
    parser.add_argument("--availability", help="availability evidence JSON file")
    parser.add_argument("--benchmark", help="benchmark observations JSON file")
    parser.add_argument("--evaluated-at", help="ISO-8601 UTC instant for cooldown evaluation")
    parser.add_argument("--json", action="store_true", help="print the full JSON result")
    args = parser.parse_args(argv)
    try:
        inventory = _load_json(args.inventory)
        policy = _load_json(args.policy)
        mission = _load_json(args.mission)
        availability = _load_json(args.availability) if args.availability else []
        benchmark = _load_json(args.benchmark) if args.benchmark else []
        result = recommend(
            inventory=inventory,
            policy=policy,
            mission=mission,
            availability=availability,
            benchmark=benchmark,
            evaluated_at=args.evaluated_at,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(result.as_dict(), ensure_ascii=False, indent=2))
    else:
        print(f"decision: {result.decision.value}")
        print(f"selected: {result.selected or '-'}")
        print(f"fallback: {result.fallback_candidate or '-'}")
        print(f"requires_authorization: {result.requires_authorization}")
        print(f"explanation: {result.explanation}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
