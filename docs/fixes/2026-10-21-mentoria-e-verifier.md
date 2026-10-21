# Fix 1 + Fix 2 — S-34 vinculado + falso positivo do verifier (2026-10-21)

Origem: A/B Copilot v2.5.0 vs Gemini (`docs/experiments/2026-10-21-copilot-vs-gemini.md`).
Dois desvios de confiança observados pelo usuário no device:
1. o chat avisava "sem esboço S-34 vinculado" mesmo com a nota estruturada;
2. o chat avisava "Revise: 1 citação" em cima de uma citação **literal e correta**.

---

## Fix 1 — S-34 vinculado (o que a evidência realmente mostrou)

### Causa raiz (Discovery, read-only)

| Fato | Evidência |
|---|---|
| `s34_outlines` **não tem `noteId`** | `AppDatabase.kt:511-522` — colunas: `id, sourceAttachmentId, symbol, title, objective, headerLinesJson, createdAt, updatedAt, parserVersion`; índice só por `sourceAttachmentId` |
| `s34_outlines` representa o **arquivo S-34 importado**, não a nota | só há 3 linhas na base, todas ligadas a anexos `S-34_T_*.jwpub`; o consumidor é `S34StructuralRetriever` via `outlineBySource` (`AppDatabase.kt:594`) |
| Nota colada **não tem** linha em `s34_outlines` nem em `outlines` | consulta na base do A34: `outlines` (por noteId) vazia para a nota; nenhum anexo vinculado |
| A rota do chat usa **só** o S-34 importado | `structuralFor` (`CopilotViewModel.kt:2019-2070`) → `retriever.retrieve(sourceAttachmentId = …)`; sem anexo S-34 → `null to null` |
| **Mas a estrutura da nota está no dossiê** | `DossierPromptBuilder` monta "## ESTRUTURA DO DISCURSO", "## SEÇÃO ATUAL" e "## LINHA DE RACIOCÍNIO" a partir de `speech_sections`/`sub_points` — e o prompt capturado no A34 mostra exatamente isso |

**Conclusão: a premissa do plano estava errada.** O chat **não** perdia o
contexto estrutural: ele o recebia pelo dossiê (e o usou de verdade — no A/B
identificou os 3 movimentos, os tempos e as refs). O que existia era:

1. um **aviso falso** ("o texto pode não seguir a estrutura original") numa
   resposta que HAVIA seguido a estrutura da nota; e
2. a **busca do RAG sem os títulos das seções** (só o S-34 importado os
   fornecia) → o app "não via" as partes ("ponto 2").

Adicionar `noteId` em `s34_outlines` exigiria **migração de DB** (o plano
manda parar nesse caso) **e** popular `s34_sections`/`s34_subsections`/
`s34_references` a partir do paste — mapeamento com perda (o S-34 tem
seções/lições; a nota tem INTRO/BODY/CONCLUSION). **Não foi feito**; fica
como decisão do usuário.

### Solução aplicada (sem migração)

| Mudança | Arquivo |
|---|---|
| O aviso passa a dizer a verdade: "_O arquivo S-34 não está vinculado — usei a estrutura do seu esboço na nota._" | `CopilotViewModel.kt` (`NO_S34_WARNING`) |
| A query do RAG passa a usar os **títulos das seções da nota** quando não há S-34 importado | `CopilotViewModel.kt` (`s34SectionTitlesForRetrieval`) |

### Testes

`S34WarningHonestyTest` (4): o aviso não acusa a estrutura que o app usa ·
não duplica · aparece quando a resposta está vazia · a fala do usuário é fonte.

### Validação A34

- Aviso novo no device: "_⚠️ O arquivo S-34 não está vinculado — usei a estrutura do seu esboço na nota._" ✓
- A resposta continuou citando os 3 movimentos e as refs ✓

---

## Fix 2 — Falso positivo do verifier

### Causa raiz (Discovery, read-only)

O verificador comparava a citação com o corpus **entrada por entrada**:

```kotlin
corpus.none { it.contains(normalize(quote)) }   // GroundednessVerifier
```

E o corpus do chat é **partido** (uma entrada por passagem/frase do acervo,
`verificationCorpus` + o dossiê + as refs da pergunta). Três consequências
medidas no A/B (A34, nota "Ter esperança…"):

| Citação do app | Motivo do falso sinal |
|---|---|
| "Em que armadilha Baruque caiu **Baruque, o secretário do profeta Jeremias…**" | atravessa **duas** entradas do corpus (título do § + corpo) — não cabia em nenhuma |
| "aonde quer que você for, eu lhe darei a sua vida como despojo." | no acervo é "…como despojo**'.**" — a pontuação original quebrava o casamento |
| "Baruque perdeu prestígio social" | era a **hipótese do próprio usuário**, citada de volta para ser refutada — e a fala do usuário não estava no corpus |

### Solução aplicada

| Mudança | Efeito |
|---|---|
| `normalizeForComparison` (nova, pura): mesma normalização dos dois lados, **sem pontuação/aspas** | "despojo'." ≡ "despojo." · "Jeová" ≡ "Jeova" (o acervo é citado sem acento) · "Deus" ≠ "Jeová" (precisão doutrinária preservada) |
| A citação é comparada contra o corpus **corrido** (`corpus.joinToString(" ")`) | citação que atravessa duas entradas passa a ser reconhecida |
| A **fala do usuário** + o **histórico do usuário** + o **rótulo das refs citadas** entram no corpus | citar a hipótese do usuário para refutá-la não é inventar |

`warnOnly = true` (o chat nunca remove texto) e as regras de
ref/verso/número **permanecem inalteradas** — a detecção de invenção não foi
afrouxada.

### Testes

`GroundednessQuoteNormalizationTest` (7): normalização tira pontuação · colapsa
espaços · mantém as letras · **citação que atravessa dois trechos não é
sinalizada** · citação com pontuação diferente não é sinalizada · **citação
inventada continua sinalizada (regressão)** · hipótese do usuário não é
invenção.

### Validação A34

| Teste | Resultado |
|---|---|
| "O que diz exatamente o parágrafo 8 da página 22 da Sentinela? Cite o texto literal." | citação literal correta, **sem aviso "Revise"** ✓ |
| "Baruque era filho do profeta Ezequiel? Isso está na Bíblia?" | o app refutou ("a Bíblia não diz") — **nenhum aviso falso** ✓ |
| Nota **sem acervo**: "Cite a palavra exata do versículo que fala do contentamento." | o app **recusou inventar** ("não tenho como te dar a palavra exata… e eu não vou inventar citação") ✓ |
| Regressão (unidade): citação inventada | **continua sinalizada** ✓ (teste verde) |

---

## Estado final

- **Testes:** 2169/0 (2158 + 11 novos)
- **Commits:** `831c0c1` (verifier) · `32e2d3b` (S-34/aviso) — atômicos
- **Branch:** `fix/mentoria-e-verifier` → mergeado em `main` como **v2.5.1**
- **Não tocado:** `SyncWorker`, `PastePreviewSheet`, `PublicationCatalog`,
  `texturedBackground`, `GroundednessVerifier` (só a comparação de citação —
  o resto do verificador intacto)

### Recomendação

**Merge: sim** (validado no A34, 3/3). A **migração** do `s34_outlines` com
`noteId` fica para depois: ela é grande (schema + 3 tabelas filhas) e o
benefício real — a estrutura da nota — já chega ao modelo pelo dossiê.
