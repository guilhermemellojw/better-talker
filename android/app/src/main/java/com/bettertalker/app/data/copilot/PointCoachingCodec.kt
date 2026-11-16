package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec

/**
 * Codec line-based v1 do coaching de ponto.
 *
 * Formato:
 * ```
 * v1
 * sectionId=...
 * pointNumber=2
 * title=...
 * minutes=4
 * overall=ATTENTION
 * reading=...
 * generatedAt=...
 * finding=Subpontos|ATTENTION|texto
 * step=...
 * nextStep=OpenEditor|sectionId|título|2|Abrir no editor
 * ```
 *
 * Escapa `\`, `|`, `\n` e `\t`. Payload corrompido/versão desconhecida →
 * `null`; o chamador mostra o texto-resumo.
 */
object PointCoachingCodec {

    private const val VERSION = "v1"

    fun encode(report: PointCoachingReport): String = buildString {
        append(VERSION).append('\n')
        append("sectionId=").append(esc(report.sectionId)).append('\n')
        append("pointNumber=").append(report.pointNumber).append('\n')
        append("title=").append(esc(report.title)).append('\n')
        append("minutes=").append(report.minutes).append('\n')
        append("overall=").append(report.overall.name).append('\n')
        append("reading=").append(esc(report.reading)).append('\n')
        append("generatedAt=").append(report.generatedAt).append('\n')
        report.findings.forEach { finding ->
            append("finding=")
                .append(esc(finding.label)).append('|')
                .append(finding.level.name).append('|')
                .append(esc(finding.detail))
                .append('\n')
        }
        report.steps.forEach { step ->
            append("step=").append(esc(step)).append('\n')
        }
        when (val step = report.nextStep) {
            is PointCoachingNextStep.OpenEditor -> {
                append("nextStep=OpenEditor|")
                    .append(esc(step.sectionId)).append('|')
                    .append(esc(step.title)).append('|')
                    .append(step.sectionNumber).append('|')
                    .append(esc(step.label)).append('\n')
            }
        }
    }.trimEnd('\n')

    fun decode(raw: String): PointCoachingReport? {
        if (raw.isBlank()) return null
        val lines = raw.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return null

        var sectionId: String? = null
        var pointNumber: Int? = null
        var title: String? = null
        var minutes = 0
        var overall: DiagnosisLevel? = null
        var reading: String? = null
        var generatedAt = 0L
        var nextStep: PointCoachingNextStep? = null
        val findings = mutableListOf<PointCoachingFinding>()
        val steps = mutableListOf<String>()

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                "sectionId" -> sectionId = unesc(value)
                "pointNumber" -> pointNumber = value.toIntOrNull()
                "title" -> title = unesc(value)
                "minutes" -> minutes = value.toIntOrNull() ?: 0
                "overall" -> overall = enumOrNull(value)
                "reading" -> reading = unesc(value)
                "generatedAt" -> generatedAt = value.toLongOrNull() ?: 0L
                "finding" -> {
                    val fields = fields(value, 3)
                    val level = enumOrNull<DiagnosisLevel>(fields[1])
                    if (level != null) {
                        findings += PointCoachingFinding(
                            label = unesc(fields[0]),
                            level = level,
                            detail = unesc(fields[2]),
                        )
                    }
                }
                "step" -> steps += unesc(value)
                "nextStep" -> nextStep = decodeNextStep(value)
            }
        }

        if (sectionId == null || pointNumber == null || title == null ||
            overall == null || reading == null || nextStep == null
        ) {
            return null
        }
        return PointCoachingReport(
            sectionId = sectionId,
            pointNumber = pointNumber,
            title = title,
            minutes = minutes,
            overall = overall,
            reading = reading,
            findings = findings,
            steps = steps,
            nextStep = nextStep,
            generatedAt = generatedAt,
        )
    }

    private fun decodeNextStep(raw: String): PointCoachingNextStep? {
        val fields = fields(raw, 5)
        return when (fields[0]) {
            "OpenEditor" -> PointCoachingNextStep.OpenEditor(
                sectionId = unesc(fields[1]),
                title = unesc(fields[2]),
                sectionNumber = fields[3].toIntOrNull() ?: 0,
                label = unesc(fields[4]).ifBlank { "Abrir no editor" },
            )
            else -> null
        }
    }

    private fun fields(raw: String, count: Int): List<String> {
        val parts = raw.split('|')
        if (parts.size >= count) return parts
        return parts + List(count - parts.size) { "" }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
        runCatching { enumValueOf<T>(raw) }.getOrNull()

    private fun esc(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '|' -> sb.append("\\p")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unesc(raw: String): String {
        val sb = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '\\' && index + 1 < raw.length) {
                when (raw[index + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(raw[index + 1])
                }
                index += 2
            } else {
                sb.append(c)
                index++
            }
        }
        return sb.toString()
    }
}

/** Extrai o coaching de um payload de chat (`kind="coaching"`). */
fun pointCoachingFromPayload(payload: String): PointCoachingReport? =
    PointCoachingCodec.decode(ChatCodec.unescMap(payload)["report"].orEmpty())
