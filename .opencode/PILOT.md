# COORD-04/P3A.1 — piloto real controlado (preparação, sem execução)

Este documento prepara o primeiro piloto real. **Nenhuma transmissão, POST, sessão,
prompt, serviço ou aprovação real foi operado na P3A/P3A.1.** A ativação depende de P3B,
autorização explícita do mantenedor e recertificação independente.

## Destino experimental proposto

| Campo | Valor proposto |
| --- | --- |
| `project_id` | `better-talker` |
| `repository_id` | `guilhermemellojw/better-talker` |
| `agent_id` | `build` (agente efetivo observado na sessão experimental real) |
| `session_id` | `ses_de83b0620ffeMIQP5tvH7MLFEx` (sessão real persistida) |
| `instance_id` | a recalcular no preflight de P3B a partir de `GET /api/info` |
| `location` | `/home/guilherme/Documentos/better-talker-coord-supervisor-shadow` |
| `modelo` | `opencode/hy4-flash-free` (substituição autorizada pelo mantenedor em P3B.3.1) |

O destinatário operacional real é `ses_de83b0620ffeMIQP5tvH7MLFEx` com agente `build`.
O ID histórico `ses_defc05853ffeej4fPXrp7frx4x` e `agent_id="A"` existem **somente em
fixtures** e não são destinatários autorizados. Se qualquer metadado divergir, interromper.
A sessão não deve
possuir acesso operacional desnecessário a ferramentas; instrução textual não substitui
restrição efetiva.

## Missão experimental proposta

```json
{
  "schema_version": "1.0",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "mission_id": "MISS-COORD04-PILOT-001",
  "operation_id": "OP-COORD04-PILOT-001",
  "coordinator_id": "supervisor-shadow-experimental",
  "target_agent": "A",
  "target_session_id": "ses_de83b0620ffeMIQP5tvH7MLFEx",
  "target_instance_id": "<preenchido no preflight P3B>",
  "target_location": "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow",
  "objective": "Validar comunicação bidirecional real em sessão experimental",
  "payload": {
    "prompt": "MISS-COORD04-PILOT-001 / OP-COORD04-PILOT-001. Responda somente em texto estruturado, sem ferramentas, sem edição, sem shell, sem Git, sem operações em dispositivos e sem acesso a credenciais. Inclua o marcador <!-- BT-COORD-REPORT:v1 --> e uma linha contract: com JSON contendo schema_version, project_id, mission_id, operation_id, agent_id, session_id, status, head_sha null, branch null, test_evidence [], blockers [], artifacts [], completed_at e acceptance_results."
  },
  "payload_sha256": "<calcular no preflight P3B>",
  "expected_deliverables": ["resposta estruturada com mission_id e operation_id"],
  "acceptance_criteria": ["identidade da missão devolvida", "status explícito", "marcador BT-COORD-REPORT:v1"],
  "ownership_constraints": ["somente sessão experimental; nenhuma edição de arquivo"],
  "authorization_reference": "AUTH-COORD04-PILOT-001",
  "created_at": "<P3B>",
  "expires_at": "<P3B, no máximo 1 hora>",
  "timeout": 60,
  "scope": "piloto de comunicação experimental",
  "expected_head_sha": null,
  "expected_branch": null
}
```

O prompt e `parse_pilot_report()` estão alinhados: o texto pede exatamente uma linha
`contract:` com o JSON que o parser consegue validar. O `message_id` do relatório é tomado
da envelope da mensagem devolvida pela API, não de uma afirmação do modelo. Não são exigidas
evidências fictícias de testes, HEAD ou alterações de código de uma missão puramente textual;
resposta textual não é certificação de código.

Modelo congelado e autorizado: `opencode/hy4-flash-free` (variante observada `none`).
Diretriz futura de produto: FREE FIRST / 1M PREFERRED. Nenhuma rotação automática é autorizada.

## Autorização necessária (proposta, não aprovada)

