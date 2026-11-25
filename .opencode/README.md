# Supervisor SHADOW — configuração persistente do Agente S

Configuração **restrita ao projeto**, versionável, no formato nativo do OpenCode v2.
Escopo: supervisão técnica operacional em modo **SHADOW** por interface determinística.
**Não** ativa ASSISTED/DELEGATED, **não** despacha missões, **não** altera agentes A–E.

## Arquivos

| Arquivo | Função |
| --- | --- |
| `agents/supervisor.md` | Agente `supervisor` (primary), sem modelo fixado. Nega leitura/glob/grep nativos e shell genérico; permite apenas comandos exatos do checkpoint. |
| `scripts/coord_checkpoint.py` | Checkpoint somente leitura, contexto de governança fixado ao SHA da main e modo fixture sem chamadas externas. |
| `scripts/test_supervisor.py` | Testes de configuração, matcher, fixtures, API e ausência de chamadas no fixture. |

## Utilização

1. Selecione o agente `supervisor` (ele **não** é o padrão; `default_agent` permanece `build`).
2. Recupere governança: `python3 .opencode/scripts/coord_checkpoint.py --context`.
3. Checkpoint resumido: `python3 .opencode/scripts/coord_checkpoint.py`.
4. Checkpoint detalhado: `python3 .opencode/scripts/coord_checkpoint.py --detail`.
5. Relatórios: `python3 .opencode/scripts/coord_checkpoint.py --reports`.
6. Teste sintético: `python3 .opencode/scripts/coord_checkpoint.py --fixture`.
   `--json` pode ser combinado. Nenhuma proposta é enviada automaticamente.

## Fronteira de leitura

- O supervisor **não possui permissão de `read`, `glob` ou `grep`**. Isso elimina o bypass de
  symlink observado na ferramenta nativa de leitura.
- Governança e evidências são fornecidas pelo checkpoint, que aceita apenas comandos fixos,
  sem caminhos, repositórios ou sessões fornecidos pelo modelo.
- `--context` recupera os cinco documentos oficiais pelo **SHA da main observado**, evitando
  misturar versões durante mudanças concorrentes. A validação usa commit → árvore raiz → árvore
  `docs` → blob, todos por SHA fixo: `docs` deve ser árvore `040000`; cada documento deve ser
  `blob` `100644`. O conteúdo vem do SHA do blob e é conferido pelo hash Git. `Contents.type=file`
  não é usado como prova; symlink, submódulo, executável, entrada ambígua, árvore truncada,
  documento ausente ou blob divergente deixa `documents_complete=false`.
- O limite é fixo: três consultas de metadado no resumo e até oito com os cinco blobs no contexto.
- O checkpoint real exige seu próprio arquivo regular, rastreado e limpo no Git antes de qualquer
  consulta. A revisão local de `ACTIVE_TASKS.md` vem do objeto Git do HEAD, não da worktree.
- O checkpoint é uma operação confiável separada da sessão do modelo. Git limpo/rastreado é
  apenas um sinal de integridade; **não é prova suficiente de autorização**.
- A configuração não é um sandbox forte. O script ainda executa subprocessos com autoridade do
  usuário; a confiança está no código versionado, revisão humana e allowlist de comando exato.

## Mapa local de sessões autorizadas

O checkpoint não autoriza sessões por título. `AGENTE` é apenas candidato para descoberta de
metadados. Mensagens só são consultadas quando o `sessionID` está em mapa local privado, com
`projectID` e diretório exato observáveis e conferidos.

Caminho fixo: `~/.local/state/better-talker/coord-sessions.json` (0700 no diretório, 0600 no
arquivo). A leitura usa `O_NOFOLLOW`, revalida o arquivo aberto e rejeita symlinks em qualquer
componente do caminho. Diretórios do mapa e observados na API devem ser absolutos, normalizados
e canônicos. O mapa é local, transitório e não versionado. Não há variável de ambiente que amplie
sua autoridade. Sem mapa, nenhuma mensagem é lida.

## Modo fixture

`--fixture` usa sessões, PRs, checks, relatórios e worktrees sintéticos. Não chama shell, API de
sessões, GitHub, credenciais ou qualquer serviço real. O resultado informa `fixture: true`,
`query_count: 0` e `external_calls: 0`. Os testes provocam `sh` com erro e usam auditoria do Python
para detectar abertura de arquivo, subprocesso ou rede durante a fixture.

## Relatório e proveniência

- Paginação limitada a 2×3 mensagens assistant; cursor sem `order`.
- `finish=stop` é apenas mensagem concluída. Marcadores genéricos produzem candidato não certificado.
- Relatório identificado exige `<!-- BT-COORD-REPORT:v1 -->`, `mission_id`, `head_sha` e `status`.
- `IN_PROGRESS` não é conclusão. Só `COMPLETED` com associação atual produz `mission_completed=true`.
- Texto limitado a 4.000 caracteres; ID e timestamps preservados.
- A sanitização por expressões regulares é **best-effort**; não garante ausência absoluta de segredos.
- Proveniência separa declaração, existência de PR/worktree, associação/atualidade e desconhecido.

## Checkpoint e CI

