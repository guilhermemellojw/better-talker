package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SectionRefs
import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitedPublicationTest {
    private fun att(name: String) = AttachmentEntity(
        id = "a-$name",
        noteId = null,
        fileName = name,
        kind = name.substringAfterLast('.', "pdf"),
        sizeBytes = 1L,
        appPath = "",
        indexed = true,
        addedAt = 0L,
    )

    private fun ref(raw: String) = RefDetector.detect(raw).first()

    @Test
    fun mesmaEdicaoEmDuasNotasViraUmaLinha() {
        val cited = citedPublicationsFrom(
            listOf(ref("w25.07 §8"), ref("w25.07 §12")),
            emptyList(),
        )
        assertEquals(1, cited.size)
        assertEquals("w|2025|7", cited.single().editionKey)
        assertTrue(cited.single().label.contains("Sentinela", ignoreCase = true))
        assertNotNull(cited.single().downloadUrl)
    }

    @Test
    fun edicaoNoAcervoNaoEntra() {
        val cited = citedPublicationsFrom(
            listOf(ref("w25.07")),
            listOf(att("w_T_202507.jwpub")),
        )
        assertTrue(cited.isEmpty())
    }

    @Test
    fun ambiguoNaoRecebeUrl() {
        val cited = citedPublicationsFrom(listOf(ref("g 1/16")), emptyList())
        assertEquals(1, cited.size)
        assertTrue(cited.single().ambiguous)
        assertNull(cited.single().downloadUrl)
        assertTrue(!cited.single().hint.isNullOrBlank())
    }

    @Test
    fun refsDeSecaoVmEntramNaColeta() {
        val refs = NoteRefsCollector.collect(
            sections = listOf(
                SectionRefs(
                    title = "Parte",
                    publicationRefs = listOf(PublicationRef("w25.07")),
                ),
            ),
        )
        val cited = citedPublicationsFrom(refs.publications, emptyList())
        assertEquals(1, cited.size)
        assertFalse(cited.single().ambiguous)
    }
}
