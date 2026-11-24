# Better Talker — Registro de decisões (DECISIONS)

- **Criação:** 24/11/2026 — direção da coordenação (COORD-02/P1).
- **Estado de publicação:** proposta em PR Draft; vigência somente após revisão e merge expressamente autorizados.

> Registro incremental, **só-acréscimo**, de decisões técnicas e operacionais da coordenação do Better Talker. Este arquivo **não substitui fontes primárias**: decisões tomadas em corpos de PR, em [TEAM_COORDINATION.md](TEAM_COORDINATION.md) e em [DELIVERY_POLICY.md](DELIVERY_POLICY.md) permanecem registradas onde ocorreram e são **referenciadas, não duplicadas**. Decisões anteriores à criação deste registro não são recriadas aqui; inferências não viram fatos.

## Regras

1. Toda entrada cita a **evidência primária** que a autorizou (direcionamento, PR, documento datado com SHA).
2. Campos obrigatórios: identificador, data, contexto, decisão, fundamentação, alternativas consideradas, evidências, escopo, autoridade responsável, estado, condições para revisão.
3. Estados possíveis: `PROPOSTA` · `VIGENTE` · `SUPERSEDED` · `REVOGADA` · `SUSPENSA`.
4. Revisão ou superação não apaga histórico: a nova entrada referencia a anterior.
5. Datas em UTC; SHAs completos quando pertinentes; nenhum dado sensível.

## Índice

| ID | Data (UTC) | Resumo | Estado |
| --- | --- | --- | --- |
| D-001 | 2026-11-24 | Aprovação do discovery COORD-01 e direção incremental via API local | VIGENTE |
| D-002 | 2026-11-24 | Governança documental do coordenador: COORDINATOR.md e DECISIONS.md | PROPOSTA (este PR) |
| D-003 | 2026-11-24 | Estratégia inicial de modelos e franquia OpenCode Go | PROPOSTA |

---

## D-001 — Aprovação do discovery COORD-01 e direção de orquestração

**Data:** 24/11/2026 · **Autoridade:** mantenedor, por direção da coordenação humana · **Estado:** VIGENTE

- **Contexto:** o relatório de discovery COORD-01/P0 (24/11/2026, sessão "OpenCode coordination discovery") mapeou o ambiente OpenCode v2.3.2, o repositório, os agentes A–E, a documentação de governança e comparou três alternativas de orquestração.
- **Decisão:** o discovery foi **aprovado com ressalvas**; a arquitetura incremental baseada na **API/SDK local do OpenCode** foi aceita como direção técnica, **preservando integralmente as cinco sessões independentes A–E**; o escopo é exclusivamente o Better Talker; a coordenação operacional permanece com o ChatGPT e o mantenedor; o Agente S **não** está autorizado a distribuir missões.
- **Fundamentação:** menor risco incremental (somente-leitura primeiro; escrita apenas documental e, depois, mediante autorização), eliminação gradual da dependência de assinatura externa e preservação da equipe e das worktrees existentes.
- **Alternativas consideradas:** coordenação manual pura com documentação persistente (mantida como piso); coordenação nativa por agentes/subagentes OpenCode (rejeitada nesta fase por reestruturar a equipe e multiplicar consumo).
- **Evidências:** direcionamento COORD-02/P1 §0 ("COORD-01/P0 aprovado com ressalvas"); relatório COORD-01 de 24/11/2026; [TEAM_COORDINATION.md](TEAM_COORDINATION.md) integrado via PR #46 (23/11/2026).
- **Escopo:** coordenação técnica do Better Talker; sem alteração de produto, código ou atribuições.
- **Condições para revisão:** autorização expressa de despacho de missões; contramandamento do mantenedor; revisão da arquitetura após COORD-03.

## D-002 — Governança documental do coordenador

**Data:** 24/11/2026 · **Autoridade:** direção da coordenação (COORD-02/P1); publicação sujeita ao mantenedor · **Estado:** PROPOSTA (este PR)

