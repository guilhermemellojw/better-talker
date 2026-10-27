package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1 — inventário de referências no prompt. O modelo precisa saber
 * exatamente o que a nota cita e o que existe no acervo, sem receber o texto
 * completo (esse continua no RAG/dossiê).
 */
class NoteRefsInventoryTest {

    private fun pub(
        ref: String,
        status: SourceStatus = SourceStatus.RESOLVED,
        pubKey: String = "w25.07",
        editionKey: String = "w|2025|7",
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.PUBLICATION,
        status = status,
        editionKey = editionKey,
        publicationKey = pubKey,
    )

    private fun bible(
        ref: String,
        status: SourceStatus = SourceStatus.RESOLVED,
        book: String = "Jeremias",
        chapter: Int = 29,
        verse: Int = 11,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.BIBLE,
        status = status,
        book = book,
        chapter = chapter,
        verse = verse,
        editionKey = "bible|nwt",
        publicationKey = "nwt",
        publicationLabel = "TNM",
    )

    private fun sources(vararg items: NoteSource) = NoteSources(
        sources = items.toList(),
        resolved = items.count { it.status == SourceStatus.RESOLVED },
        total = items.size,
    )

    @Test
    fun inventarioListaPublicacaoCitadaNoTituloDoBody() {
        val text = buildNoteRefsInventory(
            sources(pub("lmd lição 7 ponto 4", pubKey = "lmd", editionKey = "book|lmd")),
        )
        assertTrue(text.contains("Publicações citadas:"))
        assertTrue(text.contains("- lmd lição 7 ponto 4 — edição no acervo; trecho resolvido"))
    }

    @Test
    fun inventarioListaRefDeSubpontoS34() {
        val text = buildNoteRefsInventory(
            sources(pub("w25.07 p. 22 §8-§9")),
        )
        assertTrue(text.contains("- w25.07 p. 22 §8-§9 — edição no acervo; trecho resolvido"))
    }

    @Test
    fun inventarioDistingueTrechoAusenteDeEdicaoAusente() {
        val text = buildNoteRefsInventory(
            sources(
                pub("w25.07 § 999", SourceStatus.TEXT_NOT_FOUND),
                pub("w24.01 § 3", SourceStatus.EDITION_MISSING, pubKey = "w24.01", editionKey = "w|2024|1"),
            ),
        )
        // Edição existe, trecho não: o modelo NÃO pode dizer que a publicação
        // inteira não está no acervo.
        assertTrue(text.contains("w25.07 § 999 — edição no acervo; trecho não encontrado"))
        assertFalse(text.contains("w25.07 § 999 — edição ausente do acervo"))
        assertTrue(text.contains("w24.01 § 3 — edição ausente do acervo"))
    }

    @Test
    fun inventarioMostraDownloadEIndexacao() {
        val text = buildNoteRefsInventory(
            sources(
                pub("w25.08 § 1", SourceStatus.DOWNLOADING, pubKey = "w25.08", editionKey = "w|2025|8"),
                pub("w25.09 § 2", SourceStatus.INDEXING, pubKey = "w25.09", editionKey = "w|2025|9"),
            ),
        )
        assertTrue(text.contains("w25.08 § 1 — baixando — acompanhe na Biblioteca"))
        assertTrue(text.contains("w25.09 § 2 — indexando — o Copilot usa quando terminar"))
    }

    @Test
    fun inventarioDistingueIndexFailedDeFormatoNaoSuportado() {
        val text = buildNoteRefsInventory(
            sources(
                pub("lmd lição 7 §4", SourceStatus.INDEX_FAILED, pubKey = "lmd", editionKey = "book|lmd"),
                pub("w24.01 § 3", SourceStatus.UNSUPPORTED, pubKey = "w24.01", editionKey = "w|2024|1"),
            ),
        )
        assertTrue(text.contains("lmd lição 7 §4 — edição importada; indexação falhou"))
        assertTrue(text.contains("w24.01 § 3 — edição em formato não suportado"))
    }

