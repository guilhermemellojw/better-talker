# COORD-04/P13 — Fechamento dos bloqueantes de comunicação

**PR #64** · branch `feat/coord-communication-foundation` (Draft) · **HEAD inicial:** `ed587e3305514e05959e5b354586dca324fa24bf` · **HEAD de código:** `f945cf1835137035192463d693e5dc678593a015` · **HEAD final:** commit documental que contém este relatório.
**Main observada:** `cc90091a60c7b50ca8e0255660baba378bc9e997` · **merge-base do PR:** `4ae783759086353d55edcf31b587d51a491b0ebd`.
**Checkpoint:** `/tmp/opencode/P13-CHECKPOINT.md` e cópia persistente em `/home/guilherme/.local/state/better-talker/P13-CHECKPOINT.md`.

> Transporte exclusivamente sintético, SHADOW, sem sessão OpenCode real, sem POST real, sem LLM, credenciais, Gradle, Android, ADB, merge ou operação destrutiva. Testes Python host-only, rede bloqueada. PR #64 mantido em Draft.

---

## 1. Estado de retomada

O P13 havia parado após a auditoria do HEAD `ed587e33`, com os seguintes bloqueantes provisórios:

1. **P0:** o caminho padrão podia transmitir com `admission_authorized=false` e `exclusive_consumer=false`; o executor checava o hash da evidência de consumidor, mas não as decisões.
2. **P1:** catálogo com `cost` ausente/vazio ou `active` ausente era aceito como gratuito/disponível.
3. **P1:** o binding de aprovação não vinculava `approver_id`/`approval_reference`; alterá-los não invalidava a aprovação.
4. **P1:** evidência de consumidor só verificava existência de arquivo; lock liberado, diretório e lock de outro consumidor passavam.
5. **P1:** `UNKNOWN` com `observed_message_id` divergente do esperado ficava permanentemente bloqueado na reconciliação.
6. **P2:** o teste de contrato padrão usava um ID de POST divergente (`msg-pilot-{SID}`), então o “1 POST” terminava em UNKNOWN.

O P13 retomou exatamente nesses pontos. Nenhum achado anterior foi presumido resolvido sem teste.

---

## 2. Correções implementadas

### 2.1 Admissão e exclusividade (P0)

`PilotExecutor._validate(require_real=True)` agora exige, além do manifesto/hash:

- `admission_authorized is True`;
- `exclusive_consumer is True`;
- hash de evidência de exclusividade válido;
- custo numérico da sessão, política, inbox, repositório e base de admissão já existentes.

A proposta do CLI continua emitindo `admission_authorized=false` (proposta não é autorização), mas `exclusive_consumer` passa a refletir a evidência de lock verificada. A promoção para `true` é feita na aprovação externa sintética, que reconstrói o manifesto a partir da autorização aprovada. O caminho padrão com qualquer um dos dois flags falso agora produz **0 POST**; o caminho positivo com ambos verdadeiros produz 1 POST e fica em `DISPATCHING`.

**Limite remanescente:** os flags continuam sendo declarações do aprovador; não há autoridade criptográfica. A autenticidade externa depende do #72.

### 2.2 Catálogo fail-closed (P1)

`verify_model_catalog()` agora:

- rejeita `cost` ausente, vazio, não-lista, booleano, negativo ou não-zero;
- exige `active is True` (ausente, `false`, `None` ou string falham);
- mantém `enabled=false` como indisponível;
- exige lista de custos conhecida e explicitamente zero; cache, quando presente, também precisa ser numérico e zero.

Sete casos novos cobrem ausência/vazio, booleano/inválido, custo positivo, `active` ausente/inválido/falso, `enabled=false` e o caso válido.

### 2.3 Binding de aprovação (P1)

`PilotApproval.validate(require_real=True)` agora exige:

- `binding.approver_id == approval.approver_id == manifest.maintainer_id`;
- `binding.approval_reference == manifest.approval_reference`;
- digests do manifesto/material;
- `approver_type="human"` e ambiente `APPROVED`.

Alterar o aprovador, a referência ou o material invalida a aprovação. O binding continua sendo **integridade**, não assinatura/autenticação; a autoridade centralizada do #72 permanece necessária.

### 2.4 Evidência de consumidor (P1 parcial)

`verify_consumer_evidence()` agora:

