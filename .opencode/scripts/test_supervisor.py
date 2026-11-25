#!/usr/bin/env python3
"""Testes isolados do supervisor SHADOW — não destrutivos.

Categorias:
  A. Configuração (parsing, identidade, ausência de campos amplos).
  B. Simulação do matcher de permissões (lógica das regras).
  C. Reconhecimento da configuração pela API (--live, somente leitura).
  D. Imposição real em execução — PENDENTE; exige sessão supervisor autorizada.

Regressões F1–F5 usam fixtures sintéticas; nenhum dado real é negativado.
Uso: python3 .opencode/scripts/test_supervisor.py [--live]
"""
import argparse
import fnmatch
import json
import os
import py_compile
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
AGENT = os.path.join(ROOT, ".opencode", "agents", "supervisor.md")
CHECKPOINT = os.path.join(HERE, "coord_checkpoint.py")
PASS, FAIL = 0, 0


def check(name, cond):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}")


def load_frontmatter():
    import yaml
    with open(AGENT, encoding="utf-8") as f:
        text = f.read()
    assert text.startswith("---"), "frontmatter ausente"
    _, fm, body = text.split("---", 2)
    return yaml.safe_load(fm), body.strip()


def match_effect(rules, action, resource):
    """Última regra correspondente vence. '*' casa quaisquer caracteres."""
    effect = None
    for r in rules:
        if not fnmatch.fnmatchcase(action, r.get("action", "")):
            continue
        res = r.get("resource", "")
        hit = fnmatch.fnmatchcase(resource, res)
        if not hit and res.endswith(" *"):
            hit = fnmatch.fnmatchcase(resource, res[:-2])
        if hit:
            effect = r.get("effect")
    return effect


def has_rule(rules, action, resource, effect):
    return any(r.get("action") == action and r.get("resource") == resource and
               r.get("effect") == effect for r in rules)


