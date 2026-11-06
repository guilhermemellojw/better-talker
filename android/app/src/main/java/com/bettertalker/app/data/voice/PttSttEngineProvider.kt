package com.bettertalker.app.data.voice

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Provê o motor do PTT: sherpa local quando preferido e baixado; senão o
 * reconhecedor on-device/sistema. O engine é criado uma vez e reaproveitado
 * entre pressões para não recarregar o modelo.
 */
class PttSttEngineProvider(
    private val context: Context,
    private val preferLocal: () -> Boolean,
) : PttEngineProvider {

    private var local: SherpaSttEngine? = null
    private var system: SystemSttEngine? = null

    override fun create(): PttSelection? {
        val localEngine = local ?: SherpaSttEngine(context).also { local = it }
        val choice = choosePttSttChoice(
            preferLocal = preferLocal(),
            localAvailable = localEngine.isAvailable(),
            systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            onDeviceAvailable = SpeechRecognizerManager.isOnDeviceAvailable(context),
            sdkInt = Build.VERSION.SDK_INT,
        ) ?: return null
        val engine = when (choice) {
            PttSttChoice.LOCAL -> localEngine
            PttSttChoice.ON_DEVICE, PttSttChoice.SYSTEM ->
                system ?: SystemSttEngine(
                    context,
                    preferOnDevice = choice == PttSttChoice.ON_DEVICE,
                ).also { system = it }
        }
        return PttSelection(engine, choice)
    }

    override fun release() {
        local?.release()
        local = null
        system?.release()
        system = null
    }
}
