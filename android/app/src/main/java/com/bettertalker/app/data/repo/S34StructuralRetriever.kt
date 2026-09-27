package com.bettertalker.app.data.repo

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import com.bettertalker.app.data.s34.S34StructuralRetrieval.Resolution
import com.bettertalker.app.data.s34.S34StructuralRetrieval.ScopedView

/**
 * Fase 19-B.4 — retrieval ESTRUTURAL fino (§ "retrieval por sectionId").
 *
 * Só orquestra: resolve outline pela source persistida (B.3) e delega a
 * lógica pura (B.4). Não toca no índice textual legado, não chama LLM,
 * não monta ContextPack.
 *
 * Contrato do resultado: SEMPRE um estado explícito. Nunca devolve outro
 * outline, outra seção ou o documento inteiro no lugar do pedido.
 */
class S34StructuralRetriever(private val outlines: S34OutlineRepository) {

    sealed interface Result {
        /** Ponto em foco, ordenado, com refs vinculadas. */
        data class SectionFocus(val view: ScopedView) : Result

        /** Escopo pedido foi o documento: TODOS os pontos, em ordem. */
        data class DocumentScope(val outlineId: String, val sections: List<ScopedView>) : Result

        /** Não há S-34 persistido para esta source (legado decide o resto). */
        data object NoOutline : Result

        /** Havia outline; a seção pedida não pertence a ele. Nunca cai no resto. */
        data class UnknownSection(val outlineId: String, val requested: String) : Result

        /** Dica de ponto ambígua/ausente no outline. Nunca chuta. */
        data class UnmatchedSection(val outlineId: String, val hint: String) : Result
    }

    /**
     * @param sourceAttachmentId arquivo importado (mesma identidade da B.3)
     * @param sectionId ponto explícito; tem precedência sobre [sectionHint]
     * @param sectionHint dica textual (ex.: título do bloco ativo)
     * @param query marca `matched` sem reordenar
     */
    suspend fun retrieve(
        sourceAttachmentId: String,
        sectionId: String? = null,
        sectionHint: String? = null,
        query: String = ""
    ): Result {
        val doc = outlines.getBySource(sourceAttachmentId) ?: return Result.NoOutline
        return scope(doc, sectionId, sectionHint, query)
    }

    /** Mesma lógica para um documento já em mãos (sem nova query ao banco). */
    fun scope(
        doc: S34Document,
        sectionId: String? = null,
        sectionHint: String? = null,
        query: String = ""
    ): Result {
        if (sectionId != null) {
            val view = S34StructuralRetrieval.scopeToSection(doc, sectionId)
                ?: return Result.UnknownSection(doc.id, sectionId)
            return Result.SectionFocus(S34StructuralRetrieval.markMatches(view, query))
        }
        return when (val r = S34StructuralRetrieval.resolveSection(doc, sectionHint)) {
            is Resolution.Resolved -> {
                val view = S34StructuralRetrieval.scopeToSection(doc, r.sectionId)!!
                Result.SectionFocus(S34StructuralRetrieval.markMatches(view, query))
            }
            is Resolution.NoHint -> Result.DocumentScope(
                doc.id,
                S34StructuralRetrieval.scopeToDocument(doc)
                    .map { S34StructuralRetrieval.markMatches(it, query) }
            )
            is Resolution.Unmatched -> Result.UnmatchedSection(doc.id, r.hint)
        }
    }
}
