# COORD-04 / P3B.1 — Primeiro preflight real autorizado (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Autorização:** uma única instância privada temporária, somente leitura, condicionada à certificação P6 e à revisão do procedimento de inicialização. **Sem POST, sem criação de sessão, sem prompt, sem LLM.**
**Certificação anterior:** `issuecomment-7179597704` (297/297; endpoint e cliente somente leitura certificados).

> Nenhuma transmissão, sessão, prompt, build, merge ou alteração de código foi executada. O serviço compartilhado e todos os processos preexistentes foram preservados. **Sem autocertificação.**

---

## A. HEAD e preflight do notebook

| Item | Observado antes da inicialização |
|---|---|
| Worktree certificado | `657c2a7cb345d8731e89d895968f7312246b12af`; apenas `__pycache__` untracked |
| HEAD do código | `582ba295690b09393b9ca012b208e8778527d19b` (inalterado) |
| Main observada | `02b89856837d03cbaa11d2f30d5931b4d7e43bd3` |
| OpenCode | `opencode v2.3.2` |
| Serviço compartilhado | `http://127.0.0.1:49374` (em execução, PID `387110`, `--service`) — **não tocado** |
| Processos preexistentes | `178143` (`serve --hostname 127.0.0.1 --port 35057`), `387110` (`serve --service`) e seus PTY daemons `179193`, `387343` |
| Portas em uso | `49374` (compartilhado), `35057` (existente), `631`, `5037`, `37085`, `7070`, `53317`, DNS; nenhuma em `512xx` |
| MemAvailable / SwapFree | `2.299.976 kB` (~2,2 GiB) / `13.283.768 kB` (~12,7 GiB) |
| Disco | 19 GiB livres (92% usado) |
| Carga | `1.66 1.67 1.51` (16 CPUs) |

Critérios de bloqueio avaliados: recursos suficientes, porta livre, sem conflito de identidade → autorizado a prosseguir. Nenhum conteúdo de mensagem ou credencial foi lido; nenhum processo existente foi interrompido.

## B. Comando efetivamente executado

```sh
D=/tmp/opencode/coord-p3b1-private
mkdir -p "$D"/{home,data,config,cache,state,logs}
chmod 700 "$D" "$D"/*                       # diretórios privados, modo 0700
cd /home/guilherme/Documentos/better-talker-coord-supervisor-shadow
env HOME="$D/home" \
    XDG_DATA_HOME="$D/data" XDG_CONFIG_HOME="$D/config" \
    XDG_CACHE_HOME="$D/cache" XDG_STATE_HOME="$D/state" \
    nohup /home/guilherme/.opencode/bin/opencode serve \
          --hostname 127.0.0.1 --port 51234 </dev/null \
          >"$D/logs/serve.log" 2>&1 &
```

Uma única instância. **Sem `--service`, sem `--standalone`**, sem cópia de banco/config/credencial produtiva. Log exclusivo: `$D/logs/serve.log` (`server listening on http://127.0.0.1:51234`).

## C. PID, endpoint e isolamento

| Verificação | Evidência |
|---|---|
| PID do servidor | `391933` (o `$!` inicial capturou o shell invólucro `391931`; o PID correto foi identificado pelo dono do listener e registrado em `$D/pid`) |
| Linha de comando | `/home/guilherme/.opencode/bin/opencode serve --hostname 127.0.0.1 --port 51234`, PPID `391931` |
| Listener | exclusivamente `127.0.0.1:51234`, `users:(("opencode",pid=391933))`; nenhum bind em `0.0.0.0`/`::` |
| Endpoint canônico | `http://127.0.0.1:51234` (calculado por `pilot.canonical_endpoint`) |
| HOME/XDG efetivos | lidos de `/proc/391933/environ` filtrando apenas chaves `HOME`/`XDG_*`: todos apontam para `$D/home|data|config|cache|state` |
| Banco experimental | FDs abertos apenas `/tmp/opencode/coord-p3b1-private/data/opencode/opencode.db` (+ `-wal`, `-shm`); **nenhum banco produtivo** |
| Sessões produtivas | nenhuma: inventário experimental vazio (item D) |
| Worktree experimental | `c9253a90da627989c6431fbdf31cf757fd60c765`, `git status` limpo antes e depois |

## D. Inventário experimental

- `GET /api/session` → `{"data":[],"cursor":{"previous":null,"next":null}}` — banco inicialmente vazio, **como esperado**. Nenhuma sessão foi criada para preenchê-lo.
- `GET /api/session/ses_defc05853ffeej4fPXrp7frx4x` → `HTTP 404 Not Found`, corpo `{"_tag":"SessionNotFoundError","sessionID":"ses_defc05853ffeej4fPXrp7frx4x",...}` — ausência esperada da sessão fixada, **não falha de comunicação HTTP**.

