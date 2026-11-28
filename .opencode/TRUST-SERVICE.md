# COORD-TRUST-BOUNDARY/P3/P4 — serviço local de autoridade e ledger

**Estado:** fundação em PR Draft; nenhuma operação real, instalação, usuário,
sudo, migração, credencial ou transporte real foi executado.
**Base:** P14B consolidado `7c1c4b1a` (PR #82), sem alterá-lo.
**Objetivo:** retirar do agente o acesso direto ao ledger 1.1 e ao trust store,
centralizando autorização/lease/replay/reconciliação em um processo com
identidade de consumidor fornecida pelo kernel.

## 1. Fronteira de confiança

O agente/consumidor é tratado como potencialmente comprometido. Ele pode
chamar a API local, tentar autodeclarar identidade/permissões, adulterar
payloads e fabricar relatórios. Ele **não** deve:

- abrir ou escrever o SQLite do ledger, o service state, o trust store ou a
  âncora externa;
- ler a chave privada de autoridade;
- reutilizar autorização/lease/relatório;
- iniciar transporte real;
- conceder a si mesmo uma ação ou papel.

A fronteira é imposta por:

1. **UID exclusivo por consumidor.** Produção usa `allow_same_uid=false` e
   `test_mode=false`; cada principal tem UID próprio, sem compartilhar UID com
   o serviço. Configuração com UID duplicado, desconhecido ou igual ao do
   serviço é recusada.
2. **Identidade do kernel.** `SO_PEERCRED` fornece PID/UID/GID da conexão;
   `SO_PASSCRED`/`SCM_CREDENTIALS` fornece credenciais **por mensagem**. Não há
   leitura obrigatória de `/proc/<pid>/exe`, `CAP_SYS_PTRACE`, root ou hash de
   executável como prova de identidade.
3. **Estado privado.** `state_dir` 0700; ledger, service state, trust store,
   consumer registry e sidecars SQLite 0600, do UID do serviço; hardlinks e
   symlinks são recusados.
4. **Socket local.** Somente `AF_UNIX`/`SOCK_SEQPACKET`; diretório de runtime
   0750 do grupo do serviço, socket 0660. O consumidor precisa do grupo e não
   recebe paths do estado.
5. **Trust store do serviço.** Allowlist de chaves públicas e consumer registry
   ficam no estado privado; o cliente não recebe paths nem conteúdo.
6. **Ledger do serviço.** O serviço é o único que instancia `Ledger` e chama
   `authorize_signed`, `reserve_dispatch_signed`, `assert_dispatchable`,
   `record_dispatch_evidence`, `reconcile_signed`, `revoke_authorization` e a
   recuperação assinada.

O adapter V2 (`coord_p14b_authority`) é injetado no ledger e exige a
`MissionEnvelope` #64 armazenada pelo serviço, recalculando a identidade
canônica em cada gate.

**Limite explícito:** UID identifica um principal, **não** um script, argv ou
interpretador. Processos que compartilham o mesmo UID pertencem à mesma
fronteira e não são distinguíveis por este serviço. `exe_sha256` não é
autoridade. O modo `allow_same_uid=true` exige `test_mode=true` e existe apenas
para a suíte host-only; não é fronteira de produção.

## 2. Protocolo local

- Unix domain socket `AF_UNIX`/`SOCK_SEQPACKET`; sem TCP, HTTP, DNS ou sockets
  de rede.
- Cada frame é um pacote com prefixo de 4 bytes e limite de 1 MiB.
- `SO_PASSCRED` no servidor; `SCM_CREDENTIALS` é exigido em **cada** frame de
  hello e request. Credenciais ausentes, truncadas (`MSG_CTRUNC`), múltiplas,
  inconsistentes ou de UID/PID/GID divergente da conexão são recusadas.
- `SCM_RIGHTS` e qualquer ancillary não esperado são recusados; transferência
  de descritor para outro UID é detectada pela credencial da mensagem.
- Handshake: welcome do serviço; hello e request do cliente; autenticação real
  por credenciais do kernel, nunca por `consumer_id` da requisição.
- `request_id` é idempotente por consumidor/ação/material. Resposta histórica
  não é autorização atual: `assert`/`reserve`/`authorize` são revalidados no
  ledger; revogação, expiração, `UNKNOWN` ou estado terminal retornam
  `REPLAY_STALE` em vez de reutilizar o lease/resposta. `status` histórico é
  marcado `historical=true`/`authorization_current=false`.
- Respostas não expõem paths, chaves, trust store ou conteúdo do ledger.

Ações: `propose`, `authorize`, `reserve`, `assert`, `record_evidence`,
`reconcile`, `revoke`, `interrupt`, `status`, `recover`. `transmit` falha
explicitamente com `TRANSPORT_DISABLED`.

## 3. Fluxo

```text
consumidor -> propose (MissionEnvelope + material SHADOW)
           -> humano assina envelope V2 fora do agente
           -> authorize (envelope + assinatura) [serviço verifica/ledger]
           -> reserve (lease) [serviço consome]
           -> assert (lease) [serviço revalida imediatamente antes do limite]
           -> record_evidence (receipt/evidence sintéticos)
           -> humano assina atestação terminal
           -> reconcile (atestação + report/evidence) [serviço verifica/ledger]
```

`revoke` persiste revogação assinada; `assert`/novas reservas falham.
`interrupt` leva `DISPATCHING -> UNKNOWN`, preserva reserva/consumo e nunca
reenvia. A janela `assert` → POST continua residual e o transporte real
permanece desabilitado (`real_transport=false`).

## 4. Privilégios, armazenamento e recuperação

- O serviço roda como `bt-coord-trust`, sem capabilities, com
  `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
  `PrivateNetwork` e somente `AF_UNIX`.
- Estado persistente em `/var/lib/better-talker-coord` (0700 do serviço);
  runtime/socket em `/run/better-talker-coord` (0750 do grupo); âncora externa
  em `/var/lib/better-talker-coord-anchor` (0700), fora do backup do ledger.
- Ledger/service state/trust store/registry e sidecars `-wal`/`-shm`/`-journal`
  são 0600, regulares, sem hardlink/symlink. O runtime valida os ancestrais
  relevantes e recusa socket regular, symlink ou de outro dono.
- O cliente autentica o servidor por `SO_PEERCRED`/dono/grupo do socket e pelo
  `service_id` público; não basta um path.
- A âncora de rollback é monotônica fora do `state_dir`. Ledger ou service
  state atrasado entra em quarentena; recuperação exige declaração assinada
  por autoridade externa e cria nova epoch. O nonce da declaração é consumido
  de forma persistente e não pode ser reutilizado em epoch futura. Restaurar
  ledger e service state juntos ainda exige política forward-only e revogação
  externa — a âncora não é distribuída.
- Trust store/consumer registry são arquivos 0600 do serviço; alteração exige
  reinício controlado e não é feita pelo agente.

## 5. Implantação e testes

O contrato único de UIDs/GIDs, paths, permissões, socket, unidade systemd,
sandbox do consumidor, chave privada fora do agente e rollback forward-only
está em `docs/operations/trust-service-deployment.md`. Nenhuma unidade,
usuário ou serviço é instalado por esta fundação.

A suíte host-only cobre comunicação local real, UID desconhecido/duplicado,
credenciais SCM ausentes/divergentes/truncadas, replay histórico vs atual,
autoconcessão, least privilege, hardlinks/sidecars, substituição de socket,
autenticação do servidor, timeout total, adulteração de ledger/service state,
quarentena/rollback, revogação, interrupção, atestação forjada/relatório
adulterado, transporte desabilitado, nonce de recuperação e jornada completa
sintética.

Evidência local (`unshare -rn`):

| Suíte | Resultado |
|---|---|
| `unittest discover` (inclui os 41 do serviço, 17 P4 e 14 P9) | **578/578 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **745/745 = 578 + 167** |

`test_coord_trust_service.py` (**41/41**) é subconjunto do discover; não entra
novamente na soma.

A certificação cross-UID real é um job **opt-in** no workflow
`.github/workflows/coord-python.yml` (`workflow_dispatch`, inputs
`crossuid_full=true` e `certified_sha=<40-hex>`, timeout de 10 min). O job
recebe `certified_sha` por env, exige formato 40-hex, `GITHUB_SHA` e checkout
HEAD iguais ao certificado, bloqueia `run_attempt != 1`, faz checkout exato
sem credenciais persistidas e tem limpeza `always()` por manifest/`run_id`.
A suíte ordinária é omitida quando `crossuid_full=true`. O harness
`.opencode/scripts/coord_trust_crossuid_harness.py` cobre G1–G9 em runner
descartável, com dois UIDs numéricos efêmeros, sem criar usuários. Antes de G1
ele copia um runtime bundle mínimo (`0555`/`0444`) para fora do checkout e
verifica, sob os UIDs efetivos, interpretador, scripts, imports, ancestrais e
negação de state/trust/âncora/chaves. O serviço recebe um `/etc/passwd`/`group`
sintético em mount namespace privado apenas para getpwuid do OpenSSH; o UID
continua 65533, o host não é alterado e nenhum usuário é criado. O dispatch do
candidato histórico P4.2 `31a150330d2b9c8af420cdfac322fde73132dda8` falhou no
run `46081657455`; após P4.3/P4.5, o dispatch do HEAD P4.5
`876bc654ce1ab1032f64e25405d89cb8601e55d3` falhou no run `46087440250`
(G1/G3/G4/G5/G7), com causa raiz OpenSSH/NSS documentada em
`docs/reports/2026-11-27-coord-trust-crossuid-p46-signed-authority.md`. Ambas
as autorizações foram consumidas e nenhuma nova execução está autorizada. O
teste local com dois UIDs reais continua **BLOCKED** neste host (sem privilégio
para criar/menorizar UID; usuários reais não são criados).

## 6. Limitações

- Não há instalação, criação de usuário, sudo, migração operacional,
  credencial real, transporte real, POST, LLM, sessão, Android/ADB ou merge.
- A implementação é fundação; o contrato do adapter/ledger continua sendo a
  autoridade operacional, não este processo isoladamente.
- Mesmo UID é apenas laboratório; UID não prova qual script roda.
- A janela `assert` → POST, comprometimento do UID do serviço, rotação de
  chaves/consumer registry e rollback integral do host exigem operação
  protegida e auditoria independente.
