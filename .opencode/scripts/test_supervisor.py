#!/usr/bin/env python3
"""Testes isolados do supervisor SHADOW — não destrutivos.

Categorias:
  A. Configuração (parsing, identidade, ausência de campos amplos).
  B. Simulação do matcher de permissões (lógica das regras).
  C. Reconhecimento da configuração pela API (--live, somente leitura).
  D. Imposição real em execução — validada separadamente na sessão experimental.

Regressões R1–R4 usam fixtures sintéticas; nenhum dado real é negativado.
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
    check("sem git show/diff/log genéricos",
          not any(has_rule(rules, "shell", f"git {x} *", "allow") for x in ("show", "diff", "log")))
    check("sem API de sessões via shell", not has_rule(rules, "shell", "opencode api get /api/session*", "allow"))
    check("sem gh shell amplo", not any(has_rule(rules, "shell", f"gh {x} *", "allow")
                                        for x in ("pr list", "pr view", "pr checks", "run list", "run view", "repo view")))
    check("script de testes não liberado", not has_rule(rules, "shell", "python3 .opencode/scripts/test_supervisor.py *", "allow"))
    check("checkpoint exato liberado", has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --reports", "allow"))


def test_matcher(fm):
    print("[B] Simulação do matcher (lógica; não imposição em execução)")
    rules = fm["permissions"]
    cases = [
        ("read", "docs/COORDINATOR.md", "allow"),
        ("read", ".opencode/scripts/coord_checkpoint.py", "allow"),
        ("read", "android/app/src/main/java/X.kt", "allow"),
        ("read", "/home/other/project/docs/COORDINATOR.md", "deny"),
        ("read", "docs/../../.env", "deny"),
        ("read", "docs/../../other/secret.txt", "deny"),
        ("read", ".env.production", "deny"),
        ("read", "service.json", "deny"),
        ("read", "android/app/src/publicApp/google-services.json", "deny"),
        ("glob", "docs/**", "allow"),
        ("glob", "**/*.kt", "deny"),
        ("grep", "CopilotViewModel", "deny"),
        ("question", "*", "allow"),
        ("edit", "docs/COORDINATOR.md", "deny"),
        ("subagent", "general", "deny"),
        ("execute", "*", "deny"),
        ("shell", "git status --short --branch", "allow"),
        ("shell", "git ls-remote origin main", "allow"),
        ("shell", "git show HEAD:docs/COORDINATOR.md", "deny"),
        ("shell", "git diff --no-index /etc/passwd docs/COORDINATOR.md", "deny"),
        ("shell", "gh pr view 52 --json state", "deny"),
        ("shell", "gh pr view 52 --repo other/repo", "deny"),
        ("shell", "gh api repos/other/repo/contents/docs", "deny"),
        ("shell", "opencode api get /api/session/x/message?limit=1", "deny"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --reports --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --session-map /etc/passwd", "deny"),
        ("shell", "python3 .opencode/scripts/test_supervisor.py --live", "deny"),
        ("shell", "git push origin main", "deny"),
        ("shell", "gh pr merge 57", "deny"),
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
        check(f"{action} {resource[:44]} -> {expected}", got == expected)


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
        "project": {"id": "proj-bt-1", "allowed_directories": ["/repo/better-talker", "/repo/better-talker-a"]},
        "agents": {"A": [{"session_id": "ses_AAAAAAAA", "directory": "/repo/better-talker",
                          "mission_id": "MISS-A", "expected_pr": 52,
                          "expected_branch": "feat/a", "expected_worktree": "better-talker-a"}]},
    }


def synthetic_candidate(sid="ses_AAAAAAAA", project="proj-bt-1", directory="/repo/better-talker", title="AGENTE A"):
    return {"id": sid, "title": title, "project_id": project, "directory": directory,
            "model": {"providerID": "free", "id": "test"}, "busy_utc": None, "idle_utc": None}


def test_sessions():
    print("[R1] Seleção explícita e mapa privado")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    cfg = synthetic_map()
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate()], cfg)
    check("sessionID + projeto + diretório exatos autorizados", len(auth) == 1)
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate(project=None)], cfg)
    check("projectID ausente não autoriza", not auth and len(rej) == 1)
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate(directory="/repo/better-talker-a")], cfg)
    check("diretório divergente da entrada não autoriza", not auth and len(rej) == 1)
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate(), synthetic_candidate()], cfg)
    check("sessionID duplicado na API rejeitado", not auth and any("duplicado" in r["reason"] for r in rej))
    auth, rej, unk = cc.authorize_sessions([synthetic_candidate(), synthetic_candidate("ses_BBBBBBBB")], cfg)
    check("candidata não mapeada rejeitada sem expor ID/título",
          len(auth) == 1 and all("id" not in r and "title" not in r for r in rej))
    # Arquivo privado aceito; symlink/modo amplo/ID inválido rejeitados.
    with tempfile.TemporaryDirectory() as td:
        os.chmod(td, 0o700)
        p = os.path.join(td, "map.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
        os.chmod(p, 0o600)
        check("mapa privado 0600 aceito", cc.load_session_map(p) is not None)
        os.chmod(p, 0o644)
        try:
            cc.load_session_map(p)
            check("mapa com modo amplo rejeitado", False)
        except ValueError:
            check("mapa com modo amplo rejeitado", True)
        os.chmod(p, 0o600)
        link = os.path.join(td, "link.json")
        os.symlink(p, link)
        try:
            cc.load_session_map(link)
            check("symlink de mapa rejeitado", False)
        except ValueError:
            check("symlink de mapa rejeitado", True)
        bad = synthetic_map()
        bad["agents"]["A"][0]["session_id"] = "invalido"
        with open(p, "w", encoding="utf-8") as f:
            json.dump(bad, f)
        try:
            cc.load_session_map(p)
            check("formato de sessionID validado", False)
        except ValueError:
            check("formato de sessionID validado", True)
    # Variável de ambiente não amplia autoridade; DEFAULT_MAP ausente bloqueia mensagens.
    calls = []
    old_map = cc.DEFAULT_MAP
    old_env = os.environ.get("BT_COORD_SESSION_MAP")
    cc.DEFAULT_MAP = "/tmp/opencode/__no_map_for_test__.json"
    os.environ["BT_COORD_SESSION_MAP"] = "/tmp/opencode/coord-sessions.json"
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
        if args[:2] == ["git", "ls-tree"]:
            return True, ""
        if args[:2] == ["gh", "api"]:
            return True, "[]"
        return False, ""
    old = cc.sh
    old_verify = cc.verify_script
    cc.sh = fake_sh
    cc.verify_script = lambda require_clean=False: {"ok": True, "owner_uid": os.getuid()}
    try:
        cc.build(reports=True)
    finally:
        cc.sh = old
        cc.verify_script = old_verify
        cc.DEFAULT_MAP = old_map
        if old_env is None:
            os.environ.pop("BT_COORD_SESSION_MAP", None)
        else:
            os.environ["BT_COORD_SESSION_MAP"] = old_env
    check("ambiente não amplia mapa e sem mapa não consulta mensagens",
          not any("/message" in " ".join(c) for c in calls))


def contract_text(mission="MISS-A", head="a" * 40):
    return (cc_report_marker() + f"\nmission_id: {mission}\nhead_sha: {head}\n"
            "status: COMPLETED\n## Parecer\nPR #52\n" + "x" * 300)


def cc_report_marker():
    return "<!-- BT-COORD-REPORT:v1 -->"


def test_reports():
    print("[R3] Classificação de relatório e sanitização")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    base = {"type": "assistant", "time": {"created": 1, "completed": 2}}
    generic = {**base, "id": "generic", "finish": "stop",
               "content": [{"type": "text", "text": "## QA\nStatus\nPR #52\n" + "x" * 300}]}
    identified = {**base, "id": "identified", "finish": "stop",
                  "content": [{"type": "text", "text": contract_text()}]}
    interrupted = {**base, "id": "interrupted", "finish": "interrupted",
                   "content": [{"type": "text", "text": contract_text()}]}
    tools = {**base, "id": "tools", "finish": "tool-calls",
             "content": [{"type": "text", "text": contract_text()}]}
    report, skipped = cc.select_completed_report([generic])
    check("marcador genérico vira candidato não certificado",
          report["classification"] == "candidate_unverified")
    report2, _ = cc.select_completed_report([identified])
    check("contrato explícito vira relatório identificado",
          report2["classification"] == "identified_report" and report2["contract"]["mission_id"] == "MISS-A")
    check("interrompido/tool-calls não viram relatório",
          cc.select_completed_report([interrupted, tools])[0] is None)
    safe = cc.sanitize_text("token=supersecret api_key=abc123\n-----BEGIN PRIVATE KEY-----\nX\n-----END PRIVATE KEY-----")
    check("saída sanitizada", "supersecret" not in safe and "abc123" not in safe and "PRIVATE KEY-----\nX" not in safe)
    # Paginação 2x3; cursor sem order.
    calls = []
    def fake_api(args):
        calls.append(args)
        if len(calls) == 1:
            return True, json.dumps({"data": [generic], "cursor": {"next": "cursor1"}})
        return True, json.dumps({"data": [tools], "cursor": {}})
    old = cc.sh
    cc.sh = fake_api
    try:
        fetched, cursor, err = cc.fetch_assistant_messages("ses_AAAAAAAA")
    finally:
        cc.sh = old
    check("paginação seletiva limitada", len(fetched) == 2 and len(calls) == 2 and not err)
    check("limite 3 e cursor sem order", "limit=3" in calls[0][-1] and "order=" not in calls[1][-1])
    # Associação: candidato não certifica; contrato + HEAD/PR atuais associam.
    entry = {"mission_local": "MISS-A", "expected_pr": 52, "expected_branch": "feat/a", "expected_worktree": None}
    prs = [{"number": 52, "headRefName": "feat/a", "headRefOid": "a" * 40}]
    assoc = cc.association(entry, report, prs, [])
    check("PR existente não promove candidato", assoc["mission_association"] == "candidato_nao_certificado")
    assoc2 = cc.association(entry, report2, prs, [])
    check("contrato + missão + HEAD atual associam", assoc2["mission_association"] == "associada_ao_mapa_local")
    stale = cc.association(entry, report2, [{**prs[0], "headRefOid": "b" * 40}], [])
    check("HEAD divergente vira desatualizado", stale["mission_association"] == "desatualizado_ou_divergente")


def test_evidence():
    print("[R4] CI, governança remota e integridade")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    old = cc.sh
    cc.sh = lambda args: (True, "worktree /repo/x\nHEAD " + "b" * 40 + "\nbranch refs/heads/feat/x\n")
    try:
        trees = cc.worktrees()
    finally:
        cc.sh = old
    check("worktree preserva SHA completo", trees[0]["head"] == "b" * 40)
    roll = [
        {"name": "build", "status": "COMPLETED", "conclusion": "SUCCESS",
         "detailsUrl": "https://github/x/actions/runs/1/job/1"},
        {"name": "build", "status": "COMPLETED", "conclusion": "FAILURE",
         "detailsUrl": "https://github/x/actions/runs/2/job/1"},
    ]
    metas = {"1": {"headSha": "a" * 40, "event": "push", "url": "https://github/x/runs/1"},
             "2": {"headSha": "a" * 40, "event": "pull_request", "url": "https://github/x/runs/2"}}
    def run_meta(args):
        if args[:3] == ["gh", "pr", "view"]:
            return True, json.dumps({"statusCheckRollup": roll})
        if args[:3] == ["gh", "run", "view"]:
            return True, json.dumps(metas[args[3]])
        return False, ""
    cc.sh = run_meta
    try:
        result = cc.pr_checks(52, "a" * 40, detail=True)
    finally:
        cc.sh = old
    check("sucesso push não esconde falha pull_request", result["readiness"] == "red" and
          {c["event"] for c in result["checks"]} == {"push", "pull_request"})
    check("check individual ≠ prontidão de merge", result["ready_for_merge"] is False)
    # SHA inválido/ausente e evento desconhecido => unknown.
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": [roll[0]]})) if args[:3] == ["gh", "pr", "view"] else (True, json.dumps({"headSha": "invalido", "event": "push"}))
    try:
        invalid = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("SHA inválido vira desconhecido", invalid["readiness"] == "unknown" and
          not invalid["checks"][0]["certifies_current_head"])
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": [roll[0]]})) if args[:3] == ["gh", "pr", "view"] else (True, json.dumps({"headSha": "a" * 40, "event": None}))
    try:
        no_event = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("evento ausente vira desconhecido", no_event["readiness"] == "unknown")
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": []}))
    try:
        empty = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("sem checks ≠ falha de consulta", empty["query_status"] == "ok" and empty["readiness"] == "no_checks")
    cc.sh = lambda args: (False, "erro")
    try:
        failed = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("falha de consulta distinguida", failed["query_status"] == "error")
    # Governança remota delimitada.
    calls = []
    def gov_api(args):
        calls.append(args)
        if "contents/docs?ref=main" in args[2]:
            return True, json.dumps([{"name": "COORDINATOR.md", "path": "docs/COORDINATOR.md", "sha": "a" * 40}])
        return True, "# doc remoto"
    cc.sh = gov_api
    try:
        gov = cc.remote_governance(detail=True)
    finally:
        cc.sh = old
    check("governança remota com repo explícito", gov["query_status"] == "ok" and
          all("guilhermemellojw/better-talker" in c[2] for c in calls))
    check("detalhe recupera texto da main", gov["documents"][0]["text"] == "# doc remoto")
    check("script regular validado", cc.verify_script()["ok"])
    many = [{"path": f"/repo/better-talker-{i}", "head": "a" * 40, "branch": f"feat/{i}"}
            for i in range(120)]
    check("resumo limita worktrees", len(cc.relevant_worktrees(many, [], None)) <= 12)


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
        check("live: leitura externa/grep negadas", match_effect(perms, "read", "/home/other/x") == "deny" and
              match_effect(perms, "grep", "secret") == "deny")
        check("live: gh/API de mensagens negados", match_effect(perms, "shell", "gh pr view 52") == "deny" and
              match_effect(perms, "shell", "opencode api get /api/session/x/message") == "deny")
        check("live: edit/subagent/git push negados", match_effect(perms, "edit", "docs/x") == "deny" and
              match_effect(perms, "subagent", "general") == "deny" and
              match_effect(perms, "shell", "git push origin main") == "deny")
    rc = subprocess.run([sys.executable, CHECKPOINT, "--summary", "--json"],
                        capture_output=True, text=True, cwd=ROOT, timeout=180)
    try:
        cp = json.loads(rc.stdout)
        check("checkpoint main SHA completo", bool(cp.get("main_sha")) and len(cp["main_sha"]) == 40)
        check("checkpoint candidatos/metadados", isinstance(cp.get("session_candidates_count"), int))
        check("resumo com worktrees limitadas", len(cp.get("worktrees") or []) <= 12)
        check("integridade do script ok", cp.get("script_integrity", {}).get("ok") is True)
    except Exception as e:  # noqa: BLE001
        check(f"checkpoint JSON ({e})", False)
    print("[D] Imposição real: evidência na sessão experimental autorizada (ver relatório P3).")


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
    print("Categoria D depende da sessão experimental; nenhuma sessão/despacho é criado por este teste.")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
