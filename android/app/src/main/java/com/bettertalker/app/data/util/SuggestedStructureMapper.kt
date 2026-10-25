package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.SuggestedStructure

/**
 * Converte a estrutura sugerida em [PastedNote] pronta para
 * `NotesRepository.createPastedNote`. Puro/testável.
 *
 * Seções nascem com `contentHtml` vazio: o Copilot orienta/desenvolve depois.
 */
fun SuggestedStructure.toPastedNote(title: String): PastedNote = PastedNote(
    title = title.trim(),
    discourseType = type,
    plainText = "",
    sections = sections.map { s ->
        PastedSection(
            role = s.role,
            title = s.title,
            minutes = s.minutes,
            contentHtml = "",
        )
    },
)
