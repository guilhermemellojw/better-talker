# Validação Física F20-F1 — A34 (2026-10-05)

## Ambiente
- **Device:** SM-A346M (a34x), ADB wireless (conectado)
- **APK:** HEAD `63c9533` — `assembleDebug` ✓ + `adb install -r` Success
- **Gemma:** presente — `gemma-4-E2B-it.litertlm` (2.588.147.712 bytes) em
  `/sdcard/Android/data/com.bettertalker.app/files/models/`
- **Chave DeepSeek:** ausente — `deepseek_api_key` não existe no DataStore
  (`settings.preferences_pb`)

## Resultado: BLOQUEIO RESOLVIDO (Bug #1 corrigido e verificado no device)

### Bug #1 (bloqueador) — ModelScreen sem rolagem; card DeepSeek inacessível
- **Sintoma:** em `Modelo IA`, o card “DeepSeek — chat de raciocínio (BYOD)”
  começava no fim da tela; o campo “Chave DeepSeek (sk-…)” e o botão
  “Salvar chave” ficavam **abaixo da dobra**. Era impossível adicionar a chave.
- **Evidência original (A34, 1080×2340):**
  - `uiautomator dump`: **0** nós `scrollable="true"` na tela de Modelo IA.
  - Swipe para cima **não movia** o conteúdo (card em `y=1910` após o gesto).
  - Os nós “Chave DeepSeek” e “Salvar chave” **não existiam** na árvore de acessibilidade.
- **Causa:** `ui/aimodel/ModelScreen.kt` — `Column` raiz sem `verticalScroll`.
- **Correção aplicada (fora da validação, autorizada):** commit `c80c3a6` —
  `Modifier.verticalScroll(rememberScrollState())` na `Column` raiz.
- **Verificação pós-fix no A34:** `scrollable="true"` = **1** nó; após swipe,
  “Chave DeepSeek” e “Salvar chave” **visíveis** (1 nó cada); screenshot confirma
  o card DeepSeek completo (campo + olho + botão + seletor flash/v4-pro).

### Observação #2 — gate de RAM exibe 7,3 GB (< 8 GB) no A34
- O card Gemma mostra “RAM do aparelho: 7,3 GB — abaixo do mínimo de 8 GB”, mas
  o modelo está presente (via `adb push`) e o app segue. O gate
  `LlmConfig.MIN_RAM_BYTES = 8 GB` só afeta a UI de download — registrar para
  revisão (mínimo de 8 GB vs. RAM total reportada de 7,3 GB no A34).

### Observação #3 — cosmético
- O chip “deepseek-v4-pro” quebra em duas linhas na largura do A34. Sem impacto funcional.

## Blocos
- Bloco 1 (import/indexação): pode rodar sem a chave DeepSeek.
- Blocos 2–7: **aguardam a chave DeepSeek ser salva no aparelho** (agora possível
  pela UI corrigida). Nada foi simulado.

## State
- Bug #1 corrigido em `c80c3a6` (fora da validação) e verificado no device.
- Screenshots/logs de apoio em `/tmp/opencode/` (não versionados).
