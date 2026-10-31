package com.bettertalker.app.matrix

import android.content.Context
import java.io.File

/** Leitura/escrita da matriz C2.6 (CSV sanitizado). */
object MatrixCsv {

    const val ASSET = "matriz-c26.csv"
    const val RESULT_NAME = "matriz-c26-result.csv"
    const val SUMMARY_NAME = "matriz-c26-summary.txt"
    const val ACERVO_NAME = "matriz-c26-acervo.csv"

    fun readRows(ctx: Context): List<MatrixRow> =
        ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            parse(reader.readLines())
        }

    /** Parser CSV simples com suporte a aspas duplas (os campos da C2.6 não têm vírgula). */
    fun parse(lines: List<String>): List<MatrixRow> {
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines.first())
        val idx = header.withIndex().associate { it.value.trim() to it.index }
        fun field(cells: List<String>, name: String): String =
            idx[name]?.let { cells.getOrNull(it) }?.trim().orEmpty()
        fun intField(cells: List<String>, name: String): Int? =
            field(cells, name).toIntOrNull()
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val cells = splitCsv(line)
            MatrixRow(
                file = field(cells, "file"),
                raw = field(cells, "raw"),
                edition = field(cells, "edition"),
                page = intField(cells, "page"),
                pageEnd = intField(cells, "pageEnd"),
                paragraph = intField(cells, "paragraph"),
                paragraphEnd = intField(cells, "paragraphEnd"),
                ambiguous = field(cells, "ambiguous").equals("true", ignoreCase = true),
            )
        }
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    fun writeResults(ctx: Context, results: List<MatrixResult>): File {
        val file = File(ctx.filesDir, RESULT_NAME)
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine(
                "file,raw,edition,page,pageEnd,paragraph,paragraphEnd,ambiguous," +
                    "status,motivo,acao,formato,arquivo,arquivo_esperado,wrong_edition," +
                    "resolver_status,resolver_canonical,resolver_chars," +
                    "cited_ok,cited_detail,note_source"
            )
            results.forEach { r ->
                w.appendLine(
                    listOf(
                        r.row.file, r.row.raw, r.row.edition,
                        r.row.page?.toString().orEmpty(), r.row.pageEnd?.toString().orEmpty(),
                        r.row.paragraph?.toString().orEmpty(), r.row.paragraphEnd?.toString().orEmpty(),
                        r.row.ambiguous.toString(),
                        r.status, r.reason, r.action, r.format.orEmpty(), r.fileName.orEmpty(),
                        r.expectedFileName.orEmpty(), r.wrongEdition.toString(),
                        r.resolverStatus, r.resolverCanonical.orEmpty(), r.resolverChars.toString(),
                        r.citedOk.toString(), r.citedDetail.orEmpty(), r.noteSourceFound.toString(),
                    ).joinToString(",") { csv(it) }
                )
            }
        }
        // Cópia no externalFilesDir para puxar sem run-as, quando disponível.
        runCatching {
            val ext = ctx.getExternalFilesDir(null) ?: return@runCatching
            file.copyTo(File(ext, RESULT_NAME), overwrite = true)
        }
        return file
    }

    fun writeAcervo(ctx: Context, report: AcervoReport): File {
        val file = File(ctx.filesDir, ACERVO_NAME)
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine("edition,status,format,fileName,attachmentId,bytes,error")
            report.results.forEach { r ->
                w.appendLine(
                    listOf(
                        r.edition, r.status, r.format.orEmpty(), r.fileName.orEmpty(),
                        r.attachmentId.orEmpty(), r.bytes.toString(), r.error.orEmpty(),
                    ).joinToString(",") { csv(it) }
                )
            }
        }
        runCatching {
            val ext = ctx.getExternalFilesDir(null) ?: return@runCatching
            file.copyTo(File(ext, ACERVO_NAME), overwrite = true)
        }
        return file
    }

    fun writeSummary(ctx: Context, acervo: AcervoReport, matrix: MatrixReport): File {
        val file = File(ctx.filesDir, SUMMARY_NAME)
        val pct = if (matrix.total == 0) 0.0 else matrix.usable * 100.0 / matrix.total
        file.writeText(
            buildString {
                appendLine("MATRIZ C2.6 — RESUMO")
                appendLine("Gerado: ${java.util.Date()}")
                appendLine()
                appendLine("ACERVO")
                appendLine("  edições: ${acervo.results.size}")
                appendLine("  prontas: ${acervo.ready}")
                appendLine("  falhas: ${acervo.failed}")
                appendLine("  indisponíveis: ${acervo.unavailable}")
                appendLine("  bytes: ${acervo.totalBytes}")
                appendLine("  duração_ms: ${acervo.finishedAt - acervo.startedAt}")
                acervo.formatValidation.forEach {
                    appendLine(
                        "  formato ${it.format}: ${it.edition} → ${it.status} " +
                            "(${it.fileName.orEmpty()})${it.error?.let { e -> " · $e" }.orEmpty()}"
                    )
                }
                appendLine()
                appendLine("MATRIZ")
                appendLine("  refs: ${matrix.total}")
                appendLine("  RESOLVED: ${matrix.resolved}")
                appendLine("  PARTIAL: ${matrix.partial}")
                appendLine("  TEXT_NOT_FOUND: ${matrix.textNotFound}")
                appendLine("  EDITION_MISSING: ${matrix.editionMissing}")
                appendLine("  outros: ${matrix.other}")
                appendLine("  utilizáveis (RESOLVED+PARTIAL): ${matrix.usable} (${"%.1f".format(pct)}%)")
                appendLine()
                appendLine("STATUS")
                matrix.results.groupingBy { it.status }.eachCount()
                    .toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
                appendLine()
                appendLine("AÇÕES")
                matrix.results.groupingBy { it.action }.eachCount()
                    .toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
                appendLine()
                appendLine("FORMATOS")
                matrix.results.groupingBy { it.format ?: "?" }.eachCount()
                    .toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
            }
        )
        runCatching {
            val ext = ctx.getExternalFilesDir(null) ?: return@runCatching
            file.copyTo(File(ext, SUMMARY_NAME), overwrite = true)
        }
        return file
    }

    private fun csv(value: String): String {
        val v = value.replace("\r", " ").replace("\n", " ")
        return if (v.contains(',') || v.contains('"')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }
}
