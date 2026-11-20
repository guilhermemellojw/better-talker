package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.domain.speech.SpeechSection

/**
 * 1C.2.2A — snapshot canônico de uma seção para reconciliação de decisões.
 *
 * Reutiliza exatamente o mesmo bloco/hash do gate de stale do aceite B1
 * (`título + contentHtml`). O hash é a única parte que compara conteúdo;
 * título, papel e ordem são copiados como metadados.
 */
fun SpeechSection.toSectionSnapshot(): SectionSnapshot = SectionSnapshot(
    id = id,
    noteId = noteId,
    role = role,
    title = title.ifBlank { "Seção" },
    order = order,
    contentHash = hashText(
        if (contentHtml.isBlank()) title else "$title\n\n$contentHtml",
    ),
)
