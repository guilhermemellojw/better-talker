package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.repo.DownloadCandidate

/**
 * Phase 2 — Scanner de publicações em Downloads.
 *
 * Casa arquivos de Downloads contra publicações citadas na nota, reusando
 * [RefDetector.matchEdition]. Dedupe por edição: se múltiplos arquivos casam,
 * prefere JWPUB > EPUB > PDF. Verifica se a edição já está no acervo.
 *
 * Puro/testável: recebe listas, devolve matches. Sem IO, sem Context.
 */
object PublicationScanner {

    /** Resultado do scan para uma publicação citada. */
    data class ScanMatch(
        val ref: RefDetector.DetectedRef,
        val candidate: DownloadCandidate,
        val alreadyInAcervo: Boolean,
        val format: DocKind,
    )

    /** Resultado agregado do scan. */
    data class ScanResult(
        val matches: List<ScanMatch>,
        val imported: Int = 0,
        val alreadyInAcervo: Int = 0,
        val notFound: List<RefDetector.DetectedRef> = emptyList(),
        val unrecognized: List<DownloadCandidate> = emptyList(),
    ) {
        /** Total de publicações citadas. */
        val citedCount: Int get() = matches.size + notFound.size

        /** Resumo curto para a UI. */
        val summary: String get() = buildString {
            val found = matches.size
            val importedCount = imported
            val alreadyCount = alreadyInAcervo
            val missingCount = notFound.size
            append("$found encontrada(s)")
            if (importedCount > 0) append(": $importedCount importada(s)")
            if (alreadyCount > 0) append(", $alreadyCount já disponível(is)")
            if (missingCount > 0) append("; $missingCount ainda falta(m)")
        }
    }

    /**
     * Casa arquivos de Downloads contra publicações citadas.
     *
     * @param candidates arquivos encontrados em Downloads
     * @param citedRefs publicações citadas na nota
     * @param existingAttachments anexos já no acervo (para dedupe)
     * @return lista de matches (publicação → arquivo)
     */
    fun match(
        candidates: List<DownloadCandidate>,
        citedRefs: List<RefDetector.DetectedRef>,
        existingAttachments: List<AttachmentEntity>,
    ): List<ScanMatch> {
        if (citedRefs.isEmpty() || candidates.isEmpty()) return emptyList()

        // Sintetiza AttachmentEntity a partir dos candidatos de Downloads
        val syntheticAttachments = candidates.map { c ->
            AttachmentEntity(
                id = "scan-${c.name.hashCode()}",
                noteId = null,
                fileName = c.name,
                kind = c.name.substringAfterLast('.', "").lowercase(),
                sizeBytes = c.sizeBytes,
                appPath = c.uriString,
                indexed = false,
                addedAt = 0L,
                baseSlot = null,
                status = "indexing",
                error = null,
            )
        }

        val result = mutableListOf<ScanMatch>()
        for (ref in citedRefs) {
            if (ref.ambiguous) continue
            // Verifica se já está no acervo
            val existing = RefDetector.matchEdition(ref, existingAttachments, includePending = true)
            val alreadyInAcervo = existing != null

            // Casa contra Downloads
            val matched = RefDetector.matchEdition(ref, syntheticAttachments, includePending = true)
            if (matched != null) {
                val candidate = candidates.firstOrNull { it.name == matched.fileName }
                if (candidate != null) {
                    result += ScanMatch(
                        ref = ref,
                        candidate = candidate,
                        alreadyInAcervo = alreadyInAcervo,
                        format = detectKind(candidate.name),
                    )
                }
            }
        }
        return result
    }

    /**
     * Identifica publicações citadas sem arquivo em Downloads.
     */
    fun notFoundRefs(
        citedRefs: List<RefDetector.DetectedRef>,
        matches: List<ScanMatch>,
    ): List<RefDetector.DetectedRef> {
        val matchedRefs = matches.map { it.ref.editionKey }.toSet()
        return citedRefs.filter { !it.ambiguous && it.editionKey !in matchedRefs }
    }

    /**
     * Identifica arquivos em Downloads que não casam com nenhuma publicação citada.
     */
    fun unrecognizedCandidates(
        candidates: List<DownloadCandidate>,
        matches: List<ScanMatch>,
    ): List<DownloadCandidate> {
        val matchedNames = matches.map { it.candidate.name }.toSet()
        return candidates.filter { it.name !in matchedNames }
    }
}
