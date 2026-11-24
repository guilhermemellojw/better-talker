# Better Talker — Registro de decisões (DECISIONS)

- **Criação:** 24/11/2026 — direção da coordenação (COORD-02/P1), sob autoridade do mantenedor.
- **Estado de publicação:** proposta em PR Draft; vigência após revisão e merge expressamente autorizados.

> Registro incremental, **somente acréscimo**, de decisões técnicas e operacionais da coordenação do Better Talker. Não substitui fontes primárias: corpos de PR, [TEAM_COORDINATION.md](TEAM_COORDINATION.md), [DELIVERY_POLICY.md](DELIVERY_POLICY.md) e [ACTIVE_TASKS.md](ACTIVE_TASKS.md) continuam sendo a origem das decisões ali registradas. Decisões anteriores à criação deste registro **não são recriadas aqui** — são referenciadas onde ocorreram. Inferências não são convertidas em fatos.

## Regras do registro

1. Toda entrada cita a **evidência primária** que a autorizou (direcionamento, PR, documento datado com SHA).
2. Campos obrigatórios: identificador, data, contexto, decisão, fundamentação, alternativas consideradas, evidências, escopo, autoridade responsável, estado, condições para revisão.
3. Estados possíveis: `PROPOSTA` · `VIGENTE` · `SUPERSEDED` · `REVOGADA` · `SUSPENSA`.
4. Revisão/superação não apaga histórico: a nova entrada referencia a anterior.
5. Datas em UTC; SHAs completos quando relevantes; autoridade sempre identificada; sem dados sensíveis.

## Índice

| ID | Data (UTC) | Resumo | Estado |
| --- | --- | --- | --- |
| D-001 | 2026-11-24 | Aprovação do discovery COORD-01 e direção incremental via API local | VIGENTE |
| D-002 | 2026-11-24 | Criação da governança documental do coordenador | PROPOSTA (este PR) |
| D-003 | 2026-11-24 | Estratégia inicial de modelos e franquia OpenCode Go | PROPOSTA |

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
