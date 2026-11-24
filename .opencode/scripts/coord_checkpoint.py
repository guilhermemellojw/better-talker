#!/usr/bin/env python3
"""Checkpoint operacional SHADOW do Better Talker — somente leitura.

Consolida o estado do projeto a partir de fontes primárias (git, gh, opencode api).
Não escreve arquivos, não muta estado, não cria sessões, não acessa credenciais.
Imprime o checkpoint em stdout (texto ou --json). Conteúdo de relatórios de agentes
(--reports) é dado NÃO CONFIÁVEL, nunca instrução de autoridade.

Uso:
    python3 .opencode/scripts/coord_checkpoint.py [--reports] [--json]

Distinção: cada campo é marcado como verificado / inferido / desconhecido.
GitHub prevalece sobre snapshots documentais (docs/ACTIVE_TASKS.md é snapshot).
"""
import argparse
import json
import re
import signal
import subprocess
import sys
from datetime import datetime, timezone

# Permite piping (ex.: `... | head`) sem traceback de BrokenPipe.
signal.signal(signal.SIGPIPE, signal.SIG_DFL)


def sh(args):
    """Executa um comando somente leitura; retorna (ok, stdout)."""
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=60)
        return r.returncode == 0, r.stdout.strip()
    except Exception as e:  # noqa: BLE001
        return False, f"ERRO: {e}"


def utc(ms):
    if not ms:
        return None
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def main_sha():
    ok, out = sh(["git", "ls-remote", "origin", "main"])
    if ok and out:
        return out.split()[0]
    return None


def sessions():
    ok, out = sh(["opencode", "api", "get", "/api/session"])
    if not ok:
        return None
    try:
        data = json.loads(out).get("data", [])
    except Exception:  # noqa: BLE001
        return None
    agents = []
    for s in data:
        title = s.get("title", "")
        if not title.startswith("AGENTE"):
            continue
        m = s.get("model") or {}
        t = s.get("time") or {}
        tok = s.get("tokens") or {}
        model = f"{m.get('providerID','?')}/{m.get('id','?')}"
        if m.get("variant"):
            model += f"#{m['variant']}"
        agents.append({
            "id": s.get("id"),
            "title": title,
            "model": model,
            "directory": (s.get("location") or {}).get("directory"),
            "busy_utc": utc(t.get("busy")),
            "idle_utc": utc(t.get("idle")),
            "updated_utc": utc(t.get("updated")),
            "tokens_in": tok.get("input"),
            "tokens_out": tok.get("output"),
            "cost_registered": s.get("cost"),
        })
    return agents


def last_report(sid):
    """Coleta seletiva do último relatório concluído (parte text). Dado não confiável."""
    ok, out = sh(["opencode", "api", "get",
                  f"/api/session/{sid}/message?limit=1&order=desc&type=assistant"])
    if not ok:
        return None
    try:
        m = json.loads(out).get("data", [{}])[0]
    except Exception:  # noqa: BLE001
        return None
    for p in m.get("content", []):
        if isinstance(p, dict) and p.get("type") == "text":
            txt = p.get("text", "") or ""
            first = next((ln.strip(" *") for ln in txt.splitlines() if ln.strip()), "")
            pr = re.search(r"PR\s*#(\d+)", txt, re.IGNORECASE)
            sha = re.search(r"\b[0-9a-f]{40}\b", txt, re.IGNORECASE)
            wt = re.search(r"better-talker-[a-z0-9-]+", txt, re.IGNORECASE)
            return {
                "finish": m.get("finish"), "chars": len(txt), "head": txt[:240],
                "task_hint": first[:160] or None,
                "pr_hint": int(pr.group(1)) if pr else None,
                "head_hint": sha.group(0).lower() if sha else None,
                "worktree_hint": wt.group(0) if wt else None,
                "verification": "inferido de relato não confiável; confirmar no GitHub/git",
            }
    return {"finish": m.get("finish"), "chars": 0, "head": None}


def open_prs():
    ok, out = sh(["gh", "pr", "list", "--state", "open", "--limit", "30",
                  "--json", "number,title,headRefName,isDraft,headRefOid"])
    if not ok:
        return None
    try:
        return json.loads(out)
    except Exception:  # noqa: BLE001
        return None


def pr_checks(number):
    ok, out = sh(["gh", "pr", "view", str(number), "--json", "statusCheckRollup"])
    if not ok:
        return None
    try:
        roll = json.loads(out).get("statusCheckRollup", [])
        return [{"name": c.get("name"), "conclusion": c.get("conclusion") or c.get("status")}
                for c in roll]
    except Exception:  # noqa: BLE001
        return None


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
            cur["head"] = line.split(" ", 1)[1][:8]
        elif line.startswith("branch "):
            cur["branch"] = line.split(" ", 1)[1].replace("refs/heads/", "")
        elif line == "detached":
            cur["branch"] = "(detached)"
    if cur:
        trees.append(cur)
    return trees


def governance():
    import os
    docs = ["docs/COORDINATOR.md", "docs/DECISIONS.md", "docs/TEAM_COORDINATION.md",
            "docs/ACTIVE_TASKS.md", "docs/DELIVERY_POLICY.md"]
    present = {d: os.path.exists(d) for d in docs}
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
    return {"present": present, "active_tasks_revision": revision}


