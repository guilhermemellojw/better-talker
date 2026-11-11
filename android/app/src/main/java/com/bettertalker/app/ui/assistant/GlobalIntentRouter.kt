package com.bettertalker.app.ui.assistant

import com.bettertalker.app.data.util.STOPWORDS_PT
import com.bettertalker.app.data.util.normalizeText

/** Nota mínima para o roteador global (sem Room/Compose). */
data class GlobalNote(
    val id: String,
    val title: String,
)

/** Ação que o usuário pediu e que pode depender de uma nota. */
enum class GlobalNoteAction { OPEN, TRAIN, PRESENT }

/** Ação pendente aguardando “sim/não” ou a escolha de uma nota. */
sealed interface GlobalPending {
    data class OpenNote(val note: GlobalNote) : GlobalPending
    data class StartTrain(val note: GlobalNote) : GlobalPending
    data class OpenPresent(val note: GlobalNote) : GlobalPending
    data class ChooseNote(
        val candidates: List<GlobalNote>,
        val action: GlobalNoteAction,
    ) : GlobalPending
}

/** Resultado puro do roteador local. O ViewModel transforma em turnos/ações. */
sealed interface GlobalRoute {
    data object ListNotes : GlobalRoute
    data class OpenNote(val note: GlobalNote) : GlobalRoute
    data class AskWhichNote(
        val query: String,
        val candidates: List<GlobalNote>,
        val action: GlobalNoteAction = GlobalNoteAction.OPEN,
    ) : GlobalRoute
    data class NotFound(val recent: List<GlobalNote>) : GlobalRoute
    data object CreateNote : GlobalRoute
    data object OpenLibrary : GlobalRoute
    data object OpenPrepare : GlobalRoute
    data object OpenMyOutlines : GlobalRoute
    data class StartTrain(val note: GlobalNote) : GlobalRoute
    data class OpenPresent(val note: GlobalNote) : GlobalRoute
    data class AskNoteForAction(val action: GlobalNoteAction) : GlobalRoute
    data object ConfigureAi : GlobalRoute
    data object Help : GlobalRoute
    data class Chat(val text: String) : GlobalRoute
    data class Confirm(val pending: GlobalPending) : GlobalRoute
    data object Cancel : GlobalRoute
}

/**
 * Roteador local do assistente global — puro/testável.
 *
 * Comandos locais (listar/abrir/criar/treinar/palco/ajuda/IA) NUNCA chamam o
 * LLM. Títulos são comparados por normalização (acentos/caixa/espaços) e
 * tokens significativos; empate vira [GlobalRoute.AskWhichNote], nunca uma
 * escolha silenciosa.
 */
object GlobalIntentRouter {

    private val YES = setOf("sim", "s", "yes", "claro", "pode", "pode abrir", "confirmo", "isso")
    private val NO = setOf("nao", "n", "no", "cancela", "cancelar", "deixa", "deixa pra la")

    /** Palavras de comando/genéricas que não identificam uma nota. */
    private val COMMAND_WORDS = setOf(
        "abrir", "abre", "abra", "quero", "queria", "gostaria", "mostrar", "mostra",
        "ir", "para", "iniciar", "iniciar", "comecar", "treinar", "treino", "apresentar",
        "apresentacao", "palco", "esboco", "esbocos", "nota", "notas", "discurso",
        "discursos", "sobre", "ponto", "pontos", "parte", "preparar", "usar", "favor",
        "por", "preciso", "ajudar", "voce", "pode", "poderia", "tenho", "tem", "qual",
        "quais", "meu", "minha", "meus", "minhas", "atual", "agora",
    )

    private val LIST_HINTS = listOf(
        "quais esbocos", "listar esbocos", "minhas notas", "quais notas",
        "o que tenho", "esbocos tenho", "quais sao os esbocos",
    )
    private val HELP_HINTS = listOf(
        "ajuda", "o que voce faz", "o que voce pode fazer", "comandos",
    )
    private val CONFIG_AI_HINTS = listOf(
        "configurar ia", "configurar chave", "chave de ia", "chave da ia",
        "modelo ia", "modelo de ia", "api key", "deepseek", "configurar modelo",
    )
    private val CREATE_HINTS = listOf(
        "criar um esboco", "criar esboco", "novo esboco", "criar nota",
        "criar uma nota", "nova nota",
    )
    private val LIBRARY_HINTS = listOf(
        "abrir biblioteca", "ir para biblioteca", "quero biblioteca",
    )
    private val MY_OUTLINES_HINTS = listOf(
        "meus esbocos", "meus esboços", "abrir meus esbocos",
    )
    private val PREPARE_HINTS = listOf(
        "abrir preparo", "ir para preparo", "quero preparar", "preparar",
    )
    private val TRAIN_HINTS = listOf(
        "iniciar treino", "ir para treino", "abrir treino", "comecar treino",
        "treinar", "quero treinar",
    )
    private val PRESENT_HINTS = listOf(
        "apresentar", "iniciar apresentacao", "ir para o palco", "abrir o palco",
        "abrir palco", "quero apresentar",
    )
    private val OPEN_HINTS = listOf(
        "abrir", "abre", "abra", "mostrar", "mostra", "ir para o esboco",
        "abrir o esboco", "abrir a nota", "quero o esboco",
    )

