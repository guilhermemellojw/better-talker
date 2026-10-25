package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 do onboarding contextual: a única lógica pura testável é
 * [NoteReadiness.isSetupComplete]. O `readNoteReadiness()` do VM não
 * tem infra de teste (padrão do projeto: sem Room fake no VM).
 */
class NoteReadinessTest {

    @Test
    fun isSetupComplete_semNadaPendente_retornaTrue() {
        val r = NoteReadiness(
            hasSections = true,
            hasOutline = true,
            hasMissingRefs = false,
            hasMissingBases = false,
            hasChatHistory = false,
        )
        assertTrue(r.isSetupComplete)
    }

    @Test
    fun isSetupComplete_comMissingBases_retornaFalse() {
        val r = NoteReadiness(
            hasSections = true,
            hasOutline = true,
            hasMissingRefs = false,
            hasMissingBases = true,
            hasChatHistory = true,
        )
        assertFalse(r.isSetupComplete)
    }

    @Test
    fun isSetupComplete_semOutlineNemSections_retornaFalse() {
        val r = NoteReadiness(
            hasSections = false,
            hasOutline = false,
            hasMissingRefs = false,
            hasMissingBases = false,
            hasChatHistory = false,
        )
        assertFalse(r.isSetupComplete)
    }

    @Test
    fun pendingSources_naoContamComoMissing() {
        val r = NoteReadiness(
            hasSections = true,
            hasOutline = true,
            hasMissingRefs = false,
            pendingSources = listOf("w25.07 § 8"),
            hasMissingBases = false,
            hasChatHistory = false,
        )
        assertTrue(r.isSetupComplete)
        assertEquals(1, r.pendingSources.size)
    }

    @Test
    fun isSetupComplete_missingRefsNaoBloqueiaSetup() {
        // Refs faltando são estado normal de trabalho, não setup incompleto.
        val r = NoteReadiness(
            hasSections = true,
            hasOutline = true,
            hasMissingRefs = true,
            missingSources = listOf("w25.07 § 8", "lmd apêndice A ponto 20"),
            hasMissingBases = false,
            hasChatHistory = false,
        )
        assertTrue(r.isSetupComplete)
        assertEquals(2, r.missingSources.size)
    }
}
