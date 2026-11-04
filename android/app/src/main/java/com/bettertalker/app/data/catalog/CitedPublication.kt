package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.toSectionRefs
import com.bettertalker.app.data.util.toSubPointRefs

/** Publicação citada em alguma nota/esboço e ainda ausente do acervo. */
data class CitedPublication(
    val editionKey: String,
    val label: String,
    val symbol: String,
    val downloadUrl: String?,
    val hint: String?,
    val ambiguous: Boolean,
)

/** Placeholder baixando/indexando não deve aparecer como "citada faltando". */
internal fun AttachmentEntity.isPendingCitation(): Boolean =
    !indexed && (status == "downloading" || status == "indexing")

/**
 * Deduplica por edição e resolve contra o acervo. Puro/testável.
 * Só devolve o que NÃO está disponível; se o mesmo editionKey aparece em
 * várias notas, vira uma linha só. Ambíguo fica sem URL para pedir conferência.
 */
internal fun citedPublicationsFrom(
    refs: List<RefDetector.DetectedRef>,
    attachments: List<AttachmentEntity>,
): List<CitedPublication> {
    val unique = refs
        .groupBy { it.editionKey }
        .mapNotNull { (_, group) -> group.firstOrNull { !it.ambiguous } ?: group.firstOrNull() }
    // A7: o resolve padrão só enxerga anexos indexados. Se a edição está
    // baixando/indexando, ela pertence a "Em andamento", não a "Citadas".
    val pendingKeys = unique.mapNotNull { ref ->
        if (RefDetector.matchEdition(ref, attachments) != null) return@mapNotNull null
        val pending = RefDetector.matchEdition(ref, attachments, includePending = true)
        ref.editionKey.takeIf { pending?.isPendingCitation() == true }
    }.toSet()
    return RefDetector.resolve(unique, attachments)
        .filter { !it.resolved && it.ref.editionKey !in pendingKeys }
        .map { status ->
            CitedPublication(
                editionKey = status.ref.editionKey,
                label = status.ref.label.ifBlank { status.ref.pubKey },
                symbol = status.ref.pubKey,
                downloadUrl = status.downloadUrl.takeIf { it.isNotBlank() },
                hint = status.hint.takeIf { it.isNotBlank() },
                ambiguous = status.ref.ambiguous,
            )
        }
        .sortedBy { it.label }
}

/**
 * Coleta one-shot das notas ativas + esboços/seções/subpontos. A Biblioteca
 * chama ao abrir e depois de importar/restaurar.
 */
suspend fun collectCitedPublications(
    db: AppDatabase,
    attachments: List<AttachmentEntity>? = null,
): List<CitedPublication> {
    val library = attachments ?: db.attachmentDao().all()
    val refs = mutableListOf<RefDetector.DetectedRef>()
    for (note in db.noteDao().allIncludingTrashed().filter { !it.trashed }) {
        val sections = db.speechSectionDao().forNote(note.id)
        val subPoints = sections.flatMap { section ->
            db.subPointDao().forSection(section.id)
        }
        val noteRefs = NoteRefsCollector.collect(
            noteTitle = note.title,
            mdText = note.mdText,
            plainText = note.plainText,
            outlineRefsJson = db.outlineDao().getForNote(note.id)?.refsJson.orEmpty(),
            sections = sections.map { it.toDomain().toSectionRefs() },
            subPoints = subPoints.map { it.toDomain().toSubPointRefs() },
        )
        refs += noteRefs.publications
    }
    return citedPublicationsFrom(refs, library)
}
