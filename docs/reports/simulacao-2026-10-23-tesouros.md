# Simulação — TESOUROS 10 min (persona: servo ministerial)

**Build:** v2.7.4 (versionCode 24) — o prompt pedia v2.7.3, mas o usuário autorizou v2.7.4
**Branch:** `feat/copilot-citations` (`cbe8e13`)
**Data/hora:** 2026-10-23, 11:15–11:28
**Tablet:** 25040RP0AL (taiko)
**Nota usada:** “Simulação TESOUROS - Baruque (10 min)” (criada para o teste; 3 seções: Intro/Body/Conclusão; `w25.07` indexado no acervo)
**Chat limpo?** Sim — nota nova, sem histórico
**Escopo:** Discurso inteiro

> **Nota de método:** o `adb input text` removeu acentos e, em alguns pontos, comeu a letra
> inicial “s” (ex.: “obre” por “sobre”); as respostas brutas abaixo vêm da tabela
> `chat_messages` (fonte de verdade), não do `uiautomator` (que não expõe os textos longos).

## Turno 1 — abertura vaga

**Prompt (ASCII):**
```
oi, preciso fazer o discurso de 10 min de TESOUROS DA PALAVRA DE DEUS na proxima reuniao. por onde comeco?
```

**Resposta bruta (52 chars):**
```
Mude o escopo para Tópico para navegar entre seções.
```

**Orientou sem framework?** ❌ Não — não orientou; devolveu um template de navegação.
**Perguntou 1 coisa por vez?** ❌ Não.
**Entregou card antes do tempo?** ❌ Não.
**Classificação:** ❌ — template do router, não resposta do copilot. Evidência: `sim_t1.png`.

## Turno 2 — ideia central

**Prompt (ASCII):**
```
entao, o texto e obre Baruque, o secretario de Jeremias. mas eu nao sei se entendi bem qual e a ideia central que o irmao quer que eu passe. a apostila fala pare de procurar grandes coisas para si. isso e a ideia central?
```

**Resposta bruta (1.465 chars):**
```
Boa pergunta — e a resposta é: sim, mas com um ajuste importante.

"Pare de procurar grandes coisas para si" é o *remédio* que Jeová deu a Baruque. A ideia central que você quer que o público leve para casa é o *contentamento*. Repare como o esboço já aponta nessa direção: o problema foi Baruque não estar satisfeito com sua situação, e a solução de Jeová foi ajudá-lo a mudar o ponto de vista dando-lhe esperança.

Então a espinha do discurso fica assim:
- Baruque caiu numa armadilha: ficou distraído, pensando demais em si mesmo e no que queria fazer.
- A falta de contentamento rouba a alegria e leva a decisões ruins.
- Jeová corrigiu com carinho e apontou para a esperança.
- Nós também precisamos meditar na nossa esperança para não cair na mesma armadilha.

Ou seja: o texto de Jeremias 45 é o *caso*, e o contentamento é a *lição*. Se você pregar só o "pare de procurar grandes coisas", o discurso vira uma bronca. Se pregar o contentamento ancorado na esperança, vira encorajamento — que é o tom que a apostila quer.

Um cuidado para a conclusão: não repita o caso de Baruque de novo. Ele já foi contado no corpo. Na conclusão, leve o público a se perguntar: 〈sugestão〉"O que eu estou procurando que está me tirando a alegria? E a minha esperança está grande o bastante para eu ficar satisfeito com o que tenho hoje?"〈/sugestão〉

Quer que eu monte a conclusão de 2 minutos nessa linha?

_Se estiver de acordo, diga "insira isso" para eu colocar no card._
```

**Usou o texto real do w25.07?** ✅ Sim — Baruque/Jeremias 45, “pare de procurar grandes coisas”, contentamento/esperança.
**Confirmou/refinou a ideia central?** ✅ Sim — “contentamento é a lição”, com o ajuste de tom.
**Citou página errada/inventou?** ❌ Não.
**Classificação:** ✅ — a melhor resposta da jornada. Evidência: `sim_t2.png` + banco.

## Turno 3 — tempo / proporção

**Prompt (ASCII):**
```
e quanto texto cabe em 10 min? eu tenho medo de escrever demais. quanto e a introducao, quanto e o desenvolvimento e quanto e a conclusao?
```

**Resposta bruta (1.110 chars):**
```
Boa pergunta — e esse medo é saudável. Orador que escreve demais quase sempre fala rápido demais e perde o contato com o público.

A conta é simples: fale em ritmo calmo, com pausas. A média segura é cerca de 130 palavras por minuto. Então, para 10 minutos, mire em torno de 1.300 palavras no total — e trate isso como teto, não como meta.

A divisão que o seu esboço já traz funciona bem:
- Introdução: 1 min → cerca de 130 palavras.
- Desenvolvimento: 7 min → cerca de 900 palavras.
- Conclusão: 2 min → cerca de 260 palavras.

Repare que a conclusão é a segunda maior parte. Isso é de propósito: é ali que o público decide o que vai levar para casa. Não economize nela.

Uma dica prática: escreva o desenvolvimento primeiro, conte as palavras, e só depois ajuste a introdução e a conclusão para caber no tempo. Se o desenvolvimento passar de 900 palavras, corte ali — não na conclusão.

E lembre-se: você não precisa escrever tudo palavra por palavra. Um esboço com ideias-chave e textos citados deixa você mais natural do que um texto decorado.

Quer que eu já monte a conclusão dentro dessas 260 palavras?
```

