package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (acesso bíblico): versículos por REF EXATA (`findByRef`), sem o LIKE
 * textual antigo — o `normalized` do índice guarda o texto do versículo, não
 * a ref, então `LIKE '%jer 29%'` nunca casava (0 hits na DB real).
 */
class BiblePassagesTest {

    private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
        override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
        override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId }
        override suspend fun deleteForAttachment(attachmentId: String) {
            rows = rows.filter { it.attachmentId != attachmentId }
        }
        override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.normalized.contains(norm) }.take(limit)
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
        override suspend fun indexedAttachmentIds(): List<String> =
            rows.map { it.attachmentId }.distinct()
        override suspend fun findByRef(ref: String): PassageEntity? =
            rows.firstOrNull { it.ref == ref }
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> =
            rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> =
            forAttachments(ids).take(limit)
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
                .sortedBy { it.ord }.take(limit)
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
            rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
                .minByOrNull { it.ord }
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.section.contains(needle, ignoreCase = true) }
                .sortedBy { it.ord }.take(limit)
    }

    private fun passage(id: String, ref: String, text: String, normalized: String = text) =
        PassageEntity(id, "nwt", text, normalized, section = "Jeremias 29", ref = ref)

    @Test
    fun canonicalBibleRefs_expandeRangeListaEPonto() {
        assertEquals(listOf("Je 29:11", "Je 29:12", "Je 29:13"), canonicalBibleRefs("Je 29:11-13"))
        assertEquals(listOf("Gên 1:26", "Gên 1:31"), canonicalBibleRefs("(Gên 1:26, 31)"))
        assertEquals(listOf("Je 29:11"), canonicalBibleRefs("o que diz Jer. 29:11?"))
        assertTrue(canonicalBibleRefs("XYZ 1:1").isEmpty())
    }

    @Test
    fun resolveBiblePassages_achaPorRefExata() = runBlocking {
        val dao = FakePassageDao(listOf(
            passage("p1", "Je 29:11", "“Pois eu sei muito bem o que tenho em mente para vocês”"),
        ))
        val out = resolveBiblePassages(dao, "o que diz Jer. 29:11?", 4)

        assertEquals(1, out.size)
        assertEquals("Je 29:11", out[0].ref)
        assertEquals("“Pois eu sei muito bem o que tenho em mente para vocês”", out[0].text)
    }

    @Test
    fun resolveBiblePassages_range_tresVersiculosNaOrdem() = runBlocking {
        val dao = FakePassageDao(listOf(
            passage("p12", "Je 29:12", "texto 12"),
            passage("p11", "Je 29:11", "texto 11"),
            passage("p13", "Je 29:13", "texto 13"),
        ))
        val out = resolveBiblePassages(dao, "Je 29:11-13", 4)

        assertEquals(listOf("Je 29:11", "Je 29:12", "Je 29:13"), out.map { it.ref })
    }

    @Test
    fun resolveBiblePassages_ignoraTextoQueSoMencionaARef() = runBlocking {
        // O LIKE antigo casaria o texto abaixo ("je 29" no normalized) e
        // devolveria o versículo errado; a busca por ref não.
        val errado = passage(
            "p-x", "Gên 1:1",
            "No princípio Deus criou os céus e a terra.",
            "no principio deus criou os ceus e a terra je 29 nada",
        )
        val dao = FakePassageDao(listOf(errado))
        val out = resolveBiblePassages(dao, "Je 29:11", 4)

        assertTrue(out.isEmpty())
    }

    @Test
    fun resolveBiblePassages_respeitaLimite() = runBlocking {
        val dao = FakePassageDao(listOf(
            passage("p11", "Je 29:11", "texto 11"),
            passage("p12", "Je 29:12", "texto 12"),
            passage("p13", "Je 29:13", "texto 13"),
        ))
        assertEquals(2, resolveBiblePassages(dao, "Je 29:11-13", 2).size)
        assertTrue(resolveBiblePassages(dao, "Je 29:11-13", 0).isEmpty())
    }

    @Test
    fun resolveBiblePassages_refAusente_naoInventa() = runBlocking {
        val dao = FakePassageDao(listOf(passage("p1", "Je 29:11", "texto")))
        assertTrue(resolveBiblePassages(dao, "Jó 33:24", 4).isEmpty())
    }
}
