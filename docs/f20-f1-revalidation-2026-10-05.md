# Re-Validação F20-F1 — A34 (2026-10-05, sessão 2)

## Ambiente
- **Device:** SM-A346M (ADB wireless) — caiu no meio (ver Incidente)
- **APK:** `536f9f0` — `assembleDebug` ✓ + `adb install -r` Success
- **Chave DeepSeek:** presente e válida (`sk-`, 35 chars)
- **Modelo Gemma:** presente (2.588.147.712 bytes)
- **Provider inicial:** `deepseek` (explícito) → depois trocado para `auto`

## Bloco A — Bug #8 (S-34 persiste): FALHOU — Bug #9
- Import de `s34-35.docx` via chat: **draft criado** (`kind=draft`), mas
  **nenhum attachment novo** e **nenhum log do `S34ImportHook`**.
- Tabelas após o import: `s34_outlines=0`, `s34_sections=0`,
  `s34_subsections=0`, `s34_references=0`.
- **Causa (Bug #9):** `S34Detector.isS34` exige o **literal “S-34” no texto**
  (`MARKER_RE = \bS[-\s]?34\b`); o arquivo real começa com “N.º 35 …” e **não
  contém o literal** → o hook nunca roda, mesmo com o fix `ea7b2e3`.
  O teste JVM passou porque a fixture sintética contém “S-34”.
- **Correção sugerida (não aplicada):** no caminho de import, usar o **nome do
  arquivo** como sinal (ex.: `S34Detector.isS34(raw, fileName)` aceitando
  `s34*`/`S-34*` no nome), ou relaxar o portão do marcador quando o filename
  indica S-34. Registrar como P0 (bloqueia o fluxo S-34 estruturado).

## Bloco B — Bug #6 (sonda): PASSOU
- “Testar conexão”: `Testando…` → **Online** (~3–5 s).
- Log confirma o novo tratamento: `deepseek vazio por length; retry com
  budget=128` (a sonda com 64 tokens sofreu o esvaziamento e o retry resolveu).

## Bloco C — Bug #7 (cards com provider ativo): PASSOU
- Com DeepSeek ativo, “Quais referências o esboço cita?” postou **mensagem
  `kind=refs`** (intercept funcionou).
- **Card `📖 No acervo` apareceu** na nota “Tenha o ponto de vista correto…”
  (ref `jr 189 § 16`): “✅ Jeremias (estudo 189) / No arquivo: jr_T.epub /
  📖 No acervo: Jeremias (jr-T)”.
- **Card `⚠️ citação sem fonte no acervo` apareceu** na nota N.º 35
  (Despertai! 8/2013).
- Observação: uma resposta vazia do DeepSeek ainda ocorreu (“Não consegui
  gerar”) — o retry com orçamento maior mitiga, mas não elimina.

## Bloco D — Fallback (auto): PASSOU (3/4 casos)
- Caso 1 (DeepSeek disponível): **auto → remoto** (origin COPILOT, sem fase
  local) ✅.
- Caso 2 (sem chave): **não executável** sem remover/recriar a chave do dono;
  coberto por testes JVM e pelo caso 3 (caminho local comprovado).
- Caso 3 (offline): modo avião + Wi-Fi off, ping “Network is unreachable” →
  **Gemma local** (`gemma_local ok load=51415ms ttft=9210ms gen=9314ms`) ✅.
- Caso 4 (restaurado): rede de volta → **DeepSeek** (nenhum log de
  `gemma_local`; resposta em segundos) ✅.

## Bloco E — Proposta F5 + Criação: BLOQUEADO (Bug #10)
- A resposta com criação marcada aparece com **💡 Sugestão criativa** no chat.
- “Criar proposta” **sempre** responde “Selecione um trecho ou abra um bloco
  para propor uma alteração.” mesmo com a nota aberta e a seção visível:
  `_activeBlockTitle` é **campo morto** (nunca setado) e o fallback
  `notes.mdText` é **apenas newlines** (notas por seções). Sem seleção manual,
  o fluxo é inutilizável.
- Badge 💡 no DEPOIS não observado (o caminho de criação INSERT/oratória
  também depende do S-34 estruturado — Bug #9).

## Bloco F — Estabilidade: PASSOU
- `force-stop` + reabertura: app voltou (pid novo), **chave preservada**
  (`sk-`, provider `auto`), **modelo Gemma preservado**, **conversa preservada**
  (46 mensagens na nota N.º 35).
- **10 interações** no chat: 10 enviadas / 11 respostas / **0 erros de provider**,
  pid estável, **0 FATAL EXCEPTION / 0 ANR**.

## Bugs
- **Bug #9 (novo, P0):** detector S-34 exige literal no texto; arquivo real
  sem o literal não persiste `s34_*` (Bloco A).
- **Bug #10 (novo, P1):** foco do “Criar proposta” nunca é setado
  (`_activeBlockTitle` morto + `mdText` whitespace) → proposta inutilizável em
  notas por seções sem seleção manual (Bloco E; explica o Bloco 6 anterior).
- **Observação #11 (P2):** resposta vazia do DeepSeek ainda ocorre
  ocasionalmente mesmo com o retry de orçamento (1 caso hoje).
- Bugs #6 e #7: correções **confirmadas no device** (B e C passaram).

## Incidente — ADB caiu; retomada via USB
- A sessão caiu no meio (ADB wireless). O aparelho ficou em **modo avião com
  Wi-Fi desligado**; retomada via **USB** e rede restaurada.

## State
- Nenhum código alterado durante a validação.
- Re-validação: **B, C, D (3/4) e F passaram**; A falhou (Bug #9); E bloqueado
  (Bug #10).
