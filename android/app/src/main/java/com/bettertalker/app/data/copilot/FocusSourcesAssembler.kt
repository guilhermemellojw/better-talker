package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.toSectionRefs
import com.bettertalker.app.data.util.toSubPointRefs
import com.bettertalker.app.domain.planning.Dossier

/**
 * C2.1 — fachada de montagem das fontes do ponto atual.
 *
 * Recebe a espinha já coletada/resolvida (`NoteRefs` + `NoteSources`) e as
 * trilhas do turno (dossiê, pack, pergunta). Mantém o resolvedor puro; aqui
 * só há mapeamento de textos/keys. Puro/testável: sem Android, sem IO.
 */
object FocusSourcesAssembler {

    fun assemble(
        refs: NoteRefs,
        noteSources: NoteSources,
        citedSources: List<EvidenceSource>,
        pack: ContextPack,
        dossier: Dossier?,
        question: String,
        structural: OutlineStructureContext?,
    ): FocusSources {
        val subPointKeys = dossier?.currentSubPoint?.let { sub ->
            keysOf(NoteRefsCollector.collect(subPoints = listOf(sub.toSubPointRefs())))
        } ?: emptySet()
        val sectionKeys = buildSet {
            dossier?.currentSection?.let { section ->
                addAll(keysOf(NoteRefsCollector.collect(sections = listOf(section.toSectionRefs()))))
            }
            structural?.currentSection?.let { section ->
                val text = buildString {
                    append(section.content).append('\n')
                    section.references.forEach { append(it.rawText).append('\n') }
                    section.subsections.forEach { sub ->
                        append(sub.content).append('\n')
                        sub.references.forEach { append(it.rawText).append('\n') }
                    }
                }
                addAll(keysOf(NoteRefsCollector.collect(mdText = text)))
            }
        }
        val questionKeys = if (question.isBlank()) {
            emptySet()
        } else {
            keysOf(NoteRefsCollector.collect(mdText = question))
        }
        val resolvedTexts = buildMap {
            citedSources.forEach { source ->
                val key = FocusSourceResolver.keyOf(source.reference) ?: return@forEach
                put(key, FocusText(source.reference, source.text))
            }
            dossier?.bibleTexts?.forEach { bible ->
                val text = bible.text?.takeIf { it.isNotBlank() } ?: return@forEach
                val key = FocusSourceResolver.keyOf(bible.ref) ?: return@forEach
                putIfAbsent(key, FocusText(bible.ref, text))
            }
            dossier?.publicationTexts?.forEach { publication ->
                val text = publication.text?.takeIf { it.isNotBlank() } ?: return@forEach
                val detected = NoteRefsCollector.publicationRefToDetected(publication.ref)
                    ?: return@forEach
                putIfAbsent(
                    NoteRefsCollector.sourceKey(detected),
                    FocusText(publication.ref.symbol, text),
                )
            }
            pack.contentSources.forEach { source ->
                val key = FocusSourceResolver.keyOf(source.reference) ?: return@forEach
                putIfAbsent(key, FocusText(source.reference, source.text))
            }
        }
        val dossierRefs = buildSet {
            dossier?.bibleTexts?.filter { !it.text.isNullOrBlank() }?.forEach { add(it.ref) }
            dossier?.publicationTexts?.filter { !it.text.isNullOrBlank() }?.forEach { publication ->
                val detected = NoteRefsCollector.publicationRefToDetected(publication.ref)
                if (detected != null) add(NoteRefsCollector.sourceKey(detected))
            }
        }
        return FocusSourceResolver.resolve(
            FocusSourcesInput(
                noteRefs = refs,
                noteSources = noteSources,
                resolvedTexts = resolvedTexts,
                subPointKeys = subPointKeys,
                sectionKeys = sectionKeys,
                questionKeys = questionKeys,
                packSources = pack.contentSources,
                dossierRefs = dossierRefs,
                pinnedRefs = pack.contentSources.map { it.reference }.toSet(),
            ),
        )
    }

    fun keysOf(refs: NoteRefs): Set<String> = buildSet {
        refs.publications.forEach { add(NoteRefsCollector.sourceKey(it)) }
        refs.bibles.forEach { add(FocusSourceResolver.bibleKey(it)) }
    }
}
