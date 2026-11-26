# COORD-04 / P3B.0 — Conexão controlada ao OpenCode real (Agente S)

**PR #64** `feat(coord): fundação de comunicação bidirecional simulada` · branch `feat/coord-communication-foundation` (mantida) · PR **OPEN/DRAFT**.
**Certificação independente de referência:** `issuecomment-7176077335` (282/282; F2 com ressalva obrigatória de endpoint).

> Esta missão **não** executou POST, não iniciou transmissão, não iniciou/reiniciou serviços, não criou sessões, não leu mensagens de A–E e **não se autocertifica**. Nenhuma aprovação humana foi emitida. Aguarda revisão independente e autorização explícita do mantenedor.

---

## A. HEAD inicial e final

| Item | Valor |
|---|---|
| HEAD certificado (inicial) | `c7d873ea565a955b105b57e9ab64e11182a4678f` |
| HEAD final do código | `582ba295690b09393b9ca012b208e8778527d19b` |
| HEAD final da branch | SHA corrente (`git rev-parse HEAD`); commits posteriores ao código são somente este relatório |
| Main observada | `b256b8d7a0f104f08b780177ca096c199652c15f` |
| Delta | 6 arquivos de código/doc + 1 novo teste, **+380/−61** (antes deste relatório); `git diff --check` limpo |
| Push | sem force-push; PR permanece Draft |

## B. Fail-first do endpoint (reproduzido no HEAD certificado, isolado, rede bloqueada)

| Sonda | Resultado no HEAD certificado |
|---|---|
| Manifesto aprovado para `http://127.0.0.1:51111`, cliente efetivo em `49999` | **EXECUTADO**, estado `DISPATCHING`, POST emitido para o cliente real (bloqueador) |
| Endpoint externo `https://externo.example.com/api` no manifesto | **EXECUTADO**, POST emitido (bloqueador) |
| Endpoint externo com caminho `http://externo.example.com/prompt` | **EXECUTADO**, POST emitido (bloqueador) |
| `PilotManifest.endpoint` aceita `https://…`, userinfo, query e fragmento | **ACEITO** (bloqueador) |

## C. Correção e regressões

- `canonical_endpoint()` é a fonte única: exige `http`, host exatamente `127.0.0.1`, porta válida/autorizada, ausência de userinfo, query, fragmento e caminho; normaliza `http://127.0.0.1:<porta>`. `endpoint_port()` deriva a porta para o cliente.
- `client_endpoint()` lê o endpoint **efetivo do cliente** (`endpoint`/`base_url`), nunca argumento do chamador. `LocalHttpClient` constrói `base_url` a partir do valor canônico, mantém `ProxyHandler({})` e `_NoRedirect`.
- `PilotAuthorization` e `PilotManifest` canonicalizam `endpoint` em `__post_init__`; o endpoint entra no hash canônico da autorização e no material do manifesto, vinculando autorização ↔ manifesto ↔ operação.
- `OpenCodeTransport` deriva `self.endpoint` do cliente e expõe `confirm_endpoint()`; `prepare()` e `transmit()` revalidam cliente ↔ autorização ↔ guarda. `PilotExecutor` revalida em `_validate()` (chamado no início de `execute()` **e** imediatamente antes de `transmit()`); `_OperationGuard` carrega o endpoint. Evidência persistida registra `source`, `endpoint` e `service_contacted`.
- Pós-correção: porta divergente, endpoint externo, autorização divergente e cliente mutado após construção → **BLOQUEADO com `posts=0`**; caso autorizado simulado → exatamente **1 POST**. Um novo arquivo `test_pilot_endpoint.py` com **15 regressões** cobre todas essas vias e as regras de validação.

## D. Separação fixture/real

- Toda saída fixture traz `source=fixture`, `service_contacted=false`, `mode=fixture`, `fallback=none`. Nenhum dado simulado é rotulado como observação real; `preflight` fixture mantém `agent_id=A` explicitamente em `assumptions`.
- `--real` constrói `ReadOnlyHttpClient`, que bloqueia POST **mecanicamente** (`TransportDisabled` antes de qualquer I/O), sem proxies e sem redirecionamentos.
- `preflight --real` / `prepare --real` emitem somente GETs; falha de conexão retorna `fallback=none`, `service_contacted=false` e bloqueio — **sem fallback silencioso para fixture** e sem reapresentar valores simulados como observados.
- Stores separados: padrão real `~/.local/state/better-talker/coord-pilot/real`; fixture permanece no diretório anterior. `dry-run` nunca toca o store real.

## E. Estado real dos serviços

| Verificação (somente leitura) | Observado |
|---|---|
| `opencode --version` | `opencode v2.3.2` |
| `opencode service status` | `stopped` |
| `opencode service get` | `{}` (sem configuração de serviço) |
| Recursos | 6,7 GiB RAM (3,0 GiB disponíveis), 16 CPUs, 20 GiB livres em `/` |
| Worktree experimental | `/home/guilherme/Documentos/better-talker-coord-supervisor-shadow` presente, HEAD `c9253a90` |

Conclusão: **nenhuma instância local autorizada e disponível**. O serviço compartilhado não foi tocado.

## F. Preflight observado ou bloqueador de acesso

**Bloqueador de acesso:** serviço parado e sem configuração. Nenhum GET real foi executado; nenhum serviço foi iniciado ou reiniciado. Comando exato proposto, **dependendo de autorização específica para instância privada**:

