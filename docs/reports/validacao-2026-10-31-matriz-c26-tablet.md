# Matriz C2.6/C2.8 no tablet — 391 refs / 238 edições (2026-10-31)

**Branches:** spike `spike/matriz-c26-tablet` @ `3a57416` (matcher
`2683887`, C2.8 `2e7acd7`); release `main` @ `40ceb95` (3.0.4/35, matcher);
C2.8 `fix/c28-book-prompt` @ `73f3bd6`.
**Tablet:** Samsung Galaxy Tab 25040RP0AL · Android 16 (SDK 36) · arm64 ·
1600×2560 · 172 GB livres · Wi-Fi `Guilherme_5G`
**APK debug:** `com.bettertalker.app.debug` (3.0.3-debug, versionCode 34),
SHA-256 `0d6ed62668b0127441228a81de0cc48acc9b49a2ac3f36c8595ff121628a65e2`.
**APK release 3.0.4/35:** SHA-256
`8457822fac8e6d567d5e4373ca6978bbdb6d09618fd64e6d6831c0b9e117e228`,
certificado release SHA-256
`ad1b0a302256a66aa083d76b767291eb2e3c8c4b07f07ae741dd99716233bba4`.
**A34/#192:** não conectado, não tocado.
**Testes JVM:** **2.776 / 0** no C2.8; **2.771 / 0** no main (matcher-only).

## 1. Resultado final (C2.8 — prompt)

| Métrica | Resultado |
|---|---|
| Refs da matriz | **391** |
| Refs de CONTEÚDO (sem be/th) | **375** |
| **`prompt_coverage` (conteúdo)** | **347/375 = 92,5%** (meta ≥90%) |
| `cited_ok` (todos os refs) | 347/391 = 88,7% |
| `RESOLVED` | **56** |
| `PARTIAL` | **291** |
| `TEXT_NOT_FOUND` | **8** |
| `EDITION_MISSING` | **20** |
| `TRAINING` (`be`/`th`) | **16** |
| `WRONG_EDITION` | **0** |
| Edições prontas/indexadas | **216/238** |
| Indisponíveis na API | **22** |
| Falhas de download/indexação | **0** |
| Acervo baixado | **2,40 GB** |
| `prompt_chars` (conteúdo) | **986.253** |

O CSV final está em
[`matriz-c26-2026-10-31-c28-tablet.csv`](matriz-c26-2026-10-31-c28-tablet.csv);
o acervo em
[`matriz-c26-2026-10-31-c28-acervo.csv`](matriz-c26-2026-10-31-c28-acervo.csv);
o resumo bruto em
[`matriz-c26-2026-10-31-c28-summary.txt`](matriz-c26-2026-10-31-c28-summary.txt).
O CSV C2.6/C2.7 anterior (`matriz-c26-2026-10-31-tablet.csv`) fica como
histórico da métrica de UI (91,3%).

**Por que “todos os refs” é 88,7% e não 92,5%:** os 16 refs `book|be`
(“Beneficie-se”) são **orientação de oratória** (`TRAINING`), não conteúdo.
O `citedSources` os exclui de propósito do bloco de conteúdo; o prompt
recebe o bloco de orientação BE/TH separado. Eles ficam fora do denominador
de conteúdo (375). A métrica oficial para “o Copilot vê o conteúdo” é
**347/375 = 92,5%**.

## 2. C2.8 — unificação livro (UI × prompt)

### Antes

| Status da UI (`NoteSources`) | `cited_ok=true` | `cited_ok=false` |
|---|---:|---:|
| `RESOLVED` | 245 | **34** |
| `PARTIAL` | 63 | **15** |
| `TEXT_NOT_FOUND` | **6** | 8 |
| `EDITION_MISSING` | 0 | 20 |

A UI dizia **357/391 = 91,3%**, mas o prompt só recebia **314/391 =
80,3%**. Os dois caminhos discordavam:

- 34 `RESOLVED` + 15 `PARTIAL` apareciam “No acervo”, mas o
  `citedSources` re-resolvia por `RoomReferenceResolver` e descartava o
  texto;
