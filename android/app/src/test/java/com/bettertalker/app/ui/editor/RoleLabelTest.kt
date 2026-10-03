package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rótulo legível do role ([roleLabel]). Pura, sem Compose.
 */
class RoleLabelTest {

    @Test
    fun roleLabel_intro_returnsIntroducao() {
        assertEquals("INTRODUÇÃO", roleLabel(SectionRole.INTRO))
    }

    @Test
    fun roleLabel_body_returnsDesenvolvimento() {
        assertEquals("DESENVOLVIMENTO", roleLabel(SectionRole.BODY))
    }

    @Test
    fun roleLabel_conclusion_returnsConclusao() {
        assertEquals("CONCLUSÃO", roleLabel(SectionRole.CONCLUSION))
    }
}
