package com.bettertalker.app.data.db

import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteEntityDiscourseTypeTest {

    private fun note(discourseType: String = DiscourseType.S34_DISCOURSE.name) = NoteEntity(
        id = "n1", title = "T", mdText = "", plainText = "",
        folderId = null, colorArgb = 0L, pinned = false, trashed = false,
        createdAt = 1L, updatedAt = 2L, richHtml = "",
        discourseType = discourseType,
    )

    @Test
    fun noteEntity_defaultsToS34() {
        val entity = NoteEntity(
            id = "n1", title = "T", mdText = "", plainText = "",
            folderId = null, colorArgb = 0L, pinned = false, trashed = false,
            createdAt = 1L, updatedAt = 2L,
        )
        assertEquals(DiscourseType.S34_DISCOURSE.name, entity.discourseType)
        assertEquals(DiscourseType.S34_DISCOURSE, DiscourseType.fromNameOrDefault(entity.discourseType))
    }

    @Test
    fun noteEntity_roundTripPreservesDiscourseType() {
        val entity = note(DiscourseType.AVULSO.name)
        assertEquals(DiscourseType.AVULSO, DiscourseType.fromNameOrDefault(entity.copy().discourseType))
    }
}
