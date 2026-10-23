# Crash nativo do TTS (sherpa-onnx) — 2026-10-23

## Sintoma

App no tablet **25040RP0AL** (v2.7.0) fechou sozinho às **06:47:06**.
O DropBox do Android registrou:

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x1fe
pid 27675 (ettertalker.app), tid 29532 (DefaultDispatch)
#13 Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithConfigImpl+1856
#20 com.k2fsa.sherpa.onnx.OfflineTts.generateWithConfigAndCallback+0
#24 com.bettertalker.app.data.voice.PiperTtsManager$speak$1.invokeSuspend+0
```

Não foi o chat nem a persona: foi a **síntese de voz** (Piper/sherpa-onnx).
O processo tinha ~42 min de uptime (várias leituras em voz alta).

## Causa

O `OfflineTts` do sherpa-onnx **não é thread-safe**. O `PiperTtsManager.speak()`
fazia:

```kotlin
stop()                 // cancela a coroutine, mas NÃO interrompe a chamada JNI
playJob = scope.launch {
    ...
    local.generateWithConfigAndCallback(...)  // mesma instância de OfflineTts
}
```

`Job.cancel()` é cooperativo: a chamada JNI em andamento continua. Um novo
`speak()` (ex.: tocar "Ouvir" em outra resposta, ou repetir a leitura) iniciava
**outra** geração na mesma instância enquanto a anterior ainda estava no JNI →
corrida no estado interno do modelo → SIGSEGV em `generateWithConfigImpl`.

O `shutdown()` também podia chamar `tts.release()` sem esperar uma geração em
andamento (o `join` do job cancelado não garante que o JNI retornou).

## Correção (v2.7.1)

- `nativeLock: Mutex` serializa `ensureLoaded()`, `generateWithConfigAndCallback()`
  e `tts.release()` — nunca há duas chamadas nativas simultâneas.
- `generation: AtomicLong` invalida o callback da leitura anterior. `stop()`
  incrementa o token; o callback retorna `0` (parar) no próximo chunk, mesmo
  que um novo `speak()` já tenha começado.
- `shutdown()` faz `release()` **sob o mesmo mutex**, depois do `join`.
- `warmUp()` carrega o modelo sob o mutex (evita duas cargas concorrentes).

## Smoke test (tablet, v2.7.1)

| Evento | Evidência |
|---|---|
| 1ª leitura | AudioTrack criado às **07:54:04** (piid 7471, pid 1247) |
| 2ª leitura com a 1ª em andamento | AudioTrack criado às **07:55:56** (piid 7479, mesmo pid 1247) |
| Processo | **pid 1247 vivo** após as duas |
| Crash novo | **nenhum** — crash buffer e DropBox só têm o evento de 06:47 |

## Observação

O crash de 10-20 09:08 no DropBox é do **uiautomator** (`UiAutomationService
already registered`), não do app — ferramenta de teste, sem relação.
