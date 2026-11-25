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
import base64
import contextlib
import fnmatch
import hashlib
import io
import json
import os
import py_compile
import stat
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
    check("leitura/glob/grep nativos totalmente negados",
          not any(r.get("action") in {"read", "glob", "grep"} and r.get("effect") == "allow" for r in rules))
    check("sem grep '*' amplo", not has_rule(rules, "grep", "*", "allow"))
    check("sem glob '*' amplo", not has_rule(rules, "glob", "*", "allow"))
    check("allowlist de shell sem curingas",
          all("*" not in r.get("resource", "") and "?" not in r.get("resource", "")
              for r in rules if r.get("action") == "shell" and r.get("effect") == "allow"))
    check("sem git show/diff/log genéricos",
          not any(has_rule(rules, "shell", f"git {x} *", "allow") for x in ("show", "diff", "log")))
    check("sem API de sessões via shell", not has_rule(rules, "shell", "opencode api get /api/session*", "allow"))
    check("sem gh shell amplo", not any(has_rule(rules, "shell", f"gh {x} *", "allow")
                                        for x in ("pr list", "pr view", "pr checks", "run list", "run view", "repo view")))
    check("script de testes não liberado", not has_rule(rules, "shell", "python3 .opencode/scripts/test_supervisor.py *", "allow"))
    check("checkpoint exato liberado", has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --reports", "allow"))
    check("contexto determinístico liberado", has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --context", "allow"))
    check("página de contexto liberada", has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --context-next --json", "allow"))
    check("fixture sintético liberado", has_rule(rules, "shell", "python3 .opencode/scripts/coord_checkpoint.py --fixture", "allow"))


def test_matcher(fm):
    print("[B] Simulação do matcher (lógica; não imposição em execução)")
    rules = fm["permissions"]
    cases = [
        ("read", "docs/COORDINATOR.md", "deny"),
        ("read", ".opencode/scripts/coord_checkpoint.py", "deny"),
        ("read", "android/app/src/main/java/X.kt", "deny"),
        ("read", "/home/other/project/docs/COORDINATOR.md", "deny"),
        ("read", "docs/../../.env", "deny"),
        ("read", "docs/../../other/secret.txt", "deny"),
        ("read", ".env.production", "deny"),
        ("read", "service.json", "deny"),
        ("read", "android/app/src/publicApp/google-services.json", "deny"),
        ("glob", "docs/**", "deny"),
        ("glob", "**/*.kt", "deny"),
        ("grep", "CopilotViewModel", "deny"),
        ("question", "*", "allow"),
        ("edit", "docs/COORDINATOR.md", "deny"),
        ("subagent", "general", "deny"),
        ("execute", "*", "deny"),
        ("shell", "git status --short --branch", "deny"),
        ("shell", "git ls-remote origin main", "deny"),
        ("shell", "git show HEAD:docs/COORDINATOR.md", "deny"),
        ("shell", "git diff --no-index /etc/passwd docs/COORDINATOR.md", "deny"),
        ("shell", "gh pr view 52 --json state", "deny"),
        ("shell", "gh pr view 52 --repo other/repo", "deny"),
        ("shell", "gh api repos/other/repo/contents/docs", "deny"),
        ("shell", "opencode api get /api/session/x/message?limit=1", "deny"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --reports --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --context", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --context --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --context-next", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --context-next --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --context-next --page 2", "deny"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --fixture --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --fixture --detail --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --fixture --reports --json", "allow"),
        ("shell", "python3 .opencode/scripts/coord_checkpoint.py --session-map /etc/passwd", "deny"),
        ("shell", "python3 .opencode/scripts/test_supervisor.py --live", "deny"),
        ("shell", "git push origin main", "deny"),
        ("shell", "gh pr merge 57", "deny"),
        ("shell", "rm -rf /", "deny"),
        ("shell", "adb shell", "deny"),
        ("shell", "bash .opencode/scripts/coord_checkpoint.py", "deny"),
        ("shell", "eval git push", "deny"),
        ("webfetch", "https://opencode.ai/docs/agents", "deny"),
        ("websearch", "OpenCode", "deny"),
        ("external_directory", "/home/user/other", "deny"),
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
        # Symlink em ancestral também é rejeitado, não apenas no arquivo/pai imediato.
        real = os.path.join(td, "real")
        nested = os.path.join(real, "nested")
        os.mkdir(real, 0o700)
        os.mkdir(nested, 0o700)
        nested_map = os.path.join(nested, "map.json")
        with open(nested_map, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
        os.chmod(nested_map, 0o600)
        alias = os.path.join(td, "alias")
        os.symlink(real, alias)
        try:
            cc.load_session_map(os.path.join(alias, "nested", "map.json"))
            check("symlink em ancestral do mapa rejeitado", False)
        except ValueError:
            check("symlink em ancestral do mapa rejeitado", True)
        bad = synthetic_map()
        bad["project"]["allowed_directories"][0] = "/repo/better-talker/../better-talker"
        with open(p, "w", encoding="utf-8") as f:
            json.dump(bad, f)
        try:
            cc.load_session_map(p)
            check("diretório não canônico no mapa rejeitado", False)
        except ValueError:
            check("diretório não canônico no mapa rejeitado", True)
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
    safe = cc.sanitize_text("to" + "ken=FAKE_VALUE api" + "_key=FAKE_KEY\n-----BEGIN PRIVATE KEY-----\nX\n-----END PRIVATE KEY-----")
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
    check("contrato + missão + HEAD atual associam", assoc2["mission_association"] == "concluida" and
          assoc2["mission_completed"] is True)
    stale = cc.association(entry, report2, [{**prs[0], "headRefOid": "b" * 40}], [])
    check("HEAD divergente vira desatualizado", stale["mission_association"] == "desatualizado_ou_divergente")
    in_progress = {**report2, "contract": {**report2["contract"], "status": "IN_PROGRESS"}}
    running = cc.association(entry, in_progress, prs, [])
    check("IN_PROGRESS não conclui missão", running["mission_association"] == "associada_em_andamento" and
          running["mission_completed"] is False)
    blocked = {**report2, "contract": {**report2["contract"], "status": "BLOCKED"}}
    blocked_assoc = cc.association(entry, blocked, prs, [])
    check("BLOCKED preserva bloqueio e não conclui", blocked_assoc["mission_association"] == "associada_bloqueada"
          and blocked_assoc["mission_completed"] is False)
    check("sanitização não garante ausência de segredos", report2["secret_free_guaranteed"] is False)


def synthetic_governance_api():
    """API Git sintética com objetos e SHAs reais calculados em memória."""
    commit_sha, root_sha, docs_sha = "a" * 40, "b" * 40, "c" * 40
    blob_bytes = {name: f"# {name}\ntoken=FAKE_SECRET\n".encode("utf-8")
                  for name in ("COORDINATOR.md", "DECISIONS.md", "TEAM_COORDINATION.md",
                               "ACTIVE_TASKS.md", "DELIVERY_POLICY.md")}
    blob_shas = {name: hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()
                 for name, data in blob_bytes.items()}
    state = {"commit_sha": commit_sha, "root_sha": root_sha, "docs_sha": docs_sha,
             "blob_bytes": blob_bytes, "blob_shas": blob_shas,
             "docs_mode": "040000", "docs_type": "tree", "doc_mode": "100644",
             "doc_type": "blob", "root_truncated": False, "docs_truncated": False,
             "omit": None, "duplicate": None}
    calls = []
    def fake(args):
        calls.append(args)
        endpoint = args[2]
        if endpoint == f"repos/guilhermemellojw/better-talker/git/commits/{commit_sha}":
            return True, json.dumps({"sha": commit_sha, "tree": {"sha": root_sha}})
        if endpoint == f"repos/guilhermemellojw/better-talker/git/trees/{root_sha}":
            entry = {"path": "docs", "mode": state["docs_mode"],
                     "type": state["docs_type"], "sha": docs_sha}
            return True, json.dumps({"sha": root_sha, "truncated": state["root_truncated"],
                                     "tree": [entry]})
        if endpoint == f"repos/guilhermemellojw/better-talker/git/trees/{docs_sha}":
            entries = [{"path": name, "mode": state["doc_mode"],
                        "type": state["doc_type"], "sha": blob_shas[name]}
                       for name in blob_shas if name != state["omit"]]
            if state["duplicate"]:
                entries.append({**next(e for e in entries if e["path"] == state["duplicate"])})
            return True, json.dumps({"sha": docs_sha, "truncated": state["docs_truncated"],
                                     "tree": entries})
        for name, sha in blob_shas.items():
            if endpoint == f"repos/guilhermemellojw/better-talker/git/blobs/{sha}":
                content = base64.encodebytes(state["blob_bytes"][name]).decode("ascii")
                return True, json.dumps({"sha": sha, "encoding": "base64", "content": content})
        # Resposta enganosa da API Contents, usada apenas para provar que não é consultada.
        if "/contents/" in endpoint:
            return True, json.dumps([{"name": name, "type": "file", "sha": blob_shas[name]}
                                     for name in blob_shas])
        if endpoint.endswith("/git/commits/main"):
            return True, json.dumps({"sha": "d" * 40, "tree": {"sha": "e" * 40}})
        return False, ""
    return fake, calls, state


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
    # Evento conhecido e check informacional não são gate.
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": [{**roll[0], "name": "propose"}]})) if args[:3] == ["gh", "pr", "view"] else (True, json.dumps({"headSha": "a" * 40, "event": "schedule"}))
    try:
        informational = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("schedule/propose não certificam gate", informational["readiness"] == "unknown" and
          not informational["checks"][0]["gate_relevant"] and
          not informational["checks"][0]["certifies_current_head"])
    cc.sh = lambda args: (True, json.dumps({"statusCheckRollup": [roll[0]]})) if args[:3] == ["gh", "pr", "view"] else (True, json.dumps({"headSha": "a" * 40, "event": "push"}))
    try:
        push_only = cc.pr_checks(52, "a" * 40)
    finally:
        cc.sh = old
    check("evento push conhecido não vira gate de PR", push_only["readiness"] == "unknown"
          and not push_only["checks"][0]["gate_relevant"]
          and push_only["required_gates_verified"] is False)
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
    check("script regular validado", cc.verify_script()["ok"])
    # Metadados/revisão local vêm de objetos Git; nenhuma leitura da worktree.
    import builtins
    old_open = builtins.open
    def local_read_forbidden(*args, **kwargs):
        raise AssertionError("leitura local de documento proibida")
    def git_only(args):
        if args[:2] == ["git", "ls-tree"]:
            return True, "100644 blob " + "b" * 40 + "\tdocs/ACTIVE_TASKS.md"
        if args[:2] == ["git", "show"]:
            return True, "**Revisão:** fixture R1"
        if args[:2] == ["gh", "api"]:
            return False, ""
        return False, ""
    builtins.open = local_read_forbidden
    cc.sh = git_only
    try:
        local_gov = cc.governance("b" * 40, "a" * 40)
    finally:
        builtins.open = old_open
        cc.sh = old
    check("governança local não abre arquivo da worktree",
          local_gov["active_tasks_revision_local_blob"] == "**Revisão:** fixture R1")
    many = [{"path": f"/repo/better-talker-{i}", "head": "a" * 40, "branch": f"feat/{i}"}
            for i in range(120)]
    check("resumo limita worktrees", len(cc.relevant_worktrees(many, [], None)) <= 12)


def test_governance_git():
    print("[P5] Governança por commit/árvore/blob Git")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    old = cc.sh
    def invoke(mutator=None, detail=True, ref=None):
        fake, calls, state = synthetic_governance_api()
        if mutator:
            mutator(state)
        cc.sh = fake
        try:
            result = cc.remote_governance(detail=detail, ref=ref or state["commit_sha"])
        finally:
            cc.sh = old
        return result, calls, state
    gov, calls, state = invoke()
    check("arquivos regulares autorizados são validados", gov["query_status"] == "ok"
          and len(gov["documents"]) == 5
          and all(d["mode"] == "100644" and d["type"] == "blob" and d["git_sha_verified"]
                  for d in gov["documents"]))
    check("conteúdo vem do SHA do blob e passa na integridade Git",
          all(d["text"].startswith(f"# {d['path'].split('/')[-1]}") for d in gov["documents"]))
    check("consulta detalhada tem limite fixo de oito chamadas", len(calls) == 8
          and gov["max_queries"] == 8)
    check("nenhuma consulta usa Contents ou referência mutável",
          all("/contents/" not in c[2] and "ref=" not in c[2] and not c[2].endswith("/main")
              for c in calls))
    summary, summary_calls, _ = invoke(detail=False)
    check("checkpoint resumido preserva limite de três metadados", summary["query_status"] == "ok"
          and len(summary_calls) == 3 and summary["max_queries"] == 3
          and all("text" not in d for d in summary["documents"]))
    invalid, invalid_calls, _ = invoke(ref="main")
    check("SHA inválido não consulta API", invalid["query_status"] == "unknown" and not invalid_calls)
    linked, linked_calls, _ = invoke(lambda s: s.update(doc_mode="120000"))
    check("symlink apresentado como type=file pelo Contents é rejeitado",
          linked["query_status"] == "incomplete" and not any("/contents/" in c[2] for c in linked_calls)
          and not any("/git/blobs/" in c[2] for c in linked_calls))
    dir_link, dir_calls, _ = invoke(lambda s: s.update(docs_mode="120000", docs_type="blob"))
    check("symlink de diretório docs é rejeitado", dir_link["query_status"] == "incomplete"
          and not any(f"/git/trees/{state['docs_sha']}" in c[2] for c in dir_calls))
    submodule, _, _ = invoke(lambda s: s.update(doc_mode="160000", doc_type="commit"))
    check("submódulo é rejeitado", submodule["query_status"] == "incomplete")
    executable, _, _ = invoke(lambda s: s.update(doc_mode="100755"))
    check("documento executável é rejeitado", executable["query_status"] == "incomplete")
    unknown, _, _ = invoke(lambda s: s.update(doc_type="unknown"))
    check("tipo de objeto desconhecido é rejeitado", unknown["query_status"] == "incomplete")
    ambiguous, _, _ = invoke(lambda s: s.update(duplicate="COORDINATOR.md"))
    check("entrada de documento ambígua é rejeitada", ambiguous["query_status"] == "incomplete")
    divergent, divergent_calls, _ = invoke(
        lambda s: s["blob_bytes"].update({"COORDINATOR.md": b"# conteudo divergente\n"}))
    check("blob divergente falha na integridade Git", divergent["query_status"] == "incomplete"
          and "divergente" in divergent["reason"] and len(divergent_calls) == 4)
    incomplete, _, _ = invoke(lambda s: s.update(omit="DELIVERY_POLICY.md"))
    check("árvore incompleta é rejeitada", incomplete["query_status"] == "incomplete")
    truncated, _, _ = invoke(lambda s: s.update(docs_truncated=True))
    check("árvore truncada é rejeitada", truncated["query_status"] == "incomplete")
    root_truncated, _, _ = invoke(lambda s: s.update(root_truncated=True))
    check("árvore raiz truncada é rejeitada", root_truncated["query_status"] == "incomplete")
    changed_main, changed_calls, changed_state = invoke()
    check("mudança da main não interfere na recuperação fixada",
          changed_main["query_status"] == "ok"
          and any(changed_state["commit_sha"] in c[2] for c in changed_calls)
          and not any(c[2].endswith("/main") or "ref=main" in c[2] for c in changed_calls))


def test_context():
    print("[P5] Contexto determinístico e integridade")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    old_sh, old_verify = cc.sh, cc.verify_script
    calls = []
    def forbidden(args):
        calls.append(args)
        raise AssertionError("chamada externa inesperada")
    cc.sh = forbidden
    cc.verify_script = lambda require_clean=False: {"ok": False, "reason": "fixture test"}
    try:
        try:
            cc.build_context()
            check("contexto falha fechado com script sujo", False)
        except RuntimeError:
            check("contexto falha fechado com script sujo", not calls)
        fake_git, git_calls, state = synthetic_governance_api()
        def fake_api(args):
            calls.append(args)
            if args[:2] == ["git", "ls-remote"]:
                return True, "a" * 40 + "\trefs/heads/main"
            return fake_git(args)
        cc.sh = fake_api
        cc.verify_script = lambda require_clean=False: {"ok": True, "git_clean": True}
        ctx = cc.build_context()
        state["doc_mode"] = "120000"
        failed_ctx = cc.build_context()
    finally:
        cc.sh, cc.verify_script = old_sh, old_verify
    check("contexto fixado ao SHA observado", ctx["main_sha"] == "a" * 40
          and all("/contents/" not in c[2] and "ref=" not in c[2] and not c[2].endswith("/main")
                  for c in calls if c[:2] == ["gh", "api"]))
    check("main observada no repositório fixo", any(c[:2] == ["git", "ls-remote"]
          and cc.MAIN_REMOTE in c for c in calls))
    check("contexto completo exige os cinco documentos", ctx["documents_complete"]
          and len(ctx["documents"]) == 5)
    check("falha estrutural deixa documents_complete=false",
          failed_ctx["documents_complete"] is False and failed_ctx["query_status"] == "incomplete")
    check("contexto sanitiza sem garantir ausência de segredos",
          "FAKE_SECRET" not in ctx["documents"][0]["text"]
          and ctx["documents"][0]["secret_free_guaranteed"] is False)


def test_context_pagination():
    print("[OP2] Contexto paginado e snapshot consistente")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    docs = []
    for index, name in enumerate(cc.GOVERNANCE_DOCS):
        text = f"# {name}\n"
        if index == 0:
            text += "".join(f"linha sintética {n} — conteúdo de governança\n" for n in range(140))
        raw = text.encode("utf-8")
        docs.append({"path": f"docs/{name}", "blob_sha": hashlib.sha1(
            f"blob {len(raw)}\0".encode("ascii") + raw).hexdigest(),
            "mode": "100644", "type": "blob", "status": "verified",
            "git_sha_verified": True, "text": text})
    snapshot = cc._make_context_snapshot("a" * 40, docs)
    manifest = cc._context_manifest(snapshot)
    check("manifesto completo e compacto", manifest["documents_complete"]
          and manifest["document_count"] == 5 and manifest["total_pages"] > 5
          and all("text" not in d for d in manifest["documents"]))
    check("documento pequeno em uma página", len(snapshot["documents"][1]["pages"]) == 1)
    check("documento longo em múltiplas páginas", len(snapshot["documents"][0]["pages"]) > 1)
    all_pages = cc._flatten_context_pages(snapshot)
    check("páginas respeitam bytes e linhas", all(p["bytes"] <= cc.CONTEXT_PAGE_MAX_BYTES
          and p["lines"] <= cc.CONTEXT_PAGE_MAX_LINES for _, _, p in all_pages))
    rendered_sizes = [len(json.dumps(cc._context_page(snapshot, i), ensure_ascii=False).encode("utf-8"))
                      for i in range(len(all_pages))]
    check("saída JSON da página abaixo do truncamento observado", max(rendered_sizes) < 20000)
    missing = {**snapshot, "documents": [{**d, "pages": list(d["pages"])} for d in snapshot["documents"]]}
    missing["documents"][0]["pages"].pop()
    check("página ausente invalida o snapshot", cc._context_page(missing, 0)["documents_complete"] is False)
    check("página fora do intervalo é rejeitada",
          cc._context_page(snapshot, len(all_pages))["documents_complete"] is False)
    divergent = {**snapshot, "documents": [{**d, "blob_sha": "b" * 40 if i == 0 else d["blob_sha"]}
                                           for i, d in enumerate(snapshot["documents"])]}
    check("SHA divergente invalida o snapshot", cc._context_page(divergent, 0)["documents_complete"] is False)
    old_cache, old_verify, old_main = cc.CONTEXT_CACHE, cc.verify_script, cc.main_sha
    with tempfile.TemporaryDirectory() as td:
        os.chmod(td, 0o700)
        cc.CONTEXT_CACHE = os.path.join(td, "context.json")
        cc.verify_script = lambda require_clean=False: {"ok": True, "git_clean": True}
        def main_changed():
            raise AssertionError("paginação não pode consultar a main")
        cc.main_sha = main_changed
        try:
            cc._store_context_snapshot(snapshot)
            emitted = []
            for _ in range(len(all_pages)):
                output = io.StringIO()
                if not cc.emit_context_next(json_output=True, stdout=output):
                    raise AssertionError("emissão falhou")
                emitted.append(json.loads(output.getvalue()))
            output = io.StringIO()
            if not cc.emit_context_next(json_output=True, stdout=output):
                raise AssertionError("conclusão falhou")
            complete = json.loads(output.getvalue())
        finally:
            cc.CONTEXT_CACHE, cc.verify_script, cc.main_sha = old_cache, old_verify, old_main
    check("cursor entrega páginas únicas e sequenciais",
          [p.get("page_number") for p in emitted] == list(range(1, len(all_pages) + 1)))
    check("última página e emissão completa são explícitas", emitted[-1]["last_page"]
          and complete["all_pages_emitted"] and complete["emitted_pages"] == len(all_pages))
    check("emissão não confirma recebimento", complete["receipt_confirmed"] is False
          and complete["context_recovered"] is False)
    check("chamada extra não repete página", complete["mode"] == "context_complete"
          and "text" not in complete)
    check("mudança da main não mistura o snapshot", all(p["main_sha"] == "a" * 40
          and p["snapshot_id"] == snapshot["snapshot_id"] for p in emitted))
    check("páginas do cache não fazem chamadas externas", all(p["external_calls"] == 0
          and p["query_count"] == 0 for p in emitted))


def test_context_manifest_cache():
    print("[OP2] Manifesto, cache privado e economia de consultas")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    old_cache, old_sh, old_verify, old_main = cc.CONTEXT_CACHE, cc.sh, cc.verify_script, cc.main_sha
    with tempfile.TemporaryDirectory() as td:
        os.chmod(td, 0o700)
        cc.CONTEXT_CACHE = os.path.join(td, "context.json")
        fake_git, git_calls, state = synthetic_governance_api()
        calls = []
        def fake_api(args):
            calls.append(args)
            if args[:2] == ["git", "ls-remote"]:
                return True, "a" * 40 + "\trefs/heads/main"
            return fake_git(args)
        cc.sh = fake_api
        cc.verify_script = lambda require_clean=False: {"ok": True, "git_clean": True}
        try:
            manifest = cc.build_context_manifest()
            cache_mode = stat.S_IMODE(os.stat(cc.CONTEXT_CACHE).st_mode)
            dir_mode = stat.S_IMODE(os.stat(td).st_mode)
            cc.main_sha = lambda: (_ for _ in ()).throw(AssertionError("main consultada na paginação"))
            pages = []
            for _ in range(manifest["total_pages"]):
                output = io.StringIO()
                if not cc.emit_context_next(json_output=True, stdout=output):
                    raise AssertionError("emissão falhou")
                pages.append(json.loads(output.getvalue()))
            output = io.StringIO()
            if not cc.emit_context_next(json_output=True, stdout=output):
                raise AssertionError("conclusão falhou")
            complete = json.loads(output.getvalue())
        finally:
            cc.CONTEXT_CACHE, cc.sh, cc.verify_script, cc.main_sha = old_cache, old_sh, old_verify, old_main
    check("manifesto criado com oito GETs Git e tamanho compacto",
          manifest["documents_complete"] and len(git_calls) == 8
          and len(json.dumps(manifest, ensure_ascii=False).encode("utf-8")) < 10000)
    check("cache privado respeita 0700/0600", dir_mode == 0o700 and cache_mode == 0o600)
    check("páginas posteriores não consultam GitHub", all(p["external_calls"] == 0
          and p["query_count"] == 0 for p in pages))
    check("snapshot permanece o mesmo após a criação", all(p["snapshot_id"] == manifest["snapshot_id"]
          and p["main_sha"] == "a" * 40 for p in pages))
    check("emissão completa só aparece após todas as páginas", complete["all_pages_emitted"]
          and complete["emitted_pages"] == manifest["total_pages"]
          and complete["receipt_confirmed"] is False)


def test_context_delivery_cli():
    print("[OP2/P2] Emissão, flush e persistência do cursor")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    docs = []
    for index, name in enumerate(cc.GOVERNANCE_DOCS):
        text = f"# {name}\n" + ("".join(f"linha {n}\n" for n in range(40)) if index == 0 else "pequeno\n")
        raw = text.encode("utf-8")
        docs.append({"path": f"docs/{name}", "blob_sha": hashlib.sha1(
            f"blob {len(raw)}\0".encode("ascii") + raw).hexdigest(),
            "mode": "100644", "type": "blob", "status": "verified",
            "git_sha_verified": True, "text": text})
    snapshot = cc._make_context_snapshot("a" * 40, docs)
    total = len(cc._flatten_context_pages(snapshot))
    old_cache, old_verify, old_commit = cc.CONTEXT_CACHE, cc.verify_script, cc.commit_context_cursor

    class FailingStdout:
        def __init__(self, failure):
            self.failure, self.text = failure, ""
        def write(self, value):
            if self.failure == "write":
                raise OSError("stdout indisponível")
            self.text += value
        def flush(self):
            if self.failure == "flush":
                raise OSError("flush indisponível")

    class SinkStdout:
        def write(self, value):
            return len(value)
        def flush(self):
            return None

    class SimulatedCrash(BaseException):
        pass

    with tempfile.TemporaryDirectory() as td:
        os.chmod(td, 0o700)
        cc.CONTEXT_CACHE = os.path.join(td, "context.json")
        cc.verify_script = lambda require_clean=False: {"ok": True, "git_clean": True}
        def reset():
            cc._store_context_snapshot(snapshot)
        def emit(stdout=None, serializer=None):
            return cc.emit_context_next(json_output=True, stdout=stdout, serializer=serializer)
        try:
            reset()
            output = io.StringIO()
            with contextlib.redirect_stderr(io.StringIO()):
                ok = emit(output, serializer=lambda value: (_ for _ in ()).throw(ValueError("serialização")))
            check("falha de serialização não avança cursor", not ok and output.getvalue() == ""
                  and cc._read_context_cache()["next_index"] == 0)

            reset()
            old_argv, old_dumps = sys.argv, cc.json.dumps
            def fail_dumps(*args, **kwargs):
                raise ValueError("serialização CLI")
            cc.json.dumps = fail_dumps
            sys.argv = ["coord_checkpoint.py", "--context-next", "--json"]
            cli_output = io.StringIO()
            exit_code = None
            try:
                with contextlib.redirect_stdout(cli_output), contextlib.redirect_stderr(io.StringIO()):
                    try:
                        cc.main()
                    except SystemExit as e:
                        exit_code = e.code
            finally:
                sys.argv, cc.json.dumps = old_argv, old_dumps
            check("caminho main da CLI falha sem avançar cursor", exit_code == 2
                  and cli_output.getvalue() == "" and cc._read_context_cache()["next_index"] == 0)

            reset()
            with contextlib.redirect_stderr(io.StringIO()):
                ok = emit(FailingStdout("write"))
            check("falha de escrita em stdout não avança cursor", not ok
                  and cc._read_context_cache()["next_index"] == 0)

            reset()
            with contextlib.redirect_stderr(io.StringIO()):
                ok = emit(FailingStdout("flush"))
            check("falha de flush não avança cursor", not ok
                  and cc._read_context_cache()["next_index"] == 0)

            reset()
            output = io.StringIO()
            try:
                emit(output, serializer=lambda value: (_ for _ in ()).throw(SimulatedCrash()))
            except SimulatedCrash:
                pass
            check("interrupção antes da emissão não avança cursor", output.getvalue() == ""
                  and cc._read_context_cache()["next_index"] == 0)

            reset()
            output = io.StringIO()
            def crash_commit(*args):
                raise SimulatedCrash()
            cc.commit_context_cursor = crash_commit
            try:
                emit(output)
            except SimulatedCrash:
                pass
            finally:
                cc.commit_context_cursor = old_commit
            first = json.loads(output.getvalue())
            cursor_before_repeat = cc._read_context_cache()["next_index"]
            repeated_output = io.StringIO()
            if not emit(repeated_output):
                raise AssertionError("reemissão falhou")
            repeated = json.loads(repeated_output.getvalue())
            identity = ("snapshot_id", "main_sha", "page_number", "page_sha256")
            check("interrupção após emissão repete página sem perdê-la",
                  cursor_before_repeat == 0 and cc._read_context_cache()["next_index"] == 1
                  and all(first[k] == repeated[k] for k in identity)
                  and first["document"]["blob_sha"] == repeated["document"]["blob_sha"])

            reset()
            received = []
            for _ in range(total - 1):
                output = io.StringIO()
                if not emit(output):
                    raise AssertionError("emissão sequencial falhou")
                received.append(json.loads(output.getvalue()))
            if not emit(SinkStdout()):
                raise AssertionError("última emissão falhou")
            output = io.StringIO()
            if not emit(output):
                raise AssertionError("conclusão falhou")
            complete = json.loads(output.getvalue())
            verification = cc.verify_received_context_pages({
                "snapshot_id": snapshot["snapshot_id"], "main_sha": snapshot["main_sha"],
                "documents_complete": True, "total_pages": total,
                "documents": [{"path": d["path"], "blob_sha": d["blob_sha"], "pages": len(d["pages"])}
                              for d in snapshot["documents"]]}, received)
            check("página final não recebida impede conclusão do consumidor",
                  complete["all_pages_emitted"] and complete["receipt_confirmed"] is False
                  and complete["context_recovered"] is False and not verification["context_recovered"])

            reset()
            received = []
            for _ in range(total):
                output = io.StringIO()
                if not emit(output):
                    raise AssertionError("emissão completa falhou")
                received.append(json.loads(output.getvalue()))
            manifest = {"snapshot_id": snapshot["snapshot_id"], "main_sha": snapshot["main_sha"],
                        "documents_complete": True, "total_pages": total,
                        "documents": [{"path": d["path"], "blob_sha": d["blob_sha"], "pages": len(d["pages"])}
                                      for d in snapshot["documents"]]}
            full = cc.verify_received_context_pages(manifest, received)
            duplicate = cc.verify_received_context_pages(manifest, received + [received[1]])
            missing = cc.verify_received_context_pages(manifest, received[:2] + received[3:])
            divergent = [dict(page) for page in received]
            divergent[1] = {**divergent[1], "snapshot_id": "b" * 40}
            changed = cc.verify_received_context_pages(manifest, divergent)
            truncated = [dict(page) for page in received]
            truncated[0] = {**truncated[0], "truncated": True}
            cut = cc.verify_received_context_pages(manifest, truncated)
            forged = [dict(page) for page in received]
            forged[1] = {**forged[1], "text": forged[1]["text"] + "forjado"}
            forged_result = cc.verify_received_context_pages(manifest, forged)
            check("recuperação sequencial completa é verificável pelo consumidor", full["context_recovered"])
            check("duplicidade idêntica pode ser descartada", duplicate["context_recovered"])
            check("página ausente ou divergente impede recuperação", not missing["context_recovered"]
                  and not changed["context_recovered"] and not cut["context_recovered"]
                  and not forged_result["context_recovered"])
        finally:
            cc.CONTEXT_CACHE, cc.verify_script, cc.commit_context_cursor = old_cache, old_verify, old_commit


def test_fixture():
    print("[P4] Modo fixture sem chamadas externas")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    calls, audit_events = [], []
    auditing = False
    def forbidden(args):
        calls.append(args)
        raise AssertionError("chamada externa proibida no fixture")
    def audit(event, args):
        if auditing and event in {"subprocess.Popen", "os.system", "socket.connect",
                                  "socket.getaddrinfo", "open"}:
            audit_events.append(event)
    sys.addaudithook(audit)
    old = cc.sh
    cc.sh = forbidden
    auditing = True
    try:
        cp = cc.fixture_checkpoint(detail=True, reports=True)
        ctx = cc.build_context(fixture=True)
        fixture_page = cc._context_page(ctx, 0)
    finally:
        auditing = False
        cc.sh = old
    check("fixture não chama shell/API", not calls)
    check("fixture não abre arquivo, subprocesso ou rede", not audit_events)
    check("fixture tem sessões/PRs/checks/worktrees sintéticos", len(cp["agents"]) == 5 and
          len(cp["open_prs"]) == 2 and len(cp["worktrees"]) == 2 and cp["query_count"] == 0)
    check("fixture não contém SHA real da main", cp["main_sha"] == "a" * 40)
    check("contexto fixture sem chamadas", ctx["external_calls"] == 0 and len(ctx["documents"]) == 5)
    check("página fixture sem chamadas e com snapshot sintético",
          fixture_page["documents_complete"] and fixture_page["fixture"] and fixture_page["external_calls"] == 0)
    check("fixture COMPLETED só associa dados sintéticos",
          cp["agents"][0]["provenance"]["mission_completed"] is True and cp["fixture"] is True)


def test_docs():
    print("[A] Documentos oficiais")
    for d in ["docs/COORDINATOR.md", "docs/DECISIONS.md", "docs/TEAM_COORDINATION.md",
              "docs/ACTIVE_TASKS.md", "docs/DELIVERY_POLICY.md"]:
        check(d, os.path.exists(os.path.join(ROOT, d)))
    with open(os.path.join(ROOT, ".opencode", "README.md"), encoding="utf-8") as f:
        readme = f.read()
    checks = ("INC-COORD-03-001", "intenção", "operação", "destinatário", "UNKNOWN",
              "evidência positiva", "autorização humana", "ledger completo não é implementado")
    check("incidente e protocolo UNKNOWN formalizados", all(term in readme for term in checks))
    check("paginação e reinício do host documentados",
          all(term in readme for term in ("--context-next", "10.000 bytes", "16 linhas",
                                          "INC-COORD-03-OP1-HOST-RESTART", "causa é desconhecida")))


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
        check("live: leitura nativa negada", match_effect(perms, "read", "docs/COORDINATOR.md") == "deny" and
              match_effect(perms, "glob", "docs/**") == "deny")
        check("live: gh/API de mensagens negados", match_effect(perms, "shell", "gh pr view 52") == "deny" and
              match_effect(perms, "shell", "opencode api get /api/session/x/message") == "deny")
        check("live: contexto/fixture permitidos", match_effect(perms, "shell", "python3 .opencode/scripts/coord_checkpoint.py --context") == "allow" and
              match_effect(perms, "shell", "python3 .opencode/scripts/coord_checkpoint.py --context-next --json") == "allow" and
              match_effect(perms, "shell", "python3 .opencode/scripts/coord_checkpoint.py --fixture") == "allow")
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
    test_governance_git()
    test_context()
    test_context_pagination()
    test_context_manifest_cache()
    test_context_delivery_cli()
    test_fixture()
    test_docs()
    if args.live:
        test_live()
    print(f"\nRESULTADO: {PASS} passed, {FAIL} failed")
    print("Categoria D depende da sessão experimental; nenhuma sessão/despacho é criado por este teste.")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
