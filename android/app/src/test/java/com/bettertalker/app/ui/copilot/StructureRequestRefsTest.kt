package com.bettertalker.app.ui.copilot

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F5f (E2E no device) — o request do #3c precisa agregar as refs dos
 * SUB-PONTOS (é onde o esboço as guarda); sem isto, seções criadas pelo app
 * chegavam sem refs e o validador barrava a proposta.
 */
class StructureRequestRefsTest {

    private fun section(
        bible: List<String> = emptyList(),
        pubs: List<PublicationRef> = emptyList(),
    ) = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = "A esperança que sustenta", minutes = 6, contentHtml = "",
        bibleRefs = bible, publicationRefs = pubs, methodPrinciple = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun sub(
        order: Int = 0,
        bible: List<String> = emptyList(),
        pubs: List<PublicationRef> = emptyList(),
    ) = SubPoint(
        id = "sp$order", sectionId = "s1", order = order, outlineText = "Ponto $order",
        bibleRefs = bible, publicationRefs = pubs, instruction = null,
        developedHtml = "", createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun caso1_secaoSemRefs_subpontoComRef_entraNoRequest() {
        val r = buildStructureRequest(
            section(),
            subPoints = listOf(sub(bible = listOf("Jo 3:16"))),
            id = "req-1",
        )
        assertEquals(listOf("Jo 3:16"), r.bibleRefs)
    }

    @Test
    fun caso2_secaoESubpontos_dedupeNaOrdemDeAparicao() {
        val r = buildStructureRequest(
            section(bible = listOf("Jo 3:16")),
            subPoints = listOf(
                sub(order = 0, bible = listOf("Jo 3:16", "Rm 5:12")),
                sub(order = 1, bible = listOf("Rm 5:12", "Sl 37:29")),
            ),
            id = "req-2",
        )
        assertEquals(listOf("Jo 3:16", "Rm 5:12", "Sl 37:29"), r.bibleRefs)
    }

    @Test
    fun caso3_semRefsEmLugarNenhum_vazio() {
        val r = buildStructureRequest(section(), subPoints = listOf(sub()), id = "req-3")
        assertEquals(emptyList<String>(), r.bibleRefs)
        assertEquals(emptyList<PublicationRef>(), r.publicationRefs)
    }

    @Test
    fun publicacoesTambemAgregamEDeduplicam() {
        val r = buildStructureRequest(
            section(pubs = listOf(PublicationRef("w13 15/5"))),
            subPoints = listOf(
                sub(order = 0, pubs = listOf(PublicationRef("w13 15/5"), PublicationRef("bh 109"))),
            ),
            id = "req-4",
        )
        assertEquals(listOf(PublicationRef("w13 15/5"), PublicationRef("bh 109")), r.publicationRefs)
    }

    @Test
    fun ordemDosSubPontosEhDocumentalEHaTrim() {
        val r = buildStructureRequest(
            section(),
            subPoints = listOf(
                sub(order = 2, bible = listOf("Sl 37:29")),
                sub(order = 1, bible = listOf("  Rm 5:12  ", " ")),
            ),
            id = "req-5",
        )
        assertEquals(listOf("Rm 5:12", "Sl 37:29"), r.bibleRefs)
    }
}
