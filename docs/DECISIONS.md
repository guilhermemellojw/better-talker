# Better Talker — Registro de decisões (DECISIONS)

- **Criação:** 24/11/2026 — direção da coordenação (COORD-02/P1), sob autoridade do mantenedor.
- **Revisão:** 24/11/2026 — correções focais F1–F5 (COORD-02/P2); entradas D-001–D-003 preservadas intactas (append-only).
- **Estado de publicação:** proposta em PR Draft; vigência após revisão e merge expressamente autorizados.

> Registro incremental, **somente acréscimo**, de decisões técnicas e operacionais da coordenação do Better Talker. Não substitui fontes primárias: corpos de PR, [TEAM_COORDINATION.md](TEAM_COORDINATION.md), [DELIVERY_POLICY.md](DELIVERY_POLICY.md) e [ACTIVE_TASKS.md](ACTIVE_TASKS.md) continuam sendo a origem das decisões ali registradas. Decisões anteriores à criação deste registro **não são recriadas aqui** — são referenciadas onde ocorreram. Inferências não são convertidas em fatos.

## Regras do registro

1. Toda entrada cita a **evidência primária** que a autorizou (direcionamento, PR, documento datado com SHA).
2. Campos obrigatórios: identificador, data, contexto, decisão, fundamentação, alternativas consideradas, evidências, escopo, autoridade responsável, estado, condições para revisão.
3. Estados possíveis: `PROPOSTA` · `VIGENTE` · `SUPERSEDED` · `REVOGADA` · `SUSPENSA`.
4. Revisão/superação não apaga histórico: a nova entrada referencia a anterior.
5. Datas em UTC; SHAs completos quando relevantes; autoridade sempre identificada; sem dados sensíveis.
6. O registro cobre **decisões relevantes e mudanças de autoridade**; consultas, mensagens e eventos de sessão de rotina pertencem aos checkpoints e ao ledger operacional da missão, não a este arquivo (D-006).

## Índice

| ID | Data (UTC) | Resumo | Estado |
| --- | --- | --- | --- |
| D-001 | 2026-11-24 | Aprovação do discovery COORD-01 e direção incremental via API local | VIGENTE |
| D-002 | 2026-11-24 | Criação da governança documental do coordenador | PROPOSTA (este PR) |
| D-003 | 2026-11-24 | Estratégia inicial de modelos e franquia OpenCode Go | SUPERSEDED (D-007) |
| D-004 | 2026-11-24 | Contrato real de prevenção de duplicidade e recuperação (F1) | PROPOSTA |
| D-005 | 2026-11-24 | Modos de coordenação SHADOW / ASSISTED / DELEGATED (F2) | PROPOSTA |
| D-006 | 2026-11-24 | Autorização proporcional e contrato de delegação operacional (F3) | PROPOSTA |
| D-007 | 2026-11-24 | Estratégia de modelos e custos — três categorias (F4) | PROPOSTA (substitui D-003) |
| D-008 | 2026-11-24 | Permissões efetivas: política × configuração × controle testado (F5) | PROPOSTA |
| D-009 | 2026-11-24 | Estratégia Free First de coordenação; identidade ≠ modelo (evolução de D-007) | PROPOSTA |

---

## D-001 — Aprovação do discovery COORD-01 e direção de orquestração

