package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.toPassage
import com.bettertalker.app.data.domain.toPublication
import com.bettertalker.app.data.domain.toSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainTest {

    @Test
    fun trainingCategorySerialRoundTrip() {
        for (cat in TrainingCategory.values()) {
            assertEquals(cat, TrainingCategory.fromSerial(cat.serial))
        }
        // Serial é minúsculo e estável (não depende de name).
        assertEquals("illustration", TrainingCategory.ILLUSTRATION.serial)
    }

    @Test
    fun trainingCategoryUnknownFallback() {
        assertEquals(TrainingCategory.UNKNOWN, TrainingCategory.fromSerial(null))
        assertEquals(TrainingCategory.UNKNOWN, TrainingCategory.fromSerial(""))
        assertEquals(TrainingCategory.UNKNOWN, TrainingCategory.fromSerial("inexistente"))
        // Case-insensitive de propósito (robusto a valores legados).
        assertEquals(TrainingCategory.ILLUSTRATION, TrainingCategory.fromSerial("ILLUSTRATION"))
    }

    @Test
    fun sourceTypeFromBaseSlot() {
        assertEquals(SourceType.TRAINING, SourceType.fromBaseSlot("be"))
        assertEquals(SourceType.TRAINING, SourceType.fromBaseSlot("th"))
        assertEquals(SourceType.BIBLE, SourceType.fromBaseSlot("nwt"))
        assertEquals(SourceType.CONTENT, SourceType.fromBaseSlot(null))
        assertEquals(SourceType.CONTENT, SourceType.fromBaseSlot("w"))
    }

    @Test
    fun sourceTypeSerialRoundTrip() {
        for (st in SourceType.values()) {
            assertEquals(st, SourceType.fromSerial(st.serial))
        }
        assertEquals(SourceType.CONTENT, SourceType.fromSerial("lixo"))
    }

    @Test
    fun attachmentAdapterPrefersExplicitSourceType() {
        val explicit = AttachmentEntity("a", null, "x.pdf", "pdf", 1, "/x", true, 1L, baseSlot = "be", sourceType = "content")
        assertEquals(SourceType.CONTENT, explicit.toPublication().sourceType)
        val legacy = AttachmentEntity("b", null, "be_T.pdf", "pdf", 1, "/x", true, 1L, baseSlot = "be", sourceType = "")
        assertEquals(SourceType.TRAINING, legacy.toPublication().sourceType)
        val plain = AttachmentEntity("c", null, "w.pdf", "pdf", 1, "/x", true, 1L)
        assertEquals(SourceType.CONTENT, plain.toPublication().sourceType)
        assertNull(plain.toPublication().symbol)
    }

    @Test
    fun passageAdapterPreservesNullProvenance() {
        val e = PassageEntity("p", "a", "texto", "texto")
        val p = e.toPassage()
        assertEquals("", p.ref)
        assertNull(p.page)
        assertNull(p.paragraph)
        assertEquals(0, p.order)
        assertEquals(TrainingCategory.UNKNOWN, p.trainingCategory)
        val full = PassageEntity("q", "a", "t", "t", "Sec", "ref", 3, 7, 2, "illustration")
        val pf = full.toPassage()
        assertEquals("ref", pf.ref)
        assertEquals(3, pf.page)
        assertEquals(7, pf.paragraph)
        assertEquals(2, pf.order)
        assertEquals(TrainingCategory.ILLUSTRATION, pf.trainingCategory)
    }

    @Test
    fun noteWithoutOutlineBecomesEmptySpeech() {
        val n = NoteEntity("n1", "Titulo", "# md", "plain", null, 0L, false, false, 1L, 2L)
        val s = n.toSpeech(null)
        assertEquals("n1", s.id)
        assertEquals("Titulo", s.title)
        assertEquals(2L, s.updatedAt)
        assertEquals(0, s.blocks.size)
    }
}
