# Spec — Treino MVP

Status: aprovado para documentação. Nenhum código de Treino implementado.
Base: main (C2 + M1/M1.1 + card-first 1–4).

## 1. Objetivo

Treino é o laboratório do orador: ler, falar, medir e repetir —
sem gravar áudio no MVP, sem nota e sem julgamento.

O usuário deve sair sabendo o que funcionou, o que ajustar e qual o próximo exercício.

## 2. Nomenclatura

- Barra inferior: "Treino".
- Aba: "Treino" (ex-"Ensaio").
- Sessão: "Treino" + título da nota.
- Resultado: "Resultado do treino".
- "Ensaio" fica apenas em nomes internos/código.
- "Palco" é uma frente separada (apresentação ao vivo).

## 3. Jornada

ESBOÇOS → card ⋮ → Treinar (define o esboço atual) → Treino (aba)
→ preparar treino → Treino → Resultado → Treinar de novo / outro trecho / voltar.

Treino → Trocar esboço → picker → escolhe → volta.

## 4. Aba Treino

- Cartão do esboço atual com tipo, duração e nº de seções.
- A lista completa fica em Esboços; a troca é explícita ("Trocar esboço" → picker).
- Última vez treinada e último resultado, quando houver.
- Ação principal: "Treinar".
- "Continuar último treino" — **v2.10.1** (histórico de sessão; fora do MVP).

> Histórico de sessões, "última vez treinada" e "último resultado" ficam
> para a **v2.10.1**. O MVP é stateless entre sessões: só a preparação
> (escopo/modo/timer) é lembrada por nota.
- Estado vazio sem seleção: "Escolha um esboço para treinar." + picker.
- Sem esboços: "Crie seu primeiro esboço para treinar." + CTA para Esboços.
- Sem texto: "Sem texto para treinar" + "Abrir editor".
- Não é dashboard; é esboço atual + prontidão + última preparação + Trocar esboço.

## 5. Preparação

Sheet curto:

- Escopo: Discurso inteiro | Escolher trecho (seção/subponto).
- Modo: Com análise de fala | Só leitura.
- Cronômetro: ligado | sem cronômetro.
- Começar treino.

Regras:

- Lembrar a última escolha por nota; na primeira vez, discurso inteiro +
  análise se disponível.
- Sem STT disponível: "Com análise" desabilitado com motivo; "Só leitura"
  pré-selecionado.
- "Escolher trecho" lista seções/subpontos com duração.
- Trecho usa a duração da seção como meta.

## 6. Sessão

Tela cheia, alto contraste, tela sempre acesa.

Deve ter:

- texto do discurso no centro, fonte ajustável;
- "Começar treino" explícito: o timer não começa ao abrir;
- timer com meta, restante/excedido e pausa;
- controles fixos: pausar/retomar, fonte, ouvir/parar, microfone, sair;
- modo dupla para demonstrações: papel em destaque, TTS lê só as falas;
- análise: iniciar → pausar → retomar → parar;
- banner honesto quando o STT falhar;
- nenhum áudio gravado no MVP; transcrição descartada após as métricas.

Não deve ter:

- cronômetro correndo antes do Começar;
- controles que somem sozinhos;
- gravação de áudio no MVP;
- métricas inventadas quando o STT perde fala;
- score, streak ou gamificação no MVP.

## 7. Modos

| Modo | Texto | Timer | Análise | Gravação |
|------|-------|-------|---------|----------|
| Leitura | sim | opcional | não | não |
| Cronometrado | sim | sim | não | não |
| Com análise | sim | sim/opcional | sim | não |
| Trecho | sim | meta da seção | opcional | não |
| Dupla/demo | papéis | sim | opcional | não |
| Gravado | sim | opcional | não | sim — v2.11 |

Gravação e análise não rodam juntas no MVP: o STT do sistema ocupa o microfone.

## 8. Resultado

- Tempo de fala e tempo de sessão.
- Ritmo (wpm) vs faixa de referência.
- Cobertura e termos que faltaram.
- Muletas mais repetidas.
- Quatro blocos: o que foi bem, o que ajustar, padrão observado, exercício.
- "Ouvir feedback" via TTS.
- Ações: Treinar de novo, Treinar outro trecho, Voltar.
- Amostra curta/STT falhou: não julgar ritmo/cobertura; dizer
  "pouca fala capturada" e preservar o timer.

## 9. Falhas

| Situação | Comportamento |
|----------|---------------|
| Nota sem texto | "Esta nota ainda não tem texto para o treino." |
| Sem meta | timer normal, sem restante/excedido |
| Microfone negado | banner; leitura/timer continuam |
| STT indisponível/error=10 | banner; análise desabilitada; leitura funciona |
| TTS indisponível | fallback nativo ou "Ouvir" desabilitado com motivo |
| Saiu da tela | para TTS e microfone; não deixa áudio aberto |

## 10. Privacidade

- Nenhum áudio gravado no MVP.
- Transcrição descartada após as métricas.
- STT usa o serviço do sistema; pode usar rede.
- TTS Piper offline por padrão; fallback nativo.

## 11. v2.11 — gravação e progresso

Gravação opt-in, local, nunca na nuvem:

- indicador vermelho "Gravando";
- arquivo privado do app, fora do backup;
- Ouvir e Apagar;
- apagar ao sair ou após N dias;
- mostrar espaço usado;
- permissão explicada;
- sem transcrição persistida junto do áudio.

Progresso/desafios:

- constância semanal sem punir dia perdido;
- marcos: primeiro treino, 5 treinos, introdução 3x, conclusão no tempo;
- desafios de habilidade: "1 min sem 'né'", "treine a introdução 2x";
- tendência de ritmo/cobertura/muletas;
- próximo exercício sugerido;
- sem ranking, pontos por tempo de tela, gamificação de conteúdo bíblico
  ou streak punitivo.

## 12. Fora de escopo

- gravar áudio no MVP;
- nota/ranking/gamificação no MVP;
- social;
- feedback de conteúdo por IA que julgue doutrina ou estilo;
- histórico persistido no MVP.

## 13. Critérios de aceite do MVP

- A aba Treino abre no esboço atual; sem seleção mostra empty state + picker.
- "Treinar este esboço" no menu do card define o esboço atual.
- Preparação permite inteiro/trecho, análise/leitura e timer on/off.
- Timer só começa no "Começar treino".
- Trecho usa a duração da seção como meta.
- Resultado mostra métricas e quatro blocos.
- "Treinar de novo" e "Outro trecho" funcionam.
- STT indisponível não bloqueia leitura/timer.
- Nenhum áudio é gravado.
- A34 e tablet validados.
- Suíte + lintRelease verdes.
