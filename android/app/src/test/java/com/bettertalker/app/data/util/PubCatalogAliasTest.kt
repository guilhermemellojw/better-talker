package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.2.3a-fix4b: aliases do catálogo (`ifi` → `ia`) sem poluir o MAP.
 * `it-3` NÃO é alias — é símbolo válido (edição em 3 volumes, 1990-1992).
 */
class PubCatalogAliasTest {

    @Test
    fun resolveSymbol_ifi_returnsIa() {
        assertEquals("ia", PubCatalog.resolveSymbol("ifi"))
        assertEquals("ia", PubCatalog.resolveSymbol("IFI"))
    }

    @Test
    fun resolveSymbol_ia_returnsIa() {
        // canônico resolve para ele mesmo
        assertEquals("ia", PubCatalog.resolveSymbol("ia"))
    }

    @Test
    fun resolveSymbol_it3_returnsIt3() {
        // símbolo válido, não alias
        assertEquals("it-3", PubCatalog.resolveSymbol("it-3"))
        assertEquals("it-1", PubCatalog.resolveSymbol("it-1"))
        assertEquals("it-2", PubCatalog.resolveSymbol("it-2"))
    }

    @Test
    fun resolveSymbol_unknown_returnsNull() {
        assertNull(PubCatalog.resolveSymbol("xyz"))
        assertNull(PubCatalog.resolveSymbol("it-9"))
    }

    @Test
    fun isSymbol_ifi_returnsTrue() {
        // alias é aceito (tolerância a esboços antigos)
        assertTrue(PubCatalog.isSymbol("ifi"))
        assertTrue(PubCatalog.isSymbol("ia"))
        // "na" continua excluído (preposição)
        assertFalse(PubCatalog.isSymbol("na"))
    }

    @Test
    fun entryOf_ifi_returnsIaEntry() {
        val entry = PubCatalog.entryOf("ifi")
        assertNotNull(entry)
        assertEquals("ia", entry!!.symbol)
        assertEquals("Imite a Sua Fé", entry.title)
        // ia corrigido no MAP
        assertEquals("Imite a Sua Fé", PubCatalog.titleOf("ia"))
    }

    // ---------- catálogo curado: dx e nwt ----------

    @Test
    fun resolveSymbol_dx_returnsDx() {
        assertEquals("dx", PubCatalog.resolveSymbol("dx"))
        assertEquals("dx", PubCatalog.resolveSymbol("DX"))
        val e = PubCatalog.entryOf("dx")
        assertNotNull(e)
        assertTrue(e!!.title.contains("Guia de Pesquisa"))
        assertTrue(e.wol.startsWith("https://www.jw.org/finder"))
    }

    @Test
    fun resolveSymbol_nwt_e_nwtsty_returnsEntradas() {
        assertEquals("nwt", PubCatalog.resolveSymbol("nwt"))
        assertEquals("nwtsty", PubCatalog.resolveSymbol("nwtsty"))
        assertEquals("Tradução do Novo Mundo", PubCatalog.titleOf("nwt"))
        assertEquals("Bíblia de Estudo", PubCatalog.titleOf("nwtsty"))
        assertEquals("bible", PubCatalog.entryOf("nwtsty")?.kind)
        assertTrue(PubCatalog.entryOf("nwtsty")!!.wol.contains("/nwtsty"))
    }
}
