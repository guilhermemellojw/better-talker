package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.export.parseStructureMarkdown
import com.bettertalker.app.data.llm.GroundednessVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #7e — gate de groundedness nos caminhos de geração fora do chat
 * (oratória e "insira tudo"), via a função pura compartilhada.
 */
class GenerationVerificationTest {

    private val corpus = listOf(
        "Salmo 37:29 — Os justos possuirão a terra e viverão nela para sempre.",
        "w25.07 § 2 — Jeová sustenta seus servos fiéis.",
    )

    @Test
    fun oratoria_aspasInventadas_removeFraseComAviso() {
        val generated = "A Bíblia promete: \"os mansos herdarão a terra e viverão nela para sempre\". Confie nisso."
        val r = verifyGeneratedText(generated, extraSources = corpus)
        assertTrue(r.hasRemovals)
        assertTrue(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
        assertTrue(r.removed.any { it.contains("os mansos herdarão a terra") })
        assertFalse(r.text.contains("os mansos herdarão a terra"))
    }

    @Test
    fun oratoria_textoLimpo_passaIntacto() {
        val generated = "Os justos possuirão a terra e viverão nela para sempre."
        val r = verifyGeneratedText(generated, extraSources = corpus)
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    @Test
    fun insiraTudo_citacaoSemApoio_removidaComAviso() {
        val markdown = "# INTRODUÇÃO\n\n" +
            "A Bíblia diz: \"a terra permanecerá para sempre e jamais passará\".\n\n" +
            "# DESENVOLVIMENTO\n\nDesenvolva o ponto principal do discurso.\n"
        val r = verifyGeneratedText(markdown, extraSources = corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("jamais passará"))
        assertTrue(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
        val sections = parseStructureMarkdown(r.text)
        assertTrue(sections.isNotEmpty())
        assertFalse(sections.joinToString(" ") { it.contentHtml }.contains("jamais passará"))
    }

    @Test
    fun corpusVazio_naoBloqueia() {
        val generated = "Ele disse: \"uma frase inventada qualquer sem apoio nenhum\"."
        val r = verifyGeneratedText(generated, extraSources = emptyList())
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }
}
