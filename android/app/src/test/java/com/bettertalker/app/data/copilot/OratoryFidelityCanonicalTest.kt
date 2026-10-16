package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #16-fix-f — fidelidade com canonicalização: range do S-34 ("Jó 38:31-33")
 * autoriza os versículos individuais e o número do versículo; livro por
 * extenso casa com a abreviação. Puro.
 */
class OratoryFidelityCanonicalTest {

    private fun ref(label: String) = OratoryGeneration.RefWithText(
        type = S34RefType.BIBLE, label = label, ownerId = "sec-1", text = null
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
    fun versiculoDentroDoRangeNaoEhInventado() {
        val r = OratoryFidelityCheck.check("Em Jó 38:32, Jeová pergunta isso.", spec("(Jó 38:31-33)"))
        assertFalse(r.inventedReferences.contains("Jó 38:32"))
    }

    @Test
    fun numeroDoVersiculoDentroDoRangeNaoEhSemApoio() {
        val r = OratoryFidelityCheck.check("Em Jó 38:32, Jeová pergunta isso.", spec("(Jó 38:31-33)"))
        assertFalse(r.unsupportedNumbers.contains("32"))
    }

    @Test
    fun livroPorExtensoAutorizadoNaoEhInventado() {
        val r = OratoryFidelityCheck.check(
            "Paulo escreveu em 1 Coríntios 14:33.", spec("(1Co 14:33, 40)")
        )
        assertFalse(r.inventedReferences.contains("1 Coríntios 14:33"))
    }
}