- **Contexto:** a coordenação precisa sobreviver à troca de modelos e recuperar contexto sem depender do histórico do ChatGPT; a documentação de equipe ([TEAM_COORDINATION.md](TEAM_COORDINATION.md), [ACTIVE_TASKS.md](ACTIVE_TASKS.md)) já existe na main e não deve ser duplicada.
- **Decisão:** criar `docs/COORDINATOR.md` (carta do coordenador: autoridade, limites, checkpoint, modelo de ameaças, franquia, recuperação, troca de modelo, suspensão emergencial) e **este registro**, em branch exclusiva `docs/coord-supervisor-governance`, publicados como **PR Draft** para revisão. **Sem** criação de `AGENTS.md` nesta rodada; **sem** edição de `ACTIVE_TASKS.md`, `TEAM_COORDINATION.md`, `DELIVERY_POLICY.md` ou `ROADMAP_STATUS.md`; **sem** alteração de código Android, sessões A–E ou worktrees alheias.
- **Fundamentação:** preenche as duas lacunas verificadas (carta do coordenador e log de decisões) com o menor escopo documental possível, subordinado aos documentos integrados.
- **Alternativas consideradas:** estrutura `docs/coordination/` completa (rejeitada: duplicaria TEAM_COORDINATION/ACTIVE_TASKS/ROADMAP_STATUS e os drafts do PR #10); aguardar COORD-03 para documentar (rejeitada: a continuidade exige registro antes do próximo ciclo).
- **Evidências:** escopo autorizado no direcionamento COORD-02/P1 §4 e §8; proibições §8; verificação de ausência de branch/worktree homônimas e de conflito documental com a main `b963b936e33a61c5283a49b79b6ae77032d1e8a1`.
- **Escopo:** exclusivamente documental; dois arquivos novos.
- **Condições para revisão:** parecer do mantenedor sobre o PR; revisão independente por agente (sugestão: D ou E, conforme [TEAM_COORDINATION.md](TEAM_COORDINATION.md) §4); reconciliação com o PR #10 antes de qualquer consolidação futura.

## D-003 — Estratégia inicial de modelos e franquia OpenCode Go

**Data:** 24/11/2026 · **Autoridade:** proposta do Agente S sob o escopo COORD-02 §7; adoção aguarda o mantenedor · **Estado:** PROPOSTA

- **Contexto:** as sessões A–E já operam em modelos gratuitos (`opencode/hy4-flash-free`, `opencode/big-pickle`, custo de catálogo 0, observado em 24/11/2026); a cota efetiva da franquia não é observável localmente (`GET /api/experimental/usage/limits` → `data: null`); o campo `cost: 0` registrado por sessão **não** prova ausência de consumo.
- **Decisão (proposta):** modelos gratuitos como primeira escolha (A–E e rotina do coordenador); `deepseek-v4.1-flash-free` como fallback; `glm-5.3` mantido apenas como modelo do coordenador **em avaliação**; modelos premium somente com necessidade demonstrada e autorização registrada; nenhum custo adicional à assinatura OpenCode Go vigente; **nenhuma alteração de modelo das sessões existentes** nesta rodada.
- **Fundamentação:** mantém o consumo dentro da franquia com monitoramento possível (tokens por sessão via `GET /api/session`) e torna a decisão sobre premium explícita e registrada.
- **Alternativas consideradas:** manter `glm-5.3` em todas as funções (rejeitado: preço nominal superior, sem avaliação de qualidade); migração total imediata para `deepseek-v4.1-flash-free` (adiada: exige avaliação de qualidade e alteração de modelos das sessões, hoje proibida).
- **Evidências:** catálogo de modelos consultado em 24/11/2026 (41 modelos correntes; `hy4-flash-free` e `deepseek-v4.1-flash-free` ativos, custo 0); campo `model` das sessões A–E verificado em 24/11/2026 ~22:49Z via `GET /api/session`.
- **Escopo:** política de consumo da coordenação; não vincula as sessões A–E sem direcionamento próprio do mantenedor.
- **Condições para revisão:** baseline de tokens do primeiro ciclo de checkpoints; disponibilidade dos modelos gratuitos; conclusão da avaliação de qualidade do coordenador.
