# COORD-04 / P3B.2 — Compatibilidade com a API real do OpenCode v2.3.2 (Agente S)

**PR #64** · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Entrada:** evidências sanitizadas da P3B.1 (`docs/reports/2026-11-25-coord-04-p3b1-real-preflight.md`) e certificação P6 (`issuecomment-7179597704`).

> Nenhuma instância foi iniciada, nenhuma sessão criada/excluída, nenhum POST real, prompt, LLM, credencial produtiva, build, ADB, merge, acesso a A–E, HealthAgentForms, ASSISTED/DELEGATED. **Sem autocertificação.**

---

## A. HEAD e delta

| Item | Valor |
|---|---|
| HEAD inicial | `362b1a86837ab042249d65258269fd17e27f7836` |
| HEAD final do código | `d98950f9e2cee91f7c9e2e34191baa2b88b81c1a` |
| HEAD final da branch | SHA corrente; commits posteriores são somente documentação |
| Main observada | `02b89856837d03cbaa11d2f30d5931b4d7e43bd3` |
| Delta de código | `coord_pilot.py`, `coord_pilot_cli.py`, `PILOT.md`, `test_pilot_endpoint.py`, `test_pilot_api_compat.py` (novo): **+360/−97**; `git diff --check` limpo |

## B. Mecanismo de autenticação escolhido e justificativa

Comparação avaliada:

- **A — CLI oficial com argumentos estritamente delimitados**: funcional para GET (P3B.1 provou), mas levaria o futuro POST para um subprocesso com prompt em `argv`/`ps`, enfraquecendo a reserva exclusiva, o `DISPATCHING` persistido e o bloqueio de reenvio do executor.
- **B — Cliente HTTP com autenticação experimental explícita (escolhida)**: mantém a abstração `HttpClient`, o guarda operacional e o bloqueio mecânico de POST. `pilot.ExperimentalAuth` recebe um **provedor callable**, nunca o segredo; o operador informa apenas o **nome** da variável (`--auth-env NAME`). O cabeçalho `Authorization: Basic` (usuário fixo `opencode`) é gerado por requisição e emitido **somente** para o endpoint privado ao qual a credencial foi vinculada; endpoint divergente gera `ContractError`. O valor não aparece em `repr`, logs, manifestos, comandos, commits ou relatórios.

Nenhuma autenticação foi executada contra serviço real nesta rodada. Nenhuma credencial produtiva foi lida, copiada ou reutilizada; autenticação não foi desativada como atalho. O cliente real permanece `ReadOnlyHttpClient` com POST bloqueado antes de qualquer I/O.

## C. Compatibilidade com os formatos observados

Os corpos sanitizados da P3B.1 tornaram-se fixtures de regressão:

- `/api/info` com `version`, `pid`, `hostname`, `urls`, `paths` e `capabilities`; instância calculada pelos 4 campos canônicos.
- `/api/location` com `project.id` em hash; `project.canonical` distinto de `directory`; ausência de campo de repositório registrada sem fabricação.
- `/api/session` com `data` e `cursor`; inventário vazio sem criar sessão.
- `/api/session/active` com `data`.
- `HTTP 401` classificado como **serviço contactado + autenticação recusada** (`connection_attempted=true`, `service_contacted=true`, `authentication_succeeded=false`, `http_response_valid=false`, `status_kind=unauthorized`), não como ausência de conexão.
- `HTTP 404 SessionNotFoundError` classificado como **sessão ausente** (`status_kind=session_absent`, `session_absence_confirmed=true`) dentro de uma comunicação HTTP válida, sem autorizar criação automática.
- Sessão experimental inexistente: preflight de infraestrutura funciona e reporta `recipient_authorized=false`.

Estados separados: tentativa de conexão, serviço contactado, autenticação bem-sucedida, resposta HTTP válida, metadados disponíveis e sessão encontrada.

## D. Identidade real do projeto e repositório

`coord_pilot.py` passa a distinguir explicitamente:

