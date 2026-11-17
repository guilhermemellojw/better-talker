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

    @Test
    fun unescMapValoresLongosEEscapadosNaoEstouram() {
        // Cards de revisão podem chegar com milhares de chars no report.
        // O regex guloso antigo causava backtracking catastrófico e
        // StackOverflow; quantificadores possessivos mantêm o round-trip.
        val long = "A".repeat(20_000) + "\n|\\\t"
        val payload = ChatCodec.escMap(mapOf("text" to long))
        val back = ChatCodec.unescMap(payload)
        assertEquals(long, back["text"])
    }

    @Test
    fun cardsFromJsonCorpoLongoNaoEstoura() {
        val long = "A".repeat(20_000)
        val json = ChatCodec.cardsToJson(listOf(card().copy(body = long)))
        val back = ChatCodec.cardsFromJson(json)
        assertEquals(1, back.size)
        assertEquals(long, back.first().body)
    }
}
