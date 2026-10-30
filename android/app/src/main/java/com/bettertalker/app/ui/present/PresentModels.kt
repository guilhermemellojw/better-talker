package com.bettertalker.app.ui.present

import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.ui.stage.StageTurn

/**
 * Apresentar live — modelos puros do pré-voo e do palco (spec
 * `docs/specs/apresentar-live.md`). A Tribuna continua sendo a preparação;
 * aqui só se lê o que já foi gerado.
 */

/** Prontidão da Tribuna para o palco. */
enum class PresentReadiness { READY, PARTIAL, MISSING }

fun presentReadinessLabel(readiness: PresentReadiness): String = when (readiness) {
    PresentReadiness.READY -> "Tribuna pronta"
    PresentReadiness.PARTIAL -> "Tribuna incompleta"
    PresentReadiness.MISSING -> "Falta gerar Tribuna"
}

/** `readySections` conta seções com cápsula (ou roteiro, em demos). Puro. */
fun presentReadiness(totalSections: Int, readySections: Int): PresentReadiness = when {
    totalSections <= 0 -> PresentReadiness.MISSING
    readySections >= totalSections -> PresentReadiness.READY
    readySections > 0 -> PresentReadiness.PARTIAL
    else -> PresentReadiness.MISSING
}

enum class PresentMode { CUES, SCRIPT }

enum class PresentOrientation { PORTRAIT, AUTO, LOCK_CURRENT }

fun presentOrientationLabel(orientation: PresentOrientation): String = when (orientation) {
    PresentOrientation.PORTRAIT -> "Retrato"
    PresentOrientation.AUTO -> "Automática"
    PresentOrientation.LOCK_CURRENT -> "Travar"
}

/** Seção pronta para o palco: cápsula nos discursos; turnos na demo. */
fun presentCueReady(cue: PresentCue, demo: Boolean): Boolean =
    if (demo) cue.turns.isNotEmpty() else cue.capsule != null

fun presentReadinessOf(cues: List<PresentCue>, demo: Boolean): PresentReadiness =
    presentReadiness(cues.size, cues.count { presentCueReady(it, demo) })

/** Uma seção do palco (uma por vez). */
data class PresentCue(
    val sectionId: String,
    val index: Int,
    val title: String,
    val minutes: Int,
    val capsule: TribunaCapsule?,
    val turns: List<StageTurn> = emptyList(),
)

/** Turno achatado do roteiro para o modo demo. */
data class PresentScriptItem(val cueIndex: Int, val turn: StageTurn)

fun presentScriptItems(cues: List<PresentCue>): List<PresentScriptItem> =
    cues.flatMapIndexed { index, cue ->
        cue.turns.map { PresentScriptItem(cueIndex = index, turn = it) }
    }

/** Primeiro turno de uma seção (ou o último item se a seção não tiver turnos). */
fun presentScriptStartIndex(
    items: List<PresentScriptItem>,
    sectionIndex: Int,
): Int {
    if (items.isEmpty()) return 0
    val found = items.indexOfFirst { it.cueIndex >= sectionIndex }
    return if (found >= 0) found else items.lastIndex
}

fun presentSpeakers(cues: List<PresentCue>): List<String> =
    cues.flatMap { cue -> cue.turns.map { it.speaker } }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

/** Preparação escolhida no pré-voo (não persistida no MVP). */
data class PresentPrep(
    val mode: PresentMode = PresentMode.CUES,
    val startIndex: Int = 0,
    val timerVisible: Boolean = true,
    val orientation: PresentOrientation = PresentOrientation.PORTRAIT,
    val roleIndex: Int = 0,
    /** Retoma seção/tempo salvos ao sair do palco. */
    val continueSaved: Boolean = false,
)

/** Sessão salva para "Continuar de onde parei". */
data class PresentSavedSession(
    val index: Int,
    val elapsedSec: Long,
    val updatedAt: Long,
)

fun encodePresentSession(session: PresentSavedSession): String =
    "${session.index}|${session.elapsedSec}|${session.updatedAt}"

fun decodePresentSession(raw: String?, totalSections: Int): PresentSavedSession? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|')
    if (parts.size < 3) return null
    val index = parts[0].toIntOrNull() ?: return null
    val elapsed = parts[1].toLongOrNull() ?: return null
    val updated = parts[2].toLongOrNull() ?: return null
    if (totalSections <= 0) return null
    return PresentSavedSession(
        index = index.coerceIn(0, totalSections - 1),
        elapsedSec = elapsed.coerceAtLeast(0L),
        updatedAt = updated,
    )
}

/** Bateria baixa só vira aviso quando não está carregando. Puro. */
fun presentBatteryLow(levelPercent: Int, charging: Boolean): Boolean =
    !charging && levelPercent in 0..20
