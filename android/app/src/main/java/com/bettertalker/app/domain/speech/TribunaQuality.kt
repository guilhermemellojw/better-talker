package com.bettertalker.app.domain.speech

/** Qualidade do mapa de lembretes de uma seção. Puro/testável. */
enum class TribunaMapStatus { COMPLETE, INCOMPLETE, LEGACY, MISSING }

data class TribunaMapQuality(
    val status: TribunaMapStatus,
    /** Motivos exibidos no badge quando incompleto. */
    val reasons: List<String> = emptyList(),
    /** Avisos que não bloqueiam completude (ex.: keyTerms vazio). */
    val warnings: List<String> = emptyList(),
) {
    val complete: Boolean get() = status == TribunaMapStatus.COMPLETE
}

/**
 * Avalia o mapa de uma seção. `sectionHasRefs` indica se a seção tem refs
 * bíblicas/publicações; `isLastSection` libera a exigência de transição.
 */
fun tribunaMapQuality(
    capsule: TribunaCapsule?,
    sectionHasRefs: Boolean,
    isLastSection: Boolean,
): TribunaMapQuality {
    if (capsule == null) return TribunaMapQuality(TribunaMapStatus.MISSING)
    if (capsule.cues.isEmpty() && capsule.cueSheetVersion < 2) {
        return TribunaMapQuality(TribunaMapStatus.LEGACY)
    }
    val reasons = mutableListOf<String>()
    if (capsule.anchorPhrase.isBlank()) reasons += "sem âncora"
    if (capsule.cues.isEmpty()) reasons += "sem lembretes"
    if (capsule.cues.any { it.text.isBlank() }) reasons += "lembrete vazio"
    val hasTransition = capsule.transition?.isNotBlank() == true ||
        capsule.cues.any { it.kind == TribunaCueKind.TRANSITION }
    if (!isLastSection && !hasTransition) reasons += "sem transição"
    val hasCueRefs = capsule.cues.any { cue -> cue.refs.isNotEmpty() }
    if (sectionHasRefs && !hasCueRefs) reasons += "sem refs"
    if (capsule.cues.isNotEmpty() && capsule.cues.all { it.kind == TribunaCueKind.DELIVERY }) {
        reasons += "só entrega"
    }
    val warnings = if (capsule.keyTerms.isEmpty()) listOf("sem termos-chave") else emptyList()
    return if (reasons.isEmpty()) {
        TribunaMapQuality(TribunaMapStatus.COMPLETE, warnings = warnings)
    } else {
        TribunaMapQuality(TribunaMapStatus.INCOMPLETE, reasons, warnings)
    }
}
