package com.bettertalker.app.data.copilot

import com.bettertalker.app.S34Fixture
import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34Reference
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-e — referenceTexts resolvidos por linha (fake do acervo):
 * linha → textos das refs BÍBLICAS; sem texto = linha fora (preserva o
 * "NÃO disponível" do prompt). Puro/testável.
 */
class OratoryReferenceTextsTest {

    private fun bibleRef(raw: String, order: Int = 1) = S34Reference(
        type = S34RefType.BIBLE,
        rawText = raw,
        normalizedReference = raw,
        order = order,
        sourceLine = order,
    )

    private fun section(refs: List<S34Reference>) = S34Section(
        id = "sec-1", order = 1, title = "PONTO",
        content = "Corpo do ponto.", references = refs, sourceLine = 1,
    )

    @Test
    fun linhaComRefBiblicaViraTextoAutorizado() = runBlocking {
        val linha = "(Sal 103:20, 21; Da 7:10)"
        val map = OratoryGeneration.referenceTextsFor(
            listOf(section(listOf(bibleRef(linha)))),
        ) { raw ->
            if (raw == linha) listOf("Louvem a Jeová.", "Um rio de fogo corria.") else emptyList()
        }
        assertEquals("Louvem a Jeová. Um rio de fogo corria.", map[linha])
    }

    @Test
    fun semRefsBiblicasMapVazio() = runBlocking {
        val pub = S34Reference(
            type = S34RefType.PUBLICATION,
            rawText = "w11 1/7", normalizedReference = "w11", order = 1, sourceLine = 1,
        )
        val map = OratoryGeneration.referenceTextsFor(listOf(section(listOf(pub)))) { listOf("x") }
        assertTrue(map.isEmpty())
    }

    @Test
    fun semTextoNoAcervoLinhaFicaFora() = runBlocking {
        val map = OratoryGeneration.referenceTextsFor(
            listOf(section(listOf(bibleRef("(Sal 103:20, 21)")))),
        ) { emptyList() }
        assertTrue(map.isEmpty())
    }

    @Test
    fun specComOsTextosPreencheRefWithText() = runBlocking {
        val doc: S34Document = S34Parser.parseS34(S34Fixture.TEXT)
        val view = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val ref = doc.sections.first { it.id == "sec-2" }
            .references.first { it.type == S34RefType.BIBLE }
        val texts = mapOf(ref.rawText to "Texto autorizado de teste.")
        val r = OratoryGeneration.spec(
            OratoryGeneration.Mode.DEVELOPMENT, doc, view,
            OratoryGeneration.Action.INSERT, texts,
        )
        val spec = (r as OratoryGeneration.Result.Ready).spec
        val resolved = spec.current!!.references.first { it.label == ref.rawText }
        assertEquals("Texto autorizado de teste.", resolved.text)
    }
}
