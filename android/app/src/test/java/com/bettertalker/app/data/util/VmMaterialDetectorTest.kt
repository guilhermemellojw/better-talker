package com.bettertalker.app.data.util

import com.bettertalker.app.S34Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---------- UX "Colar e Pronto": parte solta (sem cabeçalho) ----------

    @Test
    fun parteSolta_textoRealFalho_detectaDemoComCenarioETipo() {
        val part = single(
            "5. Cultivando o interesse (4 min) DE CASA EM CASA. Fale sobre uma das verdades " +
                "do apêndice A da brochura Ame as Pessoas. (lmd lição 7 ponto 4)"
        )
        assertEquals(VmNature.DEMO, part.nature)
        assertEquals("DE CASA EM CASA", part.scenario)
        assertEquals("Cultivando o interesse", part.partType)
        assertEquals("lmd lição 7 ponto 4", part.reference)
        assertEquals(5, part.partNumber)
        assertEquals(4, part.minutes)
        assertEquals(VmSection.MINISTRY, part.section)
    }

    @Test
    fun referencia_lffComIntervaloDeVersiculos() {
        val part = single("4. Iniciando conversas (3 min) DE CASA EM CASA. Leia lff 3:1-3 com a pessoa.")
        assertEquals("lff 3:1-3", part.reference)
    }

    @Test
    fun referencia_ausente_ficaNula() {
        val part = single("5. Cultivando o interesse (4 min) DE CASA EM CASA. Fale livremente.")
        assertNull(part.reference)
    }

    @Test
    fun parte6_comDemonstracao_detectaDemo() {
        val part = single("6. Explicando suas crenças (5 min) Demonstração.")
        assertEquals(VmNature.DEMO, part.nature)
        assertEquals("Explicando suas crenças", part.partType)
    }

    @Test
    fun parte6_discurso_detectaTalk() {
        val part = single("6. Discurso (5 min)")
        assertEquals(VmNature.TALK, part.nature)
        assertEquals("Discurso", part.partType)
    }

    @Test
    fun parte6_explicarCrencas_comDemonstracao_detectaDemo() {
        val part = single(
            "6. Explicar as suas crenças (5 min) Demonstração. " +
                "Prepare uma resposta respeitosa sobre a esperança."
        )
        assertEquals(VmNature.DEMO, part.nature)
        assertEquals("Explicar as suas crenças", part.partType)
    }

    @Test
    fun parte6_oQueVoceDiria_comConsideracao_detectaConsideration() {
        val part = single(
            "6. O que você diria? (6 min) Consideração. " +
                "Pergunte à assistência como responderiam."
        )
        assertEquals(VmNature.CONSIDERATION, part.nature)
        assertEquals("O que você diria?", part.partType)
    }

    @Test
    fun parte5_explicarCrencas_comDiscurso_detectaTalk() {
        val part = single(
            "5. Explicar as suas crenças (5 min) Discurso. " +
                "Use uma abordagem simples e respeitosa."
        )
        assertEquals(VmNature.TALK, part.nature)
        assertEquals("Explicar as suas crenças", part.partType)
    }

    @Test
    fun parte4_perseveranca_comDiscurso_detectaTalk() {
        val part = single(
            "4. Perseverança – O que Paulo fez (7 min) Discurso com participação. " +
                "Convide a assistência a comentar."
        )
        assertEquals(VmNature.TALK, part.nature)
        assertEquals("Perseverança", part.partType)
    }

    @Test
    fun parte4_testemunhoInformal_detectaDemo() {
        val part = single("4. Iniciando conversas (3 min) TESTEMUNHO INFORMAL.")
        assertEquals(VmNature.DEMO, part.nature)
        assertEquals("TESTEMUNHO INFORMAL", part.scenario)
        assertEquals("Iniciando conversas", part.partType)
    }

    @Test
    fun parte7_consideracao_detectaTalk() {
        val part = single("7. A luz dos justos brilha intensamente (8 min) Consideração.")
        assertEquals(VmNature.TALK, part.nature)
    }

    @Test
    fun parteSolta_comPapeisSemCenario_detectaDemo() {
        val part = single(
            """
            5. Visita inicial (4 min)
            Publicador: Olá, tudo bem?
            Morador: Tudo bem, obrigado.
            """.trimIndent()
        )
        assertEquals(VmSection.MINISTRY, part.section)
        assertEquals(5, part.partNumber)
        assertEquals(VmNature.DEMO, part.nature)
        assertTrue(part.body.contains("Publicador:"))
    }

    @Test
    fun numeroEMinutosSemContextoVm_naoDetecta() {
        assertTrue(
            detectVmMaterial(
                "5. Reunião de pais (4 min)\nFalar sobre a escola e as notas."
            ).isEmpty()
        )
    }
}
