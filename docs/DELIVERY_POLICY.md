# Better Talker — Política de entrega incremental (histórico: quatro agentes)

**Decisão de direcionamento:** 22/11/2026 — mantenedor do projeto.  
**Estado documental:** documento histórico já presente na `main`; o modelo de cinco agentes foi aprovado pelo mantenedor em 23/11/2026 e está definido em [TEAM_COORDINATION.md](TEAM_COORDINATION.md). Os snapshots abaixo não comprovam o estado atual de PRs, builds ou releases.  
**Escopo:** estratégia de execução e integração, sem mudar a visão do produto, os critérios de confiança, código Android ou calendário prometido.

> **Atualização operacional — 23/11/2026:** a coordenação passa a ser de **cinco agentes com QA distribuído e revisão cruzada**, conforme [TEAM_COORDINATION.md](TEAM_COORDINATION.md), após integração da atualização documental. As seções abaixo sobre **quatro agentes**, a fila do D e checkpoints datados são **históricas**, não instruções operacionais vigentes. As regras de produto, segurança, caminho crítico e autorização explícita de merge permanecem válidas.

> **Princípio central:** o beta é o primeiro marco utilizável do **mesmo Better Talker final**. Não manter dois produtos, duas arquiteturas ou um beta descartável. Construir funcionalidades definitivas em incrementos verificáveis, com a menor jornada funcional segura possível.

## 1. Uma arquitetura, uma linha de evolução

- **Produto único:** Android nativo/Compose, Room, Biblioteca local, Mentor, editor por seções, Treino e Palco continuam sendo reutilizados e evoluídos. Não reescrever componentes estáveis para acelerar uma demonstração.
- **Integração em main:** branches curtas por tarefa; a main recebe somente PRs revisados e com merge **explicitamente autorizado**. Uma distribuição beta é preparada a partir de commit validado, não de uma arquitetura divergente ou branch permanente que acumule mudanças ocultas.
- **Beta como marco, não atalho:** concluir primeiro a jornada mínima de ponta a ponta; recursos adicionais podem evoluir posteriormente se os contratos definitivos já permitirem a expansão.
- **Sem débito técnico invisível:** atalhos temporários precisam estar identificados, ter critério de remoção, dono e impacto. Não usar dados simulados apresentados como fontes reais, nem ativar experimental como verificado.
- **Preservação de dados e autoria:** texto e decisões somente com aceite do usuário; migrações, backups, identidade de nota, offline e privacidade são restrições permanentes.
- **Fonte factual:** Bíblia TNM e publicações do jw.org efetivamente consultadas; referências não recuperadas, índices sem identidade confiável, memória, texto do usuário, S-34 estrutural e conhecimento paramétrico da IA não se tornam provas. th/be fundamentam técnicas de oratória, não doutrina.

## 2. Linha de corte do beta: jornada vertical

O beta tem como alvo uma jornada que uma pessoa consiga completar sem auxílio do desenvolvedor:

1. Abrir ou importar manualmente um esboço S-34 / criar nota, preservando estrutura, conteúdo e referências disponíveis.
2. Selecionar um tópico, conversar com o Mentor, explicar, perguntar, revisar e manter contexto e decisões confirmadas na nota/seção correta.
3. Consultar fontes reais e seus estados (disponível, parcial, ausente, não verificado); não inventar citações, paráfrases ou explicações.
4. Pedir um rascunho ou card, revisar e **aceitar explicitamente** antes de inserir/substituir texto.
5. Verificar que a edição ficou na seção correta e se mantém após navegação/reinício.
6. Ensaiar o conteúdo pelo caminho de Treino/Palco que estiver suportado no dispositivo, com alternativas por texto/toque quando voz não estiver disponível.
7. Testar erro de fonte, cancelamento, troca de nota, troca de tópico, ausência de chave/rede e preservação de dados.

**Gate de beta:** esses fluxos devem funcionar em APK identificável por SHA, com testes automatizados pertinentes, regressão em dispositivo real, sem P0/P1 de perda de dados, corrupção de consentimento, vazamento entre notas/seções, falha de privacidade ou afirmação factual sem fonte apresentada como verificada.

**Pode evoluir depois do beta:** qualidade avançada de voz, LLM inteiramente local, comparação entre modelos, recursos secundários de UI, automações de custo, integrações e ampliação de cobertura TRUST, **desde que não sejam dependências de segurança ou funcionamento dos fluxos liberados**.

**Não pode ser adiado por conveniência:** integridade da autoria/aceite, falha segura de fontes nas rotas expostas, privacidade, dados persistidos e um caminho utilizável sem voz. Não liberar como comprovado um fluxo cuja sustentação factual ainda é experimental.

## 3. Quatro agentes: dono claro e trabalho paralelo

