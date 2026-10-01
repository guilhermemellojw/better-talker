package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Estado de uma seção no editor: modelo + sub-pontos + flags de UI.
 *
 * @param section modelo de domínio (título/contentHtml/minutos editáveis)
 * @param subPoints sub-pontos ordenados por `order` (vazio em INTRO/CONCLUSION)
 * @param isSaving true enquanto o autosave desta seção está pendente
 * @param isDirty true quando há edição local ainda não confirmada no DB
 */
data class SectionUiState(
    val section: SpeechSection,
    val subPoints: List<SubPoint>,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
)
