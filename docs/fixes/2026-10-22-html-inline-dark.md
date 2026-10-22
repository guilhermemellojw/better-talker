# HTML inline escuro + contraste de elementos compostos (2026-10-22)

Origem: dois relatos do usuário — as **referências do esboço quase invisíveis no
dark mode** (chat do Copilot e card "Orientações do Orador").

---

## 1. Discovery — o que é bug e o que não é

| Onde | Onde é renderizado | Conteúdo | Veredito |
|---|---|---|---|
| **"Orientações do orador"** | **EDITOR** — `EditorScreen.kt:769` → `SpeakerNotesBlock` (origem: `outlines.sectionsJson` → `OutlineParser.preambleOf`) | **texto puro** (sem HTML, sem cor) | ❌ **não é bug de contraste** — `onSurfaceVariant` sobre `surfaceVariant@0,4` = **6,74:1** ✓ AA |
| **refs do esboço (chat)** | `ChatScreen.kt:978` → `RefsBody` → `RefFullRow` | texto puro | ❌ **não é bug** — medido no A34: 9,2–12,5:1 ✓ |
| **corpo da seção no editor** | `SectionCardEditor.kt:939` `BasicRichTextEditor` (richeditor-compose 1.0.0-rc14) | **HTML com `style="color: rgba(...)"` inline** | 🚨 **BUG REAL** — o roxo do S-34 = **2,13:1** no dark |
| **HTML colado no chat** | `MarkdownBlocks.kt:197` `buildInline` (só markdown) | HTML inline | 🚨 **BUG REAL** — a tag aparecia **crua** ("<span style=…>") |

### Evidência (HTML real, banco do A34)

```html
<p><span style="color: rgba(123, 31, 162, 1.0); font-size: 18.0px;">Voc&ecirc;</span> j&aacute; se perguntou como Jeov&aacute; nos protege individualmente&quest; ...</p>
<p><span style="color: rgba(245, 124, 0, 1.0); font-size: 24.0px;"><b>Vamos</b></span> come&ccedil;ar com uma pergunta simples&period; ...</p>
```

- `rgba(123,31,162)` (roxo) sobre a surface escura = **2,13:1** → invisível.
- `rgba(245,124,0)` (laranja) = legível.
- A origem não é o import do S-34 (`readDocx`/`JwpubExtractor` já produzem **texto
  puro**): é o **paste direto no rich editor** (o richeditor grava o HTML do
  clipboard com os spans) → `speech_sections.contentHtml`.

### Por que o `a198b54` não cobria

`a198b54` pôs `onSurface` no `textStyle` **base** do rich editor
(`SectionCardEditor.kt:937-946`). A lib aplica a cor **do span por cima** — o
`textStyle` só vale para o texto sem cor própria. Daí o roxo sobreviver (invisível).

---

## 2. O que foi implementado (3 commits)

### Commit 1 — `fix(ui): sanitiza HTML inline escuro (editor + chat)`

- `HtmlInlineSanitizer.sanitizeRichHtmlForTheme(html, dark, fallbackArgb)`:
  no dark, cor inline **escura** (luminância < 0,30) → cor do texto do tema; no
  **light, o HTML fica intacto** (o destaque do S-34 sobrevive).
  Aplicado na **leitura** do `contentHtml` salvo → **sem migração de DB** (as
  notas antigas são reparadas no render).
- `HtmlInlineSanitizer.htmlInlineToMarkdown(text)`: o chat é markdown-only e
  mostrava a tag crua → agora converte `<b>/<i>/<p>/<br>` e entidades
  (Latin-1 + numéricas) em markdown/texto.
- 5 testes com o **HTML real do S-34**.

### Commit 2 — `fix(a11y): camada 2 do ContrastAuditTest + 3 bugs de contraste`

O `ContrastAuditTest` (camada 1) audita 13 pares de **tokens** contra
`surface` — não cobre elemento composto, alpha em texto, fundo com alpha, cor
hardcoded nem a **textura**. A nova `ContrastAuditCompostosTest` audita **18
elementos compostos × 27 paletas × 2 modos × 2 cenários de textura**
(sem textura / com a textura no máximo). Texto exige 4,5:1; ícone 3:1
(WCAG 1.4.11).

Bugs corrigidos (medidos):

