package com.bettertalker.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix (Exportação) — nome-base seguro para share/PDF: entra legível, sai
 * legível (não vira hash) e sem os caracteres que quebram o picker.
 */
class ExportFileBaseNameTest {

    @Test
    fun mantemLegibilidadeSemVirarHash() {
        assertEquals(
            "N 28 — Mostre respeito e amor no seu casamento",
            exportFileBaseName("N.º 28 — Mostre respeito e amor no seu casamento"),
        )
    }

    @Test
    fun removeCaracteresQueQuebramOPicker() {
        val sujo = "aºbªc/d\\e:f*g?h\"i<j>k|l"
        val limpo = exportFileBaseName(sujo)
        for (c in listOf('º', 'ª', '/', '\\', ':', '*', '?', '"', '<', '>', '|')) {
            assertFalse("sobrou: $c", limpo.contains(c))
        }
        assertTrue(limpo.contains("a"))
        assertTrue(limpo.contains("k"))
    }

    @Test
    fun vazioViraDiscursoETruncaEm60() {
        assertEquals("Discurso", exportFileBaseName("   "))
        assertEquals("Discurso", exportFileBaseName("ºª"))
        val longo = "x".repeat(100)
        assertEquals(60, exportFileBaseName(longo).length)
    }
}
