package com.bettertalker.app.data.domain

/**
 * Trilho da fonte — O QUE dizer (CONTENT/BIBLE) vs COMO dizer (TRAINING).
 * Persistido como [serial] minúsculo. Regra inegociável: TRAINING nunca é
 * evidência factual.
 */
enum class SourceType(val serial: String) {
    CONTENT("content"),
    TRAINING("training"),
    BIBLE("bible");

    companion object {
        /** Deriva do slot base legado (be|th -> training, nwt -> bible). */
        fun fromBaseSlot(baseSlot: String?): SourceType = when (baseSlot) {
            "be", "th" -> TRAINING
            "nwt" -> BIBLE
            else -> CONTENT
        }

        fun fromSerial(serial: String?): SourceType =
            values().firstOrNull { it.serial == serial?.lowercase()?.trim() } ?: CONTENT
    }
}
