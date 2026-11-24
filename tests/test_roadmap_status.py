import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from roadmap_status import check_status, classify, has_material_change, render_status, window_text

WORKFLOW = (ROOT / ".github" / "workflows" / "roadmap-status.yml").read_text(encoding="utf-8")


class RoadmapStatusTest(unittest.TestCase):
    def test_check_requires_evidence(self):
        self.assertEqual("Não verificado", check_status([]))
        self.assertEqual("Pendente", check_status([{"status": "in_progress"}]))
        self.assertEqual("Verde", check_status([{"status": "completed", "conclusion": "success"}]))
        self.assertEqual("Falhou", check_status([{"status": "completed", "conclusion": "failure"}]))

    # F1 — somente checks skipped/neutral não são aprovação
    def test_ignored_checks_are_not_approval(self):
        self.assertEqual(
            "Ignorado (sem aprovação)",
            check_status([{"status": "completed", "conclusion": "neutral"}]),
        )
        self.assertEqual(
            "Ignorado (sem aprovação)",
            check_status([{"status": "completed", "conclusion": "skipped"}]),
        )
        self.assertEqual(
            "Ignorado (sem aprovação)",
            check_status([
                {"status": "completed", "conclusion": "neutral"},
                {"status": "completed", "conclusion": "skipped"},
            ]),
        )

    # F2/F3 — aprovado, com ignorados, falho e pendente são estados distintos
    def test_check_states_are_distinct(self):
        self.assertEqual(
            "Verde (com ignorados)",
            check_status([
                {"status": "completed", "conclusion": "success"},
                {"status": "completed", "conclusion": "skipped"},
            ]),
        )
        self.assertEqual(
            "Falhou",
            check_status([
                {"status": "completed", "conclusion": "success"},
                {"status": "completed", "conclusion": "failure"},
            ]),
        )
        self.assertEqual(
            "Pendente",
            check_status([{"status": "queued", "conclusion": None}]),
        )

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

    # F4 — Draft explícito
    def test_draft_pr_is_marked_and_counted(self):
        prs = [
            {"number": 50, "title": "docs: rascunho", "state": "open", "draft": True,
             "created_at": "2026-11-24T10:00:00Z", "updated_at": "2026-11-24T11:00:00Z",
             "head": {"ref": "docs/draft", "sha": "d"}},
        ]
        output = render_status("owner/repo", "a" * 40, [], prs, {"d": []})
        self.assertIn("rascunho (Draft)", output)
        self.assertIn("Drafts: **1**", output)

    # F5 — mudança apenas de proveniência não gera commit
    def test_no_material_change_for_provenance_only(self):
        old = "- `main` consultada: `aaa`\n- PRs abertos: **3**\n"
        new = "- `main` consultada: `bbb`\n- PRs abertos: **3**\n"
        self.assertFalse(has_material_change(new, old))
        self.assertTrue(has_material_change(new.replace("**3**", "**4**"), old))

    # F6 — ciclo bloqueado em push, pull_request_target e workflow_run;
    #       apenas ROADMAP_STATUS.md é escrito
    def test_workflow_cycle_and_write_guards(self):
        self.assertIn("docs/ROADMAP_STATUS.md", WORKFLOW)
        self.assertIn("paths-ignore:", WORKFLOW)
        self.assertIn("github.event.pull_request.head.ref != 'automation/roadmap-status'", WORKFLOW)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", WORKFLOW)
        self.assertIn("github.event.workflow_run.head_branch != 'automation/roadmap-status'", WORKFLOW)
        self.assertNotIn("ACTIVE_TASKS.md", WORKFLOW)
        self.assertNotIn("docs/ROADMAP.md", WORKFLOW)

    # F7 — datas ausentes não inventam métricas
    def test_missing_dates_are_unavailable(self):
        prs = [
            {"number": 60, "title": "sem datas", "state": "open", "merged_at": None,
             "head": {"ref": "x", "sha": "e"}},
            {"number": 61, "title": "sem merge", "state": "closed", "merged_at": "2026-11-20T10:00:00Z",
             "head": {"ref": "y", "sha": "f"}},
        ]
        output = render_status("owner/repo", "b" * 40, [], prs, {})
        self.assertIn("indisponível", output)
        self.assertIn("Mediana abertura → merge: indisponível", output)

    # F8 — janela efetiva derivada do próprio dataset
    def test_collection_window_is_declared(self):
        prs = [
            {"number": 70, "title": "antigo", "state": "open", "created_at": "2026-11-01T10:00:00Z",
             "updated_at": "2026-11-02T10:00:00Z", "head": {"ref": "a", "sha": "g"}},
            {"number": 71, "title": "recente", "state": "open", "created_at": "2026-11-20T10:00:00Z",
             "updated_at": "2026-11-23T10:00:00Z", "head": {"ref": "b", "sha": "h"}},
        ]
        self.assertEqual("2026-11-02 a 2026-11-23", window_text(prs))
        self.assertIn("2026-11-02 a 2026-11-23", render_status("owner/repo", "c" * 40, [], prs, {}))

    # F9 — ACTIVE_TASKS.md e ROADMAP.md preservados integralmente
    def test_strategic_documents_untouched(self):
        generator = (ROOT / "scripts" / "roadmap_status.py").read_text(encoding="utf-8")
        self.assertNotIn("ACTIVE_TASKS.md", generator)
        self.assertIn("docs/ROADMAP.md", generator)  # apenas referência textual no relatório
        self.assertNotIn("open(ACTIVE_TASKS", generator)
        self.assertNotIn("write_text(ACTIVE_TASKS", generator)


    # --- P1/P2 (auditoria do Agente E) ---

    def test_material_change_is_detected(self):
        old = "- PRs abertos: **3**\n- Estados de check dos PRs abertos: Verde=2\n"
        new = old.replace("Verde=2", "Falhou=1, Verde=1")
        self.assertTrue(has_material_change(new, old))

    def test_documental_main_merge_only_provenance(self):
        # Merge documental muda SHA da main e o estado dos checks da main.
        # Nada disso é conteúdo material do painel.
        old = (
            "- `main` consultada: `aaa`\n"
            "- Checks da `main` consultada: **Pendente**\n"
            "- PRs abertos: **3**\n"
            "- Estados de check dos PRs abertos: Não verificado=1\n"
        )
        new = old.replace("`aaa`", "`bbb`").replace("**Pendente**", "**Verde**")
        self.assertFalse(has_material_change(new, old))

    def test_pr_check_state_change_is_material(self):
        # CI de PR é fato material: ausência de checks nunca vira aprovação.
        old = "- PRs abertos: **3**\n- Estados de check dos PRs abertos: Não verificado=1\n"
        new = old.replace("Não verificado=1", "Verde=1")
        self.assertTrue(has_material_change(new, old))

    def test_compare_error_is_distinct_from_no_change(self):
        script = ROOT / "scripts" / "roadmap_status.py"
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good.md"
            good.write_text("- PRs abertos: **3**\n", encoding="utf-8")
            missing = Path(tmp) / "inexistente" / "new.md"
            erro = subprocess.run(
                [sys.executable, str(script), "--compare", str(missing), str(good)],
                capture_output=True, text=True,
            )
            self.assertEqual(2, erro.returncode)
            self.assertIn("ERROR", erro.stdout)
            sem_mudanca = subprocess.run(
                [sys.executable, str(script), "--compare", str(good), str(good)],
                capture_output=True, text=True,
            )
            self.assertEqual(1, sem_mudanca.returncode)
            self.assertEqual("NO_CHANGE", sem_mudanca.stdout.strip())

    def test_workflow_treats_compare_error_as_failure(self):
        self.assertIn("0=CHANGE, 1=NO_CHANGE, 2=ERROR", WORKFLOW)
        self.assertIn('echo "::error::Falha de leitura/comparação do snapshot', WORKFLOW)
        self.assertIn('exit "$code"', WORKFLOW)
        self.assertIn("changed=false", WORKFLOW)

    def test_no_wall_clock_and_write_scope_preserved(self):
        generator = (ROOT / "scripts" / "roadmap_status.py").read_text(encoding="utf-8")
        self.assertNotIn("datetime.now", generator)
        self.assertNotIn("utcnow", generator)
        self.assertIn('path.write_text(result, encoding="utf-8")', generator)

    # --- P4 (auditoria focal): carregamento do relatório anterior ---

    def test_workflow_distinguishes_absent_report_from_git_error(self):
        self.assertIn("git fetch origin main --quiet", WORKFLOW)
        self.assertIn("origin/main indisponível para comparar", WORKFLOW)
        self.assertIn("Relatório anterior ausente na árvore consultada; inicialização permitida.", WORKFLOW)
        self.assertIn("Falha de leitura do relatório anterior", WORKFLOW)
        self.assertIn("Falha de consulta do relatório anterior", WORKFLOW)
        self.assertNotIn('|| : > "$RUNNER_TEMP/current.md"', WORKFLOW)

    def test_compare_read_error_on_directory(self):
        script = ROOT / "scripts" / "roadmap_status.py"
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "um-diretorio"
            directory.mkdir()
            good = Path(tmp) / "good.md"
            good.write_text("- PRs abertos: **3**\n", encoding="utf-8")
            erro = subprocess.run(
                [sys.executable, str(script), "--compare", str(directory), str(good)],
                capture_output=True, text=True,
            )
            self.assertEqual(2, erro.returncode)
            self.assertIn("ERROR", erro.stdout)

    def test_main_checks_snapshot_is_current_not_historical(self):
        prs = [{"number": 80, "title": "aberta", "state": "open",
                "head": {"ref": "x", "sha": "a"}}]
        falhou = render_status("owner/repo", "f" * 40,
                               [{"status": "completed", "conclusion": "failure"}], prs, {})
        self.assertIn("- Checks da `main` consultada: **Falhou**", falhou)
        self.assertIn(f"- `main` consultada: `{'f' * 40}`", falhou)
        vazio = render_status("owner/repo", "f" * 40, [], prs, {})
        self.assertIn("- Checks da `main` consultada: **Não verificado**", vazio)
        # checa o SHA consultado, não um histórico de conclusões antigas
        generator = (ROOT / "scripts" / "roadmap_status.py").read_text(encoding="utf-8")
        self.assertIn('commits/{sha}/check-runs?per_page=100', generator)


if __name__ == "__main__":
    unittest.main()
