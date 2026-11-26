# COORD-05A — Ledger persistente local de missões

Implementação independente do transporte OpenCode, do modelo de IA e do PR #64.
Nenhuma mensagem é enviada, nenhuma autorização é concedida e nenhum HealthAgentForms é acessado.

## Escolha de armazenamento

SQLite da biblioteca padrão Python (`sqlite3`). Justificativa:

- arquivo único, privado e local;
- transações ACID com `BEGIN IMMEDIATE` para reserva atômica;
- suficiente para histórico auditável e recuperação após reinício;
- sem dependências externas, rede ou serviço permanente.

Nenhuma credencial é lida ou gravada. O caminho do banco é injetado pelo chamador.

## Contrato

Identidades imutáveis: `mission_id`, `operation_id`, `identity_hash` (SHA-256 de todos
os campos materiais) e `payload_sha256` (SHA-256 do JSON canônico do payload,
exatamente o contrato COORD-04: `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`).

Registro por missão: agente, projeto, repositório, sessão, instância, worktree,
autorização, payload, hash, estado, timestamps e histórico de transições.

### Ciclo de vida

```text
PROPOSED → AUTHORIZED → DISPATCHING → DELIVERED → RUNNING → COMPLETED | FAILED
                     ↘ UNKNOWN  (delivered/running também podem → UNKNOWN)
UNKNOWN → DELIVERED | RUNNING | COMPLETED | FAILED
          somente por evidência ou decisão humana registrada
```

`COMPLETED` e `FAILED` são terminais. `UNKNOWN` bloqueia reenvio: não há transição
para `DISPATCHING` e `can_dispatch()` retorna falso.

## API mínima

```python
Ledger(db_path, project_id=..., repository_id=..., policy={...})
  .register_proposed(...)            # PROPOSED, payload + hash + identidade
  .authorize(...)                    # exige referência e hash compatíveis
  .reserve_dispatch(...)             # BEGIN IMMEDIATE; grava DISPATCHING antes do envio
  .mark_delivered(mid, evidence=...) # evidência obrigatória
  .mark_running(mid, evidence=...)
  .complete(mid, evidence=...)
  .fail(mid, evidence=...)
  .mark_unknown(mid, evidence=...)
  .reconcile(mid, target=..., evidence=..., human_decision=False)
  .recover_stale_dispatching(timeout_seconds=...)
  .get(mid) / .history(mid) / .can_dispatch(mid)
```

O ledger não chama `transmit()`, não observa sessões e não concede autorização.
`reserve_dispatch()` apenas persiste a intenção; o envio real é responsabilidade de
componente futuro, com autorização registrada.

## Garantias

- Duplicidade: `mission_id` e `operation_id` únicos; payload divergente é rejeitado.
- Concorrência: reserva por `(agent, session_id)` com chave primária e transação
  `BEGIN IMMEDIATE`; dois processos não reservam o mesmo destino.
- Recuperação: `DISPATCHING` sem desfecho vira `UNKNOWN`; reconciliação exige
  evidência ou decisão humana. Nunca reenvia automaticamente.
- Falha de armazenamento: transação revertida e `LedgerStorageError`.
- Política e projeto injetados; nenhum nome de agente ou regra de produto é embutido.

## Testes

```sh
python3 .opencode/scripts/test_coord_ledger.py
```

16 testes cobrem persistência, concorrência entre processos, reserva duplicada,
operação repetida, payload divergente, transição inválida, recuperação, falha de
armazenamento, `UNKNOWN`, estados terminais e reinício da instância.

## Fora de escopo

Transporte real,OpenCode, LLM, credenciais, HealthAgentForms, ASSISTED/DELEGATED,
integração com COORD-04, notificações e autorização automática.
