# Supervisor SHADOW — configuração persistente do Agente S

Configuração **restrita ao projeto**, versionável, no formato nativo do OpenCode v2.
Escopo: supervisão técnica operacional em modo **SHADOW** (observar, verificar, propor).
**Não** ativa ASSISTED/DELEGATED, **não** despacha missões, **não** altera agentes A–E.

## Arquivos

| Arquivo | Função |
| --- | --- |
| `agents/supervisor.md` | Agente `supervisor` (primary), instruções SHADOW e permissões de privilégio mínimo. **Sem modelo fixado** — identidade operacional ≠ modelo. |
| `scripts/coord_checkpoint.py` | Checkpoint somente leitura em dois níveis; coleta de relatórios apenas de sessões autorizadas por mapa local. |
| `scripts/test_supervisor.py` | Testes A–C com fixtures sintéticas; categoria D (imposição real) permanece pendente. |

## Utilização

1. Selecione o agente `supervisor` (ele **não** é o padrão; `default_agent` permanece `build`).
2. Recupere contexto na main vigente: `docs/COORDINATOR.md`, `docs/DECISIONS.md`,
   `docs/TEAM_COORDINATION.md`, `docs/ACTIVE_TASKS.md`, `docs/DELIVERY_POLICY.md`.
3. Checkpoint **resumido** (rotina): `python3 .opencode/scripts/coord_checkpoint.py`.
4. Checkpoint **detalhado**: `python3 .opencode/scripts/coord_checkpoint.py --detail`.
5. Relatórios concluídos: `python3 .opencode/scripts/coord_checkpoint.py --reports`.
   `--json` pode ser combinado. Nenhuma proposta é enviada automaticamente.

## Mapa local de sessões autorizadas (F1)

O checkpoint **não autoriza sessões por título**. `AGENTE` é apenas candidato para descoberta
de metadados. Mensagens só são consultadas quando o `sessionID` está em um mapa local explícito,
com projeto e diretório observáveis conferidos.

Caminho padrão: `/tmp/opencode/coord-sessions.json`; para testes/admin, `BT_COORD_SESSION_MAP`.
O arquivo é **local, transitório e não versionado** — IDs de sessão não são identidade permanente
da equipe. Sem mapa, o checkpoint funciona em modo resumido, mas **nenhuma mensagem é lida**.

Esquema ilustrativo (sem IDs reais):

```json
{
  "project": {
    "id": "<projectID observável>",
    "allowed_directories": ["/caminho/better-talker", "/caminho/better-talker-worktree"]
  },
  "agents": {
    "A": [{
      "session_id": "<sessionID local>",
      "directory": "/caminho/better-talker",
      "mission_id": "MISS-...",
      "expected_pr": 52,
      "expected_branch": "feat/...",
      "expected_worktree": "better-talker-..."
    }]
  }
}
```

- Múltiplas sessões do mesmo agente são permitidas somente se todas estiverem explicitamente mapeadas.
- SessionID desconhecido, projeto/diretório divergente ou mapa ambíguo ⇒ rejeição/DESCONHECIDO.
- `expected_*` é associação **local**; não substitui verificação no GitHub.

## Relatório concluído e proveniência (F3)

- Paginação limitada: até 2 páginas × 5 mensagens assistant.
- Elegível: `finish=stop`, texto ≥120 caracteres e marcadores de relatório (`##`, HEAD, PR, Parecer,
  Resultado, Bloqueio, Missão, QA, Recertificação, Status).
- Tool-calls, respostas interrompidas e status intermediários não viram relatório final.
- Texto limitado a 4.000 caracteres; ID e timestamps da mensagem são preservados.
- Quatro níveis separados: **declarado pelo agente** → **PR/worktree existe** → **associação à missão
  confirmada pelo mapa local** → **desconhecido**. A segunda categoria não prova a terceira.

## Checkpoint e evidências (F4/F6)

- SHAs completos são preservados internamente; abreviações só na apresentação.
- Estado de atividade vem de `GET /api/session/active`.
- CI registra evento, run ID/URL, SHA do check e observação; verde em outro SHA **não** certifica
  o HEAD atual. Checks sem SHA/evento verificáveis ficam desconhecidos.
- Ausência de checks ≠ falha de consulta.
- Governança local é rotulada como branch local; divergência da main é explícita.
- Resumo exibe no máximo 12 worktrees relevantes; detalhado exibe todas.
- Checkpoint é informativo e **não autoriza ações automaticamente**.

## Permissões: política × configuração × controle testado

- **Política:** `docs/COORDINATOR.md` §5/§12.
- **Configuração:** `agents/supervisor.md` — negar por padrão; allowlist de caminhos de leitura/glob;
  `grep` negado; shell com comandos exatos de consulta; `edit`, `subagent`, `execute`, `skill` e
  `generate_image` negados; credenciais negadas em profundidade.
- **Testado:** parsing, matcher, fixtures sintéticos e reconhecimento pela API. A **imposição real
  em execução** exige sessão supervisor e permanece **PENDENTE**.

### Lacunas conhecidas (não declarar sandbox forte)

- O scanner de shell é best-effort; `eval`, comandos compostos e scripts podem contornar padrões.
- O checkpoint executado pelo shell chama subprocessos que não são reavaliados pelas regras de shell;
  confia-se no código versionado e somente leitura. O mapa local é lido pelo script sem sandbox.
- Permissões de caminho não protegem contra symlinks/canonicalização maliciosa em todos os casos.
- MCP não está configurado; ferramentas de rede futuras podem ampliar superfície.
- Lista de proibições e instruções ao modelo **não são barreira de segurança por si**.

## Modelos (Free First — D-007 vigente, D-009 proposta)

- O modelo não é fixado; a sessão preserva o modelo selecionado. Troca exige autorização.
- Free First: gratuitos primeiro; DeepSeek Flash fallback econômico; GLM-5.3/DeepSeek Pro para
  escalonamento mediante autorização. Nenhum custo adicional.
- Tokens acumulados não medem diretamente a franquia; `cost: 0` não prova consumo zero.
- Resultados de um ciclo com mudança de modelo não são atribuídos exclusivamente ao modelo final.

## Requisitos para despacho futuro (COORD-04/05 — não implementados)

Reserva atômica; ledger persistente; ID imutável; hash do payload; validação mecânica do destinatário;
proteção contra concorrência/duplicidade; bloqueio em UNKNOWN; revogação efetiva; revalidação de HEAD
e ownership; sessão ocupada; reconciliação após reinício; evidência positiva de entrega/não entrega;
**sem exactly-once sem suporte comprovado**. Ver `docs/COORDINATOR.md` §6.1.

## Limitações desta entrega

- Reconhecimento nativo e checkpoint são validados; imposição real, carregamento completo de
  instruções e recuperação em sessão nova exigem autorização para sessão isolada.
- Nenhum daemon, agendamento, banco ou orquestrador externo foi criado.
