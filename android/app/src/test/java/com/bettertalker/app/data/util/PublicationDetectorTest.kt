package com.bettertalker.app.data.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5b (Modo C) — detecção "isto parece texto de publicação JW.org?".
 * Heurística conservadora; nunca bloqueia (o comando confirma).
 */
class PublicationDetectorTest {

    @Test
    fun amostraDePublicacao_matchedComScoreAlto() {
        val sample = """
            O que a Bíblia promete para o futuro?
            A Sentinela e Despertai! explicam o Reino de Jeová Deus. (w13 15/5)
            Podemos confiar nessa promessa?
            Jeová Deus prometeu um paraíso. (bh 109) (g 11/06)
            O Reino vai durar para sempre?
            Por que isso importa?
            [Leia João 3:16.]
            [Leia Salmo 37:29.]
        """.trimIndent()
        val signal = PublicationDetector.looksLikePublication(sample)
        assertTrue(signal.hints.toString(), signal.matched)
        assertTrue("score=${signal.score} hints=${signal.hints}", signal.score >= 4)
        assertTrue(signal.hints.any { it.startsWith("refs de publicação") })
        assertTrue(signal.hints.any { it.startsWith("marcas [Leia") })
        assertTrue(signal.hints.any { it.startsWith("termos de publicações JW") })
        assertTrue(signal.hints.any { it.startsWith("estilo pergunta-resposta") })
    }

    @Test
    fun textoCurtoDeUsuario_naoMatched() {
        val signal = PublicationDetector.looksLikePublication("Olá, quero falar sobre fé")
        assertFalse(signal.matched)
        assertTrue(signal.score <= 1)
    }

    @Test
    fun esbozoS34_publicacaoReligiosaMatched() {
        // Esboço S-34 (título + seções + refs) — publicação religiosa em geral.
        val s34 = """
            N.º 35 É possível viver para sempre?

            NOTA: Ajude a assistência a meditar.

            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)
            Precisamos estar vivos para ter esperança. (Despertai! 08/13 pág. 6)
            [Leia Eclesiastes 3:11.]

            COMO A VIDA ETERNA FOI PERDIDA (4 min)
            [Leia João 3:16.]
            Adão e Eva desobedeceram. (w13 15/5) (Sentinela 01/04/09)
        """.trimIndent()
        val signal = PublicationDetector.looksLikePublication(s34)
        assertTrue(signal.hints.toString(), signal.matched)
    }
}
