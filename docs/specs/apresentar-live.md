# Spec — Palco (apresentar ao vivo)

Status: implementado até a v5.3 (Palco do orador + roteiro completo de
demonstração); tablet e A34 validados. Sem TV/casting.
Base: main; Tribuna atual serve como preparação/edição.

## 1. Objetivo

Apresentar é o apoio de palco: uma "cola" discreta, não um teleprompter.
O orador deve olhar para a plateia e consultar cues; o app deve exigir
pouca interação, funcionar offline e não distrair.

## 2. Nomenclatura

- Aba: "Palco" (ex-"Apresentar").
- Tela ao vivo: "Palco".
- "Apresentar" é a ação.
- "Tribuna" é a preparação/edição; o palco é read-only.

## 3. Jornada

ESBOÇOS → card ⋮ → Apresentar (define o esboço atual) → PALCO
(esboço atual + prontidão + retomada) → preparação/pré-voo → PALCO → encerrar.

Palco → Trocar esboço → picker → escolhe → volta.

## 4. Aba Palco

- Cartão do esboço atual com tipo, duração, nº de seções e prontidão:
  - Discurso: "Tribuna pronta", "Tribuna incompleta", "Falta gerar a Tribuna";
  - Demonstração: "Roteiro pronto", "Roteiro incompleto", "Falta o roteiro".
- Ação por estado:

| Estado | Discurso | Demonstração |
|---|---|---|
| READY | Apresentar | Apresentar |
| PARTIAL | Abrir Tribuna | Abrir Roteiro (+ Abrir editor) |
| MISSING | Gerar Tribuna | Abrir editor |

- Em demonstração, o roteiro nasce dos turnos do editor. Nunca oferecer
  "Gerar Tribuna" em demo — é um beco sem saída.
- A lista completa fica em Esboços; "Trocar esboço" abre o picker.
- "Continuar de onde parei" quando houver sessão anterior.
- Tribuna/roteiro ausente: não abre palco vazio; leva ao gerador ou ao editor.

## 5. Pré-voo

- Discurso: modo **Lembretes** (cues).
- Demonstração: modo **Roteiro** — completo da seção (padrão) | só o turno
  atual (teleprompter). Não há “Lembretes” na demo.
- Começar do início | Escolher seção.
- Timer visível | Timer oculto.
- Fonte A−/A+.
- Orientação: Retrato (padrão) | Automática | Travar.
- Entrar no palco.
- Imagens (somente discurso): cues de imagem com legenda, arquivo local
  disponível e tira das imagens da nota (a mesma fonte do editor). A imagem é
  só do orador; o app não envia nada para a TV.
- Demonstração não tem imagens: nem no editor, nem na Tribuna, nem no pré-voo,
  nem no Palco.
- Checagem: tela acesa, bateria, notificações, orientação, fonte e offline.
- Editar Tribuna/roteiro fica no pré-voo ou editor; nunca no palco.

## 6. Palco

Tela cheia, alto contraste, sem barra global, sem edição.

Anatomia:

- indicador de seção (2/6), título e timer;
- timer total e tempo da seção;
- mapa de lembretes ou um lembrete em foco;
- cue de imagem com número e legenda; thumbnail pequeno no dispositivo do
  orador, resolvido pelo `imageId` em todas as imagens da nota;
- âncora em destaque;
- ideia central;
- texto-chave;
- transição;
- anterior/próxima.

Regras:

- uma seção por vez, não lista comprida;
- avanço manual por swipe/botões; sem auto-avanço por tempo;
- toque no thumbnail abre a imagem em tela cheia; fechar/Voltar retorna ao
  mesmo ponto e o cronômetro continua;
- imagem removida ou arquivo ausente mostra “Sem imagem”; nunca um órfão;
- a imagem nunca é projetada pelo app; a TV é da equipe de A/V;
- edição bloqueada; sair exige confirmação/long-press;
- tela sempre acesa; sistema imersivo;
- sem TTS, STT, métricas ou Copilot ao vivo;
- offline por padrão;
- rotação mantém seção/timer/estado;
- retomada após background/call.

## 7. Demonstrações V&M

Modo Roteiro (padrão):

- mostra o roteiro completo da seção, não só o turno atual;
- o turno atual fica destacado e o `Anterior/Próximo` move o destaque e o
  scroll (inclusive entre seções);
- blocos sem papel (direções, narrativa, leitura) aparecem como narração;
  nada é descartado pelo parser;
- se não houver turnos reconhecidos mas houver texto, mostra o texto completo
  como narração e considera a seção pronta;
- papel do usuário destacado; o outro esmaecido;
- swipe/Anterior/Próximo avança um turno;
- sem TTS ao vivo.

Modo “Só turno atual” (opcional):