- 6 `TEXT_NOT_FOUND` tinham texto no prompt (`it` com 4.000/936/133 chars),
  porque o `RoomReferenceResolver` achava a unidade e o `NoteSources` não.

### Correção

- `RefStatus.promptText`: texto completo que o prompt recebe; o `snippet`
  continua sendo o recorte de 600 chars da UI.
- `CopilotRepository.enrichContents` usa o **mesmo** `RoomReferenceResolver`
  (página/§, página, §, contexto) e, se ele não achar, o fallback antigo
  (página/contexto/anexo). O texto encontrado define
  `contentResolved`/`contentPartial` — logo, o status da UI e o texto do
  prompt saem do mesmo lugar.
- `citedSources(noteSources)` usa `promptText`/`snippet` **antes** de
  re-resolver; não descarta mais uma fonte que a UI mostra no acervo.
- `be`/`th` são `TRAINING`: não entram no bloco de conteúdo nem no
  denominador; o prompt recebe o bloco de orientação separado.
- `citedPromptText` puro/testável; casos novos em `CitedSourceRulesTest`.

### Depois

| Métrica | Antes C2.8 | Depois C2.8 |
|---|---:|---:|
| `prompt_coverage` (conteúdo) | 80,3% (314/391) | **92,5% (347/375)** |
| `RESOLVED` | 245 | 56 |
| `PARTIAL` | 63 | 291 |
| `TEXT_NOT_FOUND` com texto | 6 | **0** |
| `TEXT_NOT_FOUND` genuíno | 8 | **8** |
| `EDITION_MISSING` | 20 | **20** |
| `WRONG_EDITION` | 76 (C2.6) | **0** |
| `TRAINING` | — | **16** |

O `RESOLVED` caiu e o `PARTIAL` subiu porque o resolvedor classifica como
`PARTIAL` muitos trechos que o `resolveContent` antigo marcava como
`RESOLVED`. Ambos são utilizáveis; a métrica que importa é o texto no
prompt. `citedSources` único = 289; `prompt_chars` = 986.253.

## 3. C2.6/C2.7 — matcher escolhia a edição errada

A primeira execução (antes do matcher corrigido) deu **202 RESOLVED + 81
PARTIAL = 72,4%**, com **76 `WRONG_EDITION`**. O
`RefDetector.matchEdition` aceitava um arquivo de outra edição quando
mês/dia apareciam no meio do ano de outro arquivo.

| Ref | Arquivo usado (errado) | Arquivo esperado |
|---|---|---|
| `w\|2011\|12\|15` (`w11 15/12`) | `w_T_20121115.jwpub` | `w_T_20111215.jwpub` |
| `w\|2020\|3` (`Sentinela 03/20`) | `w_T_20030715.jwpub` | `w_T_202003.jwpub` |
| `book\|lv` (`‘Mantenha-se’`) | `lvs_T.jwpub` | `lv_T.jwpub` |

### Correção aplicada

Em `RefDetector.matchEdition`:

1. **Ano de 4 dígitos** no nome do arquivo precisa ser o ano da edição.
2. Nomes oficiais da API (`*_T_YYYYMMDD`/`*_T_YYYYMM`) exigem **ano+mês**
   (e o dia, quando a ref tem dia) exatos; os fallbacks de 2 dígitos ficam
   restritos a nomes legados/título (`wp19.3.pdf`, `wp2019_3.pdf`, etc.).
3. Livro com símbolo de 2 letras não casa por substring: `lv` não pode
   casar `lvs_T.jwpub`; o match por título continua valendo para nomes
   longos (“Seja Feliz para Sempre.pdf”).

Testes: `RefDetectorEditionMatchTest`
(`dated_w_naoCasaMesmoMesDiaDeOutroAno`, `dated_w_mesmoAno_mesErradoNaoCasa`,
`monthly_w_naoCasaMesmoMesDeOutroAno`, `monthly_w_naoCasaDiaDeOutroAno`,
`livroLv_naoCasaLvs`, `formatoLegadoDeDuasLetrasContinuaCasando`).

**Depois do matcher:** 76 `WRONG_EDITION` → **0**; a métrica de UI subiu
72,4% → **91,3%**. O C2.8 depois levou o prompt a **92,5% de conteúdo**.

## 4. O que sobrou