- **Data:** 24/11/2026 · **Autoridade:** mantenedor, por direção da coordenação humana · **Estado:** VIGENTE
- **Contexto:** o relatório de discovery COORD-01/P0 (24/11/2026) mapeou o ambiente OpenCode local (v2.3.2, API HTTP em `127.0.0.1`, sessões A–E em modelos gratuitos), o estado do repositório e a documentação de governança, e comparou três alternativas de orquestração.
- **Decisão:** o discovery foi **aprovado com ressalvas**; a **arquitetura incremental baseada na API/SDK local do OpenCode** foi aceita como direção técnica, **preservando integralmente as cinco sessões independentes A–E**; o escopo é exclusivamente o Better Talker; a coordenação operacional permanece com o ChatGPT e o mantenedor; o Agente S **não** está autorizado a distribuir missões aos agentes.
- **Fundamentação:** preserva a equipe, as worktrees e os fluxos existentes; elimina gradualmente a dependência de assinatura externa; adota o caminho de menor risco (somente leitura primeiro; escrita apenas documental e, depois, despachos mediante autorização registrada).
- **Alternativas consideradas:** coordenação manual pura com documentação persistente (mantida como piso); coordenação nativa por agentes/subagentes OpenCode (rejeitada nesta fase por reestruturar a equipe e multiplicar consumo).
- **Evidências:** direcionamento COORD-02/P1 §0 ("COORD-01/P0 aprovado com ressalvas"); relatório COORD-01 (24/11/2026); [TEAM_COORDINATION.md](TEAM_COORDINATION.md) integrado via PR #46 (23/11/2026).
- **Escopo:** coordenação técnica do Better Talker; nenhuma alteração de produto.
- **Condições para revisão:** transição autorizada de despacho de missões; contramandamento do mantenedor; revisão da arquitetura em ciclos subsequentes.

## D-002 — Criação da governança documental do coordenador