| Agente | Responsabilidade primária | Exemplos | Limite |
| --- | --- | --- | --- |
| **A — Mentor** | Pedagogia conversacional, memória, geração oratória, inserção no editor | Seção resolvida, decisões confirmadas, contexto do diálogo | Não altera componentes TRUST/UX compartilhados sem combinar |
| **B — UX** | Usabilidade, referências/leitor, acessibilidade, navegação | Clique, leitura local, TalkBack, fluxos claros | Não define política factual nem assume posse do ledger |
| **C — TRUST** | Identidade documental, índice Room, evidência factual, testes de confiabilidade | TNM, transação, gate de suporte, proveniência | Não promove VERIFIED_LOCAL sem todos os gates de autenticidade |
| **D — QA/Integração** | Testes físicos, regressão, preservação de dados, prontidão de PR/release | A34, cenários adversariais, SHA de APK, CI | Não é o único responsável por testar; correções pequenas de teste só com tarefa própria |

Especializações são padrões de ownership, **não silos**. Um agente bloqueado pode receber trabalho independente que preserve o caminho crítico. Nenhum agente ocupa outra branch/worktree ou altera arquivo compartilhado em paralelo sem coordenação explícita.

### Limites de trabalho em progresso (WIP)

- **Um item de BUILD ativo por agente**. Preferir finalizar, revisar e integrar antes de iniciar outra implementação grande.
- Até **três frentes de BUILD** separáveis (A/B/C), enquanto D executa QA/revisão sobre entregas maduras. QA pode corrigir seu próprio teste com escopo e autorização explícitos.
- Evitar PRs grandes, branches antigas, múltiplos donos no mesmo arquivo e alterações simultâneas em **ChatScreen, MainActivity, banco Room, provedores ou políticas TRUST**.
- Dispositivo físico é recurso exclusivo durante cada execução; Android/Gradle pesado também deve ser coordenado quando compartilha hardware/worktree.
- O agente autor implementa **testes focados**; D seleciona testes físicos por risco e realiza regressão de integração. Isso evita tornar D gargalo para toda alteração documental ou unitária.

## 4. Priorização por caminho crítico, não por contagem de PRs

**P0 — Bloqueador de segurança ou integridade:** perda/corrupção de dados, consentimento incorreto, vazamento entre notas, fonte não comprovada apresentada como verdadeira, exposição de segredo; precedência máxima.

**P1 — Essencial ao beta:** impede concluir a jornada vertical ou usá-la de forma compreensível/acessível; seguir caminho crítico.

**P2 — Evolução definitiva não bloqueante:** melhora qualidade final, TRUST mais amplo, voz avançada e operações, desde que o beta exposto permaneça seguro. Desenvolver em paralelo com P1 quando não causar conflito.

**P3 — Experimental/otimização não medida:** exigir hipótese, ganho mensurável e limite de esforço; não atrasar P0/P1 nem criar refatorações sem prova de necessidade.

Um risco só pode ser considerado resolvido por **evidência reproduzível**. Não confundir arquivos de teste, código compilado, PR aberto e CI verde com validação real ou funcionalidade integrada.

### Dependências da jornada beta

```text
Estrutura S-34 / nota
       ↓
Mentor por tópico ↔ memória confirmada e isolada
       ↓
Fontes consultáveis + falha factual segura (TRUST)
       ↓
Card/rascunho → revisão → aceite
       ↓
Editor na seção correta → persistência → Treino/Palco
       ↓
Teste E2E no A34 → beta candidato → validação de piloto
```

As frentes de TRUST podem progredir em paralelo. O bloqueio do beta é determinado pelo **risco efetivo nas rotas habilitadas**, não pela conclusão de toda pesquisa sobre autenticação documental. Componentes experimentais permanecem não integrados/desativados quando ainda não satisfazem o contrato de confiança.

## 5. Ciclo de entrega e gates

```text
Requisito e aceite definidos
  → dependências/arquivos/dono
  → branch/worktree exclusivo sobre main verificada
  → implementação vertical pequena + testes focados
  → PR aberto com HEAD/base/diff
  → CI no SHA exato
  → revisão de contrato/risco + QA físico proporcional
  → autorização explícita do mantenedor
  → merge convencional
  → CI pós-merge no novo SHA da main
  → status do marco atualizado com evidência
```

Regras:

- **Nunca** merge automático, push direto em main, release, tag, migração Room, alteração de versão, segredo ou mudança de política factual sem aprovação específica.
- Na abertura do PR: informar baseline, dependência, impacto nos dados/segredos/fontes, test matrix, comandos, resultados, CI e arquivos tocados. Sem teste físico, declarar ausência explicitamente.
- QA no aparelho usa pacote e APK identificáveis; preservar banco, decisões, publicações e transporte; evitar `pm clear`, uninstall ou limpeza ampla.
- Depois de merge, o CI anterior não basta: conferir merge commit e workflow pós-merge, especialmente quando outros agentes integraram código.
- Não iniciar BUILD dependente sobre branch não integrada sem identificar base empilhada e plano de rebase/merge.
- Contratos compartilhados são definidos antes dos consumidores paralelos; mudanças incompatíveis exigem revisão conjunta.

