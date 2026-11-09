package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.PracticePresentState
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import com.bettertalker.app.ui.present.PresentCue
import com.bettertalker.app.ui.present.PresentReadiness
import com.bettertalker.app.ui.present.decodePresentSession
import com.bettertalker.app.ui.present.presentReadinessLabel
import com.bettertalker.app.ui.present.presentReadinessOf
import com.bettertalker.app.ui.stage.buildStageContent
import com.bettertalker.app.ui.stage.parseStageScript
import com.bettertalker.app.ui.stage.trainPrepSummary

/**
 * Monta o snapshot de prática a partir do estado real da nota.
 *
 * Reusa as mesmas travessias do Treino (`buildStageContent`) e do Palco
 * (`PresentCue`/`presentReadinessOf`), para o Assistente nunca divergir do
 * que as abas de prática mostram. Puro/testável: sem Room, Compose ou rede.
 */
fun buildPracticeSnapshot(
    noteId: String,
    note: NoteEntity?,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>>,
    trainPrepRaw: String?,
    presentSessionRaw: String?,
): PracticeSnapshot {
    val type = DiscourseType.fromNameOrDefault(note?.discourseType)
    val demo = type == DiscourseType.MINISTRY_PART ||
        type == DiscourseType.FREE_DEMO
    val ordered = sections.sortedBy { it.order }

    val hasTextBySectionId = ordered.associate { section ->
        section.id to buildStageContent(
            discourseType = type,
            sections = listOf(section),
            subPointsBySection = mapOf(section.id to subPointsBySection[section.id].orEmpty()),
        ).isNotBlank()
    }

    val cues = ordered.mapIndexed { index, section ->
        PresentCue(
            sectionId = section.id,
            index = index,
            title = section.title.ifBlank { "Seção ${index + 1}" },
            minutes = section.minutes,
            capsule = section.tribunaJson?.let { tribunaCapsuleFromJson(it) },
            script = if (demo) parseStageScript(section.contentHtml) else emptyList(),
        )
    }
    val readiness = presentReadinessOf(cues, demo)
    val saved = decodePresentSession(presentSessionRaw, cues.size)

    return PracticeSnapshot(
        noteId = noteId,
        hasTrainingText = hasTextBySectionId.values.any { it },
        hasTextBySectionId = hasTextBySectionId,
        trainPrepSummary = trainPrepSummary(trainPrepRaw),
        presentState = when (readiness) {
            PresentReadiness.READY -> PracticePresentState.READY
            PresentReadiness.PARTIAL -> PracticePresentState.PARTIAL
            PresentReadiness.MISSING -> PracticePresentState.MISSING
        },
        presentLabel = presentReadinessLabel(readiness, demo),
        savedSectionIndex = saved?.index,
        savedSectionTitle = saved?.let { cues.getOrNull(it.index)?.title },
        savedElapsedSec = saved?.elapsedSec,
    )
}