def test_config(fm, body):
    print("[A] Configuração")
    rules = fm.get("permissions", [])
    check("description presente", bool(fm.get("description")))
    check("mode primary", fm.get("mode") == "primary")
    check("modelo não fixado", "model" not in fm)
    check("prompt SHADOW", "SHADOW" in body)
    check("prompt proíbe despacho", "Não despacha" in body)
    check("sem read '*' amplo", not has_rule(rules, "read", "*", "allow"))
    check("sem grep '*' amplo", not has_rule(rules, "grep", "*", "allow"))
    check("sem glob '*' amplo", not has_rule(rules, "glob", "*", "allow"))
    check("sem git show genérico", not has_rule(rules, "shell", "git show *", "allow"))
    check("sem git diff genérico", not has_rule(rules, "shell", "git diff *", "allow"))
    check("sem git log genérico", not has_rule(rules, "shell", "git log *", "allow"))
    check("sem API de sessões via shell", not has_rule(rules, "shell", "opencode api get /api/session*", "allow"))
    check("script de testes não liberado ao supervisor",
          not has_rule(rules, "shell", "python3 .opencode/scripts/test_supervisor.py *", "allow"))
    check("checkpoint exato liberado",
          has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --reports", "allow"))


def test_matcher(fm):
    print("[B] Simulação do matcher (lógica; não imposição em execução)")
    rules = fm["permissions"]
    cases = [
        ("read", "docs/COORDINATOR.md", "allow"),
        ("read", "docs/reports/qa.md", "allow"),
        ("read", ".opencode/scripts/coord_checkpoint.py", "allow"),
        ("read", "android/app/src/main/java/X.kt", "allow"),
        ("read", "/home/other/project/docs/COORDINATOR.md", "deny"),
        ("read", "docs/../../.env", "deny"),
        ("read", "docs/../../other/secret.txt", "deny"),
        ("read", ".env", "deny"),
        ("read", ".env.production", "deny"),
        ("read", "service.json", "deny"),
        ("read", "auth.json", "deny"),
        ("read", "local.properties", "deny"),
        ("read", "android/app/src/publicApp/google-services.json", "deny"),
        ("read", "release.keystore", "deny"),
        ("glob", "docs/**", "allow"),
        ("glob", "**/*.kt", "deny"),
        ("grep", "CopilotViewModel", "deny"),
        ("question", "*", "allow"),
        ("edit", "docs/COORDINATOR.md", "deny"),
        ("subagent", "general", "deny"),
        ("execute", "*", "deny"),
        ("skill", "opencode", "deny"),
        ("shell", "git status --short --branch", "allow"),
        ("shell", "git ls-remote origin main", "allow"),
        ("shell", "git show HEAD:docs/COORDINATOR.md", "deny"),
        ("shell", "git diff --no-index /etc/passwd docs/COORDINATOR.md", "deny"),
        ("shell", "git log --all --format=%H", "deny"),
        ("shell", "gh pr view 52 --json state", "allow"),
        ("shell", "opencode api get /api/session", "deny"),
        ("shell", "opencode api get /api/session/x/message?limit=1", "deny"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --reports --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --session-map /etc/passwd", "deny"),
        ("shell", "python3 .opencode/scripts/test_supervisor.py --live", "deny"),
        ("shell", "git push origin main", "deny"),
        ("shell", "gh pr merge 57", "deny"),
        ("shell", "gh api -X POST repos/x/y/pulls/57/merge", "deny"),
        ("shell", "rm -rf /", "deny"),
        ("shell", "adb shell", "deny"),
        ("shell", "bash .opencode/scripts/coord_checkpoint.py", "deny"),
        ("shell", "eval git push", "deny"),
        ("webfetch", "https://opencode.ai/docs/agents", "allow"),
        ("webfetch", "https://example.invalid/x", "ask"),
        ("external_directory", "/home/user/other", "ask"),
    ]
    for action, resource, expected in cases:
        got = match_effect(rules, action, resource)
        check(f"{action} {resource[:46]} -> {expected}", got == expected)


def test_syntax():
    print("[A] Sintaxe")
    for path in (CHECKPOINT, os.path.join(HERE, "test_supervisor.py")):
        try:
            with tempfile.TemporaryDirectory() as td:
                py_compile.compile(path, cfile=os.path.join(td, "x.pyc"), doraise=True)
            check(f"{os.path.basename(path)} compila", True)
        except Exception as e:  # noqa: BLE001
            check(f"{os.path.basename(path)} compila ({e})", False)


def synthetic_map():
    return {
        "project": {"id": "proj-bt", "allowed_directories": ["/repo/better-talker", "/repo/better-talker-a"]},
        "agents": {"A": [{"session_id": "ses_A", "directory": "/repo/better-talker",
                          "mission_id": "MISS-A", "expected_pr": 52,
                          "expected_branch": "feat/a", "expected_worktree": "better-talker-a"}]},
    }


def synthetic_candidate(sid="ses_A", project="proj-bt", directory="/repo/better-talker", title="AGENTE A"):
    return {"id": sid, "title": title, "project_id": project, "directory": directory,
            "model": {"providerID": "free", "id": "test"}, "busy_utc": None, "idle_utc": None}


def test_sessions():
    print("[F1] Seleção explícita de sessões")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    cfg = synthetic_map()
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate()], cfg)
    check("sessionID + projeto + diretório autorizados", len(auth) == 1 and auth[0]["agent"] == "A")
    auth, rej, unk = cc.authorize_sessions(
        [synthetic_candidate(project="other-project")], cfg)
    check("outro projeto com título AGENTE A rejeitado", not auth and len(rej) == 1)
    auth, rej, unk = cc.authorize_sessions(
        [synthetic_candidate(), synthetic_candidate("ses_OTHER")], cfg)
    check("duas candidatas do mesmo agente: só a mapeada é autorizada",
          len(auth) == 1 and any(r["id"] == "ses_OTHER" for r in rej))
    auth, rej, unk = cc.authorize_sessions([], cfg)
    check("sessionID do mapa não observado vira desconhecido", len(unk) == 1)
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate()], None)
    check("sem mapa: nenhuma sessão autorizada", not auth and len(rej) == 1)
    with tempfile.TemporaryDirectory() as td:
        p = os.path.join(td, "map.json")
        dup = synthetic_map()
        dup["agents"]["B"] = [{"session_id": "ses_A", "directory": "/repo/better-talker"}]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(dup, f)
        try:
            cc.load_session_map(p)
            check("mapa duplicado/ambíguo rejeitado", False)
        except ValueError:
            check("mapa duplicado/ambíguo rejeitado", True)
    # Sem mapa, build(reports=True) não pode chamar endpoint de mensagens.
    calls = []
    old_map_env = os.environ.get("BT_COORD_SESSION_MAP")
    os.environ["BT_COORD_SESSION_MAP"] = "/tmp/opencode/__no_map_for_test__.json"
    def fake_sh(args):
        calls.append(args)
        if args[:3] == ["opencode", "api", "get"]:
            if args[3] == "/api/session":
                return True, json.dumps({"data": [synthetic_candidate()]})
            if args[3] == "/api/session/active":
                return True, json.dumps({"data": {}})
        if args[:2] == ["git", "ls-remote"]:
            return True, "a" * 40 + "\trefs/heads/main"
        if args[:2] == ["git", "rev-parse"]:
            return True, "a" * 40
        if args[:3] == ["gh", "pr", "list"]:
            return True, "[]"
        if args[:2] == ["git", "worktree"]:
            return True, ""
        return False, ""
    old = cc.sh
    cc.sh = fake_sh
    try:
        cc.build(reports=True)
    finally:
        cc.sh = old
        if old_map_env is None:
            os.environ.pop("BT_COORD_SESSION_MAP", None)
        else:
            os.environ["BT_COORD_SESSION_MAP"] = old_map_env
    check("sem mapa não consulta mensagens", not any("/message" in " ".join(c) for c in calls))


