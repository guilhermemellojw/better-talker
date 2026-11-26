#!/usr/bin/env python3
"""Offline tests for coord_model_policy.py (COORD-05B/P3).

No network, LLM, OpenCode API, session, credential, ADB or Android is used.
Run: python3 .opencode/scripts/test_coord_model_policy.py -v
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from dataclasses import replace
from decimal import Decimal

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from coord_model_policy import (  # noqa: E402
    AvailabilityEvidence,
    CostCategory,
    Decision,
    Inventory,
    Mission,
    MissionRequirements,
    Model,
    ModelPolicyEngine,
    ModelRef,
    PaidAuthorization,
    Policy,
    authorization_material_sha256_from_mapping,
    canonical_model_ref,
    recommend,
)

NOW = "2026-11-26T12:00:00Z"
OBSERVED = "2026-11-26T10:00:00Z"
LATER = "2026-11-27T00:00:00Z"
EARLIER = "2026-11-25T00:00:00Z"


def ref(provider_id: str = "opencode", model_id: str = "hy4-flash-free",
        variant: str | None = "default") -> dict:
    return {"provider_id": provider_id, "model_id": model_id, "variant": variant}


def model(
    provider_id: str = "opencode",
    model_id: str = "hy4-flash-free",
    variant: str | None = "default",
    *,
    context: int | None = 1_000_000,
    tools: bool | None = True,
    modalities: tuple[str, ...] | None = ("text",),
    capabilities: tuple[str, ...] | None = ("reasoning",),
    cost: str | None = "free",
    cost_source: str = "test-catalog",
) -> dict:
    value = {
        "provider_id": provider_id,
        "model_id": model_id,
        "variant": variant,
        "context_window_tokens": context,
        "tools": tools,
        "modalities": None if modalities is None else list(modalities),
        "capabilities": None if capabilities is None else list(capabilities),
    }
    if cost is not None:
        value["cost"] = {"category": cost, "source": cost_source, "observed_at": OBSERVED}
    return value


def inventory(*models: dict, source: str = "synthetic-test") -> dict:
    return {"source": source, "observed_at": OBSERVED, "version": "test", "models": list(models)}


def evidence(model_value: dict, status: str = "available",
             observed_at: str = OBSERVED, cooldown: str | None = None) -> dict:
    value = {
        "provider_id": model_value["provider_id"],
        "model_id": model_value["model_id"],
        "variant": model_value["variant"],
        "status": status,
        "observed_at": observed_at,
        "source": "synthetic-probe",
    }
    if cooldown is not None:
        value["cooldown_until"] = cooldown
    return value


def policy(**overrides) -> dict:
    value = {
        "free_first": True,
        "preferred_context_tokens": 1_000_000,
        "allow_unknown_context": True,
        "allow_unknown_availability": False,
        "allow_included_franchise": True,
        "allow_additional_consumption": False,
        "coordination_mode": "SHADOW",
    }
    value.update(overrides)
    return value


def mission(**overrides) -> dict:
    value = {
        "mission_id": "m1",
        "state": "PLANNED",
        "scope": "coord05b:test",
        "requirements": {},
    }
    value.update(overrides)
    return value


def auth(*refs: dict, estimated_extra_cost_usd: str = "0.10",
         **overrides) -> dict:
    value = {
        "authorized": True,
        "authorized_by": "maintainer",
        "scope": "coord05b:test",
        "mission_id": "m1",
        "categories": ["included", "additional"],
        "model_refs": list(refs),
        "max_cost_usd": "0.50",
        "expires_at": LATER,
        "evidence": "authorization-test-only",
        "purpose": "offline test",
        "external_validation": {
            "authority": "maintainer",
            "reference": "decision:D-007",
            "scope": "coord05b:test",
            "mission_id": "m1",
            "validated_at": OBSERVED,
        },
    }
    value.update(overrides)
    external = value.get("external_validation")
    if isinstance(external, dict):
        external = dict(external)
        # P3: a prova externa carrega o material financeiro integral e o
        # custo estimado efetivamente usado na avaliação.
        external["authority"] = value["authorized_by"]
        external["reference"] = value["evidence"]
        external["scope"] = value["scope"]
        external["mission_id"] = value["mission_id"]
        external["estimated_cost_ceiling_usd"] = value["max_cost_usd"]
        external["estimated_extra_cost_usd"] = estimated_extra_cost_usd
        external["material_sha256"] = authorization_material_sha256_from_mapping(
            value, estimated_extra_cost_usd
        )
        value["external_validation"] = external
    return value


def select(inv: dict, mis: dict | None = None, pol: dict | None = None,
           ev: list[dict] | None = None, bench: list[dict] | None = None):
    return recommend(
        inventory=inv,
        mission=mis or mission(),
        policy=pol or policy(),
        availability=ev or [],
        benchmark=bench or [],
        evaluated_at=NOW,
    )


def selected_string(result) -> str | None:
    return None if result.selected is None else str(result.selected)


class ModelPolicyTests(unittest.TestCase):
    def test_free_1m_is_preferred_over_smaller_free(self):
        one_m = model(model_id="hy4-flash-free", context=1_000_000)
        small = model(model_id="small-free", context=128_000)
        result = select(inventory(one_m, small), ev=[evidence(one_m), evidence(small)])
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/hy4-flash-free#default", selected_string(result))
        self.assertTrue(result.change_required)
        self.assertTrue(result.selection_only)
        self.assertFalse(result.session_change_performed)
        self.assertIsNotNone(result.session_change_intent)
        self.assertTrue(result.session_change_intent.requires_separate_authorization)
        self.assertFalse(result.session_change_intent.executed)

    def test_free_smaller_is_used_when_no_1m_exists(self):
        small = model(model_id="small-free", context=128_000)
        result = select(inventory(small), ev=[evidence(small)])
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/small-free#default", selected_string(result))
        self.assertIn("context_below_preferred", result.uncertainties)

    def test_unavailable_current_recommends_another_free(self):
        current = model(model_id="current-free", context=1_000_000)
        other = model(model_id="other-free", context=1_000_000)
        result = select(
            inventory(current, other),
            mis=mission(current_model=ref(model_id="current-free")),
            ev=[
                evidence(current, status="unavailable"),
                evidence(other, status="available"),
            ],
        )
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/other-free#default", selected_string(result))
        self.assertIn("current_unavailable", result.reason_codes)
        self.assertTrue(result.change_required)

    def test_limits_reached_rotates_to_another_free(self):
        limited = model(model_id="limited-free")
        other = model(model_id="other-free")
        result = select(
            inventory(limited, other),
            ev=[
                evidence(limited, status="limits_reached", cooldown=LATER),
                evidence(other, status="available"),
            ],
        )
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/other-free#default", selected_string(result))
        excluded = {str(item.ref): item for item in result.excluded}
        self.assertIn("opencode/limited-free#default", excluded)
        self.assertIn("cooldown_active", excluded["opencode/limited-free#default"].reasons)

    def test_cooldown_elapsed_without_renewal_evidence_is_unknown(self):
        limited = model(model_id="limited-free")
        inv = inventory(limited)
        ev = [evidence(limited, status="limits_reached", cooldown=EARLIER)]
        blocked = select(inv, ev=ev)
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, blocked.decision)
        self.assertIsNone(blocked.selected)
        self.assertIn(
            "cooldown_elapsed_without_renewal_evidence",
            {reason for item in blocked.excluded for reason in item.reasons},
        )

        allowed = select(inv, pol=policy(allow_unknown_availability=True), ev=ev)
        self.assertEqual(Decision.RECOMMEND_FREE, allowed.decision)
        self.assertIn("cooldown_elapsed_without_renewal_evidence", allowed.uncertainties)
        self.assertIn("selected_free_unverified_availability", allowed.reason_codes)

    def test_context_unknown_is_not_treated_as_1m(self):
        unknown = model(model_id="mystery-context-free", context=None)
        known = model(model_id="small-free", context=128_000)
        result = select(inventory(unknown, known), ev=[evidence(unknown), evidence(known)])
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/small-free#default", selected_string(result))
        self.assertNotEqual("opencode/mystery-context-free#default", selected_string(result))

    def test_context_unknown_with_required_context_is_insufficient(self):
        unknown = model(model_id="mystery-context-free", context=None)
        result = select(
            inventory(unknown),
            mis=mission(requirements={"min_context_tokens": 1_000_000}),
            ev=[evidence(unknown)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIsNone(result.selected)

    def test_unknown_context_allowed_only_explicitly(self):
        unknown = model(model_id="mystery-context-free", context=None)
        allowed = select(inventory(unknown), ev=[evidence(unknown)])
        self.assertEqual(Decision.RECOMMEND_FREE, allowed.decision)
        self.assertIn("context_unknown", allowed.uncertainties)

        blocked = select(
            inventory(unknown),
            pol=policy(allow_unknown_context=False),
            ev=[evidence(unknown)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, blocked.decision)
        self.assertIsNone(blocked.selected)

    def test_unknown_cost_is_never_treated_as_free(self):
        unknown_cost = model(model_id="mystery-free", cost=None)
        free = model(model_id="real-free")
        result = select(inventory(unknown_cost, free), ev=[evidence(unknown_cost), evidence(free)])
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/real-free#default", selected_string(result))
        excluded = {str(item.ref): item for item in result.excluded}
        self.assertIn("cost_unknown", excluded["opencode/mystery-free#default"].reasons)

        only_unknown = select(inventory(unknown_cost), ev=[evidence(unknown_cost)])
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, only_unknown.decision)
        self.assertIsNone(only_unknown.selected)

    def test_name_free_is_not_cost_evidence(self):
        named_free_but_paid = model(model_id="mega-free", cost="additional")
        real_free = model(model_id="real-free")
        result = select(
            inventory(named_free_but_paid, real_free),
            ev=[evidence(named_free_but_paid), evidence(real_free)],
        )
        self.assertEqual("opencode/real-free#default", selected_string(result))

        blocked = select(
            inventory(named_free_but_paid),
            pol=policy(allow_additional_consumption=False),
            ev=[evidence(named_free_but_paid)],
        )
        self.assertEqual(Decision.NO_ELIGIBLE_FREE, blocked.decision)
        self.assertIsNone(blocked.selected)

    def test_paid_additional_requires_authorization(self):
        paid = model(provider_id="opencode-go", model_id="premium", context=1_000_000,
                     cost="additional")
        inv = inventory(paid)
        ev = [evidence(paid)]
        pol = policy(allow_additional_consumption=True)
        result = select(inv, pol=pol, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertEqual("opencode-go/premium#default", str(result.fallback_candidate))
        self.assertTrue(result.requires_authorization)
        self.assertFalse(result.authorized)
        self.assertEqual("authorization_missing", result.authorization_reason)
        self.assertIsNotNone(result.session_change_intent)
        self.assertFalse(result.session_change_intent.executed)

        authorized_policy = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "premium")),
        )
        authorized = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=authorized_policy,
            ev=ev,
        )
        self.assertEqual(Decision.RECOMMEND_AUTHORIZED_FALLBACK, authorized.decision)
        self.assertEqual("opencode-go/premium#default", selected_string(authorized))
        self.assertTrue(authorized.authorized)
        self.assertEqual("authorization_valid", authorized.authorization_reason)

    def test_authorization_scope_model_and_category_are_exact(self):
        paid = model(provider_id="opencode-go", model_id="premium", cost="additional")
        inv = inventory(paid)
        ev = [evidence(paid)]
        wrong_model = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "other-model")),
        )
        result = select(inv, mis=mission(estimated_extra_cost_usd="0.10"),
                        pol=wrong_model, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_model_not_covered", result.authorization_reason)

        wrong_mission = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "premium"), mission_id="other"),
        )
        result = select(inv, mis=mission(estimated_extra_cost_usd="0.10"),
                        pol=wrong_mission, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_mission_mismatch", result.authorization_reason)

        wrong_category = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "premium"), categories=["included"]),
        )
        result = select(inv, mis=mission(estimated_extra_cost_usd="0.10"),
                        pol=wrong_category, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_category_not_covered", result.authorization_reason)

    def test_limits_without_cooldown_never_invents_renewal(self):
        limited = model(model_id="limited-free")
        result = select(
            inventory(limited),
            ev=[evidence(limited, status="limits_reached")],
        )
        self.assertEqual(Decision.NO_ELIGIBLE_FREE, result.decision)
        self.assertIsNone(result.selected)
        self.assertIn(
            "limits_reached_no_renewal_evidence",
            {reason for item in result.excluded for reason in item.reasons},
        )

    def test_current_availability_unknown_is_reported(self):
        current = model(model_id="current-free")
        other = model(model_id="other-free")
        result = select(
            inventory(current, other),
            mis=mission(current_model=ref(model_id="current-free")),
            ev=[evidence(other)],
        )
        # F4/P2.1: unknown availability is not a confirmed cause for replacement.
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIsNone(result.selected)
        self.assertFalse(result.change_required)
        self.assertIsNone(result.session_change_intent)
        self.assertEqual("opencode/other-free#default", str(result.fallback_candidate))
        self.assertIn("current_availability_unknown", result.reason_codes)
        self.assertIn("no_substitution_without_confirmed_cause", result.reason_codes)

    def test_unknown_tools_modalities_capabilities_are_not_assumed(self):
        unknown_tools = model(model_id="unknown-tools", tools=None)
        result = select(
            inventory(unknown_tools),
            mis=mission(requirements={"requires_tools": True}),
            ev=[evidence(unknown_tools)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIn(
            "tools_unknown",
            {reason for item in result.excluded for reason in item.reasons},
        )

        unknown_modality = model(model_id="unknown-modality", modalities=None)
        result = select(
            inventory(unknown_modality),
            mis=mission(requirements={"required_modalities": ["image"]}),
            ev=[evidence(unknown_modality)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIn(
            "modality_unknown",
            {reason for item in result.excluded for reason in item.reasons},
        )

        unknown_capability = model(model_id="unknown-capability", capabilities=None)
        result = select(
            inventory(unknown_capability),
            mis=mission(requirements={"required_capabilities": ["vision"]}),
            ev=[evidence(unknown_capability)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIn(
            "capability_unknown",
            {reason for item in result.excluded for reason in item.reasons},
        )

    def test_authorization_expiry_and_budget_are_enforced(self):
        paid = model(provider_id="opencode-go", model_id="premium", cost="additional")
        inv = inventory(paid)
        ev = [evidence(paid)]
        expired = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "premium"), expires_at=EARLIER),
        )
        result = select(inv, mis=mission(estimated_extra_cost_usd="0.10"), pol=expired, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_expired", result.authorization_reason)

        over_budget = policy(
            allow_additional_consumption=True,
            paid_authorization=auth(ref("opencode-go", "premium"), max_cost_usd="0.05"),
        )
        result = select(inv, mis=mission(estimated_extra_cost_usd="0.10"), pol=over_budget, ev=ev)
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_cost_exceeds_max", result.authorization_reason)

    def test_included_go_is_not_free(self):
        included = model(provider_id="opencode-go", model_id="deepseek-v4.1-flash",
                         cost="included")
        free = model(model_id="real-free")
        result = select(inventory(included, free), ev=[evidence(included), evidence(free)])
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/real-free#default", selected_string(result))

        only_included = select(inventory(included), ev=[evidence(included)])
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, only_included.decision)
        self.assertIsNone(only_included.selected)
        self.assertEqual("opencode-go/deepseek-v4.1-flash#default",
                         str(only_included.fallback_candidate))

        authorized = select(
            inventory(included),
            mis=mission(estimated_extra_cost_usd="0"),
            pol=policy(
                allow_included_franchise=True,
                paid_authorization=auth(
                    ref("opencode-go", "deepseek-v4.1-flash"),
                    estimated_extra_cost_usd="0",
                ),
            ),
            ev=[evidence(included)],
        )
        self.assertEqual(Decision.RECOMMEND_AUTHORIZED_FALLBACK, authorized.decision)
        self.assertEqual("opencode-go/deepseek-v4.1-flash#default", selected_string(authorized))

    def test_ties_are_lexicographic_and_stable(self):
        alpha = model(model_id="alpha-free")
        beta = model(model_id="beta-free")
        inv = inventory(beta, alpha)
        ev = [evidence(alpha), evidence(beta)]
        first = select(inv, ev=ev)
        second = select(inv, ev=ev)
        self.assertEqual("opencode/alpha-free#default", selected_string(first))
        self.assertEqual(selected_string(first), selected_string(second))
        self.assertEqual([str(item.ref) for item in first.ranked],
                         [str(item.ref) for item in second.ranked])

    def test_verified_benchmark_breaks_tie_and_unverified_is_ignored(self):
        alpha = model(model_id="alpha-free")
        beta = model(model_id="beta-free")
        inv = inventory(alpha, beta)
        ev = [evidence(alpha), evidence(beta)]
        verified = [{
            "provider_id": "opencode", "model_id": "beta-free", "variant": "default",
            "suite": "coord05", "score": "0.91", "sample_size": 20,
            "status": "verified", "source": "future-benchmark", "observed_at": OBSERVED,
        }]
        result = select(inv, pol=policy(benchmark_suite="coord05"), ev=ev, bench=verified)
        self.assertEqual("opencode/beta-free#default", selected_string(result))
        self.assertTrue(result.benchmark_used)

        unverified = [{**verified[0], "status": "unverified", "score": "0.99"}]
        ignored = select(inv, pol=policy(benchmark_suite="coord05"), ev=ev, bench=unverified)
        self.assertEqual("opencode/alpha-free#default", selected_string(ignored))
        self.assertFalse(ignored.benchmark_used)

    def test_no_free_option_requests_authorization_when_fallback_allowed(self):
        included = model(provider_id="opencode-go", model_id="deepseek-v4.1-flash",
                         cost="included")
        paid = model(provider_id="opencode-go", model_id="premium", cost="additional")
        result = select(
            inventory(included, paid),
            pol=policy(allow_included_franchise=True, allow_additional_consumption=True),
            ev=[evidence(included), evidence(paid)],
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertEqual("opencode-go/deepseek-v4.1-flash#default",
                         str(result.fallback_candidate))

    def test_no_free_and_no_fallback_is_blocked(self):
        included = model(provider_id="opencode-go", model_id="deepseek-v4.1-flash",
                         cost="included")
        result = select(
            inventory(included),
            pol=policy(allow_included_franchise=False),
            ev=[evidence(included)],
        )
        self.assertEqual(Decision.NO_ELIGIBLE_FREE, result.decision)
        self.assertIsNone(result.selected)

    def test_mission_unknown_never_recommends_or_resends(self):
        free = model(model_id="real-free")
        result = select(
            inventory(free),
            mis=mission(state="UNKNOWN"),
            ev=[evidence(free)],
        )
        self.assertEqual(Decision.MISSION_UNKNOWN, result.decision)
        self.assertIsNone(result.selected)
        self.assertIn("mission_state_unknown", result.reason_codes)
        self.assertIn("no_resend_until_reconciled", result.reason_codes)

        delivery_unknown = select(
            inventory(free),
            mis=mission(delivery_state="UNKNOWN"),
            ev=[evidence(free)],
        )
        self.assertEqual(Decision.MISSION_UNKNOWN, delivery_unknown.decision)
        self.assertIn("delivery_unknown", delivery_unknown.reason_codes)

    def test_dynamic_inventory_has_no_cached_state(self):
        one_m = model(model_id="hy4-flash-free")
        small = model(model_id="small-free", context=128_000)
        first = select(inventory(one_m), ev=[evidence(one_m)])
        second = select(inventory(small), ev=[evidence(small)])
        third = select(inventory(one_m), ev=[evidence(one_m)])
        self.assertEqual("opencode/hy4-flash-free#default", selected_string(first))
        self.assertEqual("opencode/small-free#default", selected_string(second))
        self.assertEqual(selected_string(first), selected_string(third))

    def test_tools_modalities_and_capabilities_are_hard_requirements(self):
        no_tools = model(model_id="no-tools-free", tools=False)
        with_tools = model(model_id="with-tools-free", tools=True)
        tools_result = select(
            inventory(no_tools, with_tools),
            mis=mission(requirements={"requires_tools": True}),
            ev=[evidence(no_tools), evidence(with_tools)],
        )
        self.assertEqual("opencode/with-tools-free#default", selected_string(tools_result))

        text_only = model(model_id="text-free", modalities=("text",))
        vision = model(model_id="vision-free", modalities=("text", "image"))
        modality_result = select(
            inventory(text_only, vision),
            mis=mission(requirements={"required_modalities": ["image"]}),
            ev=[evidence(text_only), evidence(vision)],
        )
        self.assertEqual("opencode/vision-free#default", selected_string(modality_result))

        basic = model(model_id="basic-free", capabilities=("text",))
        reasoning = model(model_id="reasoning-free", capabilities=("reasoning",))
        capability_result = select(
            inventory(basic, reasoning),
            mis=mission(requirements={"required_capabilities": ["reasoning"]}),
            ev=[evidence(basic), evidence(reasoning)],
        )
        self.assertEqual("opencode/reasoning-free#default", selected_string(capability_result))

    def test_unknown_availability_is_explicit(self):
        free = model(model_id="real-free")
        blocked = select(inventory(free))
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, blocked.decision)
        self.assertIsNone(blocked.selected)

        allowed = select(inventory(free), pol=policy(allow_unknown_availability=True))
        self.assertEqual(Decision.RECOMMEND_FREE, allowed.decision)
        self.assertIn("availability_unknown", allowed.uncertainties)
        self.assertIn("selected_free_unverified_availability", allowed.reason_codes)

        small = model(model_id="small-free", context=128_000)
        confirmed = select(
            inventory(free, small),
            pol=policy(allow_unknown_availability=True),
            ev=[evidence(small)],
        )
        self.assertEqual("opencode/small-free#default", selected_string(confirmed))

    def test_conflicting_availability_is_insufficient(self):
        free = model(model_id="real-free")
        result = select(
            inventory(free),
            ev=[
                evidence(free, status="available", observed_at=OBSERVED),
                evidence(free, status="unavailable", observed_at=OBSERVED),
            ],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIsNone(result.selected)
        self.assertIn(
            "conflicting_availability_evidence",
            {reason for item in result.excluded for reason in item.reasons},
        )

    def test_current_available_keeps_current(self):
        free = model(model_id="real-free")
        result = select(
            inventory(free),
            mis=mission(current_model=ref(model_id="real-free")),
            ev=[evidence(free)],
        )
        self.assertEqual(Decision.KEEP_CURRENT, result.decision)
        self.assertEqual("opencode/real-free#default", selected_string(result))
        self.assertFalse(result.change_required)
        self.assertIsNone(result.session_change_intent)

    def test_empty_inventory_is_no_eligible_free(self):
        result = select(inventory())
        self.assertEqual(Decision.NO_ELIGIBLE_FREE, result.decision)
        self.assertIn("inventory_empty", result.reason_codes)
        self.assertIsNone(result.selected)

    def test_result_is_json_serializable(self):
        free = model(model_id="real-free")
        result = select(inventory(free), ev=[evidence(free)])
        encoded = json.dumps(result.as_dict(), ensure_ascii=False)
        self.assertIn("RECOMMEND_FREE", encoded)

    def test_duplicate_refs_are_rejected(self):
        free = model(model_id="real-free")
        with self.assertRaises(ValueError):
            select(inventory(free, dict(free)), ev=[evidence(free)])

    def test_invalid_context_and_mode_are_rejected(self):
        invalid = model(model_id="bad-free", context=0)
        with self.assertRaises(ValueError):
            select(inventory(invalid))
        free = model(model_id="real-free")
        with self.assertRaises(ValueError):
            select(inventory(free), pol=policy(coordination_mode="ASSISTED"), ev=[evidence(free)])
        with self.assertRaises(ValueError):
            select(inventory(free), pol=policy(free_first=False), ev=[evidence(free)])

    def test_engine_is_reusable_without_mutating_inputs(self):
        free = model(model_id="real-free")
        inv = inventory(free)
        ev = [evidence(free)]
        policy_value = policy()
        before = json.dumps({"inventory": inv, "policy": policy_value, "evidence": ev}, sort_keys=True)
        engine = ModelPolicyEngine(policy_value)
        first = engine.recommend(inv, mission(), ev, evaluated_at=NOW)
        second = engine.recommend(inv, mission(), ev, evaluated_at=NOW)
        after = json.dumps({"inventory": inv, "policy": policy_value, "evidence": ev}, sort_keys=True)
        self.assertEqual(before, after)
        self.assertEqual(first.as_dict(), second.as_dict())


    # ---- COORD-05B/P2 — F1/F3/F4/F5 -------------------------------------

    def test_f1_rotation_disabled_never_auto_replaces_current(self):
        current = model(model_id="current-free")
        other = model(model_id="other-free")
        result = select(
            inventory(current, other),
            mis=mission(current_model=ref("opencode", "current-free")),
            pol=policy(rotation_enabled=False),
            ev=[evidence(current, status="unavailable"), evidence(other)],
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertEqual("opencode/other-free#default", str(result.fallback_candidate))

    def test_f1_current_available_with_small_context_is_preserved(self):
        current = model(model_id="current-free", context=200_000)
        big = model(model_id="big-free", context=2_000_000)
        result = select(
            inventory(current, big),
            mis=mission(current_model=ref("opencode", "current-free")),
            ev=[evidence(current), evidence(big)],
        )
        self.assertEqual(Decision.KEEP_CURRENT, result.decision)
        self.assertEqual("opencode/current-free#default", selected_string(result))
        self.assertTrue(any("kept_current" in code for code in result.reason_codes))

    def test_f3_textual_authorization_reference_is_not_financial_approval(self):
        paid = model(provider_id="opencode-go", model_id="paid-model", cost="additional")
        with self.assertRaises(ValueError):
            select(
                inventory(paid),
                mis=mission(estimated_extra_cost_usd="0"),
                pol=policy(allow_additional_consumption=True,
                           paid_authorization="D-007/aprovado"),
                ev=[evidence(paid)],
            )

    def test_f4_contradictory_cost_entries_never_enable_permissive_selection(self):
        free = model(model_id="contradictory", cost="free")
        paid = model(model_id="contradictory", cost="additional")
        with self.assertRaises(ValueError):
            select(inventory(free, paid), ev=[evidence(free)])

    def test_f5_canonical_ref_none_and_absent_variants(self):
        self.assertEqual("opencode/hy4-flash-free", canonical_model_ref(
            ModelRef("opencode", "hy4-flash-free", "none")))
        self.assertEqual("opencode/hy4-flash-free", canonical_model_ref(
            ModelRef("opencode", "hy4-flash-free", None)))
        self.assertEqual("opencode/hy4-flash-free#high", canonical_model_ref(
            ModelRef("opencode", "hy4-flash-free", "high")))
        self.assertNotEqual(
            canonical_model_ref(ModelRef("opencode", "hy4-flash-free", None)),
            canonical_model_ref(ModelRef("opencode", "hy4-flash-free", "default")),
        )

    # ---- COORD-05B/P2.1 — divergences F1-F5 -----------------------------

    def test_p21_f1_none_and_absent_variant_share_internal_identity(self):
        absent = ModelRef("opencode", "hy4-flash-free", None)
        none = ModelRef("opencode", "hy4-flash-free", "none")
        explicit = ModelRef("opencode", "hy4-flash-free", "high")

        self.assertEqual(absent, none)
        self.assertEqual(hash(absent), hash(none))
        self.assertEqual(absent.sort_key, none.sort_key)
        self.assertEqual(1, len({absent, none}))
        self.assertNotEqual(absent, explicit)
        self.assertNotEqual(hash(absent), hash(explicit))

        observation = AvailabilityEvidence.from_mapping({
            "provider_id": "opencode",
            "model_id": "hy4-flash-free",
            "variant": "none",
            "status": "available",
            "observed_at": OBSERVED,
            "source": "synthetic-probe",
        })
        self.assertEqual(absent, observation.ref)

    def test_p21_f1_canonical_identity_binds_evidence_and_authorization(self):
        free = model(model_id="canonical-free", variant=None)
        free_result = select(
            inventory(free),
            ev=[{
                "provider_id": "opencode",
                "model_id": "canonical-free",
                "variant": "none",
                "status": "available",
                "observed_at": OBSERVED,
                "source": "synthetic-probe",
            }],
        )
        self.assertEqual(Decision.RECOMMEND_FREE, free_result.decision)
        self.assertEqual("opencode/canonical-free", selected_string(free_result))

        paid = model(provider_id="opencode-go", model_id="canonical-paid",
                     variant=None, cost="additional")
        authorized = select(
            inventory(paid),
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=auth(
                    ref("opencode-go", "canonical-paid", variant="none")
                ),
            ),
            ev=[evidence(paid)],
        )
        self.assertEqual(
            Decision.RECOMMEND_AUTHORIZED_FALLBACK, authorized.decision
        )
        self.assertEqual("opencode-go/canonical-paid", selected_string(authorized))

    def test_p21_f1_duplicate_after_canonicalization_is_rejected(self):
        absent = model(model_id="same-free", variant=None)
        none = model(model_id="same-free", variant="none")
        with self.assertRaises(ValueError):
            select(inventory(absent, none), ev=[evidence(absent)])

    def test_p21_f2_specific_authorization_requires_mission_id(self):
        with self.assertRaises(ValueError):
            PaidAuthorization.from_mapping(
                auth(ref("opencode-go", "premium"), mission_id=None)
            )

    def test_p21_f2_authorization_scope_is_compared_with_mission(self):
        paid = model(provider_id="opencode-go", model_id="premium",
                     cost="additional")
        result = select(
            inventory(paid),
            mis=mission(scope="coord05b:other", estimated_extra_cost_usd="0.10"),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=auth(ref("opencode-go", "premium")),
            ),
            ev=[evidence(paid)],
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertEqual("authorization_scope_mismatch", result.authorization_reason)

    def test_p21_f2_missing_external_validation_is_not_authorization(self):
        paid = model(provider_id="opencode-go", model_id="premium",
                     cost="additional")
        result = select(
            inventory(paid),
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=auth(
                    ref("opencode-go", "premium"),
                    external_validation=None,
                ),
            ),
            ev=[evidence(paid)],
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertFalse(result.authorized)
        self.assertEqual(
            "authorization_external_validation_missing",
            result.authorization_reason,
        )

    def test_p21_f2_free_with_positive_price_is_rejected(self):
        contradictory = model(model_id="not-really-free", cost="free")
        contradictory["cost"]["input_usd_per_million"] = "0.01"
        with self.assertRaises(ValueError):
            select(inventory(contradictory), ev=[evidence(contradictory)])

    def test_p21_f2_contradictory_cost_categories_in_one_model_are_rejected(self):
        contradictory = model(model_id="double-cost", cost="free")
        contradictory["cost_category"] = "additional"
        with self.assertRaises(ValueError):
            select(inventory(contradictory), ev=[evidence(contradictory)])

    def test_p21_f2_paid_authorization_rejects_free_or_unknown_categories(self):
        for category in ("free", "unknown"):
            with self.assertRaises(ValueError):
                PaidAuthorization.from_mapping(
                    auth(ref("opencode-go", "premium"), categories=[category])
                )

    def test_p21_f3_mission_state_is_mandatory(self):
        free = model(model_id="real-free")
        with self.assertRaises(ValueError):
            select(
                inventory(free),
                mis={"mission_id": "m1", "requirements": {}},
                ev=[evidence(free)],
            )

    def test_p21_f3_invalid_mission_state_fails_closed(self):
        free = model(model_id="real-free")
        with self.assertRaises(ValueError):
            select(
                inventory(free),
                mis=mission(state="NOT_A_STATE"),
                ev=[evidence(free)],
            )

    def test_p21_f3_running_mission_never_emits_change_intent(self):
        free = model(model_id="real-free")
        result = select(
            inventory(free),
            mis=mission(state="RUNNING"),
            ev=[evidence(free)],
        )
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertIsNone(result.session_change_intent)
        self.assertIn(
            "change_intent_suppressed_mission_state", result.reason_codes
        )

    def test_p21_f4_rotation_disabled_blocks_fallback_replacement(self):
        current = model(model_id="current-free")
        paid = model(provider_id="opencode-go", model_id="premium",
                     cost="additional")
        result = select(
            inventory(current, paid),
            mis=mission(
                current_model=ref("opencode", "current-free"),
                estimated_extra_cost_usd="0.10",
            ),
            pol=policy(
                allow_additional_consumption=True,
                rotation_enabled=False,
                paid_authorization=auth(ref("opencode-go", "premium")),
            ),
            ev=[evidence(current, status="unavailable"), evidence(paid)],
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertIsNone(result.selected)
        self.assertEqual("opencode-go/premium#default",
                         str(result.fallback_candidate))
        self.assertEqual("rotation_disabled", result.authorization_reason)
        self.assertIsNone(result.session_change_intent)

    def test_p21_f4_rotation_disabled_still_allows_initial_selection(self):
        paid = model(provider_id="opencode-go", model_id="premium",
                     cost="additional")
        result = select(
            inventory(paid),
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(
                allow_additional_consumption=True,
                rotation_enabled=False,
                paid_authorization=auth(ref("opencode-go", "premium")),
            ),
            ev=[evidence(paid)],
        )
        self.assertEqual(
            Decision.RECOMMEND_AUTHORIZED_FALLBACK, result.decision
        )
        self.assertEqual("opencode-go/premium#default", selected_string(result))
        self.assertIsNotNone(result.session_change_intent)

    def test_p21_f4_unknown_availability_preserves_current_when_permitted(self):
        current = model(model_id="current-free")
        other = model(model_id="other-free")
        result = select(
            inventory(current, other),
            mis=mission(current_model=ref("opencode", "current-free")),
            pol=policy(allow_unknown_availability=True),
            ev=[evidence(other)],
        )
        self.assertEqual(Decision.KEEP_CURRENT, result.decision)
        self.assertEqual("opencode/current-free#default", selected_string(result))
        self.assertFalse(result.change_required)
        self.assertIsNone(result.session_change_intent)
        self.assertIn("current_availability_unknown", result.uncertainties)

    def test_p21_f4_unknown_current_never_authorizes_paid_substitution(self):
        current = model(model_id="current-free")
        paid = model(provider_id="opencode-go", model_id="premium",
                     cost="additional")
        result = select(
            inventory(current, paid),
            mis=mission(
                current_model=ref("opencode", "current-free"),
                estimated_extra_cost_usd="0.10",
            ),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=auth(ref("opencode-go", "premium")),
            ),
            ev=[evidence(paid)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIsNone(result.selected)
        self.assertFalse(result.change_required)
        self.assertIsNone(result.session_change_intent)
        self.assertEqual("opencode-go/premium#default",
                         str(result.fallback_candidate))
        self.assertIn("current_availability_unknown", result.reason_codes)

    def test_p21_f4_current_absent_from_inventory_is_not_confirmed_cause(self):
        other = model(model_id="other-free")
        result = select(
            inventory(other),
            mis=mission(current_model=ref("opencode", "current-free")),
            ev=[evidence(other)],
        )
        self.assertEqual(Decision.INSUFFICIENT_EVIDENCE, result.decision)
        self.assertIsNone(result.selected)
        self.assertFalse(result.change_required)
        self.assertIsNone(result.session_change_intent)
        self.assertEqual("opencode/other-free#default",
                         str(result.fallback_candidate))
        self.assertIn("current_not_in_inventory", result.reason_codes)
        self.assertIn("no_substitution_without_confirmed_cause",
                      result.reason_codes)

    @staticmethod
    def _typed_model(model_id: str, variant: str | None = None,
                     context: int = 1_000_000) -> Model:
        return Model(
            ref=ModelRef("opencode", model_id, variant),
            context_window_tokens=context,
            tools=True,
            modalities=frozenset({"text"}),
            capabilities=frozenset({"reasoning"}),
            cost_category=CostCategory.FREE,
        )

    def test_p21_f5_typed_inventory_duplicate_is_revalidated(self):
        first = self._typed_model("duplicate-free", None)
        second = self._typed_model("duplicate-free", "none")
        with self.assertRaises(ValueError):
            Inventory.from_value(Inventory(models=(first, second)))

    def test_p21_f5_typed_mission_invalid_state_is_revalidated(self):
        invalid = Mission(
            mission_id="m1",
            state="NOT_A_STATE",
            requirements=MissionRequirements(),
        )
        with self.assertRaises(ValueError):
            Mission.from_value(invalid)

    def test_p21_f5_typed_inventory_invalid_context_is_revalidated(self):
        invalid = self._typed_model("bad-context", context=0)
        with self.assertRaises(ValueError):
            Inventory.from_value(Inventory(models=(invalid,)))

    def test_p21_f5_typed_policy_incompatible_is_revalidated(self):
        with self.assertRaises(ValueError):
            Policy.from_value(Policy(free_first=False))
        with self.assertRaises(ValueError):
            Policy.from_value(Policy(coordination_mode="ASSISTED"))


    # ---- P3: vinculação integral da autorização financeira -----------------

    def _paid_fixture(self):
        paid = model(provider_id="opencode-go", model_id="premium", cost="additional")
        inventory_value = inventory(paid)
        availability = [evidence(paid)]
        authorization_value = auth(ref("opencode-go", "premium"))
        return inventory_value, availability, authorization_value

    def test_p3_fail_first_prova_inalterada_com_teto_alterado(self):
        inv, availability, base = self._paid_fixture()
        tampered = dict(base)
        tampered["max_cost_usd"] = "5.00"  # prova externa permanece a mesma
        result = select(
            inv,
            mis=mission(estimated_extra_cost_usd="4.00"),
            pol=policy(allow_additional_consumption=True, paid_authorization=tampered),
            ev=availability,
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertFalse(result.authorized)
        self.assertEqual("authorization_external_material_mismatch", result.authorization_reason)

    def test_p3_adulteracao_de_cada_campo_material(self):
        inv, availability, base = self._paid_fixture()
        cases = (
            ("max_cost_usd", lambda a: a.update(max_cost_usd="5.00"), {},
             "authorization_external_material_mismatch"),
            ("categories", lambda a: a.update(categories=["included"]), {},
             "authorization_external_material_mismatch"),
            ("model_refs_variant",
             lambda a: a.update(model_refs=[dict(a["model_refs"][0], variant="alt")]), {},
             "authorization_external_material_mismatch"),
            ("expires_at", lambda a: a.update(expires_at="2026-12-01T00:00:00Z"), {},
             "authorization_external_material_mismatch"),
            ("external_estimated_extra_cost_usd",
             lambda a: a["external_validation"].__setitem__(
                 "estimated_extra_cost_usd", "0.50"
             ), {},
             "authorization_external_estimated_cost_mismatch"),
            ("authority", lambda a: a.update(authorized_by="other"), {},
             "authorization_external_authority_mismatch"),
            ("reference", lambda a: a.update(evidence="outra-referencia"), {},
             "authorization_external_reference_mismatch"),
            ("mission_id", lambda a: a.update(mission_id="m2"), {"mission_id": "m2"},
             "authorization_external_mission_mismatch"),
            ("scope", lambda a: a.update(scope="coord05b:other"), {"scope": "coord05b:other"},
             "authorization_external_scope_mismatch"),
        )
        for label, mutate, mission_overrides, expected in cases:
            with self.subTest(field=label):
                changed = dict(base)
                mutate(changed)
                result = select(
                    inv,
                    mis=mission(estimated_extra_cost_usd="0.10", **mission_overrides),
                    pol=policy(allow_additional_consumption=True, paid_authorization=changed),
                    ev=availability,
                )
                self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
                self.assertFalse(result.authorized)
                self.assertEqual(expected, result.authorization_reason)

    def test_p3_actual_estimated_cost_must_be_bound_to_proof(self):
        inv, availability, base = self._paid_fixture()
        result = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.04"),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=base,
            ),
            ev=availability,
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertFalse(result.authorized)
        self.assertEqual(
            "authorization_external_material_mismatch",
            result.authorization_reason,
        )

    def test_p3_external_estimated_cost_is_required(self):
        inv, availability, base = self._paid_fixture()
        changed = dict(base)
        external = dict(base["external_validation"])
        del external["estimated_extra_cost_usd"]
        changed["external_validation"] = external
        result = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(
                allow_additional_consumption=True,
                paid_authorization=changed,
            ),
            ev=availability,
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual(
            "authorization_external_estimated_cost_missing",
            result.authorization_reason,
        )

    def test_p3_custo_estimado_vinculado_e_registrado(self):
        inv, availability, base = self._paid_fixture()
        changed = dict(base)
        external = dict(base["external_validation"])
        external["estimated_cost_ceiling_usd"] = "0.05"
        changed["external_validation"] = external

        blocked = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(allow_additional_consumption=True, paid_authorization=changed),
            ev=availability,
        )
        self.assertEqual("authorization_estimated_cost_above_external_ceiling",
                         blocked.authorization_reason)
        self.assertEqual("0.10", blocked.authorization_estimated_cost_usd)
        self.assertIn("authorization_estimated_cost_usd", blocked.as_dict())

        allowed_auth = auth(
            ref("opencode-go", "premium"),
            estimated_extra_cost_usd="0.04",
        )
        allowed_external = dict(allowed_auth["external_validation"])
        allowed_external["estimated_cost_ceiling_usd"] = "0.05"
        allowed_auth["external_validation"] = allowed_external
        allowed = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.04"),
            pol=policy(allow_additional_consumption=True, paid_authorization=allowed_auth),
            ev=availability,
        )
        self.assertEqual(Decision.RECOMMEND_AUTHORIZED_FALLBACK, allowed.decision)
        self.assertEqual("0.04", allowed.authorization_estimated_cost_usd)

    def test_p3_digest_nao_e_assinatura_nem_autoridade_autenticada(self):
        inv, availability, base = self._paid_fixture()
        changed = dict(base)
        external = dict(base["external_validation"])
        external["material_sha256"] = "0" * 64
        changed["external_validation"] = external
        result = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=policy(allow_additional_consumption=True, paid_authorization=changed),
            ev=availability,
        )
        self.assertEqual("authorization_external_material_mismatch", result.authorization_reason)
        self.assertFalse(any("signature" in key for key in result.as_dict()))

    def test_p3_objeto_tipado_nao_contorna_vinculacao(self):
        inv, availability, base = self._paid_fixture()
        typed = PaidAuthorization.from_mapping(base)
        changed = replace(typed, max_cost_usd=Decimal("5.00"))
        typed_policy = Policy.from_value(Policy(
            allow_additional_consumption=True, paid_authorization=changed,
        ))
        result = select(
            inv,
            mis=mission(estimated_extra_cost_usd="0.10"),
            pol=typed_policy,
            ev=availability,
        )
        self.assertEqual(Decision.NEEDS_AUTHORIZATION, result.decision)
        self.assertEqual("authorization_external_material_mismatch", result.authorization_reason)


if __name__ == "__main__":
    unittest.main(verbosity=2)
