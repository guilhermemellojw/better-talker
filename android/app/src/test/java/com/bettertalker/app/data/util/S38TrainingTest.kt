package com.bettertalker.app.data.util

import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.TrainingClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S38TrainingTest {
    @Test
    fun s38EntraComoBaseETraining() {
        assertEquals("s38", matchBaseSlot("S-38_T_194.docx"))
        assertEquals("s38", matchBaseSlot("s38t194.jwpub"))
        assertEquals(SourceType.TRAINING, SourceType.fromBaseSlot("s38"))
        assertFalse(SourceType.fromBaseSlot("s38") == SourceType.CONTENT)
    }

    @Test
    fun noteSourceS38EhTraining() {
        val source = NoteSource(
            ref = "s38 § 3",
            kind = SourceKind.PUBLICATION,
            citation = CitationStatus(EditionStatus.READY, TextStatus.RESOLVED),
            publicationKey = "s38",
        )
        assertTrue(source.isTrainingSource())
        val nwt = source.copy(publicationKey = "nwt")
        assertFalse(nwt.isTrainingSource())
    }

    @Test
    fun classifierSeparaOrientacaoDeReuniao() {
        assertEquals(
            TrainingCategory.MEETING,
            TrainingClassifier.classify("Instruções para a reunião", null, "Como preparar a parte."),
        )
    }
}
