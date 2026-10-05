# Validação Física F20-F1 — A34 (2026-10-05)

## Ambiente
- **Device:** SM-A346M (a34x), ADB wireless (conectado)
- **APK:** HEAD `63c9533` — `assembleDebug` ✓ + `adb install -r` Success
- **Gemma:** presente — `gemma-4-E2B-it.litertlm` (2.588.147.712 bytes) em
  `/sdcard/Android/data/com.bettertalker.app/files/models/`
- **Chave DeepSeek:** ausente — `deepseek_api_key` não existe no DataStore
  (`settings.preferences_pb`)

## Resultado: BLOQUEADO — bug de UI impede configurar a chave DeepSeek

### Bug #1 (bloqueador) — ModelScreen sem rolagem; card DeepSeek inacessível
- **Sintoma:** em `Modelo IA`, o card “DeepSeek — chat de raciocínio (BYOD)”
  começa no fim da tela; o campo “Chave DeepSeek (sk-…)” e o botão
  “Salvar chave” ficam **abaixo da dobra**. É impossível adicionar a chave.
- **Evidência (A34, 1080×2340):**
  - `uiautomator dump`: **0** nós `scrollable="true"` na tela de Modelo IA.
  - Swipe para cima **não move** o conteúdo (card ainda em `y=1910` após o gesto).
  - Os nós “Chave DeepSeek” e “Salvar chave” **não existem** na árvore de acessibilidade.
- **Causa provável (código):** `ui/aimodel/ModelScreen.kt` — a `Column` raiz usa
  `Modifier.fillMaxSize().padding(pad).padding(16.dp)` **sem** `verticalScroll`;
  com os cards (Gemma + Provedor + DeepSeek + Gemini) o conteúdo excede a tela.
- **Correção sugerida (NÃO aplicada nesta validação):** adicionar
  `Modifier.verticalScroll(rememberScrollState())` à `Column` raiz da ModelScreen.

### Observação #2 — gate de RAM exibe 7,3 GB (< 8 GB) no A34
- O card Gemma mostra “RAM do aparelho: 7,3 GB — abaixo do mínimo de 8 GB”, mas
  o modelo está presente (via `adb push`) e o app segue. O gate
  `LlmConfig.MIN_RAM_BYTES = 8 GB` só afeta a UI de download — registrar para
  revisão (mínimo de 8 GB vs. RAM total reportada de 7,3 GB no A34).

## Blocos
- Blocos 1–7: **não executados** (bloqueados pelo Bug #1; a chave DeepSeek não
  pode ser configurada). Nada foi simulado.

## State
- Nenhuma alteração de código durante a validação (conforme regra).
- Screenshots/logs de apoio em `/tmp/opencode/` (não versionados).