### 8 `TEXT_NOT_FOUND` genuínos (0 chars no prompt)

| Edição | Ref |
|---|---|
| `book\|bhs` | `bhs 177 § 10` |
| `book\|bhs` | `bhs 176 § 8` |
| `book\|sh` | `(sh 28)` |
| `book\|lff` | `lff lição 14 ponto 1` |
| `book\|lff` | `lff lição 22` |
| `book\|lff` | `lff lição 46` |
| `book\|jy` | `jy 144 § 7` |
| `book\|it-1` | `it-1 749-51` |

Ação: **REINDEX/contexto**. São livros cujo índice não tem página/§
suficiente para a unidade citada. Não é “edição ausente”.

### 20 `EDITION_MISSING` (API não entrega o arquivo exato)

`w|2011|7|1`, `w|2008|5|1`, `w|2008|6|1`, `w|2008|9|1`, `w|2014|6|1`,
`w|2014|10|1`, `w|2013|1|1`, `w|2009|6|1`, `w|2012|1|1`, `w|2014|12|1`,
`w|2010|10|1`, `w|2011|11|1`, `w|2009|8|1`, `gn|2016|1` (2 refs), `gn|2018|2`.

Ação: **DOWNLOAD**/página (`PAGE_ONLY`). Edições datadas antigas ou
numeradas em que a API oficial retorna 404 para o dia exato e só existe a
edição mensal (que não casa a ref datada). É a limitação
`PDF_ONLY`/`UNAVAILABLE` prevista.

### 291 `PARTIAL` de conteúdo + 16 `TRAINING`

- **291 PARTIAL**: unidades resolvidas por página/contexto/recorte
  aproximado (PDF/EPUB sem página/§ indexados ou livro sem unidade exata).
  Ação: **OPEN** (conferir a unidade). É o caso “aceitar PARTIAL/contexto”
  — decisão de produto.
- **16 TRAINING**: todos `book|be` (“Beneficie-se”). São orientação de
  oratória, não conteúdo; o prompt recebe o bloco BE/TH separado. Ficam
  fora do denominador de conteúdo.

## 5. Validação EPUB/PDF reais

A API/região **não ofereceu edição exclusivamente EPUB ou exclusivamente
PDF** entre as 238 edições da matriz + 12 candidatas extras. A checagem
estrita registrou `NO_EPUB_ONLY`/`NO_PDF_ONLY`.

Para validar os pipelines reais, o AcervoBuilder baixou/indexou o formato
específico de uma edição que oferece os dois:

| Formato | Edição | Arquivo | Status |
|---|---|---|---|
| EPUB | `w\|2015\|4\|15` | `w_T_20150415.epub` | **READY** (indexado) |
| PDF | `w\|2025\|7` | `w_T_202507.pdf` | **READY** (indexado) |

Observação: a edição também tem JWPUB; o matcher prefere JWPUB na matriz.
A validação prova que o pipeline EPUB/PDF real importa e indexa. É um
**proxy** documentado, não uma edição exclusiva.

## 6. UI e acessibilidade

Nota de fixture: `note-c991f7c5-5690-4428-8b17-882c8535915b`
(“MATRIZ C2.6 UI”, 2 BODYs com 5 subtópicos cada).

| Evidência | Arquivo |
|---|---|
| Card expandido (PONTO 1 com 5 subtópicos) | [`assets/c26-ui/card-expandido.png`](assets/c26-ui/card-expandido.png) |
| Card colapsado (PONTO 1 só com “LINHA DE RACIOCÍNIO (5)”) | [`assets/c26-ui/card-colapsado.png`](assets/c26-ui/card-colapsado.png) |
| Árvore de acessibilidade expandida | [`assets/c26-ui/card-expandido.xml`](assets/c26-ui/card-expandido.xml) |
| Árvore de acessibilidade colapsada | [`assets/c26-ui/card-colapsado.xml`](assets/c26-ui/card-colapsado.xml) |

A árvore mostra `Copilot deste ponto`, `Menu da seção`, `Menu do subtópico`,
`Linha de raciocínio, 5 subtópicos` e os textos dos subtópicos. **TalkBack
auditivo** não foi ouvido; a inspeção foi pela árvore (pendência humana).