- **Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P1); publicação sujeita ao mantenedor · **Estado:** PROPOSTA (este PR)
- **Contexto:** a coordenação exige que o supervisor possa assumir futuramente suas responsabilidades, sobreviver à troca de modelos e recuperar contexto sem depender do histórico do ChatGPT. O discovery COORD-01 verificou que a documentação existente cobre a equipe ([TEAM_COORDINATION.md](TEAM_COORDINATION.md), [ACTIVE_TASKS.md](ACTIVE_TASKS.md), [DELIVERY_POLICY.md](DELIVERY_POLICY.md)) e o status factual ([ROADMAP_STATUS.md](ROADMAP_STATUS.md)), mas **não** o papel do coordenador nem um registro de decisões.
- **Decisão:** criar `docs/COORDINATOR.md` (carta do Agente S: autoridade, limites, distribuição de tarefas, checkpoint, ameaças, custos, recuperação, troca de modelo, suspensão emergencial) e este registro `docs/DECISIONS.md`, em branch exclusiva `docs/coord-supervisor-governance` a partir de `main@b963b936e33a61c5283a49b79b6ae77032d1e8a1`, publicados como **PR Draft** para revisão. **Sem** criação de `AGENTS.md` nesta rodada; **sem** edição de `ACTIVE_TASKS.md`; **sem** duplicar ou contradizer os documentos vigentes; **sem** alterar o PR #10.
- **Fundamentação:** preenche exclusivamente as lacunas verificadas, com o menor escopo documental possível, subordinado aos documentos integrados e revisável antes do merge.
- **Alternativas consideradas:** estrutura `docs/coordination/` completa (rejeitada: duplicaria TEAM_COORDINATION/ACTIVE_TASKS/ROADMAP_STATUS e os drafts do PR #10); aguardar a ferramenta de orquestração pronta (rejeitada: a carta de limites deve existir antes do uso da ferramenta).
- **Evidências:** escopo autorizado no direcionamento COORD-02/P1 §4 e §8; proibições §8; verificação de disponibilidade da branch e da worktree (sem conflito) em 24/11; ausência de `COORDINATOR.md`/`DECISIONS.md` na main verificada em 24/11; versões vigentes de TEAM_COORDINATION/ACTIVE_TASKS (R06)/DELIVERY_POLICY/ROADMAP_STATUS conferidas na main antes da redação.
- **Escopo:** exclusivamente documental; dois arquivos novos; nenhuma alteração funcional.
- **Condições para revisão:** aprovação editorial do mantenedor; revisão independente por um agente (sugestão: D ou E, conforme [TEAM_COORDINATION.md](TEAM_COORDINATION.md) §4) quanto a conflito com documentos vigentes; merge apenas com autorização explícita; reconciliação com o PR #10 antes de qualquer consolidação futura.

## D-003 — Estratégia inicial de modelos e franquia OpenCode Go

- **Data:** 24/11/2026 · **Autoridade:** proposta do Agente S (COORD-02 §7) para deliberação do mantenedor · **Estado:** PROPOSTA
- **Contexto:** a missão exige governança de consumo sem custo adicional à assinatura OpenCode Go. A cota consumida da franquia **não é observável localmente** (`GET /api/experimental/usage/limits` → `data: null` em 24/11) e o campo `cost` das sessões exibiu 0 para todos os agentes — valor registrado que **não prova ausência de consumo**.
- **Decisão (proposta):** (1) modelos gratuitos como primeira escolha das sessões A–E — `opencode/hy4-flash-free` (padrão observado) com `opencode/deepseek-v4.1-flash-free` como fallback; (2) `opencode-go/glm-5.3` para o coordenador **em avaliação** (qualidade das consolidações × custo nominal US$ 1,40/4,40 por 1M tokens entrada/saída), com alternativa gratuita se desfavorável; (3) modelos premium somente por necessidade registrada e autorização explícita; (4) métrica da coordenação = tokens por sessão via `GET /api/session`, coletada no checkpoint; (5) nenhum custo adicional, nenhum modelo de sessão existente alterado por iniciativa do coordenador.
- **Fundamentação:** todas as sessões A–E já operam em modelos gratuitos (observado em 24/11); tokens são o único proxy de consumo disponível localmente; a proposta não altera o estado atual das sessões.
- **Alternativas consideradas:** medir consumo pelo campo `cost` (rejeitada: não confiável); manter GLM-5.3 sem avaliação (rejeitada: custo nominal superior sem critério); migrar imediatamente as sessões para DeepSeek Flash gratuito (rejeitada: exige autorização e alteraria sessões ativas — proibido nesta rodada).
- **Evidências:** catálogo de modelos consultado via API em 24/11 (41 modelos correntes: 26 OpenCode Go + 10 OpenCode + 5 Groq; 48 incluindo versões anteriores; 10 modelos gratuitos no provedor OpenCode, incluindo `deepseek-v4.1-flash-free` e `hy4-flash-free`); campo `model` das sessões A–E verificado em 24/11 22:49Z (`hy4-flash-free`, `big-pickle`); `usage/limits` nulo.
- **Escopo:** política de consumo da coordenação; sem alteração de configuração ou de sessões nesta rodada.
- **Condições para revisão:** deliberação do mantenedor; baseline de tokens coletada nos primeiros ciclos de checkpoint; disponibilidade futura de endpoint de uso.

## D-004 — Contrato real de prevenção de duplicidade e recuperação (F1)

- **Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P2 §2), sob autoridade do mantenedor · **Estado:** PROPOSTA
- **Contexto:** a carta P1 afirmava que "o ID da missão no texto do prompt garante idempotência verificável". A auditoria independente apontou que um identificador permite correlação e detecção de duplicidade, mas **não impede automaticamente duas execuções da mesma missão**.
- **Decisão:** corrigir o contrato documental (§6.1 da carta): ID de missão único e imutável; registro persistente anterior ao despacho; identidade da sessão e da instância de serviço; hash ou identidade estável do payload; ciclo de vida `PROPOSED → AUTHORIZED → DISPATCHING → DELIVERED → RUNNING → COMPLETED | FAILED | UNKNOWN` (ou equivalentes justificados); timeout definido antes do despacho; reconciliação após reinício por consulta a mensagens e inbox; bloqueio de reenvio quando o resultado da entrega for desconhecido; ausência de garantia *exactly-once* sem suporte técnico comprovado. Nenhuma implementação de código nesta rodada; não se presume suporte a chaves de idempotência na API OpenCode.
- **Fundamentação:** idempotência é propriedade de sistema, não convenção de texto; o contrato torna a prevenção auditável e a recuperação determinística, distinguindo entregue, executado e desconhecido.
- **Alternativas consideradas:** manter a afirmação original (rejeitada: promessa sem prova); exigir *exactly-once* imediato (rejeitada: sem suporte técnico comprovado).
- **Evidências:** direcionamento COORD-02/P2 §2 (F1); auditoria independente do diff do PR #55 pela coordenação; COORDINATOR.md §6/§12/§14 na versão P1 (HEAD `23d4b2d82eab59853c77ba63dac7077932a29a46`).
- **Escopo:** exclusivamente documental; sem código, sem configuração, sem armazenamento novo.
- **Condições para revisão:** implementação futura do ledger e dos controles; prova de suporte (ou não) a chaves de idempotência na API; auditoria independente do contrato.

