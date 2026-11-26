# COORD-04 / P3B.3 — Tentativa de criação da primeira sessão experimental: BLOQUEADA (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Certificação P8:** `issuecomment-7190600151` (323/323; bloqueadores F1/F2/F3/F4 fechados; F5 preservado).

> **Nenhuma sessão foi criada.** Nenhum POST de escrita foi executado. Nenhum prompt foi enviado e nenhum LLM foi executado. A autorização operacional foi utilizada apenas até o ponto em que uma condição obrigatória falhou: **o modelo gratuito autorizado não existe na instância isolada**. Registro submetido à decisão humana; sem autocertificação.

---

## A. HEAD e complementação do runbook

| Item | Valor |
|---|---|
| HEAD certificado | `65633bd1eb6981f5f4a4e46140b3b24a98d5f101` |
| HEAD do código | `cdced54d54b148d12ea96856f9dfba5a253c3826` (inalterado nesta missão) |
| HEAD vigente | `08f1f8fb9fbfa9f11ee51c58132b3d83eb712535` (somente `PILOT.md`) |

`PILOT.md` foi complementado **antes** de qualquer inicialização com: instância privada e HOME/XDG exclusivos, diretórios privados, bind em `127.0.0.1`, porta livre, PID efetivo do servidor, banco isolado, inventário vazio, limite de uma criação, proibição de prompt inicial, encerramento restrito ao PID criado e preservação do banco. Registrado explicitamente que `--permissions-confirmed` **não comprova** permissões observadas e **não foi utilizado**. `prepare --real` não foi executado; nenhum código produtivo foi alterado.

## B. Preflight do notebook

HEAD e digests dos quatro arquivos operacionais conferidos; apenas documentação alterada desde o código certificado. Worktree limpo (exceto `__pycache__` untracked). OpenCode `v2.3.2`. Serviço compartilhado `http://127.0.0.1:49374` (PID `387110`); instância preexistente `178143`/porta `35057` e PTY daemons — todos preservados. Nenhuma porta em `513xx`. MemAvailable `3.330.232 kB`, SwapFree `13.104.312 kB`, carga `0.61/0.52/0.64`, 17 GiB livres em `/` (93%). Sem conflito ou pressão de recursos.

## C. Instância privada e isolamento

Diretório persistente: `~/.local/state/better-talker/coord-pilot/experimental-instance` (`0700`), com `home`, `data`, `config`, `cache`, `state`, `logs`, segredo `auth.secret` (`0600`) e `serve.sh` (`0700`), fora de `/tmp`. Nada foi copiado do ambiente produtivo.

Primeira inicialização (PID `972327`) herdou `OPENCODE_PASSWORD` do ambiente, o que impediu o uso do segredo experimental: requisição Basic com o segredo experimental retornou **401**. Nenhuma escrita ocorreu; o PID foi encerrado explicitamente. A evidência levou à correção documentada do lançador (`unset OPENCODE_PASSWORD/OPENCODE_SERVER_PASSWORD/OPENCODE_SERVER_USERNAME` antes de exportar apenas a credencial experimental). A instância **efetiva** desta missão foi então iniciada uma única vez: **PID `974497`**, `opencode serve --hostname 127.0.0.1 --port 51321`, listener exclusivamente em `127.0.0.1`, HOME/XDG efetivos lidos de `/proc/974497/environ`, banco em `.../experimental-instance/data/opencode/opencode.db`, distinto do produtivo. Inventário inicial `{"data":[],"cursor":{"previous":null,"next":null}}`. Serviço compartilhado inalterado.

## D. Autenticação real do cliente COORD-04

Após a correção, `coord_pilot_cli.py preflight --real --endpoint http://127.0.0.1:51321 --auth-env COORD_PILOT_AUTH` (sem destinatário) retornou: `source=real`, `service_contacted=true`, `authentication_succeeded=true`, `http_response_valid=true`, `real_calls=4`, `fallback=none`, **zero POST**. Instância `45d1ab2ca516c027891de776934845879947a69d8b2dbd2cc4304e2af87cb398`, `opencode_project_id=320134d76dfecf3ccd6e6939e946c9f18d32a4f3`, repositório Git validado `guilhermemellojw/better-talker`, `project.canonical=/home/guilherme/Documentos/better-talker`, worktree experimental. Ausência de destinatário cadastrado era esperada e foi explicitamente reportada.

## E. Contrato observado em `/doc`

`GET /doc` devolveu HTML da aplicação; a especificação real está em **`GET /openapi.json`** (HTTP 200, OpenAPI 3.1.0, 142 paths). Contrato efetivo de criação: **`POST /api/session`**, `operationId=session.create`, **zero parâmetros de query**, corpo obrigatório com `id`, `parentID`, `title`, `agent`, `model`, `location`, `metadata`, `permissions`. `Model.Ref` exige `id` + `providerID` (**`id`, não `modelID`**); `Location.PublicRef.directory` vai **no corpo**; `Permission.Ruleset` é lista de `Permission.Rule{action, resource, effect}` com `effect ∈ {allow, deny, ask}`; `Session.ID` casa `^ses`. Não há prompt inicial obrigatório.

Divergências documentadas em relação ao runbook anterior: `directory` não é query (é `location.directory` no corpo) e a regra de permissão é `{action,resource,effect}`, não `{permission,pattern,action}`. Nenhum campo foi inventado; o endpoint de escrita **não** foi improvisado.

## F. Modelo e política de permissões

