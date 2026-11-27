# COORD-04/P3B.7 — Contrato real e caminho operacional padrão (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Parecer obrigatório:** `issuecomment-7197229628` (P12).
**Main observada:** `eb85b3920d07aae57964775ce46a02fdc4ad8445` (sem merge).

> Somente código, testes e documentação offline. Rede bloqueada; nenhuma instância real, POST real, prompt, LLM, credencial, sessão nova, revinculação real, alteração de permissões, Android/ADB, HealthAgentForms, merge ou PilotApproval. SHADOW vigente. Sem autocertificação.

---

## A. Fail-first (antes da correção, HEAD `3e4c1b34`)

Teste de contrato construído com respostas sanitizadas reais (`Session.Info.cost` numérico `0`), preparação pelo CLI padrão e simulação exclusiva em `HttpClient`:

- `PilotApproval` não aceitava `approval_binding` → `TypeError` antes de qualquer execução;
- com custo numérico, `prepare` emitia proposta com `admission_basis="inactive_observed"` e `session_cost` ausente, tornando-a não executável;
- observer padrão falhava por `cost` (lista esperada) e ausência de evidência de exclusividade; nenhum caminho CLI populava `--consumer-evidence`;
- catálogo era cópia estática (`self.catalog`), sem `GET /api/model` na reobservação.

## B. HEAD e delta

| Item | Valor |
|---|---|
| HEAD inicial | `3e4c1b346dc4509d7f6f9173f8415d86811d95c5` |
| HEAD do código | `345e8ec2e59af97b92fcf210e6c6af4df25187fc` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Delta corrente | 7 arquivos, **+248/−47**; `git diff --check` limpo; push sem force-push |

Correção documental P11: delta real **+428/−36 no código**, relatório final `3e4c1b34` com +65 linhas separadas. Digests publicados: `coord_pilot.py 22d83490…`, `coord_pilot_cli.py 01a7efc8…`. Fundação intacta.

## C. Caminho padrão exercitado

`test_pilot_contract.py` executa: observação (cliente falso na fronteira I/O) → `prepare --real` pelo CLI → manifesto efetivamente emitido → `external_approval_binding` vinculando o `canonical_sha256` → construção do executor com a autorização e o catálogo **da proposta** → observer padrão → transporte real existente com cliente simulado → **exatamente um POST sintético**. Nenhum método interno é substituído; nenhum manifesto alternativo é montado.

## D. Correções P0

- **`cost` numérico:** `Session.Info.cost` é `Money.USD` numérico acumulado; preços do catálogo permanecem contrato separado. Ausência, tipo inválido, booleano, divergência ou valor não nulo bloqueiam. `session_cost` passa a integrar o material canônico.
- **Proposta vs. aprovação:** proposta é imutável e não exige autorização prévia; a aprovação externa vincula exatamente o digest emitido pelo `prepare` via `approval_binding` em `PilotApproval`. Não é necessário alterar `admission_authorized` no material aprovado; nenhuma autenticidade externa é inventada e nenhuma `PilotApproval` real é emitida.
- **Passagem efetiva:** `PilotExecutor` consome manifesto, autorização, catálogo e evidência de consumidor produzidos pela proposta; `PilotAuthorization` proposta é aceita somente quando há binding externo válido, sem promover booleano local a autorização.
- **Caminho positivo sem atalhos:** uma transmissão sintética pelo `OpenCodeTransport` padrão, com `status`/`prepare`/`transmit`/`_validate_prepared_request` reais.

## E. Reobservação

Observer padrão consulta `info`, `location`, `session`, `active`, `inbox`, `message`, `permission` **e `/api/model`**. Catálogo de arquivo usado no `prepare` tem validade máxima de 30 minutos; o executor reobserva o catálogo por GET, não por cópia estática. Política, tokens/custo, active, inbox, mensagens/parts e pendências são comparados com o material aprovado; ausência ou invalidez bloqueia.

Exclusividade: fronteira controlável definida por evidência do operador com `consumer_id`, lock operacional existente, sessão/endpoint conferidos e validade temporal. `registry_only`, hash estático e flag local não são prova. Ausência global de processos externos depende de integração futura e permanece **bloqueio/limitação explícita**; nenhum ledger foi duplicado.

## F. Probe e revinculação (achados P12)

- Hash de política formatado não basta: `--policy-evidence` compara a política observada com o hash registrado; hash ausente/inválido bloqueia o probe.
- `--permission-policy` no cadastro popular o hash a partir da política observada; matiz `ask` com `reply/reject` é coberto sinteticamente e exige autorização específica.
- `rebind-check` compara endpoint do registro com o evidenciado, preserva `repository_id` e `authorization_reference`, exige `observed_at` fresco (≤30 min) e bloqueia mudança de endpoint sem autorização expressa. Nenhuma revinculação aplicada; nenhum probe real executado.

## G. Aceite

**384/384** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 217 (incluindo **7 novos** `test_pilot_contract`). Demos normal/UNKNOWN: `COMPLETED`, 1 transmissão, UNKNOWN sem reenvio; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. Hermeticidade 217/217 com `HOME` e registry sintéticos. Cada sonda negativa parte do caminho positivo comprovado:

| Sonda após aprovação | POST |
|---|---|
| Política alterada | 0 |
| Tokens/custo alterados | 0 |
| Catálogo alterado | 0 |
| Exclusividade perdida | 0 |
| Binding da aprovação alterado | 0 |
| UNKNOWN | 1 tentativa, sem reenvio |

## H. CI e limitações

CI `propose` concluiu `success` no run `45861286831` (`head_sha=345e8ec2`); commits posteriores são somente documentação (workflow de roadmap, não a suíte — executada localmente). Permanece bloqueada a primeira transmissão real: enforcement de permissões não comprovado, probe/revinculação não executados, aprovação humana válida e manifesto final ausentes, exclusividade global não integrada. PR #64 Draft; PRs #70/#71/#72/#73 intactos.

## I. Parecer

Submeter apenas o delta `3e4c1b34..345e8ec2` à recertificação focal P13, com o teste de contrato padrão e as sondas negativas. **Não autorizar** instância, POST real, prompt, LLM, revinculação ou alteração de permissões sem decisão humana explícita. Se algum controle não puder ser comprovado, declarar **BLOQUEADO** em vez de simular sua resolução. Sem autocertificação; nenhuma outra missão iniciada.
