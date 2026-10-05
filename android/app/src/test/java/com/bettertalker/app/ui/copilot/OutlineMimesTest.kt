package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 — o picker de importação aceita RTF (formato nativo do S-34) além de
 * DOCX/PDF/octet-stream, sem regressão nos MIMEs existentes.
 */
class OutlineMimesTest {

    @Test
    fun aceitaRtfSemRegressaoDeDocxPdfEOctetStream() {
        assertTrue(OUTLINE_MIMES.contains("application/rtf"))
        assertTrue(OUTLINE_MIMES.contains("text/rtf"))
        assertTrue(OUTLINE_MIMES.contains("application/pdf"))
        assertTrue(OUTLINE_MIMES.contains("application/octet-stream"))
        assertTrue(
            OUTLINE_MIMES.contains(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
    }
}
