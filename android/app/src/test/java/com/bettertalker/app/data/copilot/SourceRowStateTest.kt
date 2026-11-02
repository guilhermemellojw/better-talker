package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.GroupStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.SourceGroup
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.TextStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRowStateTest {
    private fun source(
        edition: EditionStatus = EditionStatus.READY,
        text: TextStatus = TextStatus.RESOLVED,
        hint: String? = null,
    ) = NoteSource(
        ref = "w25.07 §8",
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(edition, text),
        hint = hint,
        editionKey = "w|2025|7",
        publicationKey = "w25.07",
    )

    private fun group(
        status: GroupStatus,
        items: List<NoteSource>,
    ) = SourceGroup(
        key = "pub:w25.07",
        label = "A Sentinela — julho de 2025",
        kind = SourceKind.PUBLICATION,
        sources = items,
        resolved = items.count { it.citation.isReady },
        total = items.size,
        status = status,
    )

    @Test
    fun missing_labelFaltaAcaoDownload() {
        val state = sourceRowState(group(GroupStatus.MISSING, listOf(source(EditionStatus.MISSING, TextStatus.NOT_FOUND))))
        assertEquals("Falta", state.label)
        assertEquals(SourceRowAction.DOWNLOAD, state.action)
        assertTrue(state.attention)
    }

    @Test
    fun partial_labelParcialAcaoDownload() {
        val state = sourceRowState(group(GroupStatus.PARTIAL, listOf(source(text = TextStatus.PARTIAL))))
        assertEquals("Parcial", state.label)
        assertEquals(SourceRowAction.DOWNLOAD, state.action)
    }

    @Test
    fun ok_labelDisponivelSemAcao() {
        val state = sourceRowState(group(GroupStatus.OK, listOf(source())))
        assertEquals("Disponível", state.label)
        assertEquals(SourceRowAction.NONE, state.action)
        assertFalse(state.attention)
    }

    @Test
    fun pending_labelIndexandoAcaoFollow() {
        val state = sourceRowState(group(GroupStatus.MISSING, listOf(source(EditionStatus.INDEXING, TextStatus.NOT_FOUND))))
        assertEquals("Indexando", state.label)
        assertEquals(SourceRowAction.FOLLOW, state.action)
    }

    @Test
    fun failed_labelFalhouAcaoRetry() {
        val state = sourceRowState(group(GroupStatus.MISSING, listOf(source(EditionStatus.INDEX_FAILED, TextStatus.NOT_FOUND))))
        assertEquals("Falhou", state.label)
        assertEquals(SourceRowAction.RETRY, state.action)
    }

    @Test
    fun ambiguous_labelConferirAcaoResolve() {
        val state = sourceRowState(
            group(
                GroupStatus.PARTIAL,
                listOf(source(), source(text = TextStatus.AMBIGUOUS)),
            )
        )
        assertEquals("Conferir", state.label)
        assertEquals(SourceRowAction.RESOLVE, state.action)
        assertTrue(state.attention)
    }

    @Test
    fun ambiguousComEdicaoAusenteNaoViraFalta() {
        val state = sourceRowState(
            group(
                GroupStatus.MISSING,
                listOf(source(EditionStatus.MISSING, TextStatus.AMBIGUOUS)),
            )
        )
        assertEquals("Conferir", state.label)
        assertEquals(SourceRowAction.RESOLVE, state.action)
        assertTrue(state.attention)
    }

    @Test
    fun hintAmbiguoMarcaConferir() {
        val state = sourceRowState(
            group(
                GroupStatus.PARTIAL,
                listOf(
                    source(),
                    source(
                        text = TextStatus.PARTIAL,
                        hint = "Referência ambígua: confirme a edição.",
                    ),
                ),
            )
        )
        assertEquals("Conferir", state.label)
        assertEquals(SourceRowAction.RESOLVE, state.action)
    }
}
