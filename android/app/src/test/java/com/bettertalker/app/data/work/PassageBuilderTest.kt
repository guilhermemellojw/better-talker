package com.bettertalker.app.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageBuilderTest {

    private val rawSimple = """
        O amor de Deus é imenso e duradouro.
        Ele nos ajuda em todos os momentos.
        A fé move montanhas.
    """.trimIndent()

    @Test
    fun buildPassages_returnsNonEmptyListForValidInput() {
        assertTrue(buildPassages("att1", "nwt", rawSimple, null).isNotEmpty())
    }

    @Test
    fun buildPassages_allRefsAreNonBlank() {
        buildPassages("att1", "nwt", rawSimple, null).forEach {
            assertTrue("ref vazio para passage ${it.id}", it.ref.isNotBlank())
        }
    }

    @Test
    fun buildPassages_refStartsWithSymbol() {
        buildPassages("att1", "nwt", rawSimple, null).forEach {
            assertTrue(it.ref.startsWith("nwt "))
        }
    }

    @Test
    fun buildPassages_refContainsParagraphMarker() {
        buildPassages("att1", "nwt", rawSimple, null).forEach {
            assertTrue(it.ref.contains("§"))
        }
    }

    @Test
    fun buildPassages_ordIsSequentialFromZero() {
        val result = buildPassages("att1", "nwt", rawSimple, null)
        result.forEachIndexed { idx, passage -> assertEquals(idx, passage.ord) }
    }

    @Test
    fun buildPassages_idIsDeterministic() {
        val a = buildPassages("att1", "nwt", rawSimple, null)
        val b = buildPassages("att1", "nwt", rawSimple, null)
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test
    fun buildPassages_emptyInput_returnsEmptyList() {
        assertTrue(buildPassages("att1", "nwt", "", null).isEmpty())
    }

    @Test
    fun buildPassages_trainingCategoryIsPropagated() {
        buildPassages("att1", "be", rawSimple, "introducao").forEach {
            assertEquals("introducao", it.trainingCategory)
        }
    }

    @Test
    fun buildPassages_nullTrainingCategoryIsAccepted() {
        buildPassages("att1", "w19.03", rawSimple, null).forEach {
            assertNull(it.trainingCategory)
        }
    }

    @Test
    fun buildPassages_respectsMaxSentences() {
        val result = buildPassages("att1", "nwt", rawSimple, null, maxSentences = 1)
        assertEquals(1, result.size)
    }

    // ---------- T1b: teto proporcional ao texto (it unificado) ----------

    @Test
    fun capDeFrasesAcompanhaOTexto() {
        assertEquals(2500, passageCapFor(0))
        assertEquals(2500, passageCapFor(50_000))        // 1250 → piso
        assertEquals(25_000, passageCapFor(1_000_000))   // 1M/40
        assertEquals(200_000, passageCapFor(8_000_000))  // 8M/40
        assertEquals(300_000, passageCapFor(12_430_000)) // it completo → teto
        assertEquals(300_000, passageCapFor(50_000_000))
    }

    @Test
    fun publicacaoGrandeIndexaMuitoMaisQueOPiso() {
        assertTrue(passageCapFor(8_000_000) > MIN_PASSAGE_CAP)
    }
}
