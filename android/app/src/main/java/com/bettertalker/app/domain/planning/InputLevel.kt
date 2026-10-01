package com.bettertalker.app.domain.planning

/**
 * Nível de entrada fornecido pelo usuário para o planejamento estrutural (Fase 1).
 *
 * Do mais estruturado ao menos estruturado:
 * - [FULL_OUTLINE]: esboço S-34-T completo (título, seções com tempo, referências).
 * - [PARTIAL_OUTLINE]: título + algumas referências, sem estrutura completa.
 * - [THEME_WITH_TEXTS]: tema + 1-3 versículos, sem estrutura de seções.
 * - [THEME_ONLY]: frase solta ou tema sem referências (fallback, inclui vazio).
 */
enum class InputLevel {
    FULL_OUTLINE,
    PARTIAL_OUTLINE,
    THEME_WITH_TEXTS,
    THEME_ONLY,
}
