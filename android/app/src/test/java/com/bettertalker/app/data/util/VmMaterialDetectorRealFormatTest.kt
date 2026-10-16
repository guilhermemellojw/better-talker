package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #24-fix (a) — o formato REAL do material da apostila (Título + Tópico N)
 * e o cabeçalho colado ao título da parte viram V&M TALK; os formatos do
 * #8b continuam funcionando (regressão).
 */
class VmMaterialDetectorRealFormatTest {

    // ---------- A1: "Título: X (N min)" + "Tópico N:" (sem cabeçalho) ----------

    @Test
    fun tituloComTopicos_formatoReal_detectaTalk() {
        val text = """
            Título: Ter esperança é o segredo para o contentamento (10 min)

            Tópico 1: Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)

            Tópico 2: Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)

            Tópico 3: Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
        """.trimIndent()

        val part = detectVmMaterial(text).single()
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals("Ter esperança é o segredo para o contentamento", part.title)
        assertEquals(10, part.minutes)
        assertEquals(VmNature.TALK, part.nature)
        assertTrue(part.body.contains("Tópico 1:"))
        assertTrue(part.body.contains("Jer. 45:3"))
    }

    // ---------- A2: cabeçalho colado ao título da parte ----------

    @Test
    fun cabecalhoColadoAoTituloDaParte_detectaTalk() {
        val text = """
            TESOUROS DA PALAVRA DE DEUS 1. Ter esperança é o segredo para o contentamento (10 min)

            Tópico 1: Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3)
        """.trimIndent()

        val part = detectVmMaterial(text).single()
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals("Ter esperança é o segredo para o contentamento", part.title)
        assertEquals(10, part.minutes)
        assertEquals(VmNature.TALK, part.nature)
        assertTrue(part.body.contains("Tópico 1:"))
    }

    // ---------- A3: cabeçalho + "Título: X (N min)" em linha própria ----------

    @Test
    fun cabecalhoComTituloEmLinhaPropria_detectaTalk() {
        val text = """
            TESOUROS DA PALAVRA DE DEUS

            Título: Ter esperança é o segredo para o contentamento (10 min)

            Tópico 1: Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3)
        """.trimIndent()

        val part = detectVmMaterial(text).single()
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals("Ter esperança é o segredo para o contentamento", part.title)
        assertEquals(10, part.minutes)
        assertEquals(VmNature.TALK, part.nature)
        assertTrue(part.body.contains("Tópico 1:"))
    }

    // ---------- A5: formatos do #8b continuam funcionando (regressão) ----------

    @Test
    fun regressao_formatoInlineDo8b() {
        val part = detectVmMaterial(
            """
            TESOUROS DA PALAVRA DE DEUS, ponto 1. (10min)
            Título do discurso
            Corpo do tópico.
            """.trimIndent()
        ).single()
        assertEquals(VmNature.TALK, part.nature)
        assertEquals(1, part.partNumber)
        assertEquals("Título do discurso", part.title)
        assertEquals(10, part.minutes)
    }

    @Test
    fun regressao_formatoDuasLinhasDo8b() {
        val part = detectVmMaterial(
            """
            TESOUROS DA PALAVRA DE DEUS

            1. Ter esperança (10 min)

            Corpo do ponto.
            """.trimIndent()
        ).single()
        assertEquals(VmNature.TALK, part.nature)
        assertEquals(1, part.partNumber)
        assertEquals("Ter esperança", part.title)
        assertEquals(10, part.minutes)
    }

    // ---------- Não sequestrar texto comum ----------

    @Test
    fun tituloSemTopicos_naoSequestra() {
        assertTrue(
            detectVmMaterial(
                "Título: Um texto qualquer (5 min)\n\nSó um parágrafo sem tópicos."
            ).isEmpty()
        )
    }

    @Test
    fun textoAleatorio_vazio() {
        assertTrue(
            detectVmMaterial("Uma anotação qualquer.\nOutra linha sem cabeçalho.").isEmpty()
        )
    }
}
