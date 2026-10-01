package com.bettertalker.app.data.db

import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubPointMapperTest {

    private fun domain() = SubPointBuilder.build(
        id = "sp1", sectionId = "sec1", order = 2,
        outlineText = "Por que a fé é essencial",
        bibleRefs = listOf("Heb 11:1", "Rm 10:17"),
        publicationRefs = listOf(
            PublicationRef("w21.08", page = 18, paragraph = 13),
            PublicationRef("be"),
        ),
        instruction = "Leia Heb 11:1.",
        developedHtml = "<p>Desenvolvido</p>",
        createdAt = 10L, updatedAt = 20L,
    )

    @Test
    fun toEntity_toDomain_roundTrip_preservesAllFields() {
        assertEquals(domain(), domain().toEntity().toDomain())
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
    fun nullInstruction_roundTripsAsNull() {
        val back = domain().copy(instruction = null).toEntity().toDomain()
        assertNull(back.instruction)
    }
}

/** Helper local para manter a fixture legível. */
private object SubPointBuilder {
    fun build(
        id: String,
        sectionId: String,
        order: Int,
        outlineText: String,
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
        instruction: String? = null,
        developedHtml: String = "",
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
    ) = com.bettertalker.app.domain.speech.SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = outlineText,
        bibleRefs = bibleRefs, publicationRefs = publicationRefs,
        instruction = instruction, developedHtml = developedHtml,
        createdAt = createdAt, updatedAt = updatedAt,
    )
}
