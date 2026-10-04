package com.bettertalker.app.data.llm

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2.1 — RAG enxuto. Puro, sem Android. */
class LeanRagTest {

    private fun src(
        id: String,
        text: String,
        reference: String = "Ref $id",
        training: Boolean = false,
    ) = EvidenceSource(
        id = id,
        reference = reference,
        text = text,
        sourceType = if (training) SourceType.TRAINING else SourceType.CONTENT,
        publication = "Pub $id",
        section = "Seção $id",
        paragraph = 3,
        page = 12,
    )

    @Test
    fun null_pack_returns_null() {
        assertEquals(null, LeanRag.compact(null))
    }

    @Test
    fun empty_pack_returns_unchanged() {
        val pack = ContextPack(emptyList(), emptyList())
        assertSame(pack, LeanRag.compact(pack))
    }

    @Test
    fun under_budget_returns_same_instance() {
        val pack = ContextPack(
            listOf(src("a", "Texto curto de evidência.")),
            listOf(src("t", "Orientação curta.", training = true)),
        )
        assertSame(pack, LeanRag.compact(pack))
    }

    @Test
    fun over_budget_keeps_ranked_order_within_cap() {
        val big = "x".repeat(3000)
        val pack = ContextPack(
            listOf(
                src("s1", "Primeira evidência relevante. " + "y".repeat(2000)),
                src("s2", "Segunda evidência relevante. " + "z".repeat(2000)),
                src("s3", big),
            ),
            emptyList(),
        )
        val out = LeanRag.compact(pack, maxTokens = 1200)!!
        val total = out.contentSources.sumOf { LeanRag.sourceTokens(it) }
        assertTrue("total=$total", total <= 1200)
        assertEquals(listOf("s1", "s2"), out.contentSources.map { it.id })
    }

    @Test
    fun duplicates_are_removed_keeping_first() {
        val pack = ContextPack(
            listOf(
                src("s1", "Texto repetido aqui."),
                src("s2", "Texto   repetido\naqui."),
                src("s3", "Texto diferente."),
            ),
            emptyList(),
        )
        val out = LeanRag.compact(pack)!!
        assertEquals(listOf("s1", "s3"), out.contentSources.map { it.id })
    }

    @Test
    fun metadata_is_preserved() {
        val pack = ContextPack(listOf(src("s1", "Algum texto de evidência.")), emptyList())
        val out = LeanRag.compact(pack)!!
        val kept = out.contentSources.single()
        assertEquals("Ref s1", kept.reference)
        assertEquals("Pub s1", kept.publication)
        assertEquals("Seção s1", kept.section)
        assertEquals(3, kept.paragraph)
        assertEquals(12, kept.page)
    }

    @Test
    fun single_huge_source_is_truncated_with_marker() {
        val pack = ContextPack(listOf(src("s1", "w".repeat(9000))), emptyList())
        val out = LeanRag.compact(pack, maxTokens = 100)!!
        assertEquals(1, out.contentSources.size)
        val text = out.contentSources.single().text
        assertTrue(text.endsWith(LeanRag.TRUNCATION_MARKER))
        assertTrue(LeanRag.estimateTokens(text) <= 100)
    }

    @Test
    fun training_shares_the_budget() {
        val filler = "v".repeat(1900)
        val pack = ContextPack(
            listOf(src("s1", filler)),
            listOf(src("t1", filler, training = true)),
        )
        // 1900 chars ≈ 475 tok cada; orçamento 500 só cabe o conteúdo.
        val out = LeanRag.compact(pack, maxTokens = 500)!!
        assertEquals(1, out.contentSources.size)
        assertTrue(out.trainingSources.isEmpty())
    }

    @Test
    fun zero_budget_empties() {
        val pack = ContextPack(listOf(src("s1", "Texto.")), emptyList())
        val out = LeanRag.compact(pack, maxTokens = 0)!!
        assertTrue(out.contentSources.isEmpty())
    }

    @Test
    fun estimate_is_chars_over_four() {
        assertEquals(1, LeanRag.estimateTokens("abc"))
        assertEquals(1, LeanRag.estimateTokens("abcd"))
        assertEquals(2, LeanRag.estimateTokens("abcde"))
    }
}