**Números concretos?** ✅ 130 wpm / 1.300 palavras / 1-7-2 min (130/900/260).
**Densidade?** ✅ Sim — “corte no desenvolvimento, não na conclusão”.
**Fugiu da pergunta?** ❌ Não.
**Classificação:** ✅.

## Turno 4 — aplicação vs conclusão

**Prompt (ASCII):**
```
e a aplicacao? eu coloco isso no fim? e a mesma coisa que a conclusao?
```

**Resposta bruta (47 chars):**
```
Mude o escopo para Tópico para inserir no card.
```

**Distinguiu conclusão de aplicação?** ❌ Não — template do router; o modelo não foi chamado (mesmo segundo do prompt).
**Disse que aplicação pode estar dentro do desenvolvimento?** ❌ Não neste turno (o Turno 5 recupera: “a aplicação não é uma quarta seção — ela vive dentro da conclusão”).
**Entrou em meta-vocabulário?** ❌ Não; devolveu instrução de escopo.
**Tratou como sinônimos?** ❌ Não respondeu.
**Classificação:** ❌ — mesmo padrão do Turno 1: template de escopo/inserção. Evidência: banco.

## Turno 5 — pedido dos cards

**Prompt (ASCII):**
```
beleza, entao me da o texto pronto pra colar nos cards: INTRODUCAO, DESENVOLVIMENTO e CONCLUSAO. pode ser curto, que eu ajusto depois.
```

**Resposta bruta (2.809 chars):**
```
Perfeito. Antes de colar, um ajuste rápido de estratégia: a aplicação não é uma quarta seção — ela vive *dentro* da conclusão. Então a conclusão faz três coisas: lembra o remédio de Jeová, aplica ao público e termina com esperança. Vou entregar os três blocos curtos, como você pediu.

<texto-do-card>INTRODUÇÃO

Você já sentiu que a sua situação na vida poderia ser melhor? Que outros recebem mais reconhecimento, mais privilégios, mais oportunidades? Se sim, você não está sozinho.

Há quase 2.600 anos, um homem fiel sentiu algo parecido. O nome dele era Baruque, o secretário do profeta Jeremias. Ele tinha uma designação difícil e, com o tempo, começou a pensar demais em si mesmo.

Hoje vamos ver como Baruque caiu nessa armadilha, como Jeová o ajudou a sair dela e o que isso ensina sobre o segredo do contentamento.</texto-do-card>

<texto-do-card>DESENVOLVIMENTO

Baruque tinha uma designação difícil: ajudar Jeremias a transmitir uma mensagem forte para uma nação ingrata. Mas depois de um tempo, ele ficou distraído. Em vez de se concentrar no que Jeová queria, parece que começou a pensar demais em si mesmo.

Por meio de Jeremias, Jeová disse a Baruque: "Você está procurando grandes coisas para si." Era como se Jeová dissesse: fique satisfeito com a sua situação atual. E Baruque aceitou essa correção.

Por que isso importa para nós? Porque a falta de contentamento é perigosa. Quem nunca está satisfeito pode viver fazendo horas extras por coisas que nem precisa. Alguns cristãos chegaram até a roubar, pensando: "Eu mereço isso." Outros ficaram tão chateados por não receber um privilégio que desistiram de servir.

Mas Jeová não só corrigiu Baruque — ele lhe deu esperança. Disse que preservaria a vida dele. E é isso que nos sustenta hoje. Vivemos "nos últimos dias" e enfrentamos problemas "difíceis de suportar". Nossa esperança nos ajuda principalmente nos problemas pessoais. Talvez você lute para sustentar a família — mas Jeová prometeu dar o necessário.</texto-do-card>

<texto-do-card>CONCLUSÃO

Então, qual é o segredo? Contentamento não é conformismo. É confiar que Jeová sabe o que é melhor para nós.

〈sugestão〉É como um viajante que carrega só o necessário: ele não para de andar, mas também não se curva sob o peso de coisas que não precisa.〈/sugestão〉

A aplicação é pessoal. Pergunte-se: estou procurando grandes coisas para mim? Ou estou satisfeito com o que Jeová me dá hoje?

Baruque aceitou a correção e Jeová cuidou dele. Jeová fará o mesmo por você. Fique satisfeito com a sua situação atual — e mantenha os olhos na esperança que ele nos deu.</texto-do-card>

Ficou em torno de 1.250 palavras no total — dentro do teto de 10 minutos. Se quiser, no próximo passo eu enxugo o desenvolvimento ou troco a ilustração do viajante por outra mais próxima da sua congregação.
```

