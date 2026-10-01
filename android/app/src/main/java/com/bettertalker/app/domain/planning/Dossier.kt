package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Dossiê de uma seção/sub-ponto ativo. Montado pelo DossierBuilder
 * a partir do contexto de seleção + todas as seções do discurso.
 *
 * REGRA ANTI-ALUCINAÇÃO: só contém dados do acervo local. NUNCA conteúdo
 * desenvolvido de outras seções (só metadados em [overview]).
 */
data class Dossier(
    /** Seção ativa (BODY/INTRO/CONCLUSION). */
    val currentSection: SpeechSection,
    /** Sub-ponto ativo (null para INTRO/CONCLUSION ou cursor no header). */
    val currentSubPoint: SubPoint?,
    /** Texto selecionado no editor (null se vazio). */
    val selectedText: String?,
    /** HTML do editor ativo (contexto). */
    val fullContentHtml: String,
    /** Metadados de TODAS as seções (sem conteúdo — anti-mistura). */
    val overview: List<SectionMeta>,
    /** Refs bíblicas resolvidas (texto literal + status). */
    val bibleTexts: List<ResolvedBibleText>,
    /** Refs de publicações resolvidas (texto literal + status). */
    val publicationTexts: List<ResolvedPublicationText>,
    /** Princípios de oratória (be/th) em texto puro. */
    val methodPrinciples: List<String>,
    /** Refs não encontradas no acervo (UNRESOLVED/MISSING_CORPUS). */
    val unresolvedRefs: List<String>,
    /**
     * Texto de transição:
     * - INTRO: primeiros sub-pontos desenvolvidos do primeiro BODY
     * - CONCLUSION: últimos sub-pontos desenvolvidos do último BODY
     * - BODY: null (não se aplica)
     *
     * Null também quando não há sub-pontos desenvolvidos no BODY alvo.
     */
    val transitionContext: String?,
)

/** Metadados de uma seção para o overview. Nunca inclui conteúdo. */
data class SectionMeta(
    val id: String,
    val title: String,
    val role: SectionRole,
    val order: Int,
    val minutes: Int,
)

/** Ref bíblica resolvida. */
data class ResolvedBibleText(
    val ref: String,
    val text: String?,
    val status: ReferenceStatus,
)

/** Ref de publicação resolvida. */
data class ResolvedPublicationText(
    val ref: PublicationRef,
    val text: String?,
    val status: ReferenceStatus,
)
