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
}
