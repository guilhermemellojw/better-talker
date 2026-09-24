package com.bettertalker.app

import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.ChatCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCodecTcTest {

    private fun card() = IdeaCard(
        "Orientação — be", "Siga a orientação.", "", "", "Beneficie-se",
        "Introdução", "Instrução.", false, "introduction"
    )

    @Test
    fun roundTripPreservesTrainingCategory() {
        val json = ChatCodec.cardsToJson(listOf(card()))
        val back = ChatCodec.cardsFromJson(json)
        assertEquals(1, back.size)
        assertEquals("introduction", back.first().trainingCategory)
        assertEquals(card().title, back.first().title)
    }

    @Test
    fun legacyPayloadWithoutTcParsesAsNull() {
        val legacy = """[{"t":"T","b":"B","s":"","u":"","src":"S","sec":"","pr":"","f":0}]"""
        val back = ChatCodec.cardsFromJson(legacy)
        assertEquals(1, back.size)
        assertNull(back.first().trainingCategory)
    }

    @Test
    fun legacyPayloadWithoutFlagDefaultsInsertable() {
        val legacy = """[{"t":"T","b":"B","s":"","u":"","src":"S","sec":"","pr":""}]"""
        val back = ChatCodec.cardsFromJson(legacy)
        assertEquals(1, back.size)
        assertEquals(true, back.first().insertable)
    }
}
