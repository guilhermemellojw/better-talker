package com.bettertalker.app.data.copilot

/**
 * Fase 3 — telemetria do card-first. Só metadados; nunca o texto do usuário
 * ou do card. As linhas são puras/testáveis e vão para o logcat em
 * `CardMetrics`.
 */
object CardMetrics {
    private const val TAG = "CardMetrics"

    fun emitted(
        route: String,
        target: String,
        source: String?,
        chars: Int,
        cards: Int,
    ) = emit(emittedLine(route, target, source, chars, cards))

    fun applied(action: String, stale: Boolean, undo: Boolean, chars: Int) =
        emit(appliedLine(action, stale, undo, chars))

    fun replaced(confirmed: Boolean, stale: Boolean, chars: Int) =
        emit(replacedLine(confirmed, stale, chars))

    fun undone() = emit("card|undone|restored=true")

    fun stale(action: String, chars: Int = 0) = emit(staleLine(action, chars))

    fun dismissed(reason: String) = emit("card|dismissed|reason=$reason")

    fun gate(action: String, reason: String? = null) =
        emit("card|gate|action=$action" + (reason?.let { "|reason=$it" } ?: ""))

    fun target(kind: String, picked: String, target: String? = null) =
        emit(
            "card|target|kind=$kind|picked=$picked" +
                (target?.let { "|target=$it" } ?: ""),
        )

    fun failed(stage: String) = emit("card|failed|stage=$stage")

    fun secondIgnored(count: Int) = emit("card|second_ignored|count=$count")

    fun transform(target: String, source: String?, chars: Int) =
        emit(
            "card|transform|target=$target|source=${source ?: "NONE"}|chars=$chars",
        )

    fun superseded(target: String) = emit("card|superseded|target=$target")

    fun proposalUsed(route: String) = emit("proposal_text|used|route=$route")

    internal fun emittedLine(
        route: String,
        target: String,
        source: String?,
        chars: Int,
        cards: Int,
    ): String = "card|emitted|route=$route|target=$target|source=${source ?: "NONE"}|" +
        "chars=$chars|cards=$cards"

    internal fun appliedLine(action: String, stale: Boolean, undo: Boolean, chars: Int): String =
        "card|applied|action=$action|stale=$stale|undo=$undo|chars=$chars"

    internal fun replacedLine(confirmed: Boolean, stale: Boolean, chars: Int): String =
        "card|replaced|confirmed=$confirmed|stale=$stale|chars=$chars"

    internal fun staleLine(action: String, chars: Int): String =
        "card|stale|action=$action|chars=$chars"

    private fun emit(line: String) {
        android.util.Log.i(TAG, line)
    }
}
