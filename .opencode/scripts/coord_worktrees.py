#!/usr/bin/env python3
"""COORD-05D/P1 — deterministic worktree lifecycle and retention inventory.

Read-only by construction:

* the classifier is pure and accepts an injected inventory/snapshot;
* the scanner only runs read-only Git queries, ``du`` and ``/proc`` reads;
* it never removes/prunes worktrees, cleans files, resets, gc's, deletes
  branches, reads credentials or changes sessions;
* cleanup eligibility is a review state, never an execution instruction.

See ``.opencode/WORKTREE_POLICY.md``.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

sys.dont_write_bytecode = True

GIB = 1024**3
FORBIDDEN_GIT_PREFIXES = (
    ("worktree", "remove"),
    ("worktree", "prune"),
    ("clean",),
    ("gc",),
    ("reset", "--hard"),
)
DEFAULT_QA_NAMES = (
    "qa",
    "evidence",
    "artifacts",
    "test-results",
    "screenshots",
    "cp02-exec",
    "trust43-p7-exec",
    "hotfix-68-exec",
)
DEFAULT_IGNORED_NAMES = (
    "build",
    ".gradle",
    ".cxx",
    "node_modules",
    "dist",
    "out",
    "target",
    ".venv",
    "__pycache__",
)


class WorktreeState(str, Enum):
    CREATED = "CREATED"
    ACTIVE = "ACTIVE"
    QA_PENDING = "QA_PENDING"
    MERGE_PENDING = "MERGE_PENDING"
    RETAINED = "RETAINED"
    CLEANUP_ELIGIBLE = "CLEANUP_ELIGIBLE"
    REMOVED = "REMOVED"
    UNKNOWN = "UNKNOWN"


class CleanupClass(str, Enum):
    A_DISPOSABLE = "A_DISPOSABLE"
    B_ELIGIBLE = "B_ELIGIBLE"
    C_PROTECTED = "C_PROTECTED"
    D_UNKNOWN = "D_UNKNOWN"
    E_EXTERNAL = "E_EXTERNAL"


class IntegrationState(str, Enum):
    MERGED_PR = "MERGED_PR"
    ANCESTOR_MAIN = "ANCESTOR_MAIN"
    REMOTE_REF = "REMOTE_REF"
    OPEN_PR = "OPEN_PR"
    CLOSED_UNMERGED = "CLOSED_UNMERGED"
    NOT_INTEGRATED = "NOT_INTEGRATED"
    UNKNOWN = "UNKNOWN"


class StorageBand(str, Enum):
    IDEAL = "IDEAL"
    ATTENTION = "ATTENTION"
    RESTRICTION = "RESTRICTION"
    CRITICAL = "CRITICAL"
    EMERGENCY = "EMERGENCY"


class PRState(str, Enum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"
    MERGED = "MERGED"


class CoordinationMode(str, Enum):
    SHADOW = "SHADOW"
    ASSISTED = "ASSISTED"
    DELEGATED = "DELEGATED"


# ---------------------------------------------------------------------------
# Time / validation helpers
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


def _int(value: Any, field: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field}: integer required")
    if value < minimum:
        raise ValueError(f"{field}: must be >= {minimum}")
    return value


def _bool(value: Any, field: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{field}: boolean required")
    return value


def _text(value: Any, field: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field}: string required")
    text = value.strip()
    if not text and not allow_empty:
        raise ValueError(f"{field}: non-empty string required")
    return text


def _safe_abs(path: str, root: str) -> str:
    return os.path.normpath(os.path.abspath(os.path.join(root, path)))


# ---------------------------------------------------------------------------
# Policy and storage thresholds
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class StorageThresholds:
    ideal_gib: int = 50
    attention_gib: int = 30
    restriction_gib: int = 20
    critical_gib: int = 10

    def __post_init__(self) -> None:
        values = (
            self.ideal_gib,
            self.attention_gib,
            self.restriction_gib,
            self.critical_gib,
        )
        if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in values):
            raise ValueError("storage thresholds: non-negative integers required")
        if not (self.ideal_gib > self.attention_gib > self.restriction_gib > self.critical_gib):
            raise ValueError("storage thresholds: ideal > attention > restriction > critical")

    def band(self, free_bytes: int) -> StorageBand:
        if free_bytes >= self.ideal_gib * GIB:
            return StorageBand.IDEAL
        if free_bytes >= self.attention_gib * GIB:
            return StorageBand.ATTENTION
        if free_bytes >= self.restriction_gib * GIB:
            return StorageBand.RESTRICTION
        if free_bytes >= self.critical_gib * GIB:
            return StorageBand.CRITICAL
        return StorageBand.EMERGENCY

    def as_dict(self) -> dict[str, int]:
        return {
            "ideal_gib": self.ideal_gib,
            "attention_gib": self.attention_gib,
            "restriction_gib": self.restriction_gib,
            "critical_gib": self.critical_gib,
        }

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any] | None) -> "StorageThresholds":
        if value is None:
            return cls()
        return cls(
            ideal_gib=_int(value.get("ideal_gib", 50), "thresholds.ideal_gib", minimum=1),
            attention_gib=_int(value.get("attention_gib", 30), "thresholds.attention_gib", minimum=1),
            restriction_gib=_int(value.get("restriction_gib", 20), "thresholds.restriction_gib", minimum=1),
            critical_gib=_int(value.get("critical_gib", 10), "thresholds.critical_gib", minimum=0),
        )


# Proteções explícitas da jornada beta: nunca dependem de argumento de CLI.
# #68 (hotfix Android), #75 (J05 leitura bíblica) e #76 (A15 beta).
DEFAULT_PROTECTED_PR_NUMBERS: tuple[int, ...] = (68, 75, 76)


@dataclass(frozen=True)
class Policy:
    merged_retention_hours: int = 48
    audit_retention_hours: int = 24
    abandoned_retention_days: int = 7
    thresholds: StorageThresholds = field(default_factory=StorageThresholds)
    project_roots: tuple[str, ...] = ()
    qa_artifact_names: tuple[str, ...] = DEFAULT_QA_NAMES
    ignored_names: tuple[str, ...] = DEFAULT_IGNORED_NAMES
    require_owner: bool = True
    require_session_evidence: bool = True
    require_qa_evidence: bool = True
    require_certification: bool = True
    protected_pr_numbers: tuple[int, ...] = DEFAULT_PROTECTED_PR_NUMBERS
    protected_set_evidence_known: bool = True
    coordination_mode: CoordinationMode = CoordinationMode.SHADOW

    def __post_init__(self) -> None:
        for value, field_name in (
            (self.merged_retention_hours, "merged_retention_hours"),
            (self.audit_retention_hours, "audit_retention_hours"),
            (self.abandoned_retention_days, "abandoned_retention_days"),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ValueError(f"policy.{field_name}: non-negative integer required")
        if not isinstance(self.protected_set_evidence_known, bool):
            raise ValueError("policy.protected_set_evidence_known: boolean required")
        object.__setattr__(
            self,
            "protected_pr_numbers",
            tuple(sorted(set(DEFAULT_PROTECTED_PR_NUMBERS) | set(self.protected_pr_numbers))),
        )
        if self.coordination_mode is not CoordinationMode.SHADOW:
            raise ValueError("policy.coordination_mode: ASSISTED/DELEGATED disabled; SHADOW only")

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any] | None) -> "Policy":
        if value is None:
            return cls()
        mode_raw = _text(value.get("coordination_mode", "SHADOW"), "policy.coordination_mode")
        try:
            mode = CoordinationMode(mode_raw.upper())
        except ValueError as exc:
            raise ValueError("policy.coordination_mode: unknown value") from exc
        roots = value.get("project_roots", ())
        if isinstance(roots, str):
            roots = (roots,)
        if not isinstance(roots, (list, tuple)):
            raise ValueError("policy.project_roots: list required")
        return cls(
            merged_retention_hours=_int(
                value.get("merged_retention_hours", 48),
                "policy.merged_retention_hours",
            ),
            audit_retention_hours=_int(
                value.get("audit_retention_hours", 24),
                "policy.audit_retention_hours",
            ),
            abandoned_retention_days=_int(
                value.get("abandoned_retention_days", 7),
                "policy.abandoned_retention_days",
            ),
            thresholds=StorageThresholds.from_mapping(value.get("thresholds")),
            project_roots=tuple(_text(root, "policy.project_roots[]") for root in roots),
            protected_pr_numbers=tuple(
                _int(number, "policy.protected_pr_numbers[]", minimum=1)
                for number in value.get("protected_pr_numbers", DEFAULT_PROTECTED_PR_NUMBERS)
            ),
            protected_set_evidence_known=bool(
                value.get("protected_set_evidence_known", True)
            ),
            coordination_mode=mode,
        )


# ---------------------------------------------------------------------------
# Evidence inputs
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PRInfo:
    number: int
    title: str
    state: PRState
    is_draft: bool = False
    head_ref: str | None = None
    head_oid: str | None = None
    merged_at: datetime | None = None
    merge_commit: str | None = None
    url: str | None = None
    author: str | None = None
    certified: bool | None = None

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "PRInfo":
        if not isinstance(value, Mapping):
            raise ValueError("pr: mapping required")
        state_raw = _text(value.get("state"), "pr.state").upper()
        try:
            state = PRState(state_raw)
        except ValueError as exc:
            raise ValueError("pr.state: unknown value") from exc
        author_raw = value.get("author")
        if isinstance(author_raw, Mapping):
            author_raw = author_raw.get("login")
        merge_raw = value.get("mergeCommit")
        if isinstance(merge_raw, Mapping):
            merge_raw = merge_raw.get("oid") or merge_raw.get("commit", {}).get("oid")
        return cls(
            number=_int(value.get("number"), "pr.number", minimum=1),
            title=_text(value.get("title"), "pr.title"),
            state=state,
            is_draft=bool(value.get("isDraft", False)),
            head_ref=None
            if value.get("headRefName") is None
            else _text(value.get("headRefName"), "pr.headRefName"),
            head_oid=None
            if value.get("headRefOid") is None
            else _text(value.get("headRefOid"), "pr.headRefOid"),
            merged_at=_utc(value.get("mergedAt"), "pr.mergedAt"),
            merge_commit=None
            if merge_raw is None
            else _text(merge_raw, "pr.mergeCommit"),
            url=None if value.get("url") is None else _text(value.get("url"), "pr.url"),
            author=None if author_raw is None else _text(author_raw, "pr.author"),
            certified=value.get("certified") if isinstance(value.get("certified"), bool) else None,
        )


class PRIndex:
    def __init__(self, prs: Iterable[PRInfo]) -> None:
        self.prs = tuple(sorted(prs, key=lambda pr: pr.number, reverse=True))
        self.by_branch: dict[str, list[PRInfo]] = {}
        self.by_oid: dict[str, list[PRInfo]] = {}
        for pr in self.prs:
            if pr.head_ref:
                self.by_branch.setdefault(pr.head_ref, []).append(pr)
            if pr.head_oid:
                self.by_oid.setdefault(pr.head_oid, []).append(pr)

    def match(self, snapshot: "WorktreeSnapshot") -> PRInfo | None:
        candidates: list[PRInfo] = []
        if snapshot.head:
            candidates = list(self.by_oid.get(snapshot.head, []))
        if not candidates and snapshot.branch:
            candidates = list(self.by_branch.get(snapshot.branch, []))
        if not candidates:
            return None
        # Prefer an exact head match; otherwise deterministic newest PR.
        exact = [pr for pr in candidates if not snapshot.head or pr.head_oid in (None, snapshot.head)]
        return (exact or candidates)[0]


@dataclass(frozen=True)
class OwnerInfo:
    owner: str | None
    mission: str | None = None
    audit_closed: bool | None = None
    audit_closed_at: datetime | None = None
    abandoned: bool | None = None
    abandoned_at: datetime | None = None
    notes: tuple[str, ...] = ()

    @classmethod
    def from_mapping(cls, value: Any) -> "OwnerInfo":
        if isinstance(value, str):
            return cls(owner=_text(value, "owner"))
        if not isinstance(value, Mapping):
            raise ValueError("owner: string or mapping required")
        owner = value.get("owner")
        return cls(
            owner=None if owner is None else _text(owner, "owner.owner"),
            mission=None if value.get("mission") is None else _text(value.get("mission"), "owner.mission"),
            audit_closed=value.get("audit_closed") if isinstance(value.get("audit_closed"), bool) else None,
            audit_closed_at=_utc(value.get("audit_closed_at"), "owner.audit_closed_at"),
            abandoned=value.get("abandoned") if isinstance(value.get("abandoned"), bool) else None,
            abandoned_at=_utc(value.get("abandoned_at"), "owner.abandoned_at"),
        )


@dataclass(frozen=True)
class SessionInfo:
    evidence_known: bool
    active_count: int = 0
    aliases: tuple[str, ...] = ()
    source: str | None = None

    @classmethod
    def from_mapping(cls, value: Any) -> "SessionInfo":
        if isinstance(value, int) and not isinstance(value, bool):
            return cls(evidence_known=True, active_count=_int(value, "session.active", minimum=0))
        if not isinstance(value, Mapping):
            raise ValueError("session: integer or mapping required")
        aliases = value.get("aliases", ())
        if not isinstance(aliases, (list, tuple)):
            raise ValueError("session.aliases: list required")
        return cls(
            evidence_known=bool(value.get("evidence_known", True)),
            active_count=_int(value.get("active", value.get("active_count", 0)), "session.active"),
            aliases=tuple(str(alias) for alias in aliases),
            source=None if value.get("source") is None else _text(value.get("source"), "session.source"),
        )


@dataclass(frozen=True)
class QAInfo:
    evidence_known: bool
    unique_artifacts: bool = False
    notes: tuple[str, ...] = ()

    @classmethod
    def from_mapping(cls, value: Any) -> "QAInfo":
        if isinstance(value, bool):
            return cls(evidence_known=True, unique_artifacts=value)
        if not isinstance(value, Mapping):
            raise ValueError("qa: boolean or mapping required")
        notes = value.get("notes", ())
        if not isinstance(notes, (list, tuple)):
            raise ValueError("qa.notes: list required")
        return cls(
            evidence_known=bool(value.get("evidence_known", True)),
            unique_artifacts=bool(value.get("unique_artifacts", False)),
            notes=tuple(str(note) for note in notes),
        )


@dataclass(frozen=True)
class WorktreeSnapshot:
    path: str
    branch: str | None = None
    head: str | None = None
    detached: bool = False
    main: bool = False
    locked: bool = False
    prunable: bool = False
    missing: bool = False
    symlink: bool = False
    external: bool = False
    healthagentforms: bool = False
    staged: int = 0
    unstaged: int = 0
    untracked: int = 0
    untracked_classified: bool = False
    ignored_relevant: tuple[str, ...] = ()
    local_only_commits: bool = False
    ancestor_main: bool | None = None
    remote_reachable: bool | None = None
    last_commit_at: datetime | None = None
    process_count: int = 0
    process_scan_complete: bool = True
    size_bytes: int | None = None
    size_method: str | None = None
    disposable_artifacts_bytes: int | None = None
    qa_detected: tuple[str, ...] = ()
    notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class WorktreeRecord:
    snapshot: WorktreeSnapshot
    pr: PRInfo | None
    integration: IntegrationState
    owner: str | None
    mission: str | None
    session_count: int
    session_evidence_known: bool
    qa_evidence_known: bool
    qa_unique: bool
    certified: bool | None
    retention_due_at: datetime | None
    state: WorktreeState
    cleanup_class: CleanupClass
    protection_reasons: tuple[str, ...]
    review_candidate: bool
    eligible: bool

    @property
    def path(self) -> str:
        return self.snapshot.path

    @property
    def size_bytes(self) -> int | None:
        return self.snapshot.size_bytes

    def as_dict(self) -> dict[str, Any]:
        snapshot = self.snapshot
        return {
            "path": snapshot.path,
            "branch": snapshot.branch,
            "head": snapshot.head,
            "detached": snapshot.detached,
            "main": snapshot.main,
            "locked": snapshot.locked,
            "prunable": snapshot.prunable,
            "missing": snapshot.missing,
            "symlink": snapshot.symlink,
            "external": snapshot.external,
            "healthagentforms": snapshot.healthagentforms,
            "staged": snapshot.staged,
            "unstaged": snapshot.unstaged,
            "untracked": snapshot.untracked,
            "untracked_classified": snapshot.untracked_classified,
            "ignored_relevant": list(snapshot.ignored_relevant),
            "local_only_commits": snapshot.local_only_commits,
            "ancestor_main": snapshot.ancestor_main,
            "remote_reachable": snapshot.remote_reachable,
            "last_commit_at": _iso(snapshot.last_commit_at),
            "process_count": snapshot.process_count,
            "process_scan_complete": snapshot.process_scan_complete,
            "size_bytes": snapshot.size_bytes,
            "size_method": snapshot.size_method,
            "disposable_artifacts_bytes": snapshot.disposable_artifacts_bytes,
            "qa_detected": list(snapshot.qa_detected),
            "owner": self.owner,
            "mission": self.mission,
            "session_count": self.session_count,
            "session_evidence_known": self.session_evidence_known,
            "qa_evidence_known": self.qa_evidence_known,
            "qa_unique": self.qa_unique,
            "pr": None
            if self.pr is None
            else {
                "number": self.pr.number,
                "title": self.pr.title,
                "state": self.pr.state.value,
                "is_draft": self.pr.is_draft,
                "head_ref": self.pr.head_ref,
                "head_oid": self.pr.head_oid,
                "merged_at": _iso(self.pr.merged_at),
                "merge_commit": self.pr.merge_commit,
                "url": self.pr.url,
                "author": self.pr.author,
                "certified": self.pr.certified,
            },
            "integration": self.integration.value,
            "certified": self.certified,
            "retention_due_at": _iso(self.retention_due_at),
            "state": self.state.value,
            "cleanup_class": self.cleanup_class.value,
            "protection_reasons": list(self.protection_reasons),
            "review_candidate": self.review_candidate,
            "eligible": self.eligible,
        }


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------


UNKNOWN_REASONS = {
    "ownership_unknown",
    "session_evidence_unknown",
    "qa_evidence_unknown",
    "certification_unknown",
    "integration_unknown",
    "mission_unknown",
    "process_scan_incomplete",
    "retention_unknown",
    "head_changed",
    "insufficient_evidence",
    "symlink_path",
    "missing_path",
    "prunable_worktree",
    "protected_set_unknown",
    "certification_conflict",
    "owner_evidence_conflict",
}
HARD_REVIEW_BLOCKERS = {
    "process_in_use",
    "session_linked",
    "staged_changes",
    "unstaged_changes",
    "untracked_files",
    "local_only_commits",
    "unique_qa_artifacts",
    "locked_worktree",
    "symlink_path",
    "missing_path",
    "prunable_worktree",
    "main_repository",
    "external_path",
    "healthagentforms",
    "pr_open",
    "pr_draft",
    "pr_closed_unmerged",
    "qa_not_certified",
    "protected_pr",
    "protected_set_unknown",
    "certification_conflict",
    "owner_evidence_conflict",
}
REVIEW_BLOCKERS = {
    "ownership_unknown",
    "session_evidence_unknown",
    "qa_evidence_unknown",
    "certification_unknown",
    "mission_unknown",
    "retention_not_due",
    "retention_unknown",
    "process_scan_incomplete",
}


def _add(reasons: list[str], reason: str) -> None:
    if reason not in reasons:
        reasons.append(reason)


def classify_worktree(
    snapshot: WorktreeSnapshot,
    policy: Policy,
    pr_index: PRIndex,
    owners: Mapping[str, OwnerInfo],
    sessions: Mapping[str, SessionInfo],
    qa: Mapping[str, QAInfo],
    certifications: Mapping[int, bool],
    *,
    session_coverage_known: bool,
    qa_coverage_known: bool,
    now: datetime | None = None,
) -> WorktreeRecord:
    """Pure deterministic classification. Never deletes or mutates anything."""
    now = _utc(now, "now") or datetime.now(timezone.utc)
    reasons: list[str] = []
    owner_info = owners.get(snapshot.path)
    owner = owner_info.owner if owner_info else None
    mission = owner_info.mission if owner_info and owner_info.mission else None
    session = sessions.get(snapshot.path)
    if session is None and session_coverage_known:
        session = SessionInfo(evidence_known=True, active_count=0)
    if session is None:
        session_evidence_known = False
    else:
        session_evidence_known = session.evidence_known
    qa_info = qa.get(snapshot.path)
    if qa_info is None and qa_coverage_known:
        qa_info = QAInfo(evidence_known=True, unique_artifacts=False)
    if qa_info is None:
        qa_evidence_known = False
    else:
        qa_evidence_known = qa_info.evidence_known
    pr = pr_index.match(snapshot)
    certified: bool | None = None
    retention_due: datetime | None = None
    integration = IntegrationState.UNKNOWN
    state = WorktreeState.UNKNOWN
    cleanup_class = CleanupClass.D_UNKNOWN

    # Absolute protections, in deterministic order.
    if not policy.protected_set_evidence_known:
        _add(reasons, "protected_set_unknown")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
    if snapshot.main:
        _add(reasons, "main_repository")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    if snapshot.healthagentforms:
        _add(reasons, "healthagentforms")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.E_EXTERNAL
    elif snapshot.external:
        _add(reasons, "external_path")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.E_EXTERNAL
    if snapshot.missing:
        _add(reasons, "missing_path")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
    if snapshot.prunable:
        _add(reasons, "prunable_worktree")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
    if snapshot.symlink:
        _add(reasons, "symlink_path")
        state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
    if snapshot.locked:
        _add(reasons, "locked_worktree")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    if not snapshot.process_scan_complete:
        _add(reasons, "process_scan_incomplete")
        if state is WorktreeState.UNKNOWN:
            cleanup_class = CleanupClass.D_UNKNOWN
    if snapshot.process_count > 0:
        _add(reasons, "process_in_use")
        state, cleanup_class = WorktreeState.ACTIVE, CleanupClass.C_PROTECTED
    if session is not None and session_evidence_known and session.active_count > 0:
        _add(reasons, "session_linked")
        state, cleanup_class = WorktreeState.ACTIVE, CleanupClass.C_PROTECTED
    if session is None or not session_evidence_known:
        _add(reasons, "session_evidence_unknown")
        if state is WorktreeState.UNKNOWN:
            cleanup_class = CleanupClass.D_UNKNOWN
    if owner is None:
        _add(reasons, "ownership_unknown")
        if state is WorktreeState.UNKNOWN:
            cleanup_class = CleanupClass.D_UNKNOWN
    if qa_info is None or not qa_evidence_known:
        _add(reasons, "qa_evidence_unknown")
        if state is WorktreeState.UNKNOWN:
            cleanup_class = CleanupClass.D_UNKNOWN
    elif qa_info.unique_artifacts:
        _add(reasons, "unique_qa_artifacts")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED

    if snapshot.staged > 0:
        _add(reasons, "staged_changes")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    if snapshot.unstaged > 0:
        _add(reasons, "unstaged_changes")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    if snapshot.untracked > 0 and not snapshot.untracked_classified:
        _add(reasons, "untracked_files")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    if snapshot.local_only_commits:
        preserved_by_pr = bool(
            pr
            and pr.state is PRState.MERGED
            and pr.head_oid
            and snapshot.head == pr.head_oid
        )
        if not preserved_by_pr:
            _add(reasons, "local_only_commits")
            state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED

    if pr is not None:
        mission = mission or pr.title
        if pr.head_oid and snapshot.head and pr.head_oid != snapshot.head:
            _add(reasons, "head_changed")
            if state is WorktreeState.UNKNOWN:
                cleanup_class = CleanupClass.D_UNKNOWN
        if pr.state is PRState.OPEN:
            integration = IntegrationState.OPEN_PR
            if pr.is_draft:
                _add(reasons, "pr_draft")
                state = WorktreeState.QA_PENDING
            else:
                _add(reasons, "pr_open")
                state = WorktreeState.MERGE_PENDING
            cleanup_class = CleanupClass.C_PROTECTED
        elif pr.state is PRState.CLOSED:
            integration = IntegrationState.CLOSED_UNMERGED
            _add(reasons, "pr_closed_unmerged")
            state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
        else:
            integration = IntegrationState.MERGED_PR
            certified = certifications.get(pr.number)
            if (certified is not None and pr.certified is not None
                    and certified != pr.certified):
                _add(reasons, "certification_conflict")
                state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
            elif certified is None:
                certified = pr.certified
            if certified is None:
                _add(reasons, "certification_unknown")
                if state is WorktreeState.UNKNOWN:
                    cleanup_class = CleanupClass.D_UNKNOWN
            elif not certified:
                _add(reasons, "qa_not_certified")
                state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
            elif pr.merged_at is None:
                _add(reasons, "retention_unknown")
                if state is WorktreeState.UNKNOWN:
                    cleanup_class = CleanupClass.D_UNKNOWN
            else:
                retention_due = pr.merged_at + timedelta(hours=policy.merged_retention_hours)
    else:
        if snapshot.ancestor_main:
            integration = IntegrationState.ANCESTOR_MAIN
        elif snapshot.remote_reachable:
            integration = IntegrationState.REMOTE_REF
        else:
            integration = IntegrationState.NOT_INTEGRATED
        mission = mission  # explicit owner metadata only
        if (owner_info and owner_info.audit_closed is True
                and owner_info.abandoned is True):
            _add(reasons, "owner_evidence_conflict")
            state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
        elif owner_info and owner_info.audit_closed is True:
            base = owner_info.audit_closed_at or snapshot.last_commit_at
            retention_due = (
                base + timedelta(hours=policy.audit_retention_hours) if base else None
            )
            if retention_due is None:
                _add(reasons, "retention_unknown")
                if state is WorktreeState.UNKNOWN:
                    cleanup_class = CleanupClass.D_UNKNOWN
        elif owner_info and owner_info.abandoned is True:
            base = owner_info.abandoned_at or snapshot.last_commit_at
            retention_due = (
                base + timedelta(days=policy.abandoned_retention_days) if base else None
            )
            if retention_due is None:
                _add(reasons, "retention_unknown")
                if state is WorktreeState.UNKNOWN:
                    cleanup_class = CleanupClass.D_UNKNOWN
        else:
            _add(reasons, "mission_unknown")
            _add(reasons, "integration_unknown")
            if state is WorktreeState.UNKNOWN:
                cleanup_class = CleanupClass.D_UNKNOWN

    if pr is not None and pr.number in policy.protected_pr_numbers:
        _add(reasons, "protected_pr")
        state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED

    if retention_due is not None:
        if now < retention_due:
            _add(reasons, "retention_not_due")
            if state is not WorktreeState.UNKNOWN:
                state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
        else:
            # Retention elapsed is necessary, never sufficient by itself.
            if not reasons:
                state, cleanup_class = WorktreeState.CLEANUP_ELIGIBLE, CleanupClass.B_ELIGIBLE

    # Final reconciliation: unknown evidence dominates unless a hard state
    # (active/open/closed/changes/QA/unique) gives a more specific label.
    if reasons:
        if state is not WorktreeState.UNKNOWN:
            # Preserve specific active/open/retained state.
            pass
        elif any(reason in UNKNOWN_REASONS for reason in reasons):
            state, cleanup_class = WorktreeState.UNKNOWN, CleanupClass.D_UNKNOWN
        else:
            state, cleanup_class = WorktreeState.RETAINED, CleanupClass.C_PROTECTED
    # External/HealthAgentForms paths are always class E, regardless of other
    # missing evidence; they must never be confused with D/unknown candidates.
    if snapshot.external or snapshot.healthagentforms:
        state = WorktreeState.UNKNOWN
        cleanup_class = CleanupClass.E_EXTERNAL
    # The main working tree is always protected even when ownership/session
    # evidence is absent.
    if snapshot.main:
        state = WorktreeState.RETAINED
        cleanup_class = CleanupClass.C_PROTECTED
    # Positive process/session evidence is an active state, even when the
    # worktree also has local changes or missing metadata.
    session_active = bool(
        session is not None and session.evidence_known and session.active_count > 0
    )
    if (
        not snapshot.main
        and not snapshot.external
        and not snapshot.healthagentforms
        and not snapshot.missing
        and not snapshot.prunable
        and not snapshot.symlink
        and (snapshot.process_count > 0 or session_active)
    ):
        state = WorktreeState.ACTIVE
        cleanup_class = CleanupClass.C_PROTECTED
    eligible = state is WorktreeState.CLEANUP_ELIGIBLE and not reasons
    if eligible:
        cleanup_class = CleanupClass.B_ELIGIBLE
    review_candidate = bool(
        not eligible
        and reasons
        and set(reasons) <= REVIEW_BLOCKERS
        and integration in {
            IntegrationState.MERGED_PR,
            IntegrationState.ANCESTOR_MAIN,
            IntegrationState.REMOTE_REF,
        }
    )
    return WorktreeRecord(
        snapshot=snapshot,
        pr=pr,
        integration=integration,
        owner=owner,
        mission=mission,
        session_count=session.active_count if session is not None else 0,
        session_evidence_known=session_evidence_known,
        qa_evidence_known=qa_evidence_known,
        qa_unique=bool(qa_info.unique_artifacts) if qa_info is not None else False,
        certified=certified,
        retention_due_at=retention_due,
        state=state,
        cleanup_class=cleanup_class,
        protection_reasons=tuple(sorted(set(reasons))),
        review_candidate=review_candidate,
        eligible=eligible,
    )


# ---------------------------------------------------------------------------
# Proposal validation (read-only; no execution)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CleanupProposal:
    path: str
    expected_head: str
    expected_owner: str
    expected_state: WorktreeState
    authorization_ref: str
    created_at: datetime


def validate_proposal(
    proposal: CleanupProposal,
    current: WorktreeRecord,
    *,
    current_process_count: int,
    current_session_count: int,
    current_staged: int,
    current_unstaged: int,
    current_untracked: int,
) -> tuple[bool, tuple[str, ...]]:
    """Pure revalidation for a future authorized cleanup. Never executes it."""
    reasons: list[str] = []
    if not proposal.authorization_ref.strip():
        reasons.append("authorization_missing")
    if proposal.expected_state is not WorktreeState.CLEANUP_ELIGIBLE:
        reasons.append("proposal_state_not_eligible")
    if not current.eligible:
        reasons.append("current_not_eligible")
    if proposal.path != current.path:
        reasons.append("path_changed")
    if proposal.expected_head != current.snapshot.head:
        reasons.append("head_changed")
    if proposal.expected_owner != current.owner:
        reasons.append("owner_changed")
    if current_process_count > 0:
        reasons.append("process_in_use")
    if current_session_count > 0:
        reasons.append("session_linked")
    if current_staged or current_unstaged or current_untracked:
        reasons.append("working_tree_changed")
    if current.snapshot.locked:
        reasons.append("locked_worktree")
    unresolved = {
        "protected_set_unknown", "certification_conflict", "owner_evidence_conflict",
        "protected_pr", "qa_not_certified", "head_changed", "insufficient_evidence",
    } & set(current.protection_reasons)
    if unresolved:
        reasons.append("evidence_conflict_unresolved")
    return not reasons, tuple(sorted(set(reasons)))


# ---------------------------------------------------------------------------
# Storage/report
# ---------------------------------------------------------------------------


def _unique_sized(records: Iterable[WorktreeRecord]) -> list[WorktreeRecord]:
    """Skip a record whose path is nested inside another measured path."""
    ordered = sorted(
        (record for record in records if record.size_bytes is not None),
        key=lambda record: (len(record.path), record.path),
    )
    kept: list[WorktreeRecord] = []
    for record in ordered:
        if any(
            record.path == parent.path
            or record.path.startswith(parent.path.rstrip(os.sep) + os.sep)
            for parent in kept
        ):
            continue
        kept.append(record)
    return kept


def build_report(
    records: Sequence[WorktreeRecord],
    policy: Policy,
    free_bytes: int,
    *,
    main_path: str,
    generated_at: datetime,
    source: str = "git worktree list --porcelain",
    limitations: Sequence[str] = (),
) -> dict[str, Any]:
    if free_bytes < 0:
        raise ValueError("free_bytes: must be >= 0")
    states: dict[str, int] = {state.value: 0 for state in WorktreeState}
    classes: dict[str, int] = {cls.value: 0 for cls in CleanupClass}
    blockers: dict[str, int] = {}
    for record in records:
        states[record.state.value] += 1
        classes[record.cleanup_class.value] += 1
        for reason in record.protection_reasons:
            blockers[reason] = blockers.get(reason, 0) + 1
    sized = _unique_sized(records)
    by_class: dict[str, int] = {cls.value: 0 for cls in CleanupClass}
    for record in sized:
        by_class[record.cleanup_class.value] += record.size_bytes or 0
    eligible = [record for record in records if record.eligible]
    review = [record for record in records if record.review_candidate]
    eligible_bytes = sum(record.size_bytes or 0 for record in _unique_sized(eligible))
    review_bytes = sum(record.size_bytes or 0 for record in _unique_sized(review))
    disposable_values = [
        record.snapshot.disposable_artifacts_bytes
        for record in records
        if record.snapshot.disposable_artifacts_bytes is not None
    ]
    disposable_bytes = sum(disposable_values) if disposable_values else None
    measured_bytes = sum(record.size_bytes or 0 for record in sized)
    unknown_sizes = sum(1 for record in records if record.size_bytes is None)
    candidates = [
        {
            "path": record.path,
            "branch": record.snapshot.branch,
            "head": record.snapshot.head,
            "owner": record.owner,
            "mission": record.mission,
            "pr": None if record.pr is None else record.pr.number,
            "integration": record.integration.value,
            "state": record.state.value,
            "class": record.cleanup_class.value,
            "size_bytes": record.size_bytes,
            "protection_reasons": list(record.protection_reasons),
            "retention_due_at": _iso(record.retention_due_at),
            "review_candidate": record.review_candidate,
            "eligible": record.eligible,
        }
        for record in sorted(
            review,
            key=lambda record: (-(record.size_bytes or 0), record.path),
        )
    ]
    return {
        "generated_at": _iso(generated_at),
        "source": source,
        "main_path": main_path,
        "worktree_count": len(records),
        "free_bytes": free_bytes,
        "free_gib": round(free_bytes / GIB, 2),
        "storage_band": policy.thresholds.band(free_bytes).value,
        "thresholds": policy.thresholds.as_dict(),
        "states": states,
        "classes": classes,
        "blockers": dict(sorted(blockers.items())),
        "space": {
            "measured_bytes": measured_bytes,
            "measured_gib": round(measured_bytes / GIB, 2),
            "eligible_bytes": eligible_bytes,
            "review_potential_bytes": review_bytes,
            "review_potential_gib": round(review_bytes / GIB, 2),
            "disposable_artifacts_bytes": disposable_bytes,
            "by_class_bytes": by_class,
            "unknown_size_count": unknown_sizes,
            "note": "Estimates are upper bounds; shared .git/object data is excluded, hardlinks/sparse/overlap are not deduplicated across worktrees.",
        },
        "eligible": [
            {
                "path": record.path,
                "size_bytes": record.size_bytes,
                "owner": record.owner,
                "pr": None if record.pr is None else record.pr.number,
            }
            for record in eligible
        ],
        "review_candidates": candidates,
        "limitations": list(limitations)
        + [
            "No cleanup or Git mutation was executed.",
            "Unknown ownership/session/QA evidence blocks exclusion.",
            "External/HealthAgentForms paths are not scanned for size or content.",
            "Shared caches are not classified as automatically disposable.",
        ],
        "records": [record.as_dict() for record in records],
    }


def render_report(report: Mapping[str, Any]) -> str:
    lines = [
        "# COORD-05D — inventário de worktrees (somente leitura)",
        "",
        f"Gerado em: `{report['generated_at']}`",
        f"Free: **{report['free_gib']} GiB** · banda: **{report['storage_band']}**",
        f"Worktrees: **{report['worktree_count']}**",
        "",
        "## Estados",
        "",
    ]
    for key, value in report["states"].items():
        lines.append(f"- `{key}`: {value}")
    lines += ["", "## Classes", ""]
    for key, value in report["classes"].items():
        lines.append(f"- `{key}`: {value}")
    lines += ["", "## Espaço", ""]
    space = report["space"]
    lines.append(f"- Medido: **{space['measured_gib']} GiB**")
    lines.append(f"- Elegível para revisão de exclusão: **{space['eligible_bytes']} bytes**")
    lines.append(f"- Potencial de candidatos à revisão: **{space['review_potential_gib']} GiB**")
    disposable = space["disposable_artifacts_bytes"]
    lines.append(
        "- Artefatos descartáveis medidos: **não medidos separadamente**"
        if disposable is None
        else f"- Artefatos descartáveis medidos: **{disposable} bytes**"
    )
    lines.append(f"- Tamanho desconhecido: **{space['unknown_size_count']}** worktrees")
    lines.append("")
    lines.append("## Bloqueadores")
    lines.append("")
    for key, value in report["blockers"].items():
        lines.append(f"- `{key}`: {value}")
    lines += ["", "## Candidatos à revisão (sem execução)", ""]
    if not report["review_candidates"]:
        lines.append("Nenhum candidato à revisão.")
    for item in report["review_candidates"][:40]:
        lines.append(
            f"- `{item['path']}` · {item['state']} · {item['size_bytes']} bytes · "
            f"PR {item['pr']} · blockers={', '.join(item['protection_reasons']) or 'none'}"
        )
    lines += ["", "## Limitações", ""]
    for limitation in report["limitations"]:
        lines.append(f"- {limitation}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Read-only Git scanner
# ---------------------------------------------------------------------------


def _run(args: Sequence[str], *, cwd: str | None = None, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    normalized = tuple(args)
    for prefix in FORBIDDEN_GIT_PREFIXES:
        if normalized[: len(prefix)] == prefix:
            raise RuntimeError(f"forbidden destructive git command: {' '.join(normalized)}")
    return subprocess.run(
        list(normalized),
        cwd=cwd,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )


def _git(path: str, *args: str, timeout: int = 30) -> tuple[int, str]:
    for prefix in FORBIDDEN_GIT_PREFIXES:
        if tuple(args[: len(prefix)]) == prefix:
            raise RuntimeError(f"forbidden destructive git command: {' '.join(args)}")
    result = _run(("git", "-C", path, *args), timeout=timeout)
    return result.returncode, result.stdout.strip()


def parse_worktree_porcelain(text: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    current: dict[str, Any] = {}

    def flush() -> None:
        nonlocal current
        if current:
            records.append(current)
            current = {}

    for line in text.splitlines():
        if line.startswith("worktree "):
            flush()
            current = {"path": line[len("worktree ") :], "locked": False, "prunable": False}
        elif line.startswith("HEAD "):
            current["head"] = line[len("HEAD ") :]
        elif line.startswith("branch "):
            branch = line[len("branch ") :]
            current["branch"] = branch[len("refs/heads/") :] if branch.startswith("refs/heads/") else branch
        elif line == "detached":
            current["detached"] = True
        elif line.startswith("locked"):
            current["locked"] = True
            current["locked_reason"] = line[len("locked") :].strip()
        elif line.startswith("prunable"):
            current["prunable"] = True
            current["prunable_reason"] = line[len("prunable") :].strip()
        elif line == "bare":
            current["bare"] = True
        elif line == "":
            flush()
    flush()
    return records


def _project_path(path: str, roots: Sequence[str]) -> bool:
    norm = os.path.normpath(os.path.abspath(path))
    for root in roots:
        root_norm = os.path.normpath(os.path.abspath(root))
        if norm == root_norm or norm.startswith(root_norm.rstrip(os.sep) + os.sep):
            return True
    return False


def _healthagentforms(path: str) -> bool:
    return "healthagentforms" in path.lower()


def _process_cwds() -> tuple[dict[str, int], bool]:
    uid = os.getuid()
    counts: dict[str, int] = {}
    complete = True
    try:
        pids = [name for name in os.listdir("/proc") if name.isdigit()]
    except OSError:
        return {}, False
    for pid in pids:
        try:
            status = Path(f"/proc/{pid}/status").read_text(encoding="utf-8", errors="ignore")
            uid_line = next((line for line in status.splitlines() if line.startswith("Uid:")), "")
            if not uid_line:
                continue
            if int(uid_line.split()[1]) != uid:
                continue
            cwd = os.readlink(f"/proc/{pid}/cwd")
        except (OSError, ValueError):
            # A process that disappeared between listdir and readlink is not an
            # incomplete scan. Only an own-UID process whose cwd remains
            # unreadable makes the same-UID evidence incomplete.
            if not os.path.exists(f"/proc/{pid}"):
                continue
            try:
                status = Path(f"/proc/{pid}/status").read_text(encoding="utf-8", errors="ignore")
                if f"Uid:\t{uid}\t" in status:
                    complete = False
            except OSError:
                pass
            continue
        counts[cwd] = counts.get(cwd, 0) + 1
    return counts, complete


def _process_count_for(path: str, counts: Mapping[str, int]) -> int:
    norm = os.path.normpath(os.path.abspath(path))
    return sum(
        count
        for cwd, count in counts.items()
        if cwd == norm or cwd.startswith(norm.rstrip(os.sep) + os.sep)
    )


def _size(path: str, timeout: int) -> tuple[int | None, str]:
    if not os.path.isdir(path):
        return None, "missing"
    try:
        result = subprocess.run(
            ["du", "-sb", "--exclude=.git", path],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None, "du_timeout_or_error"
    if result.returncode != 0:
        return None, "du_error"
    first = result.stdout.splitlines()[0] if result.stdout else ""
    try:
        return int(first.split()[0]), "du_apparent_bytes_excluding_git"
    except (ValueError, IndexError):
        return None, "du_unparsed"


def _top_level_names(path: str, names: Sequence[str]) -> tuple[str, ...]:
    found: list[str] = []
    try:
        entries = os.listdir(path)
    except OSError:
        return ()
    for entry in entries:
        if entry in names:
            found.append(entry)
    return tuple(sorted(found))


def _scan_snapshot(
    raw: Mapping[str, Any],
    *,
    main_path: str,
    policy: Policy,
    process_counts: Mapping[str, int],
    process_complete: bool,
    sizes: bool,
    size_timeout: int,
) -> WorktreeSnapshot:
    path = os.path.normpath(os.path.abspath(raw["path"]))
    branch = raw.get("branch")
    head = raw.get("head")
    main = path == main_path
    locked = bool(raw.get("locked", False))
    prunable = bool(raw.get("prunable", False))
    missing = not os.path.isdir(path)
    symlink = os.path.islink(path) or (os.path.isdir(path) and os.path.realpath(path) != path)
    external = not _project_path(path, policy.project_roots) if policy.project_roots else False
    health = _healthagentforms(path)
    process_count = _process_count_for(path, process_counts)
    qa_detected = () if (external or health or missing) else _top_level_names(path, policy.qa_artifact_names)
    ignored = () if (external or health or missing) else _top_level_names(path, policy.ignored_names)
    size_bytes: int | None = None
    size_method: str | None = "not_measured"
    if sizes and not external and not health and not missing and not symlink:
        size_bytes, size_method = _size(path, size_timeout)
    if missing or external or health or symlink:
        return WorktreeSnapshot(
            path=path,
            branch=branch,
            head=head,
            detached=bool(raw.get("detached", False)),
            main=main,
            locked=locked,
            prunable=prunable,
            missing=missing,
            symlink=symlink,
            external=external,
            healthagentforms=health,
            process_count=process_count,
            process_scan_complete=process_complete,
            size_bytes=size_bytes,
            size_method=size_method,
            qa_detected=qa_detected,
            ignored_relevant=ignored,
            notes=("external_or_missing_scan_skipped",),
        )
    status = _git(path, "status", "--porcelain=v2", "--branch", "--untracked-files=normal")[1]
    staged = unstaged = untracked = 0
    for line in status.splitlines():
        if line.startswith(("1 ", "2 ")):
            xy = line.split(" ", 2)[1]
            if xy[0] != ".":
                staged += 1
            if xy[1] != ".":
                unstaged += 1
        elif line.startswith("u "):
            staged += 1
            unstaged += 1
        elif line.startswith("? "):
            untracked += 1
    local_only = bool(_git(path, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes", "--tags")[1])
    ancestor_rc = _run(("git", "-C", path, "merge-base", "--is-ancestor", "HEAD", "refs/remotes/origin/main"), timeout=30).returncode
    ancestor_main = True if ancestor_rc == 0 else (False if ancestor_rc == 1 else None)
    remote_reachable = not local_only
    ts_raw = _git(path, "log", "-1", "--format=%ct")[1]
    try:
        last_commit_at = datetime.fromtimestamp(int(ts_raw), tz=timezone.utc)
    except (ValueError, OSError):
        last_commit_at = None
    return WorktreeSnapshot(
        path=path,
        branch=branch,
        head=head,
        detached=bool(raw.get("detached", False)),
        main=main,
        locked=locked,
        prunable=prunable,
        missing=missing,
        symlink=symlink,
        external=external,
        healthagentforms=health,
        staged=staged,
        unstaged=unstaged,
        untracked=untracked,
        ignored_relevant=ignored,
        local_only_commits=local_only,
        ancestor_main=ancestor_main,
        remote_reachable=remote_reachable,
        last_commit_at=last_commit_at,
        process_count=process_count,
        process_scan_complete=process_complete,
        size_bytes=size_bytes,
        size_method=size_method,
        qa_detected=qa_detected,
    )


def scan_worktrees(
    repo: str,
    policy: Policy,
    *,
    sizes: bool = False,
    size_timeout: int = 8,
    process_counts: Mapping[str, int] | None = None,
    process_complete: bool = True,
) -> tuple[list[WorktreeSnapshot], str, list[str]]:
    code, output = _git(repo, "worktree", "list", "--porcelain")
    if code != 0:
        raise RuntimeError("git worktree list failed")
    parsed = parse_worktree_porcelain(output)
    if not parsed:
        return [], os.path.abspath(repo), ["no worktrees parsed"]
    main_path = os.path.normpath(os.path.abspath(parsed[0]["path"]))
    if not policy.project_roots:
        policy = replace(policy, project_roots=(os.path.dirname(main_path),))
    counts = process_counts if process_counts is not None else _process_cwds()[0]
    snapshots = [
        _scan_snapshot(
            raw,
            main_path=main_path,
            policy=policy,
            process_counts=counts,
            process_complete=process_complete,
            sizes=sizes,
            size_timeout=size_timeout,
        )
        for raw in parsed
    ]
    limitations = [
        "Git shared object data excluded via du --exclude=.git.",
        "Sizes are apparent bytes and may double-count hardlinks/sparse/overlap across worktrees.",
        "Only same-UID /proc cwd evidence is collected; process absence is not inactivity.",
        "No session/owner/QA evidence was read unless explicitly injected.",
    ]
    return snapshots, main_path, limitations


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _load_json(path: str | None) -> Any:
    if not path:
        return None
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def _load_prs(path: str | None, fetch: bool) -> tuple[list[PRInfo], list[str]]:
    limitations: list[str] = []
    if path:
        data = _load_json(path)
        if not isinstance(data, list):
            raise ValueError("prs: JSON list required")
        return [PRInfo.from_mapping(item) for item in data], limitations
    if fetch:
        try:
            result = _run(
                (
                    "gh",
                    "pr",
                    "list",
                    "--state",
                    "all",
                    "--limit",
                    "500",
                    "--json",
                    "number,title,state,isDraft,headRefName,headRefOid,mergedAt,mergeCommit,url,author",
                ),
                timeout=120,
            )
            if result.returncode != 0:
                raise RuntimeError(result.stderr.strip() or "gh pr list failed")
            return [PRInfo.from_mapping(item) for item in json.loads(result.stdout)], limitations
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
            limitations.append(f"PR evidence unavailable: {exc}")
            return [], limitations
    limitations.append("PR evidence not injected; PR state unknown.")
    return [], limitations


def _load_evidence(path: str | None) -> tuple[Mapping[str, Any], bool]:
    if not path:
        return {}, False
    data = _load_json(path)
    if not isinstance(data, Mapping):
        raise ValueError("evidence: mapping required")
    coverage = bool(data.get("coverage_known", False))
    entries = data.get("entries", data)
    if not isinstance(entries, Mapping):
        raise ValueError("evidence.entries: mapping required")
    return entries, coverage


def _load_owners(path: str | None) -> tuple[dict[str, OwnerInfo], bool]:
    if not path:
        return {}, False
    data = _load_json(path)
    if not isinstance(data, Mapping):
        raise ValueError("owners: mapping required")
    coverage = bool(data.get("coverage_known", False))
    entries = data.get("entries", data)
    return {
        os.path.normpath(os.path.abspath(str(key))): OwnerInfo.from_mapping(value)
        for key, value in entries.items()
        if key not in {"coverage_known", "entries"}
    }, coverage


def _load_sessions(path: str | None) -> tuple[dict[str, SessionInfo], bool]:
    entries, coverage = _load_evidence(path)
    return {
        os.path.normpath(os.path.abspath(str(key))): SessionInfo.from_mapping(value)
        for key, value in entries.items()
    }, coverage


def _load_qa(path: str | None) -> tuple[dict[str, QAInfo], dict[int, bool], bool]:
    entries, coverage = _load_evidence(path)
    certifications: dict[int, bool] = {}
    paths: dict[str, QAInfo] = {}
    for key, value in entries.items():
        if str(key) == "certifications" and isinstance(value, Mapping):
            for number, certified in value.items():
                if isinstance(certified, bool):
                    certifications[int(number)] = certified
            continue
        paths[os.path.normpath(os.path.abspath(str(key)))] = QAInfo.from_mapping(value)
    return paths, certifications, coverage


def build_inventory(
    repo: str,
    policy: Policy,
    *,
    prs: Sequence[PRInfo] = (),
    owners: Mapping[str, OwnerInfo] | None = None,
    sessions: Mapping[str, SessionInfo] | None = None,
    qa: Mapping[str, QAInfo] | None = None,
    certifications: Mapping[int, bool] | None = None,
    session_coverage_known: bool = False,
    qa_coverage_known: bool = False,
    sizes: bool = False,
    size_timeout: int = 8,
    free_bytes: int | None = None,
    now: datetime | None = None,
) -> tuple[dict[str, Any], list[WorktreeRecord]]:
    now = _utc(now, "now") or datetime.now(timezone.utc)
    owners = owners or {}
    sessions = sessions or {}
    qa = qa or {}
    certifications = certifications or {}
    process_counts, process_complete = _process_cwds()
    snapshots, main_path, limitations = scan_worktrees(
        repo,
        policy,
        sizes=sizes,
        size_timeout=size_timeout,
        process_counts=process_counts,
        process_complete=process_complete,
    )
    if not policy.project_roots:
        policy = replace(policy, project_roots=(os.path.dirname(main_path),))
    pr_index = PRIndex(prs)
    records = [
        classify_worktree(
            snapshot,
            policy,
            pr_index,
            owners,
            sessions,
            qa,
            certifications,
            session_coverage_known=session_coverage_known,
            qa_coverage_known=qa_coverage_known,
            now=now,
        )
        for snapshot in snapshots
    ]
    free = (
        free_bytes
        if free_bytes is not None
        else shutil.disk_usage(main_path).free
    )
    report = build_report(
        records,
        policy,
        free,
        main_path=main_path,
        generated_at=now,
        limitations=limitations,
    )
    return report, records


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read-only worktree lifecycle inventory (COORD-05D/P1)."
    )
    parser.add_argument("--repo", default=os.getcwd(), help="repository/worktree to scan")
    parser.add_argument("--prs", help="JSON snapshot of GitHub PRs")
    parser.add_argument("--fetch-prs", action="store_true", help="read PRs with gh pr list")
    parser.add_argument("--owners", help="JSON owner evidence")
    parser.add_argument("--sessions", help="JSON session evidence")
    parser.add_argument("--qa", help="JSON QA/certification evidence")
    parser.add_argument("--sizes", action="store_true", help="measure du sizes")
    parser.add_argument("--size-timeout", type=int, default=8)
    parser.add_argument("--free-bytes", type=int)
    parser.add_argument("--protect-pr", type=int, action="append", default=[])
    parser.add_argument("--now", help="ISO-8601 evaluation instant")
    parser.add_argument("--json-out", help="write JSON inventory")
    parser.add_argument("--report-out", help="write Markdown report")
    parser.add_argument("--pretty", action="store_true", help="print full JSON")
    args = parser.parse_args(argv)
    try:
        prs, pr_limits = _load_prs(args.prs, args.fetch_prs)
        owners, _ = _load_owners(args.owners)
        sessions, session_coverage = _load_sessions(args.sessions)
        qa, certifications, qa_coverage = _load_qa(args.qa)
        report, _ = build_inventory(
            args.repo,
            Policy(
                protected_pr_numbers=tuple(
                    sorted(set(DEFAULT_PROTECTED_PR_NUMBERS) | set(args.protect_pr))
                ),
            ),
            prs=prs,
            owners=owners,
            sessions=sessions,
            qa=qa,
            certifications=certifications,
            session_coverage_known=session_coverage,
            qa_coverage_known=qa_coverage,
            sizes=args.sizes,
            size_timeout=args.size_timeout,
            free_bytes=args.free_bytes,
            now=_utc(args.now, "now"),
        )
        report["limitations"] = list(report["limitations"]) + pr_limits
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    if args.report_out:
        with open(args.report_out, "w", encoding="utf-8") as handle:
            handle.write(render_report(report))
    if args.pretty:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(render_report(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
