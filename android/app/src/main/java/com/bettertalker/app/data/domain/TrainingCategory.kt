package com.bettertalker.app.data.domain

/**
 * Taxonomia de treinamento BE/TH — Fase 7 (web) reproduzida no nativo (Fase 8).
 * COMO dizer. Serial estável em minúsculas ([serial]); nunca usar name para
 * lógica persistida. Desconhecido cai em [UNKNOWN] (nunca inventar categoria).
 */
enum class TrainingCategory(val serial: String) {
    INTRODUCTION("introduction"),
    DEVELOPMENT("development"),
    EXPLANATION("explanation"),
    ILLUSTRATION("illustration"),
    APPLICATION("application"),
    TRANSITION("transition"),
    CONCLUSION("conclusion"),
    QUESTIONS("questions"),
    CLARITY("clarity"),
    NATURALNESS("naturalness"),
    DELIVERY("delivery"),
    UNKNOWN("unknown");

    companion object {
        fun fromSerial(serial: String?): TrainingCategory =
            values().firstOrNull { it.serial == serial?.lowercase()?.trim() } ?: UNKNOWN
    }
}