- mostra apenas o turno atual e uma deixa do próximo, como teleprompter;
- o Treino continua no modo turno a turno; não é o padrão do Palco.

Tribuna e Palco usam o mesmo renderizador de diálogo (`DemoScript`).

A demonstração não tem imagens: o editor esconde “Imagens da nota”, o chip
“Imagens (n)” e “Imagens da seção” no menu da seção; a Tribuna não mostra a
tira; o pré-voo não mostra imagens nem “Imagens da nota”; o sheet de imagens
não abre. A imagem é recurso de discurso; o Palco demo é só roteiro.

## 8. Orientação

- Retrato é o padrão.
- Layout responsivo para paisagem.
- Opção "Travar orientação" no pré-voo para evitar giro acidental.
- Rotação não perde estado.
- Tablet e celular usam o mesmo layout.

## 9. Timer e ciclo de vida

- Timer começa no "Entrar no palco / Começar".
- Pausa automática quando o app perde foco, entra chamada ou tela bloqueia.
- Heads-up notification sem sair do app não pausa.
- Ao voltar: "Tempo pausado — Retomar"; não retoma sozinho.
- Tempo de fala não conta a interrupção.
- Ao sair e voltar: "Continuar de onde parei".

## 10. Falhas

| Situação | Comportamento |
|----------|---------------|
| Tribuna ausente | aba bloqueia e oferece gerar |
| Tribuna incompleta | aviso por seção; permite começar |
| Bateria baixa | aviso no pré-voo |
| Tela bloqueou | volta na mesma seção/timer |
| Chamada/notificação | pausa; retomar com confirmação |
| Saiu sem querer | confirmação; retomar de onde parou |
| Imagem removida/arquivo ausente | cue continua, com “Sem imagem” |
| Rotação | mantém estado |

## 11. Fora de escopo

- teleprompter de texto completo para discursos;
- métricas/feedback ao vivo;
- gravação de áudio;
- chat/Copilot;
- edição da Tribuna no palco;
- auto-avanço por tempo;
- notificações do app durante o palco;
- dependência de internet.

## 12. MVP vs futuro

MVP:

- aba com prontidão;
- pré-voo (cues/roteiro, seção inicial, timer, fonte, orientação);
- palco com uma seção por vez;
- timer total/seção;
- anterior/próxima;
- tela acesa, edição bloqueada, retomada;
- modo roteiro completo para demo, com narração e turno atual; demo não tem
  “Lembretes” nem imagens;
- modo “turno atual” opcional;
- cue de imagem (discurso) com thumbnail e tela cheia local;
- resolução do `imageId` em todas as imagens da nota (discurso);
- pré-voo com checagem de imagens locais (discurso);
- pausa em chamada/perda de foco;
- sem TTS/STT/chat/gravação; sem TV/casting.

Futuro:

- avanço por volume/controle remoto;
- tempo por seção e resumo pós-apresentação;
- tela externa/segundo display;
- modo "só minha fala" para dupla;
- anotações pós-palco;
- exportar cues em PDF.

## 13. Implicações de código

- Separar Tribuna de preparação/edição da nova tela de palco (rota própria,
  read-only).
- Reutilizar TribunaCapsule e o roteiro (`parseStageScript`) no Palco/Tribuna;
  o Treino mantém `parseStageTurns` (só falas).
- Compartilhar `DemoScript` entre Tribuna e Palco, com narração preservada.
- Resolver o `imageId` da cue em todas as imagens da nota (seção primeiro),
  usando a mesma fonte do editor (`note_images`).
- Timer próprio de apresentação, com pausa por ciclo de vida.
- keepScreenOn, imersivo, orientação responsiva com trava opcional.
- Corrigir prontidão/estado vazio na aba.
- Nada de edição/TTS/export no palco; nada de casting/TV.

## 14. Critérios de aceite

- A aba mostra pronta/falta gerar e não abre palco vazio.
- Pré-voo permite cues/roteiro, seção inicial, timer e orientação.
- Palco mostra uma seção por vez, âncora em destaque e timer.
- Avanço manual; edição bloqueada; sair com confirmação.
- Chamada/perda de foco pausa e pede retomada.
- Rotação mantém estado; retrato é o padrão.
- Demo mostra o roteiro completo da seção, com o turno atual destacado; o
  modo “só turno atual” é opcional.
- Demo não mostra imagens em lugar nenhum (editor, Tribuna, pré-voo, Palco) e
  não oferece “Lembretes”.
- Narração/direções do roteiro não somem; fallback de texto corrido funciona.
- Cue de imagem usa a mesma imagem do editor; toque abre em tela cheia e
  Voltar retorna ao Palco com o timer correndo.
- Funciona offline; sem áudio gravado; sem casting/TV.
- A34 e tablet validados; suíte + lintRelease verdes.
