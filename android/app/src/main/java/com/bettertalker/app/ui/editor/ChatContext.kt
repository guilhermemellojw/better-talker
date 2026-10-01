package com.bettertalker.app.ui.editor

/**
 * Contexto empurrado do editor para o chat no clique do FAB.
 * Volátil (vive nos VMs, não persistido). F2a do onboarding contextual.
 */
data class ChatContext(
    val sectionId: String?,
    val subPointId: String?,
    val readiness: NoteReadiness,
)
