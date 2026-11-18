package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.export.parseStructureMarkdown
import com.bettertalker.app.data.llm.GroundednessVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val r = verifyGeneratedText(
            generated,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
        assertTrue(r.hasRemovals)
        assertTrue(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
        assertTrue(r.removed.any { it.contains("os mansos herdarão a terra") })
        assertFalse(r.text.contains("os mansos herdarão a terra"))
    }

    @Test
    fun oratoria_textoLimpo_passaIntacto() {
        val generated = "Os justos possuirão a terra e viverão nela para sempre."
        val r = verifyGeneratedText(
            generated,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
        assertFalse(r.hasRemovals)
        assertEquals(generated, r.text)
    }

    @Test
    fun insiraTudo_citacaoSemApoio_removidaComAviso() {
        val markdown = "# INTRODUÇÃO\n\n" +
            "A Bíblia diz: \"a terra permanecerá para sempre e jamais passará\".\n\n" +
            "# DESENVOLVIMENTO\n\nDesenvolva o ponto principal do discurso.\n"
        val r = verifyGeneratedText(
            markdown,
            documentarySources = corpus.mapIndexed { index, text ->
                com.bettertalker.app.data.copilot.RecoveredDocument(
                    sourceId = "src-$index",
                    reference = "src-$index",
                    text = text,
                )
            },
        )
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

    private fun document(
        id: String,
        text: String,
        resolved: Boolean = true,
        waived: Boolean = false,
    ) = com.bettertalker.app.data.copilot.RecoveredDocument(
        sourceId = id,
        reference = id,
        text = text,
        resolved = resolved,
        waived = waived,
    )

    @Test
    fun documentoNaoResolvidoNaoAutorizaCitacao() {
        val generated = "A fonte diz: \"texto exclusivo do documento não resolvido\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(
                document("valid", "conteúdo válido diferente"),
                document("unresolved", "texto exclusivo do documento não resolvido", resolved = false),
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do documento não resolvido"))
    }

    @Test
    fun documentoWaivedNaoAutorizaCitacao() {
        val generated = "A fonte diz: \"texto exclusivo do documento dispensado\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(
                document("valid", "conteúdo válido diferente"),
                document("waived", "texto exclusivo do documento dispensado", waived = true),
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do documento dispensado"))
    }

    @Test
    fun structuralExtrasNaoAutorizamCitacao() {
        val generated = "O esboço diz: \"texto exclusivo do contexto estrutural\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "conteúdo válido diferente")),
            structuralExtras = listOf("texto exclusivo do contexto estrutural"),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("texto exclusivo do contexto estrutural"))
    }

    @Test
    fun documentoValidoAutorizaCitacaoCorrespondente() {
        val generated = "A fonte diz: \"texto exclusivo do documento válido recuperado\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "texto exclusivo do documento válido recuperado")),
        )
        assertFalse(r.hasRemovals)
    }

    @Test
    fun s34ETextoColadoNaoSaoProvaFactual() {
        val generated = "O texto colado diz: \"trecho do esboço colado sem origem documental\"."
        val r = verifyGeneratedText(
            generated,
            documentarySources = listOf(document("valid", "conteúdo válido diferente")),
            structuralExtras = listOf(
                "trecho do esboço colado sem origem documental",
                "S-34: objetivo e referências",
            ),
        )
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("trecho do esboço colado sem origem documental"))
    }

    // ---------- #7e-fix: aviso separado do conteúdo ----------

    @Test
    fun stripRemovalNotice_terminaComAviso_separa() {
        val text = "Conteúdo do discurso." + GroundednessVerifier.REMOVAL_NOTICE
        val (clean, notice) = stripRemovalNotice(text)
        assertEquals("Conteúdo do discurso.", clean)
        assertEquals(GroundednessVerifier.REMOVAL_NOTICE.trim(), notice)
        assertFalse(clean.contains("Observação"))
    }

    @Test
    fun stripRemovalNotice_semAviso_devolveNull() {
        val text = "Conteúdo limpo do discurso."
        val (clean, notice) = stripRemovalNotice(text)
        assertEquals(text, clean)
        assertNull(notice)
    }

    @Test
    fun stripRemovalNotice_noMeio_removeDevolveAviso() {
        val text = "Antes. " + GroundednessVerifier.REMOVAL_NOTICE.trim() + " Depois."
        val (clean, notice) = stripRemovalNotice(text)
        assertFalse(clean.contains("Observação"))
        assertEquals(GroundednessVerifier.REMOVAL_NOTICE.trim(), notice)
    }
}
