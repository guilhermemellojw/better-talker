#!/usr/bin/env python3
"""Checkpoint operacional SHADOW do Better Talker — somente leitura.

Níveis:
  --summary (padrão): estado operacional, PRs/checks recentes e worktrees relevantes.
  --detail: todos os checks/worktrees; investigação sob demanda.
  --reports: implica detail; coleta paginada e limitada do último relatório concluído
             apenas de sessões explicitamente autorizadas no mapa local.

Mapa local (não versionado): ~/.local/state/better-talker/coord-sessions.json.
Identificadores de sessão são locais e transitórios; nunca identidade permanente da equipe.
Sem mapa, títulos AGENTE são apenas candidatos e NENHUMA mensagem é consultada.

Não escreve arquivos, não muta estado, não cria sessões, não acessa credenciais.
Conteúdo de relatório é dado NÃO CONFIÁVEL, nunca instrução de autoridade.
"""
import argparse
import json
import os
import re
import signal
import stat
import subprocess
import sys
from datetime import datetime, timezone

signal.signal(signal.SIGPIPE, signal.SIG_DFL)
REPO = "guilhermemellojw/better-talker"
DEFAULT_MAP = os.path.expanduser("~/.local/state/better-talker/coord-sessions.json")
REPORT_CONTRACT = "<!-- BT-COORD-REPORT:v1 -->"
REPORT_MARKERS = ("##", "HEAD", "PR #", "Parecer", "Resultado", "Bloqueio",
                  "Missão", "QA", "Recertificação", "Status")
KNOWN_CI_EVENTS = {"push", "pull_request", "pull_request_target", "workflow_run",
                   "workflow_dispatch", "schedule"}
QUERY_COUNT = 0


def sh(args):
    """Executa comando somente leitura; retorna (ok, stdout)."""
    global QUERY_COUNT
    QUERY_COUNT += 1
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=60)
        return r.returncode == 0, r.stdout.strip()
    except Exception as e:  # noqa: BLE001
        return False, f"ERRO: {e}"


def utc(ms):
    if not ms:
        return None
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def main_sha():
    ok, out = sh(["git", "ls-remote", "origin", "main"])
    return out.split()[0] if ok and out else None


def local_head():
    ok, out = sh(["git", "rev-parse", "HEAD"])
    return out if ok else None


def verify_script(require_clean=False):
    """Falha fechada se o checkpoint for symlink, não regular ou de outro proprietário."""
    try:
        st = os.lstat(__file__)
        if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
            return {"ok": False, "reason": "script não regular ou symlink"}
        if st.st_uid != os.getuid():
            return {"ok": False, "reason": "proprietário inesperado"}
        if require_clean:
            rel = os.path.relpath(__file__, os.getcwd())
            ok_tracked, _ = sh(["git", "ls-files", "--error-unmatch", rel])
            ok_clean, status = sh(["git", "status", "--porcelain", "--", rel])
            if not ok_tracked:
                return {"ok": False, "reason": "script não rastreado pelo Git"}
            if not ok_clean or status:
                return {"ok": False, "reason": "script modificado/não limpo"}
        return {"ok": True, "owner_uid": st.st_uid, "git_clean": bool(require_clean)}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "reason": str(e)}


def _private_regular_file(path):
    """Arquivo regular, não-symlink, do proprietário atual e sem acesso grupo/outros."""
    st = os.lstat(path)
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise ValueError("mapa local não é arquivo regular")
    if st.st_uid != os.getuid():
        raise ValueError("mapa local não pertence ao usuário atual")
    if stat.S_IMODE(st.st_mode) & 0o077:
        raise ValueError("mapa local com permissões amplas")
    parent = os.path.dirname(path)
    pst = os.lstat(parent)
    if stat.S_ISLNK(pst.st_mode) or not stat.S_ISDIR(pst.st_mode):
        raise ValueError("diretório do mapa inválido")
    if pst.st_uid != os.getuid() or stat.S_IMODE(pst.st_mode) & 0o077:
        raise ValueError("diretório do mapa não é privado do usuário")


