package com.bettertalker.app.domain.help

/**
 * Codec simples do estado dos gatilhos para DataStore (sem JSON de lib).
 * Formato: `ID,seen,dismissed,lastShownAt;...`. Itens inválidos são ignorados.
 */
object HelpTriggerCodec {

    fun encode(states: Map<HelpTriggerId, HelpTriggerState>): String =
        states.entries
            .sortedBy { it.key.name }
            .joinToString(";") { (id, state) ->
                "${id.name},${if (state.seen) 1 else 0}," +
                    "${if (state.dismissed) 1 else 0},${state.lastShownAt}"
            }

    fun decode(raw: String?): Map<HelpTriggerId, HelpTriggerState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { item ->
            val parts = item.split(',')
            if (parts.size != 4) return@mapNotNull null
            val id = runCatching { HelpTriggerId.valueOf(parts[0]) }.getOrNull()
                ?: return@mapNotNull null
            id to HelpTriggerState(
                seen = parts[1] == "1",
                dismissed = parts[2] == "1",
                lastShownAt = parts[3].toLongOrNull() ?: 0L,
            )
        }.toMap()
    }
}
