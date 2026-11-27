# Implantação controlada — COORD trust boundary service

**Status:** procedimento preparado, **não executado** nesta rodada. Não crie
usuários, instale unidades, use sudo ou inicie serviços reais como parte do PR.
Qualquer execução exige janela autorizada, backup, revisão humana e rollback.

## 1. Contrato único de segregação

| Plano | UID/GID | Acesso |
|---|---|---|
| Serviço | `bt-coord-trust` / `bt-coord-trust` (ex.: 9001) | dono do estado, ledger, trust store, socket, âncora |
| Executor | `bt-coord-agent` / grupo `bt-coord-trust` (ex.: 9002) | somente socket; nenhum path de estado |
| Observer | `bt-coord-observer` (ex.: 9003) | somente socket + `status` |
| Coordenador | UID próprio (ex.: 9004) | somente ações mínimas de coordenação |
| Humano/signer | conta/hardware fora do host do agente | chave privada nunca no host do agente |
| Operador | conta administrativa separada | instala/backup/rotação, não assina automaticamente |

Cada consumidor tem **UID exclusivo**. Nenhum consumidor compartilha UID com o
serviço (`allow_same_uid=false`) nem com outro consumidor. UID duplicado,
desconhecido ou igual ao do serviço é recusado. O grupo comum existe apenas para
o socket 0660; o estado permanece 0700.

O UID identifica o principal, **não** o script/argv/interpretador. Processos do
mesmo UID pertencem à mesma fronteira e não são distinguíveis. Hash de
executável, quando registrado, é apenas inventário complementar — não é
autoridade. Não conceda o mesmo UID a agentes não equivalentes.

## 2. Diretórios, permissões e âncora

Exemplo de preparação (executar somente na janela autorizada):

```bash
install -d -m 0700 -o bt-coord-trust -g bt-coord-trust /var/lib/better-talker-coord
install -d -m 0700 -o bt-coord-trust -g bt-coord-trust /var/lib/better-talker-coord-anchor
install -d -m 0750 -o bt-coord-trust -g bt-coord-trust /run/better-talker-coord
install -d -m 0750 -o root -g bt-coord-trust /etc/better-talker
```

Não use `install ... /dev/null <arquivo-existente>`: isso trunca trust store,
consumer registry ou configuração. Prepare conteúdo revisado em arquivo
temporário no diretório de destino, valide com `--check`, e só então faça
`os.replace`/`mv` atômico com owner/modo corretos. Preserve o arquivo anterior
como `.bak` datado durante a janela de manutenção. O procedimento
abaixo é ilustrativo e não foi executado.

```bash
umask 077
# Exemplo de staging; NÃO sobrescreva o arquivo vivo diretamente.
staging=/var/lib/better-talker-coord/.trust-store.json.new
# edite/valide o conteúdo antes do mv atômico:
chown bt-coord-trust:bt-coord-trust "$staging"
chmod 0600 "$staging"
mv --no-clobber "$staging" /var/lib/better-talker-coord/trust-store.json
```

- `/var/lib/better-talker-coord`: 0700, dono do serviço; ledger, service state,
  trust store, consumer registry e sidecars SQLite.
- `/var/lib/better-talker-coord-anchor`: 0700, **fora** do backup do ledger;
  âncora monotônica/epoch/quarentena.
- `/run/better-talker-coord`: 0750, grupo do serviço; socket 0660.
- `/etc/better-talker/trust-service.json`: 0640 root:grupo, sem chave privada.
- Ledger/service state/trust store/registry/sidecars `-wal`, `-shm`,
  `-journal`: 0600, regular, sem hardlink/symlink. O runtime valida ancestrais
  relevantes e recusa socket regular, symlink ou de outro dono.

## 3. Trust store e consumer registry

O trust store contém somente chaves **públicas**, fingerprints e janelas. Use
`.opencode/deploy/trust-store.example.json`; substitua placeholders por material
revisado fora do agente.

O consumer registry contém `consumer_id`, UID e ações mínimas. **Não** exige
hash de executável; se o campo for usado, é complementar. Exemplo:
`executor` recebe `reserve`, `assert`, `record_evidence`, `reconcile`,
`interrupt`, `status`, `recover`; `observer` recebe apenas `status`;
`maintainer` recebe `revoke`/`recover`. Não conceda `propose`, `authorize`,
`reserve` e `revoke` ao mesmo consumidor sem revisão.

