# Seletor de voz + STT no aparelho (2026-10-30)

**Branch:** `feat/voice-selector-stt` (a partir de `main` `307a634`)
**Versão:** 2.8.2/27 — sem bump/tag
**Escopo:** Configurações → Voz; TTS Piper × sistema; vozes pt-BR; STT
on-device com fallback avisado.

## O que entrou

### Ajuste “Voz”

`ui/settings/VoiceScreen.kt`, aberto em **Configurações → Voz**:

- motor de leitura **Piper (offline)** × **voz do sistema**;
- lista de vozes pt-BR do sistema, com qualidade e indicação `offline`/`rede`;
- opção “Padrão do aparelho”;
- switch **“Preferir reconhecimento no aparelho”** com status real do
  aparelho (“Disponível neste aparelho” / fallback);
- botão **Testar voz**.

### TTS

- `TextToSpeechManager` implementa `SystemVoiceEngine`: lista vozes pt-BR,
  aplica a voz escolhida e guarda a escolha antes do `onInit` assíncrono.
- `VoiceRouter` delega a voz nativa e mantém a regra Piper → fallback nativo.
- `SettingsStore` persiste `tts_voice_name`.
- `MainActivity` reaplica a voz escolhida quando o app abre.

### STT

- `SettingsStore.sttOnDevice` (default **true**).
- `SpeechRecognizerManager.start(preferOnDevice)`:
  - usa `createOnDeviceSpeechRecognizer` (API 31+) quando disponível;
  - sempre envia `EXTRA_PREFER_OFFLINE` quando a preferência está ligada;
  - se indisponível, usa o serviço do sistema e publica
    `STT_ON_DEVICE_FALLBACK` como snackbar único;
- `CopilotViewModel` lê a preferência antes de abrir o microfone;
- `ChatScreen` mostra o aviso de fallback e limpa o estado.

### Modelo puro/testável

- `SystemVoice` + `systemVoiceOptions`: filtra `pt*`, remove duplicatas,
  ordena offline antes de rede, qualidade desc, nome.
- `useOnDeviceRecognizer(prefer, available, sdk)`.
- `SystemVoiceTest`: 3 casos.

## Verificação

- **2.737 testes JVM / 0 falhas** (+3 sobre 2.734)
- `lintRelease` verde
- A34 (SM-A346M):
  - Configurações → Voz abriu e listou vozes pt-BR (offline e rede);
  - “Testar voz” com Piper tocou (`PiperTts: load ok`, `AudioTrack`);
  - seleção de voz do sistema e “Padrão do aparelho” persistiram no DataStore;
  - switch de STT on-device ligado; microfone abriu em “Ouvindo… 0:05” sem
    crash e sem fallback (aparelho tem reconhecedor on-device);
  - “Leitura em voz alta indisponível” durante a escuta é a regra P0
    (`voiceActive`), não falha do motor;
  - configurações restauradas para Piper + voz padrão ao final.

## Limites

- Piper tem voz única; o seletor de vozes vale para o motor do sistema
  (inclusive como fallback).
- Sem controle de velocidade/tom nesta rodada.
- Vozes de rede aparecem marcadas como `rede`; a escolha não baixa pacotes.
- A release 3.0.0/31 continua bloqueada até keystore + smoke limpo arm64.