- exige arquivo de lock regular (rejeita diretório e symlink);
- exige `flock` exclusivo efetivamente adquirido (`LOCK_EX|LOCK_NB` deve falhar); arquivo liberado falha;
- confere `authorization_reference` quando fornecida;
- mantém sessão, endpoint e janela temporal.

O executor e o CLI passam a referência de autorização esperada. Lock liberado e diretório agora falham.

**Limite remanescente:** `flock` prova que alguém mantém o lock, não que a identidade seja o consumidor declarado; dois arquivos de evidência podem apontar para o mesmo lock mantido. A exclusividade global e a identidade do titular continuam dependentes do ledger #70 e da autorização centralizada #72.

### 2.5 Reconciliação de UNKNOWN (P1)

`OpenCodeTransport.operation_status()` aceita candidatos de ID (`prompt_ids`) e registra o ID efetivamente observado. `PilotExecutor.reconcile()` usa o ID esperado e o `observed_message_id` do receipt como candidatos; se o texto do prompt e o relatório estiverem presentes, reconcilia para `COMPLETED` mesmo com receipt divergente. Não há reenvio: a segunda execução continua bloqueada em `UNKNOWN`.

O caminho 504 (sem receipt) continua reconciliando; o caminho divergente agora também reconcilia quando há evidência textual independente.

### 2.6 Contrato padrão (P2)

O cliente falso do teste de contrato passou a devolver o ID esperado `msg-pilot-OP-COORD04-PILOT-001`, em vez de `msg-pilot-{SID}`. O teste positivo agora verifica `DISPATCHING`, `receipt_confirmed=True` e exatamente 1 POST; antes ele só verificava `executed` e contava o POST em UNKNOWN.

---

## 3. Testes e fail-first

### 3.1 Suíte final

Ambiente isolado, `HOME`/`TMPDIR` temporários, `unshare -rn` sem rota externa:

| Suíte | Resultado |
|---|---|
| `unittest discover` (`.opencode/scripts/test_*.py`) | **226/226 OK** |
| `test_supervisor.py` | **167/167 PASS** |
| **Total** | **393/393** |

- 217 testes anteriores preservados + **9 novos** P13.
- `git diff --check` limpo.
- Nenhum ADB/Gradle/Android/LLM/sessão real.

### 3.2 Fail-first

O mesmo arquivo `test_pilot_p13.py` + o contrato atualizado foram executados contra o HEAD anterior `ed587e33`, em worktree separada:

- **9 falhas/9** no baseline: catálogo vazio/ausente/booleano, `active` ausente, binding forjado, admissão/exclusividade falsas, lock liberado/diretório, UNKNOWN divergente.
- **9/9 passam** no HEAD de código `f945cf18`.

Isso demonstra falha funcional, não apenas falha de compilação.

---

## 4. Compatibilidade com #70 e #71 (sem duplicação)

### 4.1 #70 — ledger persistente

