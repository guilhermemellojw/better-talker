# F20-D — Roteamento natural do chat + OratorySession

> **S-34/Bíblia/publicações = O QUE FALAR · BE/TH = COMO APRESENTAR ·
> LLM = COMO FORMULAR · Usuário = QUANDO ACEITAR.**

## 1. Tipos de rota

```text
ORATORY            geração/iteração oratória (F20-A/B/C)
STRUCTURAL_QUERY   pergunta sobre a estrutura do S-34 (contexto, sem proposta)
PROPOSAL_REPLY     aceitar/rejeitar a proposta pendente (fluxo existente)
NOTHING_TO_REFINE  refinamento sem geração anterior
OUT_OF_SCOPE       pedido que criaria estrutura fora do S-34
GENERAL            chat livre de sempre
```

Implementação espelhada: `ChatRouter.kt` / `chatRouter.ts`. Puro,
determinístico e offline (§21) — nenhum LLM decide a rota.

## 2. Precedência (§5)

```text
1. réplica de proposta (aceitar/aplicar/rejeitar/descartar)
2. sessão oratória: iteração (herda modo + ponto) ou pedido explícito
3. phráse natural reescrita para pedido canônico
4. pergunta estrutural sobre o S-34
5. chat geral
```

Meta-linguagem ("o termo desenvolvimento", "o significado de X") **vence** o
roteamento oratório: o usuário fala sobre a palavra, não pede geração.

## 3. Comandos oratórios reconhecidos

| Entrada natural | Rota |
|---|---|
| "Crie uma introdução." / "Faça uma abertura." / "Como começo esse discurso?" | ORATORY · introduction · sec-1 |
| "Desenvolva o ponto 2." / "Me ajude a desenvolver esse ponto." | ORATORY · development · sec-2 |
| "Explique melhor o ponto 2." / "Pode explicar melhor o ponto 2?" | ORATORY · development · sec-2 |
| "Faça uma transição para o ponto 3." | ORATORY · transition · sec-3 |
| "Como passo para o próximo ponto?" | ORATORY · transition · ponto atual |
| "Faça uma conclusão." / "Como posso concluir?" | ORATORY · conclusion · sec-3 |

Reescrita canônica cobre as variações ("como posso começar" → "crie uma
introdução"), com o número do ponto preservado quando citado.

## 4. Perguntas estruturais (§18)

Objetivo, pontos, sequência, referências e "o que o S-34 diz sobre X" viram
`STRUCTURAL_QUERY` — o contexto estrutural responde, **nenhuma proposta é
criada**.

## 5. Iteração (§§12-13, 16)

"Melhore.", "Deixe mais natural.", "Encurte.", "Explique melhor.",
"Melhore isso." com sessão ativa → herda **modo + ponto** e vira `REPLACE`.
Sem sessão → `NOTHING_TO_REFINE` com mensagem amigável; nenhum alvo é
inventado.

## 6. Troca de modo / ponto (§§14-15)

"**Agora** desenvolva o ponto 2." substitui a herança (novo modo + ponto);
"Agora desenvolva o ponto 3." troca a seção e as referências acompanham.
O roteador nunca continua refinando a parte anterior sem pedido explícito.

## 7. Falsos positivos evitados (§§23, 41)

```text
"O que o S-34 diz sobre a introdução?"     → STRUCTURAL_QUERY
"Qual é a conclusão do esboço?"            → STRUCTURAL_QUERY
"Existe uma introdução nesse esboço?"      → STRUCTURAL_QUERY
"Explique o termo desenvolvimento."        → GENERAL (meta-linguagem)
"Como posso importar um arquivo?"          → GENERAL
"Qual é o objetivo desse discurso?"        → STRUCTURAL_QUERY
"Me explique esse assunto."                → GENERAL
```

Regra: pergunta factual/estrutural não é comando; "como posso começar/
concluir/passar" só é oratório em contexto de discurso.

## 8. Ausência de contexto (§17, 42)

- "Melhore." sem proposta → `NOTHING_TO_REFINE`.
- Oratória sem S-34 → rota ORATORY e a geração devolve
  `Blocked.NoStructure` (mensagem honesta) — nunca estrutura inventada.
- Transição no último ponto → `NoNextSection`.
- Referência/objetivo ausente → `null`, nunca inventado.

## 9. Web × Android

Mesmo contrato, mesma precedência, mesmos casos de teste (22 web + 25
Android), provider fake nos dois. Android integra no `send()` **antes** do
chat livre; web integra no `sendChatMessage` **antes** do provider.

## 10. Diagnóstico (§52)

`describe(route)` / `describeChatRoute(route)` produz
`route=ORATORY mode=development section=sec-2 action=INSERT inherited=false
reason=...` — sem chave, sem prompt e sem texto privado.

## 11. Sem score (§35)

Nenhuma rota carrega `score`/`confidence`; a classificação é por regras
explícitas e ordem fixa. Testado por inspeção de campos.

## 12. Limitações

- O roteador decide o **pipeline**, nunca a veracidade do conteúdo (§36) —
  isso segue em F6/Content Guard.
- Réplica de proposta é reconhecida apenas em formas inequívocas
  ("aceitar"/"rejeitar"); "sim"/"não" continuam nos fluxos de confirmação
  existentes (não interceptados pelo roteador).
- Sem LLM real disponível no ambiente: as execuções foram determinísticas
  (`LLM real = NOT_EXECUTED`); a fidelidade do modelo não foi observada.
- Validação física indisponível (sem dispositivo conectado).
- A sessão oratória é volátil por design: morre com o processo, sem
  persistência nova (§53).