```json
{
  "authorization_id": "AUTH-COORD04-PILOT-001",
  "maintainer_id": "<mantenedor>",
  "approval_reference": "<referência da decisão registrada>",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "instance_id": "<preenchido no preflight P3B>",
  "target": ["better-talker", "guilhermemellojw/better-talker", "build", "ses_de83b0620ffeMIQP5tvH7MLFEx", "<instance_id>", "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"],
  "mission_id": "MISS-COORD04-PILOT-001",
  "mission_identity": "<hash canônico calculado no preflight>",
  "operation_id": "OP-COORD04-PILOT-001",
  "prompt_sha256": "<hash do prompt acima>",
  "scope": "piloto de comunicação experimental",
  "issued_at": "<P3B>",
  "expires_at": "<P3B>",
  "approved": false,
  "environment": "PROPOSED",
  "max_transmissions": 1,
  "expected_model": "opencode/hy4-flash-free",
  "canonical_sha256": "<calculado após aprovação>"
}
```

O supervisor não pode emitir essa autorização. `approved=true` e `maintainer_id`
autodeclarado **não** constituem prova de autorização humana. O caminho correto é uma
`PilotApproval` criada pelo operador humano, com:

- `approver_type="human"`;
- `reviewed_head_sha` do HEAD revisado;
- `manifest_sha256` = SHA-256 do JSON canônico com `mission_identity`, `operation_id`,
  `session_id` e `prompt_sha256`;
- validade explícita e ambiente `APPROVED` somente após decisão registrada.

A aprovação não pode ser produzida automaticamente pelo agente e não é exposta às
ferramentas do supervisor. O adaptador rejeita `PROPOSED`, `SYNTHETIC` e `FIXTURE` para HTTP
real; uma autorização `FIXTURE` nunca habilita escrita real. Nenhuma autorização real foi
criada nesta missão. SHADOW permanece inalterado; se a operação exigi-la formalmente, propor
decisão separada sem alterar o modo global.

## Arquitetura inativa

- `scripts/coord_pilot.py`: `OpenCodeTransport`, `PilotAuthorization`, `PilotApproval`,
  `PilotOperationStore`, `PilotExecutor`, reserva exclusiva local e recuperação
  `DISPATCHING → UNKNOWN`.
- `PilotExecutor.execute()`: valida autorização/manifesto, cria e valida registro, adquire
  reserva, persiste `DISPATCHING` **antes** do POST, revalida, executa no máximo uma tentativa
  e persiste o resultado. Nenhum caminho operacional alternativo de escrita é exposto.
- `LocalHttpClient`: validação estrutural de URL (scheme `http`, host exatamente `127.0.0.1`,
  porta explicitamente autorizada, sem userinfo/fragmento/parâmetros/caminho extra), proxies
  desativados, sem redirecionamento, paths restritos a `/api/`; POST exige `allow_write=True`.
- `DisabledHttpClient`: padrão seguro, falha fechado.
- Persistência: `~/.local/state/better-talker/coord-pilot/`, diretório `0700`, arquivo `0600`,
  escrita atômica com `fsync` de arquivo e diretório, transições validadas, sem segredos.
- Nenhum daemon, lock distribuído, banco externo, ledger completo ou orquestrador.

## Runbook proposto

> Na P3A.2 toda execução é **offline/fixture**. Nenhum comando `opencode api` real, nenhum
> POST e nenhuma operação de sessão foi executada. Os comandos `opencode` abaixo são
> procedimento documentado para o preflight posterior (P3B), não executado nesta missão.

### 1. Preflight simulado (somente leitura, fixture)

```sh
python3 .opencode/scripts/coord_pilot_cli.py --endpoint http://127.0.0.1:49999 preflight
```

 Produz a identidade observada do ambiente a partir de metadados autorizados: instância,
 projeto, location, sessão, agente, modelo, estado da sessão e inbox. `agent_id=A` na fixture é
 **pressuposição** explicitamente listada em `assumptions`; permissões efetivas e
 disponibilidade permanecem em `limits`. Ausência de `busy` e ausência em `session/active`
 **não** provam disponibilidade.

### 2. Dry-run integrado

```sh
python3 .opencode/scripts/coord_pilot_cli.py dry-run
python3 .opencode/scripts/test_supervisor.py
python3 .opencode/scripts/test_communication.py
python3 .opencode/scripts/test_pilot_adapter.py
python3 .opencode/scripts/test_pilot_executor.py
python3 .opencode/scripts/test_pilot_closure.py
python3 .opencode/scripts/demo_communication.py
```

`dry-run` usa cliente HTTP falso, manifesto, autorização, executor, armazenamento e
relatório; demonstra uma única escrita simulada (`http_posts=1`, `real_calls=0`) e
reconciliação para `COMPLETED` por evidência, nunca por presença de relatório.

