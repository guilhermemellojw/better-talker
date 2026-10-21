# A/B final — Chat do Copilot vs DeepSeek Web (2026-10-21)

Sequência completa: merge do v2.4.7/v2.4.8 (orçamento × reasoning) → discovery
do parser `.jwpub` → fix do RAG → validação.

Documentos relacionados:
- `2026-10-21-chat-ab.md` — a matriz que achou a causa do chat vazio
- `2026-10-21-jwpub-discovery.md` — o mapa do parser e a causa do § por título

---

## 1. O que mudou desde o A/B anterior

| Camada | Antes | Depois |
|---|---|---|
| Provider | `reasoning_effort=low` + `max_tokens=1000` → 37 chars ou vazio | `none` + 2048 (4096 no "Profundo") → resposta inteira |
| Índice do acervo | parágrafo do JWPUB indexado pelo **título** da pergunta do esboço | indexado pelo **número real** (`data-pnum`) → o corpo do § entra com `paragraph` |
| Citação por página/§ | casava por string da ref (`§605`) → falso aviso "sem fonte" | casa por `page`/`paragraph` → texto literal |
| Rótulo da fonte | "w25.07 Sua resposta §424" | "w25.07 Sua resposta pág. 22 §8" |

## 2. Comparativo lado a lado (mesma tarefa: desenvolver a introdução do discurso)

| Aspecto | App (v2.5.0) | Web **sem** ferramentas | Web **com** ferramentas |
|---|---|---|---|
| Texto literal do §8 | ✅ do acervo local ("Baruque, o secretário do profeta Jeremias…") | ❌ pediu o texto ao usuário | ✅ buscou na web |
| Fonte apresentada | ✅ pág. 22 §§ 8-9 (sem ordinal) | — | ✅ com citação da web |
| Falta de suporte / recusa | não | sim (pediu as fontes) | não |
| Forma da resposta | orienta + riscos + sugestão marcada + **pede confirmação** | idem | escreve o texto direto |
| "Jeová" (nunca "Deus") | ✅ | ✅ | ✅ |
| Latência (1ª resposta) | ~3–10 s | ~8 s (thinking off) | ~20 s (com busca) |
| Usa dado externo | não | não | sim (estatística de terceiros) |

**Leitura:** o app fechou o gap do Web **sem** ferramentas — e ficou **melhor**
nesse eixo, porque tem o texto real do parágrafo em vez de pedir que o usuário
cole. O que ainda sobra (Web com Search) é a vantagem de **ferramenta**: buscar
estatística de terceiros na web. Isso não é prompt — é o `PublicationCatalog`/
web search, o próximo projeto real.

## 3. Evidências de campo (A34, nota real "Ter esperança…")

1. `w25.07 22 §§ 8-9` → o app responde com o **texto do parágrafo**:
   "o parágrafo 8 da página 22 está no dossiê… Baruque tinha uma designação
   difícil… 'Pare de procurar essas coisas.'" (1 316 chars, ~9 s).
2. A pergunta sobre o §8 não dispara mais "⚠️ citação sem fonte no acervo" —
   o card de referências mostra "✅ A Sentinela 7/2025 • pág. 22 §§ 8-9".
3. Índice reindexado: 885 passagens, 666 com `paragraph` real (antes 87).
4. `byPageParagraph(att, 22, 8, 9)` devolve 22 linhas / 1 553 chars de texto
   literal (antes: só o título).

## 4. Conclusão

**Gap fechado: SIM** (parcial no eixo "web search", por desenho).

O "chat do Copilot não é tão bom quanto o Web" tinha **três causas
independentes**, todas medidas:

1. **Provider** (37 chars / vazio) — corrigido no v2.4.7/v2.4.8.
2. **RAG de publicações** (título em vez do texto do parágrafo) — corrigido
   aqui; o app passa a citar o parágrafo literal, como o Web com busca.
3. **Ferramenta** (Web Search/DeepThink) — o Web buscou a w25.07 na internet e
   Statistics de terceiros; o app é offline por design. Não replicável sem
   tool-calling → projeto à parte.

E uma quarta, de **tom/prompt**: o Web usa um system prompt único e curto; o
app empilha persona + regras + dossiê + cauda. O A/B mostrou ganho pequeno
(~2×) em relação ao provider — por isso o app manteve a arquitetura e só
acrescentou a regra de preservação de termos ("Jeová", "Escrituras").

## 5. Estado dos branches

- `fix/chat-quality` → mergeado (v2.4.7) + `fix/chat-deep-budget` (v2.4.8)
- `fix/jwpub-parser` → este trabalho (v2.5.0)
- `exp/chat-diagnostics` → flags de experimento (não mergeado; superadas)
- `feat/chat-export` → export do chat (ainda não mergeado)
