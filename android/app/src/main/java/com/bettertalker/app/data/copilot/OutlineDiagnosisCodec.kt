package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec

/**
 * Codec line-based v1 do diagnóstico.
 *
 * Formato:
 * ```
 * v1
 * overall=OK
 * headline=...
 * summary=...
 * s34=LINKED|...
 * generatedAt=...
 * nextStep=OpenSection|id|título|1|Abrir
 * criterion=STRUCTURE|OK|Estrutura|1 intro...|
 * point=id|1|Título|5|OK|texto|Abrir
 * priority=...
 * ```
 *
 * Campos são escapados antes de entrar na linha (`\`, `|`, `\n`, `\t`).
 * Payload corrompido ou versão desconhecida → `null`; o chamador mostra o
 * texto-resumo como fallback.
 */
object OutlineDiagnosisCodec {

    private const val VERSION = "v1"

    fun encode(report: OutlineDiagnosis): String = buildString {
        append(VERSION).append('\n')
        append("overall=").append(report.overall.name).append('\n')
        append("headline=").append(esc(report.headline)).append('\n')
        append("summary=").append(esc(report.summary)).append('\n')
        append("s34=").append(report.s34Status.name).append('|')
            .append(esc(report.s34Detail)).append('\n')
        append("generatedAt=").append(report.generatedAt).append('\n')
        append("nextStep=").append(encodeNextStep(report.nextStep)).append('\n')
        report.criteria.forEach { criterion ->
            append("criterion=")
                .append(criterion.id.name).append('|')
                .append(criterion.level.name).append('|')
                .append(esc(criterion.label)).append('|')
                .append(esc(criterion.finding)).append('|')
                .append(esc(criterion.action.orEmpty()))
                .append('\n')
        }
        report.points.forEach { point ->
            append("point=")
                .append(esc(point.sectionId)).append('|')
                .append(point.number).append('|')
                .append(esc(point.title)).append('|')
                .append(point.minutes).append('|')
                .append(point.level.name).append('|')
                .append(esc(point.finding)).append('|')
                .append(esc(point.actionLabel))
                .append('\n')
        }
        report.priorities.forEach { priority ->
            append("priority=").append(esc(priority)).append('\n')
        }
    }.trimEnd('\n')

    fun decode(raw: String): OutlineDiagnosis? {
        if (raw.isBlank()) return null
        val lines = raw.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return null

        var overall: DiagnosisLevel? = null
        var headline: String? = null
        var summary: String? = null
        var s34Status: DiagnosisS34Status? = null
        var s34Detail = ""
        var generatedAt = 0L
        var nextStep: DiagnosisNextStep? = null
        val criteria = mutableListOf<DiagnosisCriterion>()
        val points = mutableListOf<DiagnosisPoint>()
        val priorities = mutableListOf<String>()

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                "overall" -> overall = enumOrNull(value)
                "headline" -> headline = unesc(value)
                "summary" -> summary = unesc(value)
                "s34" -> {
                    val fields = fields(value, 2)
                    s34Status = enumOrNull(fields[0])
                    s34Detail = unesc(fields[1])
                }
                "generatedAt" -> generatedAt = value.toLongOrNull() ?: 0L
                "nextStep" -> nextStep = decodeNextStep(value)
                "criterion" -> {
                    val fields = fields(value, 5)
                    val id = enumOrNull<DiagnosisCriterionId>(fields[0])
                    val level = enumOrNull<DiagnosisLevel>(fields[1])
                    if (id != null && level != null) {
                        criteria += DiagnosisCriterion(
                            id = id,
                            label = unesc(fields[2]),
                            level = level,
                            finding = unesc(fields[3]),
                            action = unesc(fields[4]).takeIf { it.isNotBlank() },
                        )
                    }
                }
                "point" -> {
                    val fields = fields(value, 7)
                    val level = enumOrNull<DiagnosisLevel>(fields[4])
                    if (level != null) {
                        points += DiagnosisPoint(
                            sectionId = unesc(fields[0]),
                            number = fields[1].toIntOrNull() ?: 0,
                            title = unesc(fields[2]),
                            minutes = fields[3].toIntOrNull() ?: 0,
                            level = level,
                            finding = unesc(fields[5]),
                            actionLabel = unesc(fields[6]),
                        )
                    }
                }
                "priority" -> priorities += unesc(value)
            }
        }

        if (overall == null || headline == null || summary == null ||
            s34Status == null || nextStep == null
        ) {
            return null
        }
        return OutlineDiagnosis(
            overall = overall,
            headline = headline,
            summary = summary,
            s34Status = s34Status,
            s34Detail = s34Detail,
            criteria = criteria,
            points = points,
            priorities = priorities,
            nextStep = nextStep,
            generatedAt = generatedAt,
        )
    }

    private fun encodeNextStep(step: DiagnosisNextStep): String = when (step) {
        is DiagnosisNextStep.OpenSection -> listOf(
            "OpenSection",
            esc(step.sectionId),
            esc(step.title),
            step.sectionNumber.toString(),
            esc(step.label),
        ).joinToString("|")
        is DiagnosisNextStep.OpenNote -> "OpenNote|${esc(step.label)}"
        is DiagnosisNextStep.ShowSources -> "ShowSources|${esc(step.label)}"
        is DiagnosisNextStep.LinkS34 -> "LinkS34|${esc(step.label)}"
        is DiagnosisNextStep.ReimportS34 -> "ReimportS34|${esc(step.label)}"
        is DiagnosisNextStep.Reanalyze -> "Reanalyze|${esc(step.label)}"
    }

    private fun decodeNextStep(raw: String): DiagnosisNextStep? {
        val fields = fields(raw, 5)
        return when (fields[0]) {
            "OpenSection" -> DiagnosisNextStep.OpenSection(
                sectionId = unesc(fields[1]),
                title = unesc(fields[2]),
                sectionNumber = fields[3].toIntOrNull() ?: 0,
                label = unesc(fields[4]).ifBlank { "Abrir" },
            )
            "OpenNote" -> DiagnosisNextStep.OpenNote(unesc(fields[1]).ifBlank { "Abrir nota" })
            "ShowSources" -> DiagnosisNextStep.ShowSources(
                unesc(fields[1]).ifBlank { "Mostrar fontes" },
            )
            "LinkS34" -> DiagnosisNextStep.LinkS34(unesc(fields[1]).ifBlank { "Vincular S-34" })
            "ReimportS34" -> DiagnosisNextStep.ReimportS34(
                unesc(fields[1]).ifBlank { "Reimportar S-34" },
            )
            "Reanalyze" -> DiagnosisNextStep.Reanalyze(
                unesc(fields[1]).ifBlank { "Reanalisar" },
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

/** Extrai o diagnóstico de um payload de chat (`kind="diagnosis"`). */
fun diagnosisFromPayload(payload: String): OutlineDiagnosis? =
    OutlineDiagnosisCodec.decode(ChatCodec.unescMap(payload)["report"].orEmpty())
