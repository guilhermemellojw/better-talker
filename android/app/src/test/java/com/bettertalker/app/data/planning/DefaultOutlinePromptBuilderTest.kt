package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultOutlinePromptBuilderTest {

    private fun request() = OutlineGenerationRequest(
        id = "req-1",
        theme = "A esperança da ressurreição",
        totalMinutes = 15,
        audience = Audience.GENERAL,
        angle = OutlineAngle.DOCTRINAL,
        bibleRefs = listOf("João 5:28,29", "Atos 24:15"),
        publicationRefs = listOf(PublicationRef("w19.03", page = 8)),
        methodPrinciples = listOf("Use ilustrações simples"),
    )

    private val prompt: String by lazy { DefaultOutlinePromptBuilder().build(request()) }

    @Test
    fun containsTheme() {
        assertTrue(prompt.contains("A esperança da ressurreição"))
    }

    @Test
    fun containsDuration() {
        assertTrue(prompt.contains("15 minutos"))
    }

    @Test
    fun listsProvidedBibleRefs() {
        assertTrue(prompt.contains("João 5:28,29"))
        assertTrue(prompt.contains("Atos 24:15"))
    }

    @Test
    fun listsProvidedPublicationRefs() {
        assertTrue(prompt.contains("w19.03"))
    }

    @Test
    fun containsAntiHallucinationInstruction() {
        assertTrue(prompt.contains("Não invente"))
    }

    @Test
    fun documentsExpectedJsonFormat() {
        assertTrue(prompt.contains("\"sections\""))
        assertTrue(prompt.contains("\"mainIdea\""))
    }
}
