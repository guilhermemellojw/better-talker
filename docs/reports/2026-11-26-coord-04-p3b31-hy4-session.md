# COORD-04 / P3B.3.1 — Criação experimental com HY4 Flash Free (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Certificações:** P9 `issuecomment-7191305574`; P8 `issuecomment-7190600151`.
**Autorização humana:** substituição do modelo experimental para `opencode/hy4-flash-free`, uma inicialização, uma criação de sessão, nenhum prompt/LLM.

> **Uma sessão experimental real foi criada com sucesso, em uma única tentativa.** Nenhum prompt foi enviado, nenhum LLM executado, nenhum POST adicional, nenhuma transmissão, manifesto ou `PilotApproval`. Sem autocertificação.

---

## A. HEAD inicial, código e final

| Item | Valor |
|---|---|
| HEAD inicial | `c94eee529c57e408b7c9727652b7a3d5c8bd6bb6` |
| HEAD do código corretivo | `d6e8d1f3f74eefd6ec05d7a962cd0dba29365685` |
| HEAD final | SHA corrente; documentação posterior não altera código |
| Main observada | `02b89856837d03cbaa11d2f30d5931b4d7e43bd3` |

Digests operacionais registrados **antes** da inicialização: `coord_checkpoint.py 55f0df70…`, `coord_communication.py e972edec…`, `coord_pilot.py 024fda25…`, `coord_pilot_cli.py 255e34b7…`. Push sem force-push.

## B. Alteração focal do modelo

`pilot.EXPERIMENTAL_MODEL = "opencode/hy4-flash-free"` é a fonte única; `coord_pilot_cli.ALLOWED_FREE_MODEL` a reexporta. `canonical_model_ref()` deriva a representação do contrato observado: `provider/id`, com sufixo `#variant` apenas para variante explícita diferente de `none` (sem presumir `#default`). `expected_model`, cadastro, manifesto, validação de destinatário, fixtures e testes relacionados foram atualizados. Modelo com "free" no nome **não** é aceito por substring — exige-se correspondência exata. Configuração de agentes produtivos e política futura de seleção dinâmica não foram alteradas.

## C. Testes e CI

**332/332** com rede bloqueada: `test_supervisor` 167 + `unittest discover` 165 (incluindo **9 novos** `test_pilot_model`: A–I). Verificados: HY4 aceito; Hungerg não autorizado; variante divergente bloqueada; modelo pago e `*-free-paid` bloqueados; modelo ausente bloqueado; nenhuma substituição automática; endpoint/autenticação intactos; cliente somente leitura bloqueando POST; manifesto e transmissão bloqueados. Demos normal e `UNKNOWN`: `COMPLETED`, `transmit_calls=1`; `dry-run`: `source=fixture`, `http_posts=1`, `real_calls=0`. CI `propose` = `success` no run `45817252976` (`head_sha=d6e8d1f3`).

## D. Correção documental

`PILOT.md` e o relatório P3B.3 foram corrigidos: `POST /api/session`, `operationId=session.create`, zero parâmetros de query, `requestBody` obrigatório, nenhuma propriedade superior obrigatória (oito disponíveis ≠ obrigatórias), `location.directory` no corpo, `Model.Ref` com `id`/`providerID`, `Permission.Rule` com `action`/`resource`/`effect`, nenhum prompt inicial. Distinção registrada entre payload mínimo do schema (`{}`) e payload mínimo **operacional** (título, agente, modelo, location, permissões). Inventário corrigido: **18 modelos — 11 `opencode` com custo listado zero e 7 `groq`**.

## E. Sanitização anterior à inicialização

Lançador validado: `unset OPENCODE_PASSWORD OPENCODE_SERVER_PASSWORD OPENCODE_SERVER_USERNAME` antes de exportar apenas a credencial experimental; segredo em arquivo `0600`, lido por `$(cat …)` (jamais em argv, logs, manifestos, relatórios ou histórico). HOME/XDG isolados em diretório persistente `~/.local/state/better-talker/coord-pilot/experimental-instance` (`0700`), porta `51321` livre, banco pré-existente vazio. **Uma única inicialização** — sem repetir o desvio de duas inicializações da missão anterior.

## F. Autenticação e isolamento

PID efetivo do servidor `1003188` (dono do listener, obtido por `exec` no lançador); `opencode serve --hostname 127.0.0.1 --port 51321`, sem `--service`/`--standalone`. Listener exclusivamente em loopback. `/proc/1003188/environ` filtrado continha apenas `OPENCODE_SERVER_PASSWORD` entre variáveis de autenticação. Basic auth com o segredo experimental → **HTTP 200**. HOME/XDG efetivos no diretório experimental; banco `.../experimental-instance/data/opencode/opencode.db`, distinto do produtivo. Inventário inicial `{"data":[],"cursor":{"previous":null,"next":null}}`. Serviço compartilhado `http://127.0.0.1:49374` e processos `178143`/`387110` preservados.

Preflight COORD-04 sem destinatário: `source=real`, `service_contacted=true`, `authentication_succeeded=true`, `http_response_valid=true`, `real_calls=4`, `fallback=none`, zero POST; instância `6682b7f1…`, `opencode_project_id=320134d7…`, repositório Git validado, location/canonical coerentes.

## G/H. Modelo gratuito e janela de contexto

`GET /api/model`: `opencode/hy4-flash-free` presente, `providerID=opencode`, `id=hy4-flash-free`, `cost=[{input:0,output:0,…}]`, variantes `none`/`high`. Janela informada pelo provedor: **context 1.048.576 tokens**, input 983.040, output 65.536 — compatível com a diretriz FREE FIRST / 1M PREFERRED. Nenhuma inferência foi executada para testar o modelo.

