package com.bettertalker.app.domain.planning

/**
 * Draft gerado pelo Copilot para uma seção/sub-ponto.
 *
 * @param textHtml HTML com `<p>` por parágrafo (pronto para
 *   `developedHtml`/`contentHtml`).
 * @param usedSources refs do dossiê efetivamente usadas pelo LLM.
 * @param validation resultado da verificação de fidelidade.
 */
data class SectionDraft(
    val textHtml: String,
    val usedSources: List<String>,
    val validation: DossierFidelityReport,
)

/**
 * Relatório de fidelidade do draft contra o dossiê.
 *
 * `ok` = todas as listas vazias.
 */
data class DossierFidelityReport(
    /** Versículos citados no texto que não estão em bibleTexts. */
    val inventedBibleRefs: List<String>,
    /** Publicações citadas no texto que não estão em publicationTexts. */
    val inventedPublicationRefs: List<String>,
    /** usedSources fora do dossiê. */
    val usedSourcesOutsideDossier: List<String>,
    /** Refs do unresolvedRefs que aparecem no texto. */
    val unresolvedRefsCited: List<String>,
) {
    val ok: Boolean
        get() = inventedBibleRefs.isEmpty() &&
            inventedPublicationRefs.isEmpty() &&
            usedSourcesOutsideDossier.isEmpty() &&
            unresolvedRefsCited.isEmpty()
}
