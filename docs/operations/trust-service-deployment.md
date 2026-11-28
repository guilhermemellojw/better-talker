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

## 7. Teste cross-UID descartável (P4)

**Estado local: BLOCKED** — o host desta rodada não permite criar/menorizar um
segundo UID sem privilégio; usuários reais não foram criados. O teste com dois
UIDs reais só roda em runner/VM descartável. O dispatch do candidato histórico
P4.2 `31a150330d2b9c8af420cdfac322fde73132dda8` falhou no run `46081657455`.
Após P4.3/P4.5, o dispatch do HEAD P4.5
`876bc654ce1ab1032f64e25405d89cb8601e55d3` falhou no run `46087440250`
(G1/G3/G4/G5/G7); a causa raiz OpenSSH/NSS e a correção P4.6 estão em
`docs/reports/2026-11-27-coord-trust-crossuid-p46-signed-authority.md`. Ambas
as autorizações foram consumidas e nenhuma nova execução está autorizada.

A certificação P4 é um job **exclusivamente opt-in** no workflow
`.github/workflows/coord-python.yml`:

- `workflow_dispatch` com input booleano `crossuid_full=true` e
  `certified_sha=<40-hex>`; o job só roda com `crossuid_full=true` e
  `run_attempt=1` (reexecução não executa o job);
- a suíte ordinária `coord-python` é omitida quando `crossuid_full=true`;
- `certified_sha` é recebido por `env`, sem interpolação direta no shell;
  formato 40-hex, `GITHUB_SHA` e `git rev-parse HEAD` precisam coincidir;
- checkout exato de `certified_sha`, com `persist-credentials: false`;
- etapa `if: always()` de limpeza remove explicitamente
  `$RUNNER_TEMP/crossuid-out`, `$RUNNER_TEMP/crossuid-home`,
  `$RUNNER_TEMP/crossuid-tmp` e o `WORK_ROOT` exato, sem wildcard; process
  groups vêm do manifesto e são validados pelo marcador `COORD_TRUST_RUN_ID`,
  nunca por nome de processo;
- `permissions: contents: read`, sem secrets de produção;
- `runs-on: ubuntu-latest`, `timeout-minutes: 10`;
- o harness roda como root **apenas** para preparar arquivos, spawnar UIDs
  numéricos e matar/reiniciar o serviço; serviço e consumidores rodam sem root,
  sem capabilities e com `no_new_privs`;
- somente a etapa de certificação cross-UID roda em `unshare -n` (sem rede),
  com guarda Python AF_UNIX adicional quando aplicável; checkout, setup,
  sanitização e limpeza permanecem no runner normal;
- chaves, ledger, trust store, relatórios e evidências são sintéticos;
- `real_transport=false` durante toda a execução; `transmit` deve retornar
  `TRANSPORT_DISABLED`.

UIDs numéricos efêmeros (sem `useradd`/`adduser`):

| Papel | UID:GID |
|---|---|
| serviço | `65533:65533` |
| executor | `65534:65533` (grupo comum apenas para o socket) |
| observer | `65531:65533` |
| não registrado/helper | `65532:65533` (UID divergente) |
| GID divergente | UID `65534`, GID `65532` |

Layout descartável: `state/` 0700 do serviço, `anchor/` 0700 **fora** do
`state/`, `run/` 0750 do grupo, socket 0660, chaves privadas sintéticas 0700
root, config/trust/registry/DBs 0600. O harness valida ancestrais, hardlinks,
symlinks, sidecars SQLite, autenticação do servidor e limites.

O checkout do runner pode ter ancestrais não atravessáveis pelo UID efêmero.
Antes de G1, o harness copia um **runtime bundle mínimo** para
`$WORK_ROOT/runtime` (fora do checkout/HOME/estado), com diretório 0555 root e
arquivos 0444 root; `PYTHONPATH` e paths de serviço/cliente apontam somente para
esse bundle. Manifesto SHA-256, importação real, scripts, ancestrais e negação
de state/trust/âncora/chaves são verificados sob os UIDs 65533/65534. O
checkout não é chmodado e nenhuma permissão de HOME/estado é ampliada.

G0 distingue `PermissionError` de `FileNotFoundError`: ausência de arquivo não
comprova isolamento. State, trust store, consumer registry, diretório da âncora
e chaves existentes devem estar legíveis para o serviço e retornar
`PermissionError` para o consumidor; `work_root`/`runtime` devem ser
`not-writable`. Ledger e arquivo de âncora, ainda inexistentes antes do start,
têm os diretórios pais verificados em G0 e a existência + negação efetiva
exigidas em G8. Falha obrigatória interrompe antes de G1.

O bundle vincula `source_head`/`certified_sha` e cada SHA-256 ao blob
`HEAD:.opencode/scripts/<name>` do SHA certificado (o workflow passa
`--certified-sha`). Após a limpeza, o manifesto bruto e `cleanup.json` são
removidos, mas `runtime_bundle` e `access_evidence` sanitizados permanecem em
`summary.json.sanitized`/log da execução.

