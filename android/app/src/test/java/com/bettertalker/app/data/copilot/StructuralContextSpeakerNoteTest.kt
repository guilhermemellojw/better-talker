package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5a — a ORIENTAÇÃO DO ORADOR (NOTA) entra no bloco estrutural do S-34
 * que viaja no prompt do chat.
 */
class StructuralContextSpeakerNoteTest {

    private fun ctx(note: String?) = OutlineStructureContext(
        outlineId = "o1",
        title = "Discurso teste",
        objective = "Mostrar a esperança.",
        speakerNote = note,
        orderedSections = emptyList(),
        currentSection = null,
        focusState = OutlineStructureContext.FocusState.DOCUMENT,
    )

    @Test
    fun comNota_imprimeLinhaDeOrientacao() {
        val out = serializeStructuralContext(ctx("Fale com bondade e convicção."))
        assertTrue(out.contains("[S34] ORIENTAÇÃO DO ORADOR: Fale com bondade e convicção."))
    }

    @Test
    fun semNota_naoImprimeLinha() {
        val out = serializeStructuralContext(ctx(null))
        assertFalse(out.contains("ORIENTAÇÃO DO ORADOR"))

        val blank = serializeStructuralContext(ctx("   "))
        assertFalse(blank.contains("ORIENTAÇÃO DO ORADOR"))
    }
}
