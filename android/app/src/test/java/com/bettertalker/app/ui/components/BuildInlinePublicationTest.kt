package com.bettertalker.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.bettertalker.app.data.publication.PublicationReferenceOccurrence
import com.bettertalker.app.data.publication.PublicationReferenceSpanDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-REF-1B.3 / B0 — contrato puro dos links de publicação: identidade,
 * offsets e coexistência com os links bíblicos. Nenhum Compose runtime aqui.
 */
class BuildInlinePublicationTest {

    private fun occurrence(text: String, token: String): PublicationReferenceOccurrence {
        val start = text.indexOf(token)
        check(start >= 0) { "token ausente no texto de teste" }
        return PublicationReferenceOccurrence(
            raw = token,
            start = start,
            endExclusive = start + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
    }

    @Test
    fun publicacaoViraUriPropriaComOffsetsExatos() {
        val text = "Veja Achegue-se a Jeová, capítulo 5."
        val occ = occurrence(text, "Achegue-se a Jeová")
        val link = publicationDisplayLinks(text, listOf(occ), blockIndex = 2).single()

        assertEquals("2:0", link.id)
        assertSame(occ, link.occurrence)
        assertEquals(text, link.sourceText)

        val annotated = buildInline(text, publicationLinks = listOf(link), linkColor = Color.Red)
        assertEquals("texto renderizado não pode mudar", text, annotated.text)

        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val range = links.single()
        val url = range.item as LinkAnnotation.Url
        assertEquals("bettertalker-publication://open?i=2%3A0", url.url)
        assertEquals(occ.start, range.start)
        assertEquals(occ.endExclusive, range.end)
        assertEquals(Color.Red, url.styles?.style?.color)
    }

    @Test
    fun publicacaoEBibliaCoexistemEmSpansDisjuntos() {
        val text = "Leia João 3:16 e Achegue-se a Jeová."
        val bibleToken = "João 3:16"
        val bibleStart = text.indexOf(bibleToken)
        val bible = InlineBibleLink(
            start = bibleStart,
            endExclusive = bibleStart + bibleToken.length,
            tag = "Jo%C3%A3o%203%3A16",
            label = bibleToken,
        )
        val pub = publicationDisplayLinks(text, listOf(occurrence(text, "Achegue-se a Jeová"))).single()

        val annotated = buildInline(text, bibleLinks = listOf(bible), publicationLinks = listOf(pub))
        assertEquals(text, annotated.text)

        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(2, links.size)
        assertTrue(links.any { (it.item as LinkAnnotation.Url).url.startsWith("bettertalker-bible://") })
        assertTrue(links.any { (it.item as LinkAnnotation.Url).url.startsWith(PUBLICATION_LINK_URI_PREFIX) })
    }

    @Test
    fun spansIdenticosSaoRejeitados() {
        val text = "Achegue-se a Jeová"
        val pub = publicationDisplayLinks(text, listOf(occurrence(text, text)))
        val bible = InlineBibleLink(
            start = 0,
            endExclusive = text.length,
            tag = "x",
            label = text,
        )

        val annotated = buildInline(text, bibleLinks = listOf(bible), publicationLinks = pub)
        val links = annotated.getLinkAnnotations(0, annotated.length)

        assertEquals(0, links.size)
        assertEquals(text, annotated.text)
    }

    @Test
    fun spanForaDoTextoIgnorado() {
        val text = "abc"
        val occ = PublicationReferenceOccurrence(
            raw = "abc",
            start = 0,
            endExclusive = 99,
            publicationKey = "lff",
            titleHint = "abc",
            editionKey = null,
        )
        val links = publicationDisplayLinks(text, listOf(occ))
        assertEquals(0, links.size)

        val annotated = buildInline(text, publicationLinks = links)
        assertEquals(0, annotated.getLinkAnnotations(0, annotated.length).size)
        assertEquals(text, annotated.text)
    }

    @Test
    fun mapeamentoPreservaOffsetsDeValidacao() {
        val token = "Estudo Perspicaz 1/24"
        val text = "$token, pág. 6 §§ 8-9"
        val start = text.indexOf(token)
        val occ = PublicationReferenceOccurrence(
            raw = token,
            start = start,
            endExclusive = start + token.length,
            publicationKey = "it",
            titleHint = "Estudo Perspicaz",
            editionKey = null,
            validationStart = start,
            validationEndExclusive = text.length,
        )

        val links = publicationDisplayLinks(text, listOf(occ), blockIndex = 3)

        assertEquals(1, links.size)
        assertEquals("3:0", links.single().id)
        assertEquals(occ.validationStart, links.single().occurrence.validationStart)
        assertEquals(occ.validationEndExclusive, links.single().occurrence.validationEndExclusive)
        assertEquals(text, links.single().sourceText)
    }

    @Test
    fun p5OffsetsTrocadosSaoRejeitados() {
        val text = "Achegue-se a Jeová, capítulo 5. Achegue-se a Jeová, capítulo 10."
        val occs = PublicationReferenceSpanDetector.detect(text)
        check(occs.size >= 2) { "detector não produziu duas ocorrências" }
        val first = occs[0]
        val second = occs[1]
        val validLinks = publicationDisplayLinks(text, listOf(first, second))
        assertEquals(2, validLinks.size)

        // Descritor adulterado: offsets do segundo apontam para a primeira ocorrência.
        val swapped = validLinks.mapIndexed { idx, link ->
            if (idx == 1) link.copy(start = first.start, endExclusive = first.endExclusive) else link
        }
        val annotated = buildInline(text, publicationLinks = swapped)

        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, rendered.size)
        val range = rendered.single()
        val url = range.item as LinkAnnotation.Url
        assertEquals(publicationLinkUri(validLinks[0].id), url.url)
        assertEquals(first.start, range.start)
        assertEquals(first.endExclusive, range.end)
    }

