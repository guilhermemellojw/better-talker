package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceAction
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.data.util.editionStatusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate determinístico: pergunta específica × pedido amplo × "continuar sem". */
class SourceGateTest {

    private fun pub(
        raw: String,
        status: SourceStatus,
        textStatus: TextStatus = com.bettertalker.app.data.util.defaultTextStatus(status),
        pubKey: String = "w25.07",
        editionKey: String = "w|2025|7",
    ): NoteSource {
        val detected = RefDetector.detect(raw).firstOrNull()
        return NoteSource(
            ref = raw,
            kind = SourceKind.PUBLICATION,
            citation = CitationStatus(editionStatusOf(status), textStatus),
            editionKey = detected?.editionKey ?: editionKey,
            publicationKey = pubKey,
            publicationLabel = pubKey,
            refStatus = detected?.let {
                RefDetector.RefStatus(
                    ref = it,
                    resolved = status == SourceStatus.RESOLVED,
                    contentResolved = status == SourceStatus.RESOLVED &&
                        textStatus != TextStatus.NOT_FOUND,
                )
            },
        )
    }

    private fun bible(status: SourceStatus): NoteSource = NoteSource(
        ref = "Gên 3:6",
        kind = SourceKind.BIBLE,
        citation = CitationStatus(editionStatusOf(status), com.bettertalker.app.data.util.defaultTextStatus(status)),
        book = "Gênesis",
        chapter = 3,
        verse = 6,
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
    fun perguntaEspecificaComEdicaoAusente_asksBeforeLlm() {
        val decision = SourceGate.decide(
            "O que w25.07 § 8 diz sobre esperança?",
            sources(pub("w25.07 § 8", SourceStatus.EDITION_MISSING)),
        )
        assertTrue(decision is SourceGateDecision.Ask)
        val ask = decision as SourceGateDecision.Ask
        assertEquals(1, ask.refs.size)
        assertEquals(SourceAction.DOWNLOAD, ask.refs.single().citation.action)
        assertEquals("O que w25.07 § 8 diz sobre esperança?", ask.question)
    }

    @Test
    fun perguntaSoDaEdicaoComTrechoAusente_asksReindex() {
        val resolved = pub("w25.07 22 § 8", SourceStatus.RESOLVED)
        val missingText = pub("w25.07 22 § 999", SourceStatus.TEXT_NOT_FOUND)
        val decision = SourceGate.decide(
            "O que w25.07 diz?",
            sources(resolved, missingText),
        )
        assertTrue(decision is SourceGateDecision.Ask)
        val ask = decision as SourceGateDecision.Ask
        assertEquals(1, ask.refs.size)
        assertEquals("w25.07 22 § 999", ask.refs.single().ref)
        assertEquals(SourceAction.REINDEX, ask.refs.single().citation.action)
    }

    @Test
    fun perguntaEspecificaComFonteResolvida_proceeds() {
        val decision = SourceGate.decide(
            "O que w25.07 § 8 diz?",
            sources(pub("w25.07 § 8", SourceStatus.RESOLVED)),
        )
        assertTrue(decision is SourceGateDecision.Proceed)
        assertNull((decision as SourceGateDecision.Proceed).warning)
    }

    @Test
    fun pedidoAmploComFonteAusente_proceedsComAviso() {
        val decision = SourceGate.decide(
            "Me ajude a melhorar a introdução.",
            sources(pub("w25.07 § 8", SourceStatus.EDITION_MISSING)),
        )
        assertTrue(decision is SourceGateDecision.Proceed)
        val warning = (decision as SourceGateDecision.Proceed).warning.orEmpty()
        assertTrue(warning.contains("NÃO diga que a referência não existe"))
        assertTrue(warning.contains("w25.07 § 8"))
        assertTrue(warning.contains("ação: baixar edição"))
    }

    @Test
    fun continuarSem_waivedNaoBloqueiaNovamente() {
        val missing = pub("w25.07 § 8", SourceStatus.EDITION_MISSING)
        val decision = SourceGate.decide(
            "O que w25.07 § 8 diz?",
            sources(missing),
            waived = setOf(missing.ref),
        )
        assertTrue(decision is SourceGateDecision.Proceed)
        val block = SourceGate.waivedBlock(listOf(missing)).orEmpty()
        assertTrue(block.contains("FONTES NÃO CONSULTADAS"))
        assertTrue(block.contains("w25.07 § 8"))
        assertTrue(block.contains("não foram consultadas"))
        assertFalse(block.contains("não existe"))
    }

    @Test
    fun referenciaAmbigua_asksConfirmacao() {
        val ambiguous = pub(
            "g 1/16",
            SourceStatus.EDITION_MISSING,
            textStatus = TextStatus.AMBIGUOUS,
            pubKey = "g",
            editionKey = "g|2016|1",
        )
        val decision = SourceGate.decide("Compare com g 1/16.", sources(ambiguous))
        assertTrue(decision is SourceGateDecision.Ask)
        assertEquals(
            SourceAction.CONFIRM,
            (decision as SourceGateDecision.Ask).refs.single().citation.action,
        )
    }

    @Test
    fun fonteBaixando_asksAguardar() {
        val decision = SourceGate.decide(
            "Use w25.07 § 8 nessa parte.",
            sources(pub("w25.07 § 8", SourceStatus.DOWNLOADING)),
        )
        assertTrue(decision is SourceGateDecision.Ask)
        assertEquals(
            SourceAction.WAIT,
            (decision as SourceGateDecision.Ask).refs.single().citation.action,
        )
    }

    @Test
    fun indexacaoFalhou_asksReindexar() {
        val decision = SourceGate.decide(
            "O que lmd lição 7 § 4 diz?",
            sources(pub("lmd lição 7 § 4", SourceStatus.INDEX_FAILED, pubKey = "lmd", editionKey = "book|lmd")),
        )
        assertTrue(decision is SourceGateDecision.Ask)
        assertEquals(
            SourceAction.REINDEX,
            (decision as SourceGateDecision.Ask).refs.single().citation.action,
        )
    }

    @Test
    fun bibliaAusenteEmPerguntaEspecifica_asksDownload() {
        val decision = SourceGate.decide("Leia Gênesis 3:6.", sources(bible(SourceStatus.EDITION_MISSING)))
        assertTrue(decision is SourceGateDecision.Ask)
        assertEquals(
            SourceAction.DOWNLOAD,
            (decision as SourceGateDecision.Ask).refs.single().citation.action,
        )
    }

    @Test
    fun orientacaoBeNuncaBloqueia() {
        val be = pub(
            "be cap. 5",
            SourceStatus.EDITION_MISSING,
            pubKey = "be",
            editionKey = "book|be",
        )
        val decision = SourceGate.decide("Como aplico be cap. 5?", sources(be))
        assertTrue(decision is SourceGateDecision.Proceed)
    }

    @Test
    fun perguntaSobreOutraRefNaoBloqueiaPorFonteDaNota() {
        val decision = SourceGate.decide(
            "O que w24.01 § 3 diz?",
            sources(pub("w25.07 § 8", SourceStatus.EDITION_MISSING)),
        )
        // w24.01 não está na nota: não é o caso de baixar w25.07.
        assertTrue(decision is SourceGateDecision.Proceed)
        // Mas o aviso amplo continua disponível para o prompt.
        assertFalse((decision as SourceGateDecision.Proceed).warning.isNullOrBlank())
    }
}
