package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.util.BibleSourceStatus
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.NoteSourcesResolver
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2.1 — resolvedor das fontes do ponto atual.
 *
 * Contrato: prioridade subponto → seção → pack (8) → pergunta; dedup pelo
 * mesmo `sourceKey` do checklist; dossiê/pinned não repetem texto; toda ref
 * (inclusive omitida) tem status, unidade e motivo.
 */
class FocusSourceResolverTest {

    private val w2507 = "w25.07 22 §§ 8-9"
    private val jer = "Jer. 45:3"
    private val jerDisplay = "Jeremias 45:3"

    private fun noteSourcesOf(
        refs: NoteRefs,
        pubStatuses: Map<String, RefDetector.RefStatus> = emptyMap(),
        bibleStatuses: Map<String, BibleSourceStatus> = emptyMap(),
    ): NoteSources = NoteSourcesResolver.toNoteSources(
        candidates = NoteSourcesResolver.candidatesOf(refs),
        publicationStatuses = pubStatuses,
        bibleStatuses = bibleStatuses,
    )

    private fun refStatus(ref: RefDetector.DetectedRef, resolved: Boolean = true): RefDetector.RefStatus =
        RefDetector.RefStatus(ref = ref, resolved = resolved, contentResolved = resolved)

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

    @Test
    fun subpontoTemPrioridadeSobreSecaoEPack() {
        val refs = NoteRefsCollector.collect(plainText = "$w2507; $jer")
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val bible = RefDetector.detectBible(jer).first()
        val bibleKey = FocusSourceResolver.bibleKey(bible)
        val sources = noteSourcesOf(
            refs,
            pubStatuses = mapOf(key to refStatus(ref)),
            bibleStatuses = mapOf(bibleKey to BibleSourceStatus(true, true)),
        )
        val input = FocusSourcesInput(
            noteRefs = refs,
            noteSources = sources,
            resolvedTexts = mapOf(
                key to FocusText(w2507, "Baruque não estava satisfeito.", "pág. 22 §8-9"),
                bibleKey to FocusText(jer, "Baruque...", "45:3"),
            ),
            subPointKeys = setOf(w2507),
            sectionKeys = setOf(w2507, jer),
            packSources = listOf(evidence(w2507, "texto do pack duplicado")),
        )

        val out = FocusSourceResolver.resolve(input)

        assertEquals(FocusOrigin.SUBPOINT, out.entries.first { it.key == key }.origin)
        assertEquals(FocusOrigin.SECTION, out.entries.first { it.key == bibleKey }.origin)
        assertEquals(FocusDestination.FOCUS_BLOCK, out.destinationOf(key))
        assertEquals(FocusDestination.FOCUS_BLOCK, out.destinationOf(bibleKey))
        assertTrue(out.block.startsWith(FOCUS_BLOCK_HEADER))
        assertTrue(out.block.contains(w2507))
        assertTrue(out.block.contains(jerDisplay))
        assertFalse("pack duplicado não repete texto", out.block.contains("texto do pack duplicado"))
    }

