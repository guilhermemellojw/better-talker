package com.bettertalker.app.data.bible

import com.bettertalker.app.domain.bible.BiblePassageReader
import com.bettertalker.app.domain.bible.BiblePassageSource
import com.bettertalker.app.domain.bible.BiblePassageStatus

/**
 * UX-REF-1A1 — resolve uma ocorrência bíblica contra o mesmo acervo que o
 * leitor usa.
 *
 * Regras:
 * - a consulta é local e determinística (sem LLM, sem rede);
 * - a edição escolhida é a mesma [BiblePassageSource.preferredSource] usada
 *   pelo [BiblePassageReader];
 * - uma referência reconhecida só vira alvo quando há texto real;
 * - `PARTIAL` continua navegável, mas carrega o aviso de incompletude;
 * - versículos de livros diferentes na mesma ocorrência são ambíguos;
 * - `evidenceId` nunca é inferido: disponibilidade não é uso pelo Mentor.
 */
class BibleReferenceResolver(
    private val reader: BiblePassageReader,
    private val source: BiblePassageSource,
) {

    suspend fun resolve(occurrence: BibleReferenceOccurrence): BibleReferenceResolution {
        if (occurrence.canonicalRefs.isEmpty()) {
            return BibleReferenceResolution.NotRecognized(
                "Referência bíblica não reconhecida.",
            )
        }
        val preferred = source.nwtSource()
            ?: return BibleReferenceResolution.NwtUnavailable(
                "A Tradução do Novo Mundo (NWT) não está disponível no acervo local.",
            )
        val passage = reader.read(occurrence.raw, requireNwt = true)
        if (passage.status == BiblePassageStatus.MISSING_CORPUS) {
            return BibleReferenceResolution.NwtUnavailable(
                passage.warning ?: "A Tradução do Novo Mundo (NWT) não está disponível.",
            )
        }
        if (passage.sourceAttachmentId == null) {
            return BibleReferenceResolution.NwtUnavailable(
                passage.warning ?: "Não foi possível identificar a edição lida.",
            )
        }
        if (passage.sourceAttachmentId != preferred.attachmentId) {
            // Nunca abrir uma edição diferente da escolhida pelo leitor.
            return BibleReferenceResolution.EditionChanged(
                "A edição disponível não corresponde à fonte de leitura.",
            )
        }
        if (passage.verses.isEmpty()) {
            return BibleReferenceResolution.PassageNotFound(
                passage.warning ?: "Nenhum versículo encontrado no acervo.",
            )
        }
        val books = passage.verses.map { it.book }.distinct()
        if (books.size > 1) {
            return BibleReferenceResolution.Ambiguous(
                reason = "A referência aponta para mais de um livro.",
                candidates = books,
            )
        }

        val target = BibleReaderTarget(
            rawReference = occurrence.raw,
            canonicalRefs = occurrence.canonicalRefs,
            displayRef = passage.displayRef,
            sourceAttachmentId = passage.sourceAttachmentId,
            sourceLabel = passage.sourceLabel,
            chapterOnly = occurrence.chapterOnly,
            partial = passage.status == BiblePassageStatus.PARTIAL,
            warning = passage.warning,
            evidenceId = null,
        )
        return when (passage.status) {
            BiblePassageStatus.RESOLVED ->
                BibleReferenceResolution.Available(target)

            BiblePassageStatus.PARTIAL ->
                BibleReferenceResolution.Partial(
                    target = target,
                    warning = passage.warning ?: "A leitura está incompleta.",
                )

            BiblePassageStatus.UNRESOLVED ->
                BibleReferenceResolution.PassageNotFound(
                    passage.warning ?: "Referência não encontrada no acervo.",
                )

            BiblePassageStatus.MISSING_CORPUS ->
                BibleReferenceResolution.NwtUnavailable(
                    passage.warning ?: "A Tradução do Novo Mundo (NWT) não está disponível.",
                )
        }
    }
}