## 7. Keystore

- Keystore de release: `~/.better-talker-keystore/better-talker-release.jks`.
- Duas cópias em `~/btwork/keystore-backup/` com permissão `600`:
  - `better-talker-release.jks`
  - `better-talker-release-copy2.jks`
- **SHA-256 (idêntico nas duas):**
  `52f974bd4cc20ffece16aef045972b6e596f274f98299379075a11a276582fa3`.
- O `.debug` usa a debug keystore; a release não foi usada no spike.
- Senhas não foram impressas nem copiadas para o repositório.
- **Pendência:** as duas cópias estão na mesma máquina; levar uma para
  fora e testar restauração em outra máquina é custódia humana.

## 8. Como reproduzir

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.bettertalker.app.debug android.permission.POST_NOTIFICATIONS
adb shell am start -n com.bettertalker.app.debug/com.bettertalker.app.matrix.MatrixRunnerActivity --ez start true
# acompanhar
adb logcat -s MatrixC26
# puxar resultados C2.8
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-result.csv > result.csv
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-summary.txt
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-acervo.csv > acervo.csv
```

- CSV de entrada: `docs/reports/matriz-c26-2026-10-30.csv` (copiado para
  `android/app/src/debug/assets/matriz-c26.csv`; SHA-256
  `256c66e676a17efa0e11d575e25d51bde8e328059d0ad5d75b30c6819347b37d`).
- O runner é idempotente: edições exatas já prontas são puladas; o segundo
  run leva ~38 s (o primeiro, com 2,4 GB, levou ~6,3 min).
- O serviço é foreground (`dataSync`) com wake lock; tela stay-on via ADB.
- O runner agora mede `prompt_chars` e `prompt_coverage`; o status de UI e
  o texto do prompt saem do mesmo `RefStatus`.

## 9. Limitações e pendências

- **49 DOCX não foram commitados**; o CSV contém apenas refs/edição/unidade.
- **22 edições indisponíveis** na API (datadas antigas/regionais) — não é
  falha do app; o status é `EDITION_MISSING`/`PAGE_ONLY`.
- **8 TEXT_NOT_FOUND genuínos** em livros: índice de página/§ incompleto;
  decisão futura entre reindexar, usar contexto (PARTIAL) ou aceitar.
- **291 PARTIAL** de conteúdo: decisão de produto sobre aceitar
  contexto/recorte.
- **16 `be` TRAINING**: o prompt recebe o bloco de orientação BE/TH, não o
  texto como conteúdo; se o produto quiser o texto específico do `be` no
  prompt, é uma decisão separada.
- **TalkBack real** e revisão de 20 refs amostradas continuam humanas.
- **Keystore:** cópia off-machine/restauração em outra máquina pendente.
- **CI fixture:** o `S34CorpusFixtureTest` cobre o parser; falta testar o
  `S34ImportHook`/save dos 40 S34Parser.
- **STT embarcado:** decisão futura.
- **A34/#192** intocado; o release `com.bettertalker.app` não foi instalado
  no tablet.

## 10. Recomendação de release

1. **Matcher na main:** `40ceb95`, release **3.0.4/35** gerado e assinado
   (APK `8457822f…`; certificado release `ad1b0a30…`). O 3.0.3 publicado
   ainda escolhe edição errada.
2. **C2.8 em branch separada:** `fix/c28-book-prompt` @ `73f3bd6`, com
   `prompt_coverage` de conteúdo **92,5%** (meta ≥90%). Não mergeado na
   main; o próximo release deve ser 3.0.5/36.
3. **Runner/evidências:** `spike/matriz-c26-tablet` @ `aa4e4bd`; não vão
   para o APK de release.
4. **Instalação do release no tablet:** o tablet recusou com
   `INSTALL_FAILED_USER_RESTRICTED` (“Install canceled by user”); o
   `.debug` segue instalado. Instalar o 3.0.4/35 manualmente no A34/tablet
   quando a restrição for liberada.
5. Antes de fechar C2.6/C2.7/C2.8, revisar humanamente as 20 refs
   amostradas, ouvir o TalkBack e decidir o aceite de PARTIAL/contexto.
