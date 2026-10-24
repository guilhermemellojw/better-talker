# Fix parser — apostila inline (v2.7.5, P0)

**Build base:** v2.7.4 (`cbe8e13`) · **Branch:** `feat/copilot-citations`
**Data:** 2026-10-23

## Fase 1 — diagnóstico (confirmado)

| # | Resposta |
|---|---|
| 1 | `PastedOutlineAnalyzer.candidates` (`OutlineParser.kt:437`) quebra linha/frase em `Candidate` (título); `level` só pelo recuo. Não distingue frase-ponto de título. |
| 2 | `createNoteFromPasted` (`CopilotViewModel.kt:4576-4585`) faz `DraftSection(title=c.text, body=bodies[i])`; `bodies` (`OutlineParser.kt:488`) só coleta `cands[j].level > c.level`. As 3 frases são nível 0 → corpo vazio. |
| 3 | `S34Parser` extrai `min=10` (`S34Parser.kt:57`, `SECTION_MIN_RE`); o paste ignora; `_draftTotal.value = null` (~4592). |
| 4 | `S34Detector.isS34` (`S34Detector.kt:82`) = false; **não é chamado** (doc: “não é chamado por ninguém ainda”). O tipo é o **toggle** da planilha. Correto manter. |
| 5 | Contrato do `PastedOutlineAnalyzer`: `OutlineTest.pastedPreservesRefsAndShortTopics` (linha 219) espera **linhas/frases como candidatos**; `bodies` só para subníveis. O formato A está fora do contrato. |

**Confirmações:** `S34Parser` e `PastedOutlineAnalyzer` são caminhos independentes; a colagem
inline nunca preencheu body (só subníveis); o toggle é o único classificador; a nota
“Simulação TESOUROS” pode ficar como baseline.

## Fase 2 — fix aplicado

| Commit | Mudança |
|---|---|
| `21c109d` | `PastedOutlineAnalyzer.inlineApostila()`: `Tema (N min)` + N linhas com refs → **1 seção** (título limpo) com body = N linhas; guards: S-34 oficial (`Tema:`/numerado), Formato B (seção temporizada/corpo recuado), linha sem ref. `createNoteFromPasted` usa antes de `candidates/bodies`. |
| `d27767b` | `InlineApostila.minutes` lê o `(N min)`; `DraftSection.minutes` + `_draftTotal` recebem; título sem o marcador. |
| `702c0c4` | `docs/parser-formatos-colagem.md` (A/B/C). |

**Não tocado:** `S34Detector`, `S34Parser`, persona, hints, router, `ORATORY_SOURCES_BLOCK`,
`CHAT_TAIL_INSTRUCTIONS`, UI de edição.

## Fase 3 — testes

| Teste | Resultado |
|---|---|
| `PastedInlineApostilaTest` (7) | ✅ 7/7 — 1 seção/3 pontos/body, 10 min, 3 refs `w25.07` exatas, Jer. 45:3/45:5, 5 pontos, 1 ponto, sem tempo, B e C sem regressão |
| Suíte completa | ✅ **2.211 / 0 falhas** |

## Validação no tablet — **achado que bloqueia a sanidade**

O fluxo **Home → Inserir material → Colar material** usa **outro caminho**:
`HomeViewModel.createNoteFromPaste` + `PastePreviewSheet`/`planPaste`, **não** o
`CopilotViewModel.createNoteFromPasted` corrigido. No tablet, com o texto da área de
transferência, o preview mostrou:

```
Texto livre
Sem V&M/S-34 detectado
Cards: 1 card (Corpo)
Destino: Nova nota
```

Ou seja: o “nota nova” da Home cria **1 card (Corpo)**, não “1 seção com título do tema +
3 pontos no body + 10 min”. **O fix atual cobre o chat** (`+ → Colar material`), que é o
caminho do `createNoteFromPasted`; a Home é um segundo parser/caminho que continua fora
do Formato A.

O teste no chat foi interrompido pelo harness: o campo do sheet auto-preencheu a **área
de transferência do tablet** com outro texto (“consegue acessar as referências agora?”) e
o `adb input text` não injeta `§`/acentos (NPE no `KeyCharacterMap`). O texto ASCII é
digitável, mas a validação visual do sheet/draft não foi concluída.

## Decisão necessária

1. **Testar o chat** (`+ → Colar material`, caminho corrigido) com o texto ASCII no tablet —
   confirma 1 seção/body/10 min/word count; o `§`/acentos ficam cobertos pelo unit test exato.
2. **Estender o Formato A ao `createNoteFromPaste` da Home** (o “nota nova” da sanidade) —
   é outro caminho; o prompt do fix focava `createNoteFromPasted` (chat). Fora do escopo
   atual; reportar antes de ampliar.
3. **Ambos** — recomendado para o “colar em nota nova” funcionar como o usuário espera.

## Estado

- **Nenhum código de produção fora do escopo**; commits 1–3 na branch; **sem tag** (v2.7.5
  só após a re-simulação).
- Nota “Apostila TESTE” **não criada** (sheet fechado); “Simulação TESOUROS” preservada.
- `git status`: só relatórios em `docs/reports/` (não commitados).
- Re-simulação TESOUROS: **não rodada** — a sanidade no tablet ainda não fechou.
