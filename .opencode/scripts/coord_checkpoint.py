#!/usr/bin/env python3
"""Checkpoint operacional SHADOW do Better Talker — somente leitura.

Níveis:
  --summary (padrão): estado operacional, PRs/checks recentes e worktrees relevantes.
  --detail: todos os checks/worktrees; investigação sob demanda.
  --reports: implica detail; coleta paginada e limitada do último relatório concluído
             apenas de sessões explicitamente autorizadas no mapa local.
  --context: cinco documentos oficiais da main, fixados ao SHA observado.
  --fixture: sessões, PRs, checks, relatórios e worktrees sintéticos; zero chamadas externas.

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
MAIN_REMOTE = f"https://github.com/{REPO}.git"
DEFAULT_MAP = os.path.expanduser("~/.local/state/better-talker/coord-sessions.json")
GOVERNANCE_DOCS = ("COORDINATOR.md", "DECISIONS.md", "TEAM_COORDINATION.md",
                   "ACTIVE_TASKS.md", "DELIVERY_POLICY.md")
REPORT_CONTRACT = "<!-- BT-COORD-REPORT:v1 -->"
REPORT_MARKERS = ("##", "HEAD", "PR #", "Parecer", "Resultado", "Bloqueio",
                  "Missão", "QA", "Recertificação", "Status")
KNOWN_CI_EVENTS = {"push", "pull_request", "pull_request_target", "workflow_run",
                   "workflow_dispatch", "schedule"}
# Sem consultar a proteção da branch, somente o evento pull_request é tratado
# como candidato de gate pertinente; os demais eventos são evidência, não gate.
GATE_CI_EVENTS = {"pull_request"}
INFORMATIONAL_CHECKS = {"propose"}
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
    """Observa a main do repositório fixo; não confia em um remote configurável."""
    ok, out = sh(["git", "ls-remote", MAIN_REMOTE, "refs/heads/main"])
    fields = out.split()
    return fields[0] if ok and fields and re.fullmatch(r"[0-9a-f]{40}", fields[0]) else None


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


def _canonical_directory(path):
    """Exige caminho absoluto, normalizado e sem symlinks em qualquer componente."""
    if not isinstance(path, str) or not os.path.isabs(path):
        raise ValueError("diretório inválido")
    normalized = os.path.abspath(path)
    if path != normalized or os.path.realpath(path) != normalized:
        raise ValueError("diretório não canônico ou contém symlink")
    return path


def _private_regular_file(path):
    """Arquivo 0600 em diretório 0700, sem symlinks em toda a cadeia do caminho."""
    path = os.path.abspath(path)
    if os.path.realpath(path) != path:
        raise ValueError("mapa local contém symlink no caminho")
    st = os.lstat(path)
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise ValueError("mapa local não é arquivo regular")
    if st.st_uid != os.getuid():
        raise ValueError("mapa local não pertence ao usuário atual")
    if stat.S_IMODE(st.st_mode) != 0o600:
        raise ValueError("mapa local deve ter permissões 0600")
    parent = os.path.dirname(path)
    pst = os.lstat(parent)
    if stat.S_ISLNK(pst.st_mode) or not stat.S_ISDIR(pst.st_mode):
        raise ValueError("diretório do mapa inválido")
    if pst.st_uid != os.getuid() or stat.S_IMODE(pst.st_mode) != 0o700:
        raise ValueError("diretório do mapa deve ser 0700 e privado do usuário")
    return path


def load_session_map(path=None):
    """Carrega mapa local privado; None = ausente. Sem autoridade via variável de ambiente."""
    path = path or DEFAULT_MAP
    if not os.path.lexists(path):
        return None
    path = _private_regular_file(path)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, encoding="utf-8") as f:
        st = os.fstat(f.fileno())
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
            raise ValueError("mapa local mudou ou não é mais privado durante a leitura")
        cfg = json.load(f)
    if not isinstance(cfg, dict):
        raise ValueError("mapa local inválido")
    project = cfg.get("project") or {}
    if not isinstance(project, dict):
        raise ValueError("project inválido")
    if not isinstance(project.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", project["id"]):
        raise ValueError("project.id inválido")
    if not isinstance(project.get("allowed_directories"), list) or not project["allowed_directories"]:
        raise ValueError("allowed_directories ausente")
    for directory in project["allowed_directories"]:
        _canonical_directory(directory)
    agents = cfg.get("agents")
    if not isinstance(agents, dict) or not agents:
        raise ValueError("mapa local sem agentes")
    seen = set()
    for agent, entries in agents.items():
        if agent not in {"A", "B", "C", "D", "E"} or not isinstance(entries, list):
            raise ValueError(f"agente inválido no mapa: {agent}")
        for e in entries:
            if not isinstance(e, dict):
                raise ValueError(f"entrada inválida no mapa: {agent}")
            sid = e.get("session_id")
            if not isinstance(sid, str) or not re.fullmatch(r"ses_[A-Za-z0-9_-]{8,128}", sid):
                raise ValueError(f"session_id inválido: {agent}")
            if sid in seen:
                raise ValueError(f"session_id duplicado/ambíguo: {sid}")
            seen.add(sid)
            directory = _canonical_directory(e.get("directory"))
            if directory not in project["allowed_directories"]:
                raise ValueError(f"diretório não permitido no mapa: {directory}")
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
            try:
                observed_directory = _canonical_directory(c.get("directory"))
            except ValueError:
                rejected.append({"agent": agent, "reason": "diretório observado não canônico ou contém symlink"})
                continue
            if observed_directory != e.get("directory") or observed_directory not in allowed:
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
            "sanitization": "best_effort_regex", "secret_free_guaranteed": False,
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
        gate_relevant = bool(event in GATE_CI_EVENTS and c.get("name") not in INFORMATIONAL_CHECKS)
        matches = bool(sha_ok and head_sha == pr_head)
        checks.append({
            "name": c.get("name"), "status": c.get("status"),
            "conclusion": c.get("conclusion"), "state": state,
            "run_id": run_id, "run_url": (meta or {}).get("url") or url,
            "head_sha": head_sha, "event": event,
            "started_at": c.get("startedAt"), "completed_at": c.get("completedAt"),
            "observed_utc": now(), "matches_pr_head": matches,
            "event_known": event_ok, "gate_relevant": gate_relevant,
            "required_gate_verified": False,
            "gate_basis": ("evento pull_request e check não informacional; "
                           "proteção da branch não verificada" if gate_relevant
                           else "não identificado como gate pertinente"),
            "certifies_current_head": bool(state == "success" and matches and gate_relevant),
        })
    visible = summarize_checks(checks, detail)
    current = [c for c in checks if c["matches_pr_head"]]
    relevant = [c for c in current if c["gate_relevant"]]
    observed = [c for c in current if c["event"] != "schedule"
                and c.get("name") not in INFORMATIONAL_CHECKS]
    if not roll:
        readiness = "no_checks"
    elif any(not valid_sha(c["head_sha"]) or not c["event_known"] for c in checks):
        readiness = "unknown"
    elif not observed:
        readiness = "unknown"
    elif any(c["state"] == "failure" for c in observed):
        readiness = "red"
    elif any(c["state"] == "pending" for c in observed):
        readiness = "pending"
    elif relevant and all(c["state"] == "success" for c in observed):
        readiness = "green"
    else:
        readiness = "unknown"
    return {"query_status": "ok", "checks": visible, "all_checks_count": len(checks),
            "events": sorted({c["event"] or "DESCONHECIDO" for c in checks}),
            "readiness": readiness, "readiness_scope": "checks observados no HEAD; gates obrigatórios não verificados",
            "required_gates_verified": False, "ready_for_merge": False,
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


def remote_governance(detail=False, ref=None):
    """Obtém somente arquivos regulares da main, fixados ao SHA observado."""
    if not valid_sha(ref):
        return {"query_status": "unknown", "repo": REPO, "ref": ref, "documents": [],
                "missing_documents": list(GOVERNANCE_DOCS)}
    ok, out = sh(["gh", "api", f"repos/{REPO}/contents/docs?ref={ref}"])
    if not ok:
        return {"query_status": "error", "repo": REPO, "ref": ref, "documents": [],
                "missing_documents": list(GOVERNANCE_DOCS)}
    try:
        entries = json.loads(out)
        if not isinstance(entries, list):
            raise ValueError("resposta inválida")
    except Exception:  # noqa: BLE001
        return {"query_status": "error", "repo": REPO, "ref": ref, "documents": [],
                "missing_documents": list(GOVERNANCE_DOCS)}
    by_name = {e.get("name"): e for e in entries
               if isinstance(e, dict) and e.get("name") in GOVERNANCE_DOCS
               and e.get("type") == "file" and valid_sha(e.get("sha"))}
    docs, missing = [], []
    for name in GOVERNANCE_DOCS:
        e = by_name.get(name)
        if not e:
            missing.append(name)
            docs.append({"path": f"docs/{name}", "sha": None, "url": None,
                         "text": None, "status": "missing_or_not_regular"})
            continue
        item = {"path": f"docs/{name}", "sha": e["sha"], "url": e.get("html_url"),
                "status": "ok"}
        if detail:
            ok2, out2 = sh(["gh", "api", f"repos/{REPO}/contents/{item['path']}?ref={ref}",
                            "-H", "Accept: application/vnd.github.raw"])
            item["text"] = out2 if ok2 else None
            if not ok2:
                item["status"] = "content_error"
        docs.append(item)
    complete = not missing and all(d.get("text") is not None for d in docs) if detail else not missing
    return {"query_status": "ok" if complete else "incomplete", "repo": REPO, "ref": ref,
            "documents": docs, "missing_documents": missing}


def governance(local_sha, main, detail=False):
    docs = [f"docs/{name}" for name in GOVERNANCE_DOCS]
    ok_tree, tree = sh(["git", "ls-tree", "HEAD", *docs])
    local_blobs = {d: None for d in docs}
    if ok_tree:
        for line in tree.splitlines():
            try:
                meta, path = line.split("\t", 1)
                local_blobs[path] = meta.split()[2]
            except Exception:  # noqa: BLE001
                continue
    # A revisão vem do objeto Git do HEAD, nunca de um arquivo da worktree.
    revision = None
    active_path = "docs/ACTIVE_TASKS.md"
    if local_blobs.get(active_path):
        ok_revision, revision_text = sh(["git", "show", f"HEAD:{active_path}"])
        if ok_revision:
            revision = next((line.strip() for line in revision_text.splitlines()
                             if line.startswith("**Revisão:**")), None)
    # Se o SHA da main não foi observado, não consulta uma referência mutável.
    remote = remote_governance(detail, ref=main) if valid_sha(main) else {
        "query_status": "unknown", "documents": [], "missing_documents": list(GOVERNANCE_DOCS)}
    remote_by_path = {d["path"]: d for d in remote.get("documents", [])}
    comparison = {}
    for d in docs:
        local_blob, remote_blob = local_blobs.get(d), (remote_by_path.get(d) or {}).get("sha")
        comparison[d] = {"local_blob": local_blob, "remote_blob": remote_blob,
                         "matches_remote": bool(valid_sha(local_blob) and valid_sha(remote_blob)
                                                and local_blob == remote_blob)}
    return {"tracked_at_local_head": {d: bool(local_blobs.get(d)) for d in docs},
            "active_tasks_revision_local_blob": revision,
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
    mission_completed = False
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
            associated = bool(tests) and all(tests)
            mission_state = "associada_ao_mapa_local" if associated else "desatualizado_ou_divergente"
            if associated and contract.get("status") == "COMPLETED":
                mission_state = "concluida"
                mission_completed = True
            elif associated:
                mission_state = {"IN_PROGRESS": "associada_em_andamento",
                                 "BLOCKED": "associada_bloqueada",
                                 "FAILED": "associada_falhou"}.get(
                                     contract.get("status"), "associada_status_desconhecido")
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
        "mission_completed": mission_completed,
    }


def fixture_checkpoint(detail=False, reports=False):
    """Checkpoint 100% sintético. Não chama shell, API, GitHub ou credenciais."""
    main, local = "a" * 40, "b" * 40
    agents = []
    for name in "ABCDE":
        agents.append({
            "id": f"ses_fixture{name}", "title": f"AGENTE {name}", "project_id": "fixture-project",
            "directory": "/fixture/better-talker", "model": "fixture/free-model",
            "busy_utc": None, "idle_utc": "2026-11-25T00:00:00Z",
            "updated_utc": "2026-11-25T00:00:00Z", "tokens_in": 0, "tokens_out": 0,
            "cost_registered": 0, "active_status": "not-running", "mission_local": f"MISS-{name}",
            "expected_pr": 901 if name == "A" else None,
            "expected_branch": "feat/fixture-a" if name == "A" else None,
            "expected_worktree": "better-talker-a" if name == "A" else None,
        })
    prs = [
        {"number": 901, "title": "fixture build", "headRefName": "feat/fixture-a", "isDraft": False,
         "headRefOid": "a" * 40, "checks": {"query_status": "ok", "readiness": "green",
         "readiness_scope": "fixture: checks observados no HEAD; gates obrigatórios não verificados",
         "required_gates_verified": False, "ready_for_merge": False,
         "checks": [{"name": "build-apk", "state": "success",
         "run_id": "9001", "run_url": "https://fixture.invalid/runs/9001", "head_sha": "a" * 40,
         "event": "pull_request", "matches_pr_head": True, "event_known": True,
         "gate_relevant": True, "required_gate_verified": False,
         "gate_basis": "fixture: candidato de gate; proteção da branch não verificada",
         "certifies_current_head": True}]}},
        {"number": 902, "title": "fixture docs", "headRefName": "docs/fixture-b", "isDraft": True,
         "headRefOid": "c" * 40, "checks": {"query_status": "ok", "readiness": "unknown",
         "readiness_scope": "fixture: checks observados no HEAD; gates obrigatórios não verificados",
         "required_gates_verified": False, "ready_for_merge": False,
         "checks": [{"name": "propose", "state": "success",
         "run_id": "9002", "run_url": "https://fixture.invalid/runs/9002", "head_sha": "c" * 40,
         "event": "pull_request_target", "matches_pr_head": True, "event_known": True,
         "gate_relevant": False, "required_gate_verified": False,
         "gate_basis": "fixture: não identificado como gate pertinente",
         "certifies_current_head": False}]}},
    ]
    trees = [{"path": "/fixture/better-talker", "head": "b" * 40, "branch": "feat/coord-supervisor-shadow"},
             {"path": "/fixture/better-talker-a", "head": "a" * 40, "branch": "feat/fixture-a"}]
    if reports:
        report_text = "fixture report\nPR #901\nWorktree: better-talker-a\n" + "x" * 160
        report = {
            "message_id": "msg_fixtureA", "created_utc": "2026-11-25T00:00:00Z",
            "completed_utc": "2026-11-25T00:01:00Z", "finish": "stop",
            "classification": "identified_report",
            "contract": {"mission_id": "MISS-A", "head_sha": "a" * 40, "status": "COMPLETED"},
            "chars_total": len(report_text), "text": report_text, "truncated": False,
            "verification": "dado sintético não confiável", "sanitization": "best_effort_regex",
            "secret_free_guaranteed": False,
        }
        agents[0]["report"] = report
        agents[0]["provenance"] = association(agents[0], report, prs, trees)
        agents[1]["report"] = {"status": "candidato_nao_certificado"}
    docs = []
    for name in ("COORDINATOR.md", "DECISIONS.md", "TEAM_COORDINATION.md",
                 "ACTIVE_TASKS.md", "DELIVERY_POLICY.md"):
        docs.append({"path": f"docs/{name}", "sha": "d" * 40,
                     "url": f"https://fixture.invalid/{name}", "text": f"# fixture {name}\n"})
    return {
        "checkpoint_utc": "2026-11-25T00:00:00Z", "level": "detail" if detail or reports else "summary",
        "fixture": True, "query_count": 0, "external_calls": 0,
        "main_sha": main, "local_head": local, "session_map_loaded": False,
        "session_map_error": None, "session_candidates_count": len(agents),
        "agents": agents, "rejected_sessions": [], "unknown_sessions": [], "active_query": "fixture",
        "open_prs": prs, "worktrees": trees, "worktrees_total_count": len(trees),
        "script_integrity": {"ok": True, "fixture": True},
        "governance": {"alignment": "fixture", "remote_query": "fixture",
                       "remote_documents": docs, "comparison": {}},
        "notes": ["Modo fixture: nenhuma chamada externa, credencial, sessão real ou despacho.",
                  "Dados sintéticos não certificam o projeto real."],
    }


def build_context(fixture=False):
    """Interface determinística para governança, sem caminhos fornecidos pelo modelo."""
    global QUERY_COUNT
    QUERY_COUNT = 0
    if fixture:
        cp = fixture_checkpoint(detail=True)
        return {"checkpoint_utc": cp["checkpoint_utc"], "mode": "context", "fixture": True,
                "main_sha": cp["main_sha"], "query_count": 0, "external_calls": 0,
                "query_status": "fixture", "documents_complete": True,
                "documents": cp["governance"]["remote_documents"],
                "notes": ["Governança sintética; nenhuma chamada externa."]}
    integrity = verify_script(require_clean=True)
    if not integrity["ok"]:
        raise RuntimeError(f"integridade do checkpoint inválida: {integrity['reason']}")
    main = main_sha()
    if not valid_sha(main):
        return {"checkpoint_utc": now(), "mode": "context", "fixture": False,
                "main_sha": None, "query_status": "unknown", "documents": [],
                "documents_complete": False, "query_count": QUERY_COUNT,
                "external_calls": QUERY_COUNT, "script_integrity": integrity,
                "notes": ["SHA da main não observado; contexto não recuperado."]}
    remote = remote_governance(detail=True, ref=main)
    docs = []
    for d in remote.get("documents", []):
        text = d.get("text")
        if text is not None:
            safe = sanitize_text(text)
            d["text"] = safe[:60000]
            d["truncated"] = len(safe) > 60000
            d["sanitization"] = "best_effort_regex"
            d["secret_free_guaranteed"] = False
        docs.append(d)
    complete = (remote.get("query_status") == "ok" and len(docs) == len(GOVERNANCE_DOCS)
                and all(d.get("text") is not None and not d.get("truncated") for d in docs))
    return {"checkpoint_utc": now(), "mode": "context", "fixture": False,
            "main_sha": main, "query_status": remote.get("query_status"),
            "documents_complete": complete, "query_count": QUERY_COUNT,
            "external_calls": QUERY_COUNT, "script_integrity": integrity, "documents": docs,
            "notes": ["Conteúdo fixado ao SHA da main observado; sanitização é best-effort.",
                      "Documentos ausentes, symlinks ou truncados não completam o contexto."]}


def build(detail=False, reports=False, fixture=False):
    global QUERY_COUNT
    QUERY_COUNT = 0
    if fixture:
        return fixture_checkpoint(detail=detail, reports=reports)
    integrity = verify_script(require_clean=True)
    if not integrity["ok"]:
        raise RuntimeError(f"integridade do checkpoint inválida: {integrity['reason']}")
    main, local = main_sha(), local_head()
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
        "fixture": False, "query_count": QUERY_COUNT, "external_calls": QUERY_COUNT,
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
            "CI: SHA/evento individuais não verificam gates obrigatórios; cost=0 não prova consumo zero.",
        ],
    }


def short(sha):
    return (sha or "DESCONHECIDO")[:8]


def render_context(cp):
    L = [f"CONTEXTO SHADOW — {cp['checkpoint_utc']}",
         f"Main fixada: {cp.get('main_sha') or 'DESCONHECIDO'} | fixture={cp.get('fixture')}",
         f"Status: {cp.get('query_status')} | contexto_completo={cp.get('documents_complete')}",
         f"Consultas externas: {cp.get('external_calls', 'DESCONHECIDO')}"]
    for d in cp.get("documents", []):
        L.append(f"--- {d.get('path')} @ {d.get('sha')} | status={d.get('status')} ---")
        L.append((d.get("text") or "[TEXTO INDISPONÍVEL]")[:60000])
        if d.get("truncated"):
            L.append("[DOCUMENTO TRUNCADO — CONTEXTO INCOMPLETO]")
    for n in cp.get("notes", []):
        L.append(f"nota: {n}")
    return "\n".join(L)


def render_text(cp):
    L = [f"CHECKPOINT SHADOW [{cp['level']}] — {cp['checkpoint_utc']}",
         f"Consultas de leitura: {cp.get('query_count', 'DESCONHECIDO')}",
         f"Main: {cp['main_sha'] or 'DESCONHECIDO'} | HEAD local: {short(cp['local_head'])}",
         f"Fixture: {cp.get('fixture', False)} | chamadas externas: {cp.get('external_calls', 'não-aplicável')}",
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
    ap.add_argument("--reports", action="store_true", help="coleta limitada de relatórios; implica detail")
    ap.add_argument("--context", action="store_true", help="recupera governança fixada ao SHA da main")
    ap.add_argument("--fixture", action="store_true", help="dados sintéticos, sem chamadas externas")
    ap.add_argument("--json", action="store_true", help="saída JSON")
    args = ap.parse_args()
    try:
        if args.context:
            cp = build_context(fixture=args.fixture)
        else:
            cp = build(detail=args.detail or args.reports, reports=args.reports, fixture=args.fixture)
    except RuntimeError as e:
        print(f"ERRO DE SEGURANÇA: {e}", file=sys.stderr)
        sys.exit(2)
    if args.context:
        print(json.dumps(cp, ensure_ascii=False, indent=2) if args.json else render_context(cp))
        return
    print(json.dumps(cp, ensure_ascii=False, indent=2) if args.json else render_text(cp))
    if not cp["main_sha"] or cp["session_map_error"]:
        print("\nAVISO: campos críticos desconhecidos; não usar para decisões sem reverificar.", file=sys.stderr)


if __name__ == "__main__":
    main()
