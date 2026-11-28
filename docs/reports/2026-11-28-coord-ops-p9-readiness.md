# COORD-OPS/P9 — prontidão operacional isolada (relatório de inventário)

**Estado:** Draft documental; **nenhuma execução, instalação, sudo, serviço,
piloto, dispatch, deploy, merge, transporte real, ADB ou LLM pago** nesta
rodada. O runbook está em
`docs/operations/coord-ops-p9-isolated-homologation.md`.

**Baseline certificada:** `ab9f08c4b233ea8d3c273084b720f8ffa827d022` (main,
PR #87 integrado). **Certificação cross-UID:** 106/106. **Suíte Python:** 731.
`real_transport=false`.

## 1. Sumário executivo

A infraestrutura de fronteira está pronta para ser **homologada em ambiente
isolado**, mas ainda não está provisionada. O serviço, o ledger 1.1, a
autoridade assinada, o transporte sintético e o pipeline do piloto estão
implementados e cobertos. O que falta é:

1. uma VM/instância descartável com UIDs efêmeros e limites;
2. config/trust/registry e signer sintético materializados fora do serviço;
3. um driver único dos 9 passos do piloto no socket do serviço — ou execução
   manual registrada como tal;
4. a matriz de homologação executada e revisada por B.

O caminho ASSISTED real continua **BLOQUEADO por desenho**: não há ponte
operacional entre o lease do serviço e um dispatcher OpenCode real, e não há
autorização para POST. DELEGATED permanece fora do escopo.

## 2. Inventário e classificação

| Classe | Itens | Situação |
|---|---|---|
| **PRONTO** | serviço, ledger 1.1, autoridade V2, adapter P14B, `SimulatedTransport`, `PilotExecutor`, preflight/manifesto/executor OpenCode (modo fixture), testes | implementado/testado; 106/106 cross-UID; 731 testes |
| **SÓ CONFIG** | unidade systemd, `trust-service.example.json`, `consumers.example.json`, `trust-store.example.json`, paths/permissões, UIDs 9101–9106 | exemplos existem; faltam valores e material revisado |
| **FALTA** | VM descartável, cloud-init/provisionamento, limites aplicados, backup/limpeza versionados, driver único do piloto sintético | preparação desta rodada delimitou, mas não implementou |
| **FORA** | OpenCode real, credencial real, permissão efetiva de sessão, dispatcher/lease, DELEGATED | exige decisão/autorização separada |

### 2.1 Detalhe por componente

- **Serviço de confiança:** pronto; `transmit` retorna `TRANSPORT_DISABLED`;
  `--check` exige processo sob o UID/GID do serviço; socket/estado/âncora
  privados.
- **Ledger/autoridade:** prontos; autorização V2, revogação, atestação,
  lease, replay, UNKNOWN e quarentena cobertos.
- **Transporte sintético:** `SimulatedTransport` e `run_synthetic_journey`
  prontos; `coord_pilot_cli.py dry-run` faz 1 POST falso e reconcilia.
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
| **ASSISTED sintético** | operador autoriza missão fictícia | sintético em memória; 0 POST | **preparado para homologação** |
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
não como equivalente.

## 5. Lacunas e decisões solicitadas

| ID | Lacuna | Menor alteração candidata | Decisão |
|---|---|---|---|
| G-P9-1 | Sem VM/provisionamento reproduzível | runbook + cloud-init (sem código de produto) | aprovar janela e provedor |
| G-P9-2 | Sem driver único dos 9 passos no socket | script de homologação ~250–350 linhas + teste; signer separado para autorização/atestado; sem tocar serviço/ledger | aprovar script ou aceitar execução manual |
| G-P9-3 | Separação de papéis do registry não certificada cross-UID | teste focal de `propose/authorize` pelo coordenador e `reserve/assert/...` pelo executor | aprovar delta focal antes do piloto |
| G-P9-4 | OpenCode real sem ponte lease/dispatcher | dispatcher separado, um lease/um POST | adiar para ASSISTED real |
| G-P9-5 | Sem autorização para execução | P9-OPS/P9-SYNTH + P9-REVIEW | solicitar ao mantenedor/B |

## 6. Orçamento e critérios

- VM 2 vCPU/2 GiB/8 GiB; estado < 256 MiB; logs < 50 MiB; 0 rede externa; 0
  LLM; 90–120 min; 1 operador + 1 aprovador + B.
- PASS: evidência observada e sanitizada por passo/código, sem transporte real
  e sem resíduo.
- FAIL: estado/código divergente, exceção escapada, path/traceback, dois leases,
  reenvio de UNKNOWN, quarentena ignorada.
- BLOCKED: UID/VM/config/signer/driver/autorização ausentes; nunca converter
  ausência em PASS.

A matriz completa, comandos propostos e rollback estão no runbook.

## 7. Handoff para B

B deve revisar, nesta ordem:

1. integridade do inventário e das classes PRONTO/CONFIG/FALTA/FORA;
2. isolamento proposto (VM, UIDs, paths, rede, limites, signer separado);
3. fixture dos 9 passos e os cenários negativos;
4. matriz PASS/FAIL/BLOCKED e critérios de parada;
5. orçamento, riscos, backup/rollback e limpeza;
6. fronteira de modos e o corte ASSISTED real/DELEGATED.

Parecer esperado: **prontidão para a janela**, com lista de bloqueios e
autorizações necessárias — não certificação de transporte real.

## 8. Referências

- `docs/operations/trust-service-deployment.md`
- `docs/operations/coord-ops-p9-isolated-homologation.md`
- `docs/COORDINATOR.md` §2.1 e §6.1
- `.opencode/TRUST-SERVICE.md`
- `.opencode/PILOT.md`
- `.opencode/P14B-AUTHORITY-ADAPTER.md`
- `.opencode/COMMUNICATION.md`
- `.opencode/deploy/`
