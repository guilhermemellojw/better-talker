# Spec — Palco (apresentar ao vivo)

Status: aprovado para documentação. Nenhum código de palco implementado.
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
  - "Tribuna pronta";
  - "Falta gerar a Tribuna";
  - "Tribuna incompleta";
  - em demonstrações, "Roteiro".
- Ação principal: Apresentar (ou Gerar Tribuna quando falta).
- A lista completa fica em Esboços; "Trocar esboço" abre o picker.
- "Continuar de onde parei" quando houver sessão anterior.
- Tribuna ausente: não abre palco vazio; oferece Gerar Tribuna ou editor.
- Corrigir o bug atual de erro no chat enquanto a Tribuna abre vazia.

## 5. Pré-voo

- Cues | Roteiro (roteiro só para demonstrações).
- Começar do início | Escolher seção.
- Timer visível | Timer oculto.
- Fonte A−/A+.
- Orientação: Retrato (padrão) | Automática | Travar.
- Entrar no palco.
- Checagem: tela acesa, bateria, notificações, orientação, fonte.
- Editar Tribuna fica no pré-voo ou editor; nunca no palco.

## 6. Palco

Tela cheia, alto contraste, sem barra global, sem edição.

Anatomia:

- indicador de seção (2/6), título e timer;
- timer total e tempo da seção;
- âncora em destaque;
- ideia central;
- texto-chave;
- transição;
- anterior/próxima.

Regras:

- uma seção por vez, não lista comprida;
- avanço manual por toque/swipe; sem auto-avanço por tempo;
- edição bloqueada; sair exige confirmação/long-press;
- tela sempre acesa; sistema imersivo;
- sem TTS, STT, métricas ou Copilot ao vivo;
- offline por padrão;
- rotação mantém seção/timer/estado;
- retomada após background/call.

## 7. Demonstrações V&M

Modo Roteiro:

- turno atual em destaque;
- próximo turno menor, como deixa;
- papel do usuário destacado; o outro esmaecido;
- toque avança um turno;
- sem TTS ao vivo.

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
- modo roteiro para demo;
- pausa em chamada/perda de foco;
- sem TTS/STT/chat/gravação.

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
- Reutilizar TribunaCapsule e parseStageTurns.
- Timer próprio de apresentação, com pausa por ciclo de vida.
- keepScreenOn, imersivo, orientação responsiva com trava opcional.
- Corrigir prontidão/estado vazio na aba.
- Nada de edição/TTS/export no palco.

## 14. Critérios de aceite

- A aba mostra pronta/falta gerar e não abre palco vazio.
- Pré-voo permite cues/roteiro, seção inicial, timer e orientação.
- Palco mostra uma seção por vez, âncora em destaque e timer.
- Avanço manual; edição bloqueada; sair com confirmação.
- Chamada/perda de foco pausa e pede retomada.
- Rotação mantém estado; retrato é o padrão.
- Demo mostra turno atual e papel destacado.
- Funciona offline; sem áudio gravado.
- A34 e tablet validados; suíte + lintRelease verdes.
