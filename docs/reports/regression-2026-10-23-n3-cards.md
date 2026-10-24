# Regressão? — N+3 cards (título + Introdução + N tópicos + Conclusão)

**Data:** 2026-10-23 · **Escopo:** read-only (histórico + código + resultados de teste; sem build/install/commit)
**Pergunta:** o comportamento N+3 existia antes do Fix 1+2? Em qual caminho/versão/formato?

---

## 0. Veredito (o mais importante)

**O N+3 já existe hoje e não é uma regressão do Fix 1+2 no caminho principal.**

- `planPaste` reconhece a apostila como **`PasteKind.VmTalk`** e monta:
  **1 INTRO + N BODY ("Ponto 1"…"Ponto N", cada um com a frase + refs) + 1 CONCLUSION** (`PasteNoteBuilder.kt:488-504`).
- O teste **`format5_cadaFraseViraBodyComSeuTextoERefs`** espera **3 BODYs** com `contentHtml` e refs exatamente para o texto da apostila; está **verde** (`PastePlanTest`: 37 testes, 0 falhas).
- A Home persiste esses cards com `createPastedNote` (`HomeViewModel.kt:139`; `NotesRepository.kt:59-114` preserva `role`, `contentHtml`, refs).
- O editor tem **INTRODUÇÃO/DESENVOLVIMENTO/CONCLUSÃO** como `SectionRole` (`DraftTarget.kt:82-86`); o título é campo separado da nota. Portanto: **título + N+2 cards**; se o título for contado como card, **N+3**.

**O que confundiu o discovery anterior:** o teste no tablet não colou a apostila. O próprio relatório registra que o sheet auto-preencheu outro texto (38 caracteres) e que o `adb input text` falhou com `§`/acentos. O preview “Texto livre / 1 card (Corpo)” era desse outro texto — **não da apostila**. A conclusão “a Home sempre faz 1 Corpo para a apostila” não tem evidência válida.

**O que o Fix 1+2 realmente quebrou:** só o **fallback FreeText** do chat/editor. Se um texto **não** é reconhecido como V&M, antes o chat criava N `DraftSection` (títulos, bodies vazios) e o `linkDraft` gerava N+2 via `OutlineConverter`; depois do Fix 1+2 cria **1 seção** e o `linkDraft` retorna cedo (`sections.size < 2`, `CopilotViewModel.kt:4721`). No caminho VmTalk, `pasteOutline` retorna antes (`:4562-4568`) e o Fix não roda. Na Home, o fallback sempre foi 1 “Corpo”; o Fix não mudou isso.

---

## 1. Linha do tempo (versão + commit)

| Versão | Commit | O que já existia |
|---|---|---|
| **v2.2** | `48cee6f` (2026-10-07) | `OutlineConverter` sintetiza **INTRO + N BODY + CONCLUSION** para S-34 (N+2 seções; título separado). |
| **v2.2** | `2cbc4e8` (2026-10-18) | Home “Colar e Pronto”: TALK vira **INTRO + BODY + CONCLUSION**; genérico vira 1 BODY “Corpo”. |
| **v2.2** | `b3418f7` (2026-10-19) | `planPaste` unificado (Home/Editor/Chat). |
| **v2.3** | `7ea6fcd` (2026-10-19) | Detecta V&M em **5 formatos** (`detectSingleLineVmPart`); apostila título + `(N min)` + refs vira `VmTalk`. |
| **v2.4.4** | `629e53c` (2026-10-20) | **Cada frase vira um BODY independente** (“Ponto 1..N”) com conteúdo e refs — o N+3 com conteúdo. |
| **v2.7.3** | — | Mesmo comportamento; `pasteOutline` do chat criava N `DraftSection` e `linkDraft` → `OutlineConverter` (N+2). |
| **v2.7.4** | `cbe8e13` | Igual à v2.7.3; **sem** `inlineApostila`. |
| **Fix 1** | `21c109d` (2026-10-23) | Chat fallback FreeText vira **1 seção**; `linkDraft` passa a recusar (<2). |
| **Fix 2** | `d27767b` (2026-10-23) | `_draftTotal = 10` nessa 1 seção. |

**Resposta curta:** o N+3 com conteúdo existe desde **v2.4.4**; a detecção dos 5 formatos desde **v2.3**; a estrutura intro/conclusão desde **v2.2**. Não é comportamento novo nem regressão recente no caminho VmTalk.

---

## 2. As 4 respostas

### 1) Em qual versão “isso já acontecia”?

- **Estrutura intro + N + conclusão:** desde **v2.2** (`48cee6f` para S-34; `2cbc4e8` para TALK na Home).
- **Apostila reconhecida como V&M (5 formatos):** desde **v2.3** (`7ea6fcd`).
- **Cada frase como BODY separado com conteúdo:** desde **v2.4.4** (`629e53c`).
- **Fix 1+2:** `21c109d`/`d27767b` são pós-v2.7.4; não estão em tag.

### 2) Em qual caminho?

