package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.util.BibleSourceStatus
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.NoteSourcesResolver
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ResolvedPublicationText
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2.1 — fachada: dossiê (subponto/seção) → pack → pergunta, com dedup.
 */
class FocusSourcesAssemblerTest {

    private val w2507 = "w25.07 22 §§ 8-9"
    private val jer = "Jer. 45:3"
    private val jer5 = "Jer. 45:5"

    private fun section(
        title: String = "Tópico",
        content: String = "",
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
    ) = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = title, minutes = 5, contentHtml = content,
        bibleRefs = bibleRefs, publicationRefs = publicationRefs,
        methodPrinciple = null, createdAt = 0, updatedAt = 0,
    )

    private fun subPoint(text: String, bibleRefs: List<String> = emptyList()) = SubPoint(
        id = "sp1", sectionId = "s1", order = 0,
        outlineText = text, bibleRefs = bibleRefs,
        publicationRefs = emptyList(), instruction = null,
        developedHtml = "", createdAt = 0, updatedAt = 0,
    )

    private fun dossier(
        currentSection: SpeechSection,
        currentSubPoint: SubPoint? = null,
        bibleTexts: List<ResolvedBibleText> = emptyList(),
        publicationTexts: List<ResolvedPublicationText> = emptyList(),
    ) = Dossier(
        currentSection = currentSection,
        currentSubPoint = currentSubPoint,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = bibleTexts,
        publicationTexts = publicationTexts,
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
        sectionSubPoints = listOfNotNull(currentSubPoint),
        noteTitle = "Nota",
        discourseType = DiscourseType.S34_DISCOURSE.name,
    )

    private fun noteSourcesOf(refs: NoteRefs): NoteSources = NoteSourcesResolver.toNoteSources(
        candidates = NoteSourcesResolver.candidatesOf(refs),
        publicationStatuses = refs.publications.associate { ref ->
            NoteRefsCollector.sourceKey(ref) to RefDetector.RefStatus(
                ref = ref, resolved = true, contentResolved = true,
            )
        },
        bibleStatuses = refs.bibles.associate { bible ->
            FocusSourceResolver.bibleKey(bible) to BibleSourceStatus(true, true)
        },
    )

    private fun evidence(reference: String, text: String): EvidenceSource = EvidenceSource(
        id = "e-$reference",
        reference = reference,
        text = text,
        sourceType = SourceType.CONTENT,
        publication = null,
        section = null,
        paragraph = null,
        page = null,
        score = 1.0,
    )

    private fun pack(vararg sources: EvidenceSource): ContextPack = ContextPack(
        contentSources = sources.toList(),
        trainingSources = emptyList(),
    )

    @Test
    fun subpontoVemAntesDaSecaoEPackNaoDuplica() {
        val noteText = "$w2507; $jer; w25.07 20 § 2"
        val refs = NoteRefsCollector.collect(plainText = noteText)
        val keyW = NoteRefsCollector.sourceKey(RefDetector.detect(w2507).first())
        val keyJer = FocusSourceResolver.bibleKey(RefDetector.detectBible(jer).first())
        val keyPack = NoteRefsCollector.sourceKey(RefDetector.detect("w25.07 20 § 2").first())
        val sources = noteSourcesOf(refs)
        val packRef = evidence("w25.07 20 § 2", "texto do pack")
        val dossie = dossier(
            currentSection = section(content = "$w2507; $jer; w25.07 20 § 2"),
            currentSubPoint = subPoint(w2507),
        )
        val cited = listOf(
            evidence("w25.07 pág. 22 §8-9", "texto de w25.07 22 §§8-9"),
            evidence("Jer. 45:3", "texto de Jeremias"),
            evidence("w25.07 pág. 20 §2", "texto do pack"),
        )

        val out = FocusSourcesAssembler.assemble(
            refs = refs,
            noteSources = sources,
            citedSources = cited,
            pack = pack(packRef),
            dossier = dossie,
            question = "o que diz $w2507?",
            structural = null,
        )

        assertEquals(FocusOrigin.SUBPOINT, out.entries.first { it.key == keyW }.origin)
        assertEquals(FocusOrigin.SECTION, out.entries.first { it.key == keyJer }.origin)
        assertEquals(FocusDestination.PACK, out.destinationOf(keyPack))
        assertTrue(out.block.startsWith(FOCUS_BLOCK_HEADER))
        assertTrue(out.block.contains("texto de w25.07 22 §§8-9"))
        assertTrue(out.block.contains("texto de Jeremias"))
        assertFalse(out.block.contains("texto do pack"))
    }

    @Test
    fun dossieJaTemTextoNaoRepeteNoBloco() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val key = NoteRefsCollector.sourceKey(RefDetector.detect(w2507).first())
        val dossie = dossier(
            currentSection = section(content = w2507),
            publicationTexts = listOf(
                ResolvedPublicationText(
                    PublicationRef("w25.07", page = 22, paragraph = 8, paragraphEnd = 9),
                    "texto já no dossiê",
                    ReferenceStatus.RESOLVED,
                ),
            ),
        )

        val out = FocusSourcesAssembler.assemble(
            refs = refs,
            noteSources = noteSourcesOf(refs),
            citedSources = listOf(evidence("w25.07 pág. 22 §8-9", "texto já no dossiê")),
            pack = pack(),
            dossier = dossie,
            question = "",
            structural = null,
        )

        assertEquals(FocusDestination.DOSSIER, out.destinationOf(key))
        assertFalse(out.block.contains("texto já no dossiê"))
    }

    @Test
    fun refDaPerguntaViraQuestionEODaNotaForaDoPontoVaiAoInventario() {
        val refs = NoteRefsCollector.collect(plainText = "$w2507; $jer5")
        val keyQuestion = FocusSourceResolver.bibleKey(RefDetector.detectBible(jer5).first())
        val out = FocusSourcesAssembler.assemble(
            refs = refs,
            noteSources = noteSourcesOf(refs),
            citedSources = listOf(
                evidence("w25.07 pág. 22 §8-9", "texto de w25.07"),
                evidence("Jer. 45:5", "texto de Jer. 45:5"),
            ),
            pack = pack(),
            dossier = null,
            question = "e o que diz $jer5?",
            structural = null,
        )

        assertEquals(FocusDestination.QUESTION, out.destinationOf(keyQuestion))
        assertEquals(
            FocusDestination.INVENTORY,
            out.entries.first { it.reference.contains("w25.07") }.destination,
        )
    }

    @Test
    fun packSemRefDaNotaEntraComoPack() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val out = FocusSourcesAssembler.assemble(
            refs = refs,
            noteSources = noteSourcesOf(refs),
            citedSources = emptyList(),
            pack = pack(evidence("w25.07 pág. 1 §2", "texto do pack externo")),
            dossier = null,
            question = "",
            structural = null,
        )

        val packEntry = out.entries.first { it.origin == FocusOrigin.PACK }
        assertEquals(FocusDestination.PACK, packEntry.destination)
        assertFalse(out.block.contains("texto do pack externo"))
    }
}
