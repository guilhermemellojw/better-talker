# COORD-OPS/P9 — homologação isolada e piloto sintético (runbook)

**Estado:** preparação documental. **Nenhum comando deste runbook foi executado**
nesta rodada: sem sudo, instalação, criação de usuário no notebook, início de
serviço, piloto, dispatch, deploy, merge, transporte real, ADB ou LLM pago.

**Baseline certificada:** `ab9f08c4b233ea8d3c273084b720f8ffa827d022` (main,
PR #87 integrado). Certificação cross-UID: **106/106**. Suíte Python: **731**.
`real_transport=false` permanece o contrato.

**Objetivo:** deixar pronta para autorização a primeira homologação operacional
isolada e o primeiro piloto sintético do Agente S, reutilizando o serviço de
confiança, o ledger 1.1, a autoridade assinada, o transporte sintético e os
adaptadores OpenCode já existentes. Não repetir a certificação cross-UID nem
reconstruir componentes aprovados.

---

## 1. Inventário de prontidão

Legenda: **PRONTO** (implementado e testado), **CONFIG** (só configuração),
**FALTA** (implementação/ambiente ausente), **FORA** (fora do escopo desta
rodada).

| Componente | Local principal | Estado | Observação |
|---|---|---|---|
| Serviço de confiança | `.opencode/scripts/coord_trust_service.py` | **PRONTO** | 106/106 cross-UID; ledger/trust/âncora privados; `transmit` desabilitado |
| CLI do serviço / health check | `.opencode/scripts/coord_trust_service_cli.py` | **PRONTO** | `--check` exige rodar sob o UID/GID do serviço |
| Ledger 1.1 | `.opencode/scripts/coord_ledger.py` | **PRONTO** | estados, lease, replay, UNKNOWN, quarentena, epoch |
| Autoridade assinada V2 | `.opencode/scripts/coord_p14b_authority.py`, `coord_authority_signed.py` | **PRONTO** | envelope, revogação, atestação; chave sintética só em teste |
| Adaptador ledger/CLI | `.opencode/scripts/coord_p14_adapter.py`, `coord_p14b.py` | **PRONTO** | `SignedOperationStore`; jornada sintética completa offline |
| Transporte sintético em memória | `.opencode/scripts/coord_communication.py` | **PRONTO** | `SimulatedTransport`; sucesso, timeout, UNKNOWN, duplicidade |
| Pipeline do piloto | `.opencode/scripts/coord_pilot.py`, `coord_pilot_cli.py` | **PRONTO (fixture)** | preflight, manifesto, aprovação, executor, reconciliação; `dry-run` com 1 POST falso |
| Integração OpenCode real | `.opencode/scripts/coord_pilot.py` (`OpenCodeTransport`) | **BLOQUEADO por desenho** | cliente local, auth experimental, sem POST real; falta ponte operacional com o serviço |
| Unidade systemd | `.opencode/deploy/coord-trust-service.service` | **CONFIG** | exemplo; não instalar no notebook |
| Config/trust/registry | `.opencode/deploy/*.example.json` | **CONFIG** | placeholders; exigem material revisado |
| Ambiente descartável | — | **FALTA** | sem VM/cloud-init/provisionamento versionado |
| Piloto sintético no socket do serviço | — | **FALTA (driver pequeno)** | peças existentes cobrem partes; não há um driver único dos 9 passos |
| Agente S operacional | `docs/COORDINATOR.md` | **FORA** | SHADOW; não há motor de decisão operacional; piloto usa fixture determinística |
| OpenCode real | — | **FORA** | exige instância privada, credencial, permissão e aprovação humana específicas |
| DELEGATED | `docs/COORDINATOR.md` §2.1 | **FORA** | exige contrato de delegação e certificação adicionais |

### 1.1 O que já existe e pode ser reutilizado

- Serviço e ledger certificados, com `real_transport=false` e resposta
  `TRANSPORT_DISABLED` para `transmit`.
- `SyntheticSigner`/`ApproverAllowlist` para autorização de teste, sem chave
  privada no agente e sem material real.
- `run_synthetic_journey()` e `coord_pilot_cli.py dry-run`: percurso
  proposta → autorização → reserva → despacho sintético → relatório →
  reconciliação, com ledger e 0 POST real.
- `demo_communication.py`: fluxo sintético com sucesso, timeout/UNKNOWN,
  bloqueio de reenvio e **próxima missão PROPOSED não despachada**.
- `coord_trust_crossuid_client.py` e `ServiceClient`: chamadas reais ao socket
  local com autenticação de servidor por UID/GID/dono/`service_id`.

### 1.2 O que falta para a primeira execução

1. Provisionar a VM descartável e os UIDs efêmeros.
2. Materializar config/trust/registry e a chave do signer sintético fora do
   serviço e do executor.
3. Instalar a unidade **somente dentro da VM**, iniciar o serviço e validar
   `--check`, socket e negação de leitura.
4. Executar o piloto sintético dos 9 passos. Sem um driver único, isso será
   uma sequência manual/orquestrada; a menor automação está na §11.
5. Executar a matriz de homologação e obter revisão independente de B.

---

## 2. Ambiente isolado proposto

### 2.1 Estratégia

**VM descartável** (recomendado) ou instância cloud efêmera com disco
descartável. Não usar o notebook para criar usuários, instalar unidades ou
hospedar estado operacional. Alternativa não recomendada para o primeiro
piloto: reutilizar o harness cross-UID apenas como evidência de mecanismo; ele
não é um ambiente operacional interativo.

| Item | Proposta |
|---|---|
| SO | Ubuntu 24.04 LTS mínimo, atualizado, sem dados do usuário |
| vCPU/RAM | 2 vCPU / 2 GiB |
| Disco | 8 GiB, descartável; estado < 256 MiB |
| Rede | loopback apenas; sem IP público, sem egress; serviço com `PrivateNetwork=yes` |
| Duração | 90 min de janela; hard stop em 120 min |
| Dados | 100% sintéticos; nenhuma sessão, credencial, worktree ou banco real |
| Signer | chave sintética em diretório 0700 de UID separado; nunca no serviço/executor |
| Limpeza | destruir a VM e o disco; nenhum resíduo no notebook |

### 2.2 UIDs/GIDs efêmeros (somente dentro da VM)

Substituir os valores se houver colisão; verificar com `getent passwd <UID>`
antes. Não criar esses usuários no notebook.

| Papel | UID:GID sugerido | Acesso |
|---|---|---|
| Serviço | `9101:9101` | dono de state/ledger/trust/registry/socket/âncora |
| Executor sintético | `9102:9101` | todas as ações do piloto no H3 (padrão certificado); sem state |
| Observer | `9103:9101` | somente `status` |
| Coordenador | `9104:9101` | opcional no H3; `propose`/`authorize`/`status` no H3b |
| Maintainer | `9105:9101` | `revoke`/`recover`/`status` |
| Signer sintético | `9106:9106` | somente diretório da chave e assinatura; sem socket/estado |
| Operador | conta administrativa da VM | instala, backup, logs; não assina automaticamente |

Para o **H3** (piloto positivo), usar o executor `9102` com todas as ações,
mesmo padrão já certificado cross-UID. O `consumers.example.json` separa
coordenador/executor/observer/maintainer; essa separação é **configuração nova**
e vira o **H3b**, que deve ser validado por delta focal antes de ser usado no
piloto. Não presumir cobertura cross-UID para o registry dividido.

### 2.3 Diretórios e permissões

| Caminho | Modo/dono | Uso |
|---|---|---|
| `/opt/better-talker` | 0555 root | código somente leitura |
| `/var/lib/better-talker-coord` | 0700 serviço | ledger, service state, trust store, registry |
| `/var/lib/better-talker-coord-anchor` | 0700 serviço | âncora forward-only, fora do backup do ledger |
| `/run/better-talker-coord` | 0750 root:grupo | socket 0660 |
| `/etc/better-talker` | 0750 root:grupo | config 0640 root:grupo |
| `/var/lib/better-talker-p9-signer` | 0700 signer | chave privada sintética e assinaturas |
| `/var/lib/better-talker-p9-work` | 0700 executor | work dir do driver sintético |
| `/var/lib/better-talker-p9-artifacts` | 0700 executor | relatórios/evidências sanitizados |
| `/var/lib/better-talker-p9-backup` | 0700 operador | backup do estado (âncora separada) |

O serviço deve rodar com exatamente `service_uid`/`service_gid`; o consumidor
não recebe paths de estado. A âncora não é restaurada junto com o ledger.

### 2.4 Limites de recurso propostos

Aplicar somente na VM, via drop-in ou cgroup; não instalar nada no notebook.

| Recurso | Limite |
|---|---|
| VM | 2 vCPU, 2 GiB RAM, 8 GiB disco |
| Serviço | `CPUQuota=50%`, `MemoryMax=512M`, `TasksMax=64` |
| Executor/coordenador | `CPUQuota=50%`, `MemoryMax=512M` cada |
| Estado | < 256 MiB; alerta em 200 MiB |
| Logs/artefatos | < 50 MiB; rotação/limpeza no encerramento |
| Duração | 90 min; `RuntimeMaxSec` de 1800 s para o serviço como proteção |
| Rede | 0 conexões externas; 0 POST; 0 LLM |
| Custo | US$ 0; nenhum modelo pago |

---

## 3. Pré-condições de autorização

Antes de qualquer execução, registrar em `DECISIONS.md`/checkpoint:

- `P9-OPS-APPROVAL`: autorização do mantenedor/coordenação para provisionar e
  destruir a VM, criar usuários efêmeros **dentro dela**, iniciar o serviço e
  executar o piloto sintético.
- `P9-SYNTH-APPROVAL`: autorização para gerar e usar a chave sintética do
  signer e a allowlist de teste; validade curta; sem chave real.
- `P9-REVIEW`: designação de B como revisor independente desta matriz e dos
  resultados.
- `P9-REAL-APPROVAL` (futura): separada, para instância OpenCode real,
  credencial, permissão, um POST e um relatório real. **Não emitida nesta
  rodada.**
- `P9-DELEGATION` (futura): contrato de delegação para DELEGATED. **Não
  emitido.**

Sem `P9-OPS-APPROVAL` e `P9-SYNTH-APPROVAL`, a execução é **BLOCKED**, não
PASS. A aprovação sintética não é aprovação humana real e não habilita
transporte real.

---

## 4. Piloto sintético do Agente S

### 4.1 Fixture

- `P9-REPORT-001` (relatório sintético): tarefa `P9-DOC-001` em andamento,
  dependência `P9-DECISION-002` pendente, ownership `docs/operations/`, status
  `BLOCKED` ou `IN_PROGRESS`; nenhum dado real.
- `MISS-P9-SYNTH-001` / `OP-P9-SYNTH-001`: “classificar o relatório e propor o
  próximo encaminhamento”; `environment=SHADOW`; modelo
  `opencode/hy4-flash-free`; `expected_head_sha=bbbb…` (40 hex);
  `expected_branch=fixture/p9`; `max_transmissions=1`; `free_only=true`;
  custo 0; `operational_digests` sintéticos não vazios.
- Destino: `agent-s-synth`, `ses-p9-synth-001`, instância `p9-synth-1`,
  location `/srv/p9/fixtures/agent-s` — todos fictícios.
- O executor do registry deve ter `agent_id=agent-s-synth` no H3; o exemplo
  `agent-a` é apenas placeholder.
- `MISS-P9-SYNTH-002`: próxima proposta (resolver `P9-DECISION-002`), criada
  em `PROPOSED`, **não autorizada e não despachada**.
- Autoridade: `SyntheticSigner` (UID 9106), allowlist pública no trust store;
  ambiente `SHADOW`; `max_transmissions=1`; custo 0; validade curta.
- Transporte: `SimulatedTransport` em memória ou receipt/evidence sintéticos
  registrados no serviço; **nenhum** `OpenCodeTransport` real, sessão ou POST.

### 4.2 Sequência dos 9 passos

| # | Passo | Evidência esperada |
|---|---|---|
| 1 | Agente S lê `P9-REPORT-001` | relatório sanitizado, hash, identidade da missão |
| 2 | Detecta tarefa pendente e dependências | `blockers`/`dependencies` explícitos; sem inventar dados |
| 3 | Propõe encaminhamento | `MISS-P9-SYNTH-001` em `PROPOSED`; sem autorização |
| 4 | Recebe autorização assinada de teste | envelope V2 verificado pelo serviço/ledger |
| 5 | Reserva a missão | lease único; estado `DISPATCHING`; sem duplicidade |
| 6 | Simula despacho por adaptador sintético | receipt `msg-p9-synth-001`; `transport_real=false`; 0 POST |
| 7 | Recebe relatório fictício | `BT-COORD-REPORT:v1` associado; `assess_report` compatível |
| 8 | Reconcilia resultado | atestação sintética válida; estado `COMPLETED`; receipt/report/evidence vinculados |
| 9 | Propõe próximo encaminhamento | `MISS-P9-SYNTH-002` em `PROPOSED`; `dispatched=false` |

O passo 3 e o passo 9 são decisão determinística de fixture, não LLM. O
relatório do agente é dado não confiável; o serviço/ledger é a autoridade de
estado.

### 4.3 Cenários negativos obrigatórios

| Cenário | Ação | Resultado esperado |
|---|---|---|
| Autorização inválida | assinatura/envelope adulterado | `SERVICE_REJECTED`/`UNAUTHENTICATED_PEER` com `reason=SIGNATURE_INVALID`; sem reserva |
| Duplicidade | repetir `reserve`/`request_id` | replay idempotente ou `REQUEST_REPLAY`/`CONFLICT`; nenhum segundo despacho |
| Ausência de autorização | `reserve`/`assert` sem `authorize` | falha fechada; estado não avança |
| Interrupção | `interrupt` após `assert`/antes do receipt | `UNKNOWN`; sem reenvio automático |
| Resultado desconhecido | timeout/perda no adaptador | `UNKNOWN`; reconciliação só por evidência ou decisão humana |
| Conflito de ownership | dois agentes/consumidores para a mesma sessão/missão | segunda reserva recusada; nenhuma transmissão duplicada |
| Relatório/atestado divergente | hash/identidade/resultado adulterado | rejeição; não vira `COMPLETED` |
| Falha interna | exceção inesperada na rota | `SERVICE_ERROR`/`reason=INTERNAL`; sem path/traceback |
| Quarentena/rollback | restaurar ledger antigo | quarentena; recuperação assinada com nova epoch; lease antigo stale |

Os códigos exatos devem ser registrados no resumo; não aceitar “falhou” genérico
como PASS.

---

## 5. Comandos propostos (NÃO EXECUTADOS)

> Executar somente na VM descartável, após `P9-OPS-APPROVAL` e
> `P9-SYNTH-APPROVAL`. Nenhum abaixo foi executado nesta rodada. `[VM]`
> significa “somente dentro da VM”; nada deve ser executado no notebook.

### 5.1 Provisionamento e usuários efêmeros

```sh
# [VM] exemplo; substituir UIDs se colidirem. Não executar no notebook.
groupadd --gid 9101 bt-coord-trust
groupadd --gid 9106 bt-coord-signer
useradd --system --uid 9101 --gid 9101 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-trust
useradd --system --uid 9102 --gid 9101 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-agent
useradd --system --uid 9103 --gid 9101 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-observer
useradd --system --uid 9104 --gid 9101 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-coordinator
useradd --system --uid 9105 --gid 9101 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-maintainer
useradd --system --uid 9106 --gid 9106 --home-dir /nonexistent \
  --shell /usr/sbin/nologin bt-coord-signer
```

### 5.2 Layout, configuração e signer

```sh
# [VM] código somente leitura; não copiar estado/chaves para o checkout.
install -d -m 0555 -o root -g root /opt/better-talker
# ... copiar/extrair o SHA aprovado para /opt/better-talker ...

install -d -m 0700 -o bt-coord-trust -g bt-coord-trust /var/lib/better-talker-coord
install -d -m 0700 -o bt-coord-trust -g bt-coord-trust /var/lib/better-talker-coord-anchor
install -d -m 0750 -o root -g bt-coord-trust /run/better-talker-coord
install -d -m 0750 -o root -g bt-coord-trust /etc/better-talker
install -d -m 0700 -o bt-coord-signer -g bt-coord-signer /var/lib/better-talker-p9-signer
install -d -m 0700 -o bt-coord-agent -g bt-coord-trust /var/lib/better-talker-p9-work
install -d -m 0700 -o bt-coord-agent -g bt-coord-trust /var/lib/better-talker-p9-artifacts
install -d -m 0700 -o root -g root /var/lib/better-talker-p9-backup

# [VM] chave sintética; nunca sai do diretório 0700 do signer.
runuser -u bt-coord-signer -- ssh-keygen -q -t ed25519 -N '' \
  -f /var/lib/better-talker-p9-signer/approver
ssh-keygen -lf /var/lib/better-talker-p9-signer/approver.pub -E sha256
```

Preencher `trust-service.json`, `trust-store.json` e `consumers.json` a partir
dos exemplos, com owner/modo corretos, e revisar fingerprints publicamente.
Para o **H3**, o registry deve conceder ao executor `9102` todas as ações
usadas no piloto (padrão certificado) e `agent_id=agent-s-synth`; o exemplo
dividido é o **H3b**. Não copiar chave privada para o serviço, executor,
checkout ou artefatos.

### 5.3 Serviço e health check

```sh
# [VM] unidade de exemplo, após revisão; NÃO instalar no notebook.
install -m 0644 /opt/better-talker/.opencode/deploy/coord-trust-service.service \
  /etc/systemd/system/coord-trust-service.service
systemctl daemon-reload
systemctl start coord-trust-service
systemctl status coord-trust-service --no-pager
journalctl -u coord-trust-service -n 50 --no-pager

# [VM] o check precisa rodar sob o UID/GID do serviço.
runuser -u bt-coord-trust -- python3 \
  /opt/better-talker/.opencode/scripts/coord_trust_service_cli.py \
  --config /etc/better-talker/trust-service.json --check
# esperado: CONFIG-OK real_transport=false

stat -c '%U:%G %a %F %n' /run/better-talker-coord/service.sock
```

### 5.4 Piloto sintético

**Estado atual: BLOCKED para um único driver de 9 passos no socket.** As peças
existentes podem ser executadas separadamente, mas não substituem o percurso
único. Com o driver mínimo da §11 aprovado, o comando proposto seria:

```sh
# [VM] PROPOSTO; script ainda NÃO implementado.
runuser -u bt-coord-agent -- env -i \
  PATH=/usr/bin:/bin HOME=/nonexistent TMPDIR=/tmp LANG=C LC_ALL=C \
  python3 /opt/better-talker/.opencode/scripts/coord_ops_p9_pilot.py \
    --config /etc/better-talker/trust-service.json \
    --socket /run/better-talker-coord/service.sock \
    --mode synthetic --scenario all \
    --work-dir /var/lib/better-talker-p9-work \
    --artifacts /var/lib/better-talker-p9-artifacts
```

Sem o driver, executar apenas as evidências parciais já existentes (host-only,
fora da VM operacional) e registrar **BLOCKED**, nunca PASS:

```sh
# [host-only, não é o piloto operacional]
python3 .opencode/scripts/demo_communication.py
python3 .opencode/scripts/coord_pilot_cli.py --store-dir /tmp/p9-dry dry-run
python3 -m unittest discover
python3 .opencode/scripts/test_supervisor.py
```

### 5.5 Negativos, backup e limpeza

```sh
# [VM] negativos: comandos devem ser emitidos pelo driver/roteiro aprovado,
# com request_id e códigos esperados registrados em summary.json.

# [VM] backup consistente; não copiar a âncora para “voltar” epoch.
systemctl stop coord-trust-service
sqlite3 /var/lib/better-talker-coord/ledger.db \
  ".backup '/var/lib/better-talker-p9-backup/ledger.db'"
cp -a /var/lib/better-talker-coord/service.db \
  /var/lib/better-talker-p9-backup/service.db
cp -a /var/lib/better-talker-coord/trust-store.json \
  /var/lib/better-talker-coord/consumers.json \
  /var/lib/better-talker-p9-backup/
sha256sum /var/lib/better-talker-p9-backup/* > \
  /var/lib/better-talker-p9-backup/SHA256SUMS

# [VM] cleanup: parar, exportar evidências sanitizadas e destruir a VM/disco.
systemctl stop coord-trust-service
ss -lxnp | grep better-talker-coord || true
# ... exportar somente summary/logs sanitizados ...
# ... destruir a VM e o disco; descartar a chave sintética ...
```

Não restaurar a âncora antiga. Se o ledger restaurado atrasar, o serviço deve
entrar em quarentena; recuperação exige declaração assinada e nova epoch.

---

## 6. Matriz de homologação

Critério geral: **PASS** somente com evidência observada e sanitizada no SHA
aprovado; **FAIL** para estado/código divergente, transporte real, privilégio
excessivo, vazamento ou resíduo; **BLOCKED** para pré-condição ausente (nunca
converter ausência em PASS).

| ID | Cenário | Evidência mínima | PASS | BLOCKED |
|---|---|---|---|---|
| H0 | Isolamento da VM | UIDs, mounts, `real_transport=false`, rede sem egress | tudo confere; nenhum dado real | VM/UID/rede não comprovados |
| H1 | Config/health | `CONFIG-OK real_transport=false` sob UID do serviço | check OK; sem ledger/socket criado indevidamente | config/trust/registry inválidos |
| H2 | Socket e least privilege | socket 0660, grupo, state inacessível ao consumidor | consumidor só socket; leitura do estado negada | permissão divergente/UID compartilhado |
| H3 | Piloto sintético positivo | 9 passos; `COMPLETED`; `MISS-P9-SYNTH-002` PROPOSED | estado/evidências coerentes; 0 POST real | driver ausente ou passo não observado |
| H3b | Separação de papéis (config nova) | coordenador `propose/authorize`; executor `reserve/assert/evidence/reconcile` | ambos os códigos/estados esperados; sem autoaprovação | registry dividido não validado em cross-UID |
| H4 | Autorização inválida | assinatura/envelope alterado | rejeição esperada; sem reserva/despacho | exceção escapou ou reserva ocorreu |
| H5 | Duplicidade/replay | repetição de reserve/request_id | replay/`REQUEST_REPLAY`/`CONFLICT`; um lease | segundo despacho ou estado divergente |
| H6 | Ausência de autorização | reserve/assert sem authorize | falha fechada; nenhum lease | operação aceita |
| H7 | Interrupção/UNKNOWN | interrupt após assert | `UNKNOWN`; sem reenvio | estado terminal falso ou reenvio |
| H8 | Timeout/perda | adaptador sintético sem receipt | `UNKNOWN`; reconciliação só com evidência | COMPLETED por presunção |
| H9 | Conflito de ownership | dois agentes/consumidores na mesma sessão | segunda reserva recusada | duas transmissões/leases |
| H10 | Relatório/atestado divergente | hash/identidade/resultado alterado | rejeição; sem COMPLETED | relatório aceito |
| H11 | Rollback/quarentena | restaurar ledger antigo | quarentena; recuperação assinada nova epoch | estado antigo volta a valer |
| H12 | Falha interna/sanitização | exceção inesperada/audit fail | `SERVICE_ERROR`/`INTERNAL`; sem path/traceback | exceção escapou ou vazou |
| H13 | Limpeza | após destruir VM | nenhum processo/socket/usuário no notebook; chaves descartadas | resíduo ou unidade instalada |
| H14 | Separação de modos | SHADOW/sintético/real/DELEGATED | nenhum modo novo ativado; real bloqueado | real/DELEGATED habilitado |
| H15 | OpenCode read-only (futuro) | `preflight --real` sem POST | auth/identidade OK; 0 POST | sem credencial/permissão ou tentativa de POST |

### 6.1 Critérios de parada

Interromper e registrar **FAIL/BLOCKED** se: UID/GID divergente; estado
legível pelo consumidor; rede externa; `real_transport=true`; `transmit`
diferente de `TRANSPORT_DISABLED`; chave privada acessível ao serviço/executor;
resposta com path/traceback; `UNKNOWN` com reenvio; segundo lease; quarentena
ignorada; ou limpeza não comprovada.

---

## 7. Orçamento de recursos

| Recurso | Orçamento |
|---|---|
| VM | 2 vCPU, 2 GiB RAM, 8 GiB disco, sem GPU |
| Serviço | 0,5 vCPU, 512 MiB, 64 tarefas |
| Executor/coordenador | 0,25–0,5 vCPU, 256–512 MiB cada |
| Estado/artefatos | < 256 MiB / < 50 MiB |
| Rede | 0 conexões externas; somente AF_UNIX |
| LLM/tokens | 0; nenhum modelo pago |
| Tempo | setup 20 min, piloto 30 min, negativos 20 min, cleanup/revisão 20 min; hard stop 120 min |
| Pessoas | 1 operador, 1 aprovador, 1 revisor B; o operador não assina a própria autorização |

---

## 8. Riscos e rollback

| Risco | Mitigação | Rollback |
|---|---|---|
| Vazamento de chave sintética | diretório 0700 do signer; nunca no serviço/executor; validade curta | destruir VM e chave; revogar allowlist de teste |
| Falso PASS por teste parcial | matriz exige evidência por passo/código; BLOCKED explícito | repetir somente após corrigir evidência |
| Serviço/rede real acidental | `PrivateNetwork`, firewall sem egress, `real_transport=false`, checagem de AF_INET | parar serviço e destruir VM |
| Corrupção de estado | backup consistente; âncora separada; não editar SQLite | quarentena + recuperação assinada; nunca restaurar epoch antiga |
| Duplicidade/UNKNOWN | um lease; sem reenvio; reconciliação por evidência | manter `UNKNOWN`, preservar ledger, reportar |
| Resíduo no notebook | toda instalação/usuário somente na VM | destruir VM; verificar `systemctl`, `getent`, sockets |
| Confusão de modos | SHADOW/ASSISTED sintético/ASSISTED real/DELEGATED explícitos | bloquear até decisão registrada |
| Dados reais | fixtures e IDs sintéticos; nenhum session/worktree/credencial | destruir artefatos e VM |

---

## 9. Caminho mínimo para OpenCode

| Modo | Execução | Transporte | Estado nesta rodada |
|---|---|---|---|
| **SHADOW** | Agente S observa e propõe; humano decide | nenhum | atual; sem dispatch |
| **ASSISTED sintético** | operador autoriza missão fictícia; passos 1–9 em ambiente isolado | `SimulatedTransport`/receipt sintético; 0 POST | **preparado; não executado** |
| **ASSISTED real** | uma sessão OpenCode privada, aprovação humana exata, um POST | `OpenCodeTransport` real | **BLOQUEADO**; requer ponte de lease/transporte, credencial e permissão |
| **DELEGATED** | Agente S distribui missões elegíveis sob contrato | real, futuro | **FORA**; exige contrato e certificação |

### 9.1 Menor integração futura

O `OpenCodeTransport` já implementa preflight, manifesto, aprovação, reserva e
reconciliação, mas hoje opera no executor/ledger, não no socket do serviço. Para
o futuro ASSISTED real, a menor fronteira defensável é:

1. o serviço continua sendo a autoridade de autorização/lease;
2. o executor obtém `assert` e um lease de uso único imediatamente antes do
   POST;
3. um dispatcher separado, com UID próprio, recebe o lease e executa **um**
   POST; o serviço não devolve a chave privada nem o estado;
4. `UNKNOWN` nunca é reenviado; a reconciliação consulta mensagens/inbox.

Ainda não há código para essa ponte. **Não habilitar transporte real** por
flag, mock ou alteração de `real_transport`; a decisão exige revisão de B e
autorização específica. Para P9, o limite é ASSISTED sintético.

---

## 10. Autorizações, estimativa e handoff

### 10.1 Autorizações necessárias

- **Agora:** revisão independente de B deste runbook/matriz (P9-REVIEW).
- **Próxima execução:** P9-OPS-APPROVAL + P9-SYNTH-APPROVAL para a VM, o
  signer sintético e o piloto; janela e responsáveis registrados.
- **Futuro:** P9-REAL-APPROVAL para OpenCode real; P9-DELEGATION para
  DELEGATED. Nenhuma foi emitida.

### 10.2 Estimativa das etapas restantes

| Etapa | Esforço estimado |
|---|---|
| Revisão B deste runbook | 1 ciclo de revisão |
| VM + UIDs + config + signer + health check | 2–3 h |
| Piloto sintético positivo (9 passos) | 2–3 h |
| Matriz negativa + rollback/quarentena | 2–3 h |
| Cleanup e evidência sanitizada | 1 h |
| Preflight OpenCode read-only (futuro) | 2 h |
| Ponte dispatcher/lease + revisão (futuro) | 1–2 ciclos |
| Primeiro ASSISTED real (futuro) | 1 janela autorizada, 1 POST |
| DELEGATED | fora desta estimativa |

Estimativas são faixa de esforço, não compromisso de prazo.

### 10.3 Handoff para B

B deve revisar: inventário, limites de isolamento, fixture dos 9 passos,
matriz PASS/FAIL/BLOCKED, orçamento, riscos/rollback e o corte de modos. O
handoff só vira execução após autorização registrada. O resultado esperado é um
parecer independente sobre **prontidão para a janela**, não uma certificação de
transporte real.

---

## 11. Lacuna de código (decisão da coordenação)

**Nenhuma alteração de código foi feita nesta rodada.**

Para o piloto único de 9 passos no socket do serviço, a menor alteração
candidata é **um script de homologação (host-only/VM)**, sem tocar no
serviço/ledger/autoridade:

- `.opencode/scripts/coord_ops_p9_pilot.py` (nome proposto): ~250–350 linhas;
  usa `ServiceClient`, `SyntheticSigner`, `coord_trust_crossuid_common`
  (fixtures) e `SimulatedTransport`; aceita `--mode synthetic`; grava
  `summary.json` sanitizado; não abre rede; não habilita `transmit`.
- `.opencode/scripts/test_coord_ops_p9_pilot.py`: cobre passo positivo,
  autorização inválida, duplicidade, ausência, interrupção, UNKNOWN, ownership,
  atestado divergente, auditoria e cleanup; sem serviço real em CI comum
  (host-only/fake client), com teste de socket apenas no ambiente autorizado.
- Nenhuma mudança em `coord_trust_service.py`, `coord_ledger.py`,
  `coord_authority_signed.py`, `coord_pilot.py` ou no workflow de certificação.

Alternativa sem código: executar o roteiro manual com `ServiceClient` e os
fixtures existentes; aceitável para uma janela única, porém menos reproduzível
e mais sujeito a erro de operador. **Recomendação:** aprovar o script de homologação
antes da janela; se a coordenação preferir zero código, registrar a execução
manual como tal e não declarar automação.

Para ASSISTED real, a lacuna não é “pequena flag”: é a ponte dispatcher/lease
descrita na §9.1; não implementar sem decisão e revisão específicas.
