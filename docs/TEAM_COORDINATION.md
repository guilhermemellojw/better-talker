# Better Talker — Coordenação integrada de cinco agentes

**Decisão do mantenedor:** 23/11/2026.  
**Estado de publicação:** decisão operacional aprovada pelo mantenedor; sua incorporação à `main` depende de revisão e merge expressamente autorizados.  
**Escopo:** organização da equipe, colaboração, revisão cruzada, QA, integração e redução de gargalos. **Não altera** requisitos de produto, política factual, arquitetura, calendário ou autorização de merges.

> **Regra central:** somos uma equipe de engenharia, não cinco filas independentes de prompts. O objetivo é aumentar a entrega segura de funcionalidades utilizáveis no beta e definitivas no produto final — não maximizar agentes ocupados, PRs abertos ou testes redundantes.

## 1. Missão e fonte de verdade

O Better Talker mantém a mesma arquitetura Android nativa/Compose, Room, Biblioteca local, Mentor, editor por seção, Treino e Palco. O beta é um marco funcional do produto definitivo, não um produto paralelo ou descartável.

Esta é a **política operacional vigente após integração à main**. A [política anterior com quatro agentes](DELIVERY_POLICY.md) preserva as decisões de produto, os critérios do beta e o histórico; em caso de conflito **sobre papéis, revisão e capacidade**, prevalece este documento. O [roadmap em revisão, PR #10](https://github.com/guilhermemellojw/better-talker/pull/10) e o respectivo AGENT_HANDOFF antigo precisam ser reconciliados antes de integração, sem apagar o histórico. Nenhum snapshot em documento substitui PR/HEAD/CI atuais no GitHub.

A coordenação técnica central acompanha evidências e propõe prioridades, atribuições e gates; **o mantenedor continua autorizando merges, releases, mudanças de schema, versões e ações potencialmente destrutivas**. A coordenação não equivale a execução automática ou assíncrona dos agentes.

## 2. Papéis e responsabilidades

| Agente | Responsabilidade primária | Também colabora em | Cuidados |
| --- | --- | --- | --- |
| **A — Mentor** | Conversa pedagógica, memória, contexto oratório, geração, autoria e aceite de cards | QA funcional de fluxos de conversa e regressões em outros PRs | Não ampliar corpus factual nem alterar TRUST unilateralmente |
| **B — UX/Referências** | Leitores, recuperação para UI, referências clicáveis, usabilidade e acessibilidade | Revisão de resolutores, scripts e regressões funcionais | Não promover fonte indisponível a verificada |
| **C — TRUST** | Proveniência documental, identidade TNM, índice Room, gates factuais e falha segura | QA de referências e integridade transacional | `RECORD_MATCH` não prova autenticidade editorial; não habilitar `VERIFIED_LOCAL` por presunção |
| **D — QA operacional** | Executor seguro, automação, preservação de dados, dispositivo/emulador, prontidão de integração | Revisões de testes e integração; apoio aos demais agentes | A34 protegido; não executar instrumentos sem autorização específica |
| **E — QA especializado** | Auditoria adversarial independente, regressões de maior risco e certificação cruzada | Revisão de Mentor/TRUST/UX e sugestões de testes | **Não é** revisor obrigatório ou único portão para todos os PRs |

Os papéis são especializações, **não silos**. O agente autor conserva a responsabilidade de testar e corrigir sua implementação; revisores cruzados verificam a evidência, não assumem automaticamente a implementação.

## 3. Trabalho contínuo com ownership explícito

1. Toda tarefa tem **ID**, prioridade, dono, objetivo observável, dependências, escopo/arquivos, definição de pronto e revisor **diferente do autor**.
2. Cada agente mantém worktree/branch exclusivos; não modificar o checkout ou PR de outro agente. Um arquivo compartilhado (especialmente `CopilotViewModel.kt`, `ChatScreen.kt`, Room, resolutores, gates factuais) tem um dono de edição por vez.
3. **Até um BUILD principal ativo por agente**; revisão curta e independente pode ocorrer em paralelo quando não compromete a entrega prioritária. Evitar abrir novas frentes apenas para ocupar capacidade.
4. Um agente **não fica parado aguardando QA/CI/merge**: após entregar seu PR, pode iniciar trabalho independente, ajudar em QA cruzado ou preparar contrato puro/testes que não dependam do merge. Se for dependente, registrar a dependência e não integrar prematuramente.
5. Em bloqueadores P0 de segurança, autoria, privacidade ou dados, corrigir o risco da rota afetada tem precedência sobre novas funcionalidades. Um revisor não deve ficar indefinidamente aguardando o autor para iniciar outro QA.
6. Troca de tarefa exige checkpoint dos arquivos não comitados, SHA, worktree, artefatos e estado; não compartilhar `/tmp` ou sobrescrever alterações de colegas.

**Planejar por caminho crítico:** dar preferência à jornada S-34/nota → Mentor por tópico → fontes reais/falha segura → proposta revisável → aceite → texto na seção correta → Treino/Palco → regressão de dados e dispositivo.

## 4. QA distribuído e independente (sem fila única do Agente E)

**O autor faz QA primário:** testes unitários/focados, negativos e regressões proporcionais, compilação pertinente, resultados e CI vinculados ao SHA final, documentação das limitações.

**Um colega faz QA secundário independente:** avalia contrato, cenários adversariais, lacunas, regressões e afirmações não sustentadas. Pode criar fixtures ou testes na própria worktree de QA; **não altera a branch do autor**. Defeito concreto retorna ao autor com reprodução, severidade e menor correção possível. O mesmo revisor recertifica a correção (diff novo + testes afetados), sem refazer toda a auditoria salvo mudança substancial.

**Matriz inicial de pares, ajustável pela capacidade:**

| Autor | Revisor cruzado preferencial | Alternativa |
| --- | --- | --- |
| A | E | C ou D, se competente no contrato |
| B | C | E ou D |
| C | B ou E | D para infraestrutura/Room |
| D | B | E ou C |
| E | A ou D | C |

Nunca autocertificar seu próprio PR. O revisor deve ser competente para aquele escopo e **não ter contribuído materialmente para a mudança auditada**. Se contribuiu, escolher outra pessoa. A coordenação distribui revisões para evitar sobrecarga; nenhuma ordem rígida obriga passar tudo pelo E.

### Proporcionalidade do QA

- **Baixo risco** (docs, mudanças puras/localizadas): revisão curta, consistência de links/contratos e testes que fizerem sentido.
- **Médio** (UX/reader/Mentor): fixtures adversariais, regressões funcionais e verificação de contexto/consentimento.
- **Alto/crítico** (Room, transação, dados do usuário, TNM/TRUST, script de dispositivo): provas negativas que **falhem sem o mecanismo protegido**, isolamento/atomicidade, erro/cancelamento, segurança factual, testes reais quando exigidos e validação independente. Teste compilado **não** equivale a instrumentado executado; CI verde **não** prova semântica nem segurança física.
- Se o PR for experimental e desconectado de produção, é possível aprovar **o código experimental com ressalvas**, mas não certificar propriedade ainda não comprovada nem ativar a funcionalidade em produção.

**Fato sem evidência e integridade de autoria não são negociáveis:** só TNM e publicações jw.org efetivamente recuperadas e elegíveis podem sustentar conteúdo bíblico; memória, S-34 estrutural e conhecimento do modelo não são provas. Não gravar alterações de texto/decisão sem aceite. Falhar de modo conservador.

## 5. Fluxo de trabalho e estados explícitos

```text
Tarefa definida (dono + revisor + critério)
  → worktree/branch exclusiva e contrato
  → BUILD + testes do autor
  → PR + SHA + CI (PRONTO PARA QA)
  → revisão cruzada em paralelo às outras frentes
       ↳ defeito reproduzido → autor corrige → revisor recertifica
  → APROVADO PARA MERGE (gate de risco demonstrado)
  → autorização explícita do mantenedor
  → merge convencional + CI pós-merge em main
  → status atualizado com commit, evidência e efeito no beta
```

Estados nunca equivalentes:
- **PRONTO PARA QA:** o autor terminou; ainda não certificado.
- **APROVADO COM RESSALVAS:** riscos e limitações explicitados; não autoriza superar gate bloqueante.
- **BLOQUEADO:** há risco de segurança/integridade ou falta uma prova indispensável. Descrever reproduções e ação mínima.
- **APROVADO PARA MERGE:** QA suficiente **para aquele escopo**, CI no SHA final e dependências resolvidas; ainda não é merge autorizado.
- **MERGED / VALIDADO PÓS-MERGE:** apenas com commit real da main e workflow pertinente conferido.

Para cada PR registrar: base/main SHA, HEAD, escopo/diff, autor/revisor, execução exata dos testes, quais **não** foram executados, checks CI/URLs, dados/dispositivo afetados, contratos negativos, defeitos P0–P3, dependências, decisão e próximo passo. Não dizer que passou apenas porque o agente relatou; verificar GitHub ou declarar indisponível.

## 6. Gestão colaborativa de gargalos

- **Toda entrega gera uma decisão de coordenação:** resultado comprovado → bloqueios → colaboração possível → próxima tarefa independente.
- Distribuir QA pela habilidade e disponibilidade, sem fila exclusiva no E nem revisões duplicadas por hábito.
- Evitar que D vire o único executor de todo teste; usar matriz de risco para decidir host-only versus instrumentado e reservar dispositivo.
- Se dois agentes precisarem do mesmo arquivo, designar o dono atual e o outro trabalha em interface/fixture pura, discovery ou QA; integrar em sequência sem commits cruzados.
- Não manter infinitamente PRs grandes e dependentes: cortar escopo em entregas definitivas menores, com ligação produtiva apenas depois que seus contratos estiverem integrados.
- Revisar o fluxo a cada ciclo: fila de PRs aguardando QA, espera por CI, bloqueadores, retrabalho e **funcionalidades efetivamente integradas**. Não inventar tempo estimado ou prometer prazos.
- Quando houver P0/P1 comprovado, deixar claro o risco e suspender **somente** a integração das rotas afetadas; não interromper projetos independentes.

## 7. Proteções operacionais permanentes

- **Sem autorização explícita:** nenhum merge em `main`, push direto em `main`, rebase/force-push de outros agentes, tag/release, versionCode/versionName, migração Room, destruição de dados, alteração de segredo, política TRUST, distribuição ou provedor.
- **A34:** não usar `connected*AndroidTest`, `uninstall` ou `pm clear` como rotina. Qualquer teste físico/instrumentado que altere estado exige autorização específica, executores seguros, pré-condições, assinatura, snapshot e plano de recuperação. Emulador descartável com dados sintéticos é preferível para validar isolamento, mas sua execução também deve ser autorizada.
- **GitHub:** código, workflows, SHA, PR e relatórios versionados constituem evidência; mensagens de agente são relatos, não certificados.
- **Fonte factual:** `RECORD_MATCH`, estrutura, hash calculado localmente e referência reconhecida não comprovam autenticidade editorial, nem apoio semântico da afirmação ao trecho.
- **Beta:** não sacrificar integridade do editor, consentimento, fontes ou dados para obter um PR a mais.

## 8. Operação inicial e manutenção

Distribuição inicial **indicativa** (reconfirmar no GitHub antes de cada ação, pois HEADs/estados mudam):

- A: Mentor/continuidade pedagógica; responde por correções descobertas no QA do seu PR.
- B: referências e regressões; pode revisar ferramentas de QA.
- C: TRUST e correções de testes de atomicidade; pode revisar referências quando livre.
- D: executor seguro e estratégia de testes; apoia execução proporcional e não destrutiva.
- E: QA especializado do Mentor e TRUST; pode recertificar regressões sem se tornar fila única.

**Transição documental:** após integrar este documento, atualizar as menções operacionais a quatro agentes em [DELIVERY_POLICY.md](DELIVERY_POLICY.md) e a “2 BUILD + 1 Discovery” no PR #10 antes do merge desse roadmap. Preservar snapshots históricos datados, mas nunca usá-los como instruções atuais. O PR #39 de automação de status não deve alterar ou aprovar tarefas/merges automaticamente.

**Definição de sucesso:** aumento de entregas seguras, revisadas e integradas à `main` que permitam ao usuário **entender, preparar, revisar e ensaiar o próprio discurso**, mantendo todas as proteções de confiança.
