package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Treino MVP — escopo, meta, preparação persistida e fallbacks. */
class TrainingPrepTest {

    private fun section(
        id: String,
        order: Int,
        role: SectionRole = SectionRole.BODY,
        title: String = "Seção",
        minutes: Int = 4,
        html: String = "",
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role, title = title,
        minutes = minutes, contentHtml = html, bibleRefs = emptyList(),
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun sub(
        id: String,
        sectionId: String,
        order: Int,
        outline: String,
        developed: String = "",
    ) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = outline,
        bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
        developedHtml = developed, createdAt = 0, updatedAt = 0,
    )

    private fun note() = listOf(
        section("s1", 0, SectionRole.INTRO, "Introdução", 2, "<p>Bom dia.</p>"),
        section("s2", 1, title = "Ponto 1", minutes = 5, html = "<p>Conteúdo antigo</p>"),
        section("s3", 2, title = "Ponto 2", minutes = 3),
        section("s4", 3, SectionRole.CONCLUSION, "Conclusão", 1, "<p>Obrigado.</p>"),
    )

    private fun subs() = mapOf(
        "s2" to listOf(
            sub("sp1", "s2", 0, "Âncora A", "<p>Fala A</p>"),
            sub("sp2", "s2", 1, "Âncora B"),
        ),
        "s3" to listOf(sub("sp3", "s3", 0, "Âncora C", "<p>Fala C</p>")),
    )

    @Test
    fun discursoInteiroSomaMetaEJuntaTudo() {
        val text = buildStageContentForScope(
            DiscourseType.S34_DISCOURSE, note(), subs(), TrainScope.Whole,
        )
        assertTrue(text.contains("Fala A"))
        assertTrue(text.contains("Âncora B"))
        assertTrue(text.contains("Fala C"))
        assertEquals(11, trainTargetMinutes(note(), subs(), TrainScope.Whole))
    }

    @Test
    fun secaoUsaSoOsSubpontosDaSecaoEADuracaoDaSecao() {
        val scope = TrainScope(TrainScopeKind.SECTION, sectionId = "s2")
        val text = buildStageContentForScope(
            DiscourseType.S34_DISCOURSE, note(), subs(), scope,
        )
        assertTrue(text.contains("Fala A"))
        assertTrue(text.contains("Âncora B"))
        assertFalse(text.contains("Fala C"))
        assertFalse(text.contains("Bom dia"))
        assertEquals(5, trainTargetMinutes(note(), subs(), scope))
    }

    @Test
    fun subpontoUsaDesenvolvimentoOuAncoraESomaADuracaoDaSecao() {
        val developed = TrainScope(
            kind = TrainScopeKind.SUBPOINT, sectionId = "s2", subPointId = "sp1",
        )
        assertEquals(
            "Fala A",
            buildStageContentForScope(DiscourseType.S34_DISCOURSE, note(), subs(), developed),
        )
        val outline = TrainScope(
            kind = TrainScopeKind.SUBPOINT, sectionId = "s2", subPointId = "sp2",
        )
        assertEquals(
            "Âncora B",
            buildStageContentForScope(DiscourseType.S34_DISCOURSE, note(), subs(), outline),
        )
        assertEquals(5, trainTargetMinutes(note(), subs(), developed))
        assertEquals(5, trainTargetMinutes(note(), subs(), outline))
    }

    @Test
    fun secaoVemLeContentHtmlDaSecao() {
        val scope = TrainScope(TrainScopeKind.SECTION, sectionId = "s1")
        assertEquals(
            "Bom dia.",
            buildStageContentForScope(DiscourseType.MINISTRY_PART, note(), subs(), scope),
        )
        assertEquals(2, trainTargetMinutes(note(), subs(), scope))
    }

