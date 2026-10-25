# Validação no Redmi 9 — 1b (V&M) + importação S-34 real

**Data:** 2026-10-25
**Aparelho:** Redmi 9 (M2004J19C / `lancelot`), Android 12 (SDK 31), arm64-v8a, 1080×2340
**ADB:** `adb-93a3ce310404-CQoRDc._adb-tls-connect._tcp` (Wi-Fi)
**APK instalado:** `android/app/build/outputs/apk/debug/app-debug.apk`, build de 2026-10-25 10:06
**Versão:** 2.7.4 (versionCode 24)
**Branch:** `feat/copilot-citations` @ `b6c839b` (`feat(vm): formatos reais sem número e consideração rejeitada`)
**Testes JVM no checkout:** 2.249 `@Test` (265 arquivos)

> O APK foi compilado às 10:06, antes do commit `b6c839b` (10:59), mas já continha o
> working tree da 1b. O comportamento observado corresponde ao commit.

## 1. Resultado executivo

| Fluxo | Resultado |
|---|---|
| V&M — apostila real de 4 linhas (com `§` e acentos) | ✅ `V&M · Discurso`, 3 pontos, 5 cards, 10 min |
| V&M — demonstração sem número de parte | ✅ `V&M · Demonstração`, cenário + referência + 1 card |
| V&M — consideração (“O que você diria?”) | ✅ rejeitada com 0 cards e mensagem explícita |
| Falsos positivos (“Minha nota…”, “Discurso: …”) | ✅ continuam `Texto livre` |
| Importação S-34 real (DOCX) | ✅ 4 seções, 30 min, 75 referências estruturadas |
| Estrutura criada na nota | ✅ INTRO + 4 BODY + CONCLUSÃO, com subtópicos e refs |
| “Copilot no ponto” no S-34 | ⚠️ não acessível no estado importado (ver 4.4) |

## 2. Textos e arquivos usados

### 2.1 Apostila V&M (4 linhas, texto exato)

```text
Ter esperança é o segredo para o contentamento (10 min)
Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)
Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)
Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
```

Preenchimento do campo via `ClipboardManager` (processo `app_process` como shell;
sem instalar IME/helper). O sheet só lê o clipboard na primeira composição — foi
necessário `force-stop` + reabrir o app entre os casos.

### 2.2 S-34 real

`/home/guilherme/Downloads/S-34_T_003.docx` (17.170 bytes)
“VOCÊ ESTÁ AVANÇANDO COM A ORGANIZAÇÃO UNIDA DE JEOVÁ?”
4 seções: 6 + 10 + 8 + 6 = 30 min.

## 3. V&M — evidências

### 3.1 Apostila de 4 linhas

Preview (Home → FAB → Colar parte da reunião V&M):

```text
V&M · Discurso
Título: Ter esperança é o segredo para o contentamento
3 tópicos de desenvolvimento
Bíblia: Je 45:3, Je 45:5
Publicações: w25.07 p. 22 §8; w25.07 p. 20 §2; w25.07 p. 24 §13
Cards: 5 cards
Título da nota: Ter esperança é o segredo para o contentamento
Duração: 10
```

Nota criada (`note-514e764a-…`):

| Ordem | Papel | Título | Min | Conteúdo |
|---|---|---|---|---|
| 0 | INTRO | Introdução | 1 | vazio |
| 1 | BODY | Ponto 1 | 4 | frase 1 + Jer. 45:3 + w25.07 22 §§ 8-9 |
| 2 | BODY | Ponto 2 | 4 | frase 2 + w25.07 20 § 2 |
| 3 | BODY | Ponto 3 | 4 | frase 3 + Jer. 45:5 + w25.07 24 §§ 13-14 |
| 4 | CONCLUSION | Conclusão | 1 | vazio |

- Contador do editor: **49 palavras** (os 3 mini discursos).
- Refs bíblicas/publicações vinculadas aos subpontos/conteúdo. ✅

### 3.2 Demonstração sem número

Texto: `Cultivando o interesse (4 min) DE CASA EM CASA. Fale sobre a importância… (lmd lição 7 ponto 4)`

```text
V&M · Demonstração
Título: Cultivando o interesse
Cenário: De casa em casa
Referência: lmd lição 7 ponto 4
Cards: 1 card (diálogo)
```

Nota criada: 1 BODY de 4 min; título do card = cenário + descrição; corpo vazio
para o diálogo. ✅

### 3.3 Consideração rejeitada

Texto: `O que você diria? (5 min) CONSIDERAÇÃO… (lmd lição 9)`

```text
V&M · sem parte aplicável
Cards: 0 cards
A parte 'O que você diria?' (consideração) não é suportada como discurso ou
demonstração. Use 'Criar esboço do zero' se quiser preparar uma nota manualmente.
```

✅ Decisão de descartar a consideração implementada e comunicada.

### 3.4 Falsos positivos

| Texto | Preview |
|---|---|
| `Minha nota (4 min) lmd lição 2 ponto 3` | `Texto livre` / `Sem V&M/S-34 detectado` / `1 card (Corpo)` |
| `Discurso: Seja corajoso (5 min) Corpo do discurso.` | `Texto livre` / `Sem V&M/S-34 detectado` / `1 card (Corpo)` |