O OpenSSH consulta `getpwuid` mesmo em fingerprint/verify; o UID 65533 não tem
entrada em `/etc/passwd` do host. O serviço é iniciado em um mount namespace
privado com `passwd`/`group` sintéticos (`bt-coord-trust:65533`), seguido de
`setpriv`; nada é alterado em `/etc` do host e nenhum usuário é criado. G0
executa fingerprint e `ssh-keygen -Y verify` sob 65533 com esse NSS efêmero;
falha do mount ou do OpenSSH interrompe antes de G1. O serviço permanece
65533:65533, sem root/capabilities.

Cobertura obrigatória do job:

1. **G1** — jornada cross-UID completa (`propose` → `authorize` → `reserve` →
   `assert` → `record_evidence` → `reconcile` → `status`), observer com
   least privilege, UID não registrado, autenticação do servidor por
   UID/GID/dono/grupo/`service_id`, campos de identidade autodeclarados
   recusados.
2. **G2** — transferência real de fd por `SCM_RIGHTS` entre UIDs; fd anexado a
   request recusado; socket encaminhado a outro UID tem credencial divergente
   recusada.
3. **G3** — rollback real de ledger/service state com quarentena, nova epoch
   assinada, lease/`request_id` antigos não reutilizados e nonce de recuperação
   persistido contra reutilização.
4. **G4** — `kill` real (`os._exit(137)`) nos limites antes/depois do commit
   do ledger, `finish_request` e selagem da âncora; restart, replay do WAL,
   `OUTCOME_UNKNOWN` antes do ledger e quarentena/sem liberação depois dele.
5. **G5** — recuperação assinada com testes negativos de assinatura, validade,
   digest da trust policy, aprovador e epoch obsoleta/repetida.
6. **G6** — divergência exclusiva de GID com UID correto: exige rejeição pelo
   serviço após mudança de GID na mesma conexão (`CAP_SETGID` ambiente). Se a
   capacidade não estiver disponível, o gate é **BLOCKED**; `EACCES` de socket
   é apenas informativo e nunca produz PASS.
7. **G7** — concorrência, saturação de slots, deadline total, frame acima de
   `max_frame_bytes` e validação de limites de configuração.
8. **G8** — sandbox efetivo do consumidor: UID/GID distintos, sem
   capabilities/`no_new_privs`, leitura de estado/chaves negada, rede bloqueada
   e sinal ao serviço negado.
9. **G9** — `real_transport=false`, `transmit` recusado, ausência de
   `AF_INET`/`create_connection` no serviço/crash service; no cliente,
   `create_connection` só é aceito como probe negativa com rede comprovadamente
   bloqueada no netns.

`setsid --wait` pertence somente à etapa de certificação abaixo; a etapa de
limpeza não usa `setsid`.

Comando previsto (não executado nesta rodada):

```bash
sudo -n setsid --wait unshare -n -- /usr/bin/env -i \
  HOME="$RUNNER_TEMP/crossuid-home" TMPDIR="$RUNNER_TEMP/crossuid-tmp" \
  PATH="$pythonLocation/bin:/usr/local/bin:/usr/bin:/bin" LANG=C LC_ALL=C \
  PYTHONPATH="$GITHUB_WORKSPACE/.opencode/scripts" \
  COORD_TRUST_RUN_ID="$RUN_ID" \
  python3 "$GITHUB_WORKSPACE/.opencode/scripts/coord_trust_crossuid_harness.py" \
    --repo "$GITHUB_WORKSPACE" --output "$RUNNER_TEMP/crossuid-out" \
    --run-id "$RUN_ID" --work-root "$WORK_ROOT" --deadline-seconds 480
```

Critérios de interrupção/falha fechada:

- qualquer gate G1–G9 falhar, o harness sair com código não zero ou o deadline
  interno de 8 min estourar;
- `sudo -n`, `setpriv`, `unshare`, `setsid` ou `ssh-keygen` indisponível;
  `--no-new-privs` ausente; `--ambient-caps` ausente torna G6 **BLOCKED**
  (EACCES de socket não é aceito como certificação);
- UID de serviço já existente/colidindo, criação inesperada de usuário ou
  alteração de `/etc/passwd`/`/etc/group`;
- rede acessível, `real_transport` diferente de `false`, `transmit` não
  retornar `TRANSPORT_DISABLED`, ou evidência de AF_INET;
- logs/artefatos sem sanitização de paths.

O probe `coord_trust_cross_uid_probe.py` continua sendo apenas a prova de
kernel `SCM_CREDENTIALS` cross-UID; ele **não** substitui os gates acima. O
job cross-UID não deve ser disparado até a certificação independente do SHA e
dos limites pelo mantenedor/B; esta rodada apenas publica o Draft.

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
