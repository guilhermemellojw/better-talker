# Better Talker — Protocolo de continuidade para qualquer agente

**Documento operacional.** Leia [ROADMAP.md](ROADMAP.md) como fonte principal da visão, prioridades e critérios; este arquivo é um procedimento de retomada. **Nenhum conteúdo deste arquivo autoriza mutações sem uma tarefa atribuída pelo mantenedor.**

## 1. Antes de qualquer ação

1. Consultar [repositório](https://github.com/guilhermemellojw/better-talker), HEAD **atual** de `main`, PRs **abertos e fechados recentemente**, respectivos HEADs, checks do último SHA, [Actions](https://github.com/guilhermemellojw/better-talker/actions), [FREEZE.md](FREEZE.md), [VISION.md](VISION.md), e [ROADMAP.md](ROADMAP.md).
2. Não confiar no snapshot histórico de 17/11/2026, em nomes de branches, em um relato de agente ou no presente chat como prova de estado atual. Verificar no GitHub; se offline, declarar que o estado não foi verificado.
3. Identificar a tarefa **explicitamente atribuída**, seu escopo, dependências, arquivos e dispositivo de teste. Se não houver tarefa, fazer apenas diagnóstico e proposta; não iniciar BUILD.
4. Registrar SHA da base `main`, branch exclusiva, PR relacionado, HEAD inicial, tipo de atividade e gates; conferir conflitos com agentes ativos.
5. Não usar `/tmp` para trabalho não comitado sujeito a limpeza/interrupção. Worktrees duráveis e commits frequentes em branches próprias; nunca usar o mesmo diretório/branch de outro agente.

## 2. Modelo de coordenação: 2 agentes de BUILD + 1 Discovery

- **A — Mentor/segurança:** mudanças de lógica conversacional, integridade, TRUST conforme autorização. Primeira prioridade de snapshot: revisão do PR #8 e correção de escrita atômica das linhas `[confirmado]` no limite de 500 chars.
- **B — UX:** composição/scroll/streaming e acessibilidade conforme autorização. Primeira prioridade de snapshot: revisão do PR #9 e confirmação do ajuste de `thread-empty`; UX-2.3 só após integração da UX-2.2.
- **C — Discovery:** investigação somente leitura TRUST-0; sem código, banco, build, dispositivo, worktree editável ou PR de implementação. Entregar mapa de rotas arquivo:linha, matriz de 40–60 casos, riscos, proposta mínima e gates. O resultado da discovery **não** autoriza TRUST-1.

**Conflitos:** não editar simultaneamente `ChatScreen.kt` por dois agentes; não compartilhar worktrees, tarefas Gradle pesadas ou tablet. Um agente utiliza A34/Xiaomi por vez. Não fazer commits cruzados para “ajudar” outro agente sem coordenação. `docs/ROADMAP.md` e este handoff têm um mantenedor editorial único.

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

**PR:** escopo, baseline SHA, HEAD SHA, diff, risco de regressão, testes, CI URLs, dispositivo/Android/APK SHA, o que NÃO foi testado, limitações e próxima tarefa. **Não fazer merge** sem aprovação.

**Pós-merge autorizado:** confirmar merge commit e `main` novo; rodar/inspecionar CI pós-merge; somente então registrar “integrado” e atualizar [ROADMAP.md](ROADMAP.md) por PR de docs. Não assumir status “concluído” com base na existência de branch ou PR verde.

## 5. Como entregar o controle a outro agente ou novo chat

Copie este handoff mínimo, preenchendo com dados atuais:

```text
Projeto: guilhermemellojw/better-talker
Leia: docs/ROADMAP.md, docs/AGENT_HANDOFF.md, docs/FREEZE.md, docs/VISION.md
Tarefa atribuída: [ID + Discovery/BUILD + objetivo + critérios]
main HEAD verificado: [SHA e quando]
PR/branch/HEAD do trabalho: [número, URL, SHA]
Dono e worktree: [agente, local]
Arquivos alterados: [nomes]
Testes/CI: [comandos, URL, status, commit]
Dispositivo/APK: [modelo, Android, SHA ou “não testado”]
Bloqueadores e riscos: [o que impede]
Próximo passo permitido: [uma ação específica]
Proibido: merge/tag/release/version bump/migração não autorizados
```

A retomada **deve começar verificando GitHub e lendo os arquivos**, mesmo quando esse resumo for fornecido. Uma documentação de planejamento nunca substitui a fonte de verdade do código/CI.

## 6. Como atualizar esses documentos

- `ROADMAP.md`: visão, decisões, status por marco, dependências e prioridades futuras.
- `AGENT_HANDOFF.md`: protocolo estável e instruções de retomada. Não usar como log de sessões.
- `FREEZE.md`: decisões congeladas e histórico de pendências (não substituir/limpar histórico sem revisão).
- PRs/commits: evidência exata das alterações, CI e revisão.
- Ao mudar status: registrar `data, SHA main, PR, HEAD, CI, dispositivo, resultado, decisão do mantenedor`; registrar também mudanças de plano e **por quê**.
- Distinguir sempre **decisão aprovada**, **hipótese**, **Discovery**, **BUILD autorizado**, **PR aberto**, **MERGED**, **release**.

## 7. Próximo checkpoint histórico

Na foto de 17/11/2026, `main=5bc0a66`, PR #8 da 1C.2.0 e PR #9 da UX-2.2 estavam abertos. A tarefa pendente do PR #8 é evitar confirmação parcial truncada; do PR #9, confirmar se `thread-empty` em discurso inteiro está contado. TRUST-0 é somente Discovery, sem implementação. **A foto ficará desatualizada — consulte estado real antes de agir.**

**Sucesso do projeto:** a pessoa deve pensar “entendi melhor como preparar e apresentar o discurso”, não “a IA fez tudo por mim”.
