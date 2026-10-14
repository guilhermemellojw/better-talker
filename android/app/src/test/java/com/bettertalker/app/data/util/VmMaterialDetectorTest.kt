package com.bettertalker.app.data.util

import com.bettertalker.app.S34Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8b — detecção de material da reunião Vida e Ministério: cabeçalhos de
 * seção, número da parte (no cabeçalho ou no corpo), tempo e natureza.
 */
class VmMaterialDetectorTest {

    private fun single(text: String) = detectVmMaterial(text).single()

    @Test
    fun tesouros1_formatoCabecalho_detectaTalk() {
        val part = single(
            """
            TESOUROS DA PALAVRA DE DEUS, ponto 1. (10min)
            Eles perguntaram: "Por que vocês fazem isso?"
            A Bíblia responde em João 3:16.
            """.trimIndent()
        )
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals("Eles perguntaram: \"Por que vocês fazem isso?\"", part.title)
        assertEquals(10, part.minutes)
        assertEquals(VmNature.TALK, part.nature)
        assertTrue(part.body.contains("João 3:16"))
    }

    @Test
    fun tesouros2_formatoCorpo_joiasEspirituais() {
        val part = single(
            """
            TESOUROS DA PALAVRA DE DEUS

            2. Joias espirituais
            (8 min)

            Tesouros escondidos na Palavra de Deus.
            """.trimIndent()
        )
        assertEquals(VmNature.GEMS, part.nature)
        assertEquals(2, part.partNumber)
        assertEquals("Joias espirituais", part.title)
        assertEquals(8, part.minutes)
    }

    @Test
    fun tesouros3_leituraDaBiblia() {
        val part = single(
            """
            TESOUROS DA PALAVRA DE DEUS, ponto 3. (4 min)
            Leitura da Bíblia
            Corpo da leitura.
            """.trimIndent()
        )
        assertEquals(VmNature.BIBLE_READING, part.nature)
        assertEquals(4, part.minutes)
    }

    @Test
    fun nossaVida7_discurso_talk() {
        val part = single(
            """
            NOSSA VIDA CRISTÃ

            7. Faça discípulos (15 min)

            Corpo do discurso.
            """.trimIndent()
        )
        assertEquals(VmNature.TALK, part.nature)
        assertEquals("Faça discípulos", part.title)
        assertEquals(15, part.minutes)
    }

    @Test
    fun nossaVida8_estudoDeCongregacao() {
        val part = single(
            """
            NOSSA VIDA CRISTÃ, ponto 8. (30 min)
            Estudo bíblico de congregação
            Corpo do estudo.
            """.trimIndent()
        )
        assertEquals(VmNature.CONGREGATION_STUDY, part.nature)
        assertEquals(30, part.minutes)
    }

    @Test
    fun faca6_comDiscurso_talk() {
        val part = single(
            """
            FAÇA SEU MELHOR NO MINISTÉRIO

            6. Discurso: Seja corajoso (5 min)

            Corpo do discurso.
            """.trimIndent()
        )
        assertEquals(VmNature.TALK, part.nature)
        assertEquals("Discurso: Seja corajoso", part.title)
        assertEquals(5, part.minutes)
    }

    @Test
    fun faca6_semDiscurso_demo() {
        val part = single(
            """
            FAÇA SEU MELHOR NO MINISTÉRIO

            6. Comece uma conversa (4 min)

            Corpo da demonstração.
            """.trimIndent()
        )
        assertEquals(VmNature.DEMO, part.nature)
    }

    @Test
    fun apostilaInteira_detecta8Partes() {
        val parts = detectVmMaterial(
            """
            TESOUROS DA PALAVRA DE DEUS, ponto 1. (10min)
            Eles perguntaram: "Por que vocês fazem isso?"
            Corpo do tópico 1.

            TESOUROS DA PALAVRA DE DEUS, ponto 2. (8 min)
            Joias espirituais
            Corpo do tópico 2.

            TESOUROS DA PALAVRA DE DEUS, ponto 3. (4 min)
            Leitura da Bíblia
            Corpo do tópico 3.

            FAÇA SEU MELHOR NO MINISTÉRIO, ponto 4. (3 min)
            Comece uma conversa
            Corpo do tópico 4.

            FAÇA SEU MELHOR NO MINISTÉRIO, ponto 5. (3 min)
            Faça revisitas
            Corpo do tópico 5.

            FAÇA SEU MELHOR NO MINISTÉRIO, ponto 6. (5 min)
            Discurso: Explique sua fé
            Corpo do tópico 6.

            NOSSA VIDA CRISTÃ, ponto 7. (15 min)
            Faça discípulos
            Corpo do tópico 7.

            NOSSA VIDA CRISTÃ, ponto 8. (30 min)
            Estudo bíblico de congregação
            Corpo do tópico 8.
            """.trimIndent()
        )
        assertEquals(8, parts.size)
        assertEquals(
            listOf(
                VmNature.TALK, VmNature.GEMS, VmNature.BIBLE_READING,
                VmNature.DEMO, VmNature.DEMO, VmNature.TALK,
                VmNature.TALK, VmNature.CONGREGATION_STUDY,
            ),
            parts.map { it.nature },
        )
    }

    @Test
    fun s34_naoEhVm_vazio() {
        assertTrue(detectVmMaterial(S34Fixture.TEXT).isEmpty())
    }

    @Test
    fun textoAleatorio_vazio() {
        assertTrue(
            detectVmMaterial("Uma anotação qualquer.\nOutra linha sem cabeçalho.").isEmpty()
        )
    }

    @Test
    fun cabecalhoMinusculo_detecta() {
        val part = single(
            """
            tesouros da palavra de deus, ponto 1. (10min)
            Título do discurso
            Corpo.
            """.trimIndent()
        )
        assertEquals(VmNature.TALK, part.nature)
        assertEquals(10, part.minutes)
    }

    @Test
    fun semNumeroNoCabecalho_numeroNoCorpo() {
        val part = single(
            """
            NOSSA VIDA CRISTÃ

            7. Faça discípulos
            (15 min)

            Corpo do discurso.
            """.trimIndent()
        )
        assertEquals(7, part.partNumber)
        assertEquals("Faça discípulos", part.title)
        assertEquals(15, part.minutes)
        assertEquals(VmNature.TALK, part.nature)
    }
}
