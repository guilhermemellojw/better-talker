# Supervisor SHADOW — configuração persistente do Agente S

Configuração **restrita ao projeto**, versionável, no formato nativo do OpenCode v2.
Escopo: supervisão técnica operacional em modo **SHADOW** (observar, verificar, propor).
**Não** ativa ASSISTED/DELEGATED, **não** despacha missões, **não** altera agentes A–E.

## Arquivos

| Arquivo | Função |
| --- | --- |
| `agents/supervisor.md` | Agente `supervisor` (primary): identidade, sistema de instruções, permissões de privilégio mínimo. **Sem modelo fixado** — a sessão conserva seu modelo; identidade ≠ modelo. |
| `scripts/coord_checkpoint.py` | Checkpoint somente leitura, reproduzível (git + gh + opencode api). `--reports` coleta seletiva do último relatório de A–E; `--json` saída estruturada. |
| `scripts/test_supervisor.py` | Testes isolados: parsing, simulação de permissões, sintaxe, ambiguidade. `--live` acrescenta reconhecimento pelo serviço e checkpoint real. |

## Utilização

1. Em uma sessão de coordenação, selecione o agente `supervisor` (ele **não** é o padrão;
   `default_agent` permanece `build`, logo A–E não são afetados).
2. Recupere contexto lendo, na main vigente: `docs/COORDINATOR.md`, `docs/DECISIONS.md`,
   `docs/TEAM_COORDINATION.md`, `docs/ACTIVE_TASKS.md`, `docs/DELIVERY_POLICY.md`.
3. Produza o checkpoint: `python3 .opencode/scripts/coord_checkpoint.py [--reports]`.
4. Produza pareceres e propostas como **texto**. Nenhuma proposta é enviada automaticamente.

## Permissões: política × configuração × controle testado

- **Política documental:** `docs/COORDINATOR.md` §5/§12 (o que é prescrito).
- **Permissão configurada:** `agents/supervisor.md` (negar por padrão; allowlist de leitura;
  shell somente leitura; edit/subagent/execute/skill negados; credenciais negadas).
- **Controle técnico efetivamente testado:** apenas a **lógica** das regras é testada
  (`test_supervisor.py`, simulação). A **imposição em execução** exige uma sessão supervisor
  e permanece **gate pendente** (não criada nesta rodada, sem autorização).

### Lacunas técnicas conhecidas (não declarar segurança efetiva)

- O scanner de shell é **best-effort**: comandos compostos, `eval`, scripts e execução indireta
  podem contornar padrões. Um script liberado (`coord_checkpoint.py`) executa subprocessos que
  **não** são reavaliados pelas regras de shell — confiança no conteúdo versionado do script.
- `subagent`/`execute` negados reduzem, mas não eliminam, caminhos indiretos; MCP e ferramentas
  de rede podem ampliar superfície (nenhum MCP configurado; webfetch restrito a opencode.ai).
- Lista de proibições **não é barreira de segurança** por si (COORDINATOR.md §12, F5).
- A cota da franquia OpenCode Go **não é observável localmente**; `cost: 0` não prova ausência
  de consumo. Métrica aceita: tokens por sessão (`GET /api/session`).

## Modelos (Free First — D-007 vigente, D-009 proposta)

- O modelo **não** é fixado no agente; a sessão preserva o modelo selecionado. Troca exige
  autorização do mantenedor; nunca automática.
- Modelo efetivo observado na sessão S: preflight 24/11 23:32Z = `opencode-go/qwen4-max#xhigh`
  (não glm-5.3 do cabeçalho de criação); fechamento 24/11 ~23:50Z =
  **`opencode-go/hungerg-free#default`** (gratuito, cost 0). A troca foi observada, **não executada
  pelo Agente S**. O modelo atual foi preservado; resultados anteriores não são atribuídos ao
  modelo atual. Inclusão na franquia vs cobrança adicional não é observável localmente.
- Free First (D-009, proposta): gratuitos primeiro; DeepSeek Flash fallback econômico;
  GLM-5.3/DeepSeek Pro para escalonamento mediante autorização. Nenhum custo adicional autorizado.

## Requisitos para despacho futuro (COORD-04/05 — não implementados)

Reserva atômica de missão; ledger persistente; ID imutável; hash do payload; validação mecânica
do destinatário (sessionID × título × diretório × instância); proteção contra concorrência e
duplicidade; bloqueio de reenvio em UNKNOWN; revogação efetiva de autorização; revalidação de
HEAD e ownership; proibição de entrega inesperada a sessão ocupada; reconciliação após reinício;
evidência positiva de entrega ou não entrega; **sem garantia exactly-once sem suporte comprovado**.
A ausência de mensagem no histórico **não** é, isoladamente, prova conclusiva de não entrega.
Ver `docs/COORDINATOR.md` §6.1.

## Limitações desta entrega

- Reconhecimento da configuração pelo serviço OpenCode **validado na worktree** via
  `GET /api/agent?location[directory]=<worktree>` (carregamento assíncrono; retentativa foi
  necessária). A lógica das permissões e o checkpoint real foram testados.
- A **imposição de permissões em execução, o carregamento completo de instruções e a recuperação
  de contexto em sessão nova** continuam sendo gates pendentes (exigiriam sessão supervisor —
  não criada nesta rodada; reinício não executado para não afetar A–E).
- Nenhum daemon, agendamento, banco ou orquestrador externo foi criado.
