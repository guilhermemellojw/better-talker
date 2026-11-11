package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.data.util.normalizeText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.0a/P0 — reader da faixa completa:
 * - página + §§ usa byPageParagraph e junta o texto integral;
 * - jr 29 lê a página 29;
 * - edição ausente/indexando e trecho não encontrado têm motivo.
 */
class SourceReaderLoaderTest {

    private fun attachment(id: String = "jr", fileName: String = "jr_T.jwpub") =
        AttachmentEntity(
            id = id,
            noteId = null,
            fileName = fileName,
            kind = "jwpub",
            sizeBytes = 1,
            appPath = "/x",
            indexed = true,
            status = "ready",
            addedAt = 0,
        )

    private fun passage(
        id: String,
        ord: Int,
        text: String,
        page: Int? = null,
        paragraph: Int? = null,
    ) = PassageEntity(
        id = "$id-p$ord",
        attachmentId = id,
        text = text,
        normalized = normalizeText(text),
        section = "",
        page = page,
        paragraph = paragraph,
        ord = ord,
    )

    private fun ref(
        raw: String,
        page: Int? = null,
        paragraph: Int? = null,
        paragraphEnd: Int? = null,
    ) = RefDetector.DetectedRef(
        raw = raw,
        kind = RefDetector.Kind.BOOK,
        pubKey = "jr",
        editionKey = "book|jr",
        label = "Jeremias — Uma Mensagem de Deus para Nós" +
            (page?.let { " — p. $it" } ?: ""),
        page = page,
        paragraph = paragraph,
        paragraphEnd = paragraphEnd,
    )

    private fun source(
        ref: RefDetector.DetectedRef,
        edition: EditionStatus = EditionStatus.READY,
        text: TextStatus = TextStatus.RESOLVED,
        hint: String? = null,
        promptText: String? = "Trecho usado no prompt.",
        fileName: String? = "jr_T.jwpub",
        publicationLabel: String = "Jeremias — Uma Mensagem de Deus para Nós",
    ) = NoteSource(
        ref = ref.raw,
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(edition, text),
        hint = hint,
        fileName = fileName,
        refStatus = RefDetector.RefStatus(
            ref = ref,
            resolved = edition == EditionStatus.READY,
            contentResolved = text == TextStatus.RESOLVED,
            promptText = promptText,
            snippet = promptText?.take(30),
        ),
        editionKey = "book|jr",
        publicationKey = "jr",
        publicationLabel = publicationLabel,
    )

    @Test
    fun carregaFaixaDaPaginaComTextoIntegral() = runBlocking {
        var called: Triple<Int, Int, Int>? = null
        val long9 = "9 Jurar em nome de Deus não é algo leviano. " + "x ".repeat(300) +
            "Eze. 17:16."
        val text10 = "10 Ele desprezou o juramento. 2 Crô. 36:13; Eze. 17:11-15, 17, 18."
        val loader = SourceReaderLoader(
            attachments = { listOf(attachment()) },
            byPageParagraph = { _, page, start, end ->
                called = Triple(page, start, end)
                listOf(
                    passage("jr", 0, long9, page = page, paragraph = 9),
                    passage("jr", 1, text10, page = page, paragraph = 10),
                )
            },
            byPage = { _, _, _, _ -> emptyList() },
            unitSlice = { _, _, _ -> null },
        )
        val content = loader.load(
            source(ref("w17.10 23 §§ 9-10", page = 23, paragraph = 9, paragraphEnd = 10))
        )

        assertEquals(Triple(23, 9, 10), called)
        assertEquals(SourceReaderStatus.READY, content.status)
        assertEquals("p. 23 · §§ 9-10", content.locationLabel)
        assertTrue(content.body.contains("Eze. 17:16."))
        assertTrue(content.body.contains("2 Crô. 36:13; Eze. 17:11-15, 17, 18."))
        assertTrue("reader não pode truncar a faixa", content.body.length > 500)
        assertFalse(content.truncated)
        assertEquals("Trecho usado no prompt.", content.usedText)
    }

    @Test
    fun jr29LeAPagina29ENaoEstudo() = runBlocking {
        var called: Pair<Int, Int>? = null
        val loader = SourceReaderLoader(
            attachments = { listOf(attachment()) },
            byPageParagraph = { _, _, _, _ -> emptyList() },
            byPage = { _, start, end, _ ->
                called = start to end
                listOf(
                    passage("jr", 0, "Texto da página 29.", page = 29),
                )
            },
            unitSlice = { _, _, _ -> null },
        )
        val content = loader.load(source(ref("jr 29", page = 29)))

        assertEquals(29 to 29, called)
        assertEquals(SourceReaderStatus.READY, content.status)
        assertEquals("p. 29", content.locationLabel)
        assertTrue(content.referenceLabel.contains("— p. 29"))
        assertTrue(content.interpretation.orEmpty().startsWith("Interpretação: jr 29 →"))
        assertTrue(content.body.contains("Texto da página 29"))
    }

    @Test
    fun edicaoAusenteNaoConsultaIndice() = runBlocking {
        var queried = false
        val loader = SourceReaderLoader(
            attachments = { listOf(attachment()) },
            byPageParagraph = { _, _, _, _ -> queried = true; emptyList() },
            byPage = { _, _, _, _ -> queried = true; emptyList() },
            unitSlice = { _, _, _ -> queried = true; null },
        )
        val content = loader.load(
            source(
                ref("jr 29", page = 29),
                edition = EditionStatus.MISSING,
                text = TextStatus.NOT_FOUND,
                hint = "Edição não importada.",
            )
        )
        assertEquals(SourceReaderStatus.MISSING, content.status)
        assertEquals("Edição não importada.", content.reason)
        assertFalse(queried)
    }

    @Test
    fun trechoNaoEncontradoTemMotivo() = runBlocking {
        val loader = SourceReaderLoader(
            attachments = { listOf(attachment()) },
            byPageParagraph = { _, _, _, _ -> emptyList() },
            byPage = { _, _, _, _ -> emptyList() },
            unitSlice = { _, _, _ -> null },
        )
        val content = loader.load(source(ref("jr 29", page = 29)))
        assertEquals(SourceReaderStatus.TEXT_NOT_FOUND, content.status)
        assertTrue(content.reason.orEmpty().contains("trecho"))
    }
}
