# Better Talker — Missões ativas (ACTIVE_TASKS)

**Ciclo:** SPRINT-BETA-01  
**Revisão:** 2026-11-24 / R03 — checkpoints físicos e reconciliação da integração #43  
**Baseline observado da main:** `66cc571fc1e9bddce872ffdee757151a506e696a`  
**Responsável pela coordenação:** coordenação técnica; decisões reservadas ao mantenedor.

> Registro operacional de atribuições, não relatório automático de progresso, não autorização de merge, release, alteração de dados ou uso de dispositivo. Enquanto esta revisão estiver em PR, é uma proposta; sua publicação não substitui a missão mais recente expressamente enviada pela coordenação. Os estados e HEADs abaixo são um snapshot, não uma leitura em tempo real.

## 1. Como usar

- Antes de agir, ler este registro, [TEAM_COORDINATION.md](TEAM_COORDINATION.md) e [DELIVERY_POLICY.md](DELIVERY_POLICY.md), conferir a arquitetura Android atual, a main, PR/HEAD/CI e ownership. Se documentos faltarem no checkout local, consultar a main remota (git show origin/main:docs/TEAM_COORDINATION.md e git show origin/main:docs/DELIVERY_POLICY.md após fetch, ou links GitHub); ausência local não significa ausência no repositório. Não atualizar worktree alheia. Usar o roadmap estratégico do [PR #10](https://github.com/guilhermemellojw/better-talker/pull/10) até sua integração e, depois, ROADMAP.md.
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
| A | MENTOR-CONTINUITY-P7 / #51 | PRONTO PARA QA | Componente publicado; CI/QA pendentes; integração futura após dependências |
| B | UX-QA-CONSOLIDATION-P2 | ATRIBUÍDA | #44 em QA E; revisão independente #49; preparar UX-SIMPLE-P1 |
| C | TRUST / fechamento #43 e #48 | EM EXECUÇÃO (a reconfirmar) | #43 integrado, CI pós-merge pendente; #48 QA por E; propor sequência mínima de TRUST restante |
| D | QA-ACTIVE-TASKS-50 / OPS-P4 | ATRIBUÍDA | P3 concluída; revisar R03; preparar #39 e plano operacional dos checkpoints físicos |
| E | QA-REF-44-P5 + TRUST-P0A | ATRIBUÍDA | Recertificar delta #44 e auditar #48; definir aceite dos checkpoints físicos na matriz beta |

### A — Mentor e continuidade conversacional (P1)

- Objetivo: seletor puro e testável de referente recuperável de histórico existente, com identidade nota/seção/mensagem/estado/escopo, ordenação determinística e falha segura.
- Escopo: novos componentes/testes puros isolados da main, somente se não dependerem de PRs pendentes. Distinguir recuperação, aprofundamento e inserção; card aceito pode ser referente sem autorização de reinsertar. Preservar consentimento e autoria.
- P6 concluída; P7 publicada como [#51](https://github.com/guilhermemellojw/better-talker/pull/51), HEAD `72fd228648887d0686cb4ef0590c3c777a2dd0f0`, dois arquivos novos; 18 testes focados PASS relatados, CI e QA independente pendentes. Não apresentar como funcionalidade já integrada.
- Fora do escopo: `CopilotViewModel.kt`, integração produtiva do #47/#51, nova persistência, mudança de schema ou gate factual; não abrir outro BUILD sem coordenação.
- Dependências para integração futura: #47/#51 e resolução dos contratos/ownership de #42/#48. QA do #51: E preferencial após #44/#48; alternativa C/D se formalmente redistribuído e independente. Não duplicar auditoria.
- Pronto: diff e testes de isolamento, estado, ambiguidade, histórico e ausência de autorização implícita; provar independência e entregar checkpoint.

### B — Referências e UX (P1; risco factual P0 quando aplicável)

- PR [#44](https://github.com/guilhermemellojw/better-talker/pull/44), HEAD `d2698ee0d92bab5faefd8b4f02f97900f42e9d76`.
- Objetivo: recuperar unidades estruturais inequívocas e sinalizar truncamento corretamente. Última auditoria em `f3c987a3` registrou unitAt vazio, limite de passagens sem aviso e fallback TextNotFound. Novo delta em RoomReferenceResolver.kt ainda requer recertificação.
- Escopo: resolutor/reader/fallback e testes estritamente afetados, mediante reproduções. Revisor E; C como alternativa se independente. Não modificar gates TRUST nem arquivos compartilhados sem ownership.
- Pronto: três sondas e regressões pertinentes, CI no HEAD final e parecer independente; documentação de limitações.
- Atividade paralela: revisão independente do #49 (se não contribuiu), consolidar parecer host-only #45 sem nova execução física; preparar UX-SIMPLE-P1 (rótulos de fonte, avisos de parcialidade, hierarquia de card e semântica acessível), sem editar ChatScreen/CopilotViewModel ou iniciar BUILD sem escopo.

### C — TRUST e identidade documental (P0)

- PR [#43](https://github.com/guilhermemellojw/better-talker/pull/43), HEAD `85e189a24ecce5c8524265c24bfcd549fb21edb9`: atomicidade certificada no escopo experimental pelo D ([parecer](https://github.com/guilhermemellojw/better-talker/pull/43#issuecomment-7130451831)), CI do PR verde; merge convencional autorizado e realizado em `66cc571f`; CI pós-merge pendente ([run 45389996766](https://github.com/guilhermemellojw/better-talker/actions/runs/45389996766)). Não repetir instrumentação.
- PR [#48](https://github.com/guilhermemellojw/better-talker/pull/48), HEAD `d0ad009e50a9db9453c6167f024c58bf5bba15cd`: aviso bíblico determinístico (warn-only), QA E pendente. Corrigir reproduções no escopo; não declarar TRUST completo.
- Próxima ação independente: propor sequência mínima de proteção das citações, paráfrases e interpretações nas rotas beta, com evidência documental elegível. BUILD adicional exige escopo definido; não editar CopilotViewModel sem liberação coordenada.
- Pronto: escopo de atomicidade preservado; #48 com parecer e CI no SHA final; lacunas P0 restantes explicitadas. RECORD_MATCH não prova autenticidade ou apoio semântico.

### D — QA operacional e automação (P1)

- P3 concluída: [#47](https://github.com/guilhermemellojw/better-talker/pull/47#issuecomment-7130769068) recertificado (37 PASS, 1 SKIP justificado, 0 FAIL); [#49](https://github.com/guilhermemellojw/better-talker/pull/49#issuecomment-7130812431) CI 2/2 PASS, revisão independente B pendente; [#39](https://github.com/guilhermemellojw/better-talker/pull/39#issuecomment-7130832785) parecer com ressalvas; [#45](https://github.com/guilhermemellojw/better-talker/pull/45#issuecomment-7130848707) proposta host-only, fechado sem merge. Não repetir esses testes.
- Prioridade: revisão documental do #50 R03, preflight dos documentos na main remota e confirmação das missões/ownership. Publicar parecer no SHA novo; não alterar o registro da coordenação.
- OPS-P4: encaminhar ao dono do #39 correções mínimas de Draft, janela e métricas observáveis; verificar especialmente classificação de checks somente skipped/neutral e prevenção de regeneração autorreferente após merge de ROADMAP_STATUS.md. Timestamp não deve causar churn. Se for autor ou receber ownership explícito, preparar delta no próprio PR, sem criar workflow paralelo; recertificação independente obrigatória.
- #45: aguardar decisão expressa de reabertura; sem ADB/instrumentação. #43 não depende de #49.
- QA-PHYSICAL-PREP-01: preparar plano operacional dos checkpoints físicos abaixo, com baseline/APKs, ambiente isolado, proteção de dados, executor, recuperação, evidências e critérios de interrupção. Preparação documental/host-only; execução física depende de autorização específica.
- Pronto: parecer #50, proposta mínima de #39 com critérios de aceite, dependências/ownership confirmados. Apoio a merges apenas com autorização do mantenedor.

### E — QA especializado (P1; riscos P0 prioritários)

- Recertificar por delta o #44 novo HEAD; três sondas anteriores, regressões afetadas e CI. Publicar parecer independente.
- Auditar #48 no escopo warn-only: corpus realmente recuperado/elegível, ausência de TNM, outra tradução, parcial, aviso propagado; distinguir referência, identidade, fidelidade e apoio semântico. Classificar riscos residuais e bloqueadores sem exigir solução integral de TRUST em um único PR.
- #42: parecer focal positivo publicado; não repetir. #43: reutilizar evidências do D, sem nova instrumentação. #47: D consolida QA, sem duplicação.
- QA-PHYSICAL-ACCEPTANCE-01: mapear critérios de aceite dos checkpoints físicos abaixo às linhas existentes da matriz; distinguir smoke, teste exploratório, instrumentação e jornada completa. Não executar testes físicos nesta missão.
- Manter matriz BT-E2E-001 já relatada (14 etapas, 16 cenários adversariais), atualizando somente estados comprovados. Relatório local ainda não conferido pela coordenação.
- Pronto: pareceres por SHA e bloqueadores reproduzíveis; matriz não promove testes isolados a aprovação da jornada completa.

## 3. Fila de integração e ownership

| PR | HEAD observado | Estado de coordenação (snapshot) | Ação |
| --- | --- | --- | --- |
| [#42](https://github.com/guilhermemellojw/better-talker/pull/42) | `e3389251` | QA E positivo, CI push/PR verde | Merge autorizado; aguardar gate pós-merge #43 e reconfirmar preflight |
| [#43](https://github.com/guilhermemellojw/better-talker/pull/43) | `85e189a2` | Integrado em `66cc571f`; CI pós-merge pendente | [Validar pós-merge](https://github.com/guilhermemellojw/better-talker/actions/runs/45389996766) |
| [#44](https://github.com/guilhermemellojw/better-talker/pull/44) | `d2698ee0` | Novo delta; QA/CI a reconfirmar | E recertifica |
| [#47](https://github.com/guilhermemellojw/better-talker/pull/47) | `e7e0a350` | Draft, CI 2/2 verde; QA D positivo | Merge autorizado; após gate #42, preflight e retirada de Draft |
| [#48](https://github.com/guilhermemellojw/better-talker/pull/48) | `d0ad009e` | QA independente pendente | E audita |
| [#49](https://github.com/guilhermemellojw/better-talker/pull/49) | `201c2f22` | Draft; CI 2/2 verde, parecer operacional D | QA independente B |
| [#39](https://github.com/guilhermemellojw/better-talker/pull/39) | `c3acba18` | Parecer D com ressalvas; riscos de classificação/churn a esclarecer | Delta mínimo com owner |
| [#45](https://github.com/guilhermemellojw/better-talker/pull/45) | `8924799a` | Fechado sem merge; proposta host-only publicada | Decisão futura sobre reabertura |
| [#51](https://github.com/guilhermemellojw/better-talker/pull/51) | `72fd2286` | Draft; CI/QA pendentes na última consulta | QA E ou alternativa designada |
| [#50](https://github.com/guilhermemellojw/better-talker/pull/50) | Revisão R03 deste documento | Draft documental | D revisa |

**CopilotViewModel.kt:** #42 e #48 alteram o mesmo arquivo. Nenhum novo BUILD nele nesta rodada; integrar em sequência após autorização e reconferir compatibilidade, QA e CI afetados. A será dono da futura integração do resolvedor quando liberada. Ownership dos arquivos compartilhados deve ser confirmado antes de cada edição, não inferido a partir deste snapshot.

**Gate de dispositivo:** nenhuma reserva/execução física nova autorizada neste registro. A execução única de #43 foi concluída; reutilizar evidências.

## 4. Checkpoints físicos e validação da experiência

**Decisão do mantenedor (24/11/2026):** adotar checkpoints físicos por conjunto funcional integrado e avaliação exploratória periódica quando houver mudanças funcionais. Aprovação do plano não é autorização para instalar APK, usar ADB, executar instrumentação, modificar dados ou realizar chamadas pagas.

| Checkpoint | Pré-condições para certificação | Escopo de aceite |
| --- | --- | --- |
| CHECKPOINT-FÍSICO-01 — Consolidação TRUST | #43, #42, #44 e #48 integrados; CI pós-merge verde; APK rastreável; autorização específica | Recuperação TNM e publicações reais, abertura de fontes, parcialidade/truncamento, fonte indisponível, isolamento entre tópicos, ausência de falsa verificação; preservar dados/autoria |
| CHECKPOINT-FÍSICO-02 — Mentor conversacional | #47 e #51 integrados e conexão produtiva ao ViewModel validada; dependências TRUST resolvidas; APK rastreável; autorização específica | Aprofundamento natural, troca de nota/seção, retomada após reinício, memória confirmada, revisão/aceite/rejeição de cards, geração, preservação de autoria e continuidade |

- D prepara operação e proteção de dados; E define critérios objetivos e controles negativos na matriz BT-E2E-001; A/B/C apoiam com fixtures e contratos das próprias áreas. O mantenedor participa da avaliação de fluidez, utilidade e usabilidade.
- Frequência: JVM/CI por PR; QA independente proporcional; smoke físico por conjunto funcional integrado; avaliação exploratória semanal quando houver mudança funcional e autorização; jornada completa em cada candidato beta. Não esperar todos os marcos se houver regressão de alto risco ou necessidade de validar UX/Android antes: propor smoke focal antecipado, com autorização própria e sem confundir com certificação do checkpoint.
- D entrega plano de preflight com SHA da main e dos APKs, variante/package/assinatura, ambiente de teste e fixtures sintéticas, isolamento da release e dados pessoais, snapshot e verificação de integridade, executor seguro com versão identificada, ações permitidas, rollback e condições de parada. Preferir emulador descartável autorizado quando suficiente. Não presumir que o #45 fechado esteja disponível na main.
- E define casos positivos/negativos, expected/observed, PASS/FAIL/BLOQUEADO/NÃO EXECUTADO/NÃO IMPLEMENTADO/NÃO APLICÁVEL, evidência sanitizada e severidade P0–P3. D/E reutilizam provas anteriores; fingerprint lógico não é igualdade byte a byte. CI de compilação não certifica execução física; teste experimental não certifica rota produtiva.
- Medir fluidez, latência percebida, estabilidade, acessibilidade/TalkBack, voz e consumo de recursos quando pertinentes ao APK e à missão; registrar método e limitações. Nenhuma métrica inventada ou inferida somente de CI.
- Proteções: sem uninstall, pm clear, downgrade, substituição de release, destruição de dados ou connectedAndroidTest indiscriminado. Nenhuma execução real sem autorização específica, preflight aprovado e plano de recuperação. Não repetir os quatro testes instrumentados do #43 sem mudança material ou necessidade demonstrada.
- Estado inicial dos dois checkpoints: **NÃO EXECUTADO / preparação atribuída**. #43 já possui evidência instrumentada própria, que não equivale à validação integrada da jornada.

## 5. Checkpoint mínimo

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
