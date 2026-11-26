# COORD-04/P3A.1 — piloto real controlado (preparação, sem execução)

Este documento prepara o primeiro piloto real. **Nenhuma transmissão, POST, sessão,
prompt, serviço ou aprovação real foi operado na P3A/P3A.1.** A ativação depende de P3B,
autorização explícita do mantenedor e recertificação independente.

## Destino experimental proposto

| Campo | Valor proposto |
| --- | --- |
| `project_id` | `better-talker` |
| `repository_id` | `guilhermemellojw/better-talker` |
| `agent_id` | `A` (pressuposição — confirmar por metadados em P3B) |
| `session_id` | `ses_defc05853ffeej4fPXrp7frx4x` |
| `instance_id` | a recalcular no preflight de P3B a partir de `GET /api/info` |
| `location` | `/home/guilherme/Documentos/better-talker-coord-supervisor-shadow` |
| `modelo` | `opencode-go/hungerg-free#default` (confirmar em P3B) |

A sessão experimental acima é a que recebeu os testes das missões anteriores. A outra sessão
experimental permanece preservada e não é destino deste piloto. **Não presumir que
`agent_id="A"` corresponde ao agente efetivamente configurado.** Em P3B, confirmar todos os
metadados autorizados; se qualquer campo divergir, interromper o piloto. A sessão não deve
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
  "target_session_id": "ses_defc05853ffeej4fPXrp7frx4x",
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

O modelo selecionado deve permanecer o gratuito já existente, preferencialmente Hungerg Free.
Nenhuma troca automática de modelo é autorizada.

## Autorização necessária (proposta, não aprovada)

```json
{
  "authorization_id": "AUTH-COORD04-PILOT-001",
  "maintainer_id": "<mantenedor>",
  "approval_reference": "<referência da decisão registrada>",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "instance_id": "<preenchido no preflight P3B>",
  "target": ["better-talker", "guilhermemellojw/better-talker", "A", "ses_defc05853ffeej4fPXrp7frx4x", "<instance_id>", "/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"],
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
  "expected_model": "opencode-go/hungerg-free#default",
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
 projeto, location, sessão, agente, modelo, estado da sessão e inbox. `agent_id=A` é
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
   agente efetivo, modelo gratuito), sem presumir agent_id=A.
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

O ID fixo `ses_defc05853ffeej4fPXrp7frx4x` existe somente em fixtures. O caminho real exige
destino previamente cadastrado e autorizado (cadastro local, sem criar sessão real):

```sh
python3 .opencode/scripts/coord_pilot_cli.py session-register \
  --store-dir ~/.local/state/better-talker/coord-pilot/real \
  --session <ses-efetiva> --endpoint <endpoint> --instance-id <instancia> \
  --agent-id <agente-observado> --authorization-reference <decisao> \
  --auth-env COORD_PILOT_AUTH
python3 .opencode/scripts/coord_pilot_cli.py prepare --real \
  --endpoint <endpoint> --session <ses-efetiva> --auth-env COORD_PILOT_AUTH
```

`project.id` do OpenCode é um ID interno (hash), distinto da identidade lógica `better-talker`,
do repositório GitHub (lido de `git remote get-url origin` e validado pela política), do
diretório canônico e da worktree experimental. `prepare --real` usa relógio UTC corrente,
validade curta e bloqueia sem destinatário, metadados ou validade vigente. Nenhuma
`PilotApproval` é emitida pela CLI; nenhuma sessão é criada nesta rodada.

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

## Reutilização futura

O núcleo permanece genérico: política, identidade, missão e transporte são injetados.
HealthAgentForms não foi acessado; qualquer reutilização futura exigirá nova política de
projeto, autorização e adaptação de destino, sem escolha pelo modelo.
