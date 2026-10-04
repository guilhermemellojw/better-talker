package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.planning.retrieval.MethodIndex
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5b.1: testes do [DefaultDossierBuilder] (caso BODY).
 *
 * Padrão do projeto: fakes parametrizáveis + `runBlocking`.
 */
class DossierBuilderTest {

    private class FakeReferenceResolver(
        private val bibleResults: Map<String, ResolvedReference> = emptyMap(),
        private val pubResults: Map<String, ResolvedReference> = emptyMap(),
    ) : ReferenceResolver {
        override suspend fun resolveBible(ref: String): ResolvedReference =
            bibleResults[ref]
                ?: ResolvedReference(ref, ref, null, null, ReferenceStatus.UNRESOLVED)

        override suspend fun resolvePublication(ref: PublicationRef): ResolvedReference =
            pubResults[ref.symbol]
                ?: ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
    }

    private class FakeMethodIndex(
        private val principles: List<String> = emptyList(),
    ) : MethodIndex {
        var lastContext: String? = null
            private set
        var lastCategory: String? = null
            private set

        override suspend fun findPrinciples(
            context: String,
            limit: Int,
            category: String?,
        ): List<String> {
            lastContext = context
            lastCategory = category
            return principles.take(limit)
        }
    }

    private fun section(
        id: String,
        order: Int,
        role: SectionRole = SectionRole.BODY,
        title: String = "T$id",
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role, title = title,
        minutes = 5, contentHtml = "", bibleRefs = bibleRefs,
        publicationRefs = pubRefs, methodPrinciple = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun subPoint(
        id: String,
        sectionId: String,
        order: Int,
        outlineText: String = "ponto $id",
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
        developedHtml: String = "",
    ) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = outlineText,
        bibleRefs = bibleRefs, publicationRefs = pubRefs, instruction = null,
        developedHtml = developedHtml, createdAt = 1L, updatedAt = 1L,
    )

    private fun builder(
        bibleResults: Map<String, ResolvedReference> = emptyMap(),
        pubResults: Map<String, ResolvedReference> = emptyMap(),
        principles: List<String> = emptyList(),
    ) = DefaultDossierBuilder(
        FakeReferenceResolver(bibleResults, pubResults),
        FakeMethodIndex(principles),
    )

    private fun context(sectionId: String, subPointId: String? = null) =
        SelectionContextContract(
            sectionId = sectionId,
            subPointId = subPointId,
            selectedText = "",
            fullContentHtml = "",
        )

    @Test
    fun build_bodyWithSingleSubPoint_resolvesRefsAndPrinciples() = runBlocking {
        val sp = subPoint("sp1", "s1", 0, bibleRefs = listOf("Gên 3:6"))
        val doc = listOf(SectionWithSubPoints(section("s1", 0), listOf(sp)))
        val b = builder(
            bibleResults = mapOf(
                "Gên 3:6" to ResolvedReference("Gên 3:6", "Gên 3:6", "texto literal", "p1", ReferenceStatus.RESOLVED)
            ),
            principles = listOf("Seja específico"),
        )
        val d = b.build(context("s1", "sp1"), doc)

        assertEquals(1, d.bibleTexts.size)
        assertEquals(ReferenceStatus.RESOLVED, d.bibleTexts[0].status)
        assertEquals("texto literal", d.bibleTexts[0].text)
        assertEquals("sp1", d.currentSubPoint?.id)
        assertEquals(listOf("Seja específico"), d.methodPrinciples)
        assertTrue(d.unresolvedRefs.isEmpty())
    }

    @Test
    fun build_bodyWithoutSubPoint_usesSectionRefs() = runBlocking {
        val sec = section("s1", 0, bibleRefs = listOf("Jo 3:16"))
        val doc = listOf(SectionWithSubPoints(sec, emptyList()))
        val b = builder(
            bibleResults = mapOf(
                "Jo 3:16" to ResolvedReference("Jo 3:16", "Jo 3:16", "porque Deus amou", "p2", ReferenceStatus.RESOLVED)
            ),
        )
        val d = b.build(context("s1", null), doc)

        assertNull(d.currentSubPoint)
        assertEquals(1, d.bibleTexts.size)
        assertEquals("porque Deus amou", d.bibleTexts[0].text)
    }

