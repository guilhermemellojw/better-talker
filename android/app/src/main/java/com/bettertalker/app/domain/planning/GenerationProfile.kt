package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.DiscourseType

/**
 * Fase A — persona de geração.
 *
 * O provider escolhe o system prompt pelo profile. `CHAT` preserva o
 * comportamento histórico (persona de chat); os demais são exclusivos de
 * geração e NÃO recebem a cauda de chat (markdown, marcadores, etc.).
 *
 * Puro/testável.
 */
enum class GenerationProfile {
    /** Chat livre — usa a persona histórica do chat. */
    CHAT,

    /** Discursos (Tesouros, Nossa Vida, parte 6 "Discurso", S-34). */
    SPEECH_MENTOR,

    /** Demonstrações V&M (diálogo Publicador/Morador). */
    DEMO_SCRIPTWRITER,

    /** Apenas o esqueleto JSON (outline), sem texto final. */
    STRUCTURE_PLANNER;

    companion object {
        /**
         * Profile de um discurso persistido. `MINISTRY_PART` é a demonstração;
         * todo o resto (incluindo `MINISTRY_TALK`) é discurso.
         */
        fun forDiscourseType(type: String?): GenerationProfile =
            if (type == DiscourseType.MINISTRY_PART.name) DEMO_SCRIPTWRITER
            else SPEECH_MENTOR
    }
}