✅ A whitelist/guards não sequestram texto livre.

## 4. S-34 real — evidências

### 4.1 Parser estrutural (`s34_outlines` / `s34_sections`)

| Posição | Título | Min |
|---|---|---|
| 1 | JEOVÁ É UM DEUS DE ORGANIZAÇÃO | 6 |
| 2 | A ORGANIZAÇÃO DE JEOVÁ É UNIDA | 10 |
| 3 | A ORGANIZAÇÃO DE JEOVÁ ESTÁ SEMPRE AVANÇANDO | 8 |
| 4 | CONTINUE AVANÇANDO COM A ORGANIZAÇÃO DE JEOVÁ | 6 |

- `symbol = S-34`, `parserVersion = 2`, `sourceAttachmentId` preenchido.
- **75 referências** estruturadas: 67 `BIBLE` + 8 `PUBLICATION`.
- Exemplos: `Jó 38:31-33`, `Sal 103:20,21`, `Da 7:10`, `Ef 1:8-10`,
  `w25.07 22 §§ 8-9` (quando presente), `w11 1/7 26-28`.

### 4.2 Estrutura da nota (`speech_sections` / `sub_points`)

| Ordem | Papel | Título | Min | Subpontos |
|---|---|---|---|---|
| 0 | INTRO | Introdução | 1 | — |
| 1 | BODY | JEOVÁ É UM DEUS DE ORGANIZAÇÃO | 6 | 4 |
| 2 | BODY | A ORGANIZAÇÃO DE JEOVÁ É UNIDA | 10 | 11 |
| 3 | BODY | A ORGANIZAÇÃO DE JEOVÁ ESTÁ SEMPRE AVANÇANDO | 8 | 6 |
| 4 | BODY | CONTINUE AVANÇANDO COM A ORGANIZAÇÃO DE JEOVÁ | 6 | 10 |
| 5 | CONCLUSION | Conclusão | 1 | — |

- 31 subpontos no total; refs bíblicas/publicações gravadas por subponto. ✅
- Título da nota = título do S-34; “Orientações do orador” presente; tipo
  `S34_DISCOURSE`. ✅
- Gate de ≥2 seções: passou com 4. ✅

### 4.3 Achados do S-34 (backlog Fase 2)

| # | Achado | Evidência | Impacto |
|---|---|---|---|
| A1 | Rodapé virou subponto | “N.° 3-T 9/15 A SER ABRANGIDO EM 30 MINUTOS” é o 10.º subponto da seção 4 | ruído no Copilot/índice |
| A2 | Espaços inseridos em palavras | “E m contraste”, “J eová”, “C rescimento”, “O s que” | texto corrompido em 4+ subpontos |
| A3 | Tempo total da nota = 32 min | 1 + 6+10+8+6 + 1; S-34 declara 30 | decisão: intro/conclusão devem consumir 0 ou redistribuir |
| A4 | “0 palavras” com 31 subpontos | WordCount só conta `contentHtml` ou `developedHtml`; S-34 usa `outlineText` | métrica enganosa no editor |
| A5 | Sem entrada visível do Copilot | ✨ só no card ativo; subpontos S-34 não ativam editor | “Copilot no ponto” inacessível no S-34 recém-importado |

> A1/A2 são do extrator/parser (Fase 2); não foram corrigidos nesta rodada.
> A3/A4/A5 são decisões de produto/UX.

### 4.4 “Copilot no ponto”

O rodapé do BODY mostra `10 min`, reordenar e menu, mas **não** o ✨
(`content-desc="Copilot deste ponto"`). O ✨ só é renderizado quando
`isActiveSection = true`; no S-34 importado os subpontos não abrem um
`RichTextEditor` ativo, então o card não se torna ativo. Resultado: não foi
possível abrir o Copilot a partir do ponto importado. O menu “Opções” da nota
também não oferece uma porta direta de chat.

## 5. Achado da 1b — título da nota V&M

O preview mostra `Título da nota` **sem** `(10 min)`, mas a nota criada
recebe `Ter esperança é o segredo para o contentamento (10 min)`.
Em `PasteNoteBuilder.kt` o VmTalk usa `noteTitle = "$partTitle ($minutes min)"`
(linha ~751), enquanto o preview usa o override sem minutos. O relatório
`regression-2026-10-23-n3-cards.md` já apontava esse comportamento como
pendente. **Decisão necessária:** manter o tempo no título da nota ou removê-lo
(como no preview).

## 6. Observações de método

- Sem build/instalação nesta rodada: usei o APK de 10:06 e o app já instalado.
- Nenhum arquivo do repositório foi alterado durante a validação.
- O clipboard foi injetado por um dex temporário em `/data/local/tmp/clip.dex`
  (processo shell, `app_process`); não houve instalação de IME/helper.
- Notas de teste criadas no aparelho (para inspeção/limpeza posterior):
  - “Ter esperança é o segredo para o contentamento (10 min)” (V&M talk)
  - “Cultivando o interesse (4 min)” (V&M demo)
  - “VOCÊ ESTÁ AVANÇANDO COM A ORGANIZAÇÃO UNIDA DE JEOVÁ?” (2 notas S-34;
    uma delas é duplicata de tentativa anterior)
  - “Nova nota” (criada por engano ao selecionar um JPG no picker)
