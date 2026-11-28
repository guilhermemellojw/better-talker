# COORD-OPS/P9 — prontidão operacional isolada (relatório de inventário)

**Estado:** Draft documental; **nenhuma execução, instalação, sudo, serviço,
piloto, dispatch, deploy, merge, transporte real, ADB ou LLM pago** nesta
rodada. O runbook está em
`docs/operations/coord-ops-p9-isolated-homologation.md`.

**Baseline:** `ab9f08c4b233ea8d3c273084b720f8ffa827d022` (main, PR #87
integrado); PR #88 em Draft. **Certificação cross-UID histórica:** 106/106.
**Suíte Python:** 745 (P9.2: 578 discover + 167 supervisor).
**Código P9.2:** `9c876727efab5cc4f88c0cd10c986b774fe48cf7`.
`real_transport=false`.

## 1. Sumário executivo

A infraestrutura de fronteira está pronta para ser **homologada em ambiente
isolado**, mas ainda não está provisionada. O serviço, o ledger 1.1, a
autoridade assinada, o transporte sintético e o pipeline do piloto estão
implementados e cobertos. O que falta é:

1. uma VM/instância descartável com UIDs efêmeros e limites;
2. config/trust/registry e signer sintético materializados fora do serviço;
3. a execução do driver no socket real e o wrapper de UID/signer;
4. a matriz de homologação executada e revisada por B.

O driver P9.2 foi implementado e testado **host-only**; a jornada no socket
real, o wrapper de UID e H3b multi-UID continuam pendentes de janela. O caminho
ASSISTED real continua **BLOQUEADO por desenho**: não há ponte operacional entre
o lease do serviço e um dispatcher OpenCode real, e não há autorização para
POST. DELEGATED permanece fora do escopo.

## 2. Inventário e classificação

| Classe | Itens | Situação |
|---|---|---|
| **PRONTO** | serviço, ledger 1.1, autoridade V2, adapter P14B, `SimulatedTransport`, `PilotExecutor`, preflight/manifesto/executor OpenCode (modo fixture), driver P9.2, testes | implementado/testado; 106/106 cross-UID histórico; **745 testes** (14 P9.2 host-only) |
| **SÓ CONFIG** | unidade systemd, `trust-service.example.json`, `consumers.example.json`, `trust-store.example.json`, paths/permissões, UIDs 9101–9106, wrapper de UID/signer | exemplos e driver existem; faltam valores, wrapper e material revisado |
| **FALTA** | VM descartável, cloud-init/provisionamento, limites aplicados, backup/limpeza versionados, wrapper multi-UID, H3b real | preparação delimitou; não executado nesta rodada |
| **BLOQUEADO** | H0–H2, H11, H13, H15, H3b multi-UID real, ASSISTED real | exigem VM, socket real, autorização ou revisão |
| **FORA** | OpenCode real, credencial real, permissão efetiva de sessão, dispatcher/lease, DELEGATED | exige decisão/autorização separada |

### 2.1 Detalhe por componente

- **Serviço de confiança:** pronto; `transmit` retorna `TRANSPORT_DISABLED`;
  `--check` exige processo sob o UID/GID do serviço; socket/estado/âncora
  privados.
- **Ledger/autoridade:** prontos; autorização V2, revogação, atestação,
  lease, replay, UNKNOWN e quarentena cobertos.
- **Transporte sintético:** `SimulatedTransport` e `run_synthetic_journey`
  prontos; `coord_pilot_cli.py dry-run` faz 1 POST falso e reconcilia.
- **Driver P9.2:** `coord_ops_p9_pilot.py` implementa os 9 passos, signer
  helper separado, matriz H0–H15 + H3b, `summary.json` sanitizado e fail-closed;
  14 testes host-only com cliente falso. Não é certificação do socket real.
- **Supervisor/Agente S:** `.opencode/agents/supervisor.md`,
  `docs/COORDINATOR.md` e `test_supervisor.py` (167) definem a política
  SHADOW/ASSISTED/DELEGATED, mas não há motor operacional; o piloto usa
  fixture determinística, não LLM.
- **OpenCode:** `OpenCodeTransport` e `ReadOnlyHttpClient` prontos para
  preflight read-only; `POST` real não foi exercitado nem autorizado.

## 3. Separação de modos

| Modo | Quem decide | Transporte | P9 |
|---|---|---|---|
| **SHADOW** | humano/coordenação | nenhum | atual; apenas leitura/proposta |
| **ASSISTED sintético** | operador autoriza missão fictícia | sintético em memória; 0 POST | **driver pronto; janela não executada** |
| **ASSISTED real** | humano autoriza sessão e manifesto | OpenCode real, um POST | **BLOQUEADO**; falta ponte e autorização |
| **DELEGATED** | Agente S sob contrato | real, futuro | **FORA**; exige contrato/certificação |

A homologação sintética **não** altera o modo global SHADOW; é uma janela de
teste isolada, não uma transição de autoridade.

## 4. Caminho mínimo para OpenCode

1. **Agora:** manter SHADOW; executar somente o piloto sintético no serviço.
2. **Futuro ASSISTED real:** instância OpenCode privada, `preflight --real`
   read-only, credencial experimental fornecida pelo operador, política de
   permissões observada e aprovação humana vinculada ao manifesto exato.
3. **Antes de qualquer POST real:** definir a fronteira dispatcher/lease. A
   opção mais defensável é um dispatcher separado, com UID próprio, recebendo
   um lease de uso único do serviço e executando um único POST; o executor não
   deve reutilizar autorização nem manter a chave privada.