## 4. Configuração do serviço

Use `.opencode/deploy/trust-service.example.json`. Campos relevantes:

```json
{
  "schema": "BT-COORD-TRUST-SERVICE-CONFIG-V1",
  "state_dir": "/var/lib/better-talker-coord",
  "project_id": "better-talker",
  "repository_id": "guilhermemellojw/better-talker",
  "service_uid": 9001,
  "service_gid": 9001,
  "trust_store_path": "/var/lib/better-talker-coord/trust-store.json",
  "consumer_registry_path": "/var/lib/better-talker-coord/consumers.json",
  "socket_path": "/run/better-talker-coord/service.sock",
  "anchor_path": "/var/lib/better-talker-coord-anchor/anchor.json",
  "allow_same_uid": false,
  "test_mode": false,
  "real_transport": false,
  "max_frame_bytes": 1048576,
  "request_timeout_seconds": 15,
  "max_workers": 4
}
```

`service_uid`/`service_gid` devem ser os IDs reais do serviço; o processo roda
com exatamente esses IDs (sem root). `real_transport` deve permanecer `false`.
`test_mode=true` é proibido em produção e só existe para a suíte host-only.
`anchor_path` deve ficar fora do `state_dir` e do backup do ledger.

### Health check (sem iniciar o serviço)

```bash
python3 /opt/better-talker/.opencode/scripts/coord_trust_service_cli.py \
  --config /etc/better-talker/trust-service.json --check
# CONFIG-OK real_transport=false
```

O check valida configuração, permissões, consumer registry, trust store e
âncora; não abre socket, não cria ledger e não contata rede.

## 5. Unidade systemd (preparação, não instalar agora)

`./.opencode/deploy/coord-trust-service.service` contém a unidade de exemplo:

- `User=bt-coord-trust`, `Group=bt-coord-trust`, sem root;
- `NoNewPrivileges=yes`, `CapabilityBoundingSet=`, `AmbientCapabilities=`;
- `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`;
- `PrivateNetwork=yes`, `RestrictAddressFamilies=AF_UNIX`;
- `RuntimeDirectory=better-talker-coord` 0750;
- `StateDirectory=better-talker-coord` 0700 e
  `StateDirectory=better-talker-coord-anchor` 0700 (ver arquivo);
- `ReadWritePaths` estado/runtime/âncora; `UMask=0077`, `LockPersonality=yes`,
  `MemoryDenyWriteExecute=yes`.

### Sandbox do consumidor (drop-in separado)

O consumidor deve rodar em unidade própria, UID próprio, sem acesso ao
`state_dir`, ledger, trust store ou chave privada. Exemplo de drop-in:

```ini
[Service]
User=bt-coord-agent
Group=bt-coord-trust
SupplementaryGroups=bt-coord-trust
NoNewPrivileges=yes
CapabilityBoundingSet=
PrivateNetwork=yes
RestrictAddressFamilies=AF_UNIX
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
InaccessiblePaths=/var/lib/better-talker-coord /var/lib/better-talker-coord-anchor
ReadOnlyPaths=/run/better-talker-coord/service.sock
UMask=0077
```

O consumidor recebe apenas o socket; não recebe paths de estado, ledger,
trust store, chave privada ou âncora.

## 6. Operação, recuperação e rollback

1. **Antes:** backup consistente do ledger/service state, trust store e
   consumer registry; registrar hashes; confirmar UID/GID/permissões. A âncora
   **não** deve ser restaurada junto com o ledger: ela é a referência
   forward-only externa ao conjunto de backup. Faça backup separado/append-only
   da âncora apenas para auditoria, nunca para “voltar” a epoch.
2. **Health check:** `--check`; confirmar `real_transport=false`.
3. **Início:** socket 0660, grupo correto; consumidor não lê o estado.
4. **Verificação:** `status`; tentar `read_store`/`transmit` e confirmar
   rejeição; testar replay, lease errado, expiração e revogação.
5. **Interrupção:** `SIGTERM`; conexões em voo fecham; UNKNOWN permanece no
   ledger, sem reenvio.
6. **Revogação:** declaração assinada via `revoke`; reiniciar apenas se trust
   store/consumer registry mudarem.
