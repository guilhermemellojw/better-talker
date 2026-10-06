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
    fun resolveSymbol_it1EIt2ValidosIt3Removido() {
        assertEquals("it-1", PubCatalog.resolveSymbol("it-1"))
        assertEquals("it-2", PubCatalog.resolveSymbol("it-2"))
        // it-3 não existe (a WOL publica só 2 volumes).
        assertNull(PubCatalog.resolveSymbol("it-3"))
    }

    @Test
    fun resolveSymbol_itUnificadoEDxAliasDeRsg() {
        assertEquals("it", PubCatalog.resolveSymbol("it"))
        assertEquals("it", PubCatalog.unifiedOf("it-1"))
        assertEquals("it", PubCatalog.unifiedOf("IT-2"))
        assertNull(PubCatalog.unifiedOf("it"))
        // dx é a edição antiga do Guia de Pesquisa (atual: rsg).
        assertEquals("rsg", PubCatalog.resolveSymbol("dx"))
        assertEquals("Guia de Pesquisa", PubCatalog.titleOf("rsg"))
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
    fun resolveSymbol_dx_aliasParaRsg() {
        // dx é a edição antiga do Guia de Pesquisa; o atual é rsg.
        assertEquals("rsg", PubCatalog.resolveSymbol("dx"))
        assertEquals("rsg", PubCatalog.resolveSymbol("DX"))
        val e = PubCatalog.entryOf("dx")
        assertNotNull(e)
        assertEquals("rsg", e!!.symbol)
        assertTrue(e.title.contains("Guia de Pesquisa"))
        assertTrue(e.wol.startsWith("https://www.jw.org/"))
    }

    @Test
    fun resolveSymbol_nwt_validoENwtstyRemovido() {
        assertEquals("nwt", PubCatalog.resolveSymbol("nwt"))
        assertEquals("Tradução do Novo Mundo", PubCatalog.titleOf("nwt"))
        // nwtsty não é baixável em nenhum formato (só online/JW Library).
        assertNull(PubCatalog.resolveSymbol("nwtsty"))
    }

    @Test
    fun resolveSymbol_s38_returnsS38() {
        assertEquals("s-38", PubCatalog.resolveSymbol("s38"))
        assertEquals("s-38", PubCatalog.resolveSymbol("S38"))
        assertEquals("s-38", PubCatalog.resolveSymbol("s-38"))
        val e = PubCatalog.entryOf("s38")
        assertNotNull(e)
        assertTrue(e!!.title.contains("Instruções para a Reunião"))
        assertEquals("manual", e.kind)
        assertTrue(e.wol.contains("S-38"))
    }
}
