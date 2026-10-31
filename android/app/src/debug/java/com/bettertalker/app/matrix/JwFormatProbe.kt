package com.bettertalker.app.matrix

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sonda debug-only da API oficial (b.jw-cdn.org) para descobrir TODOS os
 * formatos de uma edição. O app usa `JwMediaApi` (primeiro formato); aqui
 * precisamos do mapa completo para escolher JWPUB → EPUB → PDF e para achar
 * os casos EPUB-only/PDF-only.
 */
object JwFormatProbe {
    private const val BASE = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS"
    private const val TIMEOUT = 25_000

    data class PlanData(
        val status: String,
        val pub: String,
        val issue: String?,
        val formats: Map<String, String>,
        val fileNames: Map<String, String>,
        val sizes: Map<String, Long>,
        val pubName: String,
        val formattedDate: String,
        val error: String? = null,
    )

    /** Candidatos (pub, issue) para a chave de edição do CSV. */
    fun candidates(edition: String): List<Pair<String, String?>> {
        val p = edition.split("|")
        if (p.isEmpty()) return emptyList()
        return when (p[0]) {
            "book" -> {
                if (p.size < 2) emptyList()
                else listOf(p[1] to null)
            }
            "w", "g" -> {
                if (p.size == 3) {
                    listOf(p[0] to "${p[1]}${p[2].padStart(2, '0')}")
                } else if (p.size >= 4) {
                    val month = "${p[1]}${p[2].padStart(2, '0')}"
                    val day = "$month${p[3].padStart(2, '0')}"
                    listOf(p[0] to day, p[0] to month)
                } else {
                    emptyList()
                }
            }
            "wp", "gn" -> {
                val year = p.getOrNull(1)?.toIntOrNull() ?: return emptyList()
                val num = p.getOrNull(2)?.toIntOrNull() ?: return emptyList()
                val pub = if (p[0] == "wp") "wp" else "g"
                wpMonths(year, num).map { pub to "$year${it.toString().padStart(2, '0')}" }
            }
            else -> emptyList()
        }
    }

    /** Mesma tabela do `RefDetector.wpIssueMonths` (público numerado). */
    fun wpMonths(year: Int, num: Int): List<Int> {
        val starts: List<Int> = when {
            year in 2016..2017 && num in 1..6 -> listOf(2 * num - 1)
            year in 2018..2021 && num in 1..3 -> listOf(listOf(1, 5, 9)[num - 1])
            year >= 2022 && num == 1 -> (1..12).toList()
            else -> return emptyList()
        }
        return starts.flatMap { s -> listOf(s, (s + 1).coerceAtMost(12)) }
            .distinct()
            .sorted()
    }

    fun probe(edition: String): PlanData {
        val cands = candidates(edition)
        if (cands.isEmpty()) {
            return PlanData("NO_QUERY", "", null, emptyMap(), emptyMap(), emptyMap(), "", "", "sem candidato de API")
        }
        var last: PlanData? = null
        for ((index, cand) in cands.withIndex()) {
            val (pub, issue) = cand
            val data = queryAll(pub, issue)
            if (data.formats.isNotEmpty()) {
                val status = when {
                    cands.size > 1 && index == 0 -> "OK_DAY"
                    cands.size > 1 -> "OK_MONTH_FALLBACK"
                    else -> "OK"
                }
                return data.copy(status = status)
            }
            last = data
        }
        return (last ?: PlanData("NOT_FOUND", "", null, emptyMap(), emptyMap(), emptyMap(), "", ""))
            .copy(status = if (last?.error != null) "ERROR" else "NOT_FOUND")
    }

    private fun queryAll(pub: String, issue: String?): PlanData {
        val q = buildString {
            append("output=json&pub=").append(URLEncoder.encode(pub, "UTF-8"))
            if (!issue.isNullOrBlank()) append("&issue=").append(URLEncoder.encode(issue, "UTF-8"))
            append("&fileformat=JWPUB,EPUB,PDF,RTF&alllangs=0&langwritten=T&txtCMSLang=T")
        }
        return try {
            val conn = (URL("$BASE?$q").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BetterTalker-Matrix/1.0")
            }
            try {
                if (conn.responseCode != 200) {
                    return PlanData(
                        "NOT_FOUND", pub, issue, emptyMap(), emptyMap(), emptyMap(), "", "",
                        "HTTP ${conn.responseCode}",
                    )
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(body)
                val files = json.optJSONObject("files")?.optJSONObject("T")
                    ?: return PlanData("NOT_FOUND", pub, issue, emptyMap(), emptyMap(), emptyMap(), "", "")
                val formats = linkedMapOf<String, String>()
                val names = linkedMapOf<String, String>()
                val sizes = linkedMapOf<String, Long>()
                for (fmt in EditionPlan.FORMAT_PREFERENCE) {
                    val arr = files.optJSONArray(fmt) ?: continue
                    if (arr.length() == 0) continue
                    val item = arr.optJSONObject(0) ?: continue
                    val file = item.optJSONObject("file") ?: continue
                    val url = file.optString("url").orEmpty()
                    if (url.isBlank()) continue
                    formats[fmt] = url
                    names[fmt] = url.substringAfterLast('/').substringBefore('?')
                        .ifBlank { "$pub-${issue ?: "ed"}.${fmt.lowercase()}" }
                    sizes[fmt] = item.optLong("filesize", 0L)
                }
                PlanData(
                    status = if (formats.isEmpty()) "NOT_FOUND" else "OK",
                    pub = pub,
                    issue = issue,
                    formats = formats,
                    fileNames = names,
                    sizes = sizes,
                    pubName = json.optString("pubName").orEmpty(),
                    formattedDate = json.optString("formattedDate").orEmpty(),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            PlanData("ERROR", pub, issue, emptyMap(), emptyMap(), emptyMap(), "", "", e.message?.take(160))
        }
    }
}