### 3. Manifesto exato, ainda não aprovado

```sh
python3 .opencode/scripts/coord_pilot_cli.py prepare
```

Emite o manifesto canônico (`canonical_sha256`) com HEAD executado, digests dos arquivos
operacionais recalculados, identidade integral da missão e da autorização, destinatário,
instância, modelo, endpoint, prompt e hash, operation_id, validade e `max_transmissions=1`.
`approved=false`. A CLI **não** cria aprovação humana nem autorização.

### 4. Execução — bloqueada por padrão

```sh
python3 .opencode/scripts/coord_pilot_cli.py execute
# -> {"executed": false, "state": "BLOCKED_BY_DEFAULT", "real_calls": 0}
# Com ativação e aprovação, mas sem POST real autorizado nesta missão:
python3 .opencode/scripts/coord_pilot_cli.py execute --activate --approval /caminho/aprovacao.json
# -> "BLOCKED_APPROVAL_MANIFEST_DIVERGENT" se o hash não vincula o manifesto preparado
# -> "BLOCKED_PENDING_REAL_ACTIVATION" se vincula (P3A.2 não autoriza POST real)
```

A autoridade vem de ação explícita do mantenedor; `approver_type != human`, ambiente
diferente de `APPROVED` e manifesto divergente são rejeitados antes de qualquer escrita.

### 5. Reconciliação e encerramento (simulação offline)

```sh
python3 .opencode/scripts/coord_pilot_cli.py reconcile
python3 .opencode/scripts/coord_pilot_cli.py closeout
```

`reconcile` consulta evidências autorizadas e persiste somente transições válidas, sem
reenvio. Evidência de fixture **nunca** é aplicada a registro não-fixture: se o registro
não veio de `execute` com ambiente `FIXTURE`, o comando permanece inalterado
(`reason: registro não-fixture; reconciliação real exige P3B`). `closeout` apresenta
estado, evidências e limitações sem alterar estados terminais.

### 6. Escrita — somente P3B, após autorização explícita

```text
1. Confirmar aprovação humana do manifesto exato e autorização APPROVED não expirada.
2. Confirmar preflight real e destinatário exato (instância, projeto, location, sessão,
   agente efetivo `build`, modelo `opencode/hy4-flash-free`), sem presumir agent_id=A.
3. Criar registro persistente em AUTHORIZED ANTES de qualquer transmissão.
4. Adquirir reserva exclusiva local e vincular a guarda à autorização consumida.
5. Persistir DISPATCHING e confirmar a escrita ANTES do POST.
6. Revalidar autorização; executar no máximo um POST /api/session/{sessionID}/prompt com
   id vinculado ao operation_id, somente pelo executor protegido.
7. Persistir evidências e resultado; falha de disco antes do POST impede transmissão.
8. Consultar inbox, mensagens e active para distinguir enfileirado, registrado e executando.
9. Recuperar somente relatório com operation_id autorizado, message_id do envelope,
   autoria observada e associação prompt-resposta.
10. Se UNKNOWN: não reenviar; exigir evidência positiva ou decisão humana.
11. Encerrar liberando a reserva, sem alterar estados terminais.
```

Nenhum comando de escrita real é executável nesta rodada: a ativação depende de decisão
separada, autorização real e recertificação independente.

## Preflight posterior (P3B) — comandos exatos (NÃO executados nesta missão)

Endpoint é **fonte única e canônica**: `http://127.0.0.1:<porta>`; HTTP, host exatamente
`127.0.0.1`, porta autorizada, sem userinfo, query, fragmento ou caminho, sem proxies e sem
redirecionamentos. O cliente real somente leitura bloqueia POST mecanicamente; falha de
conexão **nunca** cai para fixture. Stores de fixture e de operação real são separados.

Estado observado em P3B.1: instância privada isolada iniciada e encerrada com segurança;
GETs autenticados pela CLI oficial; banco vazio e sessão fixada inexistente (404). O cliente
COORD-04 recebeu 401 porque não enviava autenticação. P3B.2 adaptou o cliente ao formato real.