7. **Rollback:** parar, restaurar backup e permissões, reiniciar com o mesmo
   `state_dir` e uma âncora **mais nova/independente**. Se o ledger/service
   state atrasar, o serviço entra em **quarentena**; não edite SQLite
   manualmente. Não restaurar silenciosamente estado anterior ao consumo:
   autorizações/leases antigos não podem voltar a valer. A recuperação exige
   declaração assinada por autoridade externa, nova epoch e revisão dos
   consumidores; preserve UNKNOWN/evidências/revogações.
8. **Rotação:** pública primeiro, sobreposição, revogação explícita; nunca
   copiar chave privada para o host do agente.

## 7. Teste cross-UID descartável

**Estado local: BLOCKED** — o host desta auditoria não permite criar/menorizar
um segundo UID sem privilégio; usuários reais não foram criados.

O CI prepara um probe descartável que executa um cliente como UID 65534
(`nobody`, sem criar usuário) contra socket `AF_UNIX`/`SOCK_SEQPACKET` 0666 em
diretório temporário e exige `SCM_CREDENTIALS` com UID/GID distintos:

```bash
python3 .opencode/scripts/coord_trust_cross_uid_probe.py
# CROSS-UID-SCM-OK uid=65534 gid=65534
# ou CROSS-UID-BLOCKED ... (quando sudo/setpriv não está disponível)
```

Procedimento de certificação (VM/CI descartável, nunca o host real):

1. criar dois UIDs efêmeros: serviço e executor, sem root, com grupo comum
   apenas para o socket;
2. preparar state 0700 do serviço, runtime 0750 do grupo, socket 0660, âncora
   0700 fora do state;
3. iniciar o serviço como UID do serviço; confirmar `id` e ausência de
   capabilities (`capsh --print` ou `/proc/self/status`);
4. executar o cliente como UID do executor; confirmar `status` autorizado e
   `propose`/`authorize` negados conforme registry;
5. tentar ler ledger/trust store/âncora como executor: deve falhar;
6. abrir socket com cliente de UID não registrado: deve fechar sem welcome;
7. transferir o fd para outro UID (SCM_RIGHTS/processo auxiliar): a credencial
   da mensagem deve divergir e o serviço deve recusar;
8. repetir `assert` após revogação/expiração/UNKNOWN/terminal: deve retornar
   `REPLAY_STALE`, nunca lease histórico;
9. registrar `id`, permissões, hashes e logs sanitizados no PR.

Sem os passos 1–3, o modo `allow_same_uid=true` é apenas laboratório e não
certifica a fronteira de produção.

O probe `coord_trust_cross_uid_probe.py` executa apenas a prova de kernel
`SCM_CREDENTIALS` cross-UID em ambiente descartável com `sudo -n setpriv`; ele
**não** é executado no host desta rodada e não substitui os passos 1–9.

## 8. Critérios de aceite da implantação

- Serviço e cada consumidor em UID distinto; `allow_same_uid=false` e
  `test_mode=false`.
- Ledger/trust store/consumer registry/âncora 0600, inacessíveis ao consumidor;
  hardlinks/symlinks/sidecars validados.
- Socket 0660 em runtime 0750; nenhum TCP/HTTP; servidor autenticado pelo
  cliente por UID/GID/dono/service_id.
- `real_transport=false` e ação `transmit` recusada.
- Identidade por `SO_PEERCRED` + `SCM_CREDENTIALS` por frame; UID autodeclarado,
  credencial ausente/truncada/divergente ou `SCM_RIGHTS` recusados.
- Ações permitidas por consumidor; replay histórico não vira autorização atual;
  lease/relatório/revogação/expiração testados.
- Âncora forward-only **fora** do backup do ledger; ledger atrasado/quarentena
  exige recuperação assinada; nunca restaurar ledger antigo silenciosamente.
- Logs sanitizados, sem paths de estado/chaves/credenciais.
- Nenhuma migração, POST, LLM, credencial, usuário ou serviço real sem janela
  humana específica.

## 9. Rollback de segurança

Se houver suspeita de comprometimento do serviço, socket ou trust store:

1. parar o serviço;
2. revogar autorizações/chaves afetadas fora do agente;
3. preservar ledger/logs/âncora para auditoria;
4. restaurar trust store/consumer registry revisados;
5. reiniciar com UID/GID/permissões verificados e âncora forward-only;
6. não reenviar UNKNOWN; reconciliar com evidência autenticada.

Este procedimento é preparação documental; nenhuma dessas ações foi executada
nesta rodada.