4. **Nunca:** habilitar `real_transport` por flag, mock, fixture ou alteração
   silenciosa; `UNKNOWN` não reenvia; a reconciliação exige evidência.

A ponte dispatcher/lease é a menor lacuna de arquitetura; não foi implementada
nesta rodada. Alternativa de menor escopo — executor atual chamando `assert`
antes do POST — deixa janela residual e deve ser tratada como decisão de risco,
não como equivalente. Para a janela P9.2, o driver roda host-only; o socket real
exige o wrapper de UID e o signer separado.

## 5. Lacunas e decisões solicitadas

| ID | Lacuna | Menor alteração candidata | Decisão |
|---|---|---|---|
| G-P9-1 | Sem VM/provisionamento reproduzível | runbook + cloud-init (sem código de produto) | aprovar janela e provedor |
| G-P9-2 | Driver único no socket real e wrapper de UID/signer | driver P9.2 implementado; falta wrapper executor/signer e execução H3 real | aprovar janela e wrapper |
| G-P9-3 | Separação de papéis do registry não certificada cross-UID | H3b host-only coberto; falta delta focal cross-UID real | aprovar delta focal antes do H3b real |
| G-P9-4 | OpenCode real sem ponte lease/dispatcher | dispatcher separado, um lease/um POST | adiar para ASSISTED real |
| G-P9-5 | Sem autorização para execução | P9-OPS/P9-SYNTH + P9-REVIEW | solicitar ao mantenedor/B |

## 6. Orçamento e critérios

- VM 2 vCPU/2 GiB/8 GiB; estado < 256 MiB; logs < 50 MiB; 0 rede externa; LLM
  US$ 0 (proibido); infraestrutura com teto a autorizar; janela 90 min/hard
  120; preparação de engenharia 7–10 h fora da janela; 1 operador + 1
  aprovador + B.
- PASS: evidência observada e sanitizada por passo/código, sem transporte real
  e sem resíduo.
- FAIL: estado/código divergente, exceção escapada, path/traceback, dois leases,
  reenvio de UNKNOWN, quarentena ignorada.
- BLOCKED: UID/VM/config/signer/driver/autorização ausentes; nunca converter
  ausência em PASS.

A matriz completa, comandos propostos e rollback estão no runbook.

## 7. Handoff para B

B deve revisar, nesta ordem:

1. integridade do inventário e das classes PRONTO/CONFIG/FALTA/BLOQUEADO/FORA;
2. isolamento proposto (VM, UIDs, paths, rede, limites, signer separado);
3. fixture dos 9 passos e os cenários negativos;
4. matriz PASS/FAIL/BLOCKED e critérios de parada;
5. orçamento, riscos, backup/rollback e limpeza;
6. fronteira de modos e o corte ASSISTED real/DELEGATED.

Parecer esperado: **prontidão para a janela**, com lista de bloqueios e
autorizações necessárias — não certificação de transporte real. Handoff
B/P9.3: revisar o delta do driver, os 14 testes host-only, os contadores
578/745, A1–A4 e o wrapper de UID/signer antes de qualquer execução no socket.

## 8. Ajustes A1–A4 e delta P9.2

| Ajuste | Aplicação |
|---|---|
| **A1** | Preparação de engenharia 7–10 h separada da janela de 90 min/hard 120; cronograma por passo; `RuntimeMaxSec=1800` não pode encerrar o serviço entre `assert` e reconciliação; interrupção vira `UNKNOWN`; não iniciar missão sem tempo para concluir |
| **A2** | LLM US$ 0 proibido; infraestrutura da VM tem teto/responsável a autorizar; nenhum custo externo nesta rodada |
| **A3** | H3 exige jornada única no socket AF_UNIX real, signer separado para autorização **e** atestação, chave inacessível ao executor, lease único, `transport_real=false`, 0 POST e códigos exatos nos negativos; H0/H2 incluem a chave inacessível |
| **A4** | Legenda inclui **BLOQUEADO**; preflight `getent passwd/group` dos UIDs 9101–9106; seção “observado vs pendente” separa CI/PR de VM/socket |

**Delta P9.2 (host-only):**

- `.opencode/scripts/coord_ops_p9_pilot.py` — driver de 9 passos, `ServiceClient`,
  `SimulatedTransport`, signer helper subprocessual, summary sanitizado,
  matriz H0–H15 + H3b, lease único, UNKNOWN sem reenvio, `--mode synthetic` e
  `--scenario positive|all`.
- `.opencode/scripts/test_coord_ops_p9_pilot.py` — 14 testes host-only com
  cliente falso; H3b usa clientes por papel; nenhum teste é certificação do
  socket real.
- Contadores: **578 discover + 167 supervisor = 745**; suíte do serviço
  permanece **41**. Nenhuma alteração em serviço, ledger, autoridade ou
  transporte produtivo.

## 9. Referências

- `docs/operations/trust-service-deployment.md`
- `docs/operations/coord-ops-p9-isolated-homologation.md`
- `.opencode/scripts/coord_ops_p9_pilot.py`
- `.opencode/scripts/test_coord_ops_p9_pilot.py`
- `docs/COORDINATOR.md` §2.1 e §6.1
- `.opencode/TRUST-SERVICE.md`
- `.opencode/PILOT.md`
- `.opencode/P14B-AUTHORITY-ADAPTER.md`
- `.opencode/COMMUNICATION.md`
- `.opencode/deploy/`
