#!/usr/bin/env python3
"""Offline tests for coord_worktrees.py (COORD-05D/P1).

No cleanup, no Git mutation, no ADB, no network and no LLM. Temporary Git
repositories are created only to exercise the read-only scanner.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from coord_worktrees import (  # noqa: E402
    CleanupClass,
    CleanupProposal,
    IntegrationState,
    OwnerInfo,
    PRIndex,
    PRInfo,
    PRState,
    Policy,
    QAInfo,
    SessionInfo,
    StorageBand,
    StorageThresholds,
    WorktreeSnapshot,
    WorktreeState,
    _git,
    build_report,
    classify_worktree,
    parse_worktree_porcelain,
    scan_worktrees,
    validate_proposal,
)

NOW = datetime(2026, 11, 26, 12, 0, tzinfo=timezone.utc)
HEAD = "a" * 40
OTHER_HEAD = "b" * 40
PATH = "/repo/wt"


def snapshot(**overrides) -> WorktreeSnapshot:
    base = dict(
        path=PATH,
        branch="feat/wt",
        head=HEAD,
        detached=False,
        main=False,
        locked=False,
        prunable=False,
        missing=False,
        symlink=False,
        external=False,
        healthagentforms=False,
        staged=0,
        unstaged=0,
        untracked=0,
        local_only_commits=False,
        ancestor_main=False,
        remote_reachable=True,
        last_commit_at=NOW - timedelta(days=3),
        process_count=0,
        process_scan_complete=True,
        size_bytes=1024,
        size_method="test",
    )
    base.update(overrides)
    return WorktreeSnapshot(**base)


def pr(
    number: int = 1,
    state: str = "MERGED",
    *,
    merged_hours_ago: float = 49,
    certified: bool | None = True,
    head: str = HEAD,
    branch: str = "feat/wt",
    draft: bool = False,
) -> PRInfo:
    merged_at = NOW - timedelta(hours=merged_hours_ago) if state == "MERGED" else None
    return PRInfo(
        number=number,
        title=f"PR {number}",
        state=PRState(state),
        is_draft=draft,
        head_ref=branch,
        head_oid=head,
        merged_at=merged_at,
        certified=certified,
        url=f"https://example/pr/{number}",
    )


def classify(
    value: WorktreeSnapshot | None = None,
    *,
    policy: Policy | None = None,
    prs: list[PRInfo] | None = None,
    owners: dict[str, OwnerInfo] | None = None,
    sessions: dict[str, SessionInfo] | None = None,
    qa: dict[str, QAInfo] | None = None,
    certifications: dict[int, bool] | None = None,
    session_coverage: bool = True,
    qa_coverage: bool = True,
):
    value = value or snapshot()
    owners = {PATH: OwnerInfo(owner="D", mission="test")} if owners is None else owners
    sessions = {} if sessions is None else sessions
    qa = {} if qa is None else qa
    certifications = {1: True} if certifications is None else certifications
    return classify_worktree(
        value,
        policy or Policy(project_roots=("/repo",)),
        PRIndex(prs if prs is not None else [pr()]),
        owners,
        sessions,
        qa,
        certifications,
        session_coverage_known=session_coverage,
        qa_coverage_known=qa_coverage,
        now=NOW,
    )


class WorktreePolicyTests(unittest.TestCase):
    def test_clean_merged_certified_after_48h_is_eligible(self):
        record = classify()
        self.assertEqual(WorktreeState.CLEANUP_ELIGIBLE, record.state)
        self.assertEqual(CleanupClass.B_ELIGIBLE, record.cleanup_class)
        self.assertTrue(record.eligible)
        self.assertEqual(IntegrationState.MERGED_PR, record.integration)
        self.assertEqual((), record.protection_reasons)

    def test_merged_certified_before_48h_is_retained(self):
        record = classify(prs=[pr(merged_hours_ago=47)])
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertIn("retention_not_due", record.protection_reasons)
        self.assertFalse(record.eligible)
        self.assertTrue(record.review_candidate)  # review after retention

    def test_open_and_draft_prs_are_protected(self):
        open_pr = classify(prs=[pr(state="OPEN", certified=None)])
        self.assertEqual(WorktreeState.MERGE_PENDING, open_pr.state)
        self.assertIn("pr_open", open_pr.protection_reasons)
        self.assertFalse(open_pr.eligible)
        draft = classify(prs=[pr(state="OPEN", draft=True, certified=None)])
        self.assertEqual(WorktreeState.QA_PENDING, draft.state)
        self.assertIn("pr_draft", draft.protection_reasons)
        self.assertFalse(draft.eligible)

    def test_closed_unmerged_is_not_integrated(self):
        record = classify(prs=[pr(state="CLOSED", certified=None)])
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertEqual(IntegrationState.CLOSED_UNMERGED, record.integration)
        self.assertIn("pr_closed_unmerged", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_changes_block_cleanup(self):
        for field in ("staged", "unstaged", "untracked"):
            record = classify(snapshot(**{field: 1}))
            self.assertFalse(record.eligible, field)
            self.assertIn(
                {"staged": "staged_changes", "unstaged": "unstaged_changes", "untracked": "untracked_files"}[field],
                record.protection_reasons,
            )

    def test_classified_untracked_does_not_hide_other_blockers(self):
        record = classify(snapshot(untracked=3, untracked_classified=True))
        # Classified untracked is no longer an automatic blocker, but the
        # cleanup decision remains review-only and all other evidence applies.
        self.assertNotIn("untracked_files", record.protection_reasons)
        self.assertTrue(record.eligible or record.review_candidate)

    def test_local_only_commits_block(self):
        record = classify(snapshot(remote_reachable=False, local_only_commits=True), prs=[])
        self.assertFalse(record.eligible)
        self.assertIn("local_only_commits", record.protection_reasons)

    def test_detached_head_is_unknown(self):
        record = classify(snapshot(branch=None, detached=True, ancestor_main=True), prs=[])
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertFalse(record.eligible)
        self.assertIn("mission_unknown", record.protection_reasons)

    def test_missing_owner_blocks_and_is_review_candidate(self):
        record = classify(owners={})
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("ownership_unknown", record.protection_reasons)
        self.assertFalse(record.eligible)
        self.assertTrue(record.review_candidate)

    def test_protected_pr_is_retained_even_after_merge(self):
        record = classify(
            policy=Policy(project_roots=("/repo",), protected_pr_numbers=(68,)),
            prs=[PRInfo(number=68, title="hotfix", state=PRState.MERGED,
                         head_ref="feat/wt", head_oid=HEAD,
                         merged_at=NOW - timedelta(hours=49), certified=True)],
            certifications={68: True},
        )
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertEqual(CleanupClass.C_PROTECTED, record.cleanup_class)
        self.assertIn("protected_pr", record.protection_reasons)
        self.assertFalse(record.eligible)
        self.assertFalse(record.review_candidate)

    def test_unique_qa_artifacts_block(self):
        record = classify(qa={PATH: QAInfo(evidence_known=True, unique_artifacts=True)})
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertIn("unique_qa_artifacts", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_missing_mission_blocks(self):
        record = classify(prs=[], owners={PATH: OwnerInfo(owner="D")}, sessions={}, qa={})
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("mission_unknown", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_active_session_blocks(self):
        record = classify(sessions={PATH: SessionInfo(evidence_known=True, active_count=1)})
        self.assertEqual(WorktreeState.ACTIVE, record.state)
        self.assertIn("session_linked", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_active_process_blocks(self):
        record = classify(snapshot(process_count=1))
        self.assertEqual(WorktreeState.ACTIVE, record.state)
        self.assertIn("process_in_use", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_active_process_dominates_untracked_and_unknown_mission(self):
        record = classify(
            snapshot(process_count=1, untracked=2, ancestor_main=True, remote_reachable=False),
            prs=[],
            owners={},
            sessions={},
            session_coverage=False,
            qa={},
            qa_coverage=False,
        )
        self.assertEqual(WorktreeState.ACTIVE, record.state)
        self.assertEqual(CleanupClass.C_PROTECTED, record.cleanup_class)
        self.assertFalse(record.eligible)
        self.assertIn("process_in_use", record.protection_reasons)

    def test_locked_blocks(self):
        record = classify(snapshot(locked=True))
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertIn("locked_worktree", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_symlink_blocks(self):
        record = classify(snapshot(symlink=True))
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("symlink_path", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_external_and_healthagentforms_are_class_e(self):
        external = classify(snapshot(path="/outside/wt", external=True), owners={})
        self.assertEqual(CleanupClass.E_EXTERNAL, external.cleanup_class)
        self.assertIn("external_path", external.protection_reasons)
        health = classify(snapshot(path="/repo/HealthAgentForms", healthagentforms=True), owners={})
        self.assertEqual(CleanupClass.E_EXTERNAL, health.cleanup_class)
        self.assertIn("healthagentforms", health.protection_reasons)

    def test_unknown_session_evidence_blocks(self):
        record = classify(sessions={}, session_coverage=False)
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("session_evidence_unknown", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_unknown_qa_evidence_blocks(self):
        record = classify(qa={}, qa_coverage=False)
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("qa_evidence_unknown", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_head_change_blocks(self):
        record = classify(snapshot(head=OTHER_HEAD))
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("head_changed", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_merged_but_uncertified_or_unknown(self):
        uncertified = classify(prs=[pr(certified=False)], certifications={})
        self.assertEqual(WorktreeState.RETAINED, uncertified.state)
        self.assertIn("qa_not_certified", uncertified.protection_reasons)
        self.assertFalse(uncertified.eligible)
        unknown = classify(prs=[pr(certified=None)], certifications={})
        self.assertEqual(WorktreeState.UNKNOWN, unknown.state)
        self.assertIn("certification_unknown", unknown.protection_reasons)
        self.assertFalse(unknown.eligible)

    def test_audit_closed_after_24h_is_eligible(self):
        record = classify_worktree(
            snapshot(ancestor_main=True, remote_reachable=False),
            Policy(project_roots=("/repo",)),
            PRIndex([]),
            {PATH: OwnerInfo(owner="D", mission="audit", audit_closed=True,
                             audit_closed_at=NOW - timedelta(hours=25))},
            {},
            {},
            {},
            session_coverage_known=True,
            qa_coverage_known=True,
            now=NOW,
        )
        self.assertEqual(WorktreeState.CLEANUP_ELIGIBLE, record.state)
        self.assertTrue(record.eligible)
        self.assertEqual(IntegrationState.ANCESTOR_MAIN, record.integration)

    def test_abandoned_after_7d_is_eligible(self):
        record = classify_worktree(
            snapshot(ancestor_main=True, remote_reachable=False),
            Policy(project_roots=("/repo",)),
            PRIndex([]),
            {PATH: OwnerInfo(owner="D", mission="experiment", abandoned=True,
                             abandoned_at=NOW - timedelta(days=8))},
            {},
            {},
            {},
            session_coverage_known=True,
            qa_coverage_known=True,
            now=NOW,
        )
        self.assertEqual(WorktreeState.CLEANUP_ELIGIBLE, record.state)
        self.assertTrue(record.eligible)

    def test_proposal_validation_rejects_head_change(self):
        record = classify()
        proposal = CleanupProposal(
            path=PATH,
            expected_head=HEAD,
            expected_owner="D",
            expected_state=WorktreeState.CLEANUP_ELIGIBLE,
            authorization_ref="auth-1",
            created_at=NOW,
        )
        ok, reasons = validate_proposal(
            proposal,
            record,
            current_process_count=0,
            current_session_count=0,
            current_staged=0,
            current_unstaged=0,
            current_untracked=0,
        )
        self.assertTrue(ok)
        self.assertEqual((), reasons)
        changed = replace(record, snapshot=replace(record.snapshot, head=OTHER_HEAD))
        ok, reasons = validate_proposal(
            proposal,
            changed,
            current_process_count=0,
            current_session_count=0,
            current_staged=0,
            current_unstaged=0,
            current_untracked=0,
        )
        self.assertFalse(ok)
        self.assertIn("head_changed", reasons)

    def test_storage_bands(self):
        thresholds = StorageThresholds()
        self.assertEqual(StorageBand.IDEAL, thresholds.band(50 * 1024**3))
        self.assertEqual(StorageBand.ATTENTION, thresholds.band(49 * 1024**3))
        self.assertEqual(StorageBand.ATTENTION, thresholds.band(30 * 1024**3))
        self.assertEqual(StorageBand.RESTRICTION, thresholds.band(29 * 1024**3))
        self.assertEqual(StorageBand.RESTRICTION, thresholds.band(20 * 1024**3))
        self.assertEqual(StorageBand.CRITICAL, thresholds.band(19 * 1024**3))
        self.assertEqual(StorageBand.CRITICAL, thresholds.band(10 * 1024**3))
        self.assertEqual(StorageBand.EMERGENCY, thresholds.band(9 * 1024**3))

    def test_report_deduplicates_nested_sizes_and_serializes(self):
        first = classify()
        nested = replace(
            first,
            snapshot=replace(first.snapshot, path=PATH + "/sub", size_bytes=512),
        )
        report = build_report(
            [first, nested],
            Policy(project_roots=("/repo",)),
            42 * 1024**3,
            main_path="/repo",
            generated_at=NOW,
        )
        self.assertEqual(1024, report["space"]["measured_bytes"])
        self.assertEqual(1024, report["space"]["eligible_bytes"])
        encoded = json.dumps(report, ensure_ascii=False)
        self.assertIn("CLEANUP_ELIGIBLE", encoded)

    def test_policy_rejects_assisted_and_delegated(self):
        with self.assertRaises(ValueError):
            Policy.from_mapping({"coordination_mode": "ASSISTED"})
        with self.assertRaises(ValueError):
            Policy.from_mapping({"coordination_mode": "DELEGATED"})

    def test_pr_author_mapping_and_state(self):
        value = PRInfo.from_mapping(
            {
                "number": 1,
                "title": "x",
                "state": "MERGED",
                "isDraft": False,
                "headRefName": "feat/x",
                "headRefOid": HEAD,
                "mergedAt": "2026-11-26T10:00:00Z",
                "mergeCommit": {"oid": OTHER_HEAD},
                "author": {"login": "agent"},
            }
        )
        self.assertEqual("agent", value.author)
        self.assertEqual(PRState.MERGED, value.state)
        self.assertEqual(OTHER_HEAD, value.merge_commit)

    def test_main_repository_is_retained_even_with_unknown_evidence(self):
        record = classify(
            snapshot(main=True),
            owners={},
            sessions={},
            session_coverage=False,
            qa={},
            qa_coverage=False,
        )
        self.assertEqual(WorktreeState.RETAINED, record.state)
        self.assertEqual(CleanupClass.C_PROTECTED, record.cleanup_class)
        self.assertFalse(record.eligible)
        self.assertIn("main_repository", record.protection_reasons)

    def test_forbidden_git_commands_are_not_executed(self):
        with self.assertRaises(RuntimeError):
            _git("/tmp", "worktree", "remove", "/tmp/wt")
        with self.assertRaises(RuntimeError):
            _git("/tmp", "clean", "-fd")
        with self.assertRaises(RuntimeError):
            _git("/tmp", "reset", "--hard")

    def test_parse_worktree_porcelain(self):
        text = (
            "worktree /repo/main\n"
            "HEAD " + HEAD + "\n"
            "branch refs/heads/main\n\n"
            "worktree /repo/wt\n"
            "HEAD " + OTHER_HEAD + "\n"
            "detached\n"
            "locked reason\n"
            "prunable gone\n\n"
        )
        parsed = parse_worktree_porcelain(text)
        self.assertEqual(2, len(parsed))
        self.assertEqual("main", parsed[0]["branch"])
        self.assertTrue(parsed[1]["detached"])
        self.assertTrue(parsed[1]["locked"])
        self.assertTrue(parsed[1]["prunable"])


    # ---- P1 do parecer 7216595800 -----------------------------------------

    def test_p1_worktrees_beta_protegidas_por_padrao(self):
        for number in (68, 75, 76):
            with self.subTest(pr=number):
                beta = pr(number, state="MERGED", merged_hours_ago=49, certified=True)
                record = classify(prs=[beta], certifications={number: True})
                self.assertEqual(WorktreeState.RETAINED, record.state)
                self.assertEqual(CleanupClass.C_PROTECTED, record.cleanup_class)
                self.assertIn("protected_pr", record.protection_reasons)
                self.assertFalse(record.eligible)

    def test_p1_protecao_existente_nao_e_removida_por_argumento(self):
        policy = Policy.from_mapping({"protected_pr_numbers": [999]})
        self.assertTrue({68, 75, 76, 999}.issubset(set(policy.protected_pr_numbers)))
        beta = pr(76, state="MERGED", merged_hours_ago=49, certified=True)
        record = classify(policy=policy, prs=[beta], certifications={76: True})
        self.assertFalse(record.eligible)

    def test_p1_conjunto_protetido_sem_evidencia_bloqueia(self):
        policy = Policy.from_mapping({"protected_set_evidence_known": False})
        record = classify(policy=policy)
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("protected_set_unknown", record.protection_reasons)
        self.assertFalse(record.eligible)

    def test_p1_certificacao_conflitante_e_unknown_e_proposta_invalida(self):
        conflicting = pr(1, state="MERGED", merged_hours_ago=49, certified=False)
        record = classify(prs=[conflicting], certifications={1: True})
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertEqual(CleanupClass.D_UNKNOWN, record.cleanup_class)
        self.assertIn("certification_conflict", record.protection_reasons)
        self.assertFalse(record.eligible)
        proposal = CleanupProposal(
            path=record.path,
            expected_head=record.snapshot.head,
            expected_owner=record.owner or "D",
            expected_state=WorktreeState.CLEANUP_ELIGIBLE,
            authorization_ref="AUTH-TEST",
            created_at=NOW,
        )
        ok, reasons = validate_proposal(
            proposal, record, current_process_count=0, current_session_count=0,
            current_staged=0, current_unstaged=0, current_untracked=0,
        )
        self.assertFalse(ok)
        self.assertIn("evidence_conflict_unresolved", reasons)

    def test_p1_owner_audit_closed_e_abandoned_conflita(self):
        conflict_owner = {
            PATH: OwnerInfo(
                owner="D", mission="test", audit_closed=True,
                audit_closed_at=NOW - timedelta(hours=25),
                abandoned=True, abandoned_at=NOW - timedelta(days=8),
            ),
        }
        record = classify(prs=[], certifications={}, owners=conflict_owner)
        self.assertEqual(WorktreeState.UNKNOWN, record.state)
        self.assertIn("owner_evidence_conflict", record.protection_reasons)
        self.assertFalse(record.eligible)


class GitScannerTests(unittest.TestCase):
    def _git(self, cwd: str, *args: str) -> None:
        subprocess.run(
            ["git", "-C", cwd, *args],
            check=True,
            capture_output=True,
            text=True,
        )

    def test_scan_temp_worktrees_is_read_only(self):
        with tempfile.TemporaryDirectory(prefix="coord05d-") as tmp:
            root = Path(tmp) / "main"
            root.mkdir()
            self._git(str(root), "init", "-q")
            self._git(str(root), "config", "user.email", "test@example.invalid")
            self._git(str(root), "config", "user.name", "Test")
            (root / "README.md").write_text("fixture\n", encoding="utf-8")
            self._git(str(root), "add", "README.md")
            self._git(str(root), "commit", "-q", "-m", "init")
            linked = Path(tmp) / "linked"
            subprocess.run(
                ["git", "-C", str(root), "worktree", "add", "-q", "-b", "feat/linked", str(linked), "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            )
            policy = Policy(project_roots=(tmp,))
            snapshots, main_path, limitations = scan_worktrees(
                str(root),
                policy,
                sizes=False,
                process_counts={},
                process_complete=True,
            )
            self.assertEqual(2, len(snapshots))
            self.assertEqual(str(root), main_path)
            self.assertTrue(snapshots[0].main)
            self.assertEqual("feat/linked", snapshots[1].branch)
            self.assertTrue(linked.is_dir())
            self.assertEqual(2, len(parse_worktree_porcelain(
                subprocess.run(
                    ["git", "-C", str(root), "worktree", "list", "--porcelain"],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout
            )))
            self.assertTrue(any("shared" in item.lower() for item in limitations))

    def test_inventory_without_owner_session_qa_has_zero_eligible(self):
        from coord_worktrees import build_inventory

        with tempfile.TemporaryDirectory(prefix="coord05d-inv-") as tmp:
            root = Path(tmp) / "main"
            root.mkdir()
            self._git(str(root), "init", "-q")
            self._git(str(root), "config", "user.email", "test@example.invalid")
            self._git(str(root), "config", "user.name", "Test")
            (root / "README.md").write_text("fixture\n", encoding="utf-8")
            self._git(str(root), "add", "README.md")
            self._git(str(root), "commit", "-q", "-m", "init")
            report, records = build_inventory(
                str(root),
                Policy(project_roots=(tmp,)),
                sizes=False,
                free_bytes=42 * 1024**3,
                now=NOW,
            )
            self.assertTrue(records)
            self.assertFalse(any(record.eligible for record in records))
            self.assertEqual(0, report["space"]["eligible_bytes"])
            self.assertTrue(
                all(
                    "ownership_unknown" in record.protection_reasons
                    or "session_evidence_unknown" in record.protection_reasons
                    or "qa_evidence_unknown" in record.protection_reasons
                    or record.eligible
                    for record in records
                )
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
