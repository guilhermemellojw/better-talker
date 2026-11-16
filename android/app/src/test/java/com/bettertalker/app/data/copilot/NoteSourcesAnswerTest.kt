package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.TextStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSourcesAnswerTest {

    private fun source(
        ref: String,
        edition: EditionStatus,
        text: TextStatus,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(edition, text),
        publicationKey = ref.substringBefore(' '),
    )

    private fun noteSources() = NoteSources(
        sources = listOf(
            source("w17.10 §9", EditionStatus.READY, TextStatus.RESOLVED),
            source("w17.10 §10", EditionStatus.MISSING, TextStatus.NOT_FOUND),
            source("jr p. 29", EditionStatus.READY, TextStatus.NOT_FOUND),
            source("w24.01 §3", EditionStatus.INDEXING, TextStatus.NOT_FOUND),
            source("w25.07 §8", EditionStatus.READY, TextStatus.PARTIAL),
        ),
        resolved = 1,
        total = 5,
    )

    @Test
    fun detectaPerguntaDeInventarioDaNota() {
        assertTrue(isNoteSourcesInventoryQuestion("Quais fontes desta nota ainda faltam?"))
        assertTrue(isNoteSourcesInventoryQuestion("Status das fontes da nota"))
        assertFalse(isNoteSourcesInventoryQuestion("O que diz w17.10 23 §§9-10?"))
        assertFalse(isNoteSourcesInventoryQuestion("Desenvolva o ponto 2"))
        assertFalse(isNoteSourcesInventoryQuestion("Quais são os pontos principais?"))
    }

    @Test
    fun respostaListaStatusEAcoesReais() {
        val answer = buildNoteSourcesInventoryAnswer(noteSources())

        assertTrue(answer.contains("5 referências"))
        assertTrue(answer.contains("1 disponíveis"))
        assertTrue(answer.contains("ainda precisam de atenção"))
        assertTrue(answer.contains("w17.10 §10"))
        assertTrue(answer.contains("Edição não importada"))
        assertTrue(answer.contains("jr p. 29"))
        assertTrue(answer.contains("Texto não encontrado"))
        assertTrue(answer.contains("w24.01 §3"))
        assertTrue(answer.contains("Indexando"))
        assertTrue(answer.contains("w25.07 §8"))
        assertTrue(answer.contains("Fontes e apoio"))
        assertFalse(answer.contains("Nada essencial está pendente"))
    }

    @Test
    fun respostaSemLacunasNaoInventaPendencia() {
        val sources = NoteSources(
            sources = listOf(source("w17.10 §9", EditionStatus.READY, TextStatus.RESOLVED)),
            resolved = 1,
            total = 1,
        )
        val answer = buildNoteSourcesInventoryAnswer(sources)
        assertTrue(answer.contains("Não há fontes faltando"))
        assertFalse(answer.contains("precisam de atenção"))
    }
}