| Identidade | Origem | Uso |
|---|---|---|
| `opencode_project_id` | `location.project.id` observado (hash) | auditoria, nunca identidade lógica |
| `project_id` | política `better-talker` | missão/autorização |
| `repository_id` | `git remote get-url origin`, validado contra `guilhermemellojw/better-talker` | missão/autorização |
| `project_canonical` | `location.project.canonical` observado, validado contra `/home/guilherme/Documentos/better-talker` | validação |
| `location_directory` | worktree experimental observada | destino |
| `instance_id` | SHA-256 canônico de `/api/info` | destino |
| `session_id` | sessão cadastrada | destino |

`repository_id` **não** é derivado de `project.id` nem do nome do diretório; URLs com userinfo são rejeitadas; o hash observado não é fixado como identidade universal. Nenhuma URL com credencial é registrada.

## E. Cadastro de sessão experimental

O ID fixo `ses_defc05853ffeej4fPXrp7frx4x` permanece **somente em fixtures**. O caminho real usa `experimental-session.json` (modo `0600`) no store real, escrito por `session-register` e exigindo: `experimental=true`, `session_id` válido, endpoint canônico, instância, projeto da política, repositório da política, agente observado, modelo gratuito autorizado (`opencode-go/hungerg-free#default`), location igual à worktree experimental e referência de autorização. `--session` só é aceito se corresponder ao cadastro; destino divergente ou não cadastrado é bloqueado. O supervisor não pode selecionar arbitrariamente sessões de produção. **Nenhum cadastro real foi executado.**

## F. Timestamps e validade

`NOW` fixo continua permitido exclusivamente em fixtures. `_prepare_real()` usa `cc.utc_now()` por padrão (injetável para testes), com `created_at=issued_at=agora` e `expires_at=agora+30min`; `_validate_proposal_window()` rejeita datas futuras, validade invertida e proposta expirada antes da apresentação. Propostas sem destinatário ou metadados completos são bloqueadas, sem manifesto. `approved=false`, `environment=PROPOSED`; nenhuma `PilotApproval` é emitida; proposta não autorizada ≠ autorização concedida.

## G. Testes e demos

| Suíte (rede bloqueada) | Resultado |
|---|---|
| `test_supervisor.py` (COORD-03) | 167 passed, 0 failed |
| `test_communication.py` | 56 OK |
| `test_pilot_adapter.py` | 20 OK |
| `test_pilot_executor.py` | 19 OK |
| `test_pilot_closure.py` | 20 OK |
| `test_pilot_endpoint.py` | 15 OK |
| `test_pilot_api_compat.py` (novo) | 13 OK |
| **Total** | **310/310** |

Demos normal e `UNKNOWN`: `final_state=COMPLETED`, `transmit_calls=1`, reenvio bloqueado no timeout. `dry-run`: `http_posts=1`, `real_calls=0`, `source=fixture`, `service_contacted=false`. Todos os testes com stores temporários; nenhuma conexão real.

## H. Comandos exatos para criação futura de uma sessão (NÃO executados)

 Após autorização específica e com a instância privada ativa:

```sh
# 1) iniciar UMA instância privada (isolamento HOME/XDG, bind 127.0.0.1, PID registrado)
env HOME=/tmp/<isolado>/home XDG_DATA_HOME=/tmp/<isolado>/data \
    XDG_CONFIG_HOME=/tmp/<isolado>/config XDG_CACHE_HOME=/tmp/<isolado>/cache \
    XDG_STATE_HOME=/tmp/<isolado>/state \
    opencode serve --hostname 127.0.0.1 --port <porta-livre>

# 2) criar exatamente UMA sessão experimental (POST autorizado apenas no futuro)
env HOME=/tmp/<isolado>/home ... OPENCODE_SERVER_PASSWORD=<fornecido-pelo-operador> \
    opencode api --server http://127.0.0.1:<porta-livre> session.create \
    --data '{"title":"COORD-04 experimental","location":{"directory":"/home/guilherme/Documentos/better-talker-coord-supervisor-shadow"},"agent":"<agente-observado>","model":{"providerID":"opencode-go","modelID":"hungerg-free"}}'

# 3) capturar data.id da resposta, confirmar agente/modelo/permissões e cadastrar no piloto
python3 .opencode/scripts/coord_pilot_cli.py session-register \
  --store-dir ~/.local/state/better-talker/coord-pilot/real \
  --session <id-retornado> --endpoint http://127.0.0.1:<porta-livre> \
  --instance-id <instancia-observada> --agent-id <agente-observado> \
  --authorization-reference <decisao-humana>

# 4) preflight e proposta somente leitura, depois encerramento do PID exato
python3 .opencode/scripts/coord_pilot_cli.py preflight --real --endpoint http://127.0.0.1:<porta-livre> --auth-env COORD_PILOT_AUTH --session <id-retornado>
python3 .opencode/scripts/coord_pilot_cli.py prepare --real --endpoint http://127.0.0.1:<porta-livre> --auth-env COORD_PILOT_AUTH --session <id-retornado>
```

`POST /api/session` (operação `session.create`) é o endpoint exato de criação; payload mínimo documentado publicamente: `title` + `location.directory` (aceita também `agent`, `model`, `parentID`, `permission`). Restrições de ferramentas devem ser confirmadas no `/doc` da instância isolada antes de serem fixadas — nenhum campo foi inventado. Nenhum prompt de inicialização e nenhuma execução de LLM.

## I. Permissões experimentais propostas

- Instância privada temporária, loopback, HOME/XDG isolados, PID registrado, logs exclusivos.
- Cliente somente leitura nesta rodada; `allow_write=False`; POST proibido mecanicamente.
- Credencial experimental isolada, fornecida por nome de variável, jamais impressa; sem credenciais produtivas.
- Sessão única, sem ferramentas desnecessárias; modelo gratuito autorizado sem fallback pago.
- Se a instalação isolada não oferecer modelo gratuito sem credencial produtiva: **bloquear** e apresentar alternativa segura (instalação isolada com modelo livre explicitamente autorizado), sem trocar modelo automaticamente.

## J. Consumo de recursos

Somente Python offline, nenhuma instância OpenCode iniciada nesta rodada, nenhum build/ADB/LLM/daemon. Testes em 1,0 s de suíte unitária; memória e CPU desprezíveis em comparação com a P3B.1 (RSS ~307 MiB do servidor naquela rodada, já encerrado). Nenhuma pressão adicional de disco.

## K. CI e preservação de A–E

Check `propose` concluiu `success` no run `45736202168`, `head_sha=6ebf66ed192f05540007ffd502f30d0cc03b8bf7`; commits posteriores a este são somente documentação. Worktree certificado alterado apenas nos arquivos acima; PR #64 permanece Draft. Nenhuma sessão, prompt, agente, modelo, credencial, mensagem, worktree, configuração global, Android, HealthAgentForms ou serviço de A–E foi tocado; serviço compartilhado e processos preexistentes preservados.

## L. Limitações remanescentes

Nenhuma autenticação real foi exercitada na P3B.2 (apenas fixtures); o formato exato de `permission` e de modelos gratuitos na instalação isolada ainda depende de `/doc` e do preflight autorizado; sessão experimental ainda não existe; sem garantia de escrita, exactly-once, entrega durável ou ordem global; identidade de agente e permissões efetivas seguem não confirmadas; manifesto real só poderá ser apresentado após sessão cadastrada e metadados completos.

## M. Parecer para revisão independente focal

Compatibilidade implementada com correções focais sobre evidências reais sanitizadas, sem reimplementar o motor e sem reabrir a fundação certificada. Submeter à revisão independente: escolha da alternativa B, separação entre identidade OpenCode e repositório Git, classificação de 401/404, cadastro de sessão e janelas de validade. **Não autorizar criação de sessão, POST, envio de prompt, transmissão, ASSISTED/DELEGATED ou merge** sem decisão separada e explícita do mantenedor. PR permanece Draft.