## E. GETs reais autorizados e resultados

Todos executados via `opencode api --server http://127.0.0.1:51234 get <path>` com HOME/XDG isolados; registros em `$D/logs/get-*.json`.

| GET | Resultado observado |
|---|---|
| `/api/info` | `{"version":"2.3.2","pid":391933,"hostname":"guilherme-vaiofe16","urls":["http://127.0.0.1:51234"],"paths":{"tmp":"/tmp/opencode"},"capabilities":{"persistentPty":true}}` |
| `/api/location` (nu e com `?location[directory]=…`, ambos idênticos) | `{"directory":"/home/guilherme/Documentos/better-talker-coord-supervisor-shadow","project":{"id":"320134d76dfecf3ccd6e6939e946c9f18d32a4f3","directory":"…/better-talker-coord-supervisor-shadow","canonical":"/home/guilherme/Documentos/better-talker"}}` — sem campo de repositório |
| `/api/session` | `data` lista vazia + `cursor` com `previous`/`next` nulos |
| `/api/session/active` | `{"data":{}}` |
| `/api/session/<id experimental>` | 404 `SessionNotFoundError` |

Identidade da instância calculada pelo adaptador sobre os campos observados: `31e150427323deaad406624899a332529bbd70a191a6ea8b18dfd1f78b8ee736`.

**Status HTTP:** a CLI imprime o status no stderr somente em erro (`HTTP 404 Not Found`). Nos sucessos, o status é inferido do corpo JSON bem formado e ausência de `_tag` de erro — não foi fabricado. Sondagem direta sem autenticação retornou `401 Unauthorized`, `WWW-Authenticate: Basic realm="Secure Area"`. Nenhum dado sensível foi registrado; nenhum endpoint de escrita foi chamado.

## F. Resultado do cliente COORD-04

Comando: `python3 .opencode/scripts/coord_pilot_cli.py preflight --real --endpoint http://127.0.0.1:51234`.

- Primeira tentativa executada com o guarda de rede dos testes (`PYTHONPATH=/tmp/opencode/coord64-noNet`): `REDE REAL BLOQUEADA`, zero chamadas. **Não é evidência da API**; corrigida na sequência, sem alteração de código.
- Segunda tentativa (sem guarda), resultado real:

```json
{"source":"real","mode":"real","real_calls":1,"service_contacted":false,
 "fallback":"none","observed":{"calls":["GET /api/info"],
 "error":"TransportError: GET /api/info falhou: 401","service_contacted":false},
 "blockers":["conexão real falhou; nenhum fallback para fixture","instância não observado; …","serviço real não contactado"]}
```

Zero POST, nenhum fallback para fixture, nenhum valor de fixture reapresentado como observação. `service_contacted=false` reflete a semântica atual do código (somente resposta 200), **não ausência de conexão TCP**. Causa raiz observada: o cliente real do adaptador não envia autenticação e a API exige Basic auth.

## G. Comparação API real × fixtures

| Item | A. Observado na API | B. Documentado, não observado | C. Hipótese de fixture | D. Indisponível |
|---|---|---|---|---|
| `/api/info` | `version`, `pid`, `hostname`, `urls`, **novos** `paths`, `capabilities` | — | somente 4 campos | — |
| `/api/location` | `directory`, `project.id` hash, `project.canonical` ≠ `directory`, sem repositório | parâmetro `location[directory]` aceito, mesmo resultado | `project.id == "better-talker"`, campo `repository` | repositório efetivo |
| `/api/session` | `data` vazio + `cursor` | — | só `data` | sessões reais |
| `/api/session/active` | `data {}` | — | presença por chave | — |
| Sessão experimental | 404 `SessionNotFoundError` | metadados `agent`, `model`, `time`, `outcome`, `location` | `projectID`, `repositoryID`, modelo hungerg-free | agente, modelo, disponibilidade, inbox |
| Mensagens/respostas | — | envelope `id`/`agent`/`sessionID`/`reply_to` | `BT-COORD-REPORT:v1` | estrutura real (sem sessão) |
| Autenticação | 401 + `WWW-Authenticate: Basic` | — | GET sem auth | mecanismo permitido de credencial experimental |

## H. Divergências concretas

