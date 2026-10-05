package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.PubCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 — catálogo curado: curadoria, agrupamento e badge de acervo. */
class RecommendedPublicationsTest {

    private fun att(name: String) = AttachmentEntity(
        id = "a1", noteId = null, fileName = name, kind = "pdf",
        sizeBytes = 1L, appPath = "", indexed = true, addedAt = 0L
    )

    @Test
    fun curadoriaCobreTopEDerivaDoPubCatalog() {
        val symbols = RecommendedPublications.ALL.map { it.symbol }
        listOf("nwtsty", "dx", "th", "be", "lmd", "it-1", "w", "g", "mwb", "rr", "ia")
            .forEach { assertTrue("faltou $it", symbols.contains(it)) }
        assertTrue(RecommendedPublications.ALL.size >= 10)
        // Todos os símbolos existem no PubCatalog (título/links não vazios).
        RecommendedPublications.ALL.forEach { p ->
            assertNotNull("sem entrada: ${p.symbol}", PubCatalog.entryOf(p.symbol))
            assertTrue("sem título: ${p.symbol}", p.title.isNotBlank())
            assertTrue("sem página: ${p.symbol}", p.pageUrl.isNotBlank())
        }
    }

    @Test
    fun agrupamentoPorCategoriaSemGruposVazios() {
        val groups = RecommendedPublications.byCategory()
        assertEquals(
            listOf("Bíblia", "Apostilas", "Pesquisa", "Revistas", "Livros"),
            groups.map { it.first }
        )
        groups.forEach { assertTrue(it.second.isNotEmpty()) }
    }

    @Test
    fun acervoDetectaSimboloEEdicoes() {
        assertNotNull(findInLibrary("be", listOf(att("be_T.pdf"))))
        assertNotNull(findInLibrary("w", listOf(att("w19.03.pdf"))))
        assertNotNull(findInLibrary("g", listOf(att("g 6/07.pdf"))))
        assertNotNull(findInLibrary("mwb", listOf(att("mwb24.05.pdf"))))
        assertNotNull(findInLibrary("it-1", listOf(att("it-1.pdf"))))
        assertNull(findInLibrary("dx", listOf(att("outro.pdf"))))
    }
}
