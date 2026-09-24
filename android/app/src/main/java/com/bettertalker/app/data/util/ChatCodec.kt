package com.bettertalker.app.data.util

import com.bettertalker.app.data.repo.IdeaCard

/** JSON manual p/ IdeaCard (org.json fora dos testes JVM). */
object ChatCodec {

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun cardToJson(c: IdeaCard): String =
        "{\"t\":\"${esc(c.title)}\",\"b\":\"${esc(c.body)}\"," +
            "\"s\":\"${esc(c.snippet)}\",\"u\":\"${esc(c.jwUrl)}\"," +
            "\"src\":\"${esc(c.source)}\",\"sec\":\"${esc(c.sectionTitle)}\"," +
            "\"pr\":\"${esc(c.placementReason)}\",\"f\":${if (c.insertable) 1 else 0}}"

    fun cardsToJson(cards: List<IdeaCard>): String =
        cards.joinToString(",", "[", "]") { cardToJson(it) }

    fun cardsFromJson(json: String): List<IdeaCard> {        return try {
            // tolerante: mensagens antigas não têm "f" (= inserível)
            val item = Regex("""\{"t":"((?:[^"\\]|\\.)*)","b":"((?:[^"\\]|\\.)*)","s":"((?:[^"\\]|\\.)*)","u":"((?:[^"\\]|\\.)*)","src":"((?:[^"\\]|\\.)*)","sec":"((?:[^"\\]|\\.)*)","pr":"((?:[^"\\]|\\.)*)"(?:,"f":([01]))?\}""")
            item.findAll(json).map { m ->
                IdeaCard(
                    unesc(m.groupValues[1]), unesc(m.groupValues[2]),
                    unesc(m.groupValues[3]), unesc(m.groupValues[4]),
                    unesc(m.groupValues[5]), unesc(m.groupValues[6]),
                    unesc(m.groupValues[7]),
                    m.groupValues[8].takeIf { it.isNotEmpty() }?.let { it == "1" } ?: true
                )
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Mapa plano string->string (payloads de mensagem). */
    fun escMap(m: Map<String, String>): String =
        m.entries.joinToString(",", "{", "}") { (k, v) -> "\"${esc(k)}\":\"${esc(v)}\"" }

    fun unescMap(json: String): Map<String, String> {
        return try {
            val re = Regex("\"((?:[^\"\\\\]|\\\\.)*)\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            re.findAll(json).associate { unesc(it.groupValues[1]) to unesc(it.groupValues[2]) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Instantâneo de seção p/ mensagens (título + minutos + nível). */
    data class SecItem(val title: String, val minutes: Int?, val level: Int = 0)

    fun sectionsToJson(secs: List<SecItem>): String =
        secs.joinToString(",", "[", "]") {
            "{\"t\":\"${esc(it.title)}\",\"m\":${it.minutes ?: -1},\"l\":${it.level}}"
        }

    fun sectionsFromJson(json: String): List<SecItem> {
        return try {
            // tolerante: mensagens antigas não têm "l"
            val re = Regex("""\{"t":"((?:[^"\\]|\\.)*)","m":(-?\d+)(?:,"l":(-?\d+))?\}""")
            re.findAll(json).map { m ->
                SecItem(
                    unesc(m.groupValues[1]),
                    m.groupValues[2].toInt().takeIf { it >= 0 },
                    m.groupValues[3].toIntOrNull() ?: 0
                )
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Instantâneo de tópico do draft p/ mensagens. */
    data class DraftItem(
        val title: String,
        val minutes: Int?,
        val included: Boolean = true,
        val body: String = "",
        val level: Int = 0
    )

    fun draftToJson(title: String, items: List<DraftItem>): String {
        val body = items.joinToString(",", "[", "]") {
            "{\"t\":\"${esc(it.title)}\",\"m\":${it.minutes ?: -1}," +
                "\"i\":${if (it.included) 1 else 0}," +
                "\"b\":\"${esc(it.body)}\",\"l\":${it.level}}"
        }
        return "{\"title\":\"${esc(title)}\",\"items\":$body}"
    }

    fun draftFromJson(json: String): Pair<String, List<DraftItem>> {
        return try {
            val title = Regex("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(json)?.let { unesc(it.groupValues[1]) }.orEmpty()
            val itemsRe = Regex(
                """\{"t":"((?:[^"\\]|\\.)*)","m":(-?\d+),"i":([01]),"b":"((?:[^"\\]|\\.)*)","l":(-?\d+)\}"""
            )
            val items = itemsRe.findAll(json).map { m ->
                DraftItem(
                    unesc(m.groupValues[1]),
                    m.groupValues[2].toInt().takeIf { it >= 0 },
                    m.groupValues[3] == "1",
                    unesc(m.groupValues[4]),
                    m.groupValues[5].toInt()
                )
            }.toList()
            title to items
        } catch (_: Exception) {
            "" to emptyList()
        }
    }
}
