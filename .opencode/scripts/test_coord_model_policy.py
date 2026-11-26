#!/usr/bin/env python3
"""Offline tests for coord_model_policy.py (COORD-05B/P1).

No network, LLM, OpenCode API, session, credential, ADB or Android is used.
Run: python3 .opencode/scripts/test_coord_model_policy.py -v
"""

from __future__ import annotations

import json
import os
import sys
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from coord_model_policy import (  # noqa: E402
    Decision,
    ModelPolicyEngine,
    ModelRef,
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
        "requirements": {},
    }
    value.update(overrides)
    return value


def auth(*refs: dict, **overrides) -> dict:
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
    }
    value.update(overrides)
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
        self.assertEqual(Decision.RECOMMEND_FREE, result.decision)
        self.assertEqual("opencode/other-free#default", selected_string(result))
        self.assertIn("current_availability_unknown", result.reason_codes)

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
                paid_authorization=auth(ref("opencode-go", "deepseek-v4.1-flash")),
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
