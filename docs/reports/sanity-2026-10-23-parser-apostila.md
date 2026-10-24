# Sanity — parser da apostila TESOUROS (4 linhas inline)

**Build:** v2.7.4 (v24) · **Data:** 2026-10-23
**Método:** texto **exato** (acentos + `§`) passado ao parser puro via teste temporário
(`PastedOutlineAnalyzer` + `RefDetector` + `S34Parser` + `S34Detector`), removido em seguida.
O `adb input text` não injeta `§`/acentos (NPE no `KeyCharacterMap`), então o teste puro
foi a única forma de não invalidar o resultado.

**Texto testado:**
```
Ter esperança é o segredo para o contentamento (10 min)
Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)
Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)
Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
```

## Resultado do parser

| Verificação | Resultado |
|---|---|
| `splitTitle` | ✅ `Ter esperança é o segredo para o contentamento (10 min)` |
| `PastedOutlineAnalyzer.candidates` | ✅ **3 candidatos** (as 3 frases), nível 0, sugeridos |
| `PastedOutlineAnalyzer.bodies` | ❌ **3 corpos VAZIOS** (nenhum nível > 0 para coletar) |
| `RefDetector.detect` | ✅ **3 refs exatas**: `w25.07 22 §§ 8-9` (p.22 §8-9), `20 § 2` (p.20 §2), `24 §§ 13-14` (p.24 §13-14) |
| `RefDetector.detectBible` | ✅ **2**: Jeremias 45:3 e 45:5 |
| `S34Detector.signals` | `{BIBLE_REFS, PUB_REFS}` |
| `S34Detector.isS34` | ❌ **false** (sem marcador “S-34” no texto) |
| `S34Parser.parseS34` (estrito) | 1 seção (`min=10`), **5 refs**, título vazio — **não** 3 pontos |

## O que o app faz com isso

`CopilotViewModel.createNoteFromPasted`:

```kotlin
val (titled, rest) = PastedOutlineAnalyzer.splitTitle(normalized)
val (cands, dropped) = PastedOutlineAnalyzer.candidates(rest.ifBlank { normalized })
val bodies = PastedOutlineAnalyzer.bodies(cands)   // <- 3 vazios
_draft.value = cands.mapIndexed { i, c ->
    DraftSection(c.text, null, c.suggested, bodies.getOrNull(i).orEmpty(), c.level)
}
_draftTotal.value = null
```

- O **título/tema** fica `Ter esperança… (10 min)` (com o tempo dentro do título).
- Os **3 pontos** viram **3 seções com o título = a frase inteira** e **corpo vazio**.
- `_draftTotal = null` — o “10 min” do título **não** vira o tempo total do rascunho.
- O tipo (“Discurso ou demonstração”) é o **toggle da planilha**, não o parser.

## Veredito

**FALHOU** no formato de 4 linhas (o “formato real” da apostila):

| Critério | Status |
|---|---|
| Tema | ✅ (com o tempo no título) |
| Tempo | 🟡 no título; **total do rascunho = null** |
| 3 pontos | 🟡 3 candidatos, **sem corpo** |
| Texto dos pontos + refs | 🟡 as frases+refs estão no **título**, não no corpo |
| 3 refs `w25.07` exatas | ✅ |
| Textos bíblicos (Jer. 45:3/45:5) | ✅ |
| Nota ~90–100 palavras | ❌ **0 palavras** (corpos vazios) |
| Tipo “Discurso” | ❌ `S34Detector` false; o tipo é o toggle da UI (o detector é **dead code**) |

**Isso explica a nota “Ter esperança” com 0 palavras:** a colagem não falhou; o parser
criou 3 **tópicos** com **corpo vazio**. O editor conta o corpo → 0.

## Causa raiz

O `PastedOutlineAnalyzer` trata cada frase como **candidato a tópico** (título) e só
coleta corpo de candidatos com **nível maior** (`bodies`: `cands[j].level > c.level`).
No formato de 4 linhas, as 3 frases são **os pontos**, não títulos de tópicos; todas
têm nível 0 → corpo vazio. O `S34Parser` estrito, por sua vez, espera `Tema:` e pontos
numerados (`1.`, `2.`), que o texto não tem.

## Fix necessário antes da re-simulação (não implementado)

1. **Mapear as frases-ponto para corpo**, não para título vazio: se o “resto” após o
   título são frases completas (com ponto/refs), tratar como **conteúdo de uma seção**
   (ou como pontos com o texto no corpo), não como tópicos vazios.
2. **Extrair o tempo do título** `(10 min)` para `_draftTotal` (o `S34Parser` já lê
   `min=10`; o fluxo de colar ignora).
3. **Tipo**: decidir se o paste usa o `S34Detector` (hoje dead code) ou o toggle da
   planilha; hoje o “Discurso” é escolha do usuário, não classificação.
4. **Formato**: documentar se o app aceita o resumo de 4 linhas ou exige o S-34 oficial
   com pontos numerados. O teste do copilot fica bloqueado até o item 1.

## Conclusão

- **Não rodei a re-simulação** (o protocolo manda parar quando o parse falha).
- **T1/T4 (router)** continuam como achados da simulação anterior; o **parser** é o
  bloqueio atual e deve vir **antes** do v2.7.5 de router/hint.
- **Nenhum código de produção alterado.** O teste temporário foi removido; `git status`
  só tem os relatórios em `docs/reports/`. A planilha “Apostila TESTE” foi fechada sem
  criar nota (0 ocorrências); a nota “Simulação TESOUROS” permanece no tablet.
