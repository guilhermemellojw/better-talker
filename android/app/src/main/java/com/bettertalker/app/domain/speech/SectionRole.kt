package com.bettertalker.app.domain.speech

/**
 * Papel estrutural de uma seção de discurso.
 *
 * - [INTRO]: abertura. Gancho, apresentação do tema, transição para o corpo.
 * - [BODY]: desenvolvimento. Uma seção do esboço (S-34-T, CO-tk26, etc.).
 * - [CONCLUSION]: fechamento. Recapitulação, aplicação, chamada final.
 *
 * Invariantes (ver [SpeechSectionValidator]):
 * - No máximo 1 [INTRO]
 * - No máximo 1 [CONCLUSION]
 * - Pelo menos 1 [BODY]
 * - [INTRO] precede todos os [BODY]; [CONCLUSION] sucede todos os [BODY]
 */
enum class SectionRole {
    INTRO,
    BODY,
    CONCLUSION,
}