## D-005 — Modos de coordenação SHADOW, ASSISTED e DELEGATED (F2)

- **Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P2 §3), sob autoridade do mantenedor · **Estado:** PROPOSTA (SHADOW descreve o estado atual; nenhum modo novo entra em vigor por este PR)
- **Contexto:** a hierarquia P1 era adequada ao período de transição, mas não devia constituir dependência permanente do ChatGPT.
- **Decisão:** formalizar três modos (§2.1 da carta): **SHADOW** — ChatGPT e mantenedor exercem a coordenação operacional; Agente S observa, verifica e propõe; despacho não autorizado. **ASSISTED** — Agente S executa despachos específicos previamente autorizados, sob supervisão humana e limites estritos de escopo. **DELEGATED** — somente após certificação e autorização explícita do mantenedor; Agente S assume a coordenação operacional dentro de escopos delegados, podendo organizar e distribuir missões elegíveis sem aprovação individual do ChatGPT; ChatGPT torna-se consultor e auditor opcional; mantenedor conserva a autoridade final. A transição entre modos exige decisão registrada do mantenedor.
- **Fundamentação:** elimina a dependência permanente de assinatura externa sem salto de risco; cada modo tem pré-condições verificáveis e o mantenedor mantém autoridade final em todos.
- **Alternativas consideradas:** dependência permanente do ChatGPT (rejeitada: contraria o objetivo de independência); salto direto para DELEGATED (rejeitada: sem certificação prévia nem contrato de delegação).
- **Evidências:** direcionamento COORD-02/P2 §3 (F2); parecer P2 §0 (avaliação favorável com correções focais pendentes).
- **Escopo:** documental; nenhuma autorização de despacho concedida; nenhuma mudança de modo efetivada.
- **Condições para revisão:** decisão do mantenedor sobre piloto ASSISTED; certificação para DELEGATED; contramandamento.

## D-006 — Autorização proporcional e contrato de delegação operacional (F3)

- **Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P2 §4), sob autoridade do mantenedor · **Estado:** PROPOSTA
- **Contexto:** a carta P1 exigia registro em DECISIONS.md para toda transição relevante, o que poderia burocratizar despachos rotineiros futuros e inflar o registro com eventos de rotina.
- **Decisão:** distinguir cinco categorias (§10 da carta): ações críticas (autorização específica do mantenedor); autorizações operacionais delegadas (contrato §10.1); decisões estratégicas e mudanças de autoridade (DECISIONS.md); missões e despachos (ledger operacional + checkpoints, **sem PR documental por despacho**); checkpoints e telemetria de rotina (sem autorização adicional). DECISIONS.md registra decisões relevantes e mudanças de autoridade — não cada consulta, mensagem ou evento de sessão. Definir contrato de delegação com: escopo, agentes elegíveis, tipos de tarefa permitidos, limites de custo, limites temporais ou condições de encerramento, operações proibidas, critérios de suspensão, autoridade concedente e procedimento de revogação. A existência do contrato não autoriza nenhuma ação nesta fase; nenhum armazenamento novo é criado nesta rodada.
- **Fundamentação:** controle proporcional ao risco; o mantenedor delega escopos delimitados sem perder autoridade crítica nem capacidade de revogação imediata.
- **Alternativas consideradas:** autorização individual por despacho para sempre (rejeitada: burocracia excessiva); delegação irrestrita (rejeitada: perda de controle e de rastreabilidade).
- **Evidências:** direcionamento COORD-02/P2 §4 (F3).
- **Escopo:** documental; nenhum contrato de delegação concedido até a presente data.
- **Condições para revisão:** concessão do primeiro contrato pelo mantenedor; revisão após o primeiro ciclo delegado; revogação a qualquer tempo.

## D-007 — Estratégia de modelos e custos: três categorias (F4; substitui D-003)

