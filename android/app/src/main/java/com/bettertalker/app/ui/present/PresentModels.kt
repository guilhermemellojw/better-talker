package com.bettertalker.app.ui.present

import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.StageTurnKind

/**
 * Apresentar live — modelos puros do pré-voo e do palco (spec
 * `docs/specs/apresentar-live.md`). Discurso lê a Tribuna; demonstração lê
 * os turnos do roteiro. Aqui só se lê o que já foi gerado.
 */

/** Prontidão da Tribuna (discurso) ou do roteiro (demonstração). */
enum class PresentReadiness { READY, PARTIAL, MISSING }

fun presentReadinessLabel(readiness: PresentReadiness, demo: Boolean = false): String =
    if (demo) when (readiness) {
        PresentReadiness.READY -> "Roteiro pronto"
        PresentReadiness.PARTIAL -> "Roteiro incompleto"
        PresentReadiness.MISSING -> "Falta o roteiro"
    } else when (readiness) {
        PresentReadiness.READY -> "Tribuna pronta"
        PresentReadiness.PARTIAL -> "Tribuna incompleta"
        PresentReadiness.MISSING -> "Falta gerar a Tribuna"
    }

/** Ação/ações do cartão do Palco (primária + terciária). */
enum class PresentAction { PRESENT, OPEN_TRIBUNA, OPEN_SCRIPT, GENERATE_TRIBUNA, OPEN_EDITOR }

data class PresentCardActions(
    val primary: PresentAction,
    val tertiary: PresentAction? = null,
)

/**
 * Uma ação por estado, sem beco sem saída na demonstração: o roteiro nasce
 * dos turnos do editor, então MISSING/PARTIAL nunca mandam gerar Tribuna.
 */
fun presentCardActions(readiness: PresentReadiness, demo: Boolean): PresentCardActions = when {
    readiness == PresentReadiness.READY -> PresentCardActions(
        primary = PresentAction.PRESENT,
        tertiary = if (demo) null else PresentAction.OPEN_TRIBUNA,
    )
    demo && readiness == PresentReadiness.PARTIAL -> PresentCardActions(
        primary = PresentAction.OPEN_SCRIPT,
        tertiary = PresentAction.OPEN_EDITOR,
    )
    demo -> PresentCardActions(primary = PresentAction.OPEN_EDITOR)
    readiness == PresentReadiness.PARTIAL -> PresentCardActions(
        primary = PresentAction.OPEN_TRIBUNA,
    )
    else -> PresentCardActions(primary = PresentAction.GENERATE_TRIBUNA)
}

