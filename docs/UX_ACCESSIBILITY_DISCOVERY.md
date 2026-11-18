# Discovery - UX acessivel do Mentor (sem alteracao de codigo)

Data: 2026-11-18
Escopo: discovery tecnico inicial. Nenhuma alteracao de codigo, Room, RAG, memoria, sync, prompts ou versao.

## 1. Validacao da UX-2.3 (PR #12)

HEAD revisado: ff4218f61abda33acebcee6769ed892230dff98e

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

## 2.1. Confirmações e detalhes no Modo simples

- Ações não destrutivas (ler em voz alta, mostrar detalhes, abrir ajuda) não exigem confirmação extra.
- Toda alteração aplicada ao discurso exige confirmação explícita.
- No Modo simples, podem ser ocultados por padrão: métricas detalhadas, diagnósticos extensos, histórico técnico e opções avançadas.
- Nunca podem ser ocultados: avisos de confiabilidade, fontes, cancelamento, erro recuperável e confirmação de aplicação.

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

## 5.1. Critérios objetivos de acessibilidade

- Alvos de toque mínimos de 48dp.
- Contraste mínimo WCAG AA para texto principal.
- Suporte a fonte ampliada sem corte de conteúdo.
- TalkBack anuncia estado atual em até 1 segundo.
- Foco não se perde após streaming, handoff ou scroll.
- Fluxo principal concluível com no máximo 3 toques.

## 6. Testes necessarios

- TalkBack: anuncio de "Pensando", "Pronto", "Parado".
- Fontes ampliadas: layout nao quebra.
- Toque: botoes principais acessiveis.
- Fluxo de aceite: nenhuma alteracao sem confirmacao.
- Cancelamento e retry em modo simplificado.
- Handoff da UX-2.3 preservado no modo acessivel.
- Validação em dispositivo real: TalkBack ligado, fonte ampliada, streaming longo, Room antes/depois de DONE, cancelamento e retry.


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
