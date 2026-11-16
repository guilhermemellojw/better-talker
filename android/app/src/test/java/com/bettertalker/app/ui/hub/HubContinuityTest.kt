package com.bettertalker.app.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UX-1: atalho “Usar esboço atual” sem quebrar a independência Treino/Palco. */
class HubContinuityTest {

    private fun card(id: String, title: String = "Esboço $id") = HubNoteCard(
        id = id,
        title = title,
        isDemonstration = false,
        minutes = 5,
        sectionsCount = 1,
    )

    @Test
    fun fallbackUsaUltimaNotaQuandoElaAindaExiste() {
        val cards = listOf(card("x", "Nota X"), card("y", "Nota Y"))
        assertEquals("x", hubCurrentFallback(cards, "x")?.id)
    }

    @Test
    fun fallbackIgnoraNotaExcluidaOuNaLixeira() {
        val cards = listOf(card("x"))
        assertNull(hubCurrentFallback(cards, "deleted"))
        assertNull(hubCurrentFallback(emptyList(), "x"))
    }

    @Test
    fun fallbackIgnoraUltimaNotaAusenteOuEmBranco() {
        val cards = listOf(card("x"))
        assertNull(hubCurrentFallback(cards, null))
        assertNull(hubCurrentFallback(cards, "  "))
    }

    @Test
    fun usarNoTreinoNaoAlteraPalco() {
        val before = HubContinuitySelections(trainNoteId = null, presentNoteId = "p")
        val after = hubUseCurrent(
            target = HubContinuityTarget.TRAIN,
            currentNoteId = null,
            lastNoteId = "x",
            selections = before,
        )
        assertEquals("x", after?.trainNoteId)
        assertEquals("p", after?.presentNoteId)
    }

    @Test
    fun usarNoPalcoNaoAlteraTreino() {
        val before = HubContinuitySelections(trainNoteId = "t", presentNoteId = null)
        val after = hubUseCurrent(
            target = HubContinuityTarget.PRESENT,
            currentNoteId = null,
            lastNoteId = "x",
            selections = before,
        )
        assertEquals("t", after?.trainNoteId)
        assertEquals("x", after?.presentNoteId)
    }

    @Test
    fun naoSubstituiSelecaoExistenteDoAmbiente() {
        val before = HubContinuitySelections(trainNoteId = "t", presentNoteId = null)
        assertNull(
            hubUseCurrent(
                target = HubContinuityTarget.TRAIN,
                currentNoteId = "t",
                lastNoteId = "x",
                selections = before,
            )
        )
    }

    @Test
    fun naoUsaSemUltimaNotaValida() {
        val before = HubContinuitySelections()
        assertNull(
            hubUseCurrent(
                target = HubContinuityTarget.TRAIN,
                currentNoteId = null,
                lastNoteId = null,
                selections = before,
            )
        )
        assertNull(
            hubUseCurrent(
                target = HubContinuityTarget.PRESENT,
                currentNoteId = null,
                lastNoteId = "  ",
                selections = before,
            )
        )
    }

    @Test
    fun selecaoAtualInvalidaEquivaleAVazia() {
        // currentNoteId nulo representa "card atual inexistente" (excluída).
        val before = HubContinuitySelections(trainNoteId = "deleted", presentNoteId = "p")
        val after = hubUseCurrent(
            target = HubContinuityTarget.TRAIN,
            currentNoteId = null,
            lastNoteId = "x",
            selections = before,
        )
        assertEquals("x", after?.trainNoteId)
        assertEquals("p", after?.presentNoteId)
    }
}