def test_reports():
    print("[F3] Relatório concluído e proveniência")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    base = {"type": "assistant", "time": {"created": 1, "completed": 2}}
    msgs = [
        {**base, "id": "interrupted", "finish": "interrupted", "content": [{"type": "text", "text": "## Parecer\n" + "x" * 300}]},
        {**base, "id": "tools", "finish": "tool-calls", "content": [{"type": "text", "text": "## Status\n" + "x" * 300}]},
        {**base, "id": "status", "finish": "stop", "content": [{"type": "text", "text": "Aguardando runs finais. " * 8}]},
        {**base, "id": "report", "finish": "stop", "content": [{"type": "text", "text": "## Parecer\nPR #52\nHEAD " + "a" * 40 + "\n" + "x" * 500}]},
    ]
    report, skipped = cc.select_completed_report(msgs)
    check("relatório interrompido ignorado", report and report["message_id"] == "report")
    check("tool-calls/status ignorados", len(skipped) == 3)
    check("id/timestamp preservados", report["message_id"] == "report" and report["completed_utc"])
    truncated, _ = cc.select_completed_report(msgs, max_chars=100)
    check("volume de texto limitado", truncated["truncated"] and len(truncated["text"]) == 100)
    # Paginação limitada a 2 páginas.
    calls = []
    def fake_api(args):
        calls.append(args)
        if len(calls) == 1:
            return True, json.dumps({"data": [msgs[0]], "cursor": {"next": "cursor1"}})
        return True, json.dumps({"data": [msgs[1]], "cursor": {}})
    old = cc.sh
    cc.sh = fake_api
    try:
        fetched, cursor, err = cc.fetch_assistant_messages("ses_X")
    finally:
        cc.sh = old
    check("paginação seletiva limitada", len(fetched) == 2 and len(calls) == 2 and not err)
    check("cursor não combinado com order", "order=desc" in calls[0][-1] and "order=" not in calls[1][-1])
    # PR existente não prova associação à missão.
    entry = {"mission_local": None, "expected_pr": None, "expected_branch": None, "expected_worktree": None}
    assoc = cc.association(entry, report, [{"number": 52, "headRefName": "feat/a"}], [])
    check("PR mencionado/existente sem prova de missão", assoc["pr_existence"] and assoc["mission_association"] == "desconhecida")
    entry2 = {"mission_local": "MISS-A", "expected_pr": 52, "expected_branch": "feat/a", "expected_worktree": None}
    assoc2 = cc.association(entry2, report, [{"number": 52, "headRefName": "feat/a"}], [])
    check("mapa local + PR/branch confirmam associação", assoc2["mission_association"] == "confirmada_pelo_mapa_local")


