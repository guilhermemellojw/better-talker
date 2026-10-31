# Matriz C2.6 no tablet — 391 refs / 238 edições (2026-10-31)

**Branch:** `spike/matriz-c26-tablet` (base `main` @ `1dd5c97`, v3.0.3;
commit do spike `2683887`)
**Tablet:** Samsung Galaxy Tab 25040RP0AL · Android 16 (SDK 36) · arm64 ·
1600×2560 · 172 GB livres · Wi-Fi `Guilherme_5G`
**APK:** `com.bettertalker.app.debug` (3.0.3-debug, versionCode 34), separado do
release `com.bettertalker.app`. SHA-256 do APK:
`f4498898dd43234ab6f1fcf93601bbf157b656877b075593cb2b34b962783962`.
**A34/#192:** não conectado, não tocado.
**Testes JVM (release-main):** **2.771 / 0 falhas** (inclui
`RefDetectorEditionMatchTest`).

## 1. Resultado executivo

| Métrica | Resultado |
|---|---|
| Refs da matriz | **391** |
| Edições únicas | **238** |
| Edições prontas/indexadas | **216** |
| Edições indisponíveis na API | **22** |
| Falhas de download/indexação | **0** |
| Acervo baixado | **2,40 GB** |
| `RESOLVED` | **279** |
| `PARTIAL` | **78** |
| `TEXT_NOT_FOUND` | **14** |
| `EDITION_MISSING` | **20** |
| `WRONG_EDITION` | **0** |
| **Utilizáveis (RESOLVED + PARTIAL)** | **357 / 391 = 91,3%** |

O alvo de **90% RESOLVED/PARTIAL** foi atingido. O CSV completo está em
[`matriz-c26-2026-10-31-tablet.csv`](matriz-c26-2026-10-31-tablet.csv); o
acervo em [`matriz-c26-2026-10-31-acervo.csv`](matriz-c26-2026-10-31-acervo.csv);
o resumo bruto em
[`matriz-c26-2026-10-31-summary.txt`](matriz-c26-2026-10-31-summary.txt).

## 2. Achado principal: matcher escolhia a edição errada

A primeira execução (antes da correção) deu **202 RESOLVED + 81 PARTIAL =
72,4%**, mas **76 refs** foram marcadas `WRONG_EDITION`: o
`RefDetector.matchEdition` aceitava um arquivo de outra edição quando
mês/dia apareciam no meio do ano de outro arquivo.

Exemplos reais:

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

Testes travando os casos: `RefDetectorEditionMatchTest`
(`dated_w_naoCasaMesmoMesDiaDeOutroAno`, `dated_w_mesmoAno_mesErradoNaoCasa`,
`monthly_w_naoCasaMesmoMesDeOutroAno`, `monthly_w_naoCasaDiaDeOutroAno`,
`livroLv_naoCasaLvs`, `formatoLegadoDeDuasLetrasContinuaCasando`).

**Depois da correção:** 76 `WRONG_EDITION` → **0**; utilizáveis 72,4% →
**91,3%**.

## 3. O que sobrou

### 14 `TEXT_NOT_FOUND` (edição no acervo, trecho não encontrado)

Todas são **livros** cujo índice não tem página/§ suficiente para a unidade
citada:

| Edição | Refs |
|---|---|
| `book|it-1` / `it-2` / `it “Nações”` / `it “Espírito”` | `it-1 544 § 4`, `it-2 890 §§ 5-6`, `it “Nações” §§ 10`, `it “Espírito” § 27`, `it-1 587 § 6`, `it-1 749-51` |
| `book|bhs` | `bhs 177 § 10`, `bhs 176 § 8` |
| `book|sh` | `sh 28` |
| `book|lff` | `lff lição 14 ponto 1`, `lff lição 22`, `lff lição 46` |
| `book|jy` | `jy 144 § 7` |

Ação sugerida pelo app: **REINDEX** (o índice pode estar incompleto para
página/§ de livros). Não é “edição ausente”.

### 20 `EDITION_MISSING` (API não entrega o arquivo exato)

São edições datadas antigas ou numeradas em que a API oficial retorna 404
para o dia exato e só existe a edição mensal (que não casa a ref datada):

`w|2011|7|1`, `w|2008|5|1`, `w|2008|6|1`, `w|2008|9|1`, `w|2014|6|1`,
`w|2014|10|1`, `w|2013|1|1`, `w|2009|6|1`, `w|2012|1|1`, `w|2014|12|1`,
`w|2010|10|1`, `w|2011|11|1`, `w|2009|8|1`, `gn|2016|1` (2 refs), `gn|2018|2`.

Ação sugerida: **DOWNLOAD**/página (`PAGE_ONLY`). É a limitação
`PDF_ONLY`/`UNAVAILABLE` prevista no plano; não há arquivo derivável na API
para estas edições nesta região.

### 78 `PARTIAL`

São unidades resolvidas por contexto/recorte aproximado (principalmente
PDF/EPUB sem página/§ indexados ou livro sem unidade exata). Ação: **OPEN**
(conferir a unidade). É o caso “aceitar PARTIAL/contexto” — decisão de
produto.

## 4. Validação EPUB/PDF reais

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
A validação prova que o pipeline EPUB/PDF real importa e indexa.

## 5. UI e acessibilidade

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

## 6. Keystore

- Keystore de release: `~/.better-talker-keystore/better-talker-release.jks`.
- Duas cópias offline em `~/btwork/keystore-backup/` com permissão `600`:
  - `better-talker-release.jks`
  - `better-talker-release-copy2.jks`
- **SHA-256 (idêntico nas duas):**
  `52f974bd4cc20ffece16aef045972b6e596f274f98299379075a11a276582fa3`
- O `.debug` usa a debug keystore; a release não foi usada no spike.
- Senhas não foram impressas nem copiadas para o repositório.

## 7. Como reproduzir

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.bettertalker.app.debug android.permission.POST_NOTIFICATIONS
adb shell am start -n com.bettertalker.app.debug/com.bettertalker.app.matrix.MatrixRunnerActivity --ez start true
# acompanhar
adb logcat -s MatrixC26
# puxar resultados
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-result.csv > result.csv
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-summary.txt
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-acervo.csv > acervo.csv
```

- CSV de entrada: `docs/reports/matriz-c26-2026-10-30.csv` (copiado para
  `android/app/src/debug/assets/matriz-c26.csv`; SHA-256
  `256c66e676a17efa0e11d575e25d51bde8e328059d0ad5d75b30c6819347b37d`).
- O runner é idempotente: edições exatas já prontas são puladas; o segundo
  run levou ~38 s (vs. ~6,3 min do primeiro, incluindo 2,4 GB).
- O serviço é foreground (`dataSync`) com wake lock; tela stay-on via ADB.

## 8. Limitações e pendências

- **49 DOCX não foram commitados**; o CSV contém apenas refs/edição/unidade.
- **22 edições indisponíveis** na API (datadas antigas/regionais) — não é
  falha do app; o status é `EDITION_MISSING`/`PAGE_ONLY`.
- **14 TEXT_NOT_FOUND** em livros: índice de página/§ incompleto; decisão
  futura entre reindexar, usar contexto (PARTIAL) ou aceitar.
- **TalkBack real** e revisão de 20 refs amostradas continuam humanas.
- **A34/#192** intocado; o release `com.bettertalker.app` não foi instalado
  no tablet.
- O matcher corrigido é uma mudança real no app (não só no runner); os
  testes JVM do release-main estão verdes (2.771).
