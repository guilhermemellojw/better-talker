package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.PubCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- T1 (correção): download no jw.org, nunca na WOL ----------

    @Test
    fun downloadApontaParaJwOrgENuncaWol() {
        RecommendedPublications.ALL.forEach { p ->
            assertTrue(
                "download fora do jw.org: ${p.symbol} -> ${p.downloadUrl}",
                p.downloadUrl.startsWith("https://www.jw.org/finder")
            )
            assertTrue("sem wfile: ${p.symbol}", p.downloadUrl.contains("wfile=${p.symbol}"))
            assertFalse("WOL como download: ${p.symbol}", p.downloadUrl.contains("wol.jw.org"))
        }
    }

    @Test
    fun wolFicaApenasComoSecundaria() {
        // pageUrl (leitura) continua WOL; nunca é o destino do Baixar.
        assertTrue(
            RecommendedPublications.ALL.first { it.symbol == "rr" }.pageUrl.contains("wol.jw.org")
        )
        // dx não tem rota WOL de publicação (usa finder já no catálogo).
        RecommendedPublications.ALL
            .filter { it.pageUrl.contains("wol.jw.org") }
            .forEach { p ->
                assertFalse("pageUrl == downloadUrl em ${p.symbol}", p.downloadUrl == p.pageUrl)
            }
    }
}