- **Data:** 24/11/2026 · **Autoridade:** estratégia estabelecida pelo mantenedor, formalizada pela direção COORD-02/P2 §5 · **Estado:** PROPOSTA (substitui D-003; D-003 preservado intacto)
- **Contexto:** D-003 tratava "gratuito" e "sem custo adicional" como equivalentes, não distinguia modelo incluído na franquia OpenCode Go de consumo extraordinário e não previa o fallback DeepSeek Flash na variante Go.
- **Decisão:** preservar a estratégia de utilização estabelecida pelo mantenedor: agentes A–E com modelos gratuitos como primeira opção; ao atingirem limites ou houver indisponibilidade, **DeepSeek Flash como fallback, inclusive a variante OpenCode Go quando disponível e autorizada**; GLM-5.3 como coordenador em avaliação; modelos premium adicionais apenas em uso excepcional e controlado. Distinguir três categorias: (1) modelo gratuito; (2) modelo incluído na franquia OpenCode Go — uso **não equivale necessariamente a cobrança adicional**; (3) consumo extraordinário com cobrança adicional — **não autorizado**. Não assumir disponibilidade permanente de variantes gratuitas. Não alterar modelos de sessões; nenhum custo adicional autorizado.
- **Fundamentação:** precisão de custo evita tanto cobrança não autorizada quanto bloqueio indevido de uso legítimo da franquia; a estratégia do mantenedor é preservada, não substituída.
- **Alternativas consideradas:** manter D-003 sem distinção (rejeitada: imprecisa quanto à franquia); proibir toda variante Go (rejeitada: contraria a estratégia estabelecida pelo mantenedor).
- **Evidências:** direcionamento COORD-02/P2 §5 (F4); catálogo de modelos consultado em 24/11 (`opencode-go/deepseek-v4.1-flash` presente, custo nominal US$ 0,15/0,60 por 1M; `opencode/deepseek-v4.1-flash-free` custo 0); sessões A–E em modelos gratuitos verificadas em 24/11.
- **Escopo:** política de consumo documental; sem alteração de sessões, configuração ou custo.
- **Condições para revisão:** deliberação do mantenedor; baseline de tokens nos primeiros checkpoints; disponibilidade dos modelos; conclusão da avaliação do coordenador.

## D-008 — Permissões efetivas: política × configuração × controle testado (F5)

- **Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P2 §6), sob autoridade do mantenedor · **Estado:** PROPOSTA
- **Contexto:** a carta P1 listava comandos proibidos e permissões propostas sem distinguir claramente política documental de controle técnico efetivo. Listas de proibições e instruções ao modelo **não constituem, por si, barreira de segurança** — são contornáveis por execução indireta.
- **Decisão:** reforçar a distinção em três camadas (§12 da carta): política documental, permissão configurada e controle técnico efetivamente testado. Documentar requisitos futuros (§12.1): privilégio mínimo; bloqueio configurado de mutações não autorizadas; restrição de acesso a credenciais; proteção contra execução indireta por shell, scripts e subagentes; validação da identidade do destinatário; despachos limitados ao escopo autorizado; falha segura diante de ambiguidade. Nenhuma configuração ou permissão real é modificada nesta rodada; nenhum controle está configurado ou testado.
- **Fundamentação:** segurança efetiva exige controles verificáveis, não apenas instruções; a distinção evita falsa sensação de proteção e orienta a implementação futura.
- **Alternativas consideradas:** tratar a lista de proibições como barreira (rejeitada: contornável por execução indireta); implementar permissões agora (rejeitada: proibido nesta rodada).
- **Evidências:** direcionamento COORD-02/P2 §6 (F5); nenhuma permissão salva no ambiente (tabela `permission` com 0 linhas, verificada em COORD-01); configuração global do OpenCode sem agentes personalizados (verificada em COORD-01).
- **Escopo:** documental; sem alteração de configuração ou permissões reais.
- **Condições para revisão:** implementação autorizada dos controles; testes de efetividade incluindo caminhos indiretos (shell, scripts, subagentes); auditoria independente.

