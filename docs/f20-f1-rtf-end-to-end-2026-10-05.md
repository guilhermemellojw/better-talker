# RTF End-to-End + Surfacing de Erro (2026-10-05)

## Commits
- **T1 — RTF no pipeline:** `8e19781` — Build Android APK ✅
- **T2 — Surfacing de erro:** `7dcbe03` — Build ✅ + Deploy Pages ✅ (topo)

## Testes JVM
- **1302 passed / 0 failed** (T1: +3; T2: +3).

## T1 — RTF no pipeline de import

### Código
- `DocExtractors.supports(kind)` vira a **fonte única** dos formatos extraíveis
  (DOCX, PDF, RTF, EPUB, ZIP, TXT, JWPUB) — evita selecionar formato que o
  pipeline descarta.
- `OutlineRepository.previewFile` e `persistS34FromImport` (CopilotViewModel)
  passam a usar `supports` (antes: só DOCX/PDF/JWPUB).
- Mensagem de não suportado: "Formato não suportado. Use DOCX, PDF, RTF ou JWPUB."

### Validação no device (A34, arquivo real)
- Importado **`S-34_T_194.rtf` direto pelo picker** (sem conversão):
  `S34Import: S34 detected … sections=5 references=21` →
  `outcome=Saved(outlineId=s34-b6631d57, sections=5, references=21)`.
- Attachment `S-34_T_194.rtf|rtf` criado; DB após: `s34_outlines=2`,
  `s34_sections=10`, `s34_references=42` (novo outline somado ao do DOCX). ✅
- DOCX/PDF: comportamento inalterado (testes + fluxo anterior).

## T2 — Surfacing de erro no import

### Código
- `importOutlineFile` não engole mais exceções: `ImportException` → mensagem
  humana no chat; falha inesperada → orientação genérica; cancelamento propaga.
- `persistS34FromImport`: cada motivo de skip ganha **log estruturado**
  (sem nota, kind, cópia, extração, não-S-34) e o `ParseFailed` do hook posta
  "Detectei um esboço S-34, mas não consegui extrair as seções. Verifique o
  formato.".
- Helpers puros `importFailureMessage` / `S34_PARSE_FAILURE_MESSAGE` + 3 testes.

### Validação no device (A34)
- Arquivo de formato desconhecido → chat recebeu
  **"Formato não suportado. Use DOCX, PDF, RTF ou JWPUB."** + log
  `Import: falha no import: reason=UNSUPPORTED`. ✅
- DOCX inválido (extração vazia) → chat recebeu
  **"Não extraí texto do arquivo (vazio ou protegido)."** + log
  `Import: falha no import: reason=IO`. ✅

## Limpeza
- Arquivos de teste (`esboco.xyz`, `esboco.bin`, `esboco2.docx`) removidos do
  device; cópia local da chave em /tmp destruída (`shred`).

## Estado
- RTF end-to-end: **fechado** (picker → preview → parse → s34_*).
- Erros de import: **visíveis** (mensagem + log estruturado).
- Nenhum catch silencioso no caminho de import.
