#!/usr/bin/env python3
"""Generate a conservative, evidence-backed GitHub progress snapshot.

This script never edits the strategic roadmap, marks milestones as completed,
or performs GitHub writes. The workflow proposes the generated result as a PR.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
AUTOMATION_BRANCH = "automation/roadmap-status"
TRACKS = ("Mentor", "Referências e UX", "TRUST", "Beta e infraestrutura", "Documentação", "Outros")


def github_get(repo: str, path: str, token: str) -> Any:
    url = f"https://api.github.com/repos/{repo}/{path}"
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "better-talker-roadmap-status",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=40) as response:
        return json.load(response)


def fetch_recent_prs(repo: str, token: str, max_pages: int = 3) -> list[dict]:
    results: list[dict] = []
    for page in range(1, max_pages + 1):
        batch = github_get(
            repo,
            f"pulls?state=all&sort=updated&direction=desc&per_page=100&page={page}",
            token,
        )
        results.extend(batch)
        if len(batch) < 100:
            break
    return results


def classify(pr: dict) -> str:
    labels = {label.get("name", "").casefold() for label in pr.get("labels", [])}
    explicit = {
        "roadmap:mentor": "Mentor",
        "roadmap:ux": "Referências e UX",
        "roadmap:trust": "TRUST",
        "roadmap:beta": "Beta e infraestrutura",
        "roadmap:docs": "Documentação",
    }
    for label, track in explicit.items():
        if label in labels:
            return track
    title = pr.get("title") or ""
    branch = (pr.get("head") or {}).get("ref") or ""
    text = (title + " " + branch).casefold()
    if re.search(r"roadmap|\bdocs?[:( /-]|documentation|handoff", text):
        return "Documentação"
    if re.search(r"trust|\btnm\b|\bnwt\b|grounding|source.?gate", text):
        return "TRUST"
    if re.search(r"mentor|memory|mem[oó]ria|copilot|\b1c\.", text):
        return "Mentor"
    if re.search(r"ux|refer[eê]nci|publication|biblioteca|chat.?links|acessibil", text):
        return "Referências e UX"
    if re.search(r"beta|release|pipeline|\bci\b|build|gradle|cost", text):
        return "Beta e infraestrutura"
    return "Outros"


def check_status(check_runs: list[dict] | None) -> str:
    if not check_runs:
        return "Não verificado"
    if any(run.get("status") != "completed" for run in check_runs):
        return "Pendente"
    results = {run.get("conclusion") for run in check_runs}
    if results <= {"success", "neutral", "skipped"}:
        return "Verde"
    if results & {"failure", "timed_out", "cancelled", "action_required"}:
        return "Falhou"
    return "Indefinido"


def escape_cell(value: str) -> str:
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'").replace("<", "&lt;")


def render_status(
    repo: str,
    main_sha: str,
    main_runs: list[dict],
    pulls: list[dict],
    pr_checks: dict[str, list[dict]],
) -> str:
    all_pulls = [
        p for p in pulls
        if (p.get("head") or {}).get("ref") != AUTOMATION_BRANCH
        and (p.get("head") or {}).get("ref", "").find("roadmap-status") == -1
    ]
    opened = sorted((p for p in all_pulls if p.get("state") == "open"), key=lambda p: p["number"], reverse=True)
    merged = sorted((p for p in all_pulls if p.get("merged_at")), key=lambda p: p["merged_at"], reverse=True)
    abandoned = [p for p in all_pulls if p.get("state") == "closed" and not p.get("merged_at")]
    by_track: dict[str, Counter] = {key: Counter() for key in TRACKS}
    for p in all_pulls:
        state = "Aberto" if p.get("state") == "open" else "Integrado" if p.get("merged_at") else "Fechado sem merge"
        by_track[classify(p)][state] += 1

    lines = [
        "# Better Talker — Progresso do GitHub",
        "",
        "**Relatório gerado automaticamente com dados verificáveis da API do GitHub.** "
        "As alterações são propostas por PR; o planejamento estratégico não é modificado automaticamente.",
        "",
        f"- Repositório: https://github.com/{repo}",
        f"- `main` consultada: `{main_sha}`",
        f"- Checks da `main` consultada: **{check_status(main_runs)}**",
        f"- PRs encontrados na janela de consulta: **{len(all_pulls)}** "
        "(até 300, ordenados por atualização; não é histórico completo se houver mais)",
        "- PR aberto não significa funcionalidade concluída. PR integrado não significa beta validado.",
        "- CI verde no HEAD do PR não comprova CI pós-merge nem teste em aparelho.",
        "",
        "## Progresso observado por frente",
        "",
        "| Frente | Abertos | Integrados | Fechados sem merge |",
        "| --- | ---: | ---: | ---: |",
    ]
    for track in TRACKS:
        counts = by_track[track]
        lines.append(f"| {track} | {counts['Aberto']} | {counts['Integrado']} | {counts['Fechado sem merge']} |")

    lines.extend(["", "## PRs abertos", "", "| PR | Frente inferida | HEAD | Checks do HEAD |", "| --- | --- | --- | --- |"])
    for p in opened:
        number = p["number"]
        sha = (p.get("head") or {}).get("sha") or ""
        title = escape_cell(p.get("title", ""))
        status = check_status(pr_checks.get(sha))
        lines.append(f"| [#{number} — {title}](https://github.com/{repo}/pull/{number}) | {classify(p)} | `{sha[:12]}` | {status} |")
    if not opened:
        lines.append("| Nenhum na janela consultada | — | — | — |")

    lines.extend(["", "## Integrações recentes", "", "| PR | Frente inferida | Data UTC |", "| --- | --- | --- |"])
    for p in merged[:25]:
        number = p["number"]
        title = escape_cell(p.get("title", ""))
        merged_at = (p.get("merged_at") or "")[:16].replace("T", " ")
        lines.append(f"| [#{number} — {title}](https://github.com/{repo}/pull/{number}) | {classify(p)} | {merged_at} |")
    if not merged:
        lines.append("| Nenhuma na janela consultada | — | — |")

    lines.extend([
        "", "## Interpretação e próximos gates", "",
        "- **Em andamento:** PR aberto exige revisão de diff, checks no HEAD e decisão do mantenedor.",
        "- **Integrado:** merge confirmado; conferir CI no SHA da `main` e, quando pertinente, smoke test Android.",
        "- **Beta:** readiness, qualidade factual da TNM/jw.org, acessibilidade, custos e distribuição "
        "exigem critérios de aceite próprios. Não são inferidos de contagens de PRs.",
        "- **Fonte de planejamento:** `docs/ROADMAP.md` (quando aprovado e integrado). "
        "Este arquivo é um painel factual auxiliar; ele não muda prioridades, estimativas ou marcos.",
        "", "## Limitações da coleta", "",
        "- Classificação de frente é heurística, salvo label `roadmap:*`; confirmar na revisão.",
        "- Checks não encontrados são marcados *Não verificado*, nunca como aprovação.",
        f"- Fechados sem merge na janela: {len(abandoned)}. "
        "O histórico completo e os resultados por execução estão no GitHub Actions.",
        "- O conteúdo só é atualizado quando uma proposta automática é revisada e integrada.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not REPOSITORY_PATTERN.fullmatch(args.repo):
        parser.error("--repo deve usar o formato owner/repo")
    token = os.environ.get("GITHUB_TOKEN", "")
    branch = github_get(args.repo, "branches/main", token)
    sha = branch["commit"]["sha"]
    main_checks = github_get(args.repo, f"commits/{sha}/check-runs?per_page=100", token)["check_runs"]
    pulls = fetch_recent_prs(args.repo, token)
    heads = {
        (p.get("head") or {}).get("sha") for p in pulls
        if p.get("state") == "open" and (p.get("head") or {}).get("ref") != AUTOMATION_BRANCH
    }
    pr_checks: dict[str, list[dict]] = {}
    for head in sorted(h for h in heads if h):
        try:
            pr_checks[head] = github_get(args.repo, f"commits/{head}/check-runs?per_page=100", token)["check_runs"]
        except urllib.error.HTTPError as exc:
            # Fork commits may not be reachable through the base repository;
            # missing check evidence is unknown, never a green check.
            if exc.code not in (403, 404, 422):
                raise
            pr_checks[head] = []
    result = render_status(args.repo, sha, main_checks, pulls, pr_checks)
    path = Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(result, encoding="utf-8")
    print(f"Gerado {path} para main={sha}, {len(pulls)} PRs consultados")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