## D-009 — Estratégia Free First de coordenação; identidade ≠ modelo (evolução de D-007)

- **Data:** 24/11/2026 · **Autoridade:** direcionamento do mantenedor (COORD-03/P1, Adendo 01), formalizado pelo Agente S · **Estado:** PROPOSTA (evolução de D-007; D-007 permanece vigente até deliberação; D-007 não é alterado)
- **Contexto:** o mantenedor informou que o Agente S utiliza modelo gratuito com resultados satisfatórios e determinou Free First como estratégia experimental. A verificação por evidência (`GET /api/session`) mostra **duas observações na mesma sessão**: no preflight, 24/11 23:32Z, `opencode-go/qwen4-max#xhigh` (não glm-5.3 do cabeçalho de criação e não gratuito de catálogo; nominal US$ 1,50/4,50 por 1M); no fechamento, 24/11 ~23:50Z, **`opencode-go/hungerg-free#default`** (gratuito, custo nominal 0). A mudança foi **observada, não executada pelo Agente S**; não se presume quem a realizou nem se resultados anteriores usarão o modelo atual. A inclusão na franquia OpenCode Go versus cobrança adicional **não é observável localmente** (`usage/limits` nulo; `cost` registrado 0). A premissa "modelo gratuito" do adendo é confirmada apenas no fechamento; o preflight registrou a discrepância sem presumir.
- **Decisão (proposta):** adotar **Free First** como estratégia experimental de coordenação: (1) modelos gratuitos como primeira opção; (2) **DeepSeek Flash** como fallback econômico (variante gratuita `opencode/deepseek-v4.1-flash-free`; variante Go `opencode-go/deepseek-v4.1-flash` quando disponível e autorizada); (3) **GLM-5.3 e DeepSeek Pro** como alternativas de escalonamento de complexidade, mediante autorização; (4) **preservar o modelo atual da sessão** durante a avaliação, sem troca automática; (5) **separar a identidade do supervisor da identidade do modelo** — a configuração persistente `.opencode/agents/supervisor.md` não fixa modelo, permitindo substituição controlada sem perda de governança ou contexto; (6) avaliar qualidade, precisão, segurança, continuidade e consumo com evidências objetivas; (7) não atribuir resultados anteriores a modelos cuja utilização não esteja comprovada; (8) nenhum custo adicional à assinatura OpenCode Go.
- **Fundamentação:** Free First reduz custo e dependência de modelos premium; a separação identidade/modelo permite escalonamento controlado sem reescrever a governança; a preservação do modelo atual evita mudança não autorizada no meio da avaliação.
- **Alternativas consideradas:** fixar glm-5.3 no agente (rejeitada: presumiria modelo não confirmado e forçaria troca); trocar imediatamente para gratuito de catálogo (rejeitada: troca automática proibida nesta rodada); manter D-007 sem evolução (rejeitada: o adendo determina Free First como direção experimental).
- **Evidências:** direcionamento COORD-03/P1 Adendo 01; `GET /api/session` (sessão `ses_df0b668b8ffe0FOXOBjqV9ll9Z`): preflight 24/11 23:32Z = `opencode-go/qwen4-max#xhigh`; fechamento 24/11 ~23:50Z = `opencode-go/hungerg-free#default` (gratuito, cost 0), sem troca executada pelo Agente S; tokens acumulados da sessão no fechamento = input 815831/output 137663/reasoning 130327/cache read 28020169/write 766139; catálogo de modelos (`hungerg-free` custo 0; qwen4-max nominal 1,50/4,50; `deepseek-v4.1-flash-free` custo 0); `GET /api/model/default` = `opencode-go/hungerg-free` (padrão global, não prova do modelo de toda sessão).
- **Escopo:** estratégia de consumo e configuração do supervisor; sem alteração de modelos de sessões existentes, sem custo adicional, sem modificação de D-007.
- **Condições para revisão:** deliberação do mantenedor sobre a discrepância de modelo e sobre Free First; baseline de tokens e avaliação de qualidade nos próximos ciclos; disponibilidade dos modelos gratuitos; autorização para escalonamento a GLM-5.3/DeepSeek Pro.
