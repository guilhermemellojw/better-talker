# Discovery do parser `.jwpub` — 2026-10-21

Objetivo: entender por que `w25.07 22 §§ 8-9` devolvia o **título** do
parágrafo ("Em que armadilha Baruque caiu") em vez do **texto literal**, e por
que as FONTES do dossiê apareciam como `§424` / `§536`.

Tudo aqui é read-only sobre o `.jwpub` real do A34 (`w_T_202507.jwpub`, 4 MB).

---

## 1.1. Mapa do caminho (arquivo:linha)

| # | Pergunta | Resposta (evidência) |
|---|---|---|
| 1 | Como o texto é extraído do `.jwpub`? | `JwpubExtractor.readDb` (`data/util/JwpubExtractor.kt:167-222`): ZIP externo → `contents` → `.db` interno → tabela `Document` → `Content` (blob) → AES-128-CBC + zlib (`decryptInflate`, `:313`) → HTML. O texto é escrito com `# Título` por documento e `§N` nos parágrafos (`markParagraphs`, `:232`) |
| 2 | O que vai em `PassageEntity.text`? | A **frase** do texto extraído (`PassageBuilder.kt:42-77`, `maxTextLength=500`). `paragraph` = o § do marcador `§N`; `page` = página estimada (`JwpubExtractor.paragraphPages`, `:271`); `ref` = `buildRef(symbol, section, ord+1)` (`TextNorm.kt:192`) — **posição sequencial**, não o § |
| 3 | Como o `w25.07 22 §§ 8-9` é resolvido? | `RoomReferenceResolver.resolvePublication` (`data/planning/RoomReferenceResolver.kt:65`) → sem artigo/capítulo → `resolveByPageParagraph` (`:130-145`) → `passageDao.byPageParagraph(attachmentId, page, start, end)` (`AppDatabase.kt:238`) → devolve **as linhas com `page` e `paragraph` batendo** |
| 4 | Por que vinha o título? | A regra de marcação `NUMBERED_P_RE` (`JwpubExtractor.kt:239`) casa `<p …><strong>8.</strong>` — que no JWPUB é a **pergunta do esboço** (`<p class="qu">`). O **corpo** do parágrafo vem em `<p id="p16"><span class="parNum" data-pnum="8"><strong><sup>8</sup></strong></span> Baruque, o secretário…` — que a regra **não** reconhecia → o corpo ficava sem `§` |
| 5 | Por que `§424` nas FONTES? | `ChatPromptBuilder.serializePack` (`data/copilot/ChatPromptBuilder.kt:278`) imprime `EvidenceSource.reference` = `p.ref` = `buildRef(symbol, section, ord+1)` → o "§424" é o **ordinal da passagem** (posição na sequência), não o parágrafo da revista |
| 6 | `passage.index` é § ou posição? | O schema não tem "index": há `paragraph` (o § real, quando o marcador existe), `page` (estimada) e `ord` (posição sequencial). Quem usava `ord+1` na `ref` era o `buildRef` |

### Causal chain (medida no arquivo real)

```
HTML do JWPUB:
  <p class="qu"><strong>8.</strong> Em que armadilha Baruque caiu?</p>   ← PERGUNTA
  <label>Sua resposta</label>
  <p id="p16"><span class="parNum" data-pnum="8"><strong><sup>8</sup></strong></span>
      Baruque, o secretário do profeta Jeremias, tinha uma designação difícil…</p>  ← CORPO

→ markParagraphs marcava "§8" na PERGUNTA (corpo ficava sem §)
→ linha "Sua resposta" (label do JWPUB) caía no heurístico `isShortTitle`
   (TextNorm.kt:367) → section="Sua resposta", paragraph=null
→ o CORPO entrava no índice com paragraph = NULL
→ byPageParagraph(22, 8, 9) devia SÓ o título
→ e a "checagem de conteúdo" (RefDetector.resolveContent) comparava só a
   STRING da ref ("§605"), nunca page/paragraph → falso aviso
   "⚠️ citação sem fonte no acervo"
```

Números do índice real (A34, antes do fix): 885 passagens, **87** com
`paragraph`, 798 sem. Depois do fix: **666** com `paragraph`.

---

## 1.2. Schema real do `.jwpub` (extraído do arquivo)

