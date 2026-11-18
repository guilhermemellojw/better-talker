# Discovery - UX acessivel do Mentor (sem alteracao de codigo)

Data: 2026-11-18
Escopo: discovery tecnico inicial. Nenhuma alteracao de codigo, Room, RAG, memoria, sync, prompts ou versao.

## 1. Validacao da UX-2.3 (PR #12)

HEAD revisado: 2904b0b2b35afcb72a8cf921f514022d8b6386f2

Evidencias locais:

- Compilacao de ChatScreen.kt e suite de testes focados executada com sucesso.
- ChatMotionTest validando hasHiddenTurnMessage, renderedMessageCount, isTurnHandoff, isTurnHandoffArrival e messagesEffect.
- Lint executado com sucesso nos ajustes anteriores.
- CI push/PR em andamento no ultimo HEAD no momento deste relatorio.

Comportamentos validados localmente:

- handoff nao gera novidade quando a resposta persistida chega;
- evento de chegada e consumido uma unica vez;
- mensagem posterior (t2) conta como novidade normal;
- followEnd=false preserva posicao e incrementa unseenCount;
- followEnd=true pode acompanhar o fim;
- handoff + proposta simultanea mantem novidade da proposta;
- Room antes/depois de DONE nao duplica item por turnId.

## 2. Objetivo da acessibilidade

Tornar o Mentor utilizavel por pessoas com pouca familiaridade tecnologica ou dificuldade de leitura, sem substituir a conversa por formularios complexos e sem criar telas redundantes.

Principios:

- uma acao principal por vez;
- linguagem curta e direta em portugues;
- estado sempre visivel: pensando, consultando fontes, aguardando confirmacao;
- nenhuma alteracao de texto sem aceite explicito;
- reutilizar componentes existentes.

## 3. Fluxos propostos

### Fluxo A - Preparar um topico

1. Usuario escolhe um topico.
2. Mentor mostra: "Vou preparar este topico. Posso continuar?"
3. Usuario toca "Sim" ou "Nao".
4. Mentor mostra status curto: "Lendo as fontes...", "Pensando...", "Pronto".
5. Resultado aparece em um card simples com dois botoes: "Usar" e "Ajustar".

### Fluxo B - Revisar resposta

1. Mentor apresenta texto curto.
2. Botoes: "Ler em voz alta", "Ajustar", "Aceitar".
3. Se o usuario pedir ajuste, o Mentor pergunta apenas uma coisa de cada vez.

### Fluxo C - Cancelar ou repetir

1. Botao visivel "Parar" durante geracao.
2. Apos cancelamento, botao "Tentar de novo".
3. Nenhuma mensagem de erro tecnica; apenas texto simples.

## 4. Componentes existentes reutilizaveis

- ChatScreen e CopilotViewModel: fluxo de turno, streaming, cancelamento e persistencia.
- turnStatusAnnouncement / LiveRegionMode.Polite: anuncios de estado para TalkBack.
- PttMicButton: entrada por voz.
- speakMessage / TTS existente: leitura em voz alta.
- HelpSheet / HelpEngine: ajuda contextual e rotulos curtos.
- OnboardingScreen / sheets de onboarding: padrao de orientacao passo a passo.
- Cards existentes (ProposalCard, StructureProposalCard, etc.): aceite explicito.
- ChatMotion / isAtListBottom: comportamento de scroll ja estabilizado.
- CardDisclosure: revelacao progressiva de detalhes.

## 5. Mudancas de UI propostas (futuras, pequenas)

- Reduzir densidade visual: ocultar detalhes avancados por padrao.
- Botoes com rotulos curtos e alvos de toque adequados.
- Status em uma linha, com texto simples.
- Fonte respeitando escala do sistema.
- Sempre manter foco do TalkBack no item atual.
- Evitar acoes destrutivas sem confirmacao.

## 6. Testes necessarios

- TalkBack: anuncio de "Pensando", "Pronto", "Parado".
- Fontes ampliadas: layout nao quebra.
- Toque: botoes principais acessiveis.
- Fluxo de aceite: nenhuma alteracao sem confirmacao.
- Cancelamento e retry em modo simplificado.
- Handoff da UX-2.3 preservado no modo acessivel.

## 7. Riscos

- Excesso de simplificacao pode esconder funcoes importantes.
- Duplicacao de fluxos se criar telas paralelas.
- Regressao de scroll/acessibilidade se alterar layout sem testes.
- Dependencia de TTS/STT do dispositivo.

## 8. Proposta de PR pequeno e independente

Titulo sugerido: feat(ux-a11y): modo de conversa simplificado (discovery + primeiros ajustes)

Escopo minimo:

1. Adicionar preferencia "Modo simples" (apenas flag, sem alterar dados).
2. No modo simples, mostrar apenas acao principal e status curto.
3. Reutilizar TTS/PTT existentes para leitura e entrada por voz.
4. Adicionar testes de semantica e anuncios.

Nao incluir neste PR:

- alteracao de Room, RAG, memoria, sync, prompts ou versao;
- redesign completo;
- novas telas paralelas.

## 9. Proximo passo

Apos CI verde do PR #12, abrir issue/PR separado de acessibilidade com escopo minimo e validacao em dispositivo real.