Contrato integrado na main (`cc90091a`, PR #70): `coord_ledger.Ledger` com SQLite privado, estados `PROPOSED/AUTHORIZED/DISPATCHING/DELIVERED/RUNNING/UNKNOWN/COMPLETED/FAILED`, reserva atômica em `reserve_dispatch`, `UNKNOWN` preservando reserva, `reconcile` com autoridade e `is_reserved`.

O PR #64 ainda usa `PilotOperationStore` JSON local, porque o branch nasceu em `8abd5fb5` e não contém o #70. **Não copiei o ledger.** A compatibilidade preparada é de contrato:

- o futuro adaptador deve mapear `PilotOperationRecord` para `register_proposed` (mission/operation/project/repository/coordinator/agent/session/instance/worktree/authorization_reference/payload_sha256/timeout);
- a reserva deve ser `reserve_dispatch` (fonte de verdade), não o JSON local;
- `UNKNOWN`, `DELIVERED`, `RUNNING`, `COMPLETED`, `FAILED` e reconciliação devem usar as transições do ledger;
- `PilotOperationStore` pode permanecer como cache de evidências, nunca como autoridade paralela.

O merge-tree `main cc90091a × HEAD final` é limpo (`350ebacc5d90b13508f7f3786d028f9d431fa3bc`, verificado no commit `49c9f109`); a integração real exige trazer a main e remover a duplicação local, o que não foi feito nesta rodada.

### 4.2 #71 — seleção determinística de modelos

Contrato integrado na main: `coord_model_policy.ModelPolicyEngine`, `Policy`, `Inventory`, `Model`, `ModelRef` e `canonical_model_ref` com sufixo `#variant`, Free First/1M e autorização financeira.

O PR #64 mantém `verify_model_catalog()` como verificação do `/api/model` observado; **não copiei** o motor #71. A compatibilidade preparada é:

- o verificador pode construir `Inventory`/`Model` a partir do catálogo e delegar disponibilidade/custo ao #71;
- `ModelRef` do #71 aceita `provider/model#variant`; o `verify_model_catalog` atual já canonicaliza `#variant`;
- o #71 não substitui o probe de `/api/model` nem o gate de custo da sessão; ele seleciona/valida modelo, não transporte;
- a integração real depende de a main ser incorporada ao branch e de um adaptador explícito, sem segundo motor.

### 4.3 #72 — autorização centralizada

O PR #72 está em desenvolvimento (head observado `cfc498e0b6f2fb10ff1a4b93c451b048230d95db`, Draft, 3 arquivos); **não foi modificado**. O #64 ainda não tem autoridade externa:

- o binding de aprovação agora vincula aprovador/referência/material, mas não é assinatura nem prova de identidade;
- o `flock` prova liveness, não identidade/consumo;
- a futura integração deve usar `TrustContract`/`ExternalAuthorityEvidence` do #72, com digest do manifesto integral, sem criar uma segunda âncora;
- `admission_authorized`/`exclusive_consumer` devem vir da decisão centralizada, não de flags locais.

---

## 5. Pendências remanescentes

| Item | Severidade | Situação |
|---|---|---|
| Identidade do consumidor e exclusividade global | **P1** | `flock` detecta lock liberado/diretório, mas não prova que o titular é o consumidor declarado; dois arquivos de evidência podem referenciar o mesmo lock. Depende de #70/#72. |
| Autoridade humana externa | **P1** | O binding agora é íntegro e coerente com o manifesto, mas continua autodeclarado; não há assinatura/âncora externa. Depende do #72. |
| `executed=True` em UNKNOWN/FAILED | P2 | A semântica de tentativa versus conclusão permanece; `COMPLETED` só é certificado na reconciliação. |
| `service_contacted=true`/`source=real` em cliente sintético | P2 | Evidência de transporte sintético ainda se rotula como real quando `transport.is_real`; não altera o gate, mas confunde auditoria. |
| Integração #70/#71 | P1/P2 | Não integrada no branch; main contém os módulos, merge-tree limpo; requer adaptador e remoção da duplicação local. |
| CI do PR #64 | P2 | Só `propose` (roadmap); a suíte Python host-only não roda no CI. Evidência é local. |
| Branch atrás da main | P2 | Base `4ae78375`, main `cc90091a`; merge-tree limpo, mas o resultado mergeado não foi testado. |

---

## 6. Veredito P13

- **P0 de admissão/exclusividade no caminho padrão:** **fechado** — flags falsos produzem zero POST; flags verdadeiros são exigidos para a transmissão sintética.
- **P1 de catálogo:** **fechado**.
- **P1 de binding de aprovação (aprovador/referência):** **fechado** como integridade material; autenticidade externa permanece #72.
- **P1 de lock liberado/diretório:** **fechado**; identidade/globalidade permanece #70/#72.
- **P1 de UNKNOWN divergente:** **fechado** com evidência textual.
- **P2 do teste de contrato:** **fechado** (ID correto, estado e receipt verificados).
- **Comunicação bidirecional sintética:** pronta para a próxima etapa de integração, mas **não** é operação real.
- **Operação real:** **não autorizada**; sem PilotApproval real, sem sessão, sem POST.

**Próxima etapa recomendada:** manter o PR #64 em Draft; incorporar a main `cc90091a` (ou a versão que a coordenação indicar) em uma rodada própria, adaptar #70/#71 e usar a autoridade do #72; depois pedir recertificação P14. Não fazer merge nem operação real antes disso.

**Prioridade beta:** o J05 do PR #75 está tecnicamente aprovado e aguarda homologação física do Agente D; este P13 não bloqueia o retorno prioritário ao beta.
