package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-d — textos autorizados das refs do spec entram no corpus do gate
 * (o modelo vê as refs no prompt; o gate precisa ver o mesmo). Puro.
 */
class AuthorizedRefTextsTest {

    private fun ref(label: String, text: String?) = OratoryGeneration.RefWithText(
        type = S34RefType.BIBLE, label = label, ownerId = "sec-1", text = text
    )

    private fun spec(current: List<OratoryGeneration.RefWithText>) = OratoryGeneration.Spec(
        mode = OratoryGeneration.Mode.DEVELOPMENT,
        action = OratoryGeneration.Action.INSERT,
        outlineId = "doc-1",
        outlineTitle = "DISCURSO",
        objective = null,
        orderedSections = listOf(OratoryGeneration.SectionLine("sec-1", 1, "PONTO", true)),
        current = OratoryGeneration.CurrentSection(
            sectionId = "sec-1", order = 1, title = "PONTO",
            content = "Corpo do ponto.", subsections = emptyList(),
            references = current,
        ),
        previousSectionId = null,
        nextSectionId = null,
        training = emptyList(),
        contentSources = emptyList(),
        target = OratoryGeneration.Target.AfterSection("sec-1"),
    )

    @Test
    fun refsComTextoTrazemOsTextos() {
        val s = spec(
            listOf(
                ref("Sal 103:20", "Louvem a Jeová, todos os seus anjos."),
                ref("Jó 38:31-33", "Será que você pode atar as cordas da constelação de Quima?"),
                ref("Da 7:10", "Um rio de fogo corria, saindo de diante dele."),
            )
        )
        assertEquals(
            listOf(
                "Louvem a Jeová, todos os seus anjos.",
                "Será que você pode atar as cordas da constelação de Quima?",
                "Um rio de fogo corria, saindo de diante dele.",
            ),
            OratoryGeneration.authorizedRefTexts(s),
        )
    }

    @Test
    fun refsSemTextoOuEmBrancoFiltradas() {
        val s = spec(
            listOf(
                ref("w11 1/7", null),
                ref("Sal 103:20", "Louvem a Jeová, todos os seus anjos."),
                ref("w14 5/15", "   "),
            )
        )
        assertEquals(
            listOf("Louvem a Jeová, todos os seus anjos."),
            OratoryGeneration.authorizedRefTexts(s),
        )
    }

    @Test
    fun semRefsListaVazia() {
        assertTrue(OratoryGeneration.authorizedRefTexts(spec(emptyList())).isEmpty())
    }
}
