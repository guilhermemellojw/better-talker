package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #27-fix (P0): chunking do texto do Ensaio + regra de prontidão do router. */
class TtsChunkTest {

    @Test
    fun textoCurto_viraUmBloco() {
        assertEquals(listOf("Olá, tudo bem?"), chunkForTts("Olá, tudo bem?"))
    }

    @Test
    fun textoLongo_quebraEmBlocosRespeitandoOTeto() {
        val text = (1..40).joinToString(" ") { "Esta é a frase número $it do discurso." }
        val chunks = chunkForTts(text, maxChars = 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
    }

    @Test
    fun nenhumConteudoEPerdido() {
        val text = "Primeira frase. Segunda frase! Terceira pergunta? Quarta: fim."
        val chunks = chunkForTts(text, maxChars = 30)
        val rejoined = chunks.joinToString(" ")
        for (word in listOf("Primeira", "Segunda", "Terceira", "Quarta", "fim")) {
            assertTrue(rejoined, rejoined.contains(word))
        }
    }

    @Test
    fun fraseGigante_semEspacos_cortaNoTeto() {
        val giant = "a".repeat(1000)
        val chunks = chunkForTts(giant, maxChars = 300)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(1000, chunks.sumOf { it.length })
    }

    @Test
    fun vazio_retornaListaVazia() {
        assertTrue(chunkForTts("   \n  ").isEmpty())
    }

    // ---------- regra de prontidão do router ----------

    @Test
    fun routerReady_premiumValePiperOuNativo() {
        assertTrue(routerReady(premium = true, piperReady = true, nativeReady = false))
        assertTrue(routerReady(premium = true, piperReady = false, nativeReady = true))
        assertFalse(routerReady(premium = true, piperReady = false, nativeReady = false))
    }

    @Test
    fun routerReady_semPremium_soNativo() {
        assertTrue(routerReady(premium = false, piperReady = true, nativeReady = true))
        assertFalse(routerReady(premium = false, piperReady = true, nativeReady = false))
    }
}
