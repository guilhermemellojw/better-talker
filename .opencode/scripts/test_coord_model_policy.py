#!/usr/bin/env python3
"""COORD-05B/P1 — tests for the deterministic model recommendation engine.

Offline only: no LLM call, no service contact, no clock, no session change.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from coord_model_policy import (  # noqa: E402
    ADDITIONAL,
    AVAILABLE,
    BLOCKED_PAID,
    BLOCKED_UNKNOWN_MISSION,
    FREE,
    INCLUDED,
    NO_FREE_OPTION,
    OK,
    UNKNOWN,
    UNAVAILABLE,
    Evidence,
    Mission,
    Model,
    Policy,
    Recommendation,
    describe,
    recommend,
)


def m(key, **kw):
    provider, model = key.split("/", 1)
    variant = None
    if "#" in model:
        model, variant = model.split("#", 1)
    base = dict(
        provider_id=provider,
        model_id=model,
        variant=variant,
        cost=FREE,
        context_tokens=1_200_000,
        availability=AVAILABLE,
        tools=frozenset({"text"}),
        modalities=frozenset({"text"}),
        capabilities=frozenset({"reasoning"}),
    )
    base.update(kw)
    return Model(**base)


class ModelPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policy = Policy()
        self.mission = Mission("COORD-05B/P1", min_context_tokens=100_000)
        self.evidence = Evidence(observed_at=100, source="injected-test")

    # 1/2 — free 1M preferred, small context ranked below
    def test_free_1m_preferred_over_small(self):
        big = m("opencode/big-free")
        small = m("opencode/small-free", context_tokens=200_000)
        r = recommend([small, big], self.policy, self.mission, self.evidence)
        self.assertEqual(OK, r.status)
        self.assertEqual(big.key, r.model)
        self.assertEqual(FREE, r.category)
        self.assertTrue(any(x.startswith("PREFERRED_1M:") for x in r.reasons))

    def test_only_small_context_free_is_still_recommended(self):
        small = m("opencode/small-free", context_tokens=200_000)
        r = recommend([small], self.policy, self.mission, self.evidence)
        self.assertEqual(small.key, r.model)
        self.assertFalse(any(x.startswith("PREFERRED_1M:") for x in r.reasons))

    # 3/4 — unavailable / limit reached excluded
    def test_unavailable_and_limit_excluded(self):
        good = m("opencode/good-free")
        bad = m("opencode/bad-free", availability=UNAVAILABLE)
        limited = m("opencode/limited-free", at_limit=True)
        r = recommend([bad, limited, good], self.policy, self.mission, self.evidence)
        self.assertEqual(good.key, r.model)
        self.assertTrue(any(x.startswith("UNAVAILABLE:") for x in r.reasons))
        self.assertTrue(any(x.startswith("LIMIT_REACHED:") for x in r.reasons))

    # 5 — unknown context explicit
    def test_unknown_context_explicit_and_not_inferred_as_1m(self):
        unknown = m("opencode/unknown-free", context_tokens=None)
        no_min = recommend([unknown], self.policy, Mission("generic"), self.evidence)
        self.assertEqual(unknown.key, no_min.model)
        self.assertIn("CONTEXT_UNKNOWN_RANKED_LOW", no_min.reasons)
        with_min = recommend([unknown], self.policy, self.mission, self.evidence)
        self.assertEqual(NO_FREE_OPTION, with_min.status)
        self.assertTrue(any(x.startswith("CONTEXT_UNKNOWN:") for x in with_min.reasons))

    # 6 — cost not inferred from name
    def test_name_free_does_not_prove_cost(self):
        named_free = m("opencode/free-named", cost=UNKNOWN)
        r = recommend([named_free], self.policy, self.mission, self.evidence)
        self.assertEqual(NO_FREE_OPTION, r.status)
        self.assertTrue(any(x.startswith("COST_UNKNOWN:") for x in r.reasons))

    # 7 — paid blocked vs authorized
    def test_paid_escalation_blocked_unless_authorized(self):
        paid = m("opencode-go/deepseek-v4.1-flash", cost=ADDITIONAL, context_tokens=1_000_000)
        blocked = recommend([paid], self.policy, self.mission, self.evidence)
        self.assertEqual(BLOCKED_PAID, blocked.status)
        self.assertTrue(blocked.escalation_blocked)
        self.assertFalse(any(x.startswith("PAID_AUTHORIZED") for x in blocked.reasons))
        allowed = Policy(paid_escalation_authorized=True, paid_authorization_ref="D-007")
        ok = recommend([paid], allowed, self.mission, self.evidence)
        self.assertEqual(paid.key, ok.model)
        self.assertEqual(ADDITIONAL, ok.category)
        self.assertTrue(any(x.startswith("PAID_AUTHORIZED:") for x in ok.reasons))

    # 8 — franchise included distinct from free
    def test_included_is_not_free_and_ranks_after_free(self):
        free = m("opencode/big-free")
        included = m("opencode-go/glm-5.3", cost=INCLUDED)
        both = recommend([included, free], self.policy, self.mission, self.evidence)
        self.assertEqual(free.key, both.model)
        only = recommend([included], self.policy, self.mission, self.evidence)
        self.assertEqual(INCLUDED, only.category)

    # 9 — ties deterministic
    def test_tie_is_deterministic_regardless_of_inventory_order(self):
        a, b = m("opencode/a-free"), m("opencode/b-free")
        one = recommend([a, b], self.policy, self.mission, self.evidence)
        two = recommend([b, a], self.policy, self.mission, self.evidence)
        self.assertEqual(one.model, two.model)
        self.assertEqual(one.reasons, two.reasons)
        self.assertEqual(describe(one), describe(two))

    # 10 — no eligible free option
    def test_no_free_option(self):
        paid = m("opencode-go/paid", cost=ADDITIONAL)
        r = recommend([paid], self.policy, self.mission, self.evidence)
        self.assertEqual(BLOCKED_PAID, r.status)
        self.assertIn("NO_ELIGIBLE_FREE_OPTION", r.reasons)
        empty = recommend([], self.policy, self.mission, self.evidence)
        self.assertEqual(NO_FREE_OPTION, empty.status)

    # 11 — unknown mission blocked
    def test_unknown_mission_never_recommends(self):
        r = recommend([m("opencode/big-free")], self.policy, Mission("UNKNOWN"), self.evidence)
        self.assertEqual(BLOCKED_UNKNOWN_MISSION, r.status)
        self.assertIsNone(r.model)
        self.assertTrue(r.escalation_blocked)

    # 12 — dynamic inventory changes
    def test_inventory_change_changes_recommendation(self):
        small = m("opencode/small-free", context_tokens=200_000)
        big = m("opencode/big-free")
        first = recommend([small], self.policy, self.mission, self.evidence)
        second = recommend([small, big], self.policy, self.mission, self.evidence)
        third = recommend([small], self.policy, self.mission, self.evidence)
        self.assertEqual(small.key, first.model)
        self.assertEqual(big.key, second.model)
        self.assertEqual(first.model, third.model)

    # additional controls from the 15 requirements
    def test_cooldown_needs_evidence(self):
        cooling = m("opencode/cooling-free", cooldown_until=200, cooldown_evidence="rate-limit")
        full = recommend([cooling], self.policy, self.mission, Evidence(observed_at=150))
        self.assertEqual(NO_FREE_OPTION, full.status)
        self.assertTrue(any(x.startswith("COOLDOWN_ACTIVE:") for x in full.reasons))
        no_evidence = recommend([cooling], self.policy, self.mission, Evidence())
        self.assertTrue(any(x.startswith("COOLDOWN_UNVERIFIED:") for x in no_evidence.reasons))

    def test_tools_modality_capability_requirements(self):
        limited = m("opencode/text-only")
        mission = Mission(
            "M", required_tools=frozenset({"browser"}),
            required_modalities=frozenset({"image"}),
            required_capabilities=frozenset({"vision"}),
        )
        r = recommend([limited], self.policy, mission, self.evidence)
        self.assertEqual(NO_FREE_OPTION, r.status)
        for code in ("TOOLS_UNVERIFIED:", "MODALITY_UNVERIFIED:", "CAPABILITY_UNVERIFIED:"):
            self.assertTrue(any(x.startswith(code) for x in r.reasons))

    def test_rotation_prefers_another_free_model(self):
        current = m("opencode/current-free")
        other = m("opencode/other-free")
        mission = Mission("M", current_model=current.key)
        r = recommend([current, other], self.policy, mission, self.evidence)
        self.assertEqual(other.key, r.model)
        self.assertTrue(r.rotation)

    def test_selection_never_executes_or_contacts_sessions(self):
        r = recommend([m("opencode/big-free")], self.policy, self.mission, self.evidence)
        self.assertIsInstance(r, Recommendation)
        self.assertFalse(r.executed)
        self.assertNotIn("POST", describe(r))


if __name__ == "__main__":
    unittest.main()