    @Test
    fun textoQueJaEstaNoDossieNaoEhRepetido() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(refs, mapOf(key to refStatus(ref))),
                resolvedTexts = mapOf(key to FocusText(w2507, "Baruque tinha uma designação.")),
                sectionKeys = setOf(w2507),
                dossierRefs = setOf(w2507),
            ),
        )

        val entry = out.entries.single { it.key == key }
        assertEquals(FocusDestination.DOSSIER, entry.destination)
        assertTrue(out.block.contains(w2507))
        assertTrue(out.block.contains("já no dossiê"))
        assertFalse(out.block.contains("Baruque tinha uma designação."))
    }

    @Test
    fun textoPinnedViraPackSemDuplicar() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(refs, mapOf(key to refStatus(ref))),
                resolvedTexts = mapOf(key to FocusText(w2507, "texto pinned")),
                sectionKeys = setOf(w2507),
                pinnedRefs = setOf(w2507),
            ),
        )

        assertEquals(FocusDestination.PACK, out.destinationOf(key))
        assertFalse(out.block.contains("texto pinned"))
    }

    @Test
    fun packEAPerguntaEntramComoDestinoSemTextoDuplicado() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(refs, mapOf(key to refStatus(ref))),
                resolvedTexts = mapOf(key to FocusText(w2507, "texto da nota")),
                sectionKeys = setOf(w2507),
                packSources = (1..10).map { evidence("w25.07 1 § $it", "pack $it") },
                questionSources = listOf(evidence("Jer. 45:5", "versículo da pergunta")),
            ),
        )

        assertEquals(8, out.entries.count { it.origin == FocusOrigin.PACK })
        assertEquals(FocusDestination.QUESTION, out.destinationOf(FocusSourceResolver.keyOf("Jer. 45:5")!!))
        assertFalse(out.block.contains("pack 1"))
        assertFalse(out.block.contains("versículo da pergunta"))
    }

    @Test
    fun refDaNotaForaDoPontoFicaNoInventarioComMotivo() {
        val refs = NoteRefsCollector.collect(plainText = "$w2507; $jer")
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val bible = RefDetector.detectBible(jer).first()
        val bibleKey = FocusSourceResolver.bibleKey(bible)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(
                    refs,
                    mapOf(key to refStatus(ref)),
                    mapOf(bibleKey to BibleSourceStatus(true, true)),
                ),
                resolvedTexts = mapOf(
                    key to FocusText(w2507, "texto"),
                    bibleKey to FocusText(jer, "texto bíblico"),
                ),
                sectionKeys = setOf(w2507),
            ),
        )

        val other = out.entries.single { it.key == bibleKey }
        assertEquals(FocusDestination.INVENTORY, other.destination)
        assertNotNull(other.omissionReason)
        assertTrue(other.omissionReason!!.contains("fora do ponto atual"))
    }

    @Test
    fun statusIndexFailedEDicaoAusenteViramOmissaoComMotivo() {
        val refs = NoteRefsCollector.collect(plainText = "lmd lição 7 ponto 4; w24.01 § 3")
        val lmd = refs.publications.first { it.pubKey == "lmd" }
        val w24 = refs.publications.first { it.editionKey == "w|2024|1" }
        val sources = noteSourcesOf(
            refs,
            pubStatuses = mapOf(
                NoteRefsCollector.sourceKey(lmd) to RefDetector.RefStatus(
                    ref = lmd, resolved = false, hint = "Indexação falhou.",
                ),
                NoteRefsCollector.sourceKey(w24) to RefDetector.RefStatus(
                    ref = w24, resolved = false, hint = "Baixe a edição.",
                ),
            ),
        )
        // Força o estado de falha de indexação no checklist.
        val withFailed = sources.copy(
            sources = sources.sources.map {
                if (it.refStatus?.ref?.pubKey == "lmd") it.copy(status = SourceStatus.INDEX_FAILED) else it
            },
        )
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(noteRefs = refs, noteSources = withFailed),
        )

        val lmdEntry = out.entries.first { it.key == NoteRefsCollector.sourceKey(lmd) }
        assertEquals(FocusStatus.INDEX_FAILED, lmdEntry.status)
        assertEquals(FocusDestination.OMITTED, lmdEntry.destination)
        assertTrue(lmdEntry.omissionReason!!.contains("indexação falhou"))
        val w24Entry = out.entries.first { it.key == NoteRefsCollector.sourceKey(w24) }
        assertEquals(FocusStatus.EDITION_MISSING, w24Entry.status)
        assertEquals(FocusDestination.OMITTED, w24Entry.destination)
        assertTrue(w24Entry.omissionReason!!.contains("edição ausente"))
    }

    @Test
    fun truncamentoMarcaPartialEInformaChars() {
        val refs = NoteRefsCollector.collect(plainText = w2507)
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val long = "x".repeat(600)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(refs, mapOf(key to refStatus(ref))),
                resolvedTexts = mapOf(key to FocusText(w2507, long)),
                sectionKeys = setOf(w2507),
                maxChars = 360,
            ),
        )

        val entry = out.entries.single { it.key == key }
        assertEquals(FocusStatus.PARTIAL, entry.status)
        assertEquals(FocusDestination.FOCUS_BLOCK, entry.destination)
        assertEquals(600, entry.originalChars)
        assertTrue(entry.truncatedChars in 1 until 600)
        assertTrue(entry.isTruncated)
        assertTrue(out.block.contains("texto truncado"))
        assertTrue(out.block.length <= 360)
    }

    @Test
    fun unidadeDePublicacaoEBibliaSaoRegistradas() {
        val refs = NoteRefsCollector.collect(plainText = "w25.07 22 §§ 8-9; Jer. 45:3")
        val pub = refs.publications.single()
        val bible = refs.bibles.single()
        val out = FocusSourceResolver.resolve(FocusSourcesInput(noteRefs = refs))

        assertEquals("pág. 22 §8-9", out.entries.first { it.key == NoteRefsCollector.sourceKey(pub) }.unit)
        assertEquals("45:3", out.entries.first { it.key == FocusSourceResolver.bibleKey(bible) }.unit)
        assertEquals(SourceKind.BIBLE, out.entries.first { it.key == FocusSourceResolver.bibleKey(bible) }.kind)
    }

    @Test
    fun contagensReconciliamRefsDaNotaComExtrasDoTurno() {
        val refs = NoteRefsCollector.collect(plainText = "$w2507; $jer")
        val ref = RefDetector.detect(w2507).first()
        val key = NoteRefsCollector.sourceKey(ref)
        val bible = RefDetector.detectBible(jer).first()
        val bibleKey = FocusSourceResolver.bibleKey(bible)
        val extra = "w25.07 1 § 2"
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(
                    refs,
                    pubStatuses = mapOf(key to refStatus(ref)),
                    bibleStatuses = mapOf(bibleKey to BibleSourceStatus(true, true)),
                ),
                resolvedTexts = mapOf(
                    key to FocusText(w2507, "texto do ponto"),
                    bibleKey to FocusText(jer, "texto bíblico"),
                ),
                sectionKeys = setOf(w2507),
                packSources = listOf(
                    evidence(w2507, "texto do pack"),
                    evidence(extra, "extra do pack"),
                ),
                pinnedRefs = setOf(w2507),
            ),
        )

        val counts = out.counts
        assertEquals(1, counts.point)
        assertEquals(1, counts.pointContent)
        assertEquals(1, counts.content)
        assertEquals(1, counts.pack)
        assertEquals(2, counts.noteRefs)
        assertEquals(3, counts.turnRefs)
        assertEquals(1, counts.extras)
        assertEquals(1, out.extras.size)
        assertTrue(out.telemetryLine().contains("pack=1"))
        assertTrue(out.telemetryLine().contains("note_refs=2"))
        assertTrue(out.telemetryLine().contains("turn_refs=3"))
        assertTrue(out.telemetryLine().contains("extras=1"))
    }

    @Test
    fun orientacaoNaoEntraComoConteudoNemNoDenominador() {
        val refs = NoteRefsCollector.collect(plainText = "th lição 3; $jer")
        val th = refs.publications.single { it.pubKey == "th" }
        val bible = refs.bibles.single()
        val thKey = NoteRefsCollector.sourceKey(th)
        val bibleKey = FocusSourceResolver.bibleKey(bible)
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSourcesOf(
                    refs,
                    pubStatuses = mapOf(thKey to refStatus(th)),
                    bibleStatuses = mapOf(bibleKey to BibleSourceStatus(true, true)),
                ),
                resolvedTexts = mapOf(
                    thKey to FocusText("th lição 3", "orientação"),
                    bibleKey to FocusText(jer, "texto bíblico"),
                ),
                subPointKeys = setOf("th lição 3", jer),
                packSources = listOf(evidence(jer, "texto do pack")),
            ),
        )

        val counts = out.counts
        assertEquals(2, counts.point)
        assertEquals(1, counts.pointContent)
        assertEquals(1, counts.content)
        assertEquals(1, counts.orientation)
        assertEquals(1, counts.orientationInventory)
        assertEquals(0, counts.extras)
    }

    @Test
    fun noTextEBudgetSaoContadosSeparadamente() {
        val refs = NoteRefsCollector.collect(plainText = "w24.01 § 3; $w2507")
        val missing = refs.publications.first { it.editionKey == "w|2024|1" }
        val present = refs.publications.first { it.editionKey == "w|2025|7" }
        val missingKey = NoteRefsCollector.sourceKey(missing)
        val presentKey = NoteRefsCollector.sourceKey(present)
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = NoteSourcesResolver.candidatesOf(refs),
            publicationStatuses = mapOf(
                missingKey to RefDetector.RefStatus(ref = missing, resolved = false),
                presentKey to RefDetector.RefStatus(
                    ref = present, resolved = true, contentResolved = true,
                ),
            ),
            bibleStatuses = emptyMap(),
        )
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = sources,
                resolvedTexts = mapOf(presentKey to FocusText(w2507, "x".repeat(600))),
                sectionKeys = setOf("w24.01 § 3", w2507),
                maxChars = 360,
            ),
        )

        val counts = out.counts
        assertEquals(1, counts.noText)
        assertEquals(1, counts.newText)
        assertEquals(1, counts.truncated)
        assertEquals(0, counts.budget)
    }
}