`GET /api/model` retornou 18 modelos (16 `opencode/*` gratuitos e 2 provedores `groq`). **O modelo autorizado `opencode-go/hungerg-free#default` não está presente.** Modelos gratuitos disponíveis incluem `opencode/hy4-flash-free`, `opencode/seaglass-alpha-free`, entre outros — nenhum é o autorizado, e substituí-los sem decisão humana violaria o congelamento de modelo e a proibição de fallback. `GET /api/agent` listou `build`, `general`, `explore`, `plan`, `supervisor` e agentes ocultos; a política de ferramentas é configurável pelo ruleset confirmado, mas a ausência do modelo é **bloqueante**. Nenhum LLM foi executado para testar disponibilidade.

## G. Tentativa única de criação

**Não executada.** Nenhum POST de criação, nenhum payload enviado, nenhuma segunda tentativa. A intenção, o endpoint e a identidade da instância ficaram registrados neste relatório; como o bloqueio ocorreu antes da escrita, não há hash de payload a registrar.

## H. Identidade efetiva da sessão

Nenhuma sessão foi criada; portanto não há Session ID efetivo, ID interno de projeto de sessão, agente ou modelo de sessão a registrar.

## I. Metadados observados

Somente infraestrutura: `/api/info`, `/api/location`, `/api/session` (vazio), `/api/session/active`, `/api/model`, `/api/agent` e `/openapi.json`. Nenhum metadado de sessão foi consultado ou fabricado.

## J. Cadastro e preflight posterior

Nenhum cadastro foi criado no store real; `--permissions-confirmed` não foi usado; nenhuma substituição ocorreu. Novo preflight com destinatário não foi executado porque não há destinatário. `prepare --real` não foi executado; nenhum manifesto apresentável nem `PilotApproval` foi emitido.

## K. Evidências necessárias à correção de disponibilidade e permissões

Disponibilidade (`outcome`/`time`/`active`) e permissões efetivas só são observáveis após existir uma sessão — permanecem **indisponíveis**. Para a próxima etapa são necessárias: (1) decisão humana sobre o modelo autorizado ou instalação isolada que o disponibilize; (2) confirmação do ruleset `action/resource/effect` aceito na criação; (3) metadados reais da sessão criada; (4) evidência de que o agente escolhido é textual/restrito. Nada disso foi presumido.

## L. Persistência da sessão e futura revinculação

Não há sessão a preservar, mas o banco experimental foi preservado em `~/.local/state/better-talker/coord-pilot/experimental-instance/data/opencode/opencode.db` (13.922.304 bytes, sem sessões). A identidade da instância incorpora PID e metadados do processo: uma futura inicialização invalidará o vínculo cadastrado. Recomenda-se revinculação controlada da **mesma** sessão (quando existir) via novo preflight e confirmação de instância — nunca criar outra sessão para resolver divergência de PID.

## M. Consumo de recursos

Antes: MemAvailable 3.330.232 kB, SwapFree 13.104.312 kB, carga 0.61, 17 GiB livres. Durante (PID `974497`): RSS 371.228 kB (~363 MiB), CPU 6,8%, MemAvailable 3.215.504 kB, SwapFree 13.157.048 kB, carga 1.01. Depois: instância encerrada; sem processos ou daemon adicionais; nenhum build, Gradle, ADB ou LLM.

## N. Encerramento

Titularidade confirmada (`cmdline` exata, dono do listener). `kill 974497` — sem `pkill`/`killall`. Processo ausente, listener `51321` indisponível, serviço compartilhado `http://127.0.0.1:49374` inalterado, PIDs `178143`/`387110` preservados, banco experimental preservado, nenhum prompt enviado, nenhum LLM executado, nenhum POST adicional. Log sanitizado em `.../experimental-instance/logs/`.

## O. Preservação de A–E

Nenhuma sessão, prompt, agente, modelo, credencial, mensagem, worktree, configuração global, Android, HealthAgentForms ou serviço de A–E foi acessado ou modificado; nenhum merge, ASSISTED/DELEGATED ou início de missão posterior.

## P. Menor trabalho restante para a primeira transmissão experimental

1. Decisão humana: autorizar instalação isolada que contenha `opencode-go/hungerg-free#default`, **ou** autorizar explicitamente um modelo gratuito disponível — sem fallback automático.
2. Com essa decisão, repetir somente as etapas C–J com o contrato real confirmado (`POST /api/session`, `model:{id,providerID}`, `location:{directory}`, `permissions:[{action,resource,effect}]`), ainda sem prompt inicial.
3. Validar metadados reais, corrigir os controles de disponibilidade/permissões com evidência observada e cadastrar a sessão sem `--permissions-confirmed`.
4. Manter bloqueio de manifesto e transmissão até aprovação humana específica sobre o manifesto final.

**Adendo P3B.3.1:** o mantenedor autorizou a substituição do modelo por `opencode/hy4-flash-free`. Correções documentais deste relatório: `POST /api/session` com `operationId=session.create`, **nenhum** parâmetro de query, `requestBody` obrigatório, nenhuma propriedade superior obrigatória (oito propriedades disponíveis ≠ obrigatórias), `location.directory` no corpo, `Model.Ref` com `id`/`providerID`, `Permission.Rule` com `action`/`resource`/`effect`, nenhum prompt inicial. Inventário real: 18 modelos — 11 `opencode` com custo listado zero e 7 `groq`. Payload mínimo do schema (`{}`) ≠ payload mínimo operacional (titulo/agente/modelo/location/permissions).

**Parecer:** operação interrompida por bloqueio objetivo e documentado; submeter à revisão independente. PR permanece Draft.
