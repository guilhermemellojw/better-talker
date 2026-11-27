package com.bettertalker.app.domain.planning

/**
 * Draft gerado pelo Copilot para uma seção/sub-ponto.
 *
 * @param textHtml HTML com `<p>` por parágrafo (pronto para
 *   `developedHtml`/`contentHtml`).
 * @param usedSources refs do dossiê efetivamente usadas pelo LLM.
 * @param validation resultado da verificação de fidelidade.
 * @param possiblyTruncated true quando o modelo parou por limite de
 *   tamanho (`finish_reason == "length"`) — o texto pode estar cortado.
 */
data class SectionDraft(
    val textHtml: String,
    val usedSources: List<String>,
    val validation: DossierFidelityReport,
    val possiblyTruncated: Boolean = false,
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
    /** Citações literais que não conferem com nenhum texto recuperado. */
    val unsupportedLiteralQuotes: List<String> = emptyList(),
) {
    val ok: Boolean
        get() = inventedBibleRefs.isEmpty() &&
            inventedPublicationRefs.isEmpty() &&
            usedSourcesOutsideDossier.isEmpty() &&
            unresolvedRefsCited.isEmpty() &&
            unsupportedLiteralQuotes.isEmpty()
}

/**
 * BETA-TRUST/P1 — aceite de rascunho é bloqueado enquanto houver qualquer
 * afirmação bíblica sem suporte verificável. Antes este era apenas informativo.
 */
fun draftAcceptAllowed(report: DossierFidelityReport): Boolean = report.ok
