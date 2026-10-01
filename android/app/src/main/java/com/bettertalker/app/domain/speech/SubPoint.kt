package com.bettertalker.app.domain.speech

import com.bettertalker.app.domain.planning.PublicationRef

/**
 * Sub-ponto de uma seção BODY. Cada sub-ponto corresponde a uma afirmação do
 * esboço (S-34-T, CO-tk26, etc.) com suas refs próprias.
 *
 * O desenvolvimento (`developedHtml`) é o texto corrido que o usuário escreve
 * ou que o Copilot gera. Inicia vazio.
 *
 * O Copilot opera por sub-ponto — [SpeechSection] BODY nunca é desenvolvida
 * como um bloco único, mas ponto-a-ponto.
 *
 * Convenções (ver [SubPointValidator]):
 * - BODY usa sub-pontos; INTRO/CONCLUSION usam `contentHtml` da seção.
 * - `outlineText` é a âncora — sempre presente.
 * - Refs vivem no sub-ponto (a união na seção BODY é derivável pelo caller).
 *
 * @param id identificador único (UUID gerado pelo caller)
 * @param sectionId id da seção BODY pai
 * @param order posição dentro da seção (0-based, contíguo)
 * @param outlineText texto do sub-ponto como veio do esboço
 * @param bibleRefs refs bíblicas do sub-ponto (ex: ["Rm 5:12"])
 * @param publicationRefs refs de publicações do sub-ponto
 * @param instruction instrução do esboço, se houver (ex: "[Leia Rm 5:12.]")
 * @param developedHtml texto desenvolvido (vazio até ser preenchido)
 * @param createdAt timestamp de criação
 * @param updatedAt timestamp da última edição
 *
 * DÉBITO (3.2.5c): hierarquia interna de categorias não é representada.
 * Esboços como o S-34-T N.º 194 têm sub-pontos agrupados por categoria
 * ("Quando enfrentamos dificuldades econômicas:" engloba 3 bullets).
 * Hoje todos os sub-pontos ficam nivelados. Requer campo
 * `isCategory: Boolean` no futuro (roadmap, provavelmente junto com o
 * parser da apostila).
 */
data class SubPoint(
    val id: String,
    val sectionId: String,
    val order: Int,
    val outlineText: String,
    val bibleRefs: List<String>,
    val publicationRefs: List<PublicationRef>,
    val instruction: String?,
    val developedHtml: String,
    val createdAt: Long,
    val updatedAt: Long,
)