    @Test
    fun escopoInvalidoCaiNoInteiroEmVezDeAbrirVazio() {
        val scope = TrainScope(TrainScopeKind.SUBPOINT, sectionId = "sumiu", subPointId = "nada")
        val text = buildStageContentForScope(
            DiscourseType.S34_DISCOURSE, note(), subs(), scope,
        )
        assertTrue(text.contains("Fala A"))
        assertTrue(text.contains("Fala C"))
    }

    @Test
    fun preparacaoPersistidaFazRoundTrip() {
        val preps = listOf(
            TrainPrep(TrainScope.Whole, TrainMode.READING, timerEnabled = false),
            TrainPrep(
                TrainScope(TrainScopeKind.SECTION, sectionId = "s2"),
                TrainMode.ANALYSIS,
                timerEnabled = true,
            ),
            TrainPrep(
                TrainScope(TrainScopeKind.SUBPOINT, "s2", "sp1"),
                TrainMode.READING,
                timerEnabled = false,
            ),
        )
        preps.forEach { prep ->
            val decoded = decodeTrainPrep(
                encodeTrainPrep(prep), note(), subs(), analysisAvailable = true,
            )
            assertEquals(prep, decoded)
        }
    }

    @Test
    fun preparacaoInvalidaVoltaParaODiscursoInteiro() {
        assertEquals(
            TrainScope.Whole,
            decodeTrainPrep("SECTION|sumiu|A|1", note(), subs(), true).scope,
        )
        assertEquals(
            TrainScope.Whole,
            decodeTrainPrep("SUBPOINT|s2|sumiu|A|1", note(), subs(), true).scope,
        )
        assertEquals(
            TrainScope.Whole,
            decodeTrainPrep("lixo", note(), subs(), true).scope,
        )
        assertEquals(
            TrainScope.Whole,
            decodeTrainPrep(null, note(), subs(), true).scope,
        )
    }

    @Test
    fun semSttForcaLeituraESaneiaEscopo() {
        val prep = TrainPrep(
            scope = TrainScope(TrainScopeKind.SECTION, sectionId = "s2"),
            mode = TrainMode.ANALYSIS,
            timerEnabled = true,
        )
        val safe = sanitizeTrainPrep(prep, note(), subs(), analysisAvailable = false)
        assertEquals(TrainMode.READING, safe.mode)
        assertEquals("s2", safe.scope.sectionId)

        val default = defaultTrainPrep(analysisAvailable = false)
        assertEquals(TrainMode.READING, default.mode)
        assertTrue(default.timerEnabled)
    }

    @Test
    fun listaDeTrechosIgnoraSecaoSemTextoESemSubpontos() {
        val options = trainSectionOptions(note(), subs())
        assertEquals(listOf("s1", "s2", "s3", "s4"), options.map { it.id })
        assertEquals(2, options.first { it.id == "s2" }.subPoints.size)
        assertTrue(options.first { it.id == "s2" }.subPoints.first().text.contains("Âncora A"))

        val withEmpty = note() + section("s5", 4, title = "Vazia", minutes = 0)
        val filtered = trainSectionOptions(withEmpty, subs())
        assertFalse(filtered.any { it.id == "s5" })
    }

    @Test
    fun trechoQueFicouVazioVoltaParaODiscursoInteiro() {
        val empty = note() + section("s5", 4, title = "Vazia", minutes = 0)
        val staleSection = TrainPrep(
            scope = TrainScope(TrainScopeKind.SECTION, sectionId = "s5"),
            mode = TrainMode.READING,
            timerEnabled = true,
        )
        assertEquals(
            TrainScope.Whole,
            sanitizeTrainPrep(staleSection, empty, subs(), true).scope,
        )

        val emptySubs = subs() + ("s5" to listOf(sub("sp9", "s5", 0, "")))
        assertEquals(
            TrainScope.Whole,
            sanitizeTrainPrep(
                TrainPrep(
                    scope = TrainScope(TrainScopeKind.SUBPOINT, "s5", "sp9"),
                    mode = TrainMode.READING,
                    timerEnabled = true,
                ),
                empty,
                emptySubs,
                true,
            ).scope,
        )
    }
}
