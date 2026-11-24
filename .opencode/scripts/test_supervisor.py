#!/usr/bin/env python3
"""Testes isolados da configuração do supervisor SHADOW — não destrutivos.

Offline (padrão): parsing do frontmatter, simulação das regras de permissão,
sintaxe do checkpoint, tratamento de ausência/ambiguidade, presença dos docs.
--live: reconhecimento do agente pelo serviço OpenCode (somente leitura) e
produção real do checkpoint. NÃO cria sessões, NÃO executa operações proibidas.

A simulação de permissões testa a LÓGICA das regras; não prova imposição técnica
em execução (isso exigiria uma sessão supervisor — gate pendente, ver README).

Uso:
    python3 .opencode/scripts/test_supervisor.py [--live]
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
    """Última regra correspondente vence. '*' casa qualquer chars, inclusive '/'."""
    effect = None
    for r in rules:
        a, res = r.get("action", ""), r.get("resource", "")
        if not fnmatch.fnmatchcase(action, a):
            continue
        hit = fnmatch.fnmatchcase(resource, res)
        # Padrão shell terminado em ' *' também casa o comando sem argumentos.
        if not hit and res.endswith(" *"):
            hit = fnmatch.fnmatchcase(resource, res[:-2])
        if hit:
            effect = r.get("effect")
    return effect


def test_frontmatter(fm, body):
    print("[1] Frontmatter / identidade")
    check("description presente", bool(fm.get("description")))
    check("mode primary", fm.get("mode") == "primary")
    check("modelo NÃO fixado (identidade ≠ modelo)", "model" not in fm)
    check("permissions é lista", isinstance(fm.get("permissions"), list))
    check("system prompt (body) não vazio", len(body) > 200)
    check("body referencia COORDINATOR.md", "docs/COORDINATOR.md" in body)
    check("body referencia modo SHADOW", "SHADOW" in body)
    check("body proíbe despacho", "Não despacha" in body or "não despacha" in body)


def test_permissions(fm):
    print("[2] Simulação de permissões (lógica das regras; não imposição em execução)")
    rules = fm["permissions"]
    cases = [
        ("read", "docs/COORDINATOR.md", "allow"),
        ("read", "docs/DECISIONS.md", "allow"),
        ("read", "local.properties", "deny"),
        ("read", "android/local.properties", "deny"),
        ("read", ".env", "deny"),
        ("read", ".env.production", "deny"),
        ("read", "service.json", "deny"),
        ("read", "auth.json", "deny"),
        ("read", "google-services.json", "deny"),
        ("read", "release.keystore", "deny"),
        ("read", "release.jks", "deny"),
        ("read", "android/app/src/publicApp/google-services.json", "deny"),
        ("glob", "**/*.kt", "allow"),
        ("grep", "CopilotViewModel", "allow"),
        ("question", "*", "allow"),
        ("edit", "docs/COORDINATOR.md", "deny"),
        ("edit", "android/app/src/main/java/X.kt", "deny"),
        ("subagent", "general", "deny"),
        ("execute", "*", "deny"),
        ("skill", "opencode", "deny"),
        ("shell", "git status --short", "allow"),
        ("shell", "git status", "allow"),
        ("shell", "git ls-remote origin main", "allow"),
        ("shell", "git branch --list 'feat/*'", "allow"),
        ("shell", "git branch -D other-agent-work", "deny"),
        ("shell", "gh pr view 52 --json state", "allow"),
        ("shell", "opencode api get /api/session", "allow"),
        ("shell", "opencode api get /api/credential", "deny"),
        ("shell", "git push origin main", "deny"),
        ("shell", "git commit -m x", "deny"),
        ("shell", "gh pr merge 55", "deny"),
        ("shell", "gh api -X POST repos/x/y/pulls/55/merge", "deny"),
        ("shell", "rm -rf /", "deny"),
        ("shell", "adb shell", "deny"),
        ("shell", "bash script.sh", "deny"),
        ("shell", "eval git push", "deny"),
        ("webfetch", "https://opencode.ai/docs/agents", "allow"),
        ("webfetch", "https://example.invalid/x", "ask"),
        ("external_directory", "/home/user/other", "ask"),
    ]
    for action, resource, expected in cases:
        got = match_effect(rules, action, resource)
        check(f"{action} {resource[:40]} -> {expected}", got == expected)


def test_syntax():
    print("[3] Sintaxe do checkpoint")
    try:
        with tempfile.TemporaryDirectory() as td:
            py_compile.compile(CHECKPOINT, cfile=os.path.join(td, "checkpoint.pyc"), doraise=True)
        check("coord_checkpoint.py compila", True)
    except Exception as e:  # noqa: BLE001
        check(f"coord_checkpoint.py compila ({e})", False)


def test_ambiguity():
    print("[4] Tratamento de ausência/ambiguidade")
    sys.path.insert(0, HERE)
    import coord_checkpoint as cc
    cp = {"checkpoint_utc": "T", "main_sha": None, "agents": None, "open_prs": None,
          "worktrees": None, "governance": {"present": {}, "active_tasks_revision": None},
          "notes": []}
    txt = cc.render_text(cp)
    check("main desconhecida marcada", "DESCONHECIDO" in txt)
    check("agentes desconhecidos marcados", "DESCONHECIDO" in txt)
    check("matcher: comando não casado -> deny (shell)",
          match_effect(load_frontmatter()[0]["permissions"], "shell", "curl http://x") == "deny")


def test_docs():
    print("[5] Documentos oficiais presentes (recuperação de contexto)")
    for d in ["docs/COORDINATOR.md", "docs/DECISIONS.md", "docs/TEAM_COORDINATION.md",
              "docs/ACTIVE_TASKS.md", "docs/DELIVERY_POLICY.md"]:
        check(d, os.path.exists(os.path.join(ROOT, d)))


def test_live():
    print("[6] Reconhecimento ao vivo (somente leitura) e produção do checkpoint")
    import time
    # O serviço carrega configuração de worktree de forma assíncrona. Usar a
    # localização explícita e repetir a consulta sem reiniciar/criar sessão.
    agents = []
    for attempt in range(3):
        r = subprocess.run(["opencode", "api", "get",
                            f"/api/agent?location[directory]={ROOT}"], capture_output=True,
                           text=True, cwd=ROOT, timeout=60)
        try:
            agents = json.loads(r.stdout).get("data", [])
            if any(a.get("id") == "supervisor" for a in agents):
                break
        except Exception:  # noqa: BLE001
            pass
        if attempt < 2:
            time.sleep(1)
    sup = next((a for a in agents if a.get("id") == "supervisor"), None)
    check("agente 'supervisor' reconhecido pelo serviço", sup is not None)
    if sup:
        check("supervisor mode primary", sup.get("mode") == "primary")
        check("supervisor sem modelo fixado", not sup.get("model"))
        perms = sup.get("permissions", [])
        check("supervisor com permissões configuradas", len(perms) > 0)
        check("system SHADOW carregado na definição", "SHADOW" in (sup.get("system") or ""))
        check("live: edit negado", match_effect(perms, "edit", "docs/COORDINATOR.md") == "deny")
        check("live: subagent negado", match_effect(perms, "subagent", "general") == "deny")
        check("live: git push negado", match_effect(perms, "shell", "git push origin main") == "deny")
        check("live: leitura autorizada", match_effect(perms, "read", "docs/COORDINATOR.md") == "allow")
    rc = subprocess.run([sys.executable, CHECKPOINT, "--json"], capture_output=True,
                        text=True, cwd=ROOT, timeout=120)
    try:
        cp = json.loads(rc.stdout)
        check("checkpoint produz main_sha", bool(cp.get("main_sha")))
        check("checkpoint lista agentes A–E", bool(cp.get("agents")))
    except Exception as e:  # noqa: BLE001
        check(f"checkpoint produz JSON ({e})", False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="inclui reconhecimento ao vivo e checkpoint real")
    args = ap.parse_args()
    os.chdir(ROOT)
    fm, body = load_frontmatter()
    test_frontmatter(fm, body)
    test_permissions(fm)
    test_syntax()
    test_ambiguity()
    test_docs()
    if args.live:
        test_live()
    print(f"\nRESULTADO: {PASS} passed, {FAIL} failed")
    print("NOTA: simulação valida a lógica das regras; imposição técnica em execução")
    print("exige sessão supervisor (gate pendente). Nenhum despacho/merge foi executado.")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