- SHAs completos são preservados internamente; abreviações só na apresentação.
- Checks são agrupados por nome + evento. Sucesso `push` não esconde falha `pull_request`.
- Evento conhecido não é necessariamente gate. Sem consultar a proteção da branch, somente
  `pull_request` é tratado como candidato de gate; `schedule` e checks informacionais (`propose`)
  não certificam prontidão. `push`, `workflow_run` e `workflow_dispatch` não viram gate por si.
- `certifies_current_head` exige SHA válido/correspondente, candidato de gate e sucesso individual.
  `required_gate_verified` permanece falso: o checkpoint não conhece os gates obrigatórios.
- `readiness` considera evidências observadas no HEAD e é separado de autorização de merge;
  o checkpoint nunca autoriza merge.
- Ausência de checks ≠ falha de consulta; resposta inválida vira desconhecido.

## Recuperação de operações UNKNOWN

### Incidente `INC-COORD-03-001` — criação experimental duplicada

| Campo | Registro |
| --- | --- |
| Operação | Criação de uma sessão experimental do agente `supervisor`. |
| Destinatário pretendido | Uma única sessão experimental; nenhuma sessão A–E. |
| Resultado esperado | Uma criação, com evidência positiva do identificador retornado. |
| Resultado observado | O resultado da primeira tentativa ficou desconhecido após reinício; uma segunda criação foi executada e duas sessões experimentais passaram a existir. |
| Impacto observado | Duas sessões preservadas; nenhuma alteração em A–E. Consumo/custo da sessão vazia não é afirmado. |
| Evidência | Inventário local somente leitura e relatório COORD-03/P3; sem exclusão ou novo despacho. |
| Disposição | Nenhuma sessão experimental foi excluída; o teste prosseguiu na que já havia recebido o prompt. |
| Lacuna de prevenção | Não há evidência de registro prévio e persistente da intenção/operação/destinatário; não se reconstrói retroativamente uma garantia exactly-once. |

### Protocolo mínimo antes de qualquer nova criação

Registro prévio, em texto operacional ou ledger futuro, deve conter: `operation_id`, intenção,
operação inequívoca, destinatário, autorização, limite de tempo e estado inicial `PROPOSED`.
Não é obrigatório implementar agora um ledger completo; é obrigatório não operar sem esses dados.

1. Registrar intenção, operação e destinatário antes de executar.
2. Identificar a operação de forma inequívoca e consultar o estado existente.
3. Executar no máximo uma tentativa; registrar a evidência positiva retornada.
4. Em timeout ou reinício, classificar o resultado como **UNKNOWN**, nunca como não executado.
5. Consultar novamente o estado pela operação e pelo destinatário antes de qualquer repetição.
6. Não repetir operação de resultado indeterminado.
7. Para sair de UNKNOWN, exigir evidência positiva de não criação ou autorização humana específica
   e registrada; silêncio, ausência de resposta ou sessão vazia não são evidência suficiente.

O ledger completo não é implementado nesta rodada. COORD-04 não é iniciado.

## Validação real isolada (COORD-03/P3 — histórica)

- Sessão experimental: agente `supervisor`, modelo `opencode-go/hungerg-free`, worktree exclusiva.
- **Permitido:** leitura de fixture autorizada, leitura dos cinco documentos de governança e
  execução do checkpoint resumido.
- **Negado:** leitura de `.env.fixture`; leitura externa direta (após `ASK` rejeitado); shell
  `git show`, `gh pr view` e consulta de mensagens de sessão via shell.
- **Limitação comprovada:** symlink dentro de `docs/` foi lido e alcançou o alvo externo sintético;
  a checagem de `external_directory` não canonicalizou o alvo nesse caso. A P4 remove a permissão
  nativa de leitura, mas não revalida essa imposição em uma sessão já aberta.
- **Não testado como negação:** escrita e subagente, porque as ferramentas não foram expostas
  (`ERROR` ≠ `DENIED`). Nenhuma escrita ou agente foi criado.
- **Conclusão histórica:** categoria D parcialmente validada; isolamento forte **não certificado**.

## Permissões e limitações

- Política documental, permissão configurada e controle efetivo são camadas distintas.
- `edit`, `subagent`, `execute`, `skill`, `generate_image`, rede e diretórios externos são negados.
- Ferramentas não expostas são **INDISPONÍVEIS**, não `DENIED`.
- O scanner de shell é best-effort; não se habilitam ferramentas perigosas apenas para obter
  evidência de negação.
- Nenhum daemon, agendamento, banco ou orquestrador externo foi criado.

## Modelos (Free First — D-007 vigente, D-009 proposta)

O modelo não é fixado; a sessão preserva o modelo selecionado. Gratuitos primeiro, DeepSeek Flash
fallback, GLM-5.3/DeepSeek Pro para escalonamento autorizado. Tokens acumulados não medem diretamente
a franquia; `cost: 0` não prova consumo zero.

## Requisitos para despacho futuro (COORD-04/05 — não implementados)

Reserva atômica; ledger persistente; ID imutável; hash do payload; validação mecânica do destinatário;
proteção contra concorrência/duplicidade; bloqueio em UNKNOWN; revogação efetiva; revalidação de HEAD
e ownership; sessão ocupada; reconciliação após reinício; evidência positiva de entrega/não entrega;
**sem exactly-once sem suporte comprovado**. Ver `docs/COORDINATOR.md` §6.1.
