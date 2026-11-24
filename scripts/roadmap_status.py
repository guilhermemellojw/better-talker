#!/usr/bin/env python3
"""Generate a conservative, evidence-backed GitHub progress snapshot.

This script never edits the strategic roadmap, marks milestones as completed,
or performs GitHub writes. The workflow proposes the generated result as a PR.
Operational metrics use only fields already returned by the GitHub API and a
fixed reference derived from the dataset itself (never the wall clock).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
AUTOMATION_BRANCH = "automation/roadmap-status"
TRACKS = ("Mentor", "Referências e UX", "TRUST", "Beta e infraestrutura", "Documentação", "Outros")
MAX_PRS = 300

APPROVED = {"success"}
IGNORED = {"neutral", "skipped"}
FAILED = {"failure", "timed_out", "cancelled", "action_required"}
# Linhas que registram proveniência da consulta (SHA da main e estado dos
# checks dessa main). Mudam após merges documentais sem alterar o conteúdo
# material do painel — nunca são usadas para promover "sem checks" a aprovação.
VOLATILE_LINE_PREFIXES = (
    "- `main` consultada:",
    "- Checks da `main` consultada:",
)


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
    """Five distinct states; neutral/skipped are never counted as approval."""
    if not check_runs:
        return "Não verificado"
    if any(run.get("status") != "completed" for run in check_runs):
        return "Pendente"
    results = {run.get("conclusion") for run in check_runs}
    if results & FAILED:
        return "Falhou"
    if results <= IGNORED:
        return "Ignorado (sem aprovação)"
    if results <= APPROVED | IGNORED and results & APPROVED:
        return "Verde" if results == APPROVED else "Verde (com ignorados)"
    return "Indefinido"


def escape_cell(value: str) -> str:
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'").replace("<", "&lt;")


def is_draft(pr: dict) -> bool:
    return bool(pr.get("draft"))


def parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def collection_window(pulls: list[dict]) -> tuple[datetime | None, datetime | None]:
    stamps = [s for s in (parse_timestamp(p.get("updated_at")) for p in pulls) if s is not None]
    if not stamps:
        return None, None
    return min(stamps), max(stamps)


def window_text(pulls: list[dict]) -> str:
    start, end = collection_window(pulls)
    if start is None or end is None:
        return "indisponível (datas de atualização ausentes)"
    return f"{start.date().isoformat()} a {end.date().isoformat()}"


def _median_days(values: list[float]) -> str:
    if not values:
        return "indisponível"
    return f"{statistics.median(values):.1f} dias"


def render_metrics(pulls: list[dict], pr_checks: dict[str, list[dict]], window: str) -> list[str]:
    """Only dataset-derived fields; no wall clock and no invented estimates."""
    open_prs = [p for p in pulls if p.get("state") == "open"]
    drafts = [p for p in open_prs if is_draft(p)]
    merged = [p for p in pulls if p.get("merged_at")]

    periods: Counter[str] = Counter()
    for pr in merged:
        stamp = parse_timestamp(pr.get("merged_at"))
        if stamp:
            periods[stamp.strftime("%Y-%m")] += 1

    durations: list[float] = []
    for pr in merged:
        created = parse_timestamp(pr.get("created_at"))
        finished = parse_timestamp(pr.get("merged_at"))
        if created and finished and finished >= created:
            durations.append((finished - created).total_seconds() / 86400)

    _, reference = collection_window(pulls)
    ages: list[float] = []
    for pr in open_prs:
        created = parse_timestamp(pr.get("created_at"))
        if created and reference and reference >= created:
            ages.append((reference - created).total_seconds() / 86400)

    states: Counter[str] = Counter(
        check_status(pr_checks.get((pr.get("head") or {}).get("sha") or "")) for pr in open_prs
    )

    lines = [
        "## Métricas operacionais observáveis", "",
        f"- Janela efetiva da coleta: {window} (até {MAX_PRS} PRs, ordenados por `updated_at`).",
        f"- PRs abertos: **{len(open_prs)}**; desses, Drafts: **{len(drafts)}**.",
        f"- Integrações por período (mês UTC, somente períodos presentes na janela): "
        + (", ".join(f"{month}={periods[month]}" for month in sorted(periods, reverse=True)) if periods else "indisponível")
        + ".",
        f"- Mediana abertura → merge: {_median_days(durations)} (PRs com ambas as datas).",
        f"- Mediana de idade dos PRs abertos: {_median_days(ages)} "
        + (f"(referência fixa: {reference.date().isoformat()})." if reference else "(referência indisponível)."),
        "- Estados de check dos PRs abertos: "
        + (", ".join(f"{state}={states[state]}" for state in sorted(states)) if states else "indisponível")
        + ".",
        "- Estado de check é fato de CI, não certificação de QA; nenhum percentual de conclusão do beta é inferido de contagens de PRs.",
        "- Datas ausentes são excluídas das medianas; quando não há dados suficientes, o valor é `indisponível`.",
        "",
    ]
    return lines


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

    report_window = window_text(all_pulls)

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
        f"(até {MAX_PRS}, ordenados por atualização; não é histórico completo se houver mais), {report_window}.",
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
        draft = " (Draft)" if is_draft(p) else ""
        status = check_status(pr_checks.get(sha))
        lines.append(f"| [#{number} — {title}{draft}](https://github.com/{repo}/pull/{number}) | {classify(p)} | `{sha[:12]}` | {status} |")
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

    lines.extend(["", "## Interpretação e próximos gates", "",
        "- **Em andamento:** PR aberto exige revisão de diff, checks no HEAD e decisão do mantenedor.",
        "- **Integrado:** merge confirmado; conferir CI no SHA da `main` e, quando pertinente, smoke test Android.",
        "- **Beta:** readiness, qualidade factual da TNM/jw.org, acessibilidade, custos e distribuição "
        "exigem critérios de aceite próprios. Não são inferidos de contagens de PRs.",
        "- **Fonte de planejamento:** `docs/ROADMAP.md` (quando aprovado e integrado). "
        "Este arquivo é um painel factual auxiliar; ele não muda prioridades, estimativas ou marcos.",
        "",
    ])
    lines.extend(render_metrics(all_pulls, pr_checks, report_window))
    lines.extend([
        "## Limitações da coleta", "",
        "- Classificação de frente é heurística, salvo label `roadmap:*`; confirmar na revisão.",
        "- Checks não encontrados são marcados *Não verificado*, nunca como aprovação; `neutral`/`skipped` são *Ignorado (sem aprovação)*.",
        f"- Fechados sem merge na janela: {len(abandoned)}. "
        "O histórico completo e os resultados por execução estão no GitHub Actions.",
        "- O conteúdo só é atualizado quando uma proposta automática é revisada e integrada.",
        "",
    ])
    return "\n".join(lines)


def normalize_report(text: str) -> str:
    """Strip provenance-only lines (merged main SHA and its check state).

    The generated report always keeps the true SHA and CI state; normalization
    is used ONLY to decide whether a material rewrite is warranted. Check
    states of open PRs are material and are never stripped.
    """
    return "\n".join(
        line for line in text.splitlines()
        if not any(line.startswith(prefix) for prefix in VOLATILE_LINE_PREFIXES)
    )


def has_material_change(new_text: str, old_text: str) -> bool:
    return normalize_report(new_text) != normalize_report(old_text)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--output")
    parser.add_argument("--compare", nargs=2, metavar=("NEW", "OLD"),
                        help="exit 0 only when NEW differs materially from OLD")
    args = parser.parse_args()

    if args.compare:
        new_path, old_path = (Path(p) for p in args.compare)
        try:
            new_text = new_path.read_text(encoding="utf-8")
            try:
                old_text = old_path.read_text(encoding="utf-8")
            except FileNotFoundError:
                old_text = ""
            changed = has_material_change(new_text, old_text)
        except (OSError, UnicodeError, ValueError) as exc:
            # ERROR is never NO_CHANGE: exit 2 is distinct from 1/0.
            print(f"ERROR: falha ao ler/comparar snapshot: {exc}")
            return 2
        print("CHANGE" if changed else "NO_CHANGE")
        return 0 if changed else 1

    if not args.output:
        parser.error("--output é obrigatório fora do modo --compare")
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
