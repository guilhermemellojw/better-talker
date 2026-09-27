# F19-B.2 — Parser S-34 → estrutura (`S34Document`)

## 1. Modelo criado

`S34Document` (Android `data/s34/OutlineDocument.kt`, web `s34Parser.ts`):

```text
S34Document (id "s34-"+hash, symbol "S-34", title, objective?, headerLines[])
└── S34Section[] (id "sec-N", order 1-based, title, minutes?, content,
                  sourceLine, source "S34")
    ├── S34Subsection[] (id "sec-N-M", order local, content, sourceLine)
    └── S34Reference[] (type BIBLE|PUBLICATION, rawText,
                        normalizedReference, order global, sourceLine,
                        + pass-through BibleRef/DetectedRef no Android)
Índice global DERIVADO: sections → refs em ordem (nunca lista paralela).
```

Nomes adaptados para não colidir com o legado `OutlineSection`
(`data/util/OutlineParser.kt`), que continua intocado.

## 2. Regras de parsing

Linha a linha, com número 1-based preservado para provenance:

| Padrão | Ação |
|---|---|
| `S-34`/`S34`/`S 34` | marcador (não abre seção) |
| `Tema: X` | título (primeira ocorrência vence) |
| `Objetivo:` | abre bloco de objetivo |
| `1.`/`2)` + texto | abre seção (`order` = encontro, `id` = `sec-N`) |
| `a)`/`b)` + texto | subseção da seção aberta (órfã → `headerLines`) |
| `(N min)` no fim do título | `minutes`, título limpo |
| linha vazia | fecha bloco de objetivo; demais ignoradas |
| resto (com seção aberta) | corpo verbatim; (sem seção) `headerLines`/fallback de título |

Refs por linha via detectores existentes (`detectBible` + `detect`,
web `detectBibleVerseRefs` + `detectCitations`), ordem global crescente.

## 3. Como o objetivo é identificado

SOMENTE com rótulo `Objetivo:` + captura das linhas seguintes até linha
vazia ou cabeçalho de ponto. Sem rótulo → `objective = null` (nunca
inferido, nunca de posição, nunca via LLM).

## 4. Como os pontos são identificados

Linha numerada `^\s*\d{1,2}[.)]\s+\S`. `order` = índice de encontro
(1-based), não o número impresso nem score. Título = resto sem número e
sem `(N min)` final; vazio → `"Ponto N"`.

## 5. Como os subpontos são identificados

`^\s*[a-z]\)` + texto, sempre filhos da seção aberta — por construção,
um subponto nunca cai no ponto seguinte (testado). Órfão (antes de
qualquer ponto) vai para `headerLines`, sem dono inventado.

## 6. Como as referências são associadas

Cada linha de corpo/subseção é escaneada; achados viram `S34Reference`
com `sourceLine` da linha e `order` global. Bíblia carrega
`label|cap|vers`; publicação carrega `DetectedRef` + normalizado.
Refs de título/objetivo/header **não** são estruturadas (limitação
documentada — o índice global deriva só das seções).

## 7. Limitações

- Refs fora de seção não estruturadas (ver §6).
- Objetivo multi-parágrafo trunca no 1º parágrafo (conservador).
- Título fallback = primeira linha não-marcador (cru, sem semântica).
- `minutes` só de `(N min)` no fim do título.
- `detect()` é sensível a contexto (janelas-guia): acordo garantido
  por linha, não entre granularidades diferentes.
- Tabela bíblica web é porte manual da Android (manter em sync).
- Minutos/ordem impressa divergente do encontro: vale o encontro.

## 8. Exemplo da fixture

Entrada (sintética, `S34Fixture`): marcador + tema + objetivo + 3 pontos
temporizados + subideias a)/b) + João 17:17, Tiago 2:17, Hebreus 10:23 +
w24.01 §3, w24.02 §5, sem INTRODUÇÃO/CONCLUSÃO.

Saída: `title` "Como fortalecer a fé", `objective` completo,
`sec-1..3` em ordem com minutes [4,5,3], `sec-2-1/2`, 3 bíblias +
2 pubs nos donos certos, `headerLines` com a linha do marcador.

## 9. Decisões deliberadamente conservadoras

- Ambíguo → texto preservado, nunca estrutura inventada (falso
  positivo estrutural é pior que texto não-estruturado).
- Sem `introduction`/`conclusion` no modelo (pinado por teste de schema).
- Conteúdo guarda linhas de ref verbatim (round-trip > elegância).
- `headerLines` sem provenance fina (texto não-estruturado por definição).

## 10. O que fica para F19-B.3

Persistência do `S34Document` (tipos são primitivos/listas, prontos para
serializar); refs de objetivo/header; `generateForSection`-like sobre
seções; bloco "estrutura ordenada" no ContextPack; cláusula S-34 no
prompt; `inferredOratoryStructure` em camada separada (NUNCA no parser).
