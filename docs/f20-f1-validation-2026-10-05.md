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

### Bug #4 (bloqueador do DeepSeek) — chave salva não é uma chave de API válida
- **Sintoma:** “Testar conexão” no card DeepSeek retorna **Erro** (não “Online”).
- **Evidência (API real, requisição no formato exato do app, com a chave do aparelho — valor nunca impresso):**
  - `POST https://api.deepseek.com/v1/chat/completions` → **HTTP 401**
    `Authentication Fails (auth header format should be Bearer sk-...)`
  - Impressão digital do valor salvo: `len=38`, **não começa com `sk-`**, termina em `0d72`
    (o próprio erro da API mascarou `****0d72`).
- **Conclusão:** o valor salvo em `deepseek_api_key` não é uma chave da API DeepSeek
  (chaves do platform começam com `sk-`). Provavelmente veio do app consumidor
  (`com.deepseek.chat` está instalado no aparelho) ou foi colado incompleto.
- **Ação para destravar:** gerar uma chave em `platform.deepseek.com` (com créditos) e
  salvar de novo no card DeepSeek. Sem isso, os Blocos 2/3/5/6/7 (DeepSeek) não rodam.

### Observação #5 — rótulo do status na sonda
- A sonda exibiu “Erro de conexão” para um 401 de autenticação; o esperado pelo código é
  “Erro de autenticação”. Reconfirmar na próxima sessão (a re-execução limpa foi interrompida
  pela queda do ADB). Baixa severidade (UX do indicador).

### Incidente — ADB caiu no meio
- Durante a re-execução limpa da sonda, o ADB wireless caiu (`waiting for device`;
  `adb devices` vazio). **Validação interrompida conforme a regra** (sem tentar contornar).

## Blocos
- Bloco 1 (import/indexação): **não executado** (ADB caiu antes).
- Blocos 2–7: **não executados** (chave DeepSeek inválida + queda do ADB). Nada foi simulado.

## State
- Bug #1 corrigido em `c80c3a6` (fora da validação) e verificado no device.
- Bug #4 (chave inválida) registrado; aguarda ação do dono.
- Screenshots/logs de apoio em `/tmp/opencode/` (não versionados).