**Entregou 3 cards?** ✅ Sim — 3 `<texto-do-card>` separados (INTRODUÇÃO, DESENVOLVIMENTO, CONCLUSÃO).
**Separou intro/desenv/conclusão?** ✅ Sim.
**Cada card tem ideia central + texto-chave + frase-âncora + transição?** 🟡 Parcial — são os textos de fala; as 4 cápsulas da Tribuna não estão explicitadas.
**Usou o w25.07?** ✅ Sim (Baruque, “pare de procurar grandes coisas”, “fique satisfeito com a sua situação atual”, esperança).
**Respeitou 10 min?** ✅ ~1.250 palavras (teto 1.300); a divisão não está marcada por card, mas o total cabe.
**Orientou antes?** 🟡 Sim, 1 parágrafo (“a aplicação não é uma quarta seção…”). O hint C8 da v2.7.4 cobre “gera/escreve/entrega”; “me dá o texto pronto” não disparou o atalho.
**Truncou?** ❌ Não.
**Cards usáveis?** ✅ Sim/parcial — o texto está pronto para ajustar; faltam as cápsulas estruturadas.

## Jornada

- **Chegou aos 3 cards?** ✅ Sim (Turno 5).
- **Nº de turnos até o pedido:** 5.
- **Usuário “voltaria a usar”?** 🟡 **Talvez** — o conteúdo dos Turnos 2/3/5 é forte, mas os Turnos 1 e 4 devolveram templates de escopo (“Mude o escopo…”), que um usuário leigo não entende; o Turno 4 só foi respondido no preâmbulo do Turno 5.

## Achados técnicos (sem correção)

| # | Turno | Achado | Hipótese | Esperado nesta build? |
|---|---|---|---|---|
| 1 | 1 | “Mude o escopo para Tópico para navegar entre seções.” (52 chars, imediato) | “por onde começo?” classificado como navegação de seção; o C6 cobre “como/qual/o que”, não “por onde” | ❌ Não — router/UX, não persona |
| 2 | 4 | “Mude o escopo para Tópico para inserir no card.” (47 chars, mesmo segundo) | “aplicação”/“colocar no fim” casou intenção de inserir no card; o modelo não foi chamado | ❌ Não — router/UX; a pergunta ficou sem resposta no turno |
| 3 | 5 | Hint C8 não disparou em “me dá o texto pronto”; orientou 1 parágrafo antes dos cards | O hint cobre “gera/escreve/entrega”, não “me dá o texto pronto” | 🟡 Esperado em parte; os cards vieram |
| 4 | 1/4 | “Contexto: Introdução” no rodapé, mesmo com o header “Discurso inteiro” | O contexto da seção em foco continua no prompt no modo whole | ❌ UX/escopo; confunde o usuário |
| 5 | 5 | Cards são texto de fala; as cápsulas (ideia central/texto-chave/âncora/transição) não vieram explícitas | O pedido foi “texto pronto pra colar nos cards”; o modelo entregou a fala, não o metadado | 🟡 Parcial; a Tribuna precisaria das cápsulas |
| 6 | 5 | “Esta nota não tem publicações vinculadas”, mas o w25.07 foi usado via citações do texto | O `citedSources` usa a nota, não o vínculo | ✅ Funciona; o aviso é UX |

## Evidências

- [x] `sim_t1.png` — Turno 1 (template)
- [x] `sim_t2.png` — Turno 2 (corpo, fim)
- [x] `sim_t3.png` / `sim_t3b.png` — Turno 3 (posição)
- [x] `sim_t5c.png` — Turno 5, card CONCLUSÃO + “Usar no card”
- [x] Textos brutos extraídos de `chat_messages` (Turnos 1–5) — `/tmp/opencode/ab/sim_respostas.txt`
- [ ] Vídeo (não houve)

## Respostas às 6 perguntas

1. **Levou do zero aos 3 cards?** Sim, com atrito: Turnos 1 e 4 devolveram templates de escopo.
2. **Usou o `w25.07 22 §§ 8-9` de verdade?** Sim — conteúdo real nos Turnos 2, 3 e 5; sem página inventada.
3. **Distinguiu conclusão de aplicação?** Não no Turno 4 (template); sim no preâmbulo do Turno 5 (“a aplicação vive dentro da conclusão”).
4. **Entregou 3 cards separados?** Sim — 3 `<texto-do-card>`, não 1.
5. **Falhas atribuíveis a quê?** (a) persona: não; (b) build: não (v2.7.4 tem C6/C8); (c) **router/templates de escopo** nos Turnos 1/4; (d) UX: contexto “Introdução” no modo whole, cápsulas não explícitas, hint C8 estreito.
6. **Usuário voltaria a usar?** Talvez — o conteúdo é bom; os templates de navegação quebram a confiança de um leigo.
