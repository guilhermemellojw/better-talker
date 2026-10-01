package com.bettertalker.app.domain.speech

import com.bettertalker.app.domain.planning.PublicationRef

/**
 * Seção editável de um discurso. Modelo de domínio (fora do Room).
 *
 * Convenção de [order]: 0-based, contíguo (primeira seção = 0).
 *
 * @param id identificador único (UUID gerado pelo caller)
 * @param noteId id da nota pai
 * @param order posição na nota (0-based, contíguo)
 * @param role papel estrutural (INTRO/BODY/CONCLUSION)
 * @param title título da seção
 * @param minutes duração alvo em minutos
 * @param contentHtml conteúdo rico (HTML do richeditor)
 * @param bibleRefs referências bíblicas explícitas (ex: ["Mt 6:28", "Mt 6:25"])
 * @param publicationRefs referências a publicações
 * @param methodPrinciple princípio de oratória (be/th), se houver
 * @param createdAt timestamp de criação
 * @param updatedAt timestamp da última edição
 */
data class SpeechSection(
    val id: String,
    val noteId: String,
    val order: Int,
    val role: SectionRole,
    val title: String,
    val minutes: Int,
    val contentHtml: String,
    val bibleRefs: List<String>,
    val publicationRefs: List<PublicationRef>,
    val methodPrinciple: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