    @Test
    fun build_unresolvedRef_appearsInUnresolvedRefs() = runBlocking {
        val sp = subPoint("sp1", "s1", 0, bibleRefs = listOf("Gên 3:6"))
        val doc = listOf(SectionWithSubPoints(section("s1", 0), listOf(sp)))
        val d = builder().build(context("s1", "sp1"), doc)

        assertEquals(1, d.bibleTexts.size)
        assertEquals(null, d.bibleTexts[0].text)
        assertEquals(listOf("Gên 3:6"), d.unresolvedRefs)
    }

    @Test
    fun build_partialRef_notInUnresolvedRefs() = runBlocking {
        val sp = subPoint(
            "sp1", "s1", 0,
            pubRefs = listOf(PublicationRef("be", 52, 3)),
        )
        val doc = listOf(SectionWithSubPoints(section("s1", 0), listOf(sp)))
        val b = builder(
            pubResults = mapOf(
                "be" to ResolvedReference("be", "be §3", "texto aproximado", "p3", ReferenceStatus.PARTIAL)
            ),
        )
        val d = b.build(context("s1", "sp1"), doc)

        assertTrue(d.unresolvedRefs.isEmpty())
        assertEquals(1, d.publicationTexts.size)
        assertEquals(ReferenceStatus.PARTIAL, d.publicationTexts[0].status)
        assertEquals("texto aproximado", d.publicationTexts[0].text)
    }

    @Test
    fun build_methodPrinciples_receivesOutlineText() = runBlocking {
        val methods = FakeMethodIndex(listOf("p1"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val sp = subPoint("sp1", "s1", 0, outlineText = "Texto âncora do esboço")
        val doc = listOf(SectionWithSubPoints(section("s1", 0), listOf(sp)))

        b.build(context("s1", "sp1"), doc)

        assertTrue(
            "context deve conter o outlineText, era: ${methods.lastContext}",
            methods.lastContext?.contains("Texto âncora do esboço") == true,
        )
    }

    @Test
    fun build_overview_containsAllSectionsMetadata() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(section("b", 1, SectionRole.BODY, "Corpo"), emptyList()),
            SectionWithSubPoints(section("c", 2, SectionRole.CONCLUSION, "Conclusão"), emptyList()),
        )
        val d = builder().build(context("b", null), doc)

        assertEquals(3, d.overview.size)
        assertEquals(listOf("i", "b", "c"), d.overview.map { it.id })
        assertEquals(
            listOf(SectionRole.INTRO, SectionRole.BODY, SectionRole.CONCLUSION),
            d.overview.map { it.role },
        )
        assertEquals(listOf(0, 1, 2), d.overview.map { it.order })
    }

    @Test
    fun build_emptyAcervo_unresolvedRefsNotEmpty_noCrash() = runBlocking {
        val sp = subPoint(
            "sp1", "s1", 0,
            bibleRefs = listOf("Gên 3:6", "Ro 5:12"),
            pubRefs = listOf(PublicationRef("be")),
        )
        val doc = listOf(SectionWithSubPoints(section("s1", 0), listOf(sp)))
        val d = builder().build(context("s1", "sp1"), doc)

        assertEquals(2, d.bibleTexts.size)
        assertTrue(d.bibleTexts.all { it.text == null })
        assertEquals(1, d.publicationTexts.size)
        assertEquals(3, d.unresolvedRefs.size)
        assertTrue(d.methodPrinciples.isEmpty())
    }

