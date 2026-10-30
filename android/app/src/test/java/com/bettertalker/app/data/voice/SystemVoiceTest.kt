package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seletor de voz do sistema + regra de STT no aparelho (puro). */
class SystemVoiceTest {

    private fun voice(
        name: String,
        locale: String = "pt-BR",
        network: Boolean = false,
        quality: Int = 400,
    ) = SystemVoice(
        name = name,
        localeTag = locale,
        label = name,
        requiresNetwork = network,
        quality = quality,
    )

    @Test
    fun filtraPortuguesEordenaOfflineQualidadeENome() {
        val options = systemVoiceOptions(
            listOf(
                voice("rede-bom", network = true, quality = 500),
                voice("offline-normal", quality = 300),
                voice("offline-alto", quality = 500),
                voice("en-US", locale = "en-US", quality = 500),
                voice("pt-PT", locale = "pt-PT", quality = 400),
                voice("offline-alto", quality = 500), // duplicata
            ),
        )
        assertEquals(
            listOf("offline-alto", "pt-PT", "offline-normal", "rede-bom"),
            options.map { it.name },
        )
    }

    @Test
    fun vozDeOutroIdiomaNuncaEntra() {
        val options = systemVoiceOptions(
            listOf(
                voice("en-US", locale = "en-US"),
                voice("es-ES", locale = "es-ES"),
            ),
        )
        assertTrue(options.isEmpty())
    }

    @Test
    fun sttOnDeviceSoComPreferenciaDisponibilidadeEsdk31() {
        assertTrue(useOnDeviceRecognizer(true, true, 31))
        assertTrue(useOnDeviceRecognizer(true, true, 34))
        assertFalse(useOnDeviceRecognizer(false, true, 34))
        assertFalse(useOnDeviceRecognizer(true, false, 34))
        assertFalse(useOnDeviceRecognizer(true, true, 30))
    }
}
