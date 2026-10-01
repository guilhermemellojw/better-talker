package com.bettertalker.app.domain.planning

/**
 * Proposta de esboço produzida pelo planejamento estrutural (Fase 1).
 *
 * O [id] é gerado pelo caller; o validator não o gera nem valida.
 *
 * @param id identificador gerado pelo caller.
 * @param angle ângulo de abordagem do esboço.
 * @param audience público-alvo.
 * @param title título do discurso.
 * @param summary resumo do discurso.
 * @param totalMinutes duração total alvo em minutos.
 * @param sections seções planejadas.
 */
data class OutlineProposal(
    val id: String,
    val angle: OutlineAngle,
    val audience: Audience,
    val title: String,
    val summary: String,
    val totalMinutes: Int,
    val sections: List<SectionPlan>,
)