1. **Autenticação obrigatória**: GET direto do adaptador → 401; a CLI oficial autentica, o cliente COORD-04 não.
2. **`project.id` é um hash**, não `better-talker`; `project.canonical` (`…/better-talker`) difere de `directory` (`…/better-talker-coord-supervisor-shadow`); não há campo de repositório.
3. `/api/info` contém `paths` e `capabilities` além dos 4 campos usados na identidade da instância.
4. `/api/session` inclui `cursor` além de `data`.
5. A sessão fixada `ses_defc05853ffeej4fPXrp7frx4x` não existe no banco isolado (404).
6. `service_contacted=false` apesar do contato TCP bem-sucedido (semântica baseada em status 200).
7. A ressalva certificada de **timestamps fixos em `_prepare_real()`** permanece; nenhum manifesto apresentável foi gerado e nenhuma `PilotApproval` foi emitida.

## I. Consumo de recursos

| Momento | MemAvailable | SwapFree | Carga | Disco | Servidor |
|---|---|---|---|---|---|
| Antes | 2.299.976 kB | 13.283.768 kB | 1.66 / 1.67 / 1.51 | 19 GiB livres | — |
| Durante | 2.108.188 kB | 13.303.480 kB | 2.38 / 1.89 / 1.61 | 19 GiB livres | RSS 314.408 kB (~307 MiB), CPU 9,6% |
| Depois | 2.068.172 kB | 13.306.808 kB | 2.32 / 1.92 / 1.62 | 19 GiB livres | processo encerrado |

Latências dos GETs autenticados: `/api/info` 154 ms, `/api/location` 149 ms, `/api/session` 148 ms, `/api/session/active` 158 ms. Sem pressão significativa; não houve necessidade de encerramento antecipado. Nenhum build concorrente.

## J. Encerramento confirmado

Identidade/titularidade conferida antes do sinal (`cmdline` exata, PPID `391931`, listener na porta). Encerramento com **`kill 391933`** — sem `pkill`, `killall` ou comando genérico.

- Processo `391933`: **ausente**.
- Listener `127.0.0.1:51234`: **indisponível**.
- Serviço compartilhado: `opencode service status` → `http://127.0.0.1:49374` (inalterado, PID `387110`).
- Processos `178143` (porta 35057) e `387110`: **preservados**.
- Worktree experimental: `c9253a90`, limpo; banco produtivo não acessado.
- Nenhuma sessão criada, nenhum prompt enviado, nenhum POST executado.
- Logs e evidências preservados em `/tmp/opencode/coord-p3b1-private/logs/`.

## K. Preservação dos agentes A–E

Nenhuma sessão, prompt, agente, modelo, credencial, configuração global, mensagem ou worktree de A–E foi acessado ou modificado. Nenhuma alteração de código, build, ADB, merge, HealthAgentForms, ASSISTED/DELEGATED. GitHub CLI não foi reautenticado. Nenhuma nova instância foi iniciada após o encerramento.

## L. Menor conjunto de ajustes necessários (para rodada futura — não executados)

1. Permitir autenticação no cliente real por mecanismo explicitamente autorizado e isolado (config/token do ambiente experimental injetado pelo operador); nunca ler/copiar credenciais produtivas nem fixar segredos no código.
2. Mapear `project.id` observado (hash) e manter `canonical` distinto de `directory`; obter `repository` de metadados autorizados, sem derivá-lo do nome do projeto.
3. Reconhecer o envelope `data` + `cursor` de `/api/session` e tratar `SessionNotFoundError` como "sessão ausente" (estado observado), não como falha HTTP.
4. Distinguir "TCP contactado" de "resposta 200" em `service_contacted`, preservando a contagem de GETs.
5. Corrigir os timestamps fixos em `_prepare_real()` e impedir emissão de manifesto apresentável sem sessão experimental e metadados completos.
6. Nenhuma dessas alterações foi feita nesta rodada operacional.

## M. Autorização específica para sessão experimental

**Necessária.** O banco isolado está vazio e a sessão fixada não existe (404). Avançar exige autorização explícita para criar **uma** sessão experimental na instância privada isolada (nunca no serviço compartilhado), registrar sua identidade efetiva e só então consultar metadados e formato de mensagens. Nenhuma criação foi realizada nesta missão.

## N. Parecer de prontidão para a primeira transmissão futura

**Ainda não pronto.** A comunicação HTTP real foi demonstrada pela CLI oficial (5 GETs, respostas estruturadas), e o isolamento foi verificado e encerrado com segurança; porém o cliente da COORD-04 não conseguiu autenticar (401), o mapeamento de projeto/location diverge da hipótese certificada, não há sessão experimental e a ressalva de timestamps do manifesto permanece. **Não avançar para transmissão**, não gerar `PilotApproval` e não iniciar outra instância automaticamente. Submeter este relatório e as divergências à revisão independente; toda autorização de escrita deve ser uma decisão separada e explícita do mantenedor.
