# Better Talker — Missões ativas (ACTIVE_TASKS)

**Ciclo:** SPRINT-BETA-01  
**Revisão:** 2026-11-25 / R07 — reconciliação operacional pós-#60 e ownership restrita do ViewModel  
**Baseline observado da main:** `4ae783759086353d55edcf31b587d51a491b0ebd`  
**Histórico:** R06 em `70cd7513`; decisões e restrições anteriores permanecem no Git e nas seções abaixo, salvo onde R07 as substitui explicitamente.  
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
- O GitHub é fonte de verdade para código, HEAD, checks e merges. ROADMAP_STATUS.md (proposto no PR #39) pode automatizar observações e métricas, nunca atribuições, prioridades, autorizações ou merges. Nenhuma automação escreve ACTIVE_TASKS.md.
- Preservar todas as restrições de TEAM_COORDINATION.md: nenhum merge, push direto na main, release/tag/bump, migração Room, mudança de política TRUST/provedor ou ação destrutiva sem autorização explícita do mantenedor; nenhum ADB/instrumentação física sem autorização específica. Nenhum LLM pago nesta rodada.

## 2. Quadro de missões

As atribuições representam o último direcionamento conhecido; execução efetiva, branch local e disponibilidade devem ser confirmadas pelo agente. IDs abreviados de missão abaixo pertencem ao ciclo SPRINT-BETA-01.

| Agente | Missão primária | Estado observado | Dependências / próxima ação |
| --- | --- | --- | --- |
| A | PEDAGOGY-1-MIN / P6 (#62) | EM EXECUÇÃO | Integração produtiva restrita ao ViewModel; fronteiras TRUST certificadas intocáveis; HEAD `7476cf79` aberto, CI não observado |
| B | BETA-A11Y-MIN / P1 | ATRIBUÍDA | Acessibilidade mínima do beta; não duplicar QA de E nem homologação de D |
| C | RECONCILIAÇÃO OPERACIONAL / P2 | CONCLUÍDA | R07 publicada no PR #65, em revisão final; disponível para QA independente das entregas de B |
| D | HOMOLOGAÇÃO FÍSICA | AGUARDANDO AUTORIZAÇÃO | Checkpoints NÃO EXECUTADOS; exige autorização específica, preflight aprovado e plano de recuperação |
| E | TRUST-1A / #11 + QA do delta produtivo #62 | EM EXECUÇÃO | #11 aberto (`d917df70`); prioridade de QA em #62; não repetir #48/#52/#60 certificados |
| S | COORD-04 / #64 | EM EXECUÇÃO | Fundação de comunicação sintética; fronteira exclusivamente sintética, sem dados reais |

**R07 — como ler:** as seções detalhadas por agente abaixo são o histórico R06, preservado para decisões e restrições; em conflito de estado com o quadro acima, prevalece o quadro R07. Nenhuma política de entrega, prioridade do mantenedor ou restrição foi alterada.

**R07 — classificação da informação:**
- **Evidência confirmada** (GitHub/CI/QA): integrações #39, #42–#45, #47–#53, #56–#61 e #63; #60 em `252bba6c` com CI pós-merge `45664202428` SUCCESS e parecer P5 FINAL aprovado; #43 com instrumentação A34 reutilizada; #48/#52/#60 com pareceres independentes.
- **Relato de agente** (sem verificação independente nova): plano físico de D e matriz de E — preparação documental entregue, **execução NÃO realizada**; detalhes de snapshots locais não conferidos pela coordenação.
- **Estado desconhecido / não observado**: CI e QA dos HEADs abertos #11 (`d917df70`), #54 (`428f9f37`), #62 (`7476cf79`) e #64 (`e8b02798`) — nenhum run de CI associado observado nesta revisão; disponibilidade de branch/HEAD do agente deve ser confirmada no preflight.

**R07 — referências operacionais corrigidas:** #44 não está mais bloqueado (integrado em `301f4470`); #39/#45/#48/#49/#50/#51/#52/#60 deixaram de estar em Draft/pendentes; #47 não tem CI pós-merge em execução; #54/#62/#64 passam a constar como abertos. Nenhuma dessas correções altera autorizações passadas nem cria novas.

### A — Mentor e continuidade conversacional (P1)

- Objetivo: seletor puro e testável de referente recuperável de histórico existente, com identidade nota/seção/mensagem/estado/escopo, ordenação determinística e falha segura.
- Escopo: novos componentes/testes puros isolados da main, somente se não dependerem de PRs pendentes. Distinguir recuperação, aprofundamento e inserção; card aceito pode ser referente sem autorização de reinsertar. Preservar consentimento e autoria.
- P6 concluída; P7 publicada como [#51](https://github.com/guilhermemellojw/better-talker/pull/51), HEAD `72fd228648887d0686cb4ef0590c3c777a2dd0f0`, dois arquivos novos; 18 testes focados e 4338 JVM PASS relatados; CI 2/2 verde confirmado; [QA E aprovado](https://github.com/guilhermemellojw/better-talker/pull/51#issuecomment-7131956100), 18/18 PASS no escopo puro. Merge depende de autorização do mantenedor. Não apresentar como funcionalidade já integrada.
- Fora do escopo: `CopilotViewModel.kt`, integração produtiva do #47/#51, nova persistência, mudança de schema ou gate factual; não abrir outro BUILD sem coordenação.
- Dependências para integração futura: #47/#51 e resolução dos contratos/ownership de #42/#48. QA #51 concluído; P8/checkpoint concluídos. Não repetir discovery/QA; aguardar autorização e gates da integração produtiva.
- Pronto: diff e testes de isolamento, estado, ambiguidade, histórico e ausência de autorização implícita; provar independência e entregar checkpoint.

### B — Referências e UX (P1; risco factual P0 quando aplicável)

- PR [#44](https://github.com/guilhermemellojw/better-talker/pull/44), HEAD `d2698ee0d92bab5faefd8b4f02f97900f42e9d76`.
- Objetivo: recuperar unidades estruturais inequívocas e sinalizar truncamento corretamente. QA E em d2698ee0 e CI 2/2 verde: unitAt vazio corrigido; ainda BLOQUEADO por [duas falhas](https://github.com/guilhermemellojw/better-talker/pull/44#issuecomment-7130887905): SourceReaderLoader perde UnitSliceResult.truncated; CopilotRepository não protege fallback TextNotFound.
- Escopo: resolutor/reader/fallback e testes estritamente afetados, mediante reproduções. Revisor E; C como alternativa se independente. Não modificar gates TRUST nem arquivos compartilhados sem ownership.
- BUILD P6: propagar truncamento até SourceReaderContent/estado visível (sem promover parcial a completa); proteger fallback TextNotFound; dois controles negativos e regressões afetadas, CI no HEAD final, recertificação E. Confirmar ownership dos arquivos e sincronização convencional com main se necessária, sem force-push.
- #49: parecer B positivo em 201c2f22; novo HEAD 117e14c5 tem delta exclusivamente documental, CI final SUCCESS (run 45391860109); recertificar apenas delta documental, sem repetir testes. #45 reconciliado. UX-SIMPLE-P1 preparado, aguarda fechamento #44 para não duplicar reader/truncamento; sem BUILD paralelo.

### C — TRUST e identidade documental (P0)

- PR [#43](https://github.com/guilhermemellojw/better-talker/pull/43), HEAD `85e189a24ecce5c8524265c24bfcd549fb21edb9`: atomicidade certificada no escopo experimental pelo D ([parecer](https://github.com/guilhermemellojw/better-talker/pull/43#issuecomment-7130451831)), CI do PR verde; merge convencional autorizado e realizado em `66cc571f`; CI pós-merge SUCCESS ([run 45389996766](https://github.com/guilhermemellojw/better-talker/actions/runs/45389996766)). Não repetir instrumentação.
- PR [#48](https://github.com/guilhermemellojw/better-talker/pull/48): [QA E bloqueou controle 10](https://github.com/guilhermemellojw/better-talker/pull/48#issuecomment-7131875240) no HEAD d0ad009e; [C publicou correção](https://github.com/guilhermemellojw/better-talker/pull/48#issuecomment-7131969334) em `a2eb8d696fe50895fcf69324a97a3cd9f288f05d`. Helper calcula aviso bíblico sempre, 16/16 testes relatados; merge da main incluído no branch. CI e recertificação E pendentes; confirmar preservação do #42. Não mudar política TRUST nem apresentar warn-only como verificação semântica.
- Próxima ação independente: propor sequência mínima de proteção das citações, paráfrases e interpretações nas rotas beta, com evidência documental elegível. BUILD adicional exige escopo definido; não editar CopilotViewModel sem liberação coordenada.
- Pronto: escopo de atomicidade preservado; #48 com parecer e CI no SHA final; lacunas P0 restantes explicitadas. RECORD_MATCH não prova autenticidade ou apoio semântico.

### D — QA operacional e automação (P1)

- P3 concluída: [#47](https://github.com/guilhermemellojw/better-talker/pull/47#issuecomment-7130769068) recertificado (37 PASS, 1 SKIP justificado, 0 FAIL); [#49](https://github.com/guilhermemellojw/better-talker/pull/49#issuecomment-7130812431) CI 2/2 PASS, revisão independente B pendente; [#39](https://github.com/guilhermemellojw/better-talker/pull/39#issuecomment-7130832785) parecer com ressalvas; [#45](https://github.com/guilhermemellojw/better-talker/pull/45#issuecomment-7130848707) proposta host-only, fechado sem merge. Não repetir esses testes.
- P4/P5 concluídas. [Parecer R04](https://github.com/guilhermemellojw/better-talker/pull/50#issuecomment-7131949726) aprovado com ressalvas menores. #39 delta publicado em `c6cffe04ab9c693c08195e976aebfcdabcf150b9` ([evidências](https://github.com/guilhermemellojw/better-talker/pull/39#issuecomment-7131924709)), 11/11 testes relatados; sem workflow CI no HEAD observado. Preservar SHA; E revisa quando livre, B alternativa somente após #44; D não autocertifica.
- P6 concluída: [resumo sanitizado e checklist físico](https://github.com/guilhermemellojw/better-talker/pull/50#issuecomment-7132103202), [parecer R05 aprovado sem correções indispensáveis](https://github.com/guilhermemellojw/better-talker/pull/50#issuecomment-7132119530). Não repetir revisão ampla; reconciliação R06 é factual e restrita. Aguardar QA #39 e autorização específica para #45/dispositivo; preparar somente lacuna concreta. Sem ADB.
- #45: aguardar decisão expressa de reabertura; sem ADB/instrumentação. #43 não depende de #49.
- QA-PHYSICAL-PREP-01: plano operacional entregue conforme relato D; resumo sanitizado publicado no PR #50 (7132103202); arquivo local completo não conferido pela coordenação. Preparação documental/host-only concluída; execução física depende de autorização específica.
- Pronto: parecer #50, proposta mínima de #39 com critérios de aceite, dependências/ownership confirmados. Apoio a merges apenas com autorização do mantenedor.

### E — QA especializado (P1; riscos P0 prioritários)

- #44 P5 concluída; dois bloqueadores encaminhados a B. Recertificar somente deltas/sondas afetadas após novo HEAD.
- #48: parecer publicado; recertificar prioritariamente delta a2eb8d69 (controle 10, avisos simultâneos, integração verifyChatText e preservação #42). #51 aprovado em 72fd2286; não repetir. Enquanto aguarda #44, revisar #39 c6cffe04 de modo independente, se capacidade permitir; atenção à segurança dos triggers, não mascarar erro de comparação como NO_CHANGE, idempotência e métricas. B alternativa somente após #44, sem QA duplicado.
- #42/#43 integrados com CI pós-merge SUCCESS; #47 integrado, CI pós-merge em execução. Reutilizar evidências sem nova instrumentação; não repetir QA #47.
- QA-PHYSICAL-ACCEPTANCE-01: critérios entregues conforme relato E (CP-SMOKE/INSTR/SOURCE/CONTEXT/CONSENT/DIALOG/A11Y/JOURNEY); disponibilizar matriz sanitizada junto a D. Não executar testes físicos nesta missão.
- Manter matriz BT-E2E-001 já relatada (14 etapas, 16 cenários adversariais), atualizando somente estados comprovados. Relatório local ainda não conferido pela coordenação.
- Pronto: pareceres por SHA e bloqueadores reproduzíveis; matriz não promove testes isolados a aprovação da jornada completa.

## 3. Fila de integração e ownership

> **R07 — histórico R06:** a tabela de integração e as notas por PR abaixo pertencem ao snapshot R06 e **não têm valor de atribuição vigente**. Estados vigentes e ownership atualizada estão no quadro da seção 2 e na nota sobre `CopilotViewModel.kt` ao final desta seção. As entradas são preservadas apenas como histórico de decisões e integrações já realizadas.

| PR | HEAD observado | Estado de coordenação (snapshot) | Ação |
| --- | --- | --- | --- |
| [#42](https://github.com/guilhermemellojw/better-talker/pull/42) | `e3389251` | Integrado em `1e1b7a9f`; CI pós-merge SUCCESS | [Evidência](https://github.com/guilhermemellojw/better-talker/actions/runs/45395381894) |
| [#43](https://github.com/guilhermemellojw/better-talker/pull/43) | `85e189a2` | Integrado em `66cc571f`; CI pós-merge SUCCESS | [Evidência](https://github.com/guilhermemellojw/better-talker/actions/runs/45389996766) |
| [#44](https://github.com/guilhermemellojw/better-talker/pull/44) | `d2698ee0` | CI 2/2 verde; BLOQUEADO (2 defeitos) | B corrige; E recertifica |
| [#47](https://github.com/guilhermemellojw/better-talker/pull/47) | `e7e0a350` | Integrado em `cba5526b`; CI pós-merge em execução | [Validar pós-merge](https://github.com/guilhermemellojw/better-talker/actions/runs/45398656121) |
| [#48](https://github.com/guilhermemellojw/better-talker/pull/48) | `a2eb8d69` | Correção publicada; CI/QA pendentes | E recertifica |
| [#49](https://github.com/guilhermemellojw/better-talker/pull/49) | `117e14c5` | Draft; QA D/B positivo no HEAD anterior 201c2f22; delta só relatório, CI final SUCCESS | B recertifica delta; autorização ainda necessária |
| [#39](https://github.com/guilhermemellojw/better-talker/pull/39) | `c6cffe04` | Delta D publicado, 11/11 testes relatados, sem CI observado | E QA independente, B alternativa |
| [#45](https://github.com/guilhermemellojw/better-talker/pull/45) | `8924799a` | Fechado sem merge; proposta host-only publicada | Decisão futura sobre reabertura |
| [#51](https://github.com/guilhermemellojw/better-talker/pull/51) | `72fd2286` | Draft; CI 2/2 verde, QA E aprovado | Autorização do mantenedor |
| [#50](https://github.com/guilhermemellojw/better-talker/pull/50) | Revisão R06 deste documento | Draft documental; R05 aprovado, R06 reconciliação factual | Conferência focal e autorização do mantenedor |

**CopilotViewModel.kt (R07):** ownership transferida a **A**, restrita à integração PEDAGOGY-1-MIN / P6 (#62), após a liberação formal registrada no P8 e no PR #62. Fronteiras TRUST certificadas — call sites de `verifyChatText()`, deduplicação tipada, `retrievedExcerpt()` em `answerAsk()`, seam de continuidade #52, consentimento/autoria/ciclo do turno e não promoção factual — permanecem intocáveis; qualquer alteração adicional exige autorização e recertificação. C concluiu TRUST-P0A-2 em #60 (integrado) e não detém mais ownership do ViewModel. A nota R06 acima ("C tem exclusividade na correção #48") é histórica e foi superada por esta. Ownership de arquivos compartilhados deve ser confirmada no preflight antes de cada edição.

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
- Estado dos dois checkpoints: **NÃO EXECUTADO / preparação operacional e critérios entregues conforme relatos D/E**. [Resumo sanitizado publicado](https://github.com/guilhermemellojw/better-talker/pull/50#issuecomment-7132103202); relatórios locais completos ainda não conferidos. Smoke sem LLM pago: usar fixture/local se disponível; geração remota não autorizada deve ficar NÃO EXECUTADA. Snapshot/rollback precisam esclarecer restauração de dados, não apenas APK/IME; ausência de WAL/SHM exige verificar estado SQLite consistente, não presumir corrupção. #43 já possui evidência instrumentada própria, que não equivale à validação integrada da jornada.

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

## 6. R07 — registro da revisão (proposta documental)

- Origem: relatório P1 `docs/reports/2026-11-25-coord-reconciliation-p1.md` (reutilizado, sem repetir discovery). Aprovação da coordenação para publicar esta revisão.
- Estado dos checkpoints físicos permanece **NÃO EXECUTADO / AGUARDANDO AUTORIZAÇÃO** — plano (D) e critérios (E) são relatos de preparação, não evidência de execução. Nenhum ADB, dispositivo ou LLM pago nesta revisão.
- Mudanças aplicadas: cabeçalho/baseline R07; quadro de missões com as atribuições vigentes (A/B/C/D/E/S); classificação evidência × relato × desconhecido; referências operacionais corrigidas; ownership restrita do `CopilotViewModel.kt` para A.
- Ajustes P3: seção 3 marcada explicitamente como histórico R06, sem valor de atribuição vigente; missão de C registrada como **CONCLUÍDA**, disponível para QA independente das entregas de B; HEAD de #54 atualizado para `428f9f37` (únicos estados observados que mudaram desde o relatório P1).
- Histórico preservado: R06 (`70cd7513`) e decisões anteriores continuam no Git; seções detalhadas abaixo do quadro são o registro R06 e só prevalecem onde R07 não as substituiu.
- Fora de escopo: `ROADMAP_STATUS.md` (automação), roadmap estratégico, supervisor, código Android/schema e qualquer nova autorização de merge/dispositivo. R07 é proposta em PR Draft, sujeita à revisão independente; não autoriza merge, release, bump nem uso de dispositivo.
