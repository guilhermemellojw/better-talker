# Better Talker — Missões ativas (ACTIVE_TASKS)

**Ciclo:** SPRINT-BETA-01  
**Revisão:** 2026-11-24 / R01 — proposta inicial para revisão  
**Baseline observado da main:** `52e75373ceafb9e4c3ce09c6225ae057b6808369`  
**Responsável pela coordenação:** coordenação técnica; decisões reservadas ao mantenedor.

> Registro operacional de atribuições, não relatório automático de progresso, não autorização de merge, release, alteração de dados ou uso de dispositivo. Enquanto esta revisão estiver em PR, é uma proposta; sua publicação não substitui a missão mais recente expressamente enviada pela coordenação. Os estados e HEADs abaixo são um snapshot, não uma leitura em tempo real.

## 1. Como usar

- Antes de agir, ler este registro, [TEAM_COORDINATION.md](TEAM_COORDINATION.md) e [DELIVERY_POLICY.md](DELIVERY_POLICY.md), conferir a arquitetura Android atual, a main, PR/HEAD/CI e ownership. Usar o roadmap estratégico do [PR #10](https://github.com/guilhermemellojw/better-talker/pull/10) até sua integração e, depois, ROADMAP.md.
- Identificar agente, ID/revisão da missão, baseline e escopo. Um prompt posterior explicitamente atribuído pela coordenação pode atualizar a missão; informar a divergência no checkpoint para reconciliar o registro. Em conflito material, interromper somente a atividade afetada; não restaurar specs antigas ou presumir autorização.
- Specs/anexos históricos são referência, não tarefas ativas. FASE 15 web (baseline `b69c6be`) está obsoleta para o produto Android. Não reconstruir a superfície web v1.
- Até um BUILD principal por agente, em branch/worktree exclusiva. QA focal independente pode ocorrer em paralelo. Não escrever em branch de colega nem assumir ownership de arquivo compartilhado.
- Se a missão aguardar CI/QA/merge, executar apenas a alternativa independente já definida. Não repetir discovery ou testes certificados no mesmo SHA sem justificativa; se nenhuma tarefa independente estiver liberada, devolver checkpoint.
- Estados de trabalho: ATRIBUÍDA, EM EXECUÇÃO, PRONTO PARA QA, BLOQUEADA, APROVADA PARA MERGE, CONCLUÍDA. CI, revisão, autorização e integração são dimensões distintas; aprovação técnica não é autorização.
- Alterações de atribuição, prioridade, ownership ou escopo são decisões da coordenação, registradas por PR documental (ou instrução explícita mais recente, a reconciliar). Agentes reportam checkpoints e links; não editar este arquivo simultaneamente nem substituir outros agentes por decisão própria.
- O GitHub é fonte de verdade para código, HEAD, checks e merges. ROADMAP_STATUS.md (proposto no PR #39) pode automatizar observações e métricas, nunca atribuições, prioridades, autorizações ou merges.
- Preservar todas as restrições de TEAM_COORDINATION.md: nenhum merge, push direto na main, release/tag/bump, migração Room, mudança de política TRUST/provedor ou ação destrutiva sem autorização explícita do mantenedor; nenhum ADB/instrumentação física sem autorização específica. Nenhum LLM pago nesta rodada.

## 2. Quadro de missões

As atribuições representam o último direcionamento conhecido; execução efetiva, branch local e disponibilidade devem ser confirmadas pelo agente. IDs abreviados de missão abaixo pertencem ao ciclo SPRINT-BETA-01.

| Agente | Missão primária | Estado observado | Dependências / próxima ação |
| --- | --- | --- | --- |
| A | MENTOR-CONTINUITY-P6 | ATRIBUÍDA | BUILD puro e independente; sem editar ViewModel; encaminhar resultado antes de abrir PR |
| B | BUG-REF-D1 / #44 | PRONTO PARA QA | Novo delta em #44; aguardar QA focal de E, corrigir apenas reproduções |
| C | TRUST / fechamento #43 e #48 | EM EXECUÇÃO (a reconfirmar) | #43 certificado, autorização pendente; #48 QA por E; propor sequência mínima de TRUST restante |
| D | QA-OPS-CONSOLIDATION-P3 | ATRIBUÍDA | Consolidar QA #47, CI #49, revisar #39; proposta #45 separada |
| E | QA-REF-44-P5 + TRUST-P0A | ATRIBUÍDA | Recertificar delta #44 e auditar #48; atualizar matriz beta pontualmente |

### A — Mentor e continuidade conversacional (P1)

- Objetivo: seletor puro e testável de referente recuperável de histórico existente, com identidade nota/seção/mensagem/estado/escopo, ordenação determinística e falha segura.
- Escopo: novos componentes/testes puros isolados da main, somente se não dependerem de PRs pendentes. Distinguir recuperação, aprofundamento e inserção; card aceito pode ser referente sem autorização de reinsertar. Preservar consentimento e autoria.
- Fora do escopo: `CopilotViewModel.kt`, integração produtiva do #47, nova persistência, mudança de schema ou gate factual; não abrir novo PR antes do parecer da coordenação.
- Dependências para integração futura: #47 e resolução dos contratos/ownership de #42/#48. QA independente: E ou D/C conforme capacidade e independência (a designar para o novo BUILD).
- Pronto: diff e testes de isolamento, estado, ambiguidade, histórico e ausência de autorização implícita; provar independência e entregar checkpoint.

### B — Referências e UX (P1; risco factual P0 quando aplicável)

- PR [#44](https://github.com/guilhermemellojw/better-talker/pull/44), HEAD `d2698ee0d92bab5faefd8b4f02f97900f42e9d76`.
- Objetivo: recuperar unidades estruturais inequívocas e sinalizar truncamento corretamente. Última auditoria em `f3c987a3` registrou unitAt vazio, limite de passagens sem aviso e fallback TextNotFound. Novo delta em RoomReferenceResolver.kt ainda requer recertificação.
- Escopo: resolutor/reader/fallback e testes estritamente afetados, mediante reproduções. Revisor E; C como alternativa se independente. Não modificar gates TRUST nem arquivos compartilhados sem ownership.
- Pronto: três sondas e regressões pertinentes, CI no HEAD final e parecer independente; documentação de limitações.

### C — TRUST e identidade documental (P0)

- PR [#43](https://github.com/guilhermemellojw/better-talker/pull/43), HEAD `85e189a24ecce5c8524265c24bfcd549fb21edb9`: atomicidade certificada no escopo experimental pelo D ([parecer](https://github.com/guilhermemellojw/better-talker/pull/43#issuecomment-7130451831)), CI verde; aguarda autorização do mantenedor. Não repetir instrumentação.
- PR [#48](https://github.com/guilhermemellojw/better-talker/pull/48), HEAD `d0ad009e50a9db9453c6167f024c58bf5bba15cd`: aviso bíblico determinístico (warn-only), QA E pendente. Corrigir reproduções no escopo; não declarar TRUST completo.
- Próxima ação independente: propor sequência mínima de proteção das citações, paráfrases e interpretações nas rotas beta, com evidência documental elegível. BUILD adicional exige escopo definido; não editar CopilotViewModel sem liberação coordenada.
- Pronto: escopo de atomicidade preservado; #48 com parecer e CI no SHA final; lacunas P0 restantes explicitadas. RECORD_MATCH não prova autenticidade ou apoio semântico.

### D — QA operacional e automação (P1)

- #47: consolidar evidências F1–F6 e motivo do SKIP, confirmar independência e SHA; não repetir auditoria certificada. Publicar parecer.
- #49: confirmar execução dos dois gates androidTest e testes anteriores, controles negativos, CI e duração. Se contribuiu materialmente, solicitar revisão B/E/C independente, sem autocertificar.
- #39: depois dos gates anteriores, revisão focal de segurança, permissões, ciclos, estados e métricas do roadmap; não duplicar a implementação ou automatizar atribuições.
- #45: encerrado sem merge; preservar executor validado e propor CI host-only/reabertura apenas mediante decisão explícita. Sem nova instrumentação.
- Pronto: pareceres/links por SHA, bloqueadores e próximas decisões; #43 não depende de #49.

### E — QA especializado (P1; riscos P0 prioritários)

- Recertificar por delta o #44 novo HEAD; três sondas anteriores, regressões afetadas e CI. Publicar parecer independente.
- Auditar #48 no escopo warn-only: corpus realmente recuperado/elegível, ausência de TNM, outra tradução, parcial, aviso propagado; distinguir referência, identidade, fidelidade e apoio semântico. Classificar riscos residuais e bloqueadores sem exigir solução integral de TRUST em um único PR.
- #42: parecer focal positivo publicado; não repetir. #43: reutilizar evidências do D, sem nova instrumentação. #47: D consolida QA, sem duplicação.
- Manter matriz BT-E2E-001 já relatada (14 etapas, 16 cenários adversariais), atualizando somente estados comprovados. Relatório local ainda não conferido pela coordenação.
- Pronto: pareceres por SHA e bloqueadores reproduzíveis; matriz não promove testes isolados a aprovação da jornada completa.

## 3. Fila de integração e ownership

| PR | HEAD observado | Estado de coordenação (snapshot) | Ação |
| --- | --- | --- | --- |
| [#42](https://github.com/guilhermemellojw/better-talker/pull/42) | `e3389251` | QA E positivo, CI push/PR verde | Autorização do mantenedor, preflight |
| [#43](https://github.com/guilhermemellojw/better-talker/pull/43) | `85e189a2` | QA D positivo, CI verde | Autorização do mantenedor, preflight |
| [#44](https://github.com/guilhermemellojw/better-talker/pull/44) | `d2698ee0` | Novo delta; QA/CI a reconfirmar | E recertifica |
| [#47](https://github.com/guilhermemellojw/better-talker/pull/47) | `e7e0a350` | Draft, CI 2/2 verde; parecer formal pendente | D consolida evidências |
| [#48](https://github.com/guilhermemellojw/better-talker/pull/48) | `d0ad009e` | QA independente pendente | E audita |
| [#49](https://github.com/guilhermemellojw/better-talker/pull/49) | `201c2f22` | Draft; push verde, PR em execução na última consulta | D consolida CI; revisor independente |
| [#39](https://github.com/guilhermemellojw/better-talker/pull/39) | `c3acba18` | Revisão pendente | D revisa |
| [#45](https://github.com/guilhermemellojw/better-talker/pull/45) | `8924799a` | Fechado sem merge | Decisão futura sobre reabertura |

**CopilotViewModel.kt:** #42 e #48 alteram o mesmo arquivo. Nenhum novo BUILD nele nesta rodada; integrar em sequência após autorização e reconferir compatibilidade, QA e CI afetados. A será dono da futura integração do resolvedor quando liberada. Ownership dos arquivos compartilhados deve ser confirmado antes de cada edição, não inferido a partir deste snapshot.

**Gate de dispositivo:** nenhuma reserva/execução física nova autorizada neste registro. A execução única de #43 foi concluída; reutilizar evidências.

## 4. Checkpoint mínimo

```text
Agente / ID / revisão da missão:
Main / branch / HEAD / PR:
Estado e entrega:
Evidência e CI (links):
Testes não realizados:
Bloqueador e dependência:
Arquivos compartilhados / ownership:
Parecer / próxima ação proposta:
Autorização necessária:
```

Atualizar este registro somente em mudança material de tarefa, dependência, ownership ou decisão de integração; não gerar commits a cada polling de CI. Relatórios extensos ficam nos PRs/relatórios técnicos. Não publicar dados privados, segredos, snapshots de banco ou identificadores de dispositivo.
