package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import java.text.Normalizer
import java.util.Locale

/** Seção da nota no formato mínimo necessário para resolver o alvo. */
data class NoteSectionRef(
    val id: String,
    val order: Int,
    val role: SectionRole,
    val title: String,
)

/** Seção do S-34 no formato mínimo (id estável `sec-N`, ordem 1-based). */
data class S34SectionRef(
    val id: String,
    val order: Int,
    val title: String,
)

/**
 * Resolve a seção da NOTA que corresponde ao alvo pedido pelo S-34/roteador.
 *
 * O S-34 usa ids próprios (`sec-1`, `sec-2`, …) e a nota usa UUIDs. Sem esta
 * tradução, `generateOratory` caía no foco antigo (ex.: ponto 1) e o card
 * aplicava o texto do ponto 2 no ponto 1.
 *
 * Regras:
 * - INTRO/CONCLUSION: resolve pelo papel da seção na nota.
 * - DEVELOPMENT/TRANSITION:
 *   1. id direto (quando já é o UUID da nota);
 *   2. acha a seção S-34 pelo id `sec-N`;
 *   3. casa pelo título normalizado entre os BODY da nota;
 *   4. fallback pela ordem do ponto (`order - 1` na lista de BODY);
 *   5. sem match → null (nunca cai no foco antigo).
 *
 * Puro/testável: sem Room, Compose ou LLM.
 */
fun resolveOratoryNoteSection(
    sectionId: String,
    mode: OratoryGeneration.Mode,
    noteSections: List<NoteSectionRef>,
    s34Sections: List<S34SectionRef> = emptyList(),
): NoteSectionRef? {
    when (mode) {
        OratoryGeneration.Mode.INTRODUCTION ->
            return noteSections.firstOrNull { it.role == SectionRole.INTRO }
        OratoryGeneration.Mode.CONCLUSION ->
            return noteSections.firstOrNull { it.role == SectionRole.CONCLUSION }
        OratoryGeneration.Mode.DEVELOPMENT,
        OratoryGeneration.Mode.TRANSITION -> Unit
    }

    // 1. Já é o id da nota (UUID ou id legado persistido).
    noteSections.firstOrNull { it.id == sectionId }?.let { return it }

    // 2. Id do S-34: traduz por título e, se o usuário editou o título, por
    //    posição entre os BODY (o S-34 não tem intro/conclusão sintéticas).
    val s34 = s34Sections.firstOrNull { it.id == sectionId } ?: return null
    val bodies = noteSections
        .filter { it.role == SectionRole.BODY }
        .sortedBy { it.order }
    val wanted = normalizeSectionTitle(s34.title)
    bodies.firstOrNull { normalizeSectionTitle(it.title) == wanted }?.let { return it }
    return bodies.getOrNull(s34.order - 1)
}

/**
 * Detecta um pedido EXPLÍCITO por número de ponto no texto do usuário.
 *
 * Exige o substantivo (`ponto`/`tópico`/`seção`) junto do número: “2 minutos”
 * e “tenho 2” não mudam o foco. Puro/testável.
 */
fun explicitSectionNumber(text: String): Int? {
    val normalized = normalizeSectionTitle(text)
    val match = Regex("\\b(?:ponto|topico|secao)\\s+(\\d{1,2})\\b")
        .find(normalized) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Normalização de título para casar S-34 ↔ nota (sem acento/pontuação). */
internal fun normalizeSectionTitle(value: String): String {
    val decomposed = Normalizer.normalize(
        value.lowercase(Locale.forLanguageTag("pt-BR")),
        Normalizer.Form.NFD,
    )
    return decomposed
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
