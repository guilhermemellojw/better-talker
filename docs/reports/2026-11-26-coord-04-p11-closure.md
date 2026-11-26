# COORD-04/P3B.6 — Fechamento dos bloqueadores P11 (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Parecer P11:** `issuecomment-7194380293` · `docs/reports/2026-11-26-qa-coord-64-p11.md`.
**Main observada:** `f138b0c46b07ea3ec2eced13d4f52dbfcfc82c1a` (PR #68 integrado; **sem merge nesta missão**).

> Somente código, testes e documentação offline. **Nenhuma instância, POST real, prompt, LLM, novo session, revinculação real, alteração de permissões, PilotApproval, credencial, Android, ADB, HealthAgentForms ou merge.** SHADOW vigente. Sem autocertificação.

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD vigente | `dfa57fa0b4ecc2c113390800b5e941a54c0ef099` |
| HEAD do código | `4a921c0159eba996ac001cf88cd0ee4de2acbfdb` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Delta | 5 arquivos, **+203/−36**; `git diff --check` limpo; push sem force-push |

Digests publicados: `coord_pilot.py 136691f2…`, `coord_pilot_cli.py adb64074…`. Fundação (`coord_checkpoint`, `coord_communication`, `demo_communication`, `test_communication`) intacta.

## B. Fail-first (HEAD `dfa57fa0`)

- Circularidade: com evidência técnica completa, mas sem autorização externa, `prepare --real` retornou `blocked=True`, `manifesto=False` e bloqueadores `admissão=inactive_observed`, `admissão operacional não autorizada externamente`, `catálogo do modelo não verificado`, `enforcement de permissões não observado` — nenhum caminho CLI produzia proposta aprovável.
- Revalidação: `_validate()` reconstruía o manifesto com as evidências do próprio manifesto aprovado e comparava hashes; a auditoria demonstrou 1 POST sintético após mutação de política, tokens/custo, catálogo e exclusividade.

Após as correções: proposta é emitida sem aprovação; autorização externa vincula o digest; cada uma das quatro mutações produz **zero POST**.

## C. F1/F2 — proposta, aprovação e reobservação

Sequência separada: observação técnica → proposta imutável (`approved=false`, `PROPOSED`, `admission_authorized=false`) → aprovação externa vinculada ao `canonical_sha256` (`external_approval_binding`) → revalidação → execução. A proposta não exige aprovação para existir; `PilotApproval` não é emitido pela CLI em operação real. Alteração material invalida o vínculo.

`PilotExecutor._validate()` passou a reobservar imediatamente antes do POST (também antes de `transmit()`): instância, destinatário, modelo/variante, hash da política, tokens/custo, catálogo, active, inbox, mensagens/parts, pendências e exclusividade com fundamento verificável (`exclusive_consumer_evidence_sha256`). Igualdade de hash deixou de ser tratada como observação. Evidência ausente, vencida, inválida ou divergente bloqueia; exclusividade sem evidência mantém bloqueio explícito.

## D. F3 — teste ponta a ponta

`test_pilot_p11.py` demonstra: observação → proposta → aprovação sintética externa → reserva → reobservação → **uma** transmissão sintética. Quatro sondas P0 (política, tokens/custo, catálogo, exclusividade) produzem `AuthorizationError` com `prepare`/`transmit` não chamados. UNKNOWN continua sem reenvio e os controles anteriores permanecem.

## E. F4 — catálogo em `prepare`

`apply_catalog_evidence()` é usado em `preflight --real` **e** `prepare --real`. Sem `--catalog` a proposta bloqueia; com catálogo sanitizado (identidade exata `opencode/hy4-flash-free`, variante `none`, custo zero, disponibilidade) a proposta é emitida. Inventário desatualizado não é aceito como comprovação atual; sem fallback pago; PR #71 intacto.

## F. F5 — probe de permissões

`permission-probe-plan` agora funciona offline sem `--real`, exige hash SHA-256 verificado (`policy_preserved=false` e `blocked=true` quando ausente), usa matriz fechada com recursos temporários inofensivos (`/tmp/opencode/permission-probe-readonly`), ações compatíveis com v2.3.2 (`edit` para edit/write; `browser`/`skill` incluídos; sem `write`/`rm -rf`/`/etc/passwd`), limites separados para GETs de preflight, POSTs de avaliação e replies, parada diante de resultado inesperado e `ask` sujeito a autorização específica. Mantém `question_is_tool` e `deny_is_not_exhaustive`. Nenhum probe real executado.

## G. F6 — revinculação

`update_proposal.preserved` passa a incluir `repository_id` e `authorization_reference` além de session ID, projeto, agente, modelo e location. Lacunas do registro legado continuam reportadas sem preenchimento por presunção; proposta não aplicada. Nenhuma revinculação real.

## H. F7 — higiene

Corrigida a contagem do delta P3B.5 (+335/−12) e registrada a limitação de `IDLE_TERMINAL` (inalcançável na observação real sem `idle_evidence`; nenhuma heurística criada). Todos os SHAs citados são os efetivamente publicados.

## I. Verificação

**377/377** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 210 (incluindo **7 novos** `test_pilot_p11`). Demos normal e `UNKNOWN`: `COMPLETED`, `transmit_calls=1`, reenvio bloqueado; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. Suíte 210/210 com `HOME` temporário e registry sintético — sem acessar registry real. Stores temporários; segredos sintéticos; zero POST real.

## J. CI e preservação

CI `propose` = `success` no run `45840375577` (`head_sha=4a921c01`). PRs #68, #70, #71, #72 e agentes A–E preservados; nenhuma alteração em produto, serviço, banco produtivo ou configuração global.

## K. Limitações e parecer

Permanece bloqueada a primeira transmissão: enforcement runtime não observado, probe/revinculação não executados, aprovação humana válida e manifesto final ausentes, catálogo real não verificado em operação. Submeter apenas o delta `dfa57fa0..4a921c01` à recertificação focal, com fail-first e 377 regressões. **Não autorizar operação real**; aguardar auditoria independente e autorização humana específica. PR #64 permanece Draft.
