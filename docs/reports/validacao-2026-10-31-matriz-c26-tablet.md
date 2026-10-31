# Matriz C2.6/C2.8 no tablet — 391 refs / 238 edições (2026-10-31)

**Branches:** spike `spike/matriz-c26-tablet` @ `8fecbef`; release **3.0.6/37**
tag `v3.0.6` @ `77d7142`; **3.0.5/36** tag `v3.0.5` @ `dc5137c`; **3.0.4/35**
tag `v3.0.4` @ `40ceb95`.
**Tablet:** Samsung Galaxy Tab 25040RP0AL · Android 16 (SDK 36) · arm64 ·
1600×2560 · 172 GB livres · Wi-Fi `Guilherme_5G`
**APK debug:** `com.bettertalker.app.debug` (3.0.3-debug, versionCode 34),
SHA-256 `f12597ba95dbbf9cec5c9fa2195f0698c9d3cfe6befa10b58fb526ec01b775b7`.
**APK release 3.0.6/37:** SHA-256
`18bd8a231b8700f0ea3442d0e97b66920f917818825a09b14d01f70297032c00`,
release `v3.0.6` publicado; certificado release
`ad1b0a302256a66aa083d76b767291eb2e3c8c4b07f07ae741dd99716233bba4`.
**APK release 3.0.5/36:** SHA-256
`d795ea116c422d302f5f089f0fdeb211f7d16586fb0a7cfcee66700263d348b5`.
**APK release 3.0.4/35:** SHA-256
`8457822fac8e6d567d5e4373ca6978bbdb6d09618fd64e6d6831c0b9e117e228`.
**A34:** 3.0.4/35, 3.0.5/36 e 3.0.6/37 instalados por cima; app abriu,
notas existentes preservadas e matcher confirmado (`W11 15/12` →
`w_T_20111215.jwpub`). A nota **#192 não aparece na UI atual do A34** (Home, Discursos,
Lixeira e busca “192” vazios), mas está **preservada em backups locais**
(`/tmp/opencode/a34-backup`, `a34-live`, `m2b-192-clean`, etc.; nota
`note-f21f19a7-…`, “# 192. Você está no caminho para a vida eterna?”).
O update não apagou os dados; a ausência na UI atual merece confirmação
(folder/filtro/trash).
**Testes JVM:** **2.786 / 0** no 3.0.6; **2.776 / 0** no C2.8; **2.771 / 0**
no matcher-only.
**Lint:** `:app:lintRelease` verde no gate do CI (49 warnings, 6 hints, 0 erros); `:app:lintDebug` também verde.

## 1. Resultado final (3.0.6)

| Métrica | Resultado |
|---|---|
| Refs da matriz | **391** |
| Refs de CONTEÚDO (sem be/th) | **375** |
| **`prompt_coverage` (conteúdo)** | **347/375 = 92,5%** (meta ≥90%) |
| `cited_ok` (todos os refs) | 347/391 = 88,7% |
| `RESOLVED` | **156** |
| `PARTIAL` | **191** |
| `TEXT_NOT_FOUND` | **8** |
| `EDITION_MISSING` | **20** |
| `TRAINING` (`be`/`th`) | **16** |
| `WRONG_EDITION` | **0** |
| Edições prontas/indexadas | **216/238** |
| Indisponíveis na API | **22** |
| Falhas de download/indexação | **0** |
| Acervo baixado | **2,40 GB** |
| `prompt_chars` (conteúdo) | **546.030** |

CSV final:
[`matriz-c26-2026-10-31-c28-306-tablet.csv`](matriz-c26-2026-10-31-c28-306-tablet.csv);
acervo:
[`matriz-c26-2026-10-31-c28-306-acervo.csv`](matriz-c26-2026-10-31-c28-306-acervo.csv);
resumo bruto:
[`matriz-c26-2026-10-31-c28-306-summary.txt`](matriz-c26-2026-10-31-c28-306-summary.txt).
O CSV C2.6/C2.7 anterior (`matriz-c26-2026-10-31-tablet.csv`) fica como
histórico da métrica de UI (91,3%).

**Por que “todos os refs” é 88,7% e não 92,5%:** os 16 refs `book|be`
(“Beneficie-se”) são **orientação de oratória** (`TRAINING`), não conteúdo.
O `citedSources` os exclui de propósito do bloco de conteúdo; o prompt
recebe o bloco de orientação BE/TH separado. Eles ficam fora do denominador
de conteúdo (375). A métrica oficial para “o Copilot vê o conteúdo” é
**347/375 = 92,5%**.

## 2. Medição: runner inicial, runner corrigido e 3.0.6

| Etapa | Cobertura | RESOLVED | PARTIAL | TEXT_NOT_FOUND | `prompt_chars` |
|---|---:|---:|---:|---:|---:|
| 3.0.5, runner manual (inicial) | 347/375 = 92,5% | 56 | 291 | 8 | 986.253 |
| 3.0.5, runner corrigido (detector real) | 349/375 = 93,1% | 156 | 193 | 6 | 716.968 |
| 3.0.6, correções de foco/§/faixas | **347/375 = 92,5%** | **156** | **191** | **8** | **546.030** |

O runner inicial montava a `DetectedRef` à mão e **perdia `article`/`chapter`**.
Isso fazia livros por § caírem no fallback global e resolvia 56 refs em vez
de 156. O runner corrigido usa `RefDetector.detect` e revelou o número real
do 3.0.5: **193 PARTIAL / 93,1%**.

O 3.0.6 troca 2 refs de cobertura por correção:

- **3 §§ globais errados** (`it-1 544`, `it-2 890`, `it-1 587`) viraram
  `TEXT_NOT_FOUND` em vez de devolver o § de outro artigo;
- **1 faixa** (`it-1 749-51`) passou a resolver (749..751);
- **2 `cl`** (`cl 225`, `cl 171`) passaram a resolver o § exato (869/839
  chars), em vez de só 600 chars do fallback;
- **foco por §** e **começo+fim** encurtaram o prompt de 716.968 para
  546.030 chars sem perder o trecho citado.

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
72,4% → **91,3%**.

## 4. O que sobrou (3.0.6)

### 191 `PARTIAL` de conteúdo

Quebra pelo caminho que o `RoomReferenceResolver` usou. A coluna
`partial_reason` do CSV é derivada do `resolver_canonical` + os campos
`page`/`paragraph`:

| Motivo | Refs | O que aconteceu |
|---|---:|---|
| Página sem § indexado (índice antigo) | **116** | A ref cita §, mas o índice da edição não tem números de parágrafo; o resolvedor devolveu a página e o foco recortou o § inicial. |
| Página inteira (ref sem §) | **69** | A citação é só por página; o texto da página é a unidade. |
| § (unidade por parágrafo) | **4** | 3 artigos `it` (`Nações`, `Termo`, `Espírito`) + `lff lição 14 §1`; o § exato não está indexado e o texto é a unidade. |
| Unidade de lição | **2** | `lff lição 22` e `lff lição 46` sem §; o texto é a lição inteira. |
| Contexto do esboço | **0** | Nenhuma ref precisou do fallback por frase do esboço nesta matriz. |
| **Total** | **191** | |

Dimensão adicional (não exclusiva): **45** dos 191 textos bateram no
limite de 4.000 chars; os outros **146** couberam inteiros.

### 8 `TEXT_NOT_FOUND` genuínos (0 chars no prompt)

| Edição | Ref |
|---|---|
| `book\|it-1` | `it-1 544 § 4` |
| `book\|it-2` | `it-2 890 §§ 5-6` |
| `book\|it-1` | `it-1 587 § 6` |
| `w\|2006\|5\|15` | `Sentinela 15/05/06` |
| `w\|2006\|3\|15` | `Sentinela 15/03/06` |
| `w\|2006\|4\|1` | `Sentinela 01/04/06` |
| `wp\|2016\|3` | `A Sentinela N.º 3 de 2016` |
| `book\|jy` | `jy 144 § 7` |

Os 3 `it-1`/`it-2` são § sem página em edição com índice de página: o
resolvedor **não** usa o § global (evita outro artigo) e devolve
`TEXT_NOT_FOUND`. Ação: reindexar ou usar contexto/artigo.

### 20 `EDITION_MISSING` (API não entrega o arquivo exato)

`w|2011|7|1`, `w|2008|5|1`, `w|2008|6|1`, `w|2008|9|1`, `w|2014|6|1`,
`w|2014|10|1`, `w|2013|1|1`, `w|2009|6|1`, `w|2012|1|1`, `w|2014|12|1`,
`w|2010|10|1`, `w|2011|11|1`, `w|2009|8|1`, `gn|2016|1` (2 refs), `gn|2018|2`.

Ação: **DOWNLOAD**/página (`PAGE_ONLY`). Edições datadas antigas ou
numeradas em que a API oficial retorna 404 para o dia exato e só existe a
edição mensal (que não casa a ref datada). É a limitação
`PDF_ONLY`/`UNAVAILABLE` prevista.

### 16 `TRAINING` (`book|be`)

Todos `book|be` (“Beneficie-se”). São orientação de oratória, não conteúdo;
o prompt recebe o bloco BE/TH separado. Ficam fora do denominador de
conteúdo.

## 5. Amostra humana de 20 refs (3.0.6)

**18 OK · 1 PARCIAL · 1 SEM TEXTO · 0 ERRADO.**

A amostra foi estratificada em 11 “página sem § indexado”, 5 “página
inteira”, 3 “§” e 1 “unidade de lição”, com oversample proposital de
livros/§. O resumo público está em
[`amostra-20-c28-resumo.md`](amostra-20-c28-resumo.md); o pacote com o
texto integral do prompt fica local (`~/btwork/amostra-20-c28/`) e não é
commitado por conter trechos das publicações.

| # | Ref | Veredito | Observação |
|---|---|---|---|
| 01 | `‘Mantenha-se’` (pág. 33-34) | **OK** | Foco em §16-18; exortação a não se afastar da congregação. |
| 02 | `w98 15/2 26 §5` | **OK** | “Encorajamento” e “consolar” (2 Coríntios 1:3-5) no texto. |
| 03 | `w10 15/2 15 §5` | **OK** | Foco em §5; água da vida. |
| 04 | `w09 15/7 9 §§8-10` | **OK** | Foco em §8-10; esposa/respeito ao marido. |
| 05 | `w11 15/12 16-17 §§18-19` | **OK** | Foco em §18-19; página correta (espírito de Jeová). |
| 06 | `w09 15/10 9 §§7-9` | **OK** | Foco em §7-9; “Sede pacíficos”. |
| 07 | `w10 15/8 13-14 §§8-11` | **OK** | Foco em §8-11; resgate/perdão/fé. |
| 08 | `w15 15/10 20 §§7-8` | **OK** | Foco em §7-8; “Não use o mundo plenamente”. |
| 09 | `w07 15/9 26 §§1-2` | **OK** | Foco em §1-2; bom samaritano. |
| 10 | `w15 15/2 8-9 §§14-16` | **OK** | Foco em §14-16; ternura/ações/palavras. |
| 11 | `w16.09 29 §§3-5` | **OK** | Foco em §3-5; conhecer bem os filhos. |
| 12 | `g93 22/1 9-11` | **OK** | Começo+fim; conclusão sobre Deus salvar a Terra. |
| 13 | `it-1 665` | **PARCIAL** | “Demas” só em 3.625/4.000, depois de conteúdo sobre “dedo”; entra, mas enterrado. |
| 14 | `w73 1/3 140-1` | **OK** | Começo+fim; citação do missionário isolado. |
| 15 | `w07 1/4 8-11` | **OK** | Começo+fim; profecia de Jesus e confirmação histórica. |
| 16 | `kj 142-4` | **OK** | Começo+fim; o fim inclui “a vindoura destruição de Jerusalém e seu templo”. |
| 17 | `it-1 544 §4` | **SEM TEXTO** | § global removido; a página 544 não tem §4. Antes vinha “Filho de Sasaque”, de outro artigo. |
| 18 | `it “Termo” §15` | **OK** | Começo+fim; o fim inclui o pacto da Lei que “separou Israel”. |
| 19 | `lff lição 14 ponto 1` | **OK** | Lição 14 §1; adoração baseada na Bíblia. |
| 20 | `lff lição 22` | **OK** | Lição 22; falar as boas novas. |

**Controles:** `cl 225 §15` e `cl 171 §8` agora `RESOLVED` com o § exato
(869/839 chars); `it-1 749-51` agora `PARTIAL` (2.634 chars).

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
# puxar resultados 3.0.6
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-result.csv > result.csv
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-summary.txt
adb exec-out run-as com.bettertalker.app.debug cat files/matriz-c26-acervo.csv > acervo.csv
```

- CSV de entrada: `docs/reports/matriz-c26-2026-10-30.csv` (copiado para
  `android/app/src/debug/assets/matriz-c26.csv`; SHA-256
  `256c66e676a17efa0e11d575e25d51bde8e328059d0ad5d75b30c6819347b37d`).
- O runner é idempotente: edições exatas já prontas são puladas; o segundo
  run leva ~2 min (o primeiro, com 2,4 GB, levou ~6,3 min).
- O serviço é foreground (`dataSync`) com wake lock; tela stay-on via ADB.
- O runner usa `RefDetector.detect` (article/chapter) e mede `prompt_chars`
  e `prompt_coverage`; o status de UI e o texto do prompt saem do mesmo
  `RefStatus`.

## 9. Limitações e pendências

- **49 DOCX não foram commitados**; o CSV contém apenas refs/edição/unidade.
- **22 edições indisponíveis** na API (datadas antigas/regionais) — não é
  falha do app; o status é `EDITION_MISSING`/`PAGE_ONLY`.
- **8 TEXT_NOT_FOUND genuínos** (3 `it-1`/`it-2`, 3 Sentinela 2006,
  `wp 2016`, `jy 144 §7`): índice de página/§ incompleto; decisão futura
  entre reindexar, usar contexto ou aceitar.
- **191 PARTIAL**: 18/20 da amostra são claramente relevantes; 1 é
  `PARCIAL` (`it-1 665`, “Demas” enterrado) e 1 é `SEM TEXTO`
  (`it-1 544 §4`, corretamente sem texto). A decisão de produto sobre
  aceitar o recorte de 4.000 chars continua humana.
- **TalkBack real** e revisão das 20 refs amostradas continuam humanas.
- **Keystore:** cópia off-machine/restauração em outra máquina pendente.
- **CI fixture:** o `S34CorpusFixtureTest` cobre o parser; falta testar o
  `S34ImportHook`/save dos 40 S34Parser.
- **STT embarcado:** decisão futura.
- **#192:** não aparece na UI atual do A34, mas está preservada em múltiplos
  backups locais (`/tmp/opencode/a34-backup`, `a34-live`, `m2b-192-clean`,
  etc.; `note-f21f19a7-…`). O update não apagou o dado; confirmar
  folder/filtro/trash na UI atual.
- **Tablet:** o release não instala por `INSTALL_FAILED_USER_RESTRICTED`;
  o `.debug` e o acervo de 2,4 GB seguem intactos. Instalar o 3.0.6/37
  manualmente quando a restrição for liberada.
- **3.0.5:** as notas publicadas ainda dizem 291 PARTIAL/92,5% (medição
  inicial). O addendum com 193 PARTIAL/93,1% e a explicação do runner
  corrigido deve ser aplicado ao release 3.0.5.

## 10. Recomendação de release

1. **3.0.6/37 publicado:** tag `v3.0.6`, APK `18bd8a23…`, 2.786 testes,
   lint verde e smoke no A34. É o release recomendado.
2. **3.0.5/36** fica como histórico; corrigir as notas com o addendum da
   medição (193 PARTIAL/93,1%).
3. **3.0.4/35** fica como matcher-only; não menciona C2.8.
4. **Runner/evidências** no spike `8fecbef`; o relatório, CSVs e assets
   estão na main do 3.0.6.
5. Antes de fechar C2.6/C2.7/C2.8, revisar humanamente as 20 refs
   (feito: 18 OK/1 PARCIAL/1 SEM TEXTO), ouvir o TalkBack e decidir o
   aceite de PARTIAL/contexto.