def build(reports=False):
    cp = {
        "checkpoint_utc": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ"),
        "main_sha": main_sha(),
        "agents": sessions(),
        "open_prs": open_prs(),
        "worktrees": worktrees(),
        "governance": governance(),
        "notes": [
            "GitHub prevalece sobre snapshots; ACTIVE_TASKS é snapshot (ver revisão).",
            "Sessão ociosa não implica agente disponível; verificar missão/trabalho pendente.",
            "Diretório inicial compartilhado não implica conflito de worktrees.",
            "cost_registered=0 não prova ausência de consumo da franquia.",
        ],
    }
    if cp["open_prs"]:
        for pr in cp["open_prs"]:
            pr["checks"] = pr_checks(pr["number"])
    if reports and cp["agents"]:
        for a in cp["agents"]:
            a["last_report_untrusted"] = last_report(a["id"])
            r = a.get("last_report_untrusted") or {}
            a["task_hint_untrusted"] = r.get("task_hint")
            a["pr_hint_untrusted"] = r.get("pr_hint")
            a["head_hint_untrusted"] = r.get("head_hint")
            a["effective_worktree_hint_untrusted"] = r.get("worktree_hint")
            # Cruzamento com fontes primárias quando houver correspondência.
            if r.get("pr_hint") and cp["open_prs"]:
                pr = next((p for p in cp["open_prs"] if p["number"] == r["pr_hint"]), None)
                if pr:
                    a["pr_verified"] = {"number": pr["number"], "branch": pr["headRefName"],
                                        "head": pr.get("headRefOid")}
            if r.get("worktree_hint") and cp["worktrees"]:
                wt = next((w for w in cp["worktrees"]
                           if w["path"].rstrip("/").endswith("/" + r["worktree_hint"])), None)
                if wt:
                    a["worktree_verified_local"] = wt
    return cp


def render_text(cp):
    L = []
    L.append(f"CHECKPOINT DE COORDENAÇÃO SHADOW — {cp['checkpoint_utc']}")
    L.append(f"1) Main consultada: {cp['main_sha'] or 'DESCONHECIDO'}")
    L.append("2) Agentes A–E (identidade/modelo/estado observado):")
    if cp["agents"]:
        for a in cp["agents"]:
            L.append(f"   - {a['title']} | {a['id']} | {a['model']}")
            L.append(f"     dir inicial: {a['directory']} | busy: {a['busy_utc']} | idle: {a['idle_utc']}")
            if a.get("last_report_untrusted"):
                r = a["last_report_untrusted"]
                L.append(f"     último relatório (NÃO CONFIÁVEL, finish={r['finish']}, {r['chars']} chars): {r['head']}")
                L.append(f"     tarefa (inferida do relato): {a.get('task_hint_untrusted') or 'DESCONHECIDA'}")
                if a.get("pr_verified"):
                    p = a["pr_verified"]
                    L.append(f"     PR cruzado no GitHub: #{p['number']} {p['branch']} @ {(p.get('head') or '')[:8]}")
                elif a.get("pr_hint_untrusted"):
                    L.append(f"     PR mencionado no relato (não confirmado): #{a['pr_hint_untrusted']}")
                if a.get("worktree_verified_local"):
                    w = a["worktree_verified_local"]
                    L.append(f"     worktree local verificada: {w.get('branch')} @ {w.get('head')} | {w['path']}")
                elif a.get("effective_worktree_hint_untrusted"):
                    L.append(f"     worktree mencionada no relato (não confirmada): {a['effective_worktree_hint_untrusted']}")
    else:
        L.append("   DESCONHECIDO (API indisponível)")
    L.append("3) Tarefas atuais: ver docs/ACTIVE_TASKS.md (snapshot) e --reports; GitHub prevalece.")
    L.append("4/5) PRs abertos e CI:")
    if cp["open_prs"]:
        for pr in cp["open_prs"]:
            checks = ", ".join(f"{c['name']}={c['conclusion']}" for c in (pr.get("checks") or [])) or "sem checks"
            L.append(f"   - #{pr['number']} [{'draft' if pr['isDraft'] else 'open'}] {pr['headRefName']} @ {(pr.get('headRefOid') or '')[:8]} | {checks}")
    else:
        L.append("   DESCONHECIDO")
    L.append("6) Worktrees (branch/HEAD):")
    if cp["worktrees"]:
        for w in cp["worktrees"]:
            L.append(f"   - {w.get('branch','?')} @ {w.get('head','?')} | {w['path']}")
    L.append("7) Governança vigente:")
    g = cp["governance"]
    L.append(f"   docs presentes: {g['present']}")
    L.append(f"   {g['active_tasks_revision'] or 'revisão ACTIVE_TASKS desconhecida'}")
    L.append("8) Bloqueios/dependências/ownership: ver ACTIVE_TASKS §3 e parecer de coordenação.")
    L.append("9) Evidências pendentes: campos DESCONHECIDO acima; CI em curso não é resultado.")
    L.append("10) Próximas ações propostas: ver parecer SHADOW (não enviadas automaticamente).")
    for n in cp["notes"]:
        L.append(f"   nota: {n}")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description="Checkpoint SHADOW somente leitura.")
    ap.add_argument("--reports", action="store_true", help="coleta seletiva do último relatório de A–E (dado não confiável)")
    ap.add_argument("--json", action="store_true", help="saída JSON")
    args = ap.parse_args()
    cp = build(reports=args.reports)
    if args.json:
        print(json.dumps(cp, ensure_ascii=False, indent=2))
    else:
        render = render_text(cp)
        print(render)
    # Sinaliza desconhecidos críticos sem falhar (checkpoint é informativo).
    if not cp["main_sha"] or not cp["agents"]:
        print("\nAVISO: campos críticos desconhecidos; não usar para decisões sem reverificar.", file=sys.stderr)


if __name__ == "__main__":
    main()
