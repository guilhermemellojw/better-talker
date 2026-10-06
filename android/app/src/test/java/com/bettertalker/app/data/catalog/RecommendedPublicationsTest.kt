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
        listOf("nwt", "it", "rsg", "th", "be", "lmd", "s38", "w", "g", "mwb", "rr", "ia")
            .forEach { assertTrue("faltou $it", symbols.contains(it)) }
        // nwtsty/it-1/it-2/it-3/dx não têm card próprio (não baixáveis ou unificados).
        listOf("nwtsty", "it-1", "it-2", "it-3", "dx").forEach {
            assertFalse("não deveria ter card: $it", symbols.contains(it))
        }
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
            listOf("Bíblia", "Apostilas", "Instruções", "Pesquisa", "Revistas", "Livros"),
            groups.map { it.first }
        )
        groups.forEach { assertTrue(it.second.isNotEmpty()) }
    }

    @Test
    fun s38NaCategoriaInstrucoes() {
        val s38 = RecommendedPublications.ALL.first { it.symbol == "s38" }
        assertEquals("Instruções", s38.category)
        assertTrue(s38.title.contains("Instruções para a Reunião"))
        assertTrue(s38.downloadUrl.contains("orientacoes"))
        assertFalse(s38.downloadUrl.contains("wol.jw.org"))
    }

    @Test
    fun acervoDetectaS38PorNomeCompacto() {
        assertNotNull(findInLibrary("s38", listOf(att("S-38_T_194.docx"))))
        assertNotNull(findInLibrary("s-38", listOf(att("S-38_T_035.jwpub"))))
        assertNull(findInLibrary("s38", listOf(att("outro.docx"))))
    }

    @Test
    fun acervoDetectaSimboloEEdicoes() {
        assertNotNull(findInLibrary("be", listOf(att("be_T.pdf"))))
        assertNotNull(findInLibrary("w", listOf(att("w19.03.pdf"))))
        assertNotNull(findInLibrary("g", listOf(att("g 6/07.pdf"))))
        assertNotNull(findInLibrary("mwb", listOf(att("mwb24.05.pdf"))))
        assertNotNull(findInLibrary("it", listOf(att("it_T.jwpub"))))
        assertNotNull(findInLibrary("rsg", listOf(att("rsg_T.jwpub"))))
        assertNotNull(findInLibrary("nwt", listOf(att("nwt_T.pdf"))))
        assertNull(findInLibrary("dx", listOf(att("outro.pdf"))))
    }

    // ---------- T1 (correção): download no jw.org, nunca na WOL ----------

    @Test
    fun downloadApontaParaJwOrgENuncaWol() {
        RecommendedPublications.ALL.forEach { p ->
            assertTrue(
                "download fora do jw.org: ${p.symbol} -> ${p.downloadUrl}",
                p.downloadUrl.startsWith("https://www.jw.org/")
            )
            assertFalse("WOL como download: ${p.symbol}", p.downloadUrl.contains("wol.jw.org"))
        }
    }

    @Test
    fun paginasEspecificasPorPublicacao() {
        // Páginas específicas (não a home/finder genérico).
        val bySymbol = RecommendedPublications.ALL.associateBy { it.symbol }
        assertTrue(bySymbol["nwt"]!!.downloadUrl.contains("biblia/nwt"))
        assertTrue(bySymbol["th"]!!.downloadUrl.contains("leitura-e-ensino"))
        assertTrue(bySymbol["be"]!!.downloadUrl.contains("Beneficie-se"))
        assertTrue(bySymbol["lmd"]!!.downloadUrl.contains("ame-pessoas"))
        assertTrue(bySymbol["it"]!!.downloadUrl.contains("estudo-perspicaz"))
        assertTrue(bySymbol["rsg"]!!.downloadUrl.contains("guia-de-pesquisa"))
        assertTrue(bySymbol["rr"]!!.downloadUrl.contains("adoracao-pura"))
        assertTrue(bySymbol["mwb"]!!.downloadUrl.contains("jw-apostila-do-mes"))
    }

    @Test
    fun wolFicaApenasComoSecundaria() {
        // pageUrl (leitura) continua WOL; nunca é o destino do Baixar.
        assertTrue(
            RecommendedPublications.ALL.first { it.symbol == "rr" }.pageUrl.contains("wol.jw.org")
        )
        // rsg (e outros sem rota WOL de publicação) usam página própria.
        RecommendedPublications.ALL
            .filter { it.pageUrl.contains("wol.jw.org") }
            .forEach { p ->
                assertFalse("pageUrl == downloadUrl em ${p.symbol}", p.downloadUrl == p.pageUrl)
            }
    }
}
