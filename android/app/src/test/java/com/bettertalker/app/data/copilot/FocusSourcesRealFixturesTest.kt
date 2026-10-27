package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Parser
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
 * C2.0 — fixtures REAIS com o inventário por ref.
 *
 * Para cada referência: unidade, status, texto, destino no prompt e motivo
 * quando omitida. S-34_T_003 (4 seções/30 min) e V&M de 4 linhas.
 */
class FocusSourcesRealFixturesTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!
            .bufferedReader()
            .readText()
            .replace("\uFEFF", "")
            .replace("\u200b", " ")
            .replace("\u00a0", " ")
            .replace("\r\n", "\n")

    private fun bibleKey(ref: RefDetector.BibleRef) = FocusSourceResolver.bibleKey(ref)

    private fun baseSources(
        refs: NoteRefs,
        biblesReady: Boolean = true,
    ): NoteSources {
        val pubStatuses = refs.publications.associate { ref ->
            NoteRefsCollector.sourceKey(ref) to RefDetector.RefStatus(
                ref = ref,
                resolved = true,
                contentResolved = true,
            )
        }
        val bibleStatuses = refs.bibles.associate { bible ->
            bibleKey(bible) to BibleSourceStatus(
                available = biblesReady,
                verseFound = biblesReady,
            )
        }
        return NoteSourcesResolver.toNoteSources(
            candidates = NoteSourcesResolver.candidatesOf(refs),
            publicationStatuses = pubStatuses,
            bibleStatuses = bibleStatuses,
        )
    }

    private fun withStatus(sources: NoteSources, editionKey: String, status: SourceStatus): NoteSources =
        sources.copy(
            sources = sources.sources.map {
                if (it.editionKey == editionKey) it.copy(status = status) else it
            },
            resolved = sources.sources.count {
                val effective = if (it.editionKey == editionKey) status else it.status
                effective == SourceStatus.RESOLVED
            },
        )

    @Test
    fun s34Real_registraUnidadeStatusTextoDestinoEMotivoParaCadaRef() {
        val raw = fixture("S-34_T_003.txt")
        val doc = S34Parser.parseS34(raw)
        val refs = NoteRefsCollector.collect(noteTitle = doc.title, plainText = raw)
        assertEquals(70, refs.publications.size + refs.bibles.size)

        val sectionRefs = NoteRefsCollector.collect(mdText = doc.sections.first().content)
        val sectionKeys = (
            sectionRefs.publications.map { NoteRefsCollector.sourceKey(it) } +
                sectionRefs.bibles.map { bibleKey(it) }
            ).toSet()
        val resolvedTexts = buildMap {
            refs.publications.forEach { ref ->
                put(
                    NoteRefsCollector.sourceKey(ref),
                    FocusText(ref.raw, "Trecho de ${ref.raw}", unit = "unidade"),
                )
            }
            refs.bibles.forEach { bible ->
                put(
                    bibleKey(bible),
                    FocusText(
                        reference = "${bible.label} ${bible.chapter}:${bible.verse}",
                        text = "Texto de ${bible.label} ${bible.chapter}:${bible.verse}",
                        unit = "${bible.chapter}:${bible.verse}",
                    ),
                )
            }
        }

        val sources = withStatus(
            withStatus(
                withStatus(baseSources(refs), "book|re", SourceStatus.INDEX_FAILED),
                "book|sj",
                SourceStatus.UNSUPPORTED,
            ),
            "w|2012|7|15",
            SourceStatus.EDITION_MISSING,
        )
        val dossierRef = sectionRefs.publications.first().raw
        val pinnedBible = sectionRefs.bibles.first()
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = sources,
                resolvedTexts = resolvedTexts,
                sectionKeys = sectionKeys,
                dossierRefs = setOf(dossierRef),
                pinnedRefs = setOf("${pinnedBible.label} ${pinnedBible.chapter}:${pinnedBible.verse}"),
            ),
        )

        assertEquals(70, out.entries.size)
        assertTrue(out.entries.all { it.reference.isNotBlank() })
        assertTrue(out.entries.filter { it.destination == FocusDestination.OMITTED }
            .all { !it.omissionReason.isNullOrBlank() })
        assertTrue(out.entries.filter { it.destination == FocusDestination.FOCUS_BLOCK }
            .all { it.text.isNotBlank() && it.truncatedChars <= it.originalChars })

        // Dossiê e pinned não repetem texto no bloco.
        assertEquals(FocusDestination.DOSSIER, out.destinationOf(
            NoteRefsCollector.sourceKey(sectionRefs.publications.first())))
        assertEquals(FocusDestination.PACK, out.destinationOf(bibleKey(pinnedBible)))
        assertTrue(out.block.startsWith(FOCUS_BLOCK_HEADER))
        assertFalse(out.block.contains("Trecho de $dossierRef"))
        assertTrue(out.entries.none {
            it.destination == FocusDestination.FOCUS_BLOCK &&
                it.key == bibleKey(pinnedBible)
        })

        // Seção 1: 10 refs, 2 já no dossiê/pinned, 8 no bloco.
        assertEquals(8, out.entries.count { it.destination == FocusDestination.FOCUS_BLOCK })
        assertEquals(10, out.counts.point)
        assertEquals(10, out.counts.pointContent)
        assertEquals(10, out.counts.content)
        assertEquals(1, out.counts.dossier)
        assertEquals(1, out.counts.pack)
        assertEquals(8, out.counts.newText)
        assertEquals(70, out.counts.noteRefs)
        assertEquals(70, out.counts.turnRefs)
        assertEquals(0, out.counts.extras)
        assertTrue(out.telemetryLine().contains("note_refs=70"))

        // Fora do ponto: resolvida vai para o inventário, com motivo.
        val outside = out.entries.first { it.reference.startsWith("Salmos 48:12") }
        assertEquals(FocusDestination.INVENTORY, outside.destination)
        assertNotNull(outside.omissionReason)

        // Edição com indexação falhou e formato não suportado.
        val re = out.entries.first { it.kind == SourceKind.PUBLICATION && it.reference.startsWith("re ") }
        assertEquals(FocusStatus.INDEX_FAILED, re.status)
        assertEquals(FocusDestination.OMITTED, re.destination)
        assertTrue(re.omissionReason!!.contains("indexação falhou"))
        val sj = out.entries.first { it.kind == SourceKind.PUBLICATION && it.publicationLabel == "Escola" }
        assertEquals(FocusStatus.UNSUPPORTED, sj.status)
        assertTrue(sj.omissionReason!!.contains("formato não suportado"))

        // Edição ausente fora da seção 1.
        val w12 = out.entries.first { it.reference.startsWith("w12 ") }
        assertEquals(FocusStatus.EDITION_MISSING, w12.status)
        assertTrue(w12.omissionReason!!.contains("edição ausente"))

        assertTrue(out.block.length <= MAX_FOCUS_BLOCK_CHARS)
    }

    @Test
    fun vmReal_registraAs5RefsComUnidadeETexto() {
        val text = """
            Ter esperança é o segredo para o contentamento (10 min)
            Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)
            Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)
            Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
        """.trimIndent()
        val refs = NoteRefsCollector.collect(plainText = text)
        assertEquals(5, refs.publications.size + refs.bibles.size)

        val keys = (
            refs.publications.map { NoteRefsCollector.sourceKey(it) } +
                refs.bibles.map { bibleKey(it) }
            ).toSet()
        val resolvedTexts = buildMap {
            refs.publications.forEach { ref ->
                put(NoteRefsCollector.sourceKey(ref), FocusText(ref.raw, "Trecho de ${ref.raw}"))
            }
            refs.bibles.forEach { bible ->
                put(
                    bibleKey(bible),
                    FocusText("${bible.label} ${bible.chapter}:${bible.verse}", "Texto bíblico"),
                )
            }
        }
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = baseSources(refs),
                resolvedTexts = resolvedTexts,
                subPointKeys = keys,
            ),
        )

        assertEquals(5, out.entries.size)
        assertEquals(5, out.entries.count { it.destination == FocusDestination.FOCUS_BLOCK })
        assertEquals(5, out.counts.point)
        assertEquals(5, out.counts.pointContent)
        assertEquals(5, out.counts.content)
        assertEquals(5, out.counts.newText)
        assertEquals(5, out.counts.noteRefs)
        assertEquals(5, out.counts.turnRefs)
        assertEquals(0, out.counts.extras)
        assertEquals(2, out.entries.count { it.kind == SourceKind.BIBLE })
        assertEquals(3, out.entries.count { it.kind == SourceKind.PUBLICATION })
        assertTrue(out.block.contains("Jeremias 45:3"))
        assertTrue(out.block.contains("w25.07 22 §§ 8-9"))
        assertEquals(
            "pág. 22 §8-9",
            out.entries.first { it.reference == "w25.07 22 §§ 8-9" }.unit,
        )
        assertEquals(
            "45:3",
            out.entries.first { it.reference == "Jeremias 45:3" }.unit,
        )
    }

    @Test
    fun fakes_tnmReadyW2507ReadyLmdFailedThReadyEEdicoesAusentes() {
        val text = "lmd lição 7 ponto 4; th lição 3; w24.01 § 3; Jer. 45:3; w25.07 22 §§ 8-9"
        val refs = NoteRefsCollector.collect(plainText = text)
        val sources = baseSources(refs).let { base ->
            base.copy(
                sources = base.sources.map {
                    when {
                        it.publicationKey == "lmd" -> it.copy(status = SourceStatus.INDEX_FAILED)
                        it.publicationKey == "w24.01" -> it.copy(status = SourceStatus.EDITION_MISSING)
                        else -> it
                    }
                },
            )
        }
        val resolvedTexts = buildMap {
            refs.publications
                .filter { it.pubKey != "lmd" && it.pubKey != "w" || it.editionKey == "w|2025|7" }
                .forEach { put(NoteRefsCollector.sourceKey(it), FocusText(it.raw, "texto de ${it.raw}")) }
            refs.bibles.forEach {
                put(bibleKey(it), FocusText("${it.label} ${it.chapter}:${it.verse}", "texto bíblico"))
            }
        }
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(noteRefs = refs, noteSources = sources, resolvedTexts = resolvedTexts),
        )

        val lmd = out.entries.first { it.publicationLabel?.startsWith("Ame as Pessoas") == true }
        assertEquals(FocusStatus.INDEX_FAILED, lmd.status)
        assertEquals(FocusDestination.OMITTED, lmd.destination)
        assertTrue(lmd.omissionReason!!.contains("indexação falhou"))

        val th = out.entries.first { it.reference.startsWith("th ") }
        assertEquals(FocusStatus.RESOLVED, th.status)
        assertTrue(th.text.isNotBlank())
        assertEquals(FocusDestination.INVENTORY, th.destination)
        assertTrue(th.omissionReason!!.contains("orientação de oratória"))

        val missing = out.entries.first { it.reference.startsWith("w24.01") }
        assertEquals(FocusStatus.EDITION_MISSING, missing.status)
        assertTrue(missing.omissionReason!!.contains("edição ausente"))

        val w25 = out.entries.first { it.reference.startsWith("w25.07") }
        assertEquals(FocusStatus.RESOLVED, w25.status)
        val jer = out.entries.first { it.reference.startsWith("Jeremias") }
        assertEquals(FocusStatus.RESOLVED, jer.status)
    }

    @Test
    fun lmdLiteralComEdicaoFailedEntraNoInventarioComUnidadeEspecifica() {
        val refs = NoteRefsCollector.collect(plainText = "lmd; lmd lição 7 ponto 4")
        val sources = baseSources(refs).let { base ->
            base.copy(
                sources = base.sources.map {
                    if (it.publicationKey == "lmd") {
                        it.copy(status = SourceStatus.INDEX_FAILED)
                    } else it
                },
            )
        }
        val out = FocusSourceResolver.resolve(
            FocusSourcesInput(noteRefs = refs, noteSources = sources),
        )

        val lmd = out.entries.single { it.publicationKey == "lmd" }
        assertEquals("lição 7 §4", lmd.unit)
        assertEquals(FocusStatus.INDEX_FAILED, lmd.status)
        assertEquals(FocusDestination.OMITTED, lmd.destination)
        assertTrue(lmd.omissionReason!!.contains("indexação falhou"))
    }
}