def test_evidence():
    print("[F4] Checkpoint e evidências")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    # SHA completo da worktree.
    old = cc.sh
    cc.sh = lambda args: (True, "worktree /repo/x\nHEAD " + "b" * 40 + "\nbranch refs/heads/feat/x\n")
    try:
        trees = cc.worktrees()
    finally:
        cc.sh = old
    check("worktree preserva SHA completo", trees[0]["head"] == "b" * 40)
    # Checks sem SHA/evento não certificam; HEAD divergente não certifica.
    roll = [{"name": "build", "status": "COMPLETED", "conclusion": "SUCCESS",
             "detailsUrl": "https://github/x/actions/runs/123/job/1"}]
    def run_meta(args):
        if args[:3] == ["gh", "pr", "view"]:
            return True, json.dumps({"statusCheckRollup": roll})
        return True, json.dumps({"headSha": "c" * 40, "event": "push", "url": "https://github/x/runs/123"})
    cc.sh = run_meta
    try:
        result = cc.pr_checks(52, "a" * 40, detail=True)
    finally:
        cc.sh = old
    check("CI em SHA divergente não certifica HEAD", not result["checks"][0]["certifies_current_head"])
    check("SHA/evento/run registrados", result["checks"][0]["head_sha"] == "c" * 40 and
          result["checks"][0]["event"] == "push" and result["checks"][0]["run_id"] == "123")
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": []}))
    try:
        empty = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("sem checks ≠ falha de consulta", empty["query_status"] == "ok" and empty["note"] == "sem checks")
    cc.sh = lambda args: (False, "erro")
    try:
        failed = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("falha de consulta distinguida", failed["query_status"] == "error")
    # Resumo não lista mais de 12 worktrees.
    many = [{"path": f"/repo/better-talker-{i}", "head": "a" * 40, "branch": f"feat/{i}"}
            for i in range(120)]
    check("resumo limita worktrees", len(cc.relevant_worktrees(many, [], None)) <= 12)
    # API ausente/inválida vira desconhecida.
    cc.sh = lambda args: (False, "erro")
    try:
        check("falha da API de sessões vira desconhecida", cc.session_candidates() is None)
        check("falha da API de PRs vira desconhecida", cc.open_prs() is None)
    finally:
        cc.sh = old


def test_docs():
    print("[A] Documentos oficiais")
    for d in ["docs/COORDINATOR.md", "docs/DECISIONS.md", "docs/TEAM_COORDINATION.md",
              "docs/ACTIVE_TASKS.md", "docs/DELIVERY_POLICY.md"]:
        check(d, os.path.exists(os.path.join(ROOT, d)))


def test_live():
    print("[C] Reconhecimento pela API (somente leitura)")
    import time
    agents = []
    for attempt in range(3):
        r = subprocess.run(["opencode", "api", "get", f"/api/agent?location[directory]={ROOT}"],
                           capture_output=True, text=True, cwd=ROOT, timeout=60)
        try:
            agents = json.loads(r.stdout).get("data", [])
            if any(a.get("id") == "supervisor" for a in agents):
                break
        except Exception:  # noqa: BLE001
            pass
        if attempt < 2:
            time.sleep(1)
    sup = next((a for a in agents if a.get("id") == "supervisor"), None)
    check("supervisor reconhecido", sup is not None)
    if sup:
        perms = sup.get("permissions", [])
        check("mode primary", sup.get("mode") == "primary")
        check("sem modelo fixado", not sup.get("model"))
        check("prompt SHADOW carregado", "SHADOW" in (sup.get("system") or ""))
        check("live: leitura de docs permitida", match_effect(perms, "read", "docs/COORDINATOR.md") == "allow")
        check("live: leitura externa negada", match_effect(perms, "read", "/home/other/x") == "deny")
        check("live: grep negado", match_effect(perms, "grep", "secret") == "deny")
        check("live: API de mensagens negada", match_effect(perms, "shell", "opencode api get /api/session/x/message") == "deny")
        check("live: edit/subagent/git push negados",
              match_effect(perms, "edit", "docs/x") == "deny" and
              match_effect(perms, "subagent", "general") == "deny" and
              match_effect(perms, "shell", "git push origin main") == "deny")
    rc = subprocess.run([sys.executable, CHECKPOINT, "--summary", "--json"],
                        capture_output=True, text=True, cwd=ROOT, timeout=180)
    try:
        cp = json.loads(rc.stdout)
        check("checkpoint main SHA completo", bool(cp.get("main_sha")) and len(cp["main_sha"]) == 40)
        check("checkpoint candidatos/metadados", isinstance(cp.get("session_candidates_count"), int))
        check("resumo com worktrees limitadas", len(cp.get("worktrees") or []) <= 12)
    except Exception as e:  # noqa: BLE001
        check(f"checkpoint JSON ({e})", False)
    print("[D] Imposição real em execução: PENDENTE — sessão supervisor não autorizada/criada.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="inclui reconhecimento ao vivo (categoria C)")
    args = ap.parse_args()
    os.chdir(ROOT)
    fm, body = load_frontmatter()
    test_config(fm, body)
    test_matcher(fm)
    test_syntax()
    test_sessions()
    test_reports()
    test_evidence()
    test_docs()
    if args.live:
        test_live()
    print(f"\nRESULTADO: {PASS} passed, {FAIL} failed")
    print("Categoria D (imposição real) permanece PENDENTE; nenhuma sessão/despacho foi criado.")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