```sh
# somente após autorização explícita; --standalone usa servidor privado, não o compartilhado
opencode api --standalone get /api/info
# usar a url observada em /api/info (deve ser http://127.0.0.1:<porta>) como <endpoint-local-autorizado>
python3 .opencode/scripts/coord_pilot_cli.py preflight --real --endpoint <endpoint-local-autorizado>
python3 .opencode/scripts/coord_pilot_cli.py prepare  --real --endpoint <endpoint-local-autorizado>
```

## G. Compatibilidade observada da API

| Item | Documentado no adaptador | Observado nesta missão | Hipótese (fixture, não certificada) |
|---|---|---|---|
| `/api/info` | `version`, `pid`, `hostname`, `urls`; instância = SHA-256 canônico destes campos | **não observado** | formato sintético dos testes |
| `/api/location` | `directory`, `project.id` | **não observado** | `project.canonical` / `repository` podem existir |
| Metadados da sessão | `data.{id,projectID,agent,model,time,outcome,location}` | **não observado** | `repositoryID` pode existir |
| `active` / `inbox` | `data` como mapa / lista | **não observado** | contagem e presença de chave |
| Modelo / instância | derivados de campos acima | **não observado** | `providerID/id/variant` |
| busy/idle | sem interpretação de disponibilidade | **não observado** | — |
| Mensagens e respostas | envelope com `id`, `agent`, `sessionID`, `reply_to` | **não consultado** (conteúdo não autorizado nesta missão) | — |

Nenhum campo ausente foi fabricado; ausência de `busy` **não** foi tratada como disponibilidade comprovada; nenhum POST foi usado para descobrir escrita.

## H. Identidade do destinatário, quando confirmada

**Não confirmada** (sem serviço). Sessão proposta mantida: `ses_defc05853ffeej4fPXrp7frx4x`. O preflight real não presume `agent_id=A`, não deriva `repository_id` do `project_id` e bloqueia se instância, projeto, location, modelo ou worktree divergirem. Nenhuma sessão foi criada, operada ou excluída; nenhuma mensagem de A–E foi lida.

## I. Manifesto experimental proposto, quando possível

**Não emitido** — não há metadados reais observados. O caminho `prepare --real` foi exercitado apenas com cliente simulado offline (rede bloqueada): produziu manifests com `approved=false`, `environment=PROPOSED`, endpoint canônico vinculado, `agent_id` e `project_id` **observados** (não presumidos) e nenhuma `PilotApproval`. Esse resultado é **simulação e não é apresentável para aprovação**. Após qualquer alteração de HEAD ou dos arquivos operacionais, o manifesto deve ser recalculado; o código vigente vincula o HEAD efetivamente executado.

## J. Caminho futuro de execução

`execute --real` constrói cliente real somente leitura, valida o endpoint e permanece `BLOCKED_PENDING_REAL_ACTIVATION` com `real_calls=0`. Reserva exclusiva, `DISPATCHING` persistido antes do POST, validação integral, uma tentativa, bloqueio de reenvio, reconciliação `UNKNOWN`, associação do relatório e estados terminais permanecem preservados. Aprovação `FIXTURE` não habilita escrita real; nenhum comando de escrita é exposto ao supervisor; ASSISTED/DELEGATED não foram ativados.

## K. Testes, diff, CI e recursos

| Suíte (rede bloqueada por `sitecustomize`) | Resultado |
|---|---|
| `test_supervisor.py` (COORD-03) | 167 passed, 0 failed |
| `test_communication.py` | 56 OK |
| `test_pilot_adapter.py` | 20 OK |
| `test_pilot_executor.py` | 19 OK |
| `test_pilot_closure.py` | 20 OK |
| `test_pilot_endpoint.py` (novo) | 15 OK |
| **Total** | **297/297** |

Demos normal e `UNKNOWN`: `final_state=COMPLETED`, `transmit_calls=1`, reenvio bloqueado no cenário timeout. `dry-run`: `http_posts=1`, `real_calls=0`, `source=fixture`. Prompt de `PILOT.md` byte-idêntico ao `PILOT_PROMPT`. Recurso: somente Python offline; zero build, Gradle, ADB, LLM pago, daemon, POST ou conexão real. CI: a conferir no SHA final; `propose` já certificado no SHA anterior.

## L. Preservação dos agentes A–E

Nenhuma sessão, prompt, agente, modelo, credencial, configuração global, mensagem ou worktree de A–E foi acessado ou modificado. Nenhum serviço iniciado/reiniciado, nenhum POST real, nenhum merge, release, bump, build, ADB, alteração Android, acesso a HealthAgentForms, ativação ASSISTED/DELEGATED ou início de P3B. GitHub CLI não foi reautenticado. Apenas a worktree da branch do PR #64 foi modificada.

## M. Autorizações ainda necessárias

1. Autorização específica para iniciar **instância privada** (`opencode api --standalone`) e realizar os GETs de metadados experimentais; não autorizar o serviço compartilhado.
2. Confirmação observada de identidade do agente, projeto/repositório, location, instância, modelo gratuito, disponibilidade, inbox e permissões efetivas — divergência bloqueia.
3. Compatibilidade confirmada da API OpenCode v2.3.2 com o adaptador, inclusive formato de mensagens/respostas sem acesso não autorizado a conteúdo.
4. Aprovação humana explícita sobre o **manifesto final recalculado** (HEAD atual, digests atuais, endpoint efetivo), com `approved=true`/`environment=APPROVED` — nenhuma aprovação emitida pelo agente.
5. Decisão separada do mantenedor para ativar a execução real e recertificação independente focal. **Nenhuma transmissão real sem essas autorizações.**
