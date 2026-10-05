# Polimento Visual do Chat (2026-10-05)

## Commits
- **T1 — instrução + gate:** `bdf95c9` — Build ✅ + Deploy ✅
- **T2 — parser:** `3372858` — Build ✅ + Deploy ✅
- **T3 — link jw.org:** `9592285` + complemento `4a4886f` (espaço entre `]` e `(`)
- **T4 — parcial puro:** `8710615` — Build ✅ + Deploy ✅

## Testes JVM
- **1327 passed / 0 failed** (T1 +5; T2 +5; T3 +4; T4 +2).
- Nenhuma biblioteca externa adicionada.

## T1 — Instrução estilo ChatGPT + gate

### Código
- `CHAT_TAIL_INSTRUCTIONS` ganha a linha: abrir com 1 frase; `##` para seções;
  `-`/`1.` para listas; `**` em 1-3 termos por item; fechar com o próximo passo;
  sem código/tabelas/HTML.
- `GroundednessVerifier.normalize()` remove `*#>` antes de comparar — negrito
  DENTRO de citação válida não é mais podado; negrito em citação inventada
  continua sendo.

### Device
- O modelo passou a responder formatado: `## Três dicas…`, lista `1. 2. 3.`,
  `**negrito**` e fecho acionável ("Quer que eu monte…?"). ✅

## T2 — Parser

### Código
- Lista ordenada preserva o número (`MdBlock.Bullet(text, number)`); o chat
  renderiza `1.  texto` (antes virava `•`).
- Backtick vira `FontFamily.Monospace` (antes virava itálico).
- `#`/`##` = título grande; `###` = subtítulo menor.
- `buildInline` extraído puro para teste.

### Device
- Renderização confirmada: título sem `##`, marcador `1.  `, itens sem `**`. ✅

## T3 — Link clicável (jw.org)

### Código
- `[texto](url)` vira `LinkAnnotation.Url` apenas para `jw.org` e subdomínios
  (`wol.jw.org`); outros domínios ficam sublinhados e inertes.
- `isJwUrl` puro (rejeita `jw.org.evil.com`, `notjw.org`).
- Complemento: tolera espaço entre `]` e `(` (o modelo emitiu
  `[Site oficial] (https://www.jw.org/pt/)` na validação real).

### Device
- Link jw.org → **abriu o Chrome** (`com.android.chrome/…Main`). ✅
- Link de domínio externo → toque **não** abriu nada (app permaneceu em foco). ✅
- Seleção ao redor: `Text` com `LinkAnnotation` dentro do `SelectionContainer`
  mantém seleção normal (sem mudança de estrutura).

## T4 — Parcial puro (streaming)

- Confirmado no código: o parcial (`localPartial`) é renderizado direto em
  `Text`, **sem** `parseBlocks`/`buildInline`; o final passa pelo parser.
- Sem mudança de código; 2 testes defensivos (negrito/backtick incompletos não
  quebram nem perdem texto; título pela metade não explode).

## Fora de escopo preservado
- Badge 💡, avisos ⚠️ e cards 📖 inalterados (nenhum arquivo dessas camadas
  foi tocado).
- Sem biblioteca externa.

## Estado
- Polimento visual: **fechado** (código + testes + device).
- CI: verde em todos os commits (Build + Deploy).
