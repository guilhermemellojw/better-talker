# Better Talker — Roadmap estratégico e operacional

**Referência de produto:** 17/11/2026 · **Estado deste documento:** proposta versionada para revisão no PR de documentação · **Owner do planejamento:** mantenedor do repositório

> **LEIA PRIMEIRO:** Este documento preserva a visão e a sequência de desenvolvimento. Não autoriza executar funcionalidades, fazer merge, criar release ou alterar versões. Antes de agir, consulte o estado *atual* de `main`, PRs e CI. O snapshot abaixo é histórico e pode ficar desatualizado em minutos.
>
> **Para retomar um trabalho sem o contexto do chat:** ler [AGENT_HANDOFF.md](AGENT_HANDOFF.md) e [FREEZE.md](FREEZE.md), depois verificar GitHub. A referência anterior [VISION.md](VISION.md) registra decisões históricas; em conflitos sobre a direção futura, este roadmap prevalece após sua aprovação/merge.

## 1. Visão e inegociáveis

**O Better Talker é primeiro um mentor especialista em oratória, depois um assistente de redação.** Ajuda a pessoa a compreender, explicar com suas palavras, organizar, revisar, ensaiar e apresentar seu próprio discurso. O resultado procurado é **autonomia e confiança do usuário**, não apenas texto gerado.

**Jornada pedagógica preferencial, flexível:** compreender → explicar → raciocinar junto → orientar → oferecer rascunho → revisar/confirmar → ensaiar. O Mentor pergunta preferencialmente uma coisa de cada vez e adapta a linguagem, mas não impõe um interrogatório nem bloqueia um pedido explícito de rascunho.

- **Autoria:** proposta → revisão/leitura/escuta → confirmação → salvamento. Nunca editar nota silenciosamente; preservar undo/versões quando suportado.
- **Fundamentação:** apenas conteúdo efetivamente consultado da **Bíblia Tradução do Novo Mundo (TNM)** e de publicações **jw.org** pode sustentar afirmações bíblicas/editoriais. `th`/`be` fundamentam **orientação de oratória**. O usuário, o histórico, o próprio modelo e a mera menção de uma referência **não são evidência factual**.
- **Honestidade:** quando falta fonte, dizer o que não foi possível conferir. Ilustrações criativas são hipóteses/sugestões identificadas, não acontecimentos históricos.
- **S-34:** é um esboço estrutural que o usuário fornece em arquivo; não tentar baixá-lo automaticamente do jw.org ou presumir disponibilidade pública. Importar não autentica origem ou conteúdo.
- **Local-first e acessibilidade:** texto e toque são alternativas completas à voz. Priorizar STT/TTS local onde disponível, sem garantir offline quando o motor depende de rede. Suporte real a leitura difícil, fontes ampliadas, TalkBack, confirmações claras e interface pouco carregada.
- **Viabilidade e experiência antes do modelo:** a experiência de mentoria (conversa natural, paciência, autonomia, continuidade e fidelidade) é o produto; a marca/potência do LLM é um meio substituível. Adotar o **modelo mínimo suficiente**: o de menor custo total que passe os gates de qualidade em tarefas reais. DeepSeek permanece como provedor principal nesta fase, sem migração antecipada. Não distribuir chave compartilhada no APK. Acesso gratuito financiado por contribuições voluntárias é uma *visão condicionada* à medição e a backend seguro.
- **Evolução incremental:** preservar Android/Compose, Room, Biblioteca, editor por introdução/tópicos/conclusão, Treino, Palco e mecanismos existentes; não reescrever o app.
- **Governança:** nenhum agente faz merge, tag, release, version bump, migração de schema ou mudança de roadmap sem autorização explícita do mantenedor.

## 2. Foto verificada em 17/11/2026 (não é estado em tempo real)

