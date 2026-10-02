package com.bettertalker.app.data.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5e.1: testes do [DefaultSectionDraftParser] (parser manual de JSON).
 */
class SectionDraftParserTest {

    private val parser = DefaultSectionDraftParser()

    @Test
    fun parse_validJson_returnsDraft() {
        val json = """{"text":"<p>Parágrafo um.</p>","usedSources":["Gên 3:6"]}"""
        val parsed = parser.parse(json)
        assertEquals("<p>Parágrafo um.</p>", parsed?.textHtml)
        assertEquals(listOf("Gên 3:6"), parsed?.usedSources)
    }

    @Test
    fun parse_markdownWrapper_returnsNull() {
        val json = "```json\n{\"text\":\"<p>x</p>\",\"usedSources\":[]}\n```"
        assertNull(parser.parse(json))
    }

    @Test
    fun parse_missingText_returnsNull() {
        val json = """{"usedSources":["Gên 3:6"]}"""
        assertNull(parser.parse(json))
    }

    @Test
    fun parse_missingUsedSources_defaultsToEmpty() {
        val json = """{"text":"<p>x</p>"}"""
        val parsed = parser.parse(json)
        assertEquals("<p>x</p>", parsed?.textHtml)
        assertTrue(parsed?.usedSources?.isEmpty() == true)
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        assertNull(parser.parse("{\"text\": \"<p>sem fim\""))
    }

    @Test
    fun parse_lengthFinishReason_marksPossiblyTruncated() {
        val json = """{"text":"<p>x</p>","usedSources":[]}"""
        assertTrue(parser.parse(json, "length")?.possiblyTruncated == true)
    }

    @Test
    fun parse_stopFinishReason_notTruncated() {
        val json = """{"text":"<p>x</p>","usedSources":[]}"""
        assertTrue(parser.parse(json, "stop")?.possiblyTruncated == false)
    }

    @Test
    fun parse_nullFinishReason_notTruncated() {
        val json = """{"text":"<p>x</p>","usedSources":[]}"""
        val parsed = parser.parse(json)
        assertTrue(parsed?.possiblyTruncated == false)
    }
}
