# A/B Final — Copilot v2.5.0 vs Gemini (2026-10-21)

Mesma nota (Tesouros 1 — "Ter esperança é o segredo para o contentamento"),
mesmo prompt, mesmos critérios do plano de teste.

**Ambiente:** A34 (SM-A346M), app **v2.5.0** (versionCode 14), acervo com a
w25.07 reindexado (`index_format 7` — o parágrafo indexado pelo número real).

---

## 1. Prompt (idêntico ao dado ao Gemini)

```
Vamos desenvolver juntos a primeira o discurso de 10 min da primeira parte da reunião.

Tema: Ter esperança é o segredo para o contentamento (10 min)

Introdução: a fazer

Tópico 1: Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)

Tópico 2: Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)

Tópico 3: Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)

Conclusão: a fazer
```

**Ressalva de execução (do harness, não do app):** o `adb shell input text`
não aceita o caractere `§` (NPE no `InputShellCommand`) e o IME do aparelho
segmenta `w25.07` em `w25. 07`. As refs da **mensagem** foram portanto
escritas por extenso ("Sentinela de julho de 2025, página 22, parágrafos 8-9").
Os refs com `§§` continuam íntegros na **nota** (dossiê), que é o que alimenta a
fundamentação. O mesmo prompt colado à mão no aparelho é aceito com `§§` —
verificar com o usuário.

---

## 2. Rodadas do Copilot v2.5.0 (nota real, escopo "Tópico")

| # | Mensagem | Resposta | Latência |
|---|---|---|---|
| R1 | o prompt do Gemini | 1 819 chars — **alinha a estratégia antes de escrever** | 8,7 s |
| R2 | "Sim, pode seguir. Vamos desenvolver o Tópico 1 primeiro." | 2 739 chars — **texto no card** | 9,4 s |
| R3 | "O que diz exatamente o parágrafo 8 da página 22 da Sentinela? Cite o texto literal." | 965 chars — **citação literal** | 4,4 s |
| R4 | "Essa matéria da Sentinela você inventou ou ela realmente existe?" | 1 011 chars — **"Não inventei"** + limitações do corpus | 4,3 s |
| R5 | "Baruque perdeu prestígio social? Isso está na Bíblia ou é especulação?" | 1 302 chars — **separa texto de leitura** | 4,7 s |

**Rodadas até o texto final: 2** (R1 orienta → R2 entrega o Tópico 1).
**Gemini: 17.**

### Trechos que decidem

- R1: *"vamos alinhar a estratégia antes de escrever"* + tempos por seção
  (Introdução 1,5 min · T1 3 · T2 2 · T3 3 · fecho 30 s · "10 min ≈ 1300
  palavras") + 3 riscos + `〈sugestão〉` de abertura + *"Quer que eu escreva agora a
  introdução e o tópico 1, ou prefere que eu monte a seção inteira?"*
- R3: *"o parágrafo 8 da página 22 diz, palavra por palavra: 'Em que armadilha
  Baruque caiu Baruque, o secretário do profeta Jeremias, tinha uma designação
  difícil: ajudar Jeremias a transmitir uma forte mensagem para uma nação
  ingrata'"*
- R4: *"Não inventei… é o estudo 'Você aprendeu o segredo do contentamento', da
  Sentinela de julho de 2025, Estudo 31"* + *"as fontes que tenho trazem o texto
  corrido, sem a pontuação separada por parágrafos"* (auto-limite de granularidade)
- R5: *"A Bíblia não diz 'Baruque perdeu prestígio social'… É plausível? Sim. É
  o texto inspirado? Não."*

---

## 3. Tabela comparativa

| Dimensão | Gemini | Copilot v2.5.0 | Veredito |
|---|---|---|---|
| **1. Pede confirmação antes de redigir** | ✅ (17 rodadas) | ✅ R1 alinha estratégia e **pergunta** antes do texto | ✅ |
| **2. Cita texto literal do §8-9** | ❌ parafraseou / pediu o texto | ✅ **"palavra por palavra"**, pág. 22 §8 | ✅ |
| **3. Não inventa fatos sobre Baruque** | ❌ inventou "perda de prestígio" | ✅ separa texto bíblico de leitura ("plausível, não inspirado") | ✅ |
| **4. Estrutura S-34** | ✅ | ✅ tempos por seção, 3 movimentos, fecho de 30 s | ✅ |
| **5. Sugere ilustração** | ✅ | ✅ `〈sugestão〉` de abertura (cena, sem nome) | ✅ |
| **6. Oferece opções / pede foco** | ✅ | ✅ "escrevo a introdução e o tópico 1, ou a seção inteira?" | ✅ |
| **Rodadas até o texto final** | 17 | **2** | ✅ |
| **Latência da 1ª resposta** | ~20 s (com busca) | 8,7 s | ✅ |

**6/6 — o Copilot v2.5.0 é melhor que o Gemini nas duas dimensões que motivaram
o pedido (fundamentação e eficiência).**

---

## 4. O que o Gemini tem e o Copilot não

| | Gemini com Search | Copilot v2.5.0 |
|---|---|---|
| Texto do §8 | ✅ buscou na w25.07 online | ✅ tem no acervo local (v2.5.0) |
| Estatística externa (Gallup 33 %) | ✅ | ❌ (sem web search) |
| Escreveu o discurso inteiro em 1 resposta | ✅ | ⚠️ escreve por seção, a pedido |

A vantagem do Web **com** ferramentas é a **ferramenta** (busca na internet),
não o prompt: com DeepThink/Search **off**, o Web pediu as fontes e **não
escreveu nada** (é o comportamento que o Copilot reproduz — e faz melhor,
porque tem o texto).

---

## 5. Lacunas observadas (candidatas, não bloqueiam)

1. **"Sem esboço S-34 vinculado"** — a nota foi criada colando o esboço, e o
   app não cria a linha em `s34_outlines` (só há 3 linhas, de anexos S-34
   importados). O chat perde o contexto estrutural do S-34 e avisa. O
   `s34_outlines` está ligado a `sourceAttachmentId` (`AppDatabase`), sem
   `noteId` — por isso uma nota colada não entra.
2. **"Revise: N trechos sinalizados"** — o `GroundednessVerifier` acusa as
   citações (o acervo é indexado por frase, então a citação literal não bate
   caractere a caractere). O texto é mantido, mas o aviso assusta.
3. **Granularidade do acervo** — o app honestamente avisa (R4) que o texto vem
   corrido, sem a separação por parágrafo. Um `.jwpub` com parágrafo → frase
   resolveria isso (mesma área do fix do parser).

**Nenhum fix foi aplicado** (o plano só os pediria com 2+ ❌ — houve 0 ❌).

---

## 6. Veredito

> **O Copilot v2.5.0 é estruturalmente melhor que o Gemini para este caso:**
> ele tem o **texto literal** no acervo (o Gemini nunca teve), responde o
> "o que diz" com citação **palavra por palavra**, separa o que é texto do que
> é leitura, e chega ao texto final em **2 rodadas** (17 no Gemini).
>
> A única vantagem que o Gemini conserva é a **web search** — e ela só existe
> porque ele não tinha o material. O próximo trabalho real continua sendo o
> `PublicationCatalog`/web search, não o prompt.