| Elemento | Antes | Correção |
|---|---|---|
| "Pensando…" (pulso com alpha até 0,4) | **1,83:1** | texto sem alpha (o pulso fica no ícone) |
| check do raciocínio (`Color(0xFF2E7D32)`) | **2,74:1** (dark) | `onSurfaceVariant` (o `primary` amarelo dava 1,50:1 no light) |
| placeholder do editor (alpha 0,5) | **2,29:1** | `onSurfaceVariant` puro |
| textura de fundo em 100% | fundo = cor da tinta → texto 1:1 | teto `MAX_TEXTURE_ALPHA = 0,15` (textura segue visível, texto sempre legível) |

### Commit 3 — este relatório

---

## 3. Validação no A34 (por pixel, tema escuro)

| Cenário | Antes | Depois |
|---|---|---|
| Corpo da seção com o span roxo (dark) | 2,13:1 (invisível) | **9,00:1** (`onSurface`) |
| A mesma nota no **light** | roxo 6,69:1 | **roxo preservado** 6,69:1 ✓ |
| Mensagem do chat com o HTML inline | tag crua à mostra | "Você já se perguntou como Jeová nos protege individualmente?…" (limpo, entidades decodificadas) ✓ |

---

## 4. Decisões

| # | Decisão | Motivo |
|---|---|---|
| 1 | **Sanitizar na leitura, sem migração** | 1 função pura + rerender; zero risco de perda de HTML; as notas antigas se reparam sozinhas |
| 2 | Normalizar **só as cores escuras**, e **só no dark** | o destaque do S-34 (roxo/laranja) é intencional no light |
| 3 | No chat, **converter** o HTML inline (não descartar) | o texto continua útil; a tag some |
| 4 | Ícone = 3:1 (não 4,5) | WCAG 1.4.11: 3:1 para elemento gráfico |
| 5 | Teto da textura em 15% | acima disso o fundo vira a cor da tinta e o texto sobre ele some |
| 6 | Manter `onSurface` sobre `secondaryContainer` (bola do usuário, painel "Prepare o Copilot") | passa (7,89:1); é dívida semântica, não bug — fica no backlog |

## 5. Backlog (fora do escopo)

- `onSurface` → `onSecondaryContainer` nos 2 lugares com `secondaryContainer` (dívida).
- Granularidade do acervo (parágrafo → frase) — 1-2 dias.
- `PublicationCatalog` / Web Search — 1-2 dias.
- Migração `s34_outlines.noteId` — **descartada** (o dossiê já entrega a estrutura).


---

## 6. Segundo achado (mesma data, pós-v2.5.2) — o `LocalContentColor` era **preto**

O usuário apontou o **título** do card "Orientações do orador" e o **título
"Referências da nota"** (chat) como quase invisíveis. Medi por pixel no A34:

| Elemento | Antes | Depois |
|---|---|---|
| "Orientações do orador" (título, editor) | **1,00:1** (preto sobre o card) | **12,83:1** |
| "Referências da nota" (título, chat) | **1,00:1** | **14,08:1** |

**Causa-raiz (sistêmica, não do card):** o Compose usa `Color.Black` como
`LocalContentColor` **padrão**. As 10 telas do app usam
`Scaffold(containerColor = Color.Transparent)` (para a textura do fundo
aparecer) e `Surface` com cor translúcida/custom (`surfaceVariant@0,4`) —
nenhum define content color (`contentColorFor` de uma cor custom/transparente
retorna `Unspecified`). Resultado: **todo texto sem cor explícita** nessas
telas ficava **preto no dark**.

**Fix (sistêmico, 1 lugar):** `Theme.kt` passa a prover
`LocalContentColor = scheme.onSurface` no `MaterialTheme`. Os `Surface` com
token continuam sobrescrevendo (`onSurface`, `onSecondaryContainer`…) e os
`Text(color = …)` explícitos continuam mandando. No light, `onSurface` ≈ preto
(sem mudança visual).

+ o título do card ganhou cor explícita (o componente fica legível por si) e um
**teste-guarda** (`ThemeContentColorGuardTest`) trava a invariante.

> Correção honesta: na primeira auditoria eu medi "Referências da nota" 1,00:1
> e tratei como artefato da amostragem de pixels. **Era o bug.**
