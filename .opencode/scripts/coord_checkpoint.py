#!/usr/bin/env python3
"""Checkpoint operacional SHADOW do Better Talker — somente leitura.

Níveis:
  --summary (padrão): estado operacional, PRs/checks recentes e worktrees relevantes.
  --detail: todos os checks/worktrees; investigação sob demanda.
  --reports: implica detail; coleta paginada e limitada do último relatório concluído
             apenas de sessões explicitamente autorizadas no mapa local.
  --context: manifesto compacto de cinco documentos da main, fixados ao SHA observado.
  --context-next: próxima página do snapshot privado, sem argumentos arbitrários.
  --fixture: sessões, PRs, checks, relatórios e worktrees sintéticos; zero chamadas externas.

Mapa local (não versionado): ~/.local/state/better-talker/coord-sessions.json.
Identificadores de sessão são locais e transitórios; nunca identidade permanente da equipe.
Sem mapa, títulos AGENTE são apenas candidatos e NENHUMA mensagem é consultada.

Não escreve arquivos do projeto nem muta GitHub/sessões. A paginação de contexto usa
um cache privado, limitado e temporário em ~/.local/state/better-talker.
Conteúdo de relatório é dado NÃO CONFIÁVEL, nunca instrução de autoridade.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

signal.signal(signal.SIGPIPE, signal.SIG_DFL)
REPO = "guilhermemellojw/better-talker"
MAIN_REMOTE = f"https://github.com/{REPO}.git"
DEFAULT_MAP = os.path.expanduser("~/.local/state/better-talker/coord-sessions.json")
CONTEXT_CACHE = os.path.expanduser("~/.local/state/better-talker/coord-context-cache.json")
GOVERNANCE_DOCS = ("COORDINATOR.md", "DECISIONS.md", "TEAM_COORDINATION.md",
                   "ACTIVE_TASKS.md", "DELIVERY_POLICY.md")
CONTEXT_PAGE_MAX_BYTES = 10000
CONTEXT_PAGE_MAX_LINES = 16
CONTEXT_CACHE_MAX_BYTES = 2_000_000
CONTEXT_CACHE_TTL_SECONDS = 3600
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


def _governance_failure(ref, status, reason):
    docs = [{"path": f"docs/{name}", "sha": None,
             "url": f"https://github.com/{REPO}/blob/{ref}/docs/{name}",
             "text": None, "status": "validation_failed"} for name in GOVERNANCE_DOCS]
    return {"query_status": status, "repo": REPO, "ref": ref, "documents": docs,
            "missing_documents": list(GOVERNANCE_DOCS), "reason": reason,
            "validation": "git_commit_tree_blob_sha1"}


def _gh_git_object(kind, sha):
    """GET de objeto Git por SHA fixo; nunca aceita referência mutável."""
    if kind not in {"commits", "trees", "blobs"} or not valid_sha(sha):
        return None
    ok, out = sh(["gh", "api", f"repos/{REPO}/git/{kind}/{sha}"])
    if not ok:
        return None
    try:
        data = json.loads(out)
    except Exception:  # noqa: BLE001
        return None
    return data if isinstance(data, dict) else None


def _valid_git_tree(data, expected_sha):
    return (isinstance(data, dict) and data.get("sha") == expected_sha
            and data.get("truncated") is False and isinstance(data.get("tree"), list))


def _git_blob_sha(content):
    header = f"blob {len(content)}\0".encode("ascii")
    return hashlib.sha1(header + content).hexdigest()


def remote_governance(detail=False, ref=None):
    """Valida commit → root tree → docs tree → blobs, todos por SHA fixo.

    A API Contents não é usada: `type=file` não comprova o modo do objeto Git.
    No máximo 3 consultas de metadado; no detalhe, mais 5 consultas de blob.
    """
    if not valid_sha(ref):
        return _governance_failure(ref, "unknown", "SHA da main inválido")
    commit = _gh_git_object("commits", ref)
    if commit is None:
        return _governance_failure(ref, "error", "commit não recuperado")
    if commit.get("sha") != ref:
        return _governance_failure(ref, "incomplete", "commit divergente do SHA observado")
    commit_tree = commit.get("tree")
    if not isinstance(commit_tree, dict) or not valid_sha(commit_tree.get("sha")):
        return _governance_failure(ref, "incomplete", "árvore do commit ausente/inválida")
    root_sha = commit_tree["sha"]
    root = _gh_git_object("trees", root_sha)
    if root is None:
        return _governance_failure(ref, "error", "árvore raiz não recuperada")
    if not _valid_git_tree(root, root_sha):
        return _governance_failure(ref, "incomplete", "árvore raiz inválida/truncada")
    docs_entries = [e for e in root["tree"] if isinstance(e, dict) and e.get("path") == "docs"]
    if len(docs_entries) != 1:
        return _governance_failure(ref, "incomplete", "entrada docs ausente/ambígua")
    docs_entry = docs_entries[0]
    if (docs_entry.get("mode") != "040000" or docs_entry.get("type") != "tree"
            or not valid_sha(docs_entry.get("sha"))):
        return _governance_failure(ref, "incomplete", "docs não é árvore Git regular")
    docs_sha = docs_entry["sha"]
    docs_tree = _gh_git_object("trees", docs_sha)
    if docs_tree is None:
        return _governance_failure(ref, "error", "árvore docs não recuperada")
    if not _valid_git_tree(docs_tree, docs_sha):
        return _governance_failure(ref, "incomplete", "árvore docs inválida/truncada")
    candidates = {name: [] for name in GOVERNANCE_DOCS}
    for entry in docs_tree["tree"]:
        if isinstance(entry, dict) and entry.get("path") in candidates:
            candidates[entry["path"]].append(entry)
    docs = []
    for name in GOVERNANCE_DOCS:
        entries = candidates[name]
        if len(entries) != 1:
            return _governance_failure(ref, "incomplete", f"documento ausente/ambíguo: {name}")
        entry = entries[0]
        if entry.get("mode") != "100644" or entry.get("type") != "blob" or not valid_sha(entry.get("sha")):
            return _governance_failure(ref, "incomplete", f"objeto Git não permitido: {name}")
        item = {"path": f"docs/{name}", "sha": entry["sha"], "mode": "100644", "type": "blob",
                "url": f"https://github.com/{REPO}/blob/{ref}/docs/{name}",
                "status": "validated_blob"}
        if detail:
            blob = _gh_git_object("blobs", item["sha"])
            if blob is None:
                return _governance_failure(ref, "error", f"blob não recuperado: {name}")
            if (blob.get("sha") != item["sha"] or blob.get("encoding") != "base64"
                    or not isinstance(blob.get("content"), str)):
                return _governance_failure(ref, "incomplete", f"resposta de blob inválida: {name}")
            try:
                encoded = "".join(blob["content"].split())
                content = base64.b64decode(encoded, validate=True)
                text = content.decode("utf-8")
            except Exception:  # noqa: BLE001
                return _governance_failure(ref, "incomplete", f"conteúdo de blob inválido: {name}")
            if not content or _git_blob_sha(content) != item["sha"]:
                return _governance_failure(ref, "incomplete", f"integridade do blob divergente: {name}")
            item.update({"text": text, "status": "verified", "git_sha_verified": True})
        docs.append(item)
    return {"query_status": "ok", "repo": REPO, "ref": ref, "documents": docs,
            "missing_documents": [], "validation": "git_commit_tree_blob_sha1",
            "max_queries": 8 if detail else 3}


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


def _split_context_line(line):
    chunks, current = [], ""
    for char in line:
        if current and len((current + char).encode("utf-8")) > CONTEXT_PAGE_MAX_BYTES:
            chunks.append(current)
            current = char
        else:
            current += char
    if current or not chunks:
        chunks.append(current)
    return chunks


def _paginate_context_text(text):
    """Divide por bytes UTF-8 e linhas, sem quebrar código de ponto multibyte."""
    pages, current = [], ""
    for line in text.splitlines(keepends=True):
        for chunk in _split_context_line(line):
            candidate = current + chunk
            if current and (len(candidate.encode("utf-8")) > CONTEXT_PAGE_MAX_BYTES
                            or candidate.count("\n") > CONTEXT_PAGE_MAX_LINES):
                pages.append(current)
                current = chunk
            else:
                current = candidate
    if current or not pages:
        pages.append(current)
    return pages


def _context_snapshot_id(snapshot):
    identity = {"main_sha": snapshot.get("main_sha"), "fixture": snapshot.get("fixture", False),
                "documents": [{"path": d.get("path"), "blob_sha": d.get("blob_sha"),
                               "content_sha256": d.get("content_sha256"),
                               "pages": [p.get("sha256") for p in d.get("pages", [])]}
                              for d in snapshot.get("documents", [])]}
    raw = json.dumps(identity, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _make_context_snapshot(main_sha, documents, fixture=False):
    if len(documents) != len(GOVERNANCE_DOCS):
        raise ValueError("contexto incompleto")
    docs = []
    for expected, item in zip(GOVERNANCE_DOCS, documents):
        if item.get("path") != f"docs/{expected}" or not valid_sha(item.get("blob_sha")):
            raise ValueError("documento divergente")
        if item.get("mode") != "100644" or item.get("type") != "blob":
            raise ValueError("objeto Git não permitido")
        text = sanitize_text(item.get("text") or "")
        if not text:
            raise ValueError("documento vazio")
        parts = _paginate_context_text(text)
        pages = []
        for part in parts:
            raw = part.encode("utf-8")
            pages.append({"text": part, "bytes": len(raw), "lines": len(part.splitlines()),
                          "sha256": hashlib.sha256(raw).hexdigest()})
        docs.append({"path": item["path"], "blob_sha": item["blob_sha"], "mode": "100644",
                     "type": "blob", "status": item.get("status", "verified"),
                     "git_sha_verified": bool(item.get("git_sha_verified", fixture)),
                     "bytes": len(text.encode("utf-8")), "chars": len(text),
                     "content_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                     "sanitization": "best_effort_regex", "secret_free_guaranteed": False,
                     "text": text, "pages": pages})
    snapshot = {"snapshot_id": None, "main_sha": main_sha, "fixture": fixture,
                "created_utc": now(), "created_epoch": time.time(), "next_index": 0,
                "documents": docs}
    snapshot["snapshot_id"] = _context_snapshot_id(snapshot)
    return snapshot


def _context_failure(reason, status="incomplete", fixture=False):
    return {"checkpoint_utc": now(), "mode": "context_manifest", "fixture": fixture,
            "main_sha": None, "snapshot_id": None, "query_status": status,
            "documents_complete": False, "document_count": 0, "total_pages": 0,
            "documents": [], "query_count": QUERY_COUNT, "external_calls": 0,
            "reason": reason, "notes": ["Contexto incompleto; execute --context novamente."]}


def _context_manifest(snapshot):
    docs = []
    for d in snapshot["documents"]:
        docs.append({"path": d["path"], "blob_sha": d["blob_sha"], "mode": d["mode"],
                     "type": d["type"], "status": d["status"],
                     "git_sha_verified": d["git_sha_verified"], "bytes": d["bytes"],
                     "chars": d["chars"], "content_sha256": d["content_sha256"],
                     "pages": len(d["pages"])})
    return {"checkpoint_utc": snapshot["created_utc"], "mode": "context_manifest",
            "fixture": snapshot["fixture"], "main_sha": snapshot["main_sha"],
            "snapshot_id": snapshot["snapshot_id"], "query_status": "ok",
            "documents_complete": True, "document_count": len(docs),
            "total_pages": sum(len(d["pages"]) for d in snapshot["documents"]),
            "page_limits": {"max_bytes": CONTEXT_PAGE_MAX_BYTES,
                            "max_lines": CONTEXT_PAGE_MAX_LINES},
            "cache_ttl_seconds": CONTEXT_CACHE_TTL_SECONDS, "documents": docs,
            "query_count": QUERY_COUNT, "external_calls": QUERY_COUNT,
            "notes": ["Snapshot fixado; use --context-next --json até last_page=true.",
                      "Sanitização é best-effort e não garante ausência absoluta de segredos."]}


def _ensure_private_context_dir():
    directory = os.path.dirname(CONTEXT_CACHE)
    os.makedirs(directory, mode=0o700, exist_ok=True)
    if os.path.realpath(directory) != os.path.abspath(directory):
        raise ValueError("diretório do cache contém symlink")
    st = os.lstat(directory)
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISDIR(st.st_mode):
        raise ValueError("diretório do cache inválido")
    if st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o700:
        raise ValueError("diretório do cache deve ser 0700 e privado")


def _cache_payload(snapshot):
    return {**snapshot, "documents": [{k: v for k, v in d.items() if k != "text"}
                                      for d in snapshot["documents"]]}


def _store_context_snapshot(snapshot):
    _ensure_private_context_dir()
    raw = json.dumps(_cache_payload(snapshot), ensure_ascii=False,
                     separators=(",", ":")).encode("utf-8")
    if len(raw) > CONTEXT_CACHE_MAX_BYTES:
        raise ValueError("cache de contexto excede o limite")
    directory = os.path.dirname(CONTEXT_CACHE)
    fd, temporary = tempfile.mkstemp(prefix=".coord-context-", dir=directory)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temporary, CONTEXT_CACHE)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _read_context_cache():
    if not os.path.lexists(CONTEXT_CACHE):
        return None
    if os.path.realpath(CONTEXT_CACHE) != os.path.abspath(CONTEXT_CACHE):
        raise ValueError("cache de contexto contém symlink")
    _ensure_private_context_dir()
    st = os.lstat(CONTEXT_CACHE)
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise ValueError("cache de contexto não é arquivo regular")
    if st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
        raise ValueError("cache de contexto não é privado")
    fd = os.open(CONTEXT_CACHE, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as f:
        st = os.fstat(f.fileno())
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600:
            raise ValueError("cache de contexto mudou durante a leitura")
        raw = f.read(CONTEXT_CACHE_MAX_BYTES + 1)
    if len(raw) > CONTEXT_CACHE_MAX_BYTES:
        raise ValueError("cache de contexto excede o limite")
    data = json.loads(raw)
    return data if isinstance(data, dict) else None


def _validate_context_snapshot(snapshot, allow_fixture=False):
    try:
        if not isinstance(snapshot, dict) or not isinstance(snapshot.get("fixture"), bool):
            return False
        if snapshot["fixture"] and not allow_fixture:
            return False
        if not valid_sha(snapshot.get("main_sha")) or snapshot.get("snapshot_id") != _context_snapshot_id(snapshot):
            return False
        docs = snapshot.get("documents")
        if not isinstance(docs, list) or len(docs) != len(GOVERNANCE_DOCS):
            return False
        total = 0
        for expected, d in zip(GOVERNANCE_DOCS, docs):
            if (d.get("path") != f"docs/{expected}" or not valid_sha(d.get("blob_sha"))
                    or d.get("mode") != "100644" or d.get("type") != "blob"
                    or not isinstance(d.get("content_sha256"), str)
                    or not isinstance(d.get("pages"), list) or not d["pages"]):
                return False
            joined = ""
            for page in d["pages"]:
                text = page.get("text")
                if not isinstance(text, str):
                    return False
                raw = text.encode("utf-8")
                if (page.get("bytes") != len(raw) or page.get("lines") != len(text.splitlines())
                        or len(raw) > CONTEXT_PAGE_MAX_BYTES
                        or len(text.splitlines()) > CONTEXT_PAGE_MAX_LINES
                        or page.get("sha256") != hashlib.sha256(raw).hexdigest()):
                    return False
                joined += text
            joined_raw = joined.encode("utf-8")
            if (hashlib.sha256(joined_raw).hexdigest() != d["content_sha256"]
                    or d.get("bytes") != len(joined_raw) or d.get("chars") != len(joined)):
                return False
            total += len(d["pages"])
        return (isinstance(snapshot.get("next_index"), int) and 0 <= snapshot["next_index"] <= total
                and isinstance(snapshot.get("created_epoch"), (int, float)))
    except Exception:  # noqa: BLE001
        return False


def _flatten_context_pages(snapshot):
    pages = []
    for d in snapshot["documents"]:
        for index, page in enumerate(d["pages"], 1):
            pages.append((d, index, page))
    return pages


def _context_page(snapshot, index):
    if not _validate_context_snapshot(snapshot, allow_fixture=bool(
            isinstance(snapshot, dict) and snapshot.get("fixture"))):
        return {"mode": "context_page", "documents_complete": False,
                "query_status": "incomplete", "reason": "snapshot inválido ou divergente"}
    pages = _flatten_context_pages(snapshot)
    if not isinstance(index, int) or index < 0 or index >= len(pages):
        return {"mode": "context_page", "documents_complete": False,
                "query_status": "incomplete", "reason": "página fora do intervalo"}
    d, local_page, page = pages[index]
    return {"mode": "context_page", "fixture": snapshot["fixture"],
            "snapshot_id": snapshot["snapshot_id"], "main_sha": snapshot["main_sha"],
            "query_status": "ok", "documents_complete": True,
            "document": {"path": d["path"], "blob_sha": d["blob_sha"],
                         "content_sha256": d["content_sha256"],
                         "git_sha_verified": d["git_sha_verified"]},
            "page_number": index + 1, "document_page": local_page,
            "document_pages": len(d["pages"]), "total_pages": len(pages),
            "last_page": index == len(pages) - 1, "truncated": False,
            "page_sha256": page["sha256"], "bytes": page["bytes"],
            "lines": page["lines"], "text": page["text"],
            "query_count": 0, "external_calls": 0}


def build_context(fixture=False):
    """Constrói o snapshot completo; a CLI expõe somente manifesto e páginas."""
    global QUERY_COUNT
    QUERY_COUNT = 0
    if fixture:
        cp = fixture_checkpoint(detail=True)
        docs = [{**d, "blob_sha": d.get("sha"), "mode": "100644", "type": "blob",
                 "status": "fixture", "git_sha_verified": False}
                for d in cp["governance"]["remote_documents"]]
        snapshot = _make_context_snapshot(cp["main_sha"], docs, fixture=True)
        return {**snapshot, "mode": "context", "query_count": 0, "external_calls": 0,
                "documents_complete": True,
                "notes": ["Governança sintética; nenhuma chamada externa."]}
    integrity = verify_script(require_clean=True)
    if not integrity["ok"]:
        raise RuntimeError(f"integridade do checkpoint inválida: {integrity['reason']}")
    main = main_sha()
    if not valid_sha(main):
        return _context_failure("SHA da main não observado", status="unknown")
    remote = remote_governance(detail=True, ref=main)
    if remote.get("query_status") != "ok":
        return _context_failure(remote.get("reason", "governança remota incompleta"),
                                status=remote.get("query_status", "incomplete"))
    try:
        remote_docs = [{**d, "blob_sha": d.get("sha")} for d in remote["documents"]]
        snapshot = _make_context_snapshot(main, remote_docs)
    except ValueError as e:
        return _context_failure(str(e))
    return {**snapshot, "mode": "context", "query_count": QUERY_COUNT,
            "external_calls": QUERY_COUNT, "script_integrity": integrity,
            "documents_complete": True,
            "notes": ["Conteúdo fixado ao SHA da main observado; sanitização é best-effort."]}


def build_context_manifest(fixture=False):
    """Grava um snapshot privado e devolve apenas metadados compactos."""
    snapshot = build_context(fixture=fixture)
    if not snapshot.get("documents_complete"):
        return snapshot
    if not fixture:
        try:
            _store_context_snapshot(snapshot)
        except (OSError, ValueError) as e:
            return _context_failure(f"cache privado indisponível: {e}")
    manifest = _context_manifest(snapshot)
    manifest["cache_state"] = "memory" if fixture else "stored"
    return manifest


def next_context_page():
    """Avança o cursor sem argumentos; nunca consulta uma referência mutável."""
    global QUERY_COUNT
    QUERY_COUNT = 0
    integrity = verify_script(require_clean=True)
    if not integrity["ok"]:
        raise RuntimeError(f"integridade do checkpoint inválido: {integrity['reason']}")
    try:
        snapshot = _read_context_cache()
    except (OSError, ValueError, json.JSONDecodeError) as e:
        return _context_failure(f"cache de contexto inválido: {e}")
    if snapshot is None:
        return _context_failure("snapshot ausente; execute --context")
    if time.time() - snapshot.get("created_epoch", 0) > CONTEXT_CACHE_TTL_SECONDS:
        return _context_failure("snapshot expirado; execute --context")
    if not _validate_context_snapshot(snapshot):
        return _context_failure("snapshot inconsistente; execute --context")
    pages = _flatten_context_pages(snapshot)
    if snapshot["next_index"] >= len(pages):
        return {"checkpoint_utc": now(), "mode": "context_complete", "fixture": False,
                "snapshot_id": snapshot["snapshot_id"], "main_sha": snapshot["main_sha"],
                "query_status": "ok", "documents_complete": True,
                "all_pages_delivered": True, "delivered_pages": len(pages),
                "total_pages": len(pages), "query_count": QUERY_COUNT,
                "external_calls": 0, "script_integrity": integrity}
    page = _context_page(snapshot, snapshot["next_index"])
    if not page.get("documents_complete"):
        return page
    snapshot["next_index"] += 1
    try:
        _store_context_snapshot(snapshot)
    except (OSError, ValueError) as e:
        return _context_failure(f"cursor não persistido: {e}")
    page.update({"delivered_pages": snapshot["next_index"],
                 "all_pages_delivered": snapshot["next_index"] == len(pages),
                 "cache_hit": True, "query_count": QUERY_COUNT, "external_calls": 0,
                 "script_integrity": integrity})
    return page


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
    mode = cp.get("mode")
    if mode == "context_page":
        L = [f"CONTEXTO SHADOW — página {cp.get('page_number')}/{cp.get('total_pages')}",
             f"Snapshot: {cp.get('snapshot_id')} | main: {cp.get('main_sha')}",
             f"Documento: {cp.get('document', {}).get('path')} @ {cp.get('document', {}).get('blob_sha')}",
             f"Página do documento: {cp.get('document_page')}/{cp.get('document_pages')} | última={cp.get('last_page')}",
             f"Hash da página: {cp.get('page_sha256')} | bytes={cp.get('bytes')} | linhas={cp.get('lines')}",
             cp.get("text") or "[TEXTO INDISPONÍVEL]"]
    elif mode == "context_complete":
        L = [f"CONTEXTO SHADOW COMPLETO — {cp.get('delivered_pages')}/{cp.get('total_pages')} páginas",
             f"Snapshot: {cp.get('snapshot_id')} | main: {cp.get('main_sha')}",
             "Todas as páginas únicas foram entregues."]
    else:
        L = [f"MANIFESTO CONTEXTO SHADOW — {cp.get('checkpoint_utc')}",
             f"Main fixada: {cp.get('main_sha') or 'DESCONHECIDO'} | snapshot={cp.get('snapshot_id')}",
             f"Status: {cp.get('query_status')} | completo={cp.get('documents_complete')}",
             f"Documentos: {cp.get('document_count')} | páginas: {cp.get('total_pages')}",
             f"Limites: {cp.get('page_limits')} | consultas={cp.get('query_count')}"]
        for d in cp.get("documents", []):
            L.append(f"- {d.get('path')} @ {d.get('blob_sha')} | {d.get('bytes')} bytes | {d.get('pages')} páginas")
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
    context_mode = ap.add_mutually_exclusive_group()
    context_mode.add_argument("--context", action="store_true", help="manifesto de governança fixado ao SHA da main")
    context_mode.add_argument("--context-next", action="store_true", help="próxima página do snapshot de contexto")
    ap.add_argument("--fixture", action="store_true", help="dados sintéticos, sem chamadas externas")
    ap.add_argument("--json", action="store_true", help="saída JSON")
    args = ap.parse_args()
    try:
        if args.context_next:
            cp = next_context_page()
        elif args.context:
            cp = build_context_manifest(fixture=args.fixture)
        else:
            cp = build(detail=args.detail or args.reports, reports=args.reports, fixture=args.fixture)
    except RuntimeError as e:
        print(f"ERRO DE SEGURANÇA: {e}", file=sys.stderr)
        sys.exit(2)
    if args.context or args.context_next:
        print(json.dumps(cp, ensure_ascii=False, indent=2) if args.json else render_context(cp))
        return
    print(json.dumps(cp, ensure_ascii=False, indent=2) if args.json else render_text(cp))
    if not cp["main_sha"] or cp["session_map_error"]:
        print("\nAVISO: campos críticos desconhecidos; não usar para decisões sem reverificar.", file=sys.stderr)


if __name__ == "__main__":
    main()
