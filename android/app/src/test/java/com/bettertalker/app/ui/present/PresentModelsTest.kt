package com.bettertalker.app.ui.present

import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.ui.stage.StageTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentModelsTest {

    private fun capsule(id: String) = TribunaCapsule(
        sectionId = id,
        mainIdea = "Ideia",
        bibleRef = "Sal 103:20",
        anchorPhrase = "Âncora",
        transition = "Transição",
    )

    private fun cue(
        id: String,
        index: Int,
        capsule: TribunaCapsule? = capsule(id),
        turns: List<StageTurn> = emptyList(),
    ) = PresentCue(
        sectionId = id,
        index = index,
        title = "Seção $index",
        minutes = 5,
        capsule = capsule,
        turns = turns,
    )

    @Test
    fun prontidaoPorSecoes() {
        assertEquals(PresentReadiness.MISSING, presentReadiness(0, 0))
        assertEquals(PresentReadiness.MISSING, presentReadiness(3, 0))
        assertEquals(PresentReadiness.PARTIAL, presentReadiness(3, 1))
        assertEquals(PresentReadiness.PARTIAL, presentReadiness(3, 2))
        assertEquals(PresentReadiness.READY, presentReadiness(3, 3))
        assertEquals("Tribuna pronta", presentReadinessLabel(PresentReadiness.READY))
        assertEquals("Tribuna incompleta", presentReadinessLabel(PresentReadiness.PARTIAL))
        assertEquals("Falta gerar Tribuna", presentReadinessLabel(PresentReadiness.MISSING))
    }

    @Test
    fun discursoUsaCapsulaEDemoUsaTurnos() {
        val discourse = listOf(
            cue("s1", 0),
            cue("s2", 1, capsule = null),
        )
        assertEquals(PresentReadiness.PARTIAL, presentReadinessOf(discourse, demo = false))

        val demo = listOf(
            cue("s1", 0, capsule = null, turns = listOf(StageTurn("A", "fala"))),
            cue("s2", 1, capsule = null),
        )
        assertEquals(PresentReadiness.PARTIAL, presentReadinessOf(demo, demo = true))
        assertTrue(presentCueReady(demo[0], demo = true))
        assertFalse(presentCueReady(demo[1], demo = true))
    }

    @Test
    fun roteiroAchataTurnosEEncontraInicioDaSecao() {
        val cues = listOf(
            cue("s1", 0, turns = listOf(StageTurn("A", "a1"), StageTurn("B", "b1"))),
            cue("s2", 1, turns = listOf(StageTurn("A", "a2"))),
            cue("s3", 2),
        )
        val items = presentScriptItems(cues)
        assertEquals(3, items.size)
        assertEquals(listOf(0, 0, 1), items.map { it.cueIndex })
        assertEquals(2, presentScriptStartIndex(items, 2))
        assertEquals(0, presentScriptStartIndex(items, 0))
        assertEquals(listOf("A", "B"), presentSpeakers(cues))
        assertEquals(0, presentScriptStartIndex(emptyList(), 1))
    }

    @Test
    fun sessaoSalvaFazRoundTripEClampa() {
        val session = PresentSavedSession(index = 2, elapsedSec = 125, updatedAt = 99)
        val decoded = decodePresentSession(encodePresentSession(session), totalSections = 4)
        assertEquals(session, decoded)

        assertEquals(3, decodePresentSession("99|10|1", 4)?.index)
        assertEquals(0, decodePresentSession("-1|10|1", 4)?.index)
        assertNull(decodePresentSession("lixo", 4))
        assertNull(decodePresentSession(null, 4))
        assertNull(decodePresentSession("1|2|3", 0))
    }

    @Test
    fun bateriaBaixaSoAvisaSemCarregador() {
        assertTrue(presentBatteryLow(20, charging = false))
        assertTrue(presentBatteryLow(0, charging = false))
        assertFalse(presentBatteryLow(21, charging = false))
        assertFalse(presentBatteryLow(10, charging = true))
        assertEquals("Retrato", presentOrientationLabel(PresentOrientation.PORTRAIT))
    }
}
