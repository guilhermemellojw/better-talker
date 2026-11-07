package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection

/**
 * Fatia 4 — navegação/mentoria do assistente.
 *
 * O roteador devolve uma [ChatNavAction] pura (sem banco/UI); o
 * `CopilotViewModel` resolve contra as seções reais da nota e a tela executa.
 * Nada é editado sem aceite: comandos explícitos navegam; sugestões exigem
 * um toque de confirmação.
 */
sealed interface ChatNavAction {
    /**
     * Seção da nota.
     *
     * - [role] != null → introdução/conclusão (o número é ignorado).
     * - [role] == null → pelo número. [byTopic] = true conta todas as seções
     *   ("Tópico 2"); false conta só os pontos de desenvolvimento ("Ponto 2").
     */
    data class Section(
        val number: Int? = null,
        val role: SectionRole? = null,
        val byTopic: Boolean = false,
    ) : ChatNavAction

    data object Sources : ChatNavAction
    data object Train : ChatNavAction
    data object Present : ChatNavAction
    data object NextStep : ChatNavAction
}

/** Ação já resolvida contra as seções reais da nota (pronta para a UI). */
sealed interface ResolvedChatNav {
    data class OpenSection(
        val sectionId: String,
        val title: String,
        val sectionNumber: Int,
    ) : ResolvedChatNav

    /** O tópico em foco ainda está vazio: o próximo passo é desenvolvê-lo. */
    data class DevelopCurrent(
        val sectionId: String,
        val title: String,
    ) : ResolvedChatNav

    data object StartTrain : ResolvedChatNav
    data object OpenPresent : ResolvedChatNav
}

/** Sugestão de próximo passo — só executa depois do toque do usuário. */
data class NextStepSuggestion(
    val label: String,
    val detail: String,
    val action: ResolvedChatNav,
)

/**
 * Próximo passo de mentoria a partir do estado real da nota.
 *
 * Prioridade:
 * 1. primeiro trecho sem texto (introdução, pontos ou conclusão);
 * 2. se o trecho sem texto é o que está em foco, sugerir desenvolvê-lo;
 * 3. com todos os trechos preenchidos, sugerir o Treino.
 *
 * Puro/testável: sem Room, LLM ou Compose.
 */
fun nextStepSuggestion(
    sections: List<SpeechSection>,
    focusedSectionId: String?,
): NextStepSuggestion? {
    if (sections.isEmpty()) return null
    val ordered = sections.sortedBy { it.order }
    val body = ordered.filter { it.role == SectionRole.BODY }
    val blank = ordered.firstOrNull { !hasVisibleText(it.contentHtml) }

    if (blank == null) {
        return NextStepSuggestion(
            label = "Iniciar Treino",
            detail = "Todos os trechos têm texto. Hora de treinar a apresentação.",
            action = ResolvedChatNav.StartTrain,
        )
    }

    val title = sectionShortTitle(blank, body)
    if (blank.id == focusedSectionId) {
        return NextStepSuggestion(
            label = "Desenvolver $title",
            detail = "$title ainda está sem texto.",
            action = ResolvedChatNav.DevelopCurrent(blank.id, blank.title),
        )
    }

    return NextStepSuggestion(
        label = "Abrir $title",
        detail = "$title ainda está sem texto.",
        action = ResolvedChatNav.OpenSection(
            sectionId = blank.id,
            title = blank.title,
            sectionNumber = ordered.indexOf(blank) + 1,
        ),
    )
}

/** Rótulo curto e estável para a sugestão ("Introdução", "Ponto 2", …). */
internal fun sectionShortTitle(section: SpeechSection, body: List<SpeechSection>): String =
    when (section.role) {
        SectionRole.INTRO -> "Introdução"
        SectionRole.CONCLUSION -> "Conclusão"
        SectionRole.BODY -> {
            val n = body.indexOfFirst { it.id == section.id }
            if (n >= 0) "Ponto ${n + 1}" else section.title.ifBlank { "o próximo ponto" }
        }
    }

/** HTML vazio (inclusive `<p><br></p>`) não conta como texto. */
internal fun hasVisibleText(html: String): Boolean =
    html.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .isNotBlank()
