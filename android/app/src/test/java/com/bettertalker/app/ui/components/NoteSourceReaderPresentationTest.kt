package com.bettertalker.app.ui.components

import com.bettertalker.app.data.planning.SourceReaderContent
import com.bettertalker.app.data.planning.SourceReaderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSourceReaderPresentationTest {

    private fun content(
        status: SourceReaderStatus = SourceReaderStatus.READY,
        truncated: Boolean = false,
    ) = SourceReaderContent(
        publicationTitle = "Publicação",
        referenceLabel = "rr cap. 4",
        locationLabel = "Capítulo 4",
        body = "texto",
        status = status,
        truncated = truncated,
    )

    @Test
    fun fonteCompletaNaoRecebeRotuloDeParcial() {
        assertEquals("Fonte completa", sourceReaderStatusLabel(content()))
    }

    @Test
    fun fonteParcialPorEstado() {
        assertEquals(
            "Fonte parcial",
            sourceReaderStatusLabel(content(status = SourceReaderStatus.PARTIAL)),
        )
    }

    @Test
    fun fonteParcialPorTruncamentoMesmoComStatusReady() {
        assertEquals("Fonte parcial", sourceReaderStatusLabel(content(truncated = true)))
    }

    @Test
    fun fonteIndisponivel() {
        assertEquals(
            "Fonte indisponível",
            sourceReaderStatusLabel(content(status = SourceReaderStatus.TEXT_NOT_FOUND)),
        )
    }

    @Test
    fun acaoUsaNomenclaturaConsistente() {
        assertEquals("Consultar fonte", sourceReaderActionLabel())
    }
}