| Componente | Snapshot | Evidência/observação |
| --- | --- | --- |
| `main` | `5bc0a66c595e502478e4803f877093d816834393` | Base inclui PR #6 e PR #7 |
| Versão pública | `6.1.0/80`; banco Room `v26` | Não alterar sem decisão |
| Mentor 1C.1 | **Integrado**, PR #6 | Cards conversáveis |
| UX-2.1 | **Integrado**, PR #7 | Detecção do fim real da lista |
| 1C.2.0 | [PR #8](https://github.com/guilhermemellojw/better-talker/pull/8) **aberto**; head `5c755d0` | CI do head verde; revisão pediu escrita atômica de `[confirmado]` quando `agreedApproach` atinge limite de 500 caracteres. **Não presumir correção/merge.** |
| UX-2.2 | [PR #9](https://github.com/guilhermemellojw/better-talker/pull/9) **aberto**; head observado `e4c6a3c` | Âncora do streaming; A34 validou proveniência/swipe; conferir se ajuste `thread-empty` já está presente no novo head e validar CI. |
| UX-2.3 | **Planejada, não iniciada no snapshot** | Handoff sem salto; depende de UX-2.2 integrada |
| TRUST-0 | **Discovery proposto, não iniciado no snapshot** | Auditoria somente leitura, independente da UX |
| 1C.2.1 e demais frentes | **Propostas, não implementadas** | Requerem discoveries/contratos |

**Estado de CI e branches pode mudar:** revisar [PRs abertos](https://github.com/guilhermemellojw/better-talker/pulls), [Actions](https://github.com/guilhermemellojw/better-talker/actions) e `main` antes de atualizar esta tabela. Histórico longo de releases/bugs: [FREEZE.md](FREEZE.md).

## 3. Capacidades existentes versus lacunas

| Já existe (reutilizar) | Limitação / investimento necessário |
| --- | --- |
| Assistente global, chat com foco por seção, 1C.1, cards/propostas e streaming | Diálogo pedagógico consistente, referente de “explique melhor”, continuidade entre tópicos, UX-2.2/2.3 |
| S-34 importado (DOCX/PDF/JWPUB conforme fluxos atuais), estrutura persistida e Biblioteca local | Descoberta guiada, autenticidade do arquivo não presumida, referências efetivamente consultadas |
| `SourceGate`, `GroundednessVerifier`, `CitationContract`, `OratoryFidelityCheck`, RAG | Verificação **semântica** de afirmações, ausência de fonte segura, isolamento memória/usuário × evidência |
| Editor rico por seção, DraftSheet, Treino, Palco/Mapa mental | Jornada Entender/Escrever/Ensaiar como vistas do **mesmo dado**, integração por confirmação |
| PTT, Sherpa STT local, fallback reconhecedor do sistema, Piper/TTS, comandos locais | Voice-first orientado a tarefa, acessibilidade cognitiva, revisão de transcrição, testes de voz no aparelho |
| DeepSeek remoto por chave pessoal, telemetria de uso parcial | Custo por jornada, quotas, eventual backend seguro e política de contribuições |

## 4. Roadmap por marcos com gates

> **Estimativas:** faixas exploratórias para dedicação coordenada de ~25–35 h/semana, com 2 agentes de implementação + 1 discovery, não compromissos. O caminho crítico é TRUST, especialmente suporte semântico. Tarefas concorrentes não são automaticamente independentes.

| Marco | Frentes/ordem preferencial | Saída demonstrável / gate de conclusão |
| --- | --- | --- |
| **M0 — Estabilidade e integridade** (agora) | Revisar/fechar 1C.2.0 (#8) e UX-2.2 (#9); **depois** UX-2.3; TRUST-0 em paralelo apenas como discovery | Sem consenso gravado pelo modelo; aceites curtos e íntegros; streaming/scroll/handoff sem salto; testes JVM/CI + A34/Xiaomi onde pertinente |
| **M1 — Contrato de confiança** | TRUST-0 → TRUST-1 | Matriz de rotas; fontes autoritativas identificadas; falhas sem fonte não viram explicações factuais; contexto e evidência separados |
| **M2 — Explicação fundamentada** | TRUST-2 + PEDAGOGY-0 (discovery) | Claims avaliados contra trechos e não só contra marcadores; resposta explicativa simples, com fonte inspecionável e limitação honesta |
| **M3 — Diálogo com memória** | 1C.2.1 → 1C.2.2; PEDAGOGY-1; COST-UX (discovery antecipado, BUILD após contratos de Mentor/TRUST) | Decisões somente do usuário, por seção, rejeição/substituição, controle de apagar; Mentor conduz tópico a tópico sem perguntas infinitas |
| **M4 — Voz e compreensão** | VOICE-FIRST + A11Y + feedback visual honesto (UX-3) | Voz aciona tarefas reais; leitura/repetição/pausa, transcrição verificável, TalkBack/fonte grande; texto e toque equivalentes |
| **M5 — Preparação simples integrada** | EDITOR-SIMPLE → vertical S-34; TRUST-3/4 | Abrir/importar S-34 → compreender → conversar → rascunhar → ouvir/revisar → confirmar → editor → ensaiar, sem perda de autoria |
| **M6 — Operação sustentável** | COST-EVAL (comparação cega após baseline estável de Mentor/TRUST) → COST e eventual backend/quotas/contribuições | Escolha do modelo mínimo suficiente com qualidade, fidelidade, latência e custo por jornada medidos; nenhuma chave central no APK; testes de segurança/privacidade, viabilidade e requisitos legais verificados |

**Gate de piloto de 8–12 semanas:** uma jornada S-34 controlada demonstrável e avaliada; NÃO implica conformidade total nem segurança factual absoluta. **Beta com visão ampliada:** horizonte exploratório de 4–6 meses. **Expansão sustentada:** 6–12 meses ou mais, condicionada a TRUST, A11Y e custo.

### Dependências (respeitar a ordem)

```text
1C.2.0 (integridade) ───────► 1C.2.1 (ledger) ───► 1C.2.2 (controle)
UX-2.1 (integrado) ─► UX-2.2 (PR #9) ─► UX-2.3 ─► UX-3 (polimento)
TRUST-0 (discovery) ─► TRUST-1 (fontes) ─► TRUST-2 (claims) ─► TRUST-3/4
                                             │
PEDAGOGY-0 (discovery) ─────────────────────► PEDAGOGY-1
                                               │
VOICE-FIRST + A11Y (discovery cedo) ──────────┤
EDITOR-SIMPLE (discovery cedo) ────────────────┴──► PILOTO S-34
COST-UX (discovery cedo) ─► roteamento local + orçamento adaptativo (BUILD com gates de Mentor/TRUST)
COST (instrumentação desde já) ─► COST-EVAL (após baseline estável de Mentor/TRUST/COST-UX) ─► backend opcional após viabilidade e segurança
```

## 5. Backlog detalhado e critérios de aceitação

### A. 1C.2 — integridade e memória do usuário

**1C.2.0 — BUILD (PR #8, revisão antes de merge).** Remover `recordConsensus` automático; impedir que `〈decisão〉` do modelo grave `agreedApproach`; exigir aceite curto nos gates; distinguir `[confirmado]` versus legado não verificado; excluir memória/abordagem do corpus de verificação. **Ponto de revisão aberto:** `mergeConfirmedApproachText` usa um merge com teto de 500 chars; garantir escrita de linha confirmada **atômica**, sem truncar, sem perder legado; testar 480/490/500 chars, multiline e indicação de falha. **Critérios:** 0 gravações sem aceite, 0 alterações silenciosas, CI verde e smoke de banco no aparelho.

**1C.2.1 — DESIGN depois de M0.** Ledger por nota/seção, identidade/estado/origem de decisão (CONFIRMED/PREFERENCE/REJECTED/OPEN + ACTIVE/SUPERSEDED/STALE), chave de assunto, `sourceMessageId`, aceite e `contentHash`; recuperar por seção e em outras seções sem deslocar contexto; separar **decidir** de **aplicar**. Não promover legado automaticamente. Decidir ANTES de schema se ledger será local ou sincronizado; `agreedApproach` legado não pode ser uma segunda fonte de verdade contraditória. Medir tokens e cache de prompt.

**1C.2.2 — CONTROLE.** Expor ao usuário o que foi lembrado, origem e onde vale; permitir corrigir, reconfirmar ou remover. Definir política de “limpar conversa” e de edições manuais. **Aceite:** após reinício e troca de foco, decisão correta persiste; substituição não mantém duas decisões conflitantes; usuário controla cada uma.

### B. UX — previsibilidade do chat e feedback visual

**UX-2.2 — BUILD (PR #9):** índice do turno deve refletir proveniência (também `focusSources` sem evidence), propostas simultâneas e estado vazio; não reativar scroll automático após swipe. Checar especificamente a condição de item `thread-empty` versus o cálculo em modo Discurso inteiro. **Gate:** tests JVM + CI + validação do dispositivo conhecido.

**UX-2.3 — BUILD somente após UX-2.2 integrada:** detectar handoff pelo `turnId` entre `STREAMING/DONE` e mensagem do Room; preservar key e offset/posição, ignorar falsas novas mensagens/pílula; proteger chegada Room antes/depois do DONE. Sem mudar regras de `followEnd` da UX-2.1. Aceitar com scroll parado no meio, no fim real, cancelamento, retry, propostas e ao voltar de aba.

**UX-2.4 / UX-3 — Discovery antes de BUILD:** cards densos acessíveis; estados honestos (“Preparando”, “Lendo fontes”, “Escrevendo” somente quando a fase realmente ocorreu), streaming Markdown leve e conclusão suave. Não expor “raciocínio interno” suposto; reduced motion, TalkBack, sem animação por token.

### C. TRUST — frente crítica

**TRUST-0 — DISCOVERY SOMENTE LEITURA (iniciar já em paralelo).**
- Mapear todas as rotas de chat, fonte citada, global assistant, oratória, cards, draft, estrutura, Treino/Palco, fontes ausentes/waived, fallback local e sem chave.
- Inspecionar `SourceGate`, `GroundednessVerifier`, `CitationContract`, `OratoryFidelityCheck`, `verificationCorpus`, `buildEvidenceBlock` (PR #8) e como citações são resolvidas/inspecionadas.
- Separar evidências reais da fala do usuário, memórias, esboços, dossiê e textos criativos. Sem assumir que citar um capítulo prova uma interpretação.
- Produzir **matriz inicial de 40–60 casos** com fixtures/licenciamento respeitado: apoio correto; citação existente mas irrelevante; referência inexistente; trecho ausente/indexação; hipótese do usuário; memória; paráfrase indevida; ilustração; `th/be`; fontes conflitantes. Definir métricas de falsos positivos/falsos negativos, resultados e bloqueadores.
- Entregar mapa `arquivo:linha`, recomendações mínimas, ordem de PRs e teste antes/depois. **Sem código/Gradle/tablet/PR de implementação sem nova autorização.**

**TRUST-1 — CONTRATO:** tipos explícitos de fonte de evidência; LLM pode ver contexto conversacional mas verificador factual usa apenas material legitimamente autorizado; tratar ausência de fonte com resposta honesta, sem improvisar. Retirar “histórico do usuário como fonte” do corpus com testes de hipóteses/refutações e sem piorar citações legítimas. Preservar distinção publicação/indexação/status. Nenhuma redistribuição automática.

**TRUST-P0 — Bloqueio de conhecimento bíblico não verificado (prioridade imediata; discovery antes de BUILD).** Risco: o Mentor pode citar, parafrasear ou explicar textos bíblicos com conhecimento paramétrico do LLM sem recuperar a TNM. Priorizar correção isolada, sem aguardar TRUST-2B3B e sem interferir nos PRs ativos.

- **Discovery somente leitura:** mapear chat livre, assistente global, modo colar, perguntas sobre capítulos/versículos, cards, rascunhos, estrutura e geração de discursos. Inspecionar ChatPromptBuilder, MINIMAL_SYSTEM_PROMPT, SourceGate, GroundednessVerifier, verifyChatText, BiblePassageReader e RoomBiblePassageSource; verificar requireNwt, warnOnly, corpus vazio e fallback para outra Bíblia. Distinguir risco estático de reprodução em testes/logs.
- **Autoridade documental:** conhecimento do LLM serve à conversa e à pedagogia, nunca é evidência. Citações e paráfrases bíblicas requerem TNM efetivamente recuperada e identificada; explicações doutrinárias exigem unidade pertinente e verificável de publicação jw.org. th/be fundamentam oratória, não substituem evidência bíblica. Texto do usuário, S-34 e histórico podem ser discutidos com atribuição clara, sem promoção automática a TNM verificada.
- **Falha segura por afirmação:** sem evidência, não apresentar citação, paráfrase ou interpretação como confirmada; continuar ajudando com estrutura, perguntas, técnicas e ilustrações originais identificadas. Reconhecer referência não comprova conteúdo. Não bloquear indiscriminadamente menções bíblicas ou texto explicitamente fornecido pelo usuário.
- **Defesa em profundidade:** gate determinístico pré-geração para necessidade de fontes e pós-geração para saídas não sustentadas, centralizado e aplicado em todas as rotas. Aviso warnOnly não pode ser a única defesa; verificação de aspas e referências não substitui avaliação semântica. Distinguir fonte ausente, não indexada, ambígua e comprovada.
- **Matriz de aceite:** negativos com TNM ausente, outra Bíblia indexada, corpus vazio, referência sem trecho, capítulos, paráfrases sem aspas, interpretação inventada, memória, S-34, modo colar, respostas mistas e geração de discurso; positivos com TNM/publicação recuperada e mentoria puramente pedagógica. Gate: nenhuma afirmação bíblica/doutrinária apresentada como verificada sem vínculo real à evidência autorizada; medir escapes, falsos bloqueios, regressões de UX/acessibilidade e autoria.
- **Sequência:** discovery e reprodução com mapa arquivo:linha → contrato e testes → BUILD em branch isolada somente após autorização → JVM/CI e aparelho conforme rota → revisão de interação com TRUST-2B3B. Sem merge, release, migração Room, troca de provedor ou mudança de PRs existentes implícitos.

**TRUST-2 — AVALIAÇÃO SEMÂNTICA:** testar correspondência de afirmação→trecho específico; usar método conservador e mensurável, não assumir infalibilidade de outro modelo. Monitorar cobertura, explicações indevidas, recusas erradas, custo e latência. Liberar rota pedagógica ampliada apenas após critérios experimentais acordados.

**TRUST-3/4:** mostrar/ler proveniência do trecho utilizado, avaliação adversarial e piloto com discurso real. Auditoria de claims sem fonte; falha do detector não é certificado de correção.

### D. PEDAGOGY e aprendizado

**PEDAGOGY-0 — DISCOVERY:** estados de intenção (entender/organizar/rascunhar/revisar/ensaiar), pergunta única e útil, adaptação de profundidade, escolha de explicar de outro jeito, respeitar autoria e recusas; roteamento de “Explique melhor” em card × sugestão × tópico. Desenhar diálogos curtos de várias proficiências, sem exigir uma aula quando o usuário pede um rascunho.

**PEDAGOGY-1 — BUILD APÓS TRUST-1/avaliação de TRUST-2:** um tópico S-34 por vez; explicar com fontes; ouvir raciocínio próprio; sugerir melhoria explícita; entregar rascunho somente quando adequado; confirmar antes de aplicar. Métrica: usuário consegue explicar o tópico, encontrar apoio e preservar seu estilo.

### E. VOICE-FIRST / A11Y

Discovery de compreensão de comandos e fluxo usando motores atuais `PttSttEngineProvider`, `VoiceRouter`, Piper/STT nativo, leitura em voz alta, retomada, repetição, “mais devagar”, transcrição revisável, confirmação audível de alterações, interrupção/barge-in e acesso por texto/toque. Testes com TalkBack, escala grande de fonte e pessoas com menor fluência de leitura. Não prometer STT offline no fallback de sistema. Não sacrificar privacidade ou sustentabilidade.

### F. EDITOR-SIMPLE / Jornada vertical

Preservar um único documento por seção com **vistas Entender / Escrever / Ensaiar**, sem criar três cópias divergentes. Material original S-34 e refs, rascunho `contentHtml` e roteiro/Mapa mental precisam ser claramente distintos. Aceite sempre prévio à mutação e undo conforme fluxo atual. Definir transições para Treino/Palco sem perder o foco.

**Vertical de aceite:** usuário abre o Assistente (voz ou texto) → seleciona/importa S-34 manualmente → confirma objetivo/fonte → desenvolve um tópico → pede rascunho → escuta e revisa → confirma → texto salvo na seção correta → ensaio. Testar fonte ausente, troca de tópico, cancelamento, edição manual e reinício.

### G. COST / experiência, avaliação de modelos e operação

**COST-UX — roteamento local e profundidade adaptativa (nova frente).** Antes de chamar a API, avaliar de modo **determinístico, conservador e testável** se a intenção pode ser atendida localmente, qual contexto é indispensável e qual orçamento inicial de saída é apropriado. A classificação não deve exigir uma chamada de LLM, pois isso anularia parte da economia. Começar com discovery e testes; implementar depois de definir os contratos de diálogo, memória e confiança, sem bloquear o trabalho atual.

- **Zero tokens de API quando seguro:** saudações *isoladas* ("oi", "bom dia"), agradecimentos simples e comandos locais inequívocos podem receber resposta local curta e natural, sem chamada remota. Mensagens mistas ("oi, explique Romanos 8"), ambíguas, dependentes de histórico ("explique melhor") ou com pedido factual **não** entram nesse atalho. Normalizar variações de caixa, acentos, pontuação e espaços sem ampliar excessivamente o escopo; fallback seguro para fluxo normal.
- **Orçamento de resposta adaptativo:** intenção, complexidade, contexto e pedido explícito do usuário determinam profundidade inicial (breve, intermediária ou aprofundada), contexto recuperado e limite máximo de saída. Faixas exploratórias para medir, **não contratos rígidos**: breve ~80–180, intermediária ~200–500, aprofundada ~600–1.500+ tokens; resposta local usa 0 tokens de API. Ajustar a provedores, idioma, raciocínio e latência. Evitar truncar resposta, citações, ressalvas ou conclusões; prever expansão quando necessária.
- **Conversa natural:** respeitar "resuma", "explique melhor", "mais detalhes", "seja breve" e pedidos de rascunho longo; não encerrar mecanicamente toda resposta com pergunta, nem impor concisão que prejudique aprendizagem, acessibilidade ou fidelidade. Mensagem curta não implica tarefa simples. Não reduzir verificação factual ou RAG necessário para economizar tokens.
- **Critérios de aceite:** matriz com saudações isoladas/mistas, agradecimentos, ambiguidade, comandos, seguimento de conversa, perguntas bíblicas curtas porém complexas, ausência de fontes, solicitação explícita de profundidade e rascunhos longos. Medir falsos atalhos locais (meta: **zero nos casos críticos da matriz**), chamadas evitadas, tokens de entrada/saída, latência, custo por jornada, truncamentos, satisfação e regressões TRUST/A11Y. Exigir testes determinísticos e validação em aparelho antes de habilitar em produção.
- **Sequência:** discovery COST-UX em paralelo quando houver capacidade → implementação pequena, isolada e reversível → medir ganho real → só então incorporar o comportamento estabilizado ao baseline de COST-EVAL. Não alterar agora o provedor, o backend, o prompt de produção ou o fluxo de confirmação de edições.


**Princípio de produto — experiência acima da potência do modelo.** O usuário deve perceber um Mentor competente, paciente, natural, acessível e confiável, não um chatbot genérico. Priorizar o **modelo mínimo suficiente**: o modelo de menor **custo total por jornada aprovada** que satisfaça os gates de experiência e confiança. Não presumir equivalência a modelos premium, nem otimizar custos sacrificando fluidez, profundidade útil ou fidelidade.

**Otimizar primeiro a arquitetura:** reutilizar RAG local e evidências recuperadas, memória compacta de decisões aceitas, prompts específicos por intenção, tarefas determinísticas fora do LLM, controle de contexto, cache de prefixos quando suportado e verificação independente de fontes. Não enviar documentos inteiros ou histórico irrelevante. Medir custos de retries, verificações, tokens de raciocínio e eventuais escalonamentos; cache hit não é garantido. Preservar o contrato de privacidade e o isolamento de dados por usuário.

**COST-EVAL — avaliação antes da troca de provedor:** iniciar agora somente instrumentação e desenho de fixtures; executar comparação decisória **após baseline estável de memória, diálogo pedagógico, TRUST e COST-UX**, com prompt, corpus, RAG, memória e parâmetros congelados. Testar o DeepSeek atual contra candidatos econômicos e pelo menos uma referência premium, **sem fixar nomes ou preços antes de confirmar disponibilidade, API e tabela vigente**. Usar pelo menos 60 casos representativos: 15 mentoria/conversa, 10 desenvolvimento S-34, 15 explicações com fontes, 10 memória/continuidade e 10 ausência de evidência/ataques; incluir conversas de múltiplos turnos, texto em português, usuários com menor fluência de leitura, casos com fonte ausente e preparação longa. Comparação cega com avaliadores humanos e verificadores independentes, registrando latência p50/p95, tokens normais/em cache/de saída, custo por jornada aprovada, retries, recusas indevidas e erros de fidelidade.

**Critérios propostos, a validar no discovery:** mentoria/conversa 30%, fidelidade 30%, continuidade 20%, clareza/naturalidade 10%, latência/custo 10%. **Fidelidade é também gate eliminatório**, não compensável por pontuação alta em escrita ou preço; definir limiares concretos e taxa aceitável de erros antes do teste. Não selecionar modelo com base apenas em texto bonito, benchmark genérico ou preço por milhão de tokens. Se o modelo econômico cumprir os gates, manter modelo único; fallback premium apenas se testes demonstrarem ganho líquido suficiente e houver política de custo/privacidade.

**Operação:** instrumentar tokens/custos por **discurso preparado**, latência, retries, cache e consumo por voz; distinguir simulações de medições. Avaliar BYOK opcional, cota gratuita, contribuições voluntárias, quotas/antiabuso/backend somente depois de requisitos de privacidade, tributos e distribuição. Jamais incluir chave coletiva no APK. Não trocar provedor, implementar backend ou alterar prompts de produção apenas por esta decisão de roadmap.

## 6. Regime de trabalho: dois agentes + um Discovery

| Frente | Atribuição imediata | Área exclusiva | Bloqueios |
| --- | --- | --- | --- |
| **Agente A (segurança)** | Encerrar correção pontual e revisão do [PR #8](https://github.com/guilhermemellojw/better-talker/pull/8) | `ApproachMemory.kt`, `ChatEngine.kt`, gates/VM da 1C.2.0 na própria branch | Não iniciar ledger/Room/Trust-1; não fazer merge |
| **Agente B (UX)** | Encerrar [PR #9](https://github.com/guilhermemellojw/better-talker/pull/9) com revisão do head atual | `ChatScreen.kt`, `ChatMotionTest.kt` na branch exclusiva | Não começar UX-2.3 antes da integração da UX-2.2; não fazer merge |
| **Agente C (discovery)** | TRUST-0 somente leitura | Análise de rotas/relatório e matriz | Sem mutações, Gradle, tablet, edit em branches A/B |

Apenas um agente usa **o mesmo tablet** por vez; confirmar disponibilidade do A34 e do Xiaomi antes de executar testes. Não rodar tarefas pesadas Gradle em paralelo no mesmo ambiente. Os agentes não devem editar simultaneamente `docs/ROADMAP.md` nem `docs/AGENT_HANDOFF.md`. O **coordenador** atualiza o snapshot após revisão/integração, preferindo PR apenas de documentação.

**Ritmo de revisão:** branch isolada → testes focados → JVM/lint/CI → dispositivo quando necessário → relatório (SHA/diff/evidências/limitações) → revisão → autorização explícita para merge. Nenhum release implícito.

## 7. Definition of Done (DoD) para qualquer BUILD

1. Critério de usuário demonstrável, comportamento esperado e critérios de rejeição definidos antes da mudança.
2. Diff mínimo, sem alterar outras frentes; SHA base e head registrados.
3. Testes determinísticos para caso feliz, falha, corrida e regressão; CI verde no **head exato**.
4. Se aplicável, teste real com APK SHA-256, aparelho, Android, baseline de dados, resultado e restauração de configurações.
5. Para mudanças de fonte: não introduzir aceitação de fato sem evidência e não piorar tratamento honesto da ausência.
6. Para edições: confirmação explícita + rollback/undo compatível, sem perda de dados; migração separada com testes quando inevitável.
7. PR descreve limitações, riscos e o que fica para a próxima fase. **Sem merge/tag/release/versionCode sem autorização.**
8. Após merge autorizado: revalidar `main` + CI pós-merge, atualizar o estado neste roadmap via PR de documentação.

## 8. Riscos, hipóteses e decisões pendentes

| Tipo | Item | Encaminhamento |
| --- | --- | --- |
| **Decisão de produto** | Mentor pedagógico; autoria; somente TNM/jw.org para fatos; voz opcional e acessível | Preservar |
| **Risco crítico** | Referência válida sem apoio semântico, ou memória/usuário contaminando verificador | TRUST-0/1/2 |
| **Risco de integridade** | Aceite/truncamento em `agreedApproach`; histórico de decisões sem seção | PR #8 e 1C.2.1 |
| **Decisão técnica pendente** | Ledger local versus backup/sync; fontes de verdade e migração Room 26→27 | Discovery 1C.2.1 antes de código |
| **Decisão de UX pendente** | “Limpar conversa” apaga memória? edição manual invalida quais decisões? | 1C.2.2 |
| **Hipótese** | Três vistas simplificam o editor; feedback visual reduz confusão; voz realmente facilita aprendizado | Testes com usuários |
| **Hipótese** | Verificação semântica tem cobertura/latência/custo aceitáveis | TRUST-2 |
| **Proposta financeira** | Gratuidade + contribuições voluntárias + BYOK + backend de quotas | COST, após medições |
| **Princípio de seleção** | Experiência e confiança acima da potência/marca do LLM; modelo mínimo suficiente | COST-EVAL com gates de qualidade e custo total por jornada aprovada |
| **Otimização de experiência** | Saudações isoladas sem API e respostas de profundidade proporcional à intenção | COST-UX: roteamento conservador, orçamento adaptativo, testes de falso atalho e regressão TRUST |

## 9. Próximo checkpoint e manutenção do roadmap

**Checkpoint imediato:** revisar HEAD/CI do PR #8 e #9; solicitar apenas correções pontuais, integrar **somente com autorização**; lançar TRUST-0 como discovery independente; depois UX-2.3. Ao avançar, registrar **data UTC, base/main SHA, PR/head, CI, device, decisão de merge e evidências**. Nunca marcar “concluído” apenas porque um agente disse que executou; conferir GitHub e, quando relevante, artefatos.

**Estimativa de esforço global, não compromisso:** piloto controlado de 8–12 semanas; beta da visão em 4–6 meses; operação ampliada em 6–12 meses ou mais. Replanejar após TRUST-0 e medições de custo.

**Leituras de apoio:** [AGENT_HANDOFF.md](AGENT_HANDOFF.md) · [FREEZE.md](FREEZE.md) · [VISION.md](VISION.md) · [README](../README.md).

**Critério de norte:** “Agora eu entendo melhor como preparar e apresentar meu próprio discurso.”
