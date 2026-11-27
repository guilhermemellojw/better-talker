# COORD-TRUST-BOUNDARY/P1 — serviço local de autoridade e ledger

**Estado:** fundação em PR Draft; nenhuma operação real, instalação, usuário,
sudo, migração, credencial ou transporte real foi executado.
**Base:** P14B consolidado `7c1c4b1a` (PR #82), sem alterá-lo.
**Objetivo:** retirar do agente o acesso direto ao ledger 1.1 e ao trust store,
centralizando verificação de autorização/lease, replay e reconciliação em um
processo com identidade de consumidor derivada do kernel.

## 1. Modelo de confiança

O agente/consumidor é tratado como potencialmente comprometido. Ele pode
chamar a API local, tentar autodeclarar identidade/permissões, adulterar
payloads e fabricar relatórios. Ele **não** deve:

- abrir ou escrever o SQLite do ledger, o service state ou o trust store;
- ler a chave privada de autoridade;
- reutilizar uma autorização/lease/relatório;
- iniciar transporte real;
- conceder a si mesmo uma ação ou papel.

A separação é imposta por:

1. **UID/GID separado:** serviço e consumidor não compartilham UID em
   implantação (`allow_same_uid=false`). O modo de teste explícito
   `allow_same_uid=true` existe apenas para a suíte host-only.
2. **Estado privado:** `state_dir` 0700; ledger, service state, trust store e
   consumer registry 0600, todos do UID do serviço.
3. **Socket local:** somente `AF_UNIX`; diretório de runtime 0750 do grupo do
   serviço, socket 0660. O consumidor precisa pertencer ao grupo.
4. **Identidade do kernel:** o serviço lê `SO_PEERCRED` (pid/uid/gid),
   `starttime` de `/proc/<pid>/stat` e o SHA-256 do executável via
   `/proc/<pid>/exe`. O `consumer_id` **não** vem da requisição.
5. **Trust store do serviço:** allowlist de chaves públicas e consumer
   registry ficam no estado privado; o cliente não recebe paths nem conteúdo.
6. **Ledger do serviço:** o serviço é o único que instancia `Ledger` e chama
   `authorize_signed`, `reserve_dispatch_signed`, `assert_dispatchable`,
   `record_dispatch_evidence`, `reconcile_signed` e `revoke_authorization`.

O adapter V2 (`coord_p14b_authority`) é injetado no ledger e exige a
`MissionEnvelope` #64 armazenada pelo serviço, recalculando a identidade
canônica em cada gate.

## 2. Protocolo local

- Unix domain socket `AF_UNIX`; sem TCP, HTTP, DNS ou sockets de rede.
- Framing JSON canônico com prefixo de 4 bytes e limite de 1 MiB.
- Handshake: `welcome` do serviço com protocolo/ID público, `hello` do
  cliente; a autenticação real ocorre **antes** do welcome via `SO_PEERCRED`.
- Requisição: `{"request_id","action","payload"}`. Campos proibidos como
  `consumer_id`, `uid`, `role`, `allowed_actions`, `trust_store`, `ledger_path`
  e `state_dir` causam rejeição, mesmo aninhados no payload.
- `request_id` é idempotente: repetição do mesmo consumidor/ação/material
  retorna a resposta persistida; material divergente ou uso por outro
  consumidor é `REQUEST_REPLAY`.
- Respostas não expõem paths, chaves, trust store ou conteúdo do ledger.

Ações: `propose`, `authorize`, `reserve`, `assert`, `record_evidence`,
`reconcile`, `revoke`, `interrupt`, `status`. `transmit` existe apenas para
falhar explicitamente com `TRANSPORT_DISABLED`.

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
permanece desabilitado.

## 4. Privilégios e armazenamento

- O serviço roda como `bt-coord-trust`, sem capabilities, com
  `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
  `PrivateNetwork` e somente `AF_UNIX`.
- Estado persistente em `/var/lib/better-talker-coord` (0700 do serviço);
  runtime/socket em `/run/better-talker-coord` (0750 do grupo).
- O consumidor roda como outro UID e recebe apenas o socket; não recebe
  `state_dir`, paths do ledger/trust store ou chave privada.
- Trust store/consumer registry são arquivos 0600 do serviço; qualquer
  alteração é auditável e exige reinício controlado.

## 5. Testes

A suíte host-only cobre comunicação local real, peer desconhecido, replay,
autoconcessão, least privilege, adulteração do ledger/service state, acesso
indevido ao storage, revogação, interrupção, atestação forjada/relatório
adulterado, transporte desabilitado e jornada completa sintética.

Evidência local (`unshare -rn`):

| Suíte | Resultado |
|---|---|
| `test_coord_trust_service.py` | **21/21 OK** |
| `unittest discover` | **527/527 OK** |
| `test_supervisor.py` | **167/167 passed** |
| Total | **694** |

O workflow `.github/workflows/coord-python.yml` executa o discover com
baseline 527, a suíte do serviço com baseline 21, o supervisor com 167 e
confere o total 694 dentro do namespace sem rede. Ver o PR para logs/hashes.

## 6. Limitações

- Não há instalação, criação de usuário, sudo, migração, credencial real,
  transporte real, POST, LLM, sessão, Android/ADB ou merge.
- A implementação é fundação; o contrato do adapter/ledger continua sendo a
  autoridade operacional, não este processo isoladamente.
- Mesmo UID, em modo de teste, é apenas laboratório: não é fronteira de
  confiança.
- A janela `assert` → POST, comprometimento do UID do serviço e rotação de
  chaves/consumer registry exigem operação protegida e auditoria independente.
