package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C2.6 — o matcher não pode escolher uma edição só porque mês/dia aparecem
 * no meio do ano de outra edição. Casos reais da matriz de 2026-10-30.
 */
class RefDetectorEditionMatchTest {

    private fun att(name: String) = AttachmentEntity(
        id = "a-$name", noteId = null, fileName = name, kind = name.substringAfterLast('.').lowercase(),
        sizeBytes = 1, appPath = "/x", indexed = true, addedAt = 0,
    )

    private fun magazine(editionKey: String, raw: String = editionKey) = RefDetector.DetectedRef(
        raw = raw,
        kind = RefDetector.Kind.MAGAZINE,
        pubKey = editionKey.substringBefore('|'),
        editionKey = editionKey,
        label = raw,
    )

    private fun book(pub: String) = RefDetector.DetectedRef(
        raw = pub,
        kind = RefDetector.Kind.BOOK,
        pubKey = pub,
        editionKey = "book|$pub",
        label = pub,
    )

    @Test
    fun dated_w_naoCasaMesmoMesDiaDeOutroAno() {
        val ref = magazine("w|2011|12|15", "w11 15/12 16-17 §§ 18-19")
        assertNull(RefDetector.matchEdition(ref, listOf(att("w_T_20121115.jwpub"))))
        assertEquals(
            "a-w_T_20111215.jwpub",
            RefDetector.matchEdition(ref, listOf(att("w_T_20111215.jwpub")))?.id,
        )
    }

    @Test
    fun dated_w_mesmoAno_mesErradoNaoCasa() {
        val ref = magazine("w|2015|1|15", "Sentinela 15/01/15")
        assertNull(RefDetector.matchEdition(ref, listOf(att("w_T_20151015.jwpub"))))
        assertEquals(
            "a-w_T_20150115.jwpub",
            RefDetector.matchEdition(ref, listOf(att("w_T_20150115.jwpub")))?.id,
        )
    }

    @Test
    fun monthly_w_naoCasaMesmoMesDeOutroAno() {
        val ref = magazine("w|2020|3", "Sentinela 03/20")
        assertNull(RefDetector.matchEdition(ref, listOf(att("w_T_20030715.jwpub"))))
        assertEquals(
            "a-w_T_202003.jwpub",
            RefDetector.matchEdition(ref, listOf(att("w_T_202003.jwpub")))?.id,
        )
    }

    @Test
    fun monthly_w_naoCasaDiaDeOutroAno() {
        val ref = magazine("w|2019|9", "w19.09 10 §§ 10-11")
        assertNull(RefDetector.matchEdition(ref, listOf(att("w_T_19910801.jwpub"))))
        assertEquals(
            "a-w_T_201909.jwpub",
            RefDetector.matchEdition(ref, listOf(att("w_T_201909.jwpub")))?.id,
        )
    }

    @Test
    fun livroLv_naoCasaLvs() {
        val ref = book("lv")
        assertNull(RefDetector.matchEdition(ref, listOf(att("lvs_T.jwpub"))))
        assertEquals(
            "a-lv_T.jwpub",
            RefDetector.matchEdition(ref, listOf(att("lv_T.jwpub")))?.id,
        )
    }

    @Test
    fun livroLvs_casaLvs() {
        val ref = book("lvs")
        assertEquals(
            "a-lvs_T.jwpub",
            RefDetector.matchEdition(ref, listOf(att("lvs_T.jwpub")))?.id,
        )
    }

    @Test
    fun formatoLegadoDeDuasLetrasContinuaCasando() {
        val ref = magazine("wp|2019|3", "Sentinela número 3 de 2019")
        for (name in listOf("wp19.3.pdf", "wp19_3_T.pdf", "wp2019_3.pdf", "wp201903.pdf", "wp_T_201909.pdf")) {
            assertEquals(name, "a-$name", RefDetector.matchEdition(ref, listOf(att(name)))?.id)
        }
    }
}
