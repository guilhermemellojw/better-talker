package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultOutlineResponseParserTest {

    private val parser = DefaultOutlineResponseParser()

    private fun request() = OutlineGenerationRequest(
        id = "req-1",
        theme = "A esperança da ressurreição",
        totalMinutes = 15,
        audience = Audience.GENERAL,
        angle = OutlineAngle.DOCTRINAL,
        bibleRefs = listOf("João 5:28,29", "Atos 24:15", "Apo 21:4"),
        publicationRefs = listOf(PublicationRef("w19.03"), PublicationRef("be")),
        methodPrinciples = listOf("Use ilustrações simples"),
    )

    private fun validJson() = """
        {"title":"Ressurreição: uma promessa segura","summary":"Resumo do esboço.",
        "sections":[
        {"title":"INTRODUÇÃO","minutes":3,"mainIdea":"Gancho","bibleRefs":["João 5:28,29"],
         "publicationRefs":[{"symbol":"w19.03","page":8}],"methodPrinciple":"Ilustre"},
        {"title":"CONCLUSÃO","minutes":5,"mainIdea":"Fecho","bibleRefs":["Apo 21:4"]}
        ]}
    """.trimIndent()

    @Test
    fun validJson_returnsFilledProposal() {
        val proposal = parser.parse(validJson(), request())
        assertTrue(proposal != null)
        proposal!!
        assertEquals("Ressurreição: uma promessa segura", proposal.title)
        assertEquals("Resumo do esboço.", proposal.summary)
        assertEquals(2, proposal.sections.size)
        assertEquals("INTRODUÇÃO", proposal.sections[0].title)
        assertEquals(3, proposal.sections[0].minutes)
        assertEquals(listOf("João 5:28,29"), proposal.sections[0].bibleRefs)
        assertEquals("w19.03", proposal.sections[0].publicationRefs.single().symbol)
        assertEquals(8, proposal.sections[0].publicationRefs.single().page)
        assertEquals("Ilustre", proposal.sections[0].methodPrinciple)
    }

    @Test
    fun malformedJson_returnsNull() {
        assertNull(parser.parse("""{"title":"x","summary""", request()))
    }

    @Test
    fun missingTitle_returnsNull() {
        assertNull(parser.parse("""{"summary":"s","sections":[]}""", request()))
    }

    @Test
    fun missingSections_returnsNull() {
        assertNull(parser.parse("""{"title":"t","summary":"s"}""", request()))
    }

    @Test
    fun inventedBibleRef_isFiltered() {
        val json = """{"title":"t","summary":"s","sections":[
            {"title":"A","minutes":3,"mainIdea":"m","bibleRefs":["João 5:28,29","Gên 99:99"]}]}"""
        val proposal = parser.parse(json, request())!!
        assertEquals(listOf("João 5:28,29"), proposal.sections.single().bibleRefs)
    }

    @Test
    fun allInventedBibleRefs_sectionKeepsEmptyList() {
        val json = """{"title":"t","summary":"s","sections":[
            {"title":"A","minutes":3,"mainIdea":"m","bibleRefs":["Gên 99:99"]}]}"""
        val proposal = parser.parse(json, request())!!
        assertTrue(proposal.sections.single().bibleRefs.isEmpty())
    }

    @Test
    fun inventedPublicationRefs_areFiltered() {
        val json = """{"title":"t","summary":"s","sections":[
            {"title":"A","minutes":3,"mainIdea":"m","bibleRefs":["Apo 21:4"],
             "publicationRefs":[{"symbol":"w19.03"},{"symbol":"xx99"}]}]}"""
        val proposal = parser.parse(json, request())!!
        assertEquals(listOf("w19.03"), proposal.sections.single().publicationRefs.map { it.symbol })
    }

    @Test
    fun idsComeFromRequest_notJson() {
        val json = """{"id":"json-id","title":"t","summary":"s","sections":[]}"""
        val proposal = parser.parse(json, request())!!
        assertEquals("req-1", proposal.id)
        assertEquals(OutlineAngle.DOCTRINAL, proposal.angle)
        assertEquals(Audience.GENERAL, proposal.audience)
        assertEquals(15, proposal.totalMinutes)
    }

    @Test
    fun extraFields_areIgnored() {
        val json = """{"title":"t","summary":"s","extra":42,"sections":[
            {"title":"A","minutes":3,"mainIdea":"m","bibleRefs":[],"unknown":"x"}]}"""
        val proposal = parser.parse(json, request())
        assertTrue(proposal != null)
    }

    @Test
    fun emptySections_returnsProposalWithEmptySections() {
        val proposal = parser.parse("""{"title":"t","summary":"s","sections":[]}""", request())!!
        assertTrue(proposal.sections.isEmpty())
    }

    @Test
    fun markdownWrapper_isRejected() {
        val json = "```json\n" + validJson() + "\n```"
        assertNull(parser.parse(json, request()))
    }

    @Test
    fun wrongMinutesType_returnsNull() {
        val json = """{"title":"t","summary":"s","sections":[
            {"title":"A","minutes":"três","mainIdea":"m","bibleRefs":[]}]}"""
        assertNull(parser.parse(json, request()))
    }
}
