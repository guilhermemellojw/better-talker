package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** #8c (GAP-3): nenhuma tag interna vaza para o card. */
class CleanForInsertTest {

    @Test
    fun decisao_parRemovidoConteudoPreservado() {
        assertEquals(
            "Texto final confirmado.",
            cleanForInsert("〈decisão〉Texto final confirmado.〈/decisão〉"),
        )
    }

    @Test
    fun sugestao_parRemovidoConteudoPreservado() {
        assertEquals(
            "Uma metáfora do rio.",
            cleanForInsert("〈sugestão〉Uma metáfora do rio.〈/sugestão〉"),
        )
    }

    @Test
    fun multiplasTags_removidas() {
        assertEquals(
            "um dois",
            cleanForInsert("〈decisão〉um〈/decisão〉 〈sugestão〉dois〈/sugestão〉"),
        )
    }

    @Test
    fun tagOrfa_removida() {
        assertEquals("texto limpo", cleanForInsert("texto limpo 〈/decisão〉"))
    }
}