**Autenticação experimental (alternativa B):** a credencial é fornecida explicitamente pelo
operador por variável de ambiente cujo **nome** é passado em `--auth-env`; o valor nunca é
lido para logs, manifestos, comandos ou relatórios. O cliente constrói `Authorization: Basic`
(usuário fixo `opencode`) e só emite o cabeçalho para o endpoint privado ao qual foi vinculado.
Nenhuma credencial produtiva é lida, copiada ou reutilizada; autenticação não é desativada.

```sh
# endpoint = http://127.0.0.1:<porta> observado em /api/info da instância privada
# COORD_PILOT_AUTH = nome da variável definida pelo operador (valor nunca impresso)
python3 .opencode/scripts/coord_pilot_cli.py preflight --real \
  --endpoint <endpoint-local-autorizado> --auth-env COORD_PILOT_AUTH
```

O ID `ses_de83b0620ffeMIQP5tvH7MLFEx` (agente `build`) é o destinatário real cadastrado.
O ID `ses_defc05853ffeej4fPXrp7frx4x` existe somente em fixtures. O caminho real exige
destino previamente cadastrado e autorizado (cadastro local, sem criar sessão real):

```sh
python3 .opencode/scripts/coord_pilot_cli.py session-register \
  --store-dir ~/.local/state/better-talker/coord-pilot/real \
  --session <ses-efetiva> --endpoint <endpoint> --instance-id <instancia> \
  --agent-id <agente-observado> --authorization-reference <decisao>
# A flag --permissions-confirmed foi removida: não prova enforcement.
# Para substituir um cadastro existente é exigida ação explícita: --replace
python3 .opencode/scripts/coord_pilot_cli.py prepare --real \
  --endpoint <endpoint> --session <ses-efetiva> --auth-env COORD_PILOT_AUTH
```

### Contrato real de criação da sessão (a confirmar no `/doc` da instância privada)

Contrato **efetivamente observado** na instância v2.3.2 (P3B.3): `/doc` serve HTML da
aplicação; a especificação real está em `GET /openapi.json` (OpenAPI 3.1.0, 142 paths):

- **Caminho:** `POST /api/session`; **operationId:** `session.create` (confirmado no spec).
- **Parâmetros de query:** **nenhum**. `requestBody` é **obrigatório**.
- **Schema:** nenhuma propriedade de nível superior é obrigatória. O schema expõe oito
  propriedades (`id`, `parentID`, `title`, `agent`, `model`, `location`, `metadata`,
  `permissions`), mas **disponível ≠ obrigatório**. Payload mínimo aceito pelo schema é
  `{}`; **payload mínimo operacional não é vazio**: deve vincular `title`, `agent`,
  `model` e `location`, além da política `permissions`.
- `location.directory` vai **no corpo** (`Location.PublicRef` com `directory` absoluto),
  não em query.
- `Model.Ref` exige **`id`** e `providerID` (não `modelID`); variante é opcional e
  `variant="none"` não acrescenta sufixo à representação canônica `provider/id`.
- `Permission.Ruleset` é uma **lista** de `Permission.Rule` com `action`, `resource` e
  `effect` (`allow`/`deny`/`ask`) — **não** `{permission, pattern, action}`.
- **Nenhum prompt inicial** é exigido nem executado; nenhuma inferência é feita para testar
  modelo ou permissões. Modelo congelado: `opencode/hy4-flash-free`; ausência ou divergência
  bloqueia, sem fallback pago.

Inventário real observado na instância isolada: **18 modelos — 11 `opencode` com custo
listado zero e 7 `groq`**. Modelo contendo `free` no nome não é automaticamente autorizado;
exige-se correspondência exata com o modelo aprovado.

`project.id` do OpenCode é um ID interno (hash), distinto da identidade lógica `better-talker`,
do repositório GitHub (lido de `git remote get-url origin` e validado pela política), do
diretório canônico e da worktree experimental. `prepare --real` usa relógio UTC corrente,
validade de 30 minutos e bloqueia sem destinatário, metadados ou evidências observadas
conferentes. Nenhuma `PilotApproval` é emitida pela CLI; nenhuma sessão é criada nesta rodada.

### Procedimento operacional P3B.3 (autorização humana concedida; sem prompt/LLM)

