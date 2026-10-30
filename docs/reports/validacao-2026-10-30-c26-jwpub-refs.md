# C2.6 — unidade de revista, fallback por contexto e JWPUB confiável (2026-10-30)

**Branch:** `fix/c2.6-magazine-unit-context` (a partir da `main` 3.0.0/31)
**Versão:** não bumpada nesta branch
**Escopo:** cauda de página/§, resolução por página/§/contexto, JWPUB
(schema/erro real) e consolidação visual de “Fontes e apoio”.

## 1. Cauda de página/§ nas revistas nomeadas

`MagazineTail` é compartilhado por `AWAKE_RE`, `AWAKE_NUM_RE`,
`WATCHTOWER_RE`, `WATCH_MONTH_RE` e `SENTINELA_DATE_RE`:

| Linha | Antes | Agora |
|---|---|---|
| `Despertai! 08/13 pág. 6` | `g\|2013\|8` sem página | `g\|2013\|8`, page=6 |
| `Despertai! 08/13 pág. 8 parág. 1-2` | sem página/§ | page=8, §1-2 |
| `Sentinela número 3 de 2019 pág. 6-7` | `wp\|2019\|3` sem página | page=6, pageEnd=7 |
| `Sentinela de julho de 2025 pág. 22 § 8-9` | sem página/§ | page=22, §8-9 |

## 2. Resolvedor

`RoomReferenceResolver` agora:

1. página + § indexados → `RESOLVED`;
2. página sem § → `PARTIAL` com o texto real da página (`byPage`);
3. § sem página → `PARTIAL` via `byParagraphRange`;
4. página + § quando o índice antigo **não tem parágrafo**: usa a página como
   `PARTIAL` (g 2013/wp 2019);
5. sem página/§ indexados → contexto do esboço (`context`) acima do
   threshold → `PARTIAL` com trecho real;
6. contexto fraco → `UNRESOLVED`, sem trecho aleatório.

`RefContentCheck`/`NoteSources` propagam `PARTIAL` e o motivo
(“página localizada” / “trecho localizado pelo contexto”). O prompt/checklist
mostram o texto encontrado e o motivo, nunca “não existe”.

## 3. JWPUB confiável

- `ContentLength` era coluna obrigatória; o schema 9 do `lmd_T.jwpub` não a
  tem. A query passou a usar `length(Content)`.
- `ParagraphNumberLabel` não existe no schema antigo do `g_T_201308`; o
  extrator usa `ParagraphIndex` como fallback e não falha.
- Páginas impressas do HTML (`<span class="pageNum" data-no="6">`) viram
  `=== PAGE: 6 ===`; quando existem, não usa estimativa por posição.
- Cache de extração usa UUID (o `reindexAll` em paralelo colidia no mesmo
  arquivo millis → CRC/ENOENT).
- Mensagens de falha específicas: “JWPUB inválido/corrompido… tente
  reindexar; se persistir, baixe o EPUB”. Removida a frase falsa “JWPUB ainda
  não suportado”.
- `JwMediaApi` continua JWPUB > EPUB > PDF; o toast de download direto mostra
  o formato escolhido (`Baixando JWPUB…`).
- Card de fonte com `INDEX_FAILED` de JWPUB oferece **Reindexar** e
  **Baixar EPUB**.

## 4. “Fontes e apoio”

- Removido o card “Fontes deste ponto” acima do composer.
- `ProvenanceDisclosure` recebe `focusSources` e `onReanalyze`; o conteúdo do
  ponto entra no mesmo disclosure, colapsado por padrão, com um único
  controle e `AnimatedVisibility` respeitando reduced motion.
- Cabeçalho: resumo puro `focusSourcesSummary(counts)`.
- Seção “Deste ponto” com contagens, texto encontrado, “Verificar”,
  “Ver todas”, “Texto usado” e “Reanalisar”.
- “Do turno” lista a proveniência existente.
- `SourcesNeededCard` continua separado e acionável.

## 5. Testes

- **2.754 testes JVM / 0 falhas**
- `MagazineTailTest` (5), `ContextMatcherTest` (4),
  `FocusSourcesSummaryTest` (3), `RoomReferenceResolverTest` (30, +5),
  `JwpubTest` (pageNum) e ajustes de regressão.
- `lintRelease` verde.

## 6. A34 (JWPUBs reais)

Acervo de teste com `lmd_T.jwpub` (9,7 MB), `g_T_201308.jwpub` (2,5 MB) e
`wp_T_201909.jwpub` (3,9 MB), todos baixados do jw.org:

| Anexo | Resultado |
|---|---|
| `lmd_T.jwpub` | **ready**, 540 trechos; §4 resolve (o schema 9 era o bug do `ContentLength`) |
| `g_T_201308.jwpub` | **ready**, 451 trechos; páginas 6/8 do HTML |
| `wp_T_201909.jwpub` | **ready**, 399 trechos; páginas 6-7 |

Fontes do tópico exibiram:
- `g 8/13 pág. 6` — “página localizada” + arquivo JWPUB;
- `g 8/13 pág. 8 § 1` — “No acervo” + arquivo JWPUB;
- `wp19.03 pág. 6-7` — “página localizada” + arquivo JWPUB;
- `lmd lição 7 § 4` — “No acervo” + arquivo JWPUB.

O A34 foi restaurado com o APK release 3.0.0 publicado.

## Limites

- O fallback por contexto depende da frase do esboço chegar ao
  `PublicationRef` (S-34/OutlineConverter e seções); refs soltas sem contexto
  continuam sem trecho e pedem confirmação.
- EPUB/PDF sem página/§ usam contexto; PDF mostra “PDF no acervo (página/§ não
  indexados)”.
- A versão publicada ainda é 3.0.0/31; esta branch não fez bump/tag.
