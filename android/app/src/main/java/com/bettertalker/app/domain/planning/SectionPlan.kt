package com.bettertalker.app.domain.planning

/**
 * Plano de uma seção do esboço.
 *
 * @param title título da seção.
 * @param minutes duração alvo em minutos.
 * @param mainIdea ideia central da seção.
 * @param bibleRefs referências bíblicas (ex.: ["Gê 3:6", "Rm 5:12"]).
 * @param publicationRefs referências a publicações.
 * @param methodPrinciple princípio de be/th aplicável, se houver.
 */
data class SectionPlan(
    val title: String,
    val minutes: Int,
    val mainIdea: String,
    val bibleRefs: List<String> = emptyList(),
    val publicationRefs: List<PublicationRef> = emptyList(),
    val methodPrinciple: String? = null,
)