fun presentActionLabel(action: PresentAction): String = when (action) {
    PresentAction.PRESENT -> "Apresentar"
    PresentAction.OPEN_TRIBUNA -> "Abrir Tribuna"
    PresentAction.OPEN_SCRIPT -> "Abrir Roteiro"
    PresentAction.GENERATE_TRIBUNA -> "Gerar Tribuna"
    PresentAction.OPEN_EDITOR -> "Abrir editor"
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

/** Seção pronta para o palco: cápsula nos discursos; roteiro/texto na demo. */
fun presentCueReady(cue: PresentCue, demo: Boolean): Boolean =
    if (demo) cue.allScript.isNotEmpty() else cue.capsule != null

fun presentReadinessOf(cues: List<PresentCue>, demo: Boolean): PresentReadiness =
    presentReadiness(cues.size, cues.count { presentCueReady(it, demo) })

/** Uma seção do palco (uma por vez). */
data class PresentCue(
    val sectionId: String,
    val index: Int,
    val title: String,
    val minutes: Int,
    val capsule: TribunaCapsule?,
    /** Turnos falados (compatibilidade com o Treino/TTS). */
    val turns: List<StageTurn> = emptyList(),
    /** v5.3: roteiro completo da seção (falas + narração). */
    val script: List<StageTurn> = emptyList(),
    /** Imagens vinculadas à seção (strip/pré-voo; nunca no HTML). */
    val sectionImages: List<NoteImageEntity> = emptyList(),
    /** v5.3: todas as imagens da nota; a cue pode referenciar qualquer uma. */
    val noteImages: List<NoteImageEntity> = emptyList(),
) {
    /** v5.3: usa o roteiro novo ou os turnos antigos, sem perder a demo. */
    val allScript: List<StageTurn>
        get() = script.ifEmpty { turns }

    /**
     * v5.3: imagens da seção primeiro; depois as demais da nota, sem duplicar.
     * O `imageId` da cue é resolvido nesta lista completa, nunca em um órfão.
     */
    val images: List<NoteImageEntity>
        get() = (sectionImages + noteImages).distinctBy { it.id }

    /** v4.2: meta de tempo da seção (cues mandam; fallback = minutos). */
    val targetMinutes: Int
        get() = capsule?.timeMinutes?.takeIf { it > 0 } ?: minutes
}

/** Turno achatado do roteiro para o modo demo. */
data class PresentScriptItem(
    val cueIndex: Int,
    /** Índice do turno dentro da seção (para destacar no roteiro completo). */
    val turnIndex: Int,
    val turn: StageTurn,
)

fun presentScriptItems(cues: List<PresentCue>): List<PresentScriptItem> =
    cues.flatMapIndexed { index, cue ->
        cue.allScript.mapIndexed { turnIndex, turn ->
            PresentScriptItem(cueIndex = index, turnIndex = turnIndex, turn = turn)
        }
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
    cues.flatMap { cue ->
        cue.allScript
            .filter { it.kind == StageTurnKind.SPEECH }
            .map { it.speaker }
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

/** Preparação escolhida no pré-voo (não persistida no MVP). */
data class PresentPrep(
    val mode: PresentMode = PresentMode.CUES,
    val startIndex: Int = 0,
    val timerVisible: Boolean = true,
    val orientation: PresentOrientation = PresentOrientation.AUTO,
    val roleIndex: Int = 0,
    /** Retoma seção/tempo salvos ao sair do palco. */
    val continueSaved: Boolean = false,
    /** v5.3: no roteiro, mostra só o turno atual (teleprompter) em vez do roteiro completo. */
    val scriptFocusOnly: Boolean = false,
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

/**
 * v5.1: imagem de uma cue no Palco. Só resolve pelo `imageId` atribuído;
 * cue IMAGE sem imagem explícita não herda a primeira imagem da seção.
 * v5.3: `images` deve conter todas as imagens da nota (seção primeiro).
 */
fun presentCueImage(cue: TribunaCue, images: List<NoteImageEntity>): NoteImageEntity? {
    if (cue.kind != TribunaCueKind.IMAGE) return null
    return cue.imageId?.let { id -> images.firstOrNull { it.id == id } }
}

/**
 * v5.3: resumo do pré-voo das cues de imagem. `fileExists` é injetado para
 * manter o cálculo puro/testável; a produção passa `File(path).exists()`.
 */
data class PresentImageCheck(
    val imageCues: Int = 0,
    val withCaption: Int = 0,
    val resolved: Int = 0,
    val missing: List<String> = emptyList(),
) {
    val allResolved: Boolean get() = missing.isEmpty()
    val isComplete: Boolean get() = imageCues == 0 || (allResolved && withCaption == imageCues)
}

fun presentImageCheck(
    cues: List<PresentCue>,
    fileExists: (String) -> Boolean = { true },
): PresentImageCheck {
    var imageCues = 0
    var withCaption = 0
    var resolved = 0
    val missing = mutableListOf<String>()
    cues.forEach { cue ->
        cue.capsule?.cues.orEmpty().forEach { tribunaCue ->
            if (tribunaCue.kind == TribunaCueKind.IMAGE) {
                imageCues++
                val image = presentCueImage(tribunaCue, cue.images)
                val caption = tribunaCue.imageCaption
                    ?.takeIf { it.isNotBlank() }
                    ?: image?.caption.orEmpty()
                if (caption.isNotBlank()) withCaption++
                if (image != null && fileExists(image.path)) {
                    resolved++
                } else {
                    missing += caption.ifBlank { "Seção ${cue.index + 1}" }
                }
            }
        }
    }
    return PresentImageCheck(
        imageCues = imageCues,
        withCaption = withCaption,
        resolved = resolved,
        missing = missing,
    )
}