## I. Contrato real e política de permissões

`GET /openapi.json` reconfirmou `POST /api/session` (`session.create`), zero query params, corpo obrigatório e as oito propriedades opcionais. `GET /api/agent` listou `build` (primary) entre outros; agente escolhido: **`build`**. Política enviada: `[{action:"*",resource:"*",effect:"deny"},{action:"question",resource:"*",effect:"allow"}]` — vocabulário real `action/resource/effect` com `effect ∈ {allow,deny,ask}`. Isso **não** comprova enforcement; permissões efetivas permanecem não observadas.

## J/K. Intenção persistente, tentativa única e sessão criada

Intenção gravada em `creation-intent.json` (`0600`): referência `P3B.3.1-mantenedor-2026-11-26`, endpoint, instância, modelo aprovado, agente, location, payload sanitizado, `sha256=e88797ad…`, horário UTC, `attempt_limit=1`, sem prompt/LLM. **Exatamente um POST** `post /api/session` pelo caminho administrativo delimitado: `RC=0`. Resposta: **`ses_de83b0620ffeMIQP5tvH7MLFEx`**, `projectID=320134d76dfecf3ccd6e6939e946c9f18d32a4f3`, agente `build`, modelo `{"id":"hy4-flash-free","providerID":"opencode","variant":"none"}`, `cost=0`, `tokens` zerados, `time.created/updated=1795695114726`, título e permissões devolvidos, location experimental. Nenhum timeout, ambiguidade, repetição, `resume`, `wait` ou escrita adicional.

## L/M. Cadastro, metadados, disponibilidade e evidências

Cadastro local em `~/.local/state/better-talker/coord-pilot/real/experimental-session.json` (`0600`), sem `--permissions-confirmed`, sem substituição. Novo preflight com destinatário: `session_found=true`, `recipient_authorized=true`, `session_model=opencode/hy4-flash-free` confirmado, agente/id/hash/location coerentes. `active={}`. **Correção de atribuição:** esse preflight fez 5 GETs e **não** consultou o inbox; o `inbox` vazio (`{"data":[]}`) veio da leitura separada preservada em `verify-inbox.json`. A política de permissões retornada é `[{"action":"*","resource":"*","effect":"deny"},{"action":"question","resource":"*","effect":"allow"}]` — argumentos `"*"`, não strings vazias. **Lacunas:** a sessão não expõe `outcome` nem busy/idle (apenas `created/updated`); `repositoryID` não aparece nos metadados da sessão; permissões são apenas as solicitadas/devolvidas, não comprovadas. `prepare --real` permanece **bloqueado**, sem manifesto: `disponibilidade desconhecida`, `permissões efetivas não confirmadas`, `session_repository_id divergente`. `PilotApproval` não emitida; transmissão bloqueada.

## N. Persistência e futura revinculação

Banco experimental preservado com exatamente uma sessão. A identidade da instância incorpora PID: uma futura inicialização invalidará o vínculo cadastrado. Recomenda-se revinculação controlada da **mesma** sessão via novo preflight e confirmação de instância — nunca criar outra sessão para resolver divergência de PID.

## O. Consumo de recursos

Antes: MemAvailable 2.467.516 kB, SwapFree 13.190.840 kB, carga 1.85, 16 GiB livres. Durante (PID `1003188`): RSS 269.492 kB (~263 MiB), CPU 6,6%, MemAvailable 2.328.184 kB, SwapFree 13.187.768 kB, carga 1.76, 16 GiB. Depois: MemAvailable 2.365.728 kB, SwapFree 13.188.024 kB, carga 1.97. Sem builds, Gradle, ADB, LLM ou daemons adicionais.

## P. Encerramento e preservação de A–E

Titularidade confirmada; `kill 1003188` (sem `pkill`/`killall`). Processo ausente, listener `51321` indisponível, serviço compartilhado e PIDs `178143`/`387110` inalterados, banco/cadastro/sessão preservados. Zero prompts, zero LLM, zero POST adicional. Nenhuma sessão, agente, modelo, credencial, mensagem, worktree, configuração global, Android, HealthAgentForms ou serviço de A–E foi tocado; nenhum merge, ASSISTED/DELEGATED ou missão posterior iniciada.

## Q. Menor conjunto de ajustes para a primeira transmissão

1. Obter evidência observável de disponibilidade (busy/idle/active ou equivalente documentado) e de permissões efetivas — sem afirmá-las apenas por solicitação/`outcome`/`time`.
2. Resolver a ausência de `repositoryID` nos metadados da sessão com mapeamento autorizado (não fabricar).
3. Recalcular manifesto sobre o HEAD/digests vigentes após essas correções, com validade UTC curta, e obter aprovação humana explícita.
4. Revincular a instância ao reiniciar (PID muda), preservando a mesma sessão.
5. Somente então autorizar a transmissão — decisão separada.

## R. Diretriz FREE FIRST / 1M PREFERRED

Registrada em `PILOT.md` para COORD-05/06/07: preferir modelos gratuitos com janela ≥1M; rotação diante de limite/indisponibilidade feita manualmente hoje, a automatizar no futuro com descoberta dinâmica, preservação de contexto/evidências, ausência de reenvio cego e proteção contra gastos. **Nenhuma rotação automática foi implementada**; o piloto permanece preso a `opencode/hy4-flash-free`.
