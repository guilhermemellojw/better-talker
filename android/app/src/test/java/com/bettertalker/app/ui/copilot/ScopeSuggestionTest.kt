package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #15d-escopo — decisão de sugerir vincular publicações. Pura, sem Android. */
class ScopeSuggestionTest {

    @Test
    fun ragFraco_semVinculos_comPublicacaoDisponivel_sugere() {
        assertTrue(
            shouldSuggestScope(
                relevantCount = 0,
                hasNoteAttachments = false,
                hasUnlinkedIndexed = true,
            )
        )
    }

    @Test
    fun ragBom_naoSugere() {
        assertFalse(
            shouldSuggestScope(
                relevantCount = 5,
                hasNoteAttachments = false,
                hasUnlinkedIndexed = true,
            )
        )
    }

    @Test
    fun notaComVinculo_naoSugere() {
        // Problema não é escopo: já há anexo vinculado.
        assertFalse(
            shouldSuggestScope(
                relevantCount = 0,
                hasNoteAttachments = true,
                hasUnlinkedIndexed = true,
            )
        )
    }

    @Test
    fun nadaParaVincular_naoSugere() {
        assertFalse(
            shouldSuggestScope(
                relevantCount = 0,
                hasNoteAttachments = false,
                hasUnlinkedIndexed = false,
            )
        )
    }

    @Test
    fun limiar_relevantCount2_sugere() {
        // MIN_RELEVANT_HITS = 3 → 2 ainda é RAG fraco.
        assertTrue(
            shouldSuggestScope(
                relevantCount = 2,
                hasNoteAttachments = false,
                hasUnlinkedIndexed = true,
            )
        )
    }
}
