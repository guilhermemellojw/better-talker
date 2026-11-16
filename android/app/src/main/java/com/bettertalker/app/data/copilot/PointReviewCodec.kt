package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.ChatCodec

/**
 * Codec line-based v1 da revisão de ponto.
 *
 * ```
 * v1
 * sectionId=...
 * pointNumber=2
 * title=...
 * minutes=4
 * overall=OK
 * reading=...
 * generatedAt=...
 * strength=...
 * improvement=...
 * nextStep=OpenEditor|id|título|2|Abrir no editor
 * ```
 */
object PointReviewCodec {

    private const val VERSION = "v1"

    fun encode(report: PointReviewReport): String = buildString {
        append(VERSION).append('\n')
        append("sectionId=").append(esc(report.sectionId)).append('\n')
        append("pointNumber=").append(report.pointNumber).append('\n')
        append("title=").append(esc(report.title)).append('\n')
        append("minutes=").append(report.minutes).append('\n')
        append("overall=").append(report.overall.name).append('\n')
        append("reading=").append(esc(report.reading)).append('\n')
        append("generatedAt=").append(report.generatedAt).append('\n')
        append("qualityStatus=").append(report.qualityStatus.name).append('\n')
        report.qualityError?.takeIf { it.isNotBlank() }?.let {
            append("qualityError=").append(esc(it)).append('\n')
        }
        report.qualityTextHash?.takeIf { it.isNotBlank() }?.let {
            append("qualityTextHash=").append(esc(it)).append('\n')
        }
        report.qualityStartedAt?.let {
            append("qualityStartedAt=").append(it).append('\n')
        }
        report.quality?.let { quality ->
            quality.model?.takeIf { it.isNotBlank() }?.let {
                append("qualityModel=").append(esc(it)).append('\n')
            }
            quality.durationMs?.let { append("qualityDurationMs=").append(it).append('\n') }
            quality.inputTokens?.let { append("qualityInputTokens=").append(it).append('\n') }
            quality.outputTokens?.let { append("qualityOutputTokens=").append(it).append('\n') }
            if (quality.textTruncated) append("qualityTruncated=1\n")
            quality.positives.forEach { positive ->
                append("positive=")
                    .append(esc(positive.observation)).append('|')
                    .append(esc(positive.evidence.orEmpty()))
                    .append('\n')
            }
            quality.improvements.forEach { improvement ->
                append("qualityImprovement=")
                    .append(improvement.category.name).append('|')
                    .append(esc(improvement.issue)).append('|')
                    .append(esc(improvement.why)).append('|')
                    .append(esc(improvement.evidence.orEmpty()))
                    .append('\n')
            }
        }
        report.strengths.forEach { append("strength=").append(esc(it)).append('\n') }
        report.improvements.forEach { append("improvement=").append(esc(it)).append('\n') }
        when (val step = report.nextStep) {
            is PointReviewNextStep.OpenEditor -> {
                append("nextStep=OpenEditor|")
                    .append(esc(step.sectionId)).append('|')
                    .append(esc(step.title)).append('|')
                    .append(step.sectionNumber).append('|')
                    .append(esc(step.label)).append('\n')
            }
        }
    }.trimEnd('\n')

    fun decode(raw: String): PointReviewReport? {
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
        var nextStep: PointReviewNextStep? = null
        var qualityStatus = PointQualityStatus.NONE
        var qualityError: String? = null
        var qualityTextHash: String? = null
        var qualityStartedAt: Long? = null
        var qualityModel: String? = null
        var qualityDurationMs: Long? = null
        var qualityInputTokens: Int? = null
        var qualityOutputTokens: Int? = null
        var qualityTruncated = false
        val strengths = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        val qualityPositives = mutableListOf<PointQualityPositive>()
        val qualityImprovements = mutableListOf<PointQualityImprovement>()

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
                "strength" -> strengths += unesc(value)
                "improvement" -> improvements += unesc(value)
                "nextStep" -> nextStep = decodeNextStep(value)
                "qualityStatus" ->
                    qualityStatus = enumOrNull<PointQualityStatus>(value) ?: PointQualityStatus.NONE
                "qualityError" -> qualityError = unesc(value).takeIf { it.isNotBlank() }
                "qualityTextHash" -> qualityTextHash = unesc(value).takeIf { it.isNotBlank() }
                "qualityStartedAt" -> qualityStartedAt = value.toLongOrNull()
                "qualityModel" -> qualityModel = unesc(value).takeIf { it.isNotBlank() }
                "qualityDurationMs" -> qualityDurationMs = value.toLongOrNull()
                "qualityInputTokens" -> qualityInputTokens = value.toIntOrNull()
                "qualityOutputTokens" -> qualityOutputTokens = value.toIntOrNull()
                "qualityTruncated" -> qualityTruncated = value == "1"
                "positive" -> {
                    val fields = fields(value, 2)
                    val observation = unesc(fields[0]).trim()
                    if (observation.isNotBlank()) {
                        qualityPositives += PointQualityPositive(
                            observation = observation,
                            evidence = unesc(fields[1]).takeIf { it.isNotBlank() },
                        )
                    }
                }
                "qualityImprovement" -> {
                    val fields = fields(value, 4)
                    val category = enumOrNull<PointQualityCategory>(fields[0])
                    val issue = unesc(fields[1]).trim()
                    val why = unesc(fields[2]).trim()
                    if (category != null && issue.isNotBlank() && why.isNotBlank()) {
                        qualityImprovements += PointQualityImprovement(
                            category = category,
                            issue = issue,
                            why = why,
                            evidence = unesc(fields[3]).takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        }

        if (sectionId == null || pointNumber == null || title == null ||
            overall == null || reading == null || nextStep == null
        ) {
            return null
        }
        val hasQualityData = qualityModel != null || qualityDurationMs != null ||
            qualityInputTokens != null || qualityOutputTokens != null ||
            qualityTruncated || qualityPositives.isNotEmpty() ||
            qualityImprovements.isNotEmpty()
        val quality = if (hasQualityData || qualityStatus == PointQualityStatus.COMPLETED) {
            PointQualityReview(
                positives = qualityPositives,
                improvements = qualityImprovements,
                model = qualityModel,
                durationMs = qualityDurationMs,
                inputTokens = qualityInputTokens,
                outputTokens = qualityOutputTokens,
                textTruncated = qualityTruncated,
            )
        } else {
            null
        }
        return PointReviewReport(
            sectionId = sectionId,
            pointNumber = pointNumber,
            title = title,
            minutes = minutes,
            overall = overall,
            reading = reading,
            strengths = strengths,
            improvements = improvements,
            nextStep = nextStep,
            generatedAt = generatedAt,
            quality = quality,
            qualityStatus = qualityStatus,
            qualityError = qualityError,
            qualityTextHash = qualityTextHash,
            qualityStartedAt = qualityStartedAt,
        )
    }

    private fun decodeNextStep(raw: String): PointReviewNextStep? {
        val fields = fields(raw, 5)
        return when (fields[0]) {
            "OpenEditor" -> PointReviewNextStep.OpenEditor(
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

/** Extrai a revisão de um payload de chat (`kind="review"`). */
fun pointReviewFromPayload(payload: String): PointReviewReport? =
    PointReviewCodec.decode(ChatCodec.unescMap(payload)["report"].orEmpty())
