# WORKTREE_POLICY — ciclo de vida, retenção e limpeza segura (COORD-05D/P1)

**Estado:** proposta em PR Draft. **Nenhuma worktree foi removida.**
**Branch:** `feat/coord05-worktree-lifecycle` · **Base:** `origin/main` `f138b0c4` (merge do PR #68) · **Ownership:** Agente D / COORD-05D.
**Arquivos próprios:** `.opencode/scripts/coord_worktrees.py`, `.opencode/scripts/test_coord_worktrees.py`, este documento.

> **Regra central:** inventariar não é autorizar exclusão. `CLEANUP_ELIGIBLE` é um estado de **revisão**, não uma ordem de remoção. Evidência desconhecida bloqueia exclusão.

## 1. Preflight e fontes

Consultados: `docs/TEAM_COORDINATION.md` §3/§7, `docs/ACTIVE_TASKS.md`, `docs/DELIVERY_POLICY.md`, `docs/COORDINATOR.md` §3/§5/§6.1/§13.
Não foi encontrado contrato COORD-05C dedicado; o PR #70 é o ledger COORD-05A e o PR #72 é a fundação de autorização COORD-06A. A integração com o gerenciador 05C fica para uma rodada futura.

`origin/main` foi conferido em `f138b0c46b07ea3ec2eced13d4f52dbfcfc82c1a`; a branch foi rebaseada sobre ela. O PR #68 e o gate físico foram tratados como protegidos e **não foram alterados**. PRs #64, #70, #71 e #72 não foram editados.

## 2. Estados

| Estado | Significado | Evidência mínima |
|---|---|---|
| `CREATED` | worktree recém-registrada, sem ciclo de vida consolidado | registro Git; tratada como não elegível |
| `ACTIVE` | uso positivo por processo ou sessão | `/proc/<pid>/cwd` ou sessão autorizada |
| `QA_PENDING` | PR Draft/QA não concluído | PR Draft + artefatos/estado de QA |
| `MERGE_PENDING` | PR aberto aguardando integração | PR OPEN não-Draft |
| `RETAINED` | protegida por dependência, retenção, mudanças ou artefato único | motivo registrado |
| `CLEANUP_ELIGIBLE` | **revisão** de exclusão permitida por evidência completa | todos os gates abaixo satisfeitos |
| `REMOVED` | reservado ao pós-exclusão auditada; não usado no inventário | verificação posterior |
| `UNKNOWN` | evidência insuficiente | bloqueia exclusão |

`UNKNOWN` nunca é tratado como inativo. Ausência de processo não prova ausência de uso; diretório inicial de sessão não prova worktree efetiva.

## 3. Proteção absoluta

Não recomendar exclusão quando houver:

- repositório principal;
- missão ativa, PR aberto ou dependência ativa;
- QA pendente;
- sessão vinculada ou evidência de sessão desconhecida;
- operação/missão `UNKNOWN`;
- processo usando a worktree (cwd) ou varredura de processos incompleta;
- alterações staged/unstaged ou untracked não classificadas;
- commits exclusivamente locais não preservados em referência confiável;
- artefatos únicos de QA;
- ownership desconhecido (não inferido pelo nome da pasta);
- caminho simbólico, ausente, prunable ou locked;
- identidade/HEAD divergentes;
- worktree externa ou HealthAgentForms;
- evidência insuficiente.

PR fechado **não** equivale a código integrado. Integração considera merge convencional, squash, merge commit, referência remota confiável e ancestralidade; ancestralidade sozinha não é prova universal. Commits locais não preservados bloqueiam.

## 4. Retenção configurável

| Caso | Revisão após | Condição adicional |
|---|---|---|
| PR integrado e certificado | **48 h** | certificação + integração comprovadas |
| Auditoria encerrada | **24 h** | `audit_closed` + owner/evidência |
| Experimento abandonado | **7 dias** | `abandoned` explícito + owner/evidência |
| Worktree protegida | até resolver dependência | PR/QA/sessão/artefato |
| Evidência essencial | conforme política de entrega | nunca automática |

Tempo transcorrido **não** autoriza exclusão por si só. Os prazos são configuráveis em `Policy`.

## 5. Limites de armazenamento

Limiares injetáveis (GiB):

| Faixa | Espaço livre |
|---|---|
| `IDEAL` | `>= 50` |
| `ATTENTION` | `30–49.99` |
| `RESTRICTION` | `20–29.99` |
| `CRITICAL` | `10–19.99` |
| `EMERGENCY` | `< 10` |

Nenhuma limpeza é disparada automaticamente ao atingir uma faixa. O inventário apenas informa a faixa.

## 6. Classes de limpeza

| Classe | Conteúdo | Tratamento |
|---|---|---|
| **A** | artefatos descartáveis/reproduzíveis (build, caches locais) | não medidos separadamente neste inventário; não removidos; nunca somados automaticamente a B |
| **B** | worktree integralmente elegível | somente após todos os gates e autorização humana vinculada |
| **C** | worktree protegida | retida; motivo auditável |
| **D** | estado desconhecido | bloqueada |
| **E** | caminho externo/HealthAgentForms | não escaneada para conteúdo/tamanho; bloqueada |

Caches compartilhados (`.git`, Gradle, npm, modelos) **não** são automaticamente descartáveis. Espaços de hardlinks, arquivos esparsos, objetos Git compartilhados e paths sobrepostos não são somados como se fossem independentes; as estimativas são limites superiores.

## 7. Inventário real (somente leitura)

Snapshot: **2026-11-26T14:17Z** · fonte primária `git worktree list --porcelain` · `du -sb --exclude=.git` · PRs via `gh pr list --state all` · processos via `/proc` do mesmo UID · proteção explícita padrão da jornada beta: **PRs #68, #75 e #76** (`DEFAULT_PROTECTED_PR_NUMBERS`); `--protect-pr` apenas ADICIONA ao conjunto.

| Métrica | Valor |
|---|---|
| Worktrees registradas | **149** |
| Espaço livre | **41,58 GiB** · banda **ATTENTION** |
| Tamanho medido (worktrees do projeto) | **31,8 GiB** |
| Tamanho desconhecido | **8** (externas/HealthAgentForms não medidas) |
| `CLEANUP_ELIGIBLE` | **0** |
| Candidatos à revisão (não elegíveis) | **49** |
| Potencial dos candidatos à revisão | **11,31 GiB** |
| `ACTIVE` / `QA_PENDING` / `MERGE_PENDING` / `RETAINED` / `UNKNOWN` | 1 / 7 / 1 / 32 / 108 |
| Classes C / D / E | 41 / 100 / 8 |

**Bloqueadores dominantes:** `ownership_unknown` 149; `session_evidence_unknown` 149; `qa_evidence_unknown` 149; `process_scan_incomplete` 149; `certification_unknown` 62; `integration_unknown`/`mission_unknown` 79; `protected_pr` 3 (inclui PR #68).

**Conclusão do inventário:** não há exclusão elegível. Os 49 candidatos são apenas **fila de revisão**; para avançar é necessário injetar evidência autorizada de ownership, sessões, QA/certificação e completar a varredura de processos, depois revalidar HEAD/status. O PR #68 e seus worktrees de QA/recertificação estão protegidos.

**Artefatos locais de D (fora do repositório):** pacote `coord05d-worktrees` com `inventory-final.json`, `REPORT-FINAL.md` e `run-final.txt`. O inventário contém caminhos absolutos, mas nenhum conteúdo de credenciais, argumentos de processo ou dados particulares.

**Ownership desta rodada:** a branch/worktree `feat/coord05-worktree-lifecycle` é declarada como propriedade de D/COORD-05D neste documento. O inventário automatizado **não inferiu** ownership histórico e não injetou um manifesto de owners; por isso os 149 registros permanecem `ownership_unknown`, e o worktree novo aparece `ACTIVE` apenas pela evidência de processo.

## 8. Motor e contrato

`coord_worktrees.py` é determinístico e somente leitura:

- recebe snapshots/política/PRs/evidências por injeção;
- identifica HEAD, branch/detached, locked/prunable, staged/unstaged/untracked, commits locais, ancestralidade main, referência remota, PR, ownership, sessão, QA, processos e tamanho;
- classifica em estados/classes com códigos estáveis;
- `UNKNOWN` e qualquer bloqueador impedem `CLEANUP_ELIGIBLE`;
- `build_report()` produz JSON e relatório legível, com espaço por categoria e fila de revisão;
- `validate_proposal()` revalida uma futura proposta e rejeita HEAD/owner/status/processo/sessão alterados;
- comandos Git destrutivos são bloqueados por guarda explícita no código.

CLI:

```bash
python3 .opencode/scripts/coord_worktrees.py \
  --repo <worktree> --fetch-prs --protect-pr 999 --sizes \
  --json-out inventory.json --report-out REPORT.md
```

Nenhuma opção executa remoção, prune, clean, gc, reset, limpeza de branch ou ADB.

## 9. Protocolo futuro de exclusão (não executado)

1. Inventário read-only e revisão da fila.
2. Proposta com **caminhos exatos**, HEAD, owner, tamanho, PR, evidências e classe.
3. Preservação das evidências/QA necessárias.
4. Autorização humana vinculada à proposta (caminho + HEAD + escopo).
5. Revalidação imediata de HEAD, status, owner, processos e sessões.
6. Exclusão individual pelo mecanismo Git apropriado, após autorização.
7. Verificação posterior (path ausente, registro Git íntegro, branch preservada).
8. Registro auditável do espaço recuperado.

Mudança material entre proposta e execução invalida a autorização. **Nunca** usar force como fallback; **não** remover branches como consequência da worktree; **não** executar `git worktree prune` indiscriminadamente.

## 10. Testes e limites

`python3 .opencode/scripts/test_coord_worktrees.py` — **39 testes host-only PASS** (34 anteriores preservados + 5 regressões P2):

- worktree ativa, PR integrado, PR aberto, PR Draft, PR fechado sem integração;
- alterações locais, commits exclusivamente locais, detached HEAD;
- ownership ausente, artefato único, missão UNKNOWN, sessão vinculada;
- worktree locked, caminho simbólico, mudança de HEAD;
- evidência de sessão/QA/processo insuficiente;
- retenção 48 h/24 h/7 d e limites de armazenamento;
- estimativa de tamanho e deduplicação de paths aninhados;
- PRs #68/#75/#76 protegidos por padrão e proteção existente preservada ao acrescentar `--protect-pr`;
- conjunto protegido sem evidência confiável (`protected_set_evidence_known=false`) bloqueia elegibilidade;
- conflito de certificação (`certifications` × `PRInfo.certified`) e conflito de owner (`audit_closed` + `abandoned`) produzem `UNKNOWN` e invalidam proposta;
- `validate_proposal()` e guarda contra comandos destrutivos;
- scanner real em repositório Git temporário, sem efeitos destrutivos.

**Limitações conhecidas:**

- ownership histórico não foi inferido; sem manifesto autorizado, permanece `UNKNOWN`;
- sessões/QA não foram lidas de arquivos privados; evidência desconhecida bloqueia;
- varredura de processos é do mesmo UID e marcou-se incompleta por processos de sessão com cwd não legível; ausência de processo não é prova de inatividade;
- tamanhos usam bytes aparentes, excluem `.git` compartilhado e não deduplicam hardlinks/sparse/overlap entre worktrees;
- artefatos descartáveis (A) não foram medidos separadamente;
- caminhos externos/HealthAgentForms não são escaneados;
- nenhum beta, release, merge, schema, ADB, build ou LLM pago foi executado nesta missão.

## 11. Integração futura

A política é deliberadamente independente de transporte, modelo, sessão e Android. A integração com COORD-05C deve consumir o mesmo contrato de inventário/proposta/revalidação, sem herdar autorização automaticamente. `ASSISTED` e `DELEGATED` permanecem desativados; o motor aceita apenas `SHADOW`.
