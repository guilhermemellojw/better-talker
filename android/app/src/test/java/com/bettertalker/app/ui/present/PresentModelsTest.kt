package com.bettertalker.app.ui.present

import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.StageTurnKind
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
    }

    @Test
    fun rotulosDeProntidaoFlexionamPorTipo() {
        assertEquals("Mapa mental pronto", presentReadinessLabel(PresentReadiness.READY))
        assertEquals("Mapa mental incompleto", presentReadinessLabel(PresentReadiness.PARTIAL))
        assertEquals("Falta gerar o mapa mental", presentReadinessLabel(PresentReadiness.MISSING))

        assertEquals(
            "Roteiro pronto",
            presentReadinessLabel(PresentReadiness.READY, demo = true),
        )
        assertEquals(
            "Roteiro incompleto",
            presentReadinessLabel(PresentReadiness.PARTIAL, demo = true),
        )
        assertEquals(
            "Falta o roteiro",
            presentReadinessLabel(PresentReadiness.MISSING, demo = true),
        )
    }

    @Test
    fun acoesDoCartaoNuncaMandamGerarTribunaNaDemo() {
        assertEquals(
            PresentCardActions(PresentAction.PRESENT, null),
            presentCardActions(PresentReadiness.READY, demo = false),
        )
        assertEquals(
            PresentCardActions(PresentAction.PRESENT),
            presentCardActions(PresentReadiness.READY, demo = true),
        )
        assertEquals(
            PresentCardActions(PresentAction.OPEN_TRIBUNA),
            presentCardActions(PresentReadiness.PARTIAL, demo = false),
        )
        assertEquals(
            PresentCardActions(PresentAction.OPEN_SCRIPT, PresentAction.OPEN_EDITOR),
            presentCardActions(PresentReadiness.PARTIAL, demo = true),
        )
        assertEquals(
            PresentCardActions(PresentAction.GENERATE_TRIBUNA),
            presentCardActions(PresentReadiness.MISSING, demo = false),
        )
        assertEquals(
            PresentCardActions(PresentAction.OPEN_EDITOR),
            presentCardActions(PresentReadiness.MISSING, demo = true),
        )
    }

    @Test
    fun rotulosDasAcoes() {
        assertEquals("Apresentar", presentActionLabel(PresentAction.PRESENT))
        assertEquals("Completar mapa mental", presentActionLabel(PresentAction.OPEN_TRIBUNA))
        assertEquals("Abrir Roteiro", presentActionLabel(PresentAction.OPEN_SCRIPT))
        assertEquals("Gerar mapa mental", presentActionLabel(PresentAction.GENERATE_TRIBUNA))
        assertEquals("Abrir editor", presentActionLabel(PresentAction.OPEN_EDITOR))
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

    @Test
    fun prepSegueOrientacaoDoDispositivoPorPadrao() {
        assertEquals(PresentOrientation.AUTO, PresentPrep().orientation)
    }

    @Test
    fun metaDaSecaoUsaOTempoDaCapsulaQuandoExiste() {
        assertEquals(5, cue("s1", 0).targetMinutes)
        val timed = cue("s2", 1, capsule = capsule("s2").copy(timeMinutes = 7))
        assertEquals(7, timed.targetMinutes)
        val zeroed = cue("s3", 2, capsule = capsule("s3").copy(timeMinutes = 0))
        assertEquals(5, zeroed.targetMinutes)
    }

    @Test
    fun cueImagemSoUsaAImagemAtribuida() {
        val first = NoteImageEntity(
            id = "img-1", noteId = "n1", sectionId = "s1", path = "/a.jpg",
            mime = "image/jpeg", caption = "Primeira", addedAt = 1,
        )
        val second = NoteImageEntity(
            id = "img-2", noteId = "n1", sectionId = "s1", path = "/b.jpg",
            mime = "image/jpeg", caption = "Segunda", addedAt = 2,
        )
        val images = listOf(first, second)
        val semId = TribunaCue(TribunaCueKind.IMAGE, "Mostre a imagem.")
        val comId = semId.copy(imageId = "img-2")

        assertNull(presentCueImage(semId, images))
        assertEquals(second, presentCueImage(comId, images))
        assertNull(
            presentCueImage(
                TribunaCue(TribunaCueKind.REASONING, "Texto", imageId = "img-1"),
                images,
            )
        )
    }

    // ---------- v5.3: roteiro completo, narração e imagens da nota ----------

    @Test
    fun narracaoContaComoRoteiroPronto() {
        val demo = cue(
            "s1", 0, capsule = null,
            turns = emptyList(),
        ).copy(
            script = listOf(
                StageTurn("", "Ana entra na sala.", StageTurnKind.NARRATION),
            )
        )
        assertTrue(presentCueReady(demo, demo = true))
        assertEquals(PresentReadiness.READY, presentReadinessOf(listOf(demo), demo = true))
    }

    @Test
    fun roteiroAchatadoPreservaNarracaoEIndiceDoTurno() {
        val cue = cue("s1", 0, capsule = null).copy(
            script = listOf(
                StageTurn("", "Cenário.", StageTurnKind.NARRATION),
                StageTurn("Ana", "Olá."),
                StageTurn("", "O morador responde.", StageTurnKind.NARRATION),
                StageTurn("Morador", "Bom dia."),
            ),
        )
        val items = presentScriptItems(listOf(cue))
        assertEquals(listOf(0, 1, 2, 3), items.map { it.turnIndex })
        assertEquals(listOf("", "Ana", "", "Morador"), items.map { it.turn.speaker })
        assertEquals(listOf("Ana", "Morador"), presentSpeakers(listOf(cue)))
    }

    @Test
    fun imagemDaCueResolveEmTodaANotaComSecaoPrimeiro() {
        val sectionImage = NoteImageEntity(
            id = "img-s1", noteId = "n1", sectionId = "s1", path = "/s1.jpg",
            mime = "image/jpeg", caption = "Da seção", addedAt = 1,
        )
        val noteImage = NoteImageEntity(
            id = "img-n", noteId = "n1", sectionId = null, path = "/n.jpg",
            mime = "image/jpeg", caption = "Da nota", addedAt = 2,
        )
        val present = cue("s1", 0, capsule = null).copy(
            sectionImages = listOf(sectionImage),
            noteImages = listOf(sectionImage, noteImage),
        )
        val imageCue = TribunaCue(
            TribunaCueKind.IMAGE,
            "Mostre o mapa.",
            imageId = "img-n",
        )
        assertEquals(listOf("img-s1", "img-n"), present.images.map { it.id })
        assertEquals(noteImage, presentCueImage(imageCue, present.images))
    }

    @Test
    fun preVooContaImagensComLegendaEArquivoAusente() {
        val image = NoteImageEntity(
            id = "img-1", noteId = "n1", sectionId = "s1", path = "/ok.jpg",
            mime = "image/jpeg", caption = "Mapa", addedAt = 1,
        )
        val imageCue = TribunaCue(
            TribunaCueKind.IMAGE,
            "Mostre o mapa.",
            imageId = "img-1",
            imageCaption = "Mapa do território",
        )
        val present = cue("s1", 0, capsule = null).copy(
            capsule = capsule("s1").copy(cues = listOf(imageCue)),
            sectionImages = listOf(image),
            noteImages = listOf(image),
        )
        val ok = presentImageCheck(listOf(present)) { true }
        assertEquals(1, ok.imageCues)
        assertEquals(1, ok.withCaption)
        assertEquals(1, ok.resolved)
        assertTrue(ok.allResolved)
        assertTrue(ok.isComplete)

        val missing = presentImageCheck(listOf(present)) { false }
        assertEquals(1, missing.imageCues)
        assertEquals(1, missing.missing.size)
        assertFalse(missing.allResolved)
        assertFalse(missing.isComplete)
    }

    @Test
    fun imagemSemAtribuicaoApareceComoSemImagem() {
        val imageCue = TribunaCue(TribunaCueKind.IMAGE, "Mostre a imagem.")
        val present = cue("s1", 0, capsule = null).copy(
            capsule = capsule("s1").copy(cues = listOf(imageCue)),
        )
        val check = presentImageCheck(listOf(present)) { true }
        assertEquals(0, check.resolved)
        assertEquals(1, check.missing.size)
        assertFalse(check.isComplete)
    }
}