    fun route(
        text: String,
        notes: List<GlobalNote>,
        boundNoteId: String? = null,
        pending: GlobalPending? = null,
    ): GlobalRoute {
        val norm = normalizeText(text)
        if (norm.isBlank()) return GlobalRoute.Chat(text)
        val bound = notes.firstOrNull { it.id == boundNoteId }

        if (pending != null) {
            if (isYes(norm)) return GlobalRoute.Confirm(pending)
            if (isNo(norm)) return GlobalRoute.Cancel
            if (pending is GlobalPending.ChooseNote) {
                resolveChoice(text, pending)?.let { return it }
            }
        }

        if (HELP_HINTS.any { norm.contains(it) }) return GlobalRoute.Help
        if (LIST_HINTS.any { norm.contains(it) }) return GlobalRoute.ListNotes
        if (CONFIG_AI_HINTS.any { norm.contains(it) }) return GlobalRoute.ConfigureAi
        if (LIBRARY_HINTS.any { norm.contains(it) }) return GlobalRoute.OpenLibrary
        if (MY_OUTLINES_HINTS.any { norm.contains(it) }) return GlobalRoute.OpenMyOutlines
        if (PREPARE_HINTS.any { norm.contains(it) }) return GlobalRoute.OpenPrepare
        if (CREATE_HINTS.any { norm.contains(it) }) return GlobalRoute.CreateNote

        val wantsTrain = TRAIN_HINTS.any { norm.contains(it) }
        val wantsPresent = PRESENT_HINTS.any { norm.contains(it) }
        val tokens = significantTokens(text)

        if (wantsTrain || wantsPresent) {
            val action = if (wantsTrain) GlobalNoteAction.TRAIN else GlobalNoteAction.PRESENT
            // "treinar o Ponto 1 do discurso sobre esperança" é pedido para
            // abrir/fixar a nota; o treino em si começa depois, no walkie.
            val bindOnly = wantsTrain &&
                (norm.contains("ponto ") || norm.contains("discurso"))
            val effective = if (bindOnly) GlobalNoteAction.OPEN else action
            if (tokens.isNotEmpty()) {
                return resolveNote(tokens, notes, effective)
            }
            if (bound != null) {
                return when (action) {
                    GlobalNoteAction.TRAIN -> GlobalRoute.StartTrain(bound)
                    GlobalNoteAction.PRESENT -> GlobalRoute.OpenPresent(bound)
                    GlobalNoteAction.OPEN -> GlobalRoute.OpenNote(bound)
                }
            }
            return GlobalRoute.AskNoteForAction(action)
        }

        if (OPEN_HINTS.any { norm.startsWith(it) } || OPEN_HINTS.any { norm.contains(it) }) {
            if (tokens.isEmpty()) return GlobalRoute.ListNotes
            return resolveNote(tokens, notes, GlobalNoteAction.OPEN)
        }

        return GlobalRoute.Chat(text)
    }

    private fun resolveChoice(
        text: String,
        pending: GlobalPending.ChooseNote,
    ): GlobalRoute? {
        val norm = normalizeText(text)
        val number = norm.toIntOrNull()
        if (number != null && number in 1..pending.candidates.size) {
            return selected(pending.candidates[number - 1], pending.action)
        }
        val tokens = significantTokens(text)
        if (tokens.isEmpty()) return null
        val matches = matchCandidates(tokens, pending.candidates)
        return when {
            matches.size == 1 -> selected(matches.single(), pending.action)
            matches.size > 1 -> GlobalRoute.AskWhichNote(text, matches, pending.action)
            else -> null
        }
    }

    private fun selected(note: GlobalNote, action: GlobalNoteAction): GlobalRoute =
        when (action) {
            GlobalNoteAction.OPEN -> GlobalRoute.OpenNote(note)
            GlobalNoteAction.TRAIN -> GlobalRoute.StartTrain(note)
            GlobalNoteAction.PRESENT -> GlobalRoute.OpenPresent(note)
        }

    private fun resolveNote(
        tokens: List<String>,
        notes: List<GlobalNote>,
        action: GlobalNoteAction,
    ): GlobalRoute {
        if (notes.isEmpty()) return GlobalRoute.NotFound(emptyList())
        val matches = matchCandidates(tokens, notes)
        return when {
            matches.isEmpty() -> GlobalRoute.NotFound(notes.take(6))
            matches.size == 1 -> selected(matches.single(), action)
            else -> GlobalRoute.AskWhichNote(tokens.joinToString(" "), matches, action)
        }
    }

    private fun matchCandidates(
        tokens: List<String>,
        notes: List<GlobalNote>,
    ): List<GlobalNote> {
        val scored = notes.mapNotNull { note ->
            val title = normalizeText(note.title)
            val score = tokens.count { token -> title.contains(token) }
            if (score > 0) note to score else null
        }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.map { it.first }
    }

    /** Tokens >= 3 chars (ou números curtos), sem stopwords/palavras de comando. */
    internal fun significantTokens(text: String): List<String> =
        normalizeText(text)
            .split(' ')
            .map { it.trim() }
            .filter {
                (it.length >= 3 || it.all { c -> c.isDigit() }) &&
                    it !in STOPWORDS_PT && it !in COMMAND_WORDS
            }
            .distinct()

    private fun isYes(norm: String): Boolean =
        norm in YES || YES.any { norm.startsWith("$it ") }

    private fun isNo(norm: String): Boolean =
        norm in NO || NO.any { norm.startsWith("$it ") }
}
