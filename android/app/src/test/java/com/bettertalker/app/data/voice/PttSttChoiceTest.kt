package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Escolha local → on-device → sistema; sem nenhum, null. */
class PttSttChoiceTest {

    @Test
    fun localPreferidoEDisponivel_vence() {
        assertEquals(
            PttSttChoice.LOCAL,
            choosePttSttChoice(
                preferLocal = true,
                localAvailable = true,
                systemAvailable = true,
                onDeviceAvailable = true,
                sdkInt = 31,
            ),
        )
    }

    @Test
    fun semModeloLocal_caiParaOnDevice() {
        assertEquals(
            PttSttChoice.ON_DEVICE,
            choosePttSttChoice(
                preferLocal = true,
                localAvailable = false,
                systemAvailable = true,
                onDeviceAvailable = true,
                sdkInt = 31,
            ),
        )
    }

    @Test
    fun apiAntiga_caiParaSistema() {
        assertEquals(
            PttSttChoice.SYSTEM,
            choosePttSttChoice(
                preferLocal = true,
                localAvailable = false,
                systemAvailable = true,
                onDeviceAvailable = true,
                sdkInt = 30,
            ),
        )
    }

    @Test
    fun semNenhumMotor_retornaNull() {
        assertNull(
            choosePttSttChoice(
                preferLocal = true,
                localAvailable = false,
                systemAvailable = false,
                onDeviceAvailable = false,
                sdkInt = 34,
            ),
        )
    }

    @Test
    fun sistemaExplicito_naoUsaLocal() {
        assertEquals(
            PttSttChoice.ON_DEVICE,
            choosePttSttChoice(
                preferLocal = false,
                localAvailable = true,
                systemAvailable = true,
                onDeviceAvailable = true,
                sdkInt = 34,
            ),
        )
    }
}