1. **Ambiente persistente:** diretório privado fora de `/tmp` (por exemplo
   `~/.local/state/better-talker/coord-pilot/experimental-instance`, modo `0700`), com
   `HOME`, `XDG_DATA_HOME`, `XDG_CONFIG_HOME`, `XDG_CACHE_HOME` e `XDG_STATE_HOME`
   exclusivos, logs sanitizados e registro operacional separados. **Não copiar** banco,
   credencial, histórico, configuração global ou mensagens produtivas.
2. **Credencial:** segredo exclusivamente experimental em arquivo `0600` dentro do
   diretório privado; exportar para servidor e cliente por variável de ambiente lida via
   `$(cat <arquivo>)` — o valor nunca aparece em argumento, log, manifesto, relatório,
   commit ou histórico do shell. Não ler credencial produtiva; não desabilitar
   autenticação; não registrar cabeçalhos `Authorization`.
3. **Instância única:** `opencode serve --hostname 127.0.0.1 --port <porta-livre>` com cwd
   experimental, `HOME`/XDG isolados e **sem** `--service` ou `--standalone`. Registrar o
   **PID efetivo do servidor** (dono do listener, não o shell invólucro), confirmar bind
   exclusivo em loopback, caminhos efetivos e banco distinto. Confirmar inventário vazio
   (`GET /api/session`); sessões inesperadas → **bloquear**. Não reiniciar automaticamente.
4. **Cliente COORD-04:** `preflight --real` **sem destinatário**; exigir `source=real`,
   `service_contacted=true`, `authentication_succeeded=true`, endpoint e instância
   coerentes, nenhum fallback e zero POST. Falha de autenticação bloqueia a criação.
5. **Contrato real:** `GET /openapi.json` na instância (`/doc` serve HTML); confirmar
   `POST /api/session`, sem query, `location.directory` no corpo, agente, modelo,
   estrutura `action/resource/effect` e ausência de prompt inicial
   obrigatório. SDK não é prova suficiente; divergência material bloqueia.
6. **Criação:** registrar intenção, endpoint/instância e hash do payload sanitizado; **uma**
   tentativa de POST no caminho confirmado. Sem prompt, `resume`, edição ou operação
   adicional. Timeout/ambiguidade → **não repetir**; classificar `UNKNOWN`, consultar
   evidências autorizadas e aguardar decisão humana.
7. **Validação/cadastro:** recuperar somente metadados da sessão criada; cadastrar o ID
   efetivo no store real com endpoint, instância, agente, projeto, repositório, location,
   modelo e referência humana. Permissões são evidência observada: política retornada pela
   API + hash canônico; declaração local, flag de cadastro ou instrução textual **não**
   comprovam enforcement. Novo preflight somente leitura;
   **não** executar `prepare --real` para manifesto apresentável.
8. **Encerramento:** `kill <pid>` exclusivamente no PID criado, sem `pkill`/`killall`;
   confirmar listener indisponível, serviço compartilhado/processos produtivos inalterados
   e **preservar o banco experimental**. A identidade inclui PID: futura inicialização pode
   exigir revinculação controlada da **mesma** sessão — nunca criar outra para resolver
   divergência.

## Encerramento seguro

```sh
python3 .opencode/scripts/coord_pilot_cli.py reconcile
python3 .opencode/scripts/coord_pilot_cli.py closeout
```

- `DISPATCHING` após interrupção vira `UNKNOWN`; reserva abandonada não é removida
  automaticamente se a titularidade não foi confirmada.
- `UNKNOWN` bloqueia reenvio automático e exige reconciliação por evidência ou decisão humana.
- Relatório divergente não substitui resultado terminal.
- Aceitação HTTP não prova leitura pelo agente; `session/active` ausente não prova disponibilidade.
- Nenhuma garantia de exactly-once, entrega durável ou ordem global é afirmada.

## Controles P3B.4 — admissão, admissão separada da identidade e manifesto

- **Identidade** (`session_identity_confirmed`) é confirmada somente por ID, agente, modelo,
  projeto interno, location e repositório. Disponibilidade e permissões **não** entram nela.
- **Admissão** é tri-state: `busy`, `inactive_observed`, `new_experimental_session`,
  `unknown`. Ausência em `active` **não** implica `idle`; `outcome`/`time` não provam
  disponibilidade. Para a sessão experimental recém-criada e de consumidor exclusivo exigem-se,
  cumulativamente: instância autenticada, endpoint autorizado, banco experimental esperado,
  exatamente uma sessão, identidade coerente, `active` observado sem a sessão, inbox observado
  e vazio, zero mensagens/parts, `time.created==time.updated`, tokens/custo zero, sem
  `outcome`, política de permissões resolvida, exclusividade e autorização humana posterior.
  Falta de evidência ⇒ `UNKNOWN` e bloqueio. Nenhuma heurística permissiva se aplica a A–E.
