# Correções P0/P1 — DeepSeek fallback + RTF (2026-10-05)

## Commits
- **T1 — DeepSeek fallback:** `af522ab` + `45400da` (complemento: structured sem thinking)
- **T2 — RTF no picker:** `191ea5e`
- **Topo verde:** `45400da` — Build Android APK ✅ + Deploy Pages ✅

## Testes JVM
- **1296 passed / 0 failed** (T1: +7; T2: +1).

## T1 — DeepSeek `json_schema` fallback

### Código (`DeepSeekProvider.kt`)
- Cascata **específica** (só rejeição de `response_format`; 400 genérico propaga):
  1. `response_format: {type: json_schema}` (atual)
  2. → `{type: json_object}` + prompt "Sua resposta deve ser um objeto JSON válido."
  3. → sem `response_format` + prompt "Responda APENAS com JSON válido…" + extração
     do primeiro objeto `{...}` balanceado (parse tolerante no provider).
- **Cache de sessão** (`jsonSchemaRejected`): depois da 1ª rejeição, a próxima
  chamada já começa em `json_object` (sem repetir a tentativa que falha).
- Fallback **não consome** o orçamento de retry transitório.
- **Structured sem thinking**: requisições estruturadas (oratória/proposta) usam
  `reasoning_effort: "none"`; chat mantém `"low"`.

### Diagnóstico na API real (curl, prompt de teste)
- `json_object` funciona; com `reasoning_effort` low/minimal o modelo gastou
  **600–700+ tokens de reasoning** e devolveu `content` vazio (`finish=length`)
  nos prompts longos → resposta "Não consegui gerar…".
- Com `reasoning_effort: "none"`: **0 tokens de reasoning**, JSON direto.

### Validação no device (A34)
- **Oratória** "Desenvolva o ponto 1": log
  `deepseek 400 response_format indisponível; fallback=JSON_OBJECT` →
  `oratória: aviso de fidelidade (invented=0 leaked=0 unsupported=1)` → cartão
  **Proposta/ANTES/DEPOIS · 💡 Sugestão criativa** com texto completo. ✅
- **Criar proposta** (seção ativa): `proposal context ok focus=36` →
  `proposal generated len=261` — já **direto no `json_object`** (cache ativo),
  sem 400 → cartão Proposta/ANTES/DEPOIS/Verificar/Aceitar/Rejeitar. ✅
- Obs: no cartão da oratória, "Aceitar" caiu na trava de obsolescência
  ("o bloco mudou depois da geração") — comportamento existente, nota intacta.

## T2 — RTF no picker

- `OUTLINE_MIMES` ganhou `application/rtf` e `text/rtf` (DOCX/PDF/octet-stream
  inalterados) + teste de regressão.
- **Device:** o picker agora **permite selecionar** `S-34_T_194.rtf` (antes o
  toque não selecionava; agora o picker fecha e entrega a URI). ✅

### Achado novo (P1) — pipeline de import ignora RTF
- `OutlineRepository.previewFile` rejeita `DocKind.RTF`
  ("Esboço precisa ser DOCX, PDF ou JWPUB") e `persistS34FromImport`
  (`CopilotViewModel`) só aceita DOCX/PDF/JWPUB.
- A falha é **silenciosa** (catch sem UI em `importOutlineFile`): o usuário
  seleciona o RTF e nada acontece.
- `DocExtractors` já extrai RTF (`stripRtf`) — falta liberar o kind nos dois
  pontos + surfacing do erro. **Fora dos arquivos do T2 — reportado, não
  alterado.**

## CI
- Commits `af522ab`/`191ea5e`: jobs **cancelados na fila sem runner** (infra
  do GitHub Actions; mesma árvore passa no topo). Topo `45400da`: Build ✅ +
  Deploy ✅.

## Estado
- Correções P0/P1: **fechadas no código/testes e validadas no device**.
- Fica aberto: import RTF end-to-end (P1, arquivos fora do escopo do T2).
