import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from roadmap_status import check_status, classify, render_status


class RoadmapStatusTest(unittest.TestCase):
    def test_check_requires_evidence(self):
        self.assertEqual("Não verificado", check_status([]))
        self.assertEqual("Pendente", check_status([{"status": "in_progress"}]))
        self.assertEqual("Verde", check_status([{"status": "completed", "conclusion": "success"}]))
        self.assertEqual("Falhou", check_status([{"status": "completed", "conclusion": "failure"}]))

    def test_merged_is_not_claimed_beta_completed(self):
        prs = [
            {"number": 36, "title": "feat(mentor): memória", "state": "closed", "merged_at": "2026-11-22T09:22:46Z", "head": {"ref": "feat/mentor", "sha": "abc123"}},
            {"number": 37, "title": "UX-REF: links", "state": "open", "merged_at": None, "head": {"ref": "feat/links", "sha": "def456"}},
        ]
        output = render_status("owner/repo", "f" * 40, [], prs, {"def456": []})
        self.assertIn("[#36", output)
        self.assertIn("[#37", output)
        self.assertIn("Não verificado", output)
        self.assertIn("não significa beta validado", output)
        self.assertIn("| Mentor | 0 | 1 | 0 |", output)

    def test_excludes_automation_pr_and_escapes_title(self):
        prs = [
            {"number": 42, "title": "docs: status", "state": "open", "merged_at": None, "head": {"ref": "automation/roadmap-status", "sha": "a"}},
            {"number": 43, "title": "TRUST | test\nline", "state": "open", "merged_at": None, "head": {"ref": "trust/x", "sha": "b"}},
        ]
        output = render_status("owner/repo", "f" * 40, [], prs, {})
        self.assertNotIn("[#42", output)
        self.assertIn("TRUST \\| test line", output)
        self.assertEqual("TRUST", classify(prs[1]))


if __name__ == "__main__":
    unittest.main()