    @Test
    fun detectorRealProduzLinks() {
        val text = "Veja Achegue-se a Jeová, capítulo 5."
        val occs = PublicationReferenceSpanDetector.detect(text)
        assertTrue("detector deve reconhecer a referência", occs.isNotEmpty())
        val links = publicationDisplayLinks(text, occs)
        assertTrue("deve haver descritor válido", links.isNotEmpty())
        val annotated = buildInline(text, publicationLinks = links)
        assertEquals(text, annotated.text)

        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(links.size, rendered.size)
        val occ = occs.first()
        val link = links.first()
        val range = rendered.first()
        val url = range.item as LinkAnnotation.Url
        assertEquals(publicationLinkUri(link.id), url.url)
        assertEquals(occ.start, range.start)
        assertEquals(occ.endExclusive, range.end)
    }

    @Test
    fun idDuplicadoEhRejeitado() {
        val text = "Achegue-se a Jeová"
        val occ = occurrence(text, text)
        val link = publicationDisplayLinks(text, listOf(occ)).single()
        val dup = link.copy(id = link.id) // mesmo id, outro descritor
        val annotated = buildInline(text, publicationLinks = listOf(link, dup))
        assertEquals(0, annotated.getLinkAnnotations(0, annotated.length).size)
        assertEquals(text, annotated.text)
    }


    @Test
    fun negritoPreservaOffsetsVisuais() {
        val token = "Achegue-se a Jeová"
        val text = "**$token**, capítulo 5."
        val start = text.indexOf(token)
        val occ = PublicationReferenceOccurrence(
            raw = token,
            start = start,
            endExclusive = start + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
        val link = publicationDisplayLinks(text, listOf(occ)).single()
        val annotated = buildInline(text, publicationLinks = listOf(link))

        val expectedText = "$token, capítulo 5."
        assertEquals(expectedText, annotated.text)
        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, rendered.size)
        val range = rendered.single()
        val url = range.item as LinkAnnotation.Url
        assertEquals(publicationLinkUri(link.id), url.url)
        assertEquals(0, range.start)
        assertEquals(token.length, range.end)
    }

    @Test
    fun italicoPreservaOffsetsVisuais() {
        val token = "Achegue-se a Jeová"
        val text = "_${token}_, capítulo 5."
        val start = text.indexOf(token)
        val occ = PublicationReferenceOccurrence(
            raw = token,
            start = start,
            endExclusive = start + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
        val link = publicationDisplayLinks(text, listOf(occ)).single()
        val annotated = buildInline(text, publicationLinks = listOf(link))

        val expectedText = "$token, capítulo 5."
        assertEquals(expectedText, annotated.text)
        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, rendered.size)
        val range = rendered.single()
        val url = range.item as LinkAnnotation.Url
        assertEquals(publicationLinkUri(link.id), url.url)
        assertEquals(0, range.start)
        assertEquals(token.length, range.end)
    }

    @Test
    fun referenciaRepetidaPreservaIdentidade() {
        val token = "Achegue-se a Jeová"
        val text = "$token, capítulo 5. $token, capítulo 10."
        val firstStart = text.indexOf(token)
        val secondStart = text.indexOf(token, firstStart + 1)
        val firstOcc = PublicationReferenceOccurrence(
            raw = token,
            start = firstStart,
            endExclusive = firstStart + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
        val secondOcc = PublicationReferenceOccurrence(
            raw = token,
            start = secondStart,
            endExclusive = secondStart + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
        val links = publicationDisplayLinks(text, listOf(firstOcc, secondOcc))
        assertEquals(2, links.size)
        assertEquals(firstOcc, links[0].occurrence)
        assertEquals(secondOcc, links[1].occurrence)
        assertEquals("0:0", links[0].id)
        assertEquals("0:1", links[1].id)

        val annotated = buildInline(text, publicationLinks = links)
        assertEquals(text, annotated.text)
        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(2, rendered.size)
        assertEquals(firstStart, rendered[0].start)
        assertEquals(firstStart + token.length, rendered[0].end)
        assertEquals(secondStart, rendered[1].start)
        assertEquals(secondStart + token.length, rendered[1].end)
    }

    @Test
    fun codigoInlineEhProtegido() {
        val text = "Use `Achegue-se a Jeová` na frase."
        val occs = PublicationReferenceSpanDetector.detect(text)
        val links = publicationDisplayLinks(text, occs)
        val annotated = buildInline(text, publicationLinks = links)
        assertEquals("Use Achegue-se a Jeová na frase.", annotated.text)
        assertTrue("código inline protegido", links.isEmpty())
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun linkMarkdownExistenteNaoAninha() {
        val token = "Achegue-se a Jeová"
        val text = "Veja [fonte](https://www.jw.org) e $token."
        val start = text.indexOf(token)
        val occ = PublicationReferenceOccurrence(
            raw = token,
            start = start,
            endExclusive = start + token.length,
            publicationKey = "lff",
            titleHint = token,
            editionKey = null,
        )
        val link = publicationDisplayLinks(text, listOf(occ)).single()
        val annotated = buildInline(text, publicationLinks = listOf(link))
        assertEquals("Veja fonte e $token.", annotated.text)
        val rendered = annotated.getLinkAnnotations(0, annotated.length)
        // Publicação renderizada sem aninhar com o link Markdown.
        assertEquals(1, rendered.count { (it.item as LinkAnnotation.Url).url.startsWith(PUBLICATION_LINK_URI_PREFIX) })
    }

}
