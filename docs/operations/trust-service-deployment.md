# Implantação controlada — COORD trust boundary service

**Status:** procedimento preparado, **não executado** nesta rodada. Não crie
usuários, instale unidades, use sudo ou inicie serviços reais como parte do PR.
Qualquer execução exige janela autorizada, backup, revisão humana e rollback.

## 1. Pré-requisitos e segregação

| Plano | UID/GID sugerido | Acesso |
|---|---|---|
| Serviço | `bt-coord-trust` / `bt-coord-trust` | dono do estado, ledger, trust store, socket |
| Consumidor/executor | `bt-coord-agent` / `bt-coord-trust` | apenas socket (grupo), nenhum path de estado |
| Humano/signer | conta/hardware fora do host do agente | chave privada nunca no host do agente |
| Operador | conta administrativa separada | instala/backup/rotação, não assina automaticamente |

O consumidor **não** pode compartilhar UID com o serviço
(`allow_same_uid=false`). O grupo comum existe apenas para o socket 0660; o
estado permanece 0700 e não é acessível pelo consumidor.

## 2. Diretórios e permissões

Exemplo de preparação (executar somente na janela autorizada):

```bash
install -d -m 0700 -o bt-coord-trust -g bt-coord-trust /var/lib/better-talker-coord
install -d -m 0750 -o bt-coord-trust -g bt-coord-trust /run/better-talker-coord
install -d -m 0750 -o root -g bt-coord-trust /etc/better-talker
install -m 0600 -o bt-coord-trust -g bt-coord-trust /dev/null /var/lib/better-talker-coord/trust-store.json
install -m 0600 -o bt-coord-trust -g bt-coord-trust /dev/null /var/lib/better-talker-coord/consumers.json
install -m 0640 -o root -g bt-coord-trust /dev/null /etc/better-talker/trust-service.json
```

O comando acima é ilustrativo; não foi executado. O `INSTALL` de `/dev/null`
deve ser substituído por conteúdo revisado; use `install -T`/editor seguro.

- `/var/lib/better-talker-coord`: 0700, dono do serviço; ledger, service state,
  trust store, consumer registry.
- `/run/better-talker-coord`: 0750, grupo do serviço; socket 0660.
- `/etc/better-talker/trust-service.json`: configuração do serviço, 0640
  root:grupo do serviço (somente leitura pelo serviço; não contém chave privada).
- Nenhum arquivo contém chave privada.
- `state_dir` não pode ser symlink, world-readable ou group-writable.

## 3. Trust store e consumer registry

O trust store contém somente chaves **públicas** de autoridade, fingerprints e
janelas. Use `.opencode/deploy/trust-store.example.json` como forma; substitua
os placeholders por material revisado fora do agente.

O consumer registry contém `consumer_id`, UID, SHA-256 do executável permitido
e ações mínimas. Exemplo: `executor` recebe `reserve`, `assert`,
`record_evidence`, `reconcile`, `interrupt`, `status`; `observer` recebe apenas
`status`; `maintainer` recebe `revoke`/`authorize` se necessário. Não conceda
`propose`, `authorize`, `reserve` e `revoke` ao mesmo consumidor sem revisão.

Calcule o SHA-256 do executável fora do agente:

```bash
sha256sum /opt/better-talker/bin/coord-executor
```

## 4. Configuração do serviço

Use `.opencode/deploy/trust-service.example.json`. Campos:

```json
{
  "schema": "BT-COORD-TRUST-SERVICE-CONFIG-V1",
  "state_dir": "/var/lib/better-talker-coord",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "service_uid": 0,
  "service_gid": 0,
  "trust_store_path": "/var/lib/better-talker-coord/trust-store.json",
  "consumer_registry_path": "/var/lib/better-talker-coord/consumers.json",
  "socket_path": "/run/better-talker-coord/service.sock",
  "allow_same_uid": false,
  "real_transport": false,
  "max_frame_bytes": 1048576
}
```