**Document** (o artigo):
`DocumentId, PublicationId, MepsDocumentId, MepsLanguageIndex, Class, Type,
SectionNumber, ChapterNumber, Title, TitleRich, TocTitle, TocTitleRich,
ContextTitle, FeatureTitle, Subtitle, Content, FirstFootnoteId, …,
ParagraphCount, HasMediaLinks, HasLinks, FirstPageNumber, LastPageNumber,
ContentLength, PreferredPresentation, …`

**DocumentParagraph** (a numeração):
`DocumentParagraphId, DocumentId, ParagraphIndex, ParagraphNumberLabel,
BeginPosition, EndPosition` — **sem coluna de texto**.

| Fato | Valor real |
|---|---|
| Cartão (chave) | `5_w25_2025_20250700` (MepsLanguageIndex=5, Symbol=`w25`, Year=2025, IssueTagNumber=20250700) |
| Artigo | `DocumentId=5` — "Você 'aprendeu o segredo' do contentamento?" — pág. 20→25, `ContentLength=30725`, `ParagraphCount=79` |
| §8 | `ParagraphIndex=16`, **`ParagraphNumberLabel=8`**, Begin=12479, End=13336 |
| §9 | `ParagraphIndex=17`, `ParagraphNumberLabel=9` |
| §2 | `ParagraphIndex=8`, `ParagraphNumberLabel=2` |
| §13/§14 | `ParagraphIndex=22/23`, `ParagraphNumberLabel=13/14` |

**Achado crítico:** `BeginPosition/EndPosition` **não** alinhham com o HTML
decifrado (delta de +6 a −581 entre label e posição real). Ou seja: **não dá
para fatiar o parágrafo por offset**. O que alinha é o atributo
`data-pnum` do `<span class="parNum">` — o mesmo `ParagraphNumberLabel`.

Página estimada confere: `firstPage + (begin/contentLength × span)` → §8 →
20 + (12479/30725 × 6) = **22** ✔ (a página real do §8), §2 → **20** ✔,
§13 → **24** ✔.

---

## 1.3. Comparação Web × App (mesma citação)

| Item | Web (com Search) | Web (sem Search) | App (antes) | App (depois) |
|---|---|---|---|---|
| Texto do §8 | **literal** ("Baruque, o secretário do profeta Jeremias…") | não tinha; pediu o texto | **título** ("Em que armadilha Baruque caiu") | **literal** ✔ |
| Fonte mostrada | "pág. 22 §§ 8-9" | — | "§424" (ordinal) | "pág. 22 §8" ✔ |
| Aviso de falta de fonte | — | — | ⚠️ "citação sem fonte no acervo" | ✅ resolvido |

---

## 1.4. Fix conceitual (o que foi implementado)

| Onde | O quê |
|---|---|
| `JwpubExtractor.markParagraphs` | nova regra `PAR_NUM_RE`: `<span … data-pnum="N">` → marcador `§N` (o número **real**). A regra antiga (`<p><strong>N.</strong>`) continua como fallback para publicações sem `data-pnum` |
| `TextNorm.splitWithSectionsAndParagraphs` | a linha que **traz** o marcador `§N` não perde o parágrafo no heurístico de "título curto" (o label "Sua resposta" do JWPUB zerava o §) |
| `RefDetector.resolveContent` | citação com página/parágrafo casa pelo par **estruturado** (`page`/`paragraph`), não só pela string da ref |
| `CopilotRepository.contentCandidatesFor` | citação com página/parágrafo usa `byPageParagraph` (não `forAttachment`, que trazia só as 500 primeiras) |
| `ChatPromptBuilder.sourceLabel` | rótulo da fonte = "w25.07 Sua resposta pág. 22 §8" (o `§ordinal` some) |
| `MainActivity` | `needsIndexFormat(6)` → `needsIndexFormat(7)`: reindexa o acervo uma vez no boot |

**Esforço:** M (2 arquivos de extração/índice + 2 de resolução + 1 de prompt).
**Riscos:** (a) reindex invalida o índice atual (1 reindex no boot, ~40 s);
(b) outras edições do JWPUB podem ter estrutura diferente — por isso a regra
antiga foi mantida como fallback; (c) o `section` dos trechos muda (o label
"Sua resposta" deixa de sequestrar a seção) — isso é cosmético no dossiê.
**Sem migração de schema** (nenhuma coluna nova) e **sem tocar** em
`SyncWorker`, `PastePreviewSheet`, `GroundednessVerifier`.