def load_session_map(path=None):
    """Carrega mapa local privado; None = ausente. Sem autoridade via variável de ambiente."""
    path = path or DEFAULT_MAP
    if not os.path.lexists(path):
        return None
    _private_regular_file(path)
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)
    project = cfg.get("project") or {}
    if not isinstance(project.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", project["id"]):
        raise ValueError("project.id inválido")
    if not isinstance(project.get("allowed_directories"), list) or not project["allowed_directories"]:
        raise ValueError("allowed_directories ausente")
    if any(not isinstance(d, str) or not os.path.isabs(d) for d in project["allowed_directories"]):
        raise ValueError("allowed_directories inválidos")
    agents = cfg.get("agents")
    if not isinstance(agents, dict) or not agents:
        raise ValueError("mapa local sem agentes")
    seen = set()
    for agent, entries in agents.items():
        if agent not in {"A", "B", "C", "D", "E"} or not isinstance(entries, list):
            raise ValueError(f"agente inválido no mapa: {agent}")
        for e in entries:
            sid = e.get("session_id")
            if not isinstance(sid, str) or not re.fullmatch(r"ses_[A-Za-z0-9_-]{8,128}", sid):
                raise ValueError(f"session_id inválido: {agent}")
            if sid in seen:
                raise ValueError(f"session_id duplicado/ambíguo: {sid}")
            seen.add(sid)
            if e.get("directory") not in project["allowed_directories"]:
                raise ValueError(f"diretório não permitido no mapa: {e.get('directory')}")
    return cfg


def session_candidates():
    """Descoberta por título; metadados apenas. Não autoriza coleta de mensagens."""
    ok, out = sh(["opencode", "api", "get", "/api/session"])
    if not ok:
        return None
    try:
        data = json.loads(out).get("data", [])
    except Exception:  # noqa: BLE001
        return None
    result = []
    for s in data:
        if not str(s.get("title", "")).startswith("AGENTE"):
            continue
        m = s.get("model") or {}
        t = s.get("time") or {}
        tok = s.get("tokens") or {}
        model = f"{m.get('providerID','?')}/{m.get('id','?')}"
        if m.get("variant"):
            model += f"#{m['variant']}"
        result.append({
            "id": s.get("id"), "title": s.get("title"), "project_id": s.get("projectID"),
            "directory": (s.get("location") or {}).get("directory"), "model": model,
            "busy_utc": utc(t.get("busy")), "idle_utc": utc(t.get("idle")),
            "updated_utc": utc(t.get("updated")), "tokens_in": tok.get("input"),
            "tokens_out": tok.get("output"), "cost_registered": s.get("cost"),
        })
    return result


def active_sessions():
    """Consulta oficial de sessões em execução; None = falha, {} = nenhuma ativa."""
    ok, out = sh(["opencode", "api", "get", "/api/session/active"])
    if not ok:
        return None
    try:
        data = json.loads(out).get("data", {})
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001
        return None


def authorize_sessions(candidates, cfg):
    """Autoriza somente IDs válidos do mapa + projeto/diretório exatos observados.

    Retorna (authorized, rejected, unknown). Rejeitados não expõem títulos/IDs.
    """
    if candidates is None:
        return [], [], [{"reason": "consulta de sessões falhou"}]
    if cfg is None:
        return [], [{"reason": "mapa local ausente; candidato não autorizado"}
                    for _ in candidates], []
    counts = {}
    for c in candidates:
        cid = c.get("id")
        counts[cid] = counts.get(cid, 0) + 1
    duplicate_ids = {cid for cid, n in counts.items() if cid and n > 1}
    by_id = {c.get("id"): c for c in candidates if c.get("id") not in duplicate_ids}
    project = cfg["project"]
    allowed = set(project["allowed_directories"])
    authorized, rejected, unknown, mapped = [], [], [], set()
    for agent, entries in cfg["agents"].items():
        for e in entries:
            sid = e["session_id"]
            mapped.add(sid)
            if sid in duplicate_ids:
                rejected.append({"agent": agent, "reason": "sessionID duplicado na resposta da API"})
                continue
            c = by_id.get(sid)
            if not c:
                unknown.append({"agent": agent, "reason": "sessionID do mapa não observado"})
                continue
            if c.get("project_id") != project["id"]:
                rejected.append({"agent": agent, "reason": "projectID ausente/divergente"})
                continue
            if c.get("directory") != e.get("directory") or c.get("directory") not in allowed:
                rejected.append({"agent": agent, "reason": "diretório divergente da entrada/não permitido"})
                continue
            authorized.append({**c, "agent": agent, "mission_local": e.get("mission_id"),
                               "expected_pr": e.get("expected_pr"),
                               "expected_branch": e.get("expected_branch"),
                               "expected_worktree": e.get("expected_worktree")})
    for c in candidates:
        cid = c.get("id")
        if cid in duplicate_ids:
            rejected.append({"reason": "sessionID duplicado na resposta da API"})
        elif cid not in mapped:
            rejected.append({"reason": "sessionID não está no mapa local"})
    return authorized, rejected, unknown


def fetch_assistant_messages(sid, max_pages=2, page_size=3):
    """Paginação seletiva e limitada; retorna (mensagens, cursor, erro)."""
    messages, cursor, err = [], None, None
    for _ in range(max_pages):
        query = f"/api/session/{sid}/message?limit={page_size}&type=assistant"
        if cursor:
            query += "&cursor=" + cursor  # não combinar cursor com order (OpenAPI)
        else:
            query += "&order=desc"
        ok, out = sh(["opencode", "api", "get", query])
        if not ok:
            return messages, cursor, "api_error"
        try:
            page = json.loads(out)
        except Exception:  # noqa: BLE001
            return messages, cursor, "json_error"
        messages.extend(page.get("data", []))
        cursor = (page.get("cursor") or {}).get("next")
        if not cursor:
            break
    return messages, cursor, err


def sanitize_text(text):
    """Redação conservadora de segredos sintéticos/reaais antes de qualquer saída."""
    text = re.sub(r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
                  "[PRIVATE_KEY_REDACTED]", text, flags=re.DOTALL)
    text = re.sub(r"(?i)\b(api[_-]?key|token|password|secret)\b\s*[:=]\s*[^\s,;]+",
                  r"\1=[REDACTED]", text)
    text = re.sub(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]", text)
    return text


def parse_report_contract(text):
    if REPORT_CONTRACT not in text:
        return None
    mission = re.search(r"(?im)^mission_id:\s*(\S+)\s*$", text)
    head = re.search(r"(?im)^head_sha:\s*([0-9a-f]{40})\s*$", text)
    status = re.search(r"(?im)^status:\s*(COMPLETED|BLOCKED|FAILED|IN_PROGRESS)\s*$", text)
    if not (mission and head and status):
        return None
    return {"mission_id": mission.group(1), "head_sha": head.group(1).lower(),
            "status": status.group(1)}


def select_completed_report(messages, max_chars=4000):
    """Classifica mensagens; só contrato explícito vira relatório identificado.

    Marcadores genéricos produzem candidato não certificado, nunca conclusão de missão.
    """
    skipped = []
    for m in messages:
        finish = m.get("finish")
        if m.get("type") != "assistant" or finish != "stop":
            skipped.append({"id": m.get("id"), "reason": f"finish={finish}"})
            continue
        text = "\n".join(p.get("text", "") for p in m.get("content", [])
                         if isinstance(p, dict) and p.get("type") == "text").strip()
        if len(text) < 120:
            skipped.append({"id": m.get("id"), "reason": "texto insuficiente"})
            continue
        contract = parse_report_contract(text)
        if contract:
            classification = "identified_report"
        elif any(marker in text for marker in REPORT_MARKERS):
            classification = "candidate_unverified"
        else:
            skipped.append({"id": m.get("id"), "reason": "status intermediário/sem contrato"})
            continue
        t = m.get("time") or {}
        safe = sanitize_text(text)
        return {
            "message_id": m.get("id"), "created_utc": utc(t.get("created")),
            "completed_utc": utc(t.get("completed")), "finish": finish,
            "classification": classification, "contract": contract,
            "chars_total": len(text), "text": safe[:max_chars],
            "truncated": len(safe) > max_chars, "verification": "dado não confiável",
        }, skipped
    return None, skipped


def extract_hints(text):
    first = next((ln.strip(" *") for ln in text.splitlines() if ln.strip()), "")
    pr = re.search(r"PR\s*#(\d+)", text, re.IGNORECASE)
    sha = re.search(r"\b[0-9a-f]{40}\b", text, re.IGNORECASE)
    wt = re.search(r"better-talker-[a-z0-9-]+", text, re.IGNORECASE)
    return {"task_hint": first[:160] or None, "pr_hint": int(pr.group(1)) if pr else None,
            "head_hint": sha.group(0).lower() if sha else None,
            "worktree_hint": wt.group(0) if wt else None}


def open_prs():
    ok, out = sh(["gh", "pr", "list", "--repo", REPO, "--state", "open", "--limit", "30",
                  "--json", "number,title,headRefName,isDraft,headRefOid"])
    if not ok:
        return None
    try:
        return json.loads(out)
    except Exception:  # noqa: BLE001
        return None


def get_pr(number):
    ok, out = sh(["gh", "pr", "view", str(number), "--repo", REPO, "--json",
                  "number,state,headRefName,headRefOid,url"])
    if not ok:
        return None
    try:
        return json.loads(out)
    except Exception:  # noqa: BLE001
        return None


def check_state(check):
    if check.get("status") in {"IN_PROGRESS", "QUEUED", "PENDING"}:
        return "pending"
    if check.get("status") != "COMPLETED":
        return "unknown"
    c = check.get("conclusion")
    if c == "SUCCESS":
        return "success"
    if c in {"FAILURE", "ERROR", "CANCELLED", "TIMED_OUT"}:
        return "failure"
    return "unknown"


def valid_sha(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[0-9a-f]{40}", value))


def summarize_checks(checks, detail=False):
    if detail:
        return checks
    latest = {}
    for c in checks:
        key = (c.get("name"), c.get("event"))
        stamp = c.get("completed_at") or c.get("started_at") or ""
        if key not in latest or stamp >= (latest[key].get("completed_at") or
                                           latest[key].get("started_at") or ""):
            latest[key] = c
    return list(latest.values())


def pr_checks(number, pr_head, detail=False, run_cache=None):
    """Checks por evento/SHA; sucesso push não esconde falha pull_request."""
    run_cache = run_cache if run_cache is not None else {}
    ok, out = sh(["gh", "pr", "view", str(number), "--repo", REPO,
                  "--json", "statusCheckRollup"])
    if not ok:
        return {"query_status": "error", "checks": [], "readiness": "unknown"}
    try:
        roll = json.loads(out).get("statusCheckRollup", [])
        if not isinstance(roll, list):
            raise ValueError("rollup inválido")
    except Exception:  # noqa: BLE001
        return {"query_status": "error", "checks": [], "readiness": "unknown"}
    checks = []
    for c in roll:
        url = c.get("detailsUrl") or ""
        match = re.search(r"/actions/runs/(\d+)", url)
        run_id = match.group(1) if match else None
        meta = None
        if run_id:
            if run_id not in run_cache:
                ok2, out2 = sh(["gh", "run", "view", run_id, "--repo", REPO, "--json",
                                "headSha,event,url,conclusion,status"])
                if ok2:
                    try:
                        run_cache[run_id] = json.loads(out2)
                    except Exception:  # noqa: BLE001
                        run_cache[run_id] = None
                else:
                    run_cache[run_id] = None
            meta = run_cache.get(run_id)
        head_sha = (meta or {}).get("headSha")
        event = (meta or {}).get("event")
        state = check_state(c)
        sha_ok = valid_sha(head_sha) and valid_sha(pr_head)
        event_ok = event in KNOWN_CI_EVENTS
        matches = bool(sha_ok and head_sha == pr_head)
        checks.append({
            "name": c.get("name"), "status": c.get("status"),
            "conclusion": c.get("conclusion"), "state": state,
            "run_id": run_id, "run_url": (meta or {}).get("url") or url,
            "head_sha": head_sha, "event": event,
            "started_at": c.get("startedAt"), "completed_at": c.get("completedAt"),
            "observed_utc": now(), "matches_pr_head": matches,
            "certifies_current_head": bool(state == "success" and matches and event_ok),
        })
    visible = summarize_checks(checks, detail)
    current = [c for c in checks if c["matches_pr_head"]]
    if not roll:
        readiness = "no_checks"
    elif any(not valid_sha(c["head_sha"]) or c["event"] not in KNOWN_CI_EVENTS for c in checks):
        readiness = "unknown"
    elif not current:
        readiness = "unknown"
    elif any(c["state"] == "failure" for c in current):
        readiness = "red"
    elif any(c["state"] == "pending" for c in current):
        readiness = "pending"
    elif all(c["state"] == "success" for c in current):
        readiness = "green"
    else:
        readiness = "unknown"
    return {"query_status": "ok", "checks": visible, "all_checks_count": len(checks),
            "events": sorted({c["event"] or "DESCONHECIDO" for c in checks}),
            "readiness": readiness, "ready_for_merge": False,
            "authorization": "não_avaliada_pelo_checkpoint",
            "note": "sem checks" if not roll else None}


def worktrees():
    ok, out = sh(["git", "worktree", "list", "--porcelain"])
    if not ok:
        return None
    trees, cur = [], {}
    for line in out.splitlines():
        if line.startswith("worktree "):
            if cur:
                trees.append(cur)
            cur = {"path": line.split(" ", 1)[1]}
        elif line.startswith("HEAD "):
            cur["head"] = line.split(" ", 1)[1]  # SHA completo internamente
        elif line.startswith("branch "):
            cur["branch"] = line.split(" ", 1)[1].replace("refs/heads/", "")
        elif line == "detached":
            cur["branch"] = "(detached)"
    if cur:
        trees.append(cur)
    return trees


def relevant_worktrees(all_trees, prs, cfg):
    if not all_trees:
        return None
    branches = {p.get("headRefName") for p in (prs or [])}
    names = set()
    for entries in (cfg or {}).get("agents", {}).values():
        for e in entries:
            if e.get("expected_worktree"):
                names.add(e["expected_worktree"])
    selected = [w for w in all_trees if w.get("branch") in branches or
                os.path.basename(w["path"].rstrip("/")) in names]
    return selected[:12]


def remote_governance(detail=False):
    """Obtém documentos da main por API, com repositório explícito e somente leitura."""
    names = {"COORDINATOR.md", "DECISIONS.md", "TEAM_COORDINATION.md",
             "ACTIVE_TASKS.md", "DELIVERY_POLICY.md"}
    ok, out = sh(["gh", "api", f"repos/{REPO}/contents/docs?ref=main"])
    if not ok:
        return {"query_status": "error", "documents": []}
    try:
        entries = json.loads(out)
        if not isinstance(entries, list):
            raise ValueError("resposta inválida")
    except Exception:  # noqa: BLE001
        return {"query_status": "error", "documents": []}
    docs = []
    for e in entries:
        if e.get("name") not in names:
            continue
        item = {"path": f"docs/{e['name']}", "sha": e.get("sha"), "url": e.get("html_url")}
        if detail:
            ok2, out2 = sh(["gh", "api", f"repos/{REPO}/contents/{item['path']}?ref=main",
                            "-H", "Accept: application/vnd.github.raw"])
            item["text"] = out2 if ok2 else None
        docs.append(item)
    return {"query_status": "ok", "repo": REPO, "ref": "main", "documents": docs}


def governance(local_sha, main, detail=False):
    docs = ["docs/COORDINATOR.md", "docs/DECISIONS.md", "docs/TEAM_COORDINATION.md",
            "docs/ACTIVE_TASKS.md", "docs/DELIVERY_POLICY.md"]
    present = {d: os.path.exists(d) for d in docs}
    ok_tree, tree = sh(["git", "ls-tree", "HEAD", *docs])
    local_blobs = {d: None for d in docs}
    if ok_tree:
        for line in tree.splitlines():
            try:
                meta, path = line.split("\t", 1)
                local_blobs[path] = meta.split()[2]
            except Exception:  # noqa: BLE001
                continue
    revision = None
    if present.get("docs/ACTIVE_TASKS.md"):
        try:
            with open("docs/ACTIVE_TASKS.md", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("**Revisão:**"):
                        revision = line.strip()
                        break
        except Exception:  # noqa: BLE001
            pass
    remote = remote_governance(detail)
    remote_by_path = {d["path"]: d for d in remote.get("documents", [])}
    comparison = {d: {"local_blob": local_blobs.get(d),
                      "remote_blob": (remote_by_path.get(d) or {}).get("sha"),
                      "matches_remote": local_blobs.get(d) == (remote_by_path.get(d) or {}).get("sha")}
                  for d in docs}
    return {"present": present, "active_tasks_revision_local": revision,
            "local_head": local_sha, "main_sha": main,
            "alignment": "aligned" if local_sha and main and local_sha == main else "divergent_local_branch",
            "remote_query": remote.get("query_status"),
            "remote_documents": remote.get("documents", []),
            "remote": remote, "comparison": comparison}


def association(entry, report, prs, trees):
    """Separa declarado, existência, associação local e atualidade do relatório."""
    hints = extract_hints(report["text"]) if report else {}
    pr = None
    if hints.get("pr_hint"):
        pr = next((p for p in (prs or []) if p.get("number") == hints["pr_hint"]), None)
        if pr is None:
            pr = get_pr(hints["pr_hint"])
    wt = None
    if hints.get("worktree_hint"):
        wt = next((w for w in (trees or []) if w["path"].rstrip("/").endswith("/" + hints["worktree_hint"])), None)
    contract = report.get("contract") if report else None
    classification = report.get("classification") if report else "unknown"
    mission_state = "desconhecida"
    if classification == "identified_report" and contract and entry.get("mission_local"):
        if contract["mission_id"] != entry["mission_local"]:
            mission_state = "desatualizado_ou_outra_missao"
        else:
            tests = []
            if entry.get("expected_pr"):
                tests.append(bool(pr and pr.get("number") == entry["expected_pr"]))
            if entry.get("expected_branch"):
                tests.append(bool(pr and pr.get("headRefName") == entry["expected_branch"]))
            if entry.get("expected_worktree"):
                tests.append(bool(wt and os.path.basename(wt["path"].rstrip("/")) == entry["expected_worktree"]))
            if contract.get("head_sha") and pr and pr.get("headRefOid"):
                tests.append(contract["head_sha"] == pr["headRefOid"])
            mission_state = "associada_ao_mapa_local" if tests and all(tests) else "desatualizado_ou_divergente"
    elif classification == "candidate_unverified":
        mission_state = "candidato_nao_certificado"
    return {
        "declared_by_agent": hints,
        "pr_existence": pr,
        "worktree_existence": wt,
        "mission_id_local": entry.get("mission_local"),
        "report_classification": classification,
        "contract": contract,
        "mission_association": mission_state,
    }


def build(detail=False, reports=False):
    global QUERY_COUNT
    QUERY_COUNT = 0
    integrity = verify_script(require_clean=True)
    if not integrity["ok"]:
        raise RuntimeError(f"integridade do checkpoint inválida: {integrity['reason']}")
    cfg, map_error = None, None
    try:
        cfg = load_session_map()
    except Exception as e:  # noqa: BLE001
        map_error = str(e)
    candidates = session_candidates()
    authorized, rejected, unknown = authorize_sessions(candidates, cfg)
    active = active_sessions()
    for a in authorized:
        a["active_status"] = (active.get(a["id"], "not-running")
                              if isinstance(active, dict) else "unknown")
    prs = open_prs()
    all_trees = worktrees()
    main, local = main_sha(), local_head()
    run_cache = {}
    if prs:
        for pr in prs:
            pr["checks"] = pr_checks(pr["number"], pr.get("headRefOid"), detail, run_cache)
    if reports:
        if not cfg:
            for a in authorized:
                a["report"] = {"status": "bloqueado_sem_mapa_local"}
        else:
            for a in authorized:
                msgs, cursor, err = fetch_assistant_messages(a["id"])
                report, skipped = select_completed_report(msgs)
                a["report"] = report or {"status": err or "sem_relatorio_concluido",
                                         "skipped": skipped, "next_cursor": cursor}
                if report:
                    a["provenance"] = association(a, report, prs, all_trees)
    return {
        "checkpoint_utc": now(), "level": "detail" if detail or reports else "summary",
        "query_count": QUERY_COUNT,
        "main_sha": main, "local_head": local, "session_map_loaded": cfg is not None,
        "session_map_error": map_error, "session_candidates_count": len(candidates or []),
        "agents": authorized, "rejected_sessions": rejected, "unknown_sessions": unknown,
        "active_query": "ok" if isinstance(active, dict) else "error",
        "open_prs": prs, "worktrees": all_trees if detail or reports else relevant_worktrees(all_trees, prs, cfg),
        "worktrees_total_count": len(all_trees) if all_trees is not None else None,
        "script_integrity": integrity,
        "governance": governance(local, main, detail or reports),
        "notes": [
            "Checkpoint informativo; não autoriza ações automaticamente.",
            "Título AGENTE é candidato; só sessionID + projeto/diretório do mapa local autoriza coleta.",
            "Sessão ociosa não implica disponibilidade; diretório inicial não implica worktree.",
            "PR/worktree existente não prova associação à missão; ver proveniência.",
            "CI só certifica o HEAD quando SHA do run confere; cost=0 não prova consumo zero.",
        ],
    }


def short(sha):
    return (sha or "DESCONHECIDO")[:8]


def render_text(cp):
    L = [f"CHECKPOINT SHADOW [{cp['level']}] — {cp['checkpoint_utc']}",
         f"Consultas de leitura: {cp.get('query_count', 'DESCONHECIDO')}",
         f"Main: {cp['main_sha'] or 'DESCONHECIDO'} | HEAD local: {short(cp['local_head'])}",
         f"Governança local: {cp['governance']['alignment']} | mapa de sessões: "
         f"{'carregado' if cp['session_map_loaded'] else 'ausente/inválido'}",
         f"Integridade do script: {cp.get('script_integrity')} | governança remota: "
         f"{cp['governance'].get('remote_query')}"]
    if cp["session_map_error"]:
        L.append(f"  ERRO mapa: {cp['session_map_error']}")
    L.append("Agentes autorizados:")
    if not cp["agents"]:
        L.append("  NENHUM — candidatos por título não autorizam coleta de mensagens")
    for a in cp["agents"]:
        L.append(f"  - {a['agent']} | {a['id']} | {a['model']} | ativo={a['active_status']} | "
                 f"missão_local={a.get('mission_local') or 'DESCONHECIDA'}")
        L.append(f"    dir={a['directory']} | busy={a['busy_utc']} | idle={a['idle_utc']}")
        r = a.get("report")
        if r:
            if r.get("message_id"):
                L.append(f"    mensagem: {r['message_id']} | classe={r.get('classification')} | "
                         f"{r['completed_utc']} | {r['chars_total']} chars | truncado={r['truncated']}")
                p = a.get("provenance", {})
                L.append(f"    declarado={p.get('declared_by_agent')} | PR_existe={bool(p.get('pr_existence'))} | "
                         f"WT_existe={bool(p.get('worktree_existence'))} | missão={p.get('mission_association')}")
            else:
                L.append(f"    relatório: {r.get('status')}")
    L.append(f"Candidatos não autorizados: {len(cp['rejected_sessions'])} | desconhecidos: {len(cp['unknown_sessions'])}")
    L.append("PRs e CI (SHA do run deve conferir com HEAD do PR):")
    if cp["open_prs"] is None:
        L.append("  DESCONHECIDO — consulta de PRs falhou")
    for pr in cp["open_prs"] or []:
        L.append(f"  - #{pr['number']} [{ 'draft' if pr['isDraft'] else 'open'}] {pr['headRefName']} @ {short(pr.get('headRefOid'))}")
        cq = pr.get("checks") or {}
        if cq.get("query_status") == "error":
            L.append("    checks: ERRO DE CONSULTA (não é 'sem checks')")
        elif not cq.get("checks"):
            L.append("    checks: sem checks reportados")
        else:
            L.append(f"    prontidão técnica dos checks: {cq.get('readiness')} | merge_autorizado=NÃO AVALIADO")
        for c in cq.get("checks", []):
            L.append(f"    {c['name']}={c['state']} run={c['run_id'] or 'DESCONHECIDO'} "
                     f"evento={c['event'] or 'DESCONHECIDO'} sha={short(c['head_sha'])} "
                     f"certifica_head={c['certifies_current_head']}")
    L.append(f"Worktrees exibidas: {len(cp['worktrees'] or [])}/{cp['worktrees_total_count']}")
    for w in cp["worktrees"] or []:
        L.append(f"  - {w.get('branch','?')} @ {short(w.get('head'))} | {w['path']}")
    gov = cp["governance"]
    L.append(f"Governança remota: {gov.get('remote_query')} | docs da main: "
             f"{[d.get('path') for d in gov.get('remote_documents', [])]}")
    L.append(f"Integridade do script: {cp.get('script_integrity')}")
    L.append("Evidências pendentes: campos DESCONHECIDO/ERRO acima; CI em curso não é resultado.")
    for n in cp["notes"]:
        L.append(f"nota: {n}")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description="Checkpoint SHADOW somente leitura.")
    level = ap.add_mutually_exclusive_group()
    level.add_argument("--summary", action="store_true", help="nível resumido (padrão)")
    level.add_argument("--detail", action="store_true", help="todos os checks/worktrees")
    ap.add_argument("--reports", action="store_true", help="coleta limitada de relatórios concluídos; implica detail")
    ap.add_argument("--json", action="store_true", help="saída JSON")
    args = ap.parse_args()
    try:
        cp = build(detail=args.detail or args.reports, reports=args.reports)
    except RuntimeError as e:
        print(f"ERRO DE SEGURANÇA: {e}", file=sys.stderr)
        sys.exit(2)
    print(json.dumps(cp, ensure_ascii=False, indent=2) if args.json else render_text(cp))
    if not cp["main_sha"] or cp["session_map_error"]:
        print("\nAVISO: campos críticos desconhecidos; não usar para decisões sem reverificar.", file=sys.stderr)


if __name__ == "__main__":
    main()