Substitua `service_uid`/`service_gid` pelos IDs reais do serviço; o processo
deve rodar com exatamente esses IDs. `real_transport` deve permanecer `false`;
o serviço recusa iniciar com `true`. O socket fica em runtime 0750, nunca em
`/tmp` compartilhado.

### Health check (sem iniciar o serviço)

```bash
python3 /opt/better-talker/.opencode/scripts/coord_trust_service_cli.py \
  --config /etc/better-talker/trust-service.json --check
# CONFIG-OK real_transport=false
```

O check valida configuração, permissões, consumer registry e trust store; não
abre socket, não cria ledger e não contata rede.

## 5. Unidade systemd (preparação, não instalar agora)

`./.opencode/deploy/coord-trust-service.service` contém a unidade de exemplo.
Ela fixa:

- `User=bt-coord-trust`, `Group=bt-coord-trust`;
- `NoNewPrivileges=yes`, `CapabilityBoundingSet=`, `AmbientCapabilities=`;
- `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`;
- `PrivateNetwork=yes`, `RestrictAddressFamilies=AF_UNIX`;
- `RuntimeDirectory=better-talker-coord` 0750;
- `StateDirectory=better-talker-coord` 0700;
- `ReadWritePaths` somente estado/runtime;
- `UMask=0077`, `LockPersonality=yes`, `MemoryDenyWriteExecute=yes`.

Instalação (somente após autorização):

```bash
sudo install -m 0644 /opt/better-talker/.opencode/deploy/coord-trust-service.service \
  /etc/systemd/system/coord-trust-service.service
sudo systemctl daemon-reload
sudo systemctl enable --now coord-trust-service.service
```

Não execute esses comandos nesta rodada; sem merge, sem sudo.

## 6. Operação e rollback

1. **Antes:** backup consistente do ledger/service state, trust store e consumer
   registry; registrar hashes; confirmar UID/GID e permissões.
2. **Health check:** `--check`; confirmar `real_transport=false`.
3. **Início:** socket 0660, grupo correto; consumidor não lê o estado.
4. **Verificação:** conectar com consumidor autorizado; `status`; tentar
   `read_store`/`transmit` e confirmar rejeição; testar replay e lease errado.
5. **Interrupção:** `SIGTERM` para parar o serviço; conexões em voo são
   fechadas. Reconciliação/UNKNOWN ficam no ledger; não reenviar.
6. **Revogação:** aplicar declaração assinada via ação `revoke`; reiniciar
   apenas se trust store/consumer registry mudarem.
7. **Rollback:** parar serviço, restaurar backup e permissões, reiniciar com o
   mesmo `state_dir`; não editar SQLite manualmente.
8. **Rotação:** pública primeiro, janela de sobreposição, revogação explícita;
   nunca copiar chave privada para o host do agente.

## 7. Critérios de aceite da implantação

- Serviço e consumidor em UIDs distintos; `allow_same_uid=false`.
- Ledger/trust store/consumer registry 0600 e inacessíveis ao consumidor.
- Socket acessível somente pelo grupo; nenhum TCP/HTTP.
- `real_transport=false` e ação `transmit` recusada.
- `SO_PEERCRED` + exe hash resolvem o consumidor; identidade autodeclarada não
  é aceita.
- Ações permitidas por consumidor; replay/lease/relatório/revogação testados.
- Logs sanitizados, sem paths de estado/chaves/credenciais.
- Nenhuma migração, POST, LLM, credencial, usuário ou serviço real sem janela
  humana específica.

## 8. Rollback de segurança

Se houver suspeita de comprometimento do serviço, do socket ou do trust store:

1. parar o serviço;
2. revogar todas as autorizações e chaves afetadas fora do agente;
3. preservar ledger/logs para auditoria;
4. restaurar trust store/consumer registry revisados;
5. reiniciar com UID/GID e permissões verificados;
6. não reenviar missões UNKNOWN; reconciliar com evidência autenticada.

Este procedimento é uma preparação documental; nenhuma dessas ações foi
executada nesta rodada.
