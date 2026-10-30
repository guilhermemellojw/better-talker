package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 — rótulos/regras puras do DraftSheet (sem Compose instrumentado). */
class DraftSheetTest {

    @Test
    fun progressoDaParte_mostraMetaEEscritas() {
        assertEquals(
            "Parte 1 de 2 · ~2.990 palavras",
            draftPartProgressLabel(1, 2, 2990, 0),
        )
        assertEquals(
            "Parte 2 de 2 · ~2.860 palavras · 3.001 escritas",
            draftPartProgressLabel(2, 2, 2860, 3001),
        )
        assertNull(draftPartProgressLabel(1, 1, 3900, 0))
    }

    @Test
    fun continuacao_mostraRestanteEEscritas() {
        assertEquals(
            "Continuação · ~1.455 palavras · 4.395 escritas",
            draftContinuationLabel(1455, 4395),
        )
        assertEquals("Continuação · ~300 palavras", draftContinuationLabel(300, 0))
    }

    @Test
    fun palavrasDoDraft_mostraTotalEMeta() {
        assertEquals("5.862 de ~5.850 palavras", draftWordsLabel(5862, 5850))
        assertEquals("290 palavras", draftWordsLabel(290, 0))
    }

    @Test
    fun continuarSoQuandoCurtoOuCortado() {
        assertTrue(draftNeedsContinuation(possiblyTruncated = true, words = 5000, targetWords = 5850))
        assertFalse(draftNeedsContinuation(possiblyTruncated = false, words = 5000, targetWords = 5850))
        assertTrue(draftNeedsContinuation(possiblyTruncated = false, words = 4000, targetWords = 5850))
        // 80% exato = completo.
        assertFalse(draftNeedsContinuation(possiblyTruncated = false, words = 4680, targetWords = 5850))
        // Sem meta (demo) só corta por flag.
        assertFalse(draftNeedsContinuation(possiblyTruncated = false, words = 10, targetWords = 0))
        assertTrue(draftNeedsContinuation(possiblyTruncated = true, words = 10, targetWords = 0))
    }

    @Test
    fun aceitarMudaDeRotuloQuandoParcial() {
        assertEquals("Aceitar", draftAcceptLabel(needsContinuation = false))
        assertEquals("Aceitar mesmo assim", draftAcceptLabel(needsContinuation = true))
    }

    @Test
    fun formatacaoPtBr() {
        assertEquals("0", formatDraftWords(0))
        assertEquals("999", formatDraftWords(999))
        assertEquals("1.000", formatDraftWords(1000))
        assertEquals("5.850", formatDraftWords(5850))
        assertEquals("12.345", formatDraftWords(12345))
    }
}
