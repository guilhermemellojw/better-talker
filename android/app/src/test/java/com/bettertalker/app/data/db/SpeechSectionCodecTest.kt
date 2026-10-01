package com.bettertalker.app.data.db

import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSectionCodecTest {

    @Test
    fun encodePublicationRefList_roundTrip() {
        val items = listOf(
            PublicationRef("w21.08", page = 18, paragraph = 13),
            PublicationRef("g", page = 8, paragraph = 2),
        )
        assertEquals(items, decodePublicationRefList(encodePublicationRefList(items)))
    }

    @Test
    fun encodePublicationRefList_omitsNullFields() {
        val json = encodePublicationRefList(listOf(PublicationRef("g")))
        assertEquals("[{\"symbol\":\"g\"}]", json)
        assertEquals(listOf(PublicationRef("g")), decodePublicationRefList(json))
    }

    @Test
    fun decodePublicationRefList_handlesEscapes() {
        val items = listOf(PublicationRef("a\"b\\c"))
        assertEquals(items, decodePublicationRefList(encodePublicationRefList(items)))
    }

    @Test
    fun decodePublicationRefList_emptyArray_returnsEmpty() {
        assertTrue(decodePublicationRefList("[]").isEmpty())
        assertTrue(decodePublicationRefList("").isEmpty())
    }
}
