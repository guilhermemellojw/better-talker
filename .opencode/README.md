# Supervisor SHADOW — configuração persistente do Agente S

Configuração **restrita ao projeto**, versionável, no formato nativo do OpenCode v2.
Escopo: supervisão técnica operacional em modo **SHADOW** (observar, verificar, propor).
**Não** ativa ASSISTED/DELEGATED, **não** despacha missões, **não** altera agentes A–E.

## Arquivos

| Arquivo | Função |
| --- | --- |
| `agents/supervisor.md` | Agente `supervisor` (primary), instruções SHADOW e permissões de privilégio mínimo. **Sem modelo fixado** — identidade operacional ≠ modelo. |
| `scripts/coord_checkpoint.py` | Checkpoint somente leitura em dois níveis; coleta de relatórios apenas de sessões autorizadas por mapa local privado. |
| `scripts/test_supervisor.py` | Testes A–C com fixtures sintéticas; categoria D é validada separadamente na sessão experimental autorizada. |

## Utilização

1. Selecione o agente `supervisor` (ele **não** é o padrão; `default_agent` permanece `build`).
2. Recupere contexto na main vigente: `docs/COORDINATOR.md`, `docs/DECISIONS.md`,
   `docs/TEAM_COORDINATION.md`, `docs/ACTIVE_TASKS.md`, `docs/DELIVERY_POLICY.md`.
3. Checkpoint **resumido**: `python3 .opencode/scripts/coord_checkpoint.py`.
4. Checkpoint **detalhado**: `python3 .opencode/scripts/coord_checkpoint.py --detail`.
5. Relatórios: `python3 .opencode/scripts/coord_checkpoint.py --reports`.
   `--json` pode ser combinado. Nenhuma proposta é enviada automaticamente.

## Mapa local de sessões autorizadas (R1)

O checkpoint **não autoriza sessões por título**. `AGENTE` é apenas candidato para descoberta
de metadados. Mensagens só são consultadas quando o `sessionID` está em um mapa local explícito,
com `projectID` e diretório exato observados e conferidos.

Caminho fixo: `~/.local/state/better-talker/coord-sessions.json`.
O arquivo é **local, transitório e não versionado** — IDs de sessão não são identidade permanente
da equipe. Não há variável de ambiente que amplie sua autoridade.

Requisitos do mapa:
- arquivo regular, não-symlink, proprietário atual, modo `0600`;
- diretório privado do proprietário, modo `0700`;
- `sessionID` válido (`ses_...`), único e sem ambiguidade;
- `projectID` obrigatório e igual ao observado na API;
- diretório da entrada exato e presente em `allowed_directories`.

Sem mapa, o checkpoint funciona em modo resumido, mas **nenhuma mensagem é lida**. Rejeitados
não expõem IDs/títulos. Múltiplas sessões do mesmo agente exigem entradas explícitas.

## Relatório e proveniência (R3)

- Paginação limitada: até 2 páginas × 3 mensagens assistant; cursor não é combinado com `order`.
- `finish=stop` é apenas mensagem concluída. Marcadores genéricos (QA, HEAD, PR, Status) produzem
  **candidato não certificado**.
- Relatório identificado exige o contrato `<!-- BT-COORD-REPORT:v1 -->` com `mission_id`,
  `head_sha` de 40 hex e `status`.
- Tool-calls, interrompidos e status intermediários não são relatório.
- Texto limitado a 4.000 caracteres; ID e timestamps preservados; segredos sintéticos/reais são
  redigidos antes da saída.
- Proveniência separa: declarado → PR/worktree existe → associação/atualidade pelo contrato + mapa
  local → desconhecido. A existência não prova ownership.

## Checkpoint e evidências (R4)

- SHAs completos são preservados internamente; abreviações só na apresentação.
- Atividade vem de `GET /api/session/active`.
- CI registra evento, run ID/URL, SHA e observação. Checks são agrupados por nome + evento:
  sucesso `push` não esconde falha `pull_request`.
- `certifies_current_head` exige SHA válido/correspondente, evento conhecido e sucesso individual.
- `readiness` do PR é separado de autorização de merge; o checkpoint nunca autoriza merge.
- Ausência de checks ≠ falha de consulta; resposta inválida vira desconhecida.
- O resumo consulta metadados dos documentos da main; o detalhado recupera o texto vigente via API,
  com repositório explícito, mesmo quando a worktree local está desatualizada.
- Resumo exibe no máximo 12 worktrees relevantes; detalhado exibe todas.

## Permissões: política × configuração × controle testado

- **Política:** `docs/COORDINATOR.md` §5/§12.
- **Configuração:** `agents/supervisor.md` — negar por padrão; allowlist de caminhos de leitura/glob;
  `grep` negado; shell com comandos exatos de consulta; `edit`, `subagent`, `execute`, `skill` e
  `generate_image` negados; credenciais negadas em profundidade.
- **GitHub:** consultas estruturadas são feitas somente pelo checkpoint, com
  `guilhermemellojw/better-talker` delimitado. Não há shell `gh` genérico.
- **Integridade:** o checkpoint exige arquivo regular, proprietário atual, rastreado pelo Git e sem
  modificações locais antes de executar.
- **Testado:** parsing, matcher, fixtures sintéticas e reconhecimento pela API. A **imposição real
  em execução** é registrada separadamente na sessão experimental.

### Lacunas conhecidas (não declarar sandbox forte)

- O scanner de shell é best-effort; `eval`, comandos compostos e scripts podem contornar padrões.
- O checkpoint chamado pelo shell executa subprocessos que não são reavaliados pelas regras de shell;
  confia-se no código versionado, rastreado e limpo. O mapa local é lido pelo script sem sandbox.
- Permissões de caminho não eliminam todos os riscos de symlink/canonicalização maliciosa.
- MCP não está configurado; ferramentas de rede futuras podem ampliar superfície.
- Lista de proibições e instruções ao modelo **não são barreira de segurança por si**.

## Modelos (Free First — D-007 vigente, D-009 proposta)

- O modelo não é fixado; a sessão preserva o modelo selecionado. Troca exige autorização.
- Gratuitos primeiro; DeepSeek Flash fallback; GLM-5.3/DeepSeek Pro para escalonamento autorizado.
- Tokens acumulados não medem diretamente a franquia; `cost: 0` não prova consumo zero.
- Resultados de um ciclo com mudança de modelo não são atribuídos exclusivamente ao modelo final.

## Requisitos para despacho futuro (COORD-04/05 — não implementados)

Reserva atômica; ledger persistente; ID imutável; hash do payload; validação mecânica do destinatário;
proteção contra concorrência/duplicidade; bloqueio em UNKNOWN; revogação efetiva; revalidação de HEAD
e ownership; sessão ocupada; reconciliação após reinício; evidência positiva de entrega/não entrega;
**sem exactly-once sem suporte comprovado**. Ver `docs/COORDINATOR.md` §6.1.

## Limitações desta entrega

- Nenhum daemon, agendamento, banco ou orquestrador externo foi criado.
- A validação real usa no máximo uma sessão experimental isolada; não altera nem reinicia A–E.
