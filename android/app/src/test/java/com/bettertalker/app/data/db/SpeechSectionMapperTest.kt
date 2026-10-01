package com.bettertalker.app.data.db

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSectionMapperTest {

    private fun domain() = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = "Desenvolvimento", minutes = 7, contentHtml = "<p>Corpo</p>",
        bibleRefs = listOf("Jo 3:16", "Sal 23:1"),
        publicationRefs = listOf(
            PublicationRef("w21.08", page = 18, paragraph = 13),
            PublicationRef("g"),
        ),
        methodPrinciple = "Use ilustrações",
        createdAt = 10L, updatedAt = 20L,
    )

    @Test
    fun toEntity_toDomain_roundTrip_preservesAllFields() {
        val back = domain().toEntity().toDomain()
        assertEquals(domain(), back)
    }

    @Test
    fun bibleRefsSerialization_roundTrip() {
        val entity = domain().copy(bibleRefs = listOf("a", "b\"c", "d\\e")).toEntity()
        assertEquals(listOf("a", "b\"c", "d\\e"), entity.toDomain().bibleRefs)
    }

    @Test
    fun publicationRefsSerialization_roundTrip() {
        val entity = domain().toEntity()
        assertEquals(domain().publicationRefs, entity.toDomain().publicationRefs)
    }

    @Test
    fun emptyRefs_roundTripProducesEmptyLists() {
        val back = domain().copy(bibleRefs = emptyList(), publicationRefs = emptyList())
            .toEntity().toDomain()
        assertTrue(back.bibleRefs.isEmpty())
        assertTrue(back.publicationRefs.isEmpty())
    }

    @Test
    fun unknownRole_fallsBackToBody() {
        val entity = domain().toEntity().copy(role = "LEGADO")
        assertEquals(SectionRole.BODY, entity.toDomain().role)
    }
}
