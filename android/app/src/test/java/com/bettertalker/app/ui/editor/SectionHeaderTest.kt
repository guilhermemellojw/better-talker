package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sigla do chip compacto de role ([roleSigla]). Pura, sem Compose.
 */
class SectionHeaderTest {

    @Test
    fun roleSigla_intro_returnsIN() {
        assertEquals("IN", roleSigla(SectionRole.INTRO))
    }

    @Test
    fun roleSigla_body_returnsBD() {
        assertEquals("BD", roleSigla(SectionRole.BODY))
    }

    @Test
    fun roleSigla_conclusion_returnsCO() {
        assertEquals("CO", roleSigla(SectionRole.CONCLUSION))
    }
}