| Caminho | O que faz |
|---|---|
| **Home → Inserir material → Colar** | `HomeViewModel.createNoteFromPaste` (`:122`) → `planPaste` (`:123`) → `PasteKind.VmTalk` → `createPastedNote(plan.toPastedNote())` (`:139`). Persiste **Intro + N BODY + Conclusion** (`PasteNoteBuilder.kt:488-504`; `NotesRepository.kt:73-111`). |
| **Chat/Editor → Colar** | `pasteOutline` (`CopilotViewModel.kt:4558`) → `planPaste` (`:4561`) → se `VmTalk`, `applyVmPastePlan` (`:4562-4568`, `3194-3254`): nota vazia recebe `plan.sections` inteiro; nota com estrutura recebe só os BODYs. |
| **S-34 / esboço completo** | `OutlineConverter` (`OutlineConverter.kt:79-145`) → Intro + N BODY + Conclusion. |
| **Fallback FreeText** | Home: 1 BODY “Corpo” (`PasteNoteBuilder.kt:641-657`). Chat: antes N drafts → `linkDraft` → N+2; depois do Fix 1+2, 1 draft e `linkDraft` recusa. |

### 3) Com qual formato de entrada?

- A apostila **crua** (título com `(10 min)` + N linhas com refs), **sem** `Tema:` e **sem** numeração, já é reconhecida como `VmTalk` por `detectSingleLineVmPart` (`VmMaterialDetector.kt:604-631`). O teste `format5_cadaFraseViraBodyComSeuTextoERefs` usa exatamente o conteúdo da apostila e espera 3 BODYs; `format1_semLinhasEmBranco` cobre título/`(10 min)`/corpo em linhas separadas. O comentário do detector diz “com ou sem quebras de linha” (`:596-598`).
- **Único buraco de evidência:** o formato exato de **4 linhas** (tempo na linha do título + corpo nas linhas seguintes) não tem teste próprio. Por leitura, cai em `detectSingleLineVmPart` (o `body` começa depois do `(10 min)` e tem as 3 linhas); falta confirmar com um teste puro.
- Com marcadores (`Tema:`, `1.`, `2.`), o caminho é o **S-34 oficial** (`S34Parser`/`S34ImportHook`), não o `planPaste` da colagem. O `planPaste` usa `OutlineParser` (seções com `(N min)`), não `S34Parser`.

### 4) O editor tem campos INTRODUÇÃO e CONCLUSÃO?

**Sim.** `SectionRole.INTRO` / `SectionRole.CONCLUSION` (`DraftTarget.kt:82-86`; `SectionsController.kt:416-425`), com labels **INTRODUÇÃO** e **CONCLUSÃO**. O título é campo da nota, não uma `SectionRole`. Portanto:
- cards persistidos = **N+2** (Intro + N BODY + Conclusão);
- se o título for contado como card na UI, **N+3**.
- No `planPaste` VmTalk, os cards já vêm nessa ordem e com conteúdo (`PasteNoteBuilder.kt:488-504`).

---

## 3. O que o Fix 1+2 fez de errado (e o que não fez)

| Ponto | Evidência |
|---|---|
| Não afeta o caminho VmTalk | `pasteOutline` retorna em `applyVmPastePlan` antes do bloco `inlineApostila` (`CopilotViewModel.kt:4562-4568` vs `:4576`). A Home nem chama `pasteOutline`. |
| Afeta o fallback FreeText | Antes: `cands` → N `DraftSection` (`CopilotViewModel.kt` v2.7.4, `:4576-4585`); `linkDraft` com N≥2 → `OutlineConverter` → N+2 (bodies vazios). Depois: 1 `DraftSection`; `linkDraft` recusa `<2` (`:4721`). |
| `inlineApostila` implementa o modelo errado | Retorna 1 seção com N pontos no body (`OutlineParser.kt:384-389`; `CopilotViewModel.kt:4577-4588`), não N BODYs + intro/conclusão. |
| Título do VmTalk ainda tem tempo | `noteTitle = "$partTitle ($minutes min)"` (`PasteNoteBuilder.kt:508`). O esperado é o título sem `(10 min)`. |
| Teste verde engana | `PastedInlineApostilaTest` valida só a 1 seção; não valida o modelo N+3. `PastePlanTest` (VmTalk) é que valida 3 BODYs. |

---

## 4. Próximo passo recomendado (sem implementar ainda)

1. **Adicionar um teste puro com o texto exato de 4 linhas** em `PastePlanTest` e afirmar:
   - `plan.kind is PasteKind.VmTalk`;
   - `plan.sections` = 1 INTRO + 3 BODY (“Ponto 1..3”) + 1 CONCLUSION;
   - cada BODY contém a frase + refs;
   - `plan.noteTitle` sem `(10 min)` (hoje falha: `:508`).