### Política de bloqueio e exceções

- **Bloquear beta** se uma rota exposta produz citações ou interpretações não apoiadas como verificadas, altera decisões sem aceite, perde dados ou não tem recuperação segura.
- **Não bloquear beta** por evolução futura que está inativa ou tem fallback honesto e testado. A exclusão temporária de recurso deve ser explícita e não criar código descartável.
- Uma mudança de escopo só pode ocorrer com justificativa, impacto na versão final, critérios de aceite e decisão registrada pelo mantenedor.

## 6. Medição operacional

Acompanhar por ciclo, sem metas artificiais de commits:

| Indicador | Fonte de verdade | Objetivo |
| --- | --- | --- |
| Tempo PR aberto → integrado | GitHub PR/merge SHA | Reduzir sem relaxar gates |
| PRs aguardando revisão/QA | GitHub + agenda de dispositivo | Reduzir fila e dependências |
| Rebase/conflitos e regressões | CI, revisão e QA | Reduzir retrabalho |
| Jornada beta funcional | Cenários E2E, SHA do APK e aparelho | Aumentar cobertura real |
| Bloqueadores P0/P1 | Issues/PRs evidenciados | Zero antes da distribuição beta |
| Custo por jornada e qualidade | Telemetria com privacidade e avaliação controlada | Escolher modelo mínimo suficiente |

Não inferir prazos a partir de quantidade de PRs. Revisar o **caminho crítico** e capacidade efetiva a cada ciclo; estimativas são hipóteses sujeitas a confirmação.

## 7. Fonte de verdade documental e compatibilidade

- [ROADMAP.md](ROADMAP.md), proposto no [PR #10](https://github.com/guilhermemellojw/better-talker/pull/10), continua sendo a visão e backlog estratégico detalhado **após sua aprovação/merge**.
- [AGENT_HANDOFF.md](AGENT_HANDOFF.md), também proposto no PR #10, detalha retomada do contexto. O trecho antigo **“2 BUILD + 1 Discovery”** deve ser **reconciliado com esta decisão de quatro agentes antes de integrar os dois documentos**. Não apagar a visão nem reescrever o histórico.
- Esta política descreve o **modelo operacional vigente após aprovação/merge deste PR**; em qualquer conflito de contagem/atribuição com snapshots históricos, registrar a atualização e ajustar os documentos envolvidos antes de declararem a mesma política aprovada.
- O [PR #39](https://github.com/guilhermemellojw/better-talker/pull/39) propõe automação de **status factual**, não aprovação automática de marcos, implementação ou merges. Revisar seus gatilhos/permissões separadamente.
- [VISION.md](VISION.md) e [FREEZE.md](FREEZE.md) preservam histórico e decisões congeladas. Nunca substituir silêncio por suposição.
- Toda instrução de agente começa conferindo HEAD real da main, PRs ativos, CI, owner, dependências e bloqueadores. Snapshots datados não são o estado atual.

### Snapshot operacional em 22/11/2026 (não é status em tempo real)

- main observada: `4072293ac46948cbad0a432c7e0c4481e8a32f87`.
- PRs #36 (controle da memória) e #37 (links clicáveis no chat): **MERGED**.
- [PR #38](https://github.com/guilhermemellojw/better-talker/pull/38): **OPEN**; QA reportou 14/14 no A34 **com correção temporária**, ainda faltando corrigir no HEAD a assinatura JUnit e revalidar.
- [PR #40](https://github.com/guilhermemellojw/better-talker/pull/40): **OPEN**, leitor direto de publicações; não declarar integrado.
- PRs #10 (roadmap) e #39 (automação de status): **OPEN**; exigem revisão e autorização para merge.
- Responsabilidades em curso relatadas: A = auditoria P5B; B = leitor B2; C = TRUST-B1b; D = fechar/revalidar o PR #38. Confirmar com os agentes, pois tarefas mudam.

## 8. Critério para oficialização e controle de mudança

Esta decisão histórica define **como** entregar sem autorização implícita para implementação ou merge. O modelo atual de cinco agentes está em [TEAM_COORDINATION.md](TEAM_COORDINATION.md), sujeito a revisão e merge da atualização documental. Reconciliar o protocolo antigo do PR #10 antes de sua integração.

Não alterar prioridades estratégicas, regras TRUST, modelo financeiro, distribuição ou garantia de prazo apenas por adotar quatro agentes.

**Resultado esperado:** beta adiantado com a mesma arquitetura do produto final, menor espera entre código, teste e integração, menos retrabalho e controles de confiança preservados.
