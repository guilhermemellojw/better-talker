# Validação P0 voz — coexistência TTS/STT

**Data:** 2026-10-27
**Branch:** `fix/v2.8.2-voice-coexistence` (a partir da main `41c546d`)
**APK:** release assinado 2.8.1/26 rebuildado com o P0 (mesma assinatura; `-r` preservou os dados)
**Suíte:** **2.538 testes JVM / 0 falhas** · `lintRelease` verde

## Código

| Item | Ação |
|---|---|
| Stage — TTS→STT | `startAnalysis()` chama `tts.stop()` antes de abrir o microfone. |
| Stage — STT→TTS | `toggleSpeech()`/`speakContent()` não iniciam TTS durante `Listening/Paused/Interrupted/Processing`; “Ouvir” fica desabilitado na barra. |
| Chat — TTS→STT | `CopilotViewModel.startVoiceInput()` para o TTS; microfone desabilitado no composer enquanto `ttsSpeaking`. |
| Chat — STT→TTS | `speakMessage()` recusa iniciar com microfone `Listening/Processing`; ação “Ouvir” desabilitada durante a voz. |
| `VoiceRouter` | `speak()`/`stop()`/`shutdown()`/`setPremiumEnabled()` param os dois motores antes de falar/trocar; `isSpeaking()` consulta os dois. |
| `TextToSpeechManager` | `speak()` checa o retorno; `onStop` e `onError(id, code)` limpam `_speaking`; helper puro `ttsSpeakSucceeded`. |
| `SpeechRecognizerManager` | `finish()` ganhou timeout de 5 s; falha de `startListening` vira erro visível em vez de estado `Listening` preso. |

## Testes puros

- `VoiceRouterBehaviorTest`: `stop()` para os dois; `speak()` para os dois antes de falar; `setPremiumEnabled()` para os dois.
- `TtsSpeakResultTest`: `SUCCESS` marca falando; `ERROR` não deixa o botão preso.
- `AnalysisStateTest`: `Processing` conta como análise ativa (bloqueia TTS/microfone).
- Suíte completa **2.538 / 0**.

## A34

- Troca TTS→mic no Stage: “▶ Ouvir” voltou ao estado parado quando o microfone abriu; a análise iniciou e o serviço do sistema respondeu `SpeechRecognizer error=10`, caindo no fallback **Interrupted/Retomar/Parar**. O TTS não continuou falando.
- TTS play/stop/background já validado no release 2.8.1; o P0 não altera o caminho de fala normal.
- **Limitação conhecida:** o serviço de reconhecimento do A34 continua indisponível (`error=10`). O fallback funciona e não bloqueia o release; a validação de fala humana fica pendente de um aparelho com o serviço disponível.
- Não há seletor de voz na UI; a troca Piper↔nativo é coberta pelo `VoiceRouter` e pelo teste puro `setPremiumEnabled`.

## Próximo

Bump 2.8.2/27, suíte + lint, dry-run final, verificar/instalar o artefato, smoke e tag v2.8.2.
