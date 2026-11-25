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

### 1. Preflight somente leitura

```sh
python3 .opencode/scripts/coord_pilot_cli.py preflight
python3 .opencode/scripts/coord_pilot_cli.py reconcile
opencode service status
opencode api get /api/info
opencode api get "/api/location?location[directory]=/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x
opencode api get /api/session/active
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x/inbox
```

Confirmar instância, projeto, location, sessão, agente, modelo, disponibilidade e inbox.
Ausência de `busy` e ausência em `session/active` **não** provam disponibilidade.

### 2. Dry-run offline

```sh
python3 .opencode/scripts/coord_pilot_cli.py dry-run
python3 .opencode/scripts/test_pilot_executor.py
python3 .opencode/scripts/test_pilot_adapter.py
python3 .opencode/scripts/test_supervisor.py
python3 .opencode/scripts/test_communication.py
python3 .opencode/scripts/demo_communication.py
```

### 3. Preparação do manifesto

O mantenedor revisa o HEAD e produz a `PilotApproval` com o `manifest_sha256` exato. A CLI
`prepare` apenas confirma a presença do registro; ela não cria aprovação nem autorização:

```sh
python3 .opencode/scripts/coord_pilot_cli.py prepare
```

### 4. Execução experimental — bloqueada por padrão

```sh
python3 .opencode/scripts/coord_pilot_cli.py execute
# -> {"executed": false, "state": "BLOCKED_BY_DEFAULT", "http_calls": 0}
```

Nenhum POST real é emitido por este comando. A ativação futura exige decisão separada,
autorização `APPROVED`, transporte real e recertificação independente.

### 5–10. Escrita — somente P3B, após autorização explícita

```text
1. Confirmar aprovação humana do manifesto exato e autorização APPROVED não expirada.
2. Confirmar preflight e destinatário exato (instância, projeto, location, sessão, agente, modelo).
3. Criar registro persistente em AUTHORIZED ANTES de qualquer transmissão.
4. Adquirir reserva exclusiva local.
5. Persistir DISPATCHING e confirmar a escrita ANTES do POST.
6. Revalidar autorização; executar no máximo um POST /api/session/{sessionID}/prompt com
   id vinculado ao operation_id.
7. Persistir evidências e resultado; falha de disco antes do POST impede transmissão.
8. Consultar inbox, mensagens e active para distinguir enfileirado, registrado e executando.
9. Recuperar somente relatório com operation_id autorizado e envelopes observadas.
10. Se UNKNOWN: não reenviar; exigir evidência positiva ou decisão humana.
11. Encerrar liberando a reserva, sem alterar estados terminais.
```

Os comandos de escrita não estão listados como comandos executáveis nesta rodada porque a
ativação depende de autorização real ainda inexistente e de recertificação independente.

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