- **Inbox**: `inbox_observed=true` e `inbox_count=0`. Inbox ausente, falha HTTP, resposta
  inválida, ocupado e não consultado são estados distintos — ausência nunca vira zero.
- **Permissões são evidência, não declaração**: separar política solicitada, retornada pela
  API, precedência documentada (last-match-wins, não observada em runtime), enforcement
  observado e restrição operacional adicional. O hash canônico da política volta ao manifesto.
- **Repositório**: a sessão não retorna `repositoryID`. Ausência é aceita somente com
  `repository_source="git-local"` e validação de `projectID`, canonical, worktree e
  `git remote get-url origin`. Se o campo aparecer, exige-se igualdade exata; nunca fabricá-lo.
- **Revinculação:** mudança de PID altera `instance_id`, nunca autoriza nova sessão. Preparar
  proposta offline com `coord_pilot_cli.py rebind-check --evidence <json>`; valida os mesmos
  campos, exige autorização e **não aplica** nada. Cadastro existente não é substituído
  silenciosamente.
- **Manifesto:** vincula HEAD, digests, endpoint, instância, projeto, repositório,
  `repository_source`, sessão, agente, modelo, location, `permission_policy_sha256`,
  `admission_basis`, evidência de inbox, payload/hash, critérios, validade UTC e uma transmissão.
  Qualquer alteração material invalida a aprovação anterior. Nenhuma `PilotApproval` é emitida
  e nenhum manifesto real apresentável é gerado enquanto restarem lacunas.

### P3B.7 — contrato real do caminho padrão

- `Session.Info.cost` é **`Money.USD` numérico** (valor acumulado da sessão), não lista de
  preços. Preços do catálogo são contrato distinto. Ausência, tipo inválido, booleano,
  divergência ou valor não nulo bloqueiam.
- Caminho padrão verificado: `prepare --real` produz proposta imutável com `approved=false`;
  aprovação sintética externa vincula o `canonical_sha256` emitido; o executor consome o
  **mesmo** manifesto, a autorização e o catálogo da proposta — sem montar manifesto
  alternativo e sem exigir alteração de booleano no material aprovado.
- Observer padrão reobserva via GET (`info`, `location`, `session`, `active`, `inbox`,
  `message`, `permission` **e `/api/model`**). Catálogo estático não prova atualidade;
  arquivo de catálogo usado no `prepare` tem validade máxima de 30 minutos.
- Exclusividade tem fronteira controlável: evidência do operador com `consumer_id`, lock
  operacional existente, sessão/endpoint conferidos e validade temporal. `registry_only`,
  hash estático e flag local **não** provam exclusividade. Ausência global de processos
  externos depende de integração futura e permanece como limitação explícita; nenhum ledger
  foi duplicado.
- O teste de contrato simula somente a fronteira de I/O (`HttpClient`); nenhum método
  interno (`_validate`, `fresh_evidence`, `status`, `prepare`, `transmit`,
  `_validate_prepared_request`) é substituído.

### P3B.5 — fechamento preparação→aprovação→execução e probe de permissões

- `PilotExecutor` reconstrói o manifesto em runtime com **todas** as evidências do manifesto
  aprovado (`permission_policy_sha256`, `admission_basis`, `inbox_evidence`,
  `repository_source`, `admission_authorized`, `exclusive_consumer`). Proposta preparada e
  reconstrução produzem o mesmo `canonical_sha256`; alteração material invalida a aprovação.
- Nenhum default permissivo habilita transmissão real: evidência ausente/divergente, admissão
  não autorizada externamente ou consumidor não exclusivo bloqueiam antes do POST. A
  revalidação ocorre no início de `execute()` e novamente antes de `transmit()`.
- `tokens`/`cost` precisam ser observados explicitamente como zero; campos ausentes não são
  convertidos em zero. `FRESH_UNEXECUTED` é estado descritivo; admissão operacional exige
  autorização humana externa — proposta não autorizada pode ser elaborada, execução não.
