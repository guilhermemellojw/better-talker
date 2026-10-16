package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-c — números com apoio real (código de estudo, ano de publicação
 * do spec, conteúdo autorizado) não são flagados como inventados. Puro.
 */
class NumberFidelityTest {

    private fun ref(label: String) = OratoryGeneration.RefWithText(
        type = S34RefType.PUBLICATION, label = label, ownerId = "sec-1", text = null
    )

    private fun spec(vararg refs: String) = OratoryGeneration.Spec(
        mode = OratoryGeneration.Mode.DEVELOPMENT,
        action = OratoryGeneration.Action.INSERT,
        outlineId = "doc-1",
        outlineTitle = "DISCURSO",
        objective = null,
        orderedSections = listOf(OratoryGeneration.SectionLine("sec-1", 1, "PONTO", true)),
        current = OratoryGeneration.CurrentSection(
            sectionId = "sec-1", order = 1, title = "PONTO",
            content = "Corpo do ponto.", subsections = emptyList(),
            references = refs.map(::ref),
        ),
        previousSectionId = null,
        nextSectionId = null,
        training = emptyList(),
        contentSources = emptyList(),
        target = OratoryGeneration.Target.AfterSection("sec-1"),
    )

    @Test
    fun codigoS34NaoEhNumeroInventado() {
        assertTrue(
            OratoryFidelityCheck.isAuthorizedNumber(
                "34", spec(), "O S-34 nos lembra que os céus ensinam sobre ordem."
            )
        )
    }

    @Test
    fun anoDePublicacaoDoSpecEhAutorizado() {
        assertTrue(
            OratoryFidelityCheck.isAuthorizedNumber(
                "2011", spec("w11 1/7"), "Foi comentado na Sentinela de 2011."
            )
        )
    }

    @Test
    fun numeroInventadoContinuaFlagado() {
        assertFalse(
            OratoryFidelityCheck.isAuthorizedNumber(
                "42", spec(), "Havia 42 discípulos naquela reunião."
            )
        )
    }

    @Test
    fun checkNaoFlagS34NemAnoEflagInventado() {
        val r = OratoryFidelityCheck.check(
            "O S-34 nos lembra. Foi comentado na Sentinela de 2011. Havia 42 discípulos.",
            spec("w11 1/7"),
        )
        assertFalse(r.unsupportedNumbers.contains("34"))
        assertFalse(r.unsupportedNumbers.contains("2011"))
        assertEquals(listOf("42"), r.unsupportedNumbers)
    }
}