2. **Se o teste passar** (provável): o fix é só **título** (remover o tempo) e, se desejado, o label do preview. **Não** reescrever o parser; o N+3 já existe.
3. **Se o teste falhar** (cair em FreeText): o gap é **detecção**, não estrutura. Corrigir `detectVmMaterial` para o formato de 4 linhas; não criar um segundo parser.
4. **Reformular o Fix 1+2** apenas para o fallback: se um texto não for V&M, o fallback deve produzir N BODYs + INTRO/CONCLUSÃO (como o VmTalk), não 1 seção. O `inlineApostila` pode continuar como extrator de título/tempo/pontos, mas o mapeamento vira N seções.
5. **Reverter ou manter?** Não reverter às cegas: o `inlineApostila` não é necessário para o caminho VmTalk e está errado para o fallback. O certo é **substituí-lo** pela construção N+3 (ou remover o branch se a detecção V&M cobrir 100% dos casos).
6. **Tablet:** repetir a colagem com o texto exato (clipboard correto) e conferir “V&M · Discurso / 5 cards”; o teste anterior não vale como evidência.

---

## 5. Evidência de teste (sem build)

Resultado da última suíte (2.211/0), arquivo `TEST-com.bettertalker.app.data.util.PastePlanTest.xml`:

```
tests 37 failures 0 errors 0
format5_cadaFraseViraBodyComSeuTextoERefs ok
format5_conteudoVaiParaCadaBodySemSubpontos ok
format1_semLinhasEmBranco_retornaVmTalk ok
format1_semNumeroSemCabecalho_retornaVmTalk ok
format2_comNumeroMinutosLinhaSeguinte_retornaVmTalk ok
format3_comCabecalho_retornaVmTalk ok
format4_singleLineMultiParagrafo_retornaVmTalk ok
format5_singleLineTudoInline_retornaVmTalk ok
```

**Leitura:** o N+3 com 3 BODYs e conteúdo é comportamento **atual e testado**; o Fix 1+2 não o substituiu no caminho VmTalk. O problema real é a **observação inválida no tablet** + o **fallback FreeText** + o **título com tempo**.

---

## 6. Respostas às 2 perguntas do prompt de revisão

### 6.1 O `format5_cadaFraseViraBodyComSeuTextoERefs` usa o texto exato?

**Não no formato; sim no conteúdo.** O teste usa o mesmo título, as mesmas 3 frases e as mesmas refs, mas em **uma única linha** (`PastePlanTest.kt:346-351`):

```
"Ter esperança ... (10 min) Parece que Baruque ... (Jer. 45:3; w25.07 22 §§ 8-9) Sem ... (w25.07 20 § 2) Jeová ... (Jer. 45:5; w25.07 24 §§ 13-14)"
```

O texto real tem **4 linhas**: título + `(10 min)` na linha 1 e cada frase em sua própria linha. O teste mais próximo com quebras é `format1_semLinhasEmBranco` (`:401-409`), mas ele coloca o `(10 min)` em uma **linha separada** e usa um corpo de 3 linhas. O híbrido “tempo inline + corpo em linhas” **não tem teste**. Portanto o teste novo **não é redundante** — é o único que cobre a formatação real.

### 6.2 O `detectSingleLineVmPart` é chamado quando o título tem `(10 min)`?

**Sim, e o `(10 min)` é justamente a âncora.** `detectVmMaterial` chama `detectSingleLineVmPart(normalized)` incondicionalmente (`VmMaterialDetector.kt:767`). Dentro dele:

- `PAREN_MIN_RE.find(text)` acha `(10 min)` em qualquer posição (`:605`);
- `title = text.substring(0, time.range.first)` = linha do título (`:608`);
- `body = text.substring(time.range.last + 1)` = as 3 linhas seguintes (`:615`);
- rejeita se houver outro `(N min)` no body (`:617`) — as refs não têm `min`;
- exige publicação (`pubs.isNotEmpty()`, `:619`) e sinal bíblico (`:622-623`) — a apostila tem `w25.07` e `Jer. 45:3/45:5`;
- título < 80 chars (`:609`) — 49;
- `parsePartLine(firstLine)` é nulo (não começa com número) e `headerOf` é nulo (`:612-614`).

**Por leitura, o texto exato de 4 linhas passa.** O que falta é o teste de runtime com essa formatação; não há evidência de que o `(10 min)` “confunda” a detecção.

---

## 7. Decisão recomendada para o próximo prompt

- **(b) reformular**, não apenas reverter.
- Manter `inlineApostila` como **extrator puro** (`title`, `minutes`, `points`).
- No `planPaste`, **depois** do gate S-34 (`parsed.sections.size >= 2`), se `inlineApostila` casar, montar **N+2 `PastedSection`s**: `INTRO` + N `BODY` (“Ponto i”, `contentHtml` = frase + refs) + `CONCLUSION`, com minutos distribuídos e `noteTitle = inline.title` sem `(10 min)`.
- Home: `createPastedNote` persiste N+2 (mesmo caminho do VmTalk).
- Chat/editor: aplicar `plan.sections` como o VmTalk (nota vazia = todos; nota com estrutura = só BODYs) ou gerar N `DraftSection` + `linkDraft`; a primeira opção elimina o gate `<2` e cobre N=1.
- Preview: “Apostila · 1 parte” / “N+2 cards”, não “Texto livre / 1 card (Corpo)”.
- Testes: texto exato de 4 linhas (VmTalk), fallback só-Bíblia (Inline/Fallback), N=1, título sem tempo.
- **Não** mexer em `S34Parser`/`S34Detector`/persona/hints/router.
