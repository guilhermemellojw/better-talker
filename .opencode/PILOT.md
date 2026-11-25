# COORD-04/P3A — piloto real controlado (preparação, sem execução)

Este documento prepara o primeiro piloto real. **Nenhuma transmissão, POST, sessão,
prompt ou serviço foi operado na P3A.** A ativação depende de P3B, autorização explícita
do mantenedor e recertificação independente.

## Destino experimental proposto

| Campo | Valor proposto |
| --- | --- |
| `project_id` | `better-talker` |
| `repository_id` | `guilhermemellojw/better-talker` |
| `agent_id` | `A` |
| `session_id` | `ses_defc05853ffeej4fPXrp7frx4x` |
| `instance_id` | a ser recalculado no preflight de P3B a partir de `GET /api/info` |
| `location` | `/home/guilherme/Documentos/better-talker-coord-supervisor-shadow` |

A sessão experimental acima é a que recebeu os testes das missões anteriores. A outra sessão
experimental permanece preservada e não é destino deste piloto. Em P3B, confirmar metadados
antes de qualquer preparação; se divergirem, parar.

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
    "prompt": "MISS-COORD04-PILOT-001 / OP-COORD04-PILOT-001. Responda somente em texto estruturado, sem ferramentas, sem edição, sem shell, sem Git, sem ADB e sem acesso a credenciais. Informe: mission_id, operation_id, status e uma frase de confirmação. Termine com <!-- BT-COORD-REPORT:v1 -->."
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
  "canonical_sha256": "<calculado após aprovação>"
}
```

O supervisor não pode emitir essa autorização. O adaptador rejeita `approved=false`,
`environment=PROPOSED`, autorização sintética e qualquer alteração dos 11 campos materiais.
A autorização real será criada somente na P3B, se aprovada.

## Arquitetura inativa

- `scripts/coord_pilot.py`: `OpenCodeTransport`, `PilotAuthorization`, `PilotOperationStore`,
  reserva exclusiva local e recuperação `DISPATCHING → UNKNOWN`.
- `LocalHttpClient`: restrito a `http://127.0.0.1:<porta>`, `/api/`, sem redirecionamento;
  POST exige `allow_write=True` explícito e autorização aprovada.
- `DisabledHttpClient`: padrão seguro, falha fechado.
- Persistência: `~/.local/state/better-talker/coord-pilot/`, diretório `0700`, arquivo
  `0600`, escrita atômica, sem segredos.
- Nenhum daemon, lock distribuído, banco externo, ledger completo ou orquestrador.

## Runbook proposto

### Somente leitura (pode ser repetido)

```sh
opencode service status
opencode api get /api/info
opencode api get "/api/location?location[directory]=/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x
opencode api get /api/session/active
opencode api get /api/session/ses_defc05853ffeej4fPXrp7frx4x/inbox
```

### Preparação e dry-run (sem POST)

```sh
python3 .opencode/scripts/test_pilot_adapter.py
python3 .opencode/scripts/coord_checkpoint.py --fixture --json
```

A preparação real em P3B deve calcular o instance ID, o hash canônico da missão e o hash do
prompt, criar o registro persistente em `AUTHORIZED` e adquirir a reserva exclusiva antes de
qualquer POST. Nenhum desses atos foi executado na P3A.

### Escrita — somente P3B, após autorização explícita

```text
1. Confirmar autorização aprovada, não expirada, com max_transmissions=1.
2. Confirmar preflight e destinatário exato.
3. Criar registro persistente antes da transmissão.
4. Adquirir reserva exclusiva local.
5. Executar no máximo um POST /api/session/{sessionID}/prompt com id vinculado ao operation_id.
6. Registrar state=DISPATCHING somente após evidência de aceitação.
7. Consultar inbox, mensagens e active para distinguir enfileirado, registrado e executando.
8. Recuperar somente relatório com operation_id autorizado.
9. Associar relatório à missão; não certificar código automaticamente.
10. Se resultado for UNKNOWN: não reenviar; consultar estado; exigir evidência ou decisão humana.
11. Encerrar liberando a reserva e registrando o resultado.
```

Os comandos de escrita não estão listados como comandos executáveis nesta rodada porque a
ativação depende de uma autorização real ainda inexistente e de recertificação independente.

## Encerramento seguro

- `DISPATCHING` após interrupção vira `UNKNOWN`.
- `UNKNOWN` bloqueia reenvio automático e exige reconciliação por evidência ou decisão humana.
- Relatório divergente não substitui resultado terminal.
- Aceitação HTTP não prova leitura pelo agente; `session/active` ausente não prova disponibilidade.
- Nenhuma garantia de exactly-once, entrega durável ou ordem global é afirmada.

## Reutilização futura

O núcleo permanece genérico: política, identidade, missão e transporte são injetados.
HealthAgentForms não foi acessado; qualquer reutilização futura exigirá nova política de
projeto, autorização e adaptação de destino, sem escolha pelo modelo.
