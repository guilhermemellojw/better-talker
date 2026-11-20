package com.bettertalker.app.data.publication

import com.bettertalker.app.data.util.EditionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationReferenceNoticeTest {

    private fun target(partial: Boolean = false) = PublicationReaderTarget(
        rawReference = "lmd lição 3 ponto 4",
        displayRef = "Ame as Pessoas — lição 3, ponto 4",
        canonicalRef = "lmd lição 3 §4",
        publicationKey = "lmd",
        publicationTitle = "Ame as Pessoas",
        editionKey = "book|lmd",
        editionLabel = null,
        expectedAttachmentId = "att-lmd",
        fileName = "lmd_T.jwpub",
        sourceLabel = "Ame as Pessoas",
        partial = partial,
        evidenceId = null,
        usedText = null,
    )

    @Test
    fun availableNaoGeraAviso() {
        assertNull(
            publicationReferenceNotice(
                PublicationReferenceResolution.Available(target()),
            ),
        )
    }

    @Test
    fun partialAbreLeitorSemAvisoDeErro() {
        assertNull(
            publicationReferenceNotice(
                PublicationReferenceResolution.Partial(
                    target = target(partial = true),
                    warning = "trecho parcial",
                ),
            ),
        )
    }

    @Test
    fun notImportedOrientaBiblioteca() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.NotImported(
                reference = "w25.07 22 § 8",
                publicationTitle = "A Sentinela",
            ),
        )
        assertNotNull(notice)
        assertEquals("Publicação ainda não disponível", notice!!.title)
        assertTrue(notice.openLibrary)
    }

    @Test
    fun notReadyDistingueIndexacaoDeFalha() {
        val indexing = publicationReferenceNotice(
            PublicationReferenceResolution.NotReady("lmd lição 3", EditionStatus.INDEXING),
        )
        assertEquals("Publicação sendo preparada", indexing?.title)
        assertTrue(indexing?.openLibrary == true)

        val failed = publicationReferenceNotice(
            PublicationReferenceResolution.NotReady("lmd lição 3", EditionStatus.INDEX_FAILED),
        )
        assertEquals("Não foi possível indexar", failed?.title)
        assertTrue(failed?.openLibrary == true)

        val unsupported = publicationReferenceNotice(
            PublicationReferenceResolution.NotReady("lmd lição 3", EditionStatus.UNSUPPORTED),
        )
        assertEquals("Formato não suportado", unsupported?.title)
    }

    @Test
    fun textNotFoundNaoDizQuePublicacaoInteiraFalta() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.TextNotFound(
                reference = "lmd lição 3 §4",
                publicationTitle = "Ame as Pessoas",
                editionLabel = "Ame as Pessoas",
            ),
        )
        assertEquals("Trecho não encontrado", notice?.title)
        assertTrue(notice?.message.orEmpty().contains("trecho"))
        assertFalse(notice?.message.orEmpty().contains("publicação inteira"))
    }

    @Test
    fun ambiguousPedeEscolha() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.Ambiguous(
                reference = "g 1/16",
                candidates = emptyList(),
            ),
        )
        assertEquals("Mais de uma publicação possível", notice?.title)
        assertTrue(notice?.openLibrary == true)
    }

    @Test
    fun editionChangedApontaBiblioteca() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.EditionChanged(
                reference = "lmd lição 3",
                expectedAttachmentId = "att-antigo",
            ),
        )
        assertEquals("Publicação alterada", notice?.title)
        assertTrue(notice?.openLibrary == true)
    }

    @Test
    fun notRecognizedNaoAbreBiblioteca() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.NotRecognized("xyz"),
        )
        assertEquals("Referência não identificada", notice?.title)
        assertFalse(notice!!.openLibrary)
    }

    @Test
    fun readFailedNaoAbreBiblioteca() {
        val notice = publicationReferenceNotice(
            PublicationReferenceResolution.ReadFailed("lmd lição 3"),
        )
        assertEquals("Não foi possível abrir", notice?.title)
        assertFalse(notice!!.openLibrary)
    }
}
