package com.bettertalker.app.data.util

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API oficial de mídia (b.jw-cdn.org): resolve o ARQUIVO direto de uma edição.
 * Respostas verificadas: livros (pub, sem issue), w mensal (issue=AAAAMM),
 * g mensal antiga (issue=AAAAMM). Datadas antigas (wAAAAMMDD) e numeradas
 * (wp/gn) não têm arquivo derivável -> usa página (downloadUrl).
 */
object JwMediaApi {
    private const val BASE = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS"
    private const val TIMEOUT = 15000
    private const val PROBE_TIMEOUT = 8000

    data class DirectFile(val url: String, val fileName: String, val format: String)

    /** Ordem de preferência: texto limpo e comprovado primeiro. */
    private val FORMATS = listOf("PDF", "EPUB", "RTF")

    /**
     * Mapeia editionKey -> (pub, issue?) da API. Null = sem arquivo direto.
     * Puro/testável.
     */
    fun apiQuery(editionKey: String): Pair<String, String?>? {
        val p = editionKey.split("|")
        if (p.size < 2) return null
        return when {
            p[0] == "book" -> null // livros usam landing/finder (página com formatos)
            p[0] == "g" && p.size == 3 -> {
                val y = p[1].toIntOrNull() ?: return null
                val m = p[2].toIntOrNull() ?: return null
                if (m !in 1..12) return null
                "g" to "$y${m.toString().padStart(2, '0')}"
            }
            p[0] == "w" && p.size == 3 -> {
                val y = p[1].toIntOrNull() ?: return null
                val m = p[2].toIntOrNull() ?: return null
                if (m !in 1..12) return null
                "w" to "$y${m.toString().padStart(2, '0')}"
            }
            else -> null
        }
    }

    /** Tenta resolver o arquivo direto (rede, chamar fora da main thread). */
    fun resolveFile(pub: String, issue: String?): DirectFile? {
        for (fmt in FORMATS) {
            queryFormat(pub, issue, fmt, TIMEOUT)?.let { return it }
        }
        return null
    }

    private fun queryFormat(pub: String, issue: String?, fmt: String, timeout: Int): DirectFile? {
        return try {
            val q = buildString {
                append("output=json&pub=").append(URLEncoder.encode(pub, "UTF-8"))
                if (issue != null) append("&issue=").append(URLEncoder.encode(issue, "UTF-8"))
                append("&fileformat=").append(fmt)
                append("&alllangs=0&langwritten=T&txtCMSLang=T")
            }
            val conn = (URL("$BASE?$q").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().readText()
                // { files: { T: { PDF: [ { file: { url } } ] } } }
                val arr = org.json.JSONObject(body)
                    .optJSONObject("files")
                    ?.optJSONObject("T")
                    ?.optJSONArray(fmt) ?: return null
                if (arr.length() == 0) return null
                val url = arr.optJSONObject(0)
                    ?.optJSONObject("file")?.optString("url").orEmpty()
                if (url.isBlank()) return null
                val name = url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "$pub-${issue ?: "edicao"}.$fmt".lowercase() }
                DirectFile(url, name, fmt)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, DirectFile?>()

    /**
     * Sonda os meses do ano (numeradas sem código derivável,
     * ex: wp N.º 3 2019 -> issue 201909). Null = nada achado.
     * Sequencial com timeout curto por tentativa; chamar fora da main thread.
     */
    fun probeIssueFile(pub: String, year: Int): DirectFile? {
        val key = "$pub|$year"
        if (probeCache.containsKey(key)) return probeCache[key]
        // ordem provável primeiro: set/out (N.º 3), jul (N.º 1 2024), depois o resto
        val order = listOf(9, 7, 1, 3, 5, 6, 8, 10, 11, 12, 2, 4)
        var found: DirectFile? = null
        for (m in order) {
            val issue = "$year${m.toString().padStart(2, '0')}"
            for (fmt in FORMATS) {
                found = queryFormat(pub, issue, fmt, PROBE_TIMEOUT)
                if (found != null) break
            }
            if (found != null) break
        }
        probeCache[key] = found
        return found
    }
}
