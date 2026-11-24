"""Gate host-only: comportamento REAL das consultas Git do workflow.

Sem mock de Git: um repositório temporário é criado e a sonda de leitura do
relatório anterior é executada por `bash` exatamente nos três estados:
presente, ausente legitamente e erro de consulta/leitura.
"""

import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATE = (ROOT / ".github" / "workflows" / "roadmap-status-host-tests.yml").read_text(encoding="utf-8")
PRIVILEGED = (ROOT / ".github" / "workflows" / "roadmap-status.yml").read_text(encoding="utf-8")


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True)


def probe(repo: Path, ref: str = "origin/main", out: str = "current.md") -> subprocess.CompletedProcess:
    """Espelho da sonda do workflow — a lógica produtiva não é alterada aqui."""
    script = f"""
set -u
cd {shlex.quote(str(repo))}
if ! git rev-parse --verify --quiet '{ref}^{{commit}}' >/dev/null; then
  echo BASE_ERROR
  exit 2
fi
set +e
git rev-parse --verify --quiet '{ref}:docs/ROADMAP_STATUS.md' >/dev/null 2>&1
path_code=$?
set -e
case "$path_code" in
  0) git show '{ref}:docs/ROADMAP_STATUS.md' > {shlex.quote(out)} || {{ echo READ_ERROR; exit 2; }}; echo PRESENT ;;
  1) : > {shlex.quote(out)}; echo ABSENT ;;
  *) echo QUERY_ERROR; exit 2 ;;
esac
"""
    return subprocess.run(["bash", "-c", script], capture_output=True, text=True)


class RoadmapGitProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.email", "host-only@local")
        git(self.repo, "config", "user.name", "host-only")
        (self.repo / "docs").mkdir()
        (self.repo / "docs" / "ROADMAP_STATUS.md").write_text("# snapshot\n", encoding="utf-8")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "base")
        git(self.repo, "update-ref", "refs/remotes/origin/main", "HEAD")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_present_report_is_read_normally(self):
        result = probe(self.repo)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PRESENT", result.stdout)
        self.assertEqual("# snapshot\n", (self.repo / "current.md").read_text(encoding="utf-8"))

    def test_absent_report_allows_initialization(self):
        git(self.repo, "rm", "-q", "docs/ROADMAP_STATUS.md")
        git(self.repo, "commit", "-qm", "remove report")
        git(self.repo, "update-ref", "refs/remotes/origin/main", "HEAD")
        result = probe(self.repo)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("ABSENT", result.stdout)
        self.assertEqual("", (self.repo / "current.md").read_text(encoding="utf-8"))

    def test_invalid_base_ref_is_error(self):
        git(self.repo, "update-ref", "-d", "refs/remotes/origin/main")
        result = probe(self.repo)
        self.assertEqual(2, result.returncode)
        self.assertIn("BASE_ERROR", result.stdout)

    def test_read_failure_is_error(self):
        # alvo de escrita é um diretório: `git show > ...` falha de verdade.
        (self.repo / "current.md").mkdir()
        result = probe(self.repo)
        self.assertEqual(2, result.returncode)
        self.assertIn("READ_ERROR", result.stdout)

    def test_probe_matches_workflow_contract(self):
        for fragment in (
            "git rev-parse --verify --quiet 'origin/main^{commit}'",
            "origin/main:docs/ROADMAP_STATUS.md",
            "Relatório anterior ausente na árvore consultada; inicialização permitida.",
            "Falha de leitura do relatório anterior",
            "exit \"$path_code\"",
        ):
            self.assertIn(fragment, PRIVILEGED)

    def test_host_gate_is_read_only_and_separate(self):
        # Apenas linhas efetivas: comentários descrevem a ausência de segredos.
        effective = "\n".join(
            line for line in GATE.splitlines() if not line.lstrip().startswith("#")
        )
        self.assertIn("contents: read", effective)
        self.assertNotIn("contents: write", effective)
        self.assertNotIn("pull-requests: write", effective)
        self.assertNotIn("secrets.", effective)
        self.assertNotIn("GITHUB_TOKEN", effective)
        self.assertNotIn("gh pr", effective)
        self.assertNotIn("git push", effective)
        self.assertNotIn("workflow_call", effective)
        self.assertNotIn("roadmap-status.yml", effective.replace(
            ".github/workflows/roadmap-status.yml", "").replace("'roadmap-status.yml'", ""))


if __name__ == "__main__":
    unittest.main()
