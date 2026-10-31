package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2.7 — citações por título de livro com página/§. */
class BookTitleCitationTest {

    @Test
    fun beneficieSePaginasViraPageRange() {
        val refs = RefDetector.detect(
            "Siga o material do esboço. Beneficie-se, páginas 52-55; 166-169.",
        )
        println("DBG titleIndex be=" + PubCatalog.titleIndex().filter { it.second == "be" })
        println("DBG norm=" + normalizeText("Siga o material do esboço. Beneficie-se, páginas 52-55; 166-169."))
        println("DBG match=" + Regex("""\bbeneficie se\b""").find(normalizeText("Siga o material do esboço. Beneficie-se, páginas 52-55; 166-169.")))
        println("DBG be refs=" + refs)
        val be = refs.filter { it.editionKey == "book|be" }
        assertEquals(1, be.size)
        assertEquals(52, be.single().page)
        assertEquals(55, be.single().pageEnd)
    }

    @Test
    fun criadorPaginasViraPageRange() {
        val refs = RefDetector.detect("Veja Criador, páginas 8-9.")
        println("DBG ct refs=" + refs)
        val ct = refs.filter { it.editionKey == "book|ct" }
        assertEquals(1, ct.size)
        assertEquals(8, ct.single().page)
        assertEquals(9, ct.single().pageEnd)
    }

    @Test
    fun lvsPaginaEParagrafoNaoDuplicaEstudo() {
        val refs = RefDetector.detect("lvs 194 § 18")
        val lvs = refs.filter { it.editionKey == "book|lvs" }
        assertEquals(1, lvs.size)
        assertEquals(194, lvs.single().page)
        assertEquals(18, lvs.single().paragraph)
        assertTrue(lvs.single().chapter == null)
    }
}