    @Test
    fun build_sectionNotFound_throws() = runBlocking {
        val doc = listOf(SectionWithSubPoints(section("s1", 0), emptyList()))
        val b = builder()
        var thrown: Throwable? = null
        try {
            b.build(context("nope", null), doc)
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue("esperava IllegalStateException, era: $thrown", thrown != null)
    }

    // ---------- 3.5b.2: INTRO/CONCLUSION (transição + hints) ----------

    private fun developedSubPoint(
        id: String,
        sectionId: String,
        order: Int,
        outlineText: String = "ponto $id",
        developedHtml: String = "<p>desenvolvido $id</p>",
    ) = subPoint(id, sectionId, order, outlineText, developedHtml = developedHtml)

    @Test
    fun build_intro_transitionIncludesFirstBodySubPoints() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(
                section("b1", 1, SectionRole.BODY, "Corpo 1"),
                listOf(
                    developedSubPoint("sp1", "b1", 0, "Âncora um"),
                    developedSubPoint("sp2", "b1", 1, "Âncora dois"),
                ),
            ),
            SectionWithSubPoints(section("b2", 2, SectionRole.BODY, "Corpo 2"), emptyList()),
        )
        val d = builder().build(context("i", null), doc)

        assertTrue("transition era null", d.transitionContext != null)
        assertTrue(d.transitionContext!!.contains("Âncora um"))
        assertTrue(d.transitionContext!!.contains("Âncora dois"))
    }

    @Test
    fun build_intro_noDevelopedBody_transitionIsNull() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(
                section("b1", 1, SectionRole.BODY, "Corpo 1"),
                listOf(subPoint("sp1", "b1", 0)),
            ),
        )
        val d = builder().build(context("i", null), doc)

        assertNull(d.transitionContext)
    }

    @Test
    fun build_intro_multipleBodies_usesFirstByOrder() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            // fora de ordem no input: o builder ordena por order
            SectionWithSubPoints(
                section("b2", 2, SectionRole.BODY, "Corpo 2"),
                listOf(developedSubPoint("spB", "b2", 0, "Âncora B")),
            ),
            SectionWithSubPoints(
                section("b1", 1, SectionRole.BODY, "Corpo 1"),
                listOf(developedSubPoint("spA", "b1", 0, "Âncora A")),
            ),
        )
        val d = builder().build(context("i", null), doc)

        assertTrue("transition era null", d.transitionContext != null)
        assertTrue(d.transitionContext!!.contains("Âncora A"))
        assertTrue(!d.transitionContext!!.contains("Âncora B"))
    }

    @Test
    fun build_conclusion_recapIncludesLastBodySubPoints() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(
                section("b1", 0, SectionRole.BODY, "Corpo 1"),
                listOf(developedSubPoint("sp1", "b1", 0, "Âncora um")),
            ),
            SectionWithSubPoints(
                section("b2", 1, SectionRole.BODY, "Corpo 2"),
                listOf(developedSubPoint("sp2", "b2", 0, "Âncora dois")),
            ),
            SectionWithSubPoints(section("c", 2, SectionRole.CONCLUSION, "Conclusão"), emptyList()),
        )
        val d = builder().build(context("c", null), doc)

        assertTrue("transition era null", d.transitionContext != null)
        assertTrue(d.transitionContext!!.contains("Âncora dois"))
        assertTrue(!d.transitionContext!!.contains("Âncora um"))
    }

    @Test
    fun build_conclusion_multipleBodies_usesLastByOrder() = runBlocking {
        val doc = listOf(
            // fora de ordem no input
            SectionWithSubPoints(
                section("b2", 5, SectionRole.BODY, "Corpo 2"),
                listOf(developedSubPoint("spB", "b2", 0, "Âncora B")),
            ),
            SectionWithSubPoints(
                section("b1", 1, SectionRole.BODY, "Corpo 1"),
                listOf(developedSubPoint("spA", "b1", 0, "Âncora A")),
            ),
            SectionWithSubPoints(section("c", 9, SectionRole.CONCLUSION, "Conclusão"), emptyList()),
        )
        val d = builder().build(context("c", null), doc)

        assertTrue("transition era null", d.transitionContext != null)
        assertTrue(d.transitionContext!!.contains("Âncora B"))
        assertTrue(!d.transitionContext!!.contains("Âncora A"))
    }

    @Test
    fun build_intro_methodContextIncludesIntroHint() = runBlocking {
        val methods = FakeMethodIndex(listOf("p1"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(section("b1", 1, SectionRole.BODY, "Corpo 1"), emptyList()),
        )
        b.build(context("i", null), doc)

        assertTrue(
            "context devia começar com 'introdução', era: ${methods.lastContext}",
            methods.lastContext?.startsWith("introdução") == true,
        )
    }

    @Test
    fun build_conclusion_methodContextIncludesConclusionHint() = runBlocking {
        val methods = FakeMethodIndex(listOf("p1"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val doc = listOf(
            SectionWithSubPoints(section("b1", 0, SectionRole.BODY, "Corpo 1"), emptyList()),
            SectionWithSubPoints(section("c", 1, SectionRole.CONCLUSION, "Conclusão"), emptyList()),
        )
        b.build(context("c", null), doc)

        assertTrue(
            "context devia começar com 'conclusão', era: ${methods.lastContext}",
            methods.lastContext?.startsWith("conclusão") == true,
        )
    }

    @Test
    fun build_body_transitionContextIsNull() = runBlocking {
        val sp = subPoint("sp1", "b1", 0)
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(section("b1", 1, SectionRole.BODY, "Corpo 1"), listOf(sp)),
        )
        val d = builder().build(context("b1", "sp1"), doc)

        assertNull(d.transitionContext)
    }

    // ---------- 3.5c: categoria do MethodIndex ----------

    @Test
    fun build_intro_passesIntroductionCategory() = runBlocking {
        val methods = FakeMethodIndex(listOf("p"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(section("b1", 1, SectionRole.BODY, "Corpo 1"), emptyList()),
        )
        b.build(context("i", null), doc)

        assertEquals("introduction", methods.lastCategory)
    }

    @Test
    fun build_body_passesDevelopmentCategory() = runBlocking {
        val methods = FakeMethodIndex(listOf("p"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val sp = subPoint("sp1", "b1", 0)
        val doc = listOf(
            SectionWithSubPoints(section("b1", 0, SectionRole.BODY, "Corpo 1"), listOf(sp)),
        )
        b.build(context("b1", "sp1"), doc)

        assertEquals("development", methods.lastCategory)
    }

    @Test
    fun build_conclusion_passesConclusionCategory() = runBlocking {
        val methods = FakeMethodIndex(listOf("p"))
        val b = DefaultDossierBuilder(FakeReferenceResolver(), methods)
        val doc = listOf(
            SectionWithSubPoints(section("b1", 0, SectionRole.BODY, "Corpo 1"), emptyList()),
            SectionWithSubPoints(section("c", 1, SectionRole.CONCLUSION, "Conclusão"), emptyList()),
        )
        b.build(context("c", null), doc)

        assertEquals("conclusion", methods.lastCategory)
    }

    // ---------- F2.3: linha de raciocínio + visão global ----------

    @Test
    fun build_sectionSubPoints_carriesWholeLine() = runBlocking {
        val doc = listOf(
            SectionWithSubPoints(
                section("b", 0),
                listOf(subPoint("sp1", "b", 0, "primeiro"), subPoint("sp2", "b", 1, "segundo")),
            )
        )
        val d = builder().build(context("b", "sp1"), doc)

        assertEquals(listOf("sp1", "sp2"), d.sectionSubPoints.map { it.id })
    }

    @Test
    fun build_overview_carriesObjectiveAndSnippet() = runBlocking {
        val body = section("b", 0).copy(
            objective = "Explicar a esperança",
            contentHtml = "<p>texto já escrito do tópico</p>",
        )
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(body, emptyList()),
        )
        val d = builder().build(context("i", null), doc)

        val meta = d.overview.first { it.id == "b" }
        assertEquals("Explicar a esperança", meta.objective)
        assertTrue(meta.snippet!!.contains("texto já escrito"))
    }

    @Test
    fun build_overview_snippetFallsBackToDevelopedSubPoints() = runBlocking {
        val body = section("b", 0)
        val doc = listOf(
            SectionWithSubPoints(section("i", 0, SectionRole.INTRO, "Introdução"), emptyList()),
            SectionWithSubPoints(
                body,
                listOf(developedSubPoint("sp1", "b", 0, "Âncora", "<p>legado desenvolvido</p>")),
            ),
        )
        val d = builder().build(context("i", null), doc)

        val meta = d.overview.first { it.id == "b" }
        assertTrue(meta.snippet!!.contains("legado desenvolvido"))
    }
}