    @Test
    fun inventarioGuiaNaoEntraComoConteudo() {
        val text = buildNoteRefsInventory(
            sources(
                pub("th lição 3", pubKey = "th", editionKey = "book|th"),
                pub("be cap. 5", pubKey = "be", editionKey = "book|be"),
                pub("w25.07 § 8"),
            ),
        )
        val contentBlock = text.substringAfter("Publicações citadas:")
            .substringBefore("Orientação de oratória")
        assertFalse(contentBlock.contains("th lição 3"))
        assertFalse(contentBlock.contains("be cap. 5"))
        assertTrue(contentBlock.contains("w25.07 § 8"))
        val guideBlock = text.substringAfter("Orientação de oratória")
            .substringBefore("Bíblia:")
        assertTrue(guideBlock.contains("th lição 3"))
        assertTrue(guideBlock.contains("be cap. 5"))
    }

    @Test
    fun inventarioBibliaResolvidaEAusente() {
        val text = buildNoteRefsInventory(
            sources(
                bible("Je 29:11", SourceStatus.RESOLVED),
                bible("Gên 3:6", SourceStatus.EDITION_MISSING, book = "Gênesis", chapter = 3, verse = 6),
                bible("Sal 103:20", SourceStatus.TEXT_NOT_FOUND, book = "Salmos", chapter = 103, verse = 20),
            ),
        )
        assertTrue(text.contains("Je 29:11 — TNM resolvida"))
        assertTrue(text.contains("Gên 3:6 — TNM ausente"))
        assertTrue(text.contains("Sal 103:20 — TNM presente; versículo não encontrado"))
    }

    @Test
    fun inventarioCapPriorizaFocoENaoResolvidas() {
        val manyResolved = (1..40).map { pub("w25.07 § $it") }
        val unresolved = pub("w24.01 § 999", SourceStatus.EDITION_MISSING, pubKey = "w24.01", editionKey = "w|2024|1")
        val focus = pub("lmd lição 7 ponto 4", pubKey = "lmd", editionKey = "book|lmd")
        val out = buildNoteRefsInventoryDetailed(
            sources(focus, unresolved, *manyResolved.toTypedArray()),
            focusKeys = setOf(focus.ref),
            maxChars = 420,
        )
        assertTrue(out.omitted > 0)
        assertTrue(out.text.contains(focus.ref))
        assertTrue(out.text.contains(unresolved.ref))
        assertTrue(out.text.contains("omitida(s)"))
        assertTrue(out.text.length <= 420)
    }

    @Test
    fun inventarioCapUsaSourceKeyDoFoco() {
        val detected = RefDetector.DetectedRef(
            raw = "w25.07 22 §§ 8-9",
            kind = RefDetector.Kind.MAGAZINE,
            pubKey = "w",
            editionKey = "w|2025|7",
            label = "A Sentinela julho de 2025",
            page = 22,
            paragraph = 8,
            paragraphEnd = 9,
        )
        val focusKey = NoteRefsCollector.sourceKey(detected)
        val focus = NoteSource(
            ref = "w25.07 p. 22 §8-§9",
            kind = SourceKind.PUBLICATION,
            status = SourceStatus.RESOLVED,
            editionKey = "w|2025|7",
            publicationKey = "w25.07",
            refStatus = RefDetector.RefStatus(ref = detected, resolved = true, contentResolved = true),
        )
        val out = buildNoteRefsInventoryDetailed(
            sources(*((1..30).map { pub("w25.07 § $it") } + focus).toTypedArray()),
            focusKeys = setOf(focusKey),
            maxChars = 260,
        )
        assertTrue(out.text.contains(focus.ref))
    }

    @Test
    fun inventarioVazioNaoInjetaBloco() {
        val text = buildNoteRefsInventory(NoteSources(emptyList(), 0, 0))
        assertTrue(text.isEmpty())
    }
}
