# Better Talker — Protocolo de continuidade para qualquer agente

**Procedimento de retomada.** Leia [TEAM_COORDINATION.md](TEAM_COORDINATION.md) para a política vigente de cinco agentes e QA distribuído, e [ROADMAP.md](ROADMAP.md) para visão e prioridades. O modelo antigo de 2 BUILD + 1 Discovery é histórico após o PR #46. Nenhum documento autoriza mutações sem tarefa atribuída pelo mantenedor.

## 1. Antes de qualquer ação

1. Consultar [repositório](https://github.com/guilhermemellojw/better-talker), HEAD atual de main, PRs abertos/fechados, respectivos HEADs e CI, [Actions](https://github.com/guilhermemellojw/better-talker/actions), [TEAM_COORDINATION.md](TEAM_COORDINATION.md), [FREEZE.md](FREEZE.md), [VISION.md](VISION.md) e [ROADMAP.md](ROADMAP.md).
2. Não confiar no snapshot histórico de 17/11/2026, em nomes de branches, em um relato de agente ou no presente chat como prova de estado atual. Verificar no GitHub; se offline, declarar que o estado não foi verificado.
3. Identificar a tarefa **explicitamente atribuída**, seu escopo, dependências, arquivos e dispositivo de teste. Se não houver tarefa, fazer apenas diagnóstico e proposta; não iniciar BUILD.
4. Registrar SHA de main, branch exclusiva, PR, HEAD, agente autor, **revisor cruzado independente**, tarefa, dependências e gates; conferir conflitos com agentes ativos.
5. Não usar `/tmp` para trabalho não comitado sujeito a limpeza/interrupção. Worktrees duráveis e commits frequentes em branches próprias; nunca usar o mesmo diretório/branch de outro agente.

## 2. Modelo vigente: equipe colaborativa de cinco agentes

**Política oficial:** [TEAM_COORDINATION.md](TEAM_COORDINATION.md), aprovada pelo mantenedor e integrada à main no PR #46 em 23/11/2026. O protocolo anterior de 2 agentes BUILD + 1 Discovery foi substituído; qualquer snapshot de 17/11 ou 20/11 deve ser tratado como histórico.

| Agente | Ownership primário | Colaboração possível |
| --- | --- | --- |
| **A — Mentor** | Conversa, memória, geração e aceites | QA de fluxo e regressões alheias |
| **B — Referências/UX** | Leitores, navegação e acessibilidade | QA de scripts e ferramentas de D |
| **C — TRUST** | Room, identidade/proveniência e gate factual | QA de referências de B |
| **D — QA operacional** | Executor seguro, dispositivo, CI e integração | Regressões instrumentadas proporcionais ao risco |
| **E — QA especializado** | Auditoria adversarial e certificação de alto risco | Revisão independente de Mentor/TRUST, sem fila obrigatória |

**Responsabilidade compartilhada:** o autor implementa, testa e corrige; outro agente tecnicamente qualificado e independente revisa. Um revisor não modifica o PR do autor. O autor corrige defeitos reproduzidos e o revisor recertifica o diff impactado. PRONTO PARA QA não é aprovação de merge; a aprovação técnica ainda requer autorização explícita do mantenedor.

**Fluxo contínuo:** o autor pode assumir tarefa independente ou QA cruzado após a entrega, sem aguardar merge e sem criar conflitos. Preferir um BUILD principal por agente; reservar ownership de arquivos compartilhados, worktrees exclusivas e recursos Gradle/dispositivo. O A34 não é ambiente descartável: nada de connected*AndroidTest, uninstall ou pm clear como rotina; testes que alterem dados requerem autorização específica.

## 3. Restrições permanentes

- Produto: Android nativo Kotlin/Compose, Room, Biblioteca local, DeepSeek na fase atual, editor por seção, Treino/Palco. Reaproveitar; não reescrever.
- Autoridade factual: TNM e publicações do jw.org efetivamente consultadas. `th`/`be` para técnicas de oratória. História de chat, usuário, S-34 estrutural e modelo **não** comprovam fatos.
- Arquivos S-34 vêm do usuário (não buscar automaticamente); importação não comprova autoria/autenticidade.
- Interação: mentor pedagógico, voz opcional, texto/toque completos, baixa escolaridade sem infantilização.
- Escrita de conteúdo: proposta → revisar/escutar → confirmar → persistir. Nunca aplicar sem consentimento ou transformar tag da IA em decisão do usuário.
- **Proibido sem solicitação expressa:** merge de PR, push em `main`, tag, release, versionCode/versionName, migração Room, remoção/destruição de dados, mudança de segredo/token/política de distribuição. Uma tarefa de documentação só permite documentos na branch própria e PR para revisão.
- Respeitar a organização de sync e backups; nunca conectar contas públicas a ambientes de debug apenas para testar. Dispositivos/test data são recursos compartilhados.
- Não embutir chave de API coletiva no APK. Evitar custo LLM não autorizado; testes de confiança devem usar fixtures sempre que possível.

## 4. Fluxo de execução por tarefa

**DISCOVERY:** leitura de `main` atual + PRs pertinentes → perguntas/hipóteses explícitas → evidência `arquivo:linha` → matriz de testes → recomendação de menor mudança/risco/custo → relatório. Sem alterações do projeto.

**BUILD autorizado:** branch/worktree exclusiva em base verificada → mudança pequena e testes focados → commit de checkpoint → JVM/lint/CI da branch → validação manual com APK SHA conhecido e cópia de segurança quando houver risco → relatório → revisão. Se lint local falhar por bug do analisador/JVM, registrar comando e stacktrace; não ocultar. O CI do **HEAD exato** é obrigatório para gate, mas não substitui revisão funcional.

**PR:** escopo, baseline SHA, HEAD SHA, diff, autor e revisor cruzado, risco de regressão, testes/controles negativos, CI URLs, dispositivo/Android/APK SHA, o que NÃO foi testado, limitações e próxima tarefa. O autor não autocertifica; revisão proporcional ao risco. **Não fazer merge** sem QA aplicável e autorização expressa.

**Pós-merge autorizado:** confirmar merge commit e `main` novo; rodar/inspecionar CI pós-merge; somente então registrar “integrado” e atualizar [ROADMAP.md](ROADMAP.md) por PR de docs. Não assumir status “concluído” com base na existência de branch ou PR verde.

## 5. Como entregar o controle a outro agente ou novo chat

Copie este handoff mínimo, preenchendo com dados atuais:

```text
Projeto: guilhermemellojw/better-talker
Leia: docs/TEAM_COORDINATION.md, docs/ROADMAP.md, docs/AGENT_HANDOFF.md, docs/FREEZE.md, docs/VISION.md
Tarefa atribuída: [ID + Discovery/BUILD + objetivo + critérios]
main HEAD verificado: [SHA e quando]
PR/branch/HEAD do trabalho: [número, URL, SHA]
Dono / revisor cruzado / worktree: [agente, revisor independente, local]
Status QA: [PRONTO PARA QA / BLOQUEADO / APROVADO COM RESSALVAS / APROVADO PARA MERGE / MERGED]
Arquivos alterados: [nomes]
Testes/CI: [comandos, URL, status, commit]
Dispositivo/APK: [modelo, Android, SHA ou “não testado”]
Bloqueadores e riscos: [o que impede]
Próximo passo permitido: [uma ação específica]
Proibido: merge/tag/release/version bump/migração não autorizados
```

A retomada **deve começar verificando GitHub e lendo os arquivos**, mesmo quando esse resumo for fornecido. Uma documentação de planejamento nunca substitui a fonte de verdade do código/CI.

## 6. Como atualizar esses documentos

- `TEAM_COORDINATION.md`: política operacional vigente de cinco agentes; alterações de governança requerem autorização explícita.
- `ROADMAP.md`: visão, decisões, status por marco, dependências e prioridades futuras.
- `AGENT_HANDOFF.md`: protocolo estável e instruções de retomada. Não usar como log de sessões.
- `FREEZE.md`: decisões congeladas e histórico de pendências (não substituir/limpar histórico sem revisão).
- PRs/commits: evidência exata das alterações, CI e revisão.
- Ao mudar status: registrar `data, SHA main, PR, HEAD, CI, dispositivo, resultado, decisão do mantenedor`; registrar também mudanças de plano e **por quê**.
- Distinguir sempre **decisão aprovada**, **hipótese**, **Discovery**, **BUILD autorizado**, **PR aberto**, **MERGED**, **release**.

## 7. Histórico e próximo checkpoint

**Governança confirmada em 23/11/2026:** PR #46 integrado na main em 52e75373. Abaixo está somente o registro histórico de 17/11. Novas atribuições precisam de dono, revisor independente e preflight atual.

Na foto de 17/11/2026, `main=5bc0a66`, PR #8 da 1C.2.0 e PR #9 da UX-2.2 estavam abertos. A tarefa pendente do PR #8 é evitar confirmação parcial truncada; do PR #9, confirmar se `thread-empty` em discurso inteiro está contado. TRUST-0 é somente Discovery, sem implementação. **A foto ficará desatualizada — consulte estado real antes de agir.**

**Sucesso do projeto:** a pessoa deve pensar “entendi melhor como preparar e apresentar o discurso”, não “a IA fez tudo por mim”.