- Cadastro histórico sem `permission_policy_sha256`/`repository_source`/`admission_basis`/
  `inbox_evidence` é reportado como lacuna; `rebind-check` não preenche silenciosamente e
  exige `--replace` + autorização para atualizar somente campos efêmeros da **mesma** sessão.

**Probe futuro de permissões (não executado):** uma instância privada autenticada, mesmo banco
e Session ID, política original com hash verificado, endpoint
`POST /api/session/{id}/permission` com `{action, resources}` — uma ação por requisição, sem
`save`, sem prompt, sem LLM, sem execução de ferramenta, sem nova sessão e sem transmissão.
Matriz fechada e inofensiva: `shell/git status`, `edit`, `write`, `read`, `external_directory`,
`subagent`, `execute` → esperado `deny`; `question` → esperado `allow` (question é tool, não
"zero tools"). `ask` pendente é tratado por `POST .../permission/{requestID}/reply` com
`reject`, registrado e contabilizado. Limite: número de casos + 2; parar diante de resultado
inesperado. `deny` do avaliador **não** comprova enforcement exaustivo de plugins/snapshot.
Comando offline: `coord_pilot_cli.py permission-probe-plan`. Ledger PR #70, política PR #71 e
autorização/revogação PR #72 permanecem com seus respectivos agentes.

### Adendo P10 — contrato unificado e higiene

- `OpenCodeTransport.status()` e a preparação do manifesto usam a mesma classificação:
  **`FRESH_UNEXECUTED`**, **`IDLE_TERMINAL`**, **`BUSY`**, **`UNKNOWN`**. Sessão presente em
  `active` ou `busy>idle` ⇒ `BUSY`; `outcome` fora do enum documentado ⇒ `UNKNOWN`. A primeira
  transmissão só admite `FRESH_UNEXECUTED` com evidências cumulativas (active observado sem a
  sessão, inbox/mensagens/permissões pendentes observados e vazios, `created==updated`,
  tokens/custo zero, sem `outcome`/`busy`/`idle`). Nunca declarar `idle` por `time.idle>=time.busy`.
- Respostas 200 sem `data`, `inbox_count` ausente, inbox ocupado, `active` sem `data` e
  `busy>idle` são bloqueios explícitos — ausência de evidência não é resposta vazia.
- Vínculo Git independente inclui **canonical + worktree + common-dir + origin + projectID**;
  `repositoryID` continua ausente na API e não é fabricado. `repository_source=git-local`.
- Catálogo do modelo deve ser verificado antes da futura transmissão (`--catalog`): provider
  e ID exatos, variante autorizada `none`, custos conhecidos/compatíveis e disponibilidade.
  Custo desconhecido ou divergente bloqueia; sem seleção dinâmica/fallback (PR #71 cuida da
  política geral).
- `GET /api/session/{id}/permission` vazio não prova enforcement: separar regras solicitadas,
  devolvidas, pendentes, política efetiva e enforcement comprovado. Manter bloqueio sem
  evidência de runtime.
- Fixtures Hungerg em `test_pilot_executor.py`/`test_pilot_closure.py` estão rotuladas como
  históricas/sintéticas e não exercitam a política HY4. Bloqueios duplicados são deduplicados.
  A evidência de inbox vazio veio da leitura separada `verify-inbox.json`, não do preflight.

## Diretriz de produto do mantenedor — FREE FIRST / 1M PREFERRED

Registrada para COORD-05/06/07: preferir modelos **gratuitos** com janela de contexto de
aproximadamente **1 milhão de tokens ou superior**. Quando limites gratuitos são atingidos,
o mantenedor atualmente troca manualmente para outro modelo gratuito disponível. A futura
coordenação deverá automatizar descoberta dinâmica de modelos gratuitos, preferência por
contexto ≥1M, rotação diante de indisponibilidade ou limite atingido, preservação de contexto
e evidências, ausência de reenvio cego e proteção contra gastos não autorizados.
**Não implementar rotação automática nesta missão**: o piloto permanece restrito a um modelo
exato (`opencode/hy4-flash-free`).

## Reutilização futura

O núcleo permanece genérico: política, identidade, missão e transporte são injetados.
HealthAgentForms não foi acessado; qualquer reutilização futura exigirá nova política de
projeto, autorização e adaptação de destino, sem escolha pelo modelo.
