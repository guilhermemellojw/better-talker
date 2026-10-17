package com.bettertalker.app.data.export

import com.bettertalker.app.data.util.decodeHtmlEntities
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import com.bettertalker.app.ui.editor.countWords
import com.bettertalker.app.ui.editor.estimateMinutes
import com.bettertalker.app.ui.editor.topicSources

/**
 * T1 (Exportação) — HTML de exportação do discurso: o TEXTO FINAL + objetivo
 * e refs inline, na ordem das seções (INTRO, BODY 1..N, CONCLUSION).
 *
 * Inclui: título da nota + métricas (palavras/tempo), objetivo do tópico,
 * corpo (mini discurso ou, na falta, os sub-pontos) e as referências ao final
 * da seção (mesma agregação/dedup do `topicSources` do editor).
 *
 * NÃO inclui (§ descoberta P2): esboço S-34 integral, linha de raciocínio,
 * abordagem acordada nem fontes de retrieval.
 *
 * CSS de impressão embutido (fonte grande, contraste, margens) para o PDF via
 * `PrintManager` e para o compartilhamento. Puro/testável (sem IO/Android).
 */

/** CSS embutido: legível na tribuna e no papel (margens + quebras sadias). */
private const val EXPORT_CSS = """
body {
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 18px; line-height: 1.65; color: #111;
  margin: 0; padding: 32px 28px;
}
h1 { font-size: 26px; margin: 0 0 4px; }
.metrics { color: #555; font-size: 14px; margin: 0 0 24px; }
section.part { margin-bottom: 22px; }
h2 { font-size: 21px; margin: 28px 0 8px; page-break-after: avoid; }
.minutes { font-weight: normal; color: #555; font-size: 15px; }
.objective { font-style: italic; color: #333; margin: 0 0 10px; }
p { margin: 0 0 10px; }
.refs { color: #555; font-size: 14px; margin-top: 10px; }
@page { margin: 18mm 16mm; }
@media print { body { padding: 0; } }
"""

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * Corpo da seção: INTRO/CONCLUSION usam `contentHtml`; BODY usa o mini
 * discurso (`contentHtml`) e, quando vazio, cai nos sub-pontos — espelhando o
 * `aggregateHtml` do editor, com o salvamento extra do `outlineText` quando o
 * desenvolvimento legado também está vazio. Puro.
 */
private fun sectionBody(section: SpeechSection, subPoints: List<SubPoint>): String =
    if (section.role == SectionRole.BODY && section.contentHtml.isBlank()) {
        subPoints.joinToString("\n") { sp ->
            sp.developedHtml.ifBlank { sp.outlineText }
        }
    } else {
        section.contentHtml
    }

/**
 * Monta o HTML de exportação do discurso. [subPointsBySection] usa o id da
 * seção como chave (fallback de BODY + refs). Puro/testável.
 */
fun buildExportHtml(
    noteTitle: String,
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>> = emptyMap(),
): String {
    val ordered = sections.sortedBy { it.order }
    val bodies = ordered.map { s -> sectionBody(s, subPointsBySection[s.id].orEmpty()) }
    val plain = bodies.filter { it.isNotBlank() }.joinToString("\n")
    val words = countWords(plain)
    val minutes = estimateMinutes(plain)

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n")
    sb.append("<html lang=\"pt-BR\">\n<head>\n")
    sb.append("<meta charset=\"utf-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<title>").append(escapeHtml(noteTitle)).append("</title>\n")
    sb.append("<style>\n").append(EXPORT_CSS).append("</style>\n")
    sb.append("</head>\n<body>\n")
    sb.append("<header class=\"doc-header\">\n")
    sb.append("<h1>").append(escapeHtml(noteTitle)).append("</h1>\n")
    val metrics = if (words > 0) "$words palavras · ≈ $minutes min" else "$words palavras"
    sb.append("<p class=\"metrics\">").append(metrics).append("</p>\n")
    sb.append("</header>\n")

    ordered.forEach { s ->
        val subs = subPointsBySection[s.id].orEmpty()
        sb.append("<section class=\"part\">\n")
        sb.append("<h2>").append(escapeHtml(s.title))
            .append(" <span class=\"minutes\">(").append(s.minutes).append(" min)</span></h2>\n")
        s.objective?.takeIf { it.isNotBlank() }?.let {
            sb.append("<p class=\"objective\">").append(escapeHtml(it)).append("</p>\n")
        }
        val body = sectionBody(s, subs)
        if (body.isNotBlank()) sb.append(body).append("\n")
        val refs = topicSources(s, subs)
        if (refs.isNotEmpty()) {
            sb.append("<p class=\"refs\">Referências: ")
                .append(refs.joinToString(" · ") { escapeHtml(it) })
                .append("</p>\n")
        }
        sb.append("</section>\n")
    }

    sb.append("</body>\n</html>\n")
    return sb.toString()
}

/**
 * T2 (Exportação) — texto puro do discurso (títulos + corpo, sem HTML) para o
 * "Copiar texto". Mesma travessia do [buildExportHtml]. Puro/testável.
 */
fun buildExportPlainText(
    sections: List<SpeechSection>,
    subPointsBySection: Map<String, List<SubPoint>> = emptyMap(),
): String {
    fun strip(html: String): String = decodeHtmlEntities(
        html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
    )
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    return sections.sortedBy { it.order }.joinToString("\n\n") { s ->
        val body = strip(sectionBody(s, subPointsBySection[s.id].orEmpty()))
        if (body.isBlank()) s.title else "${s.title}\n\n$body"
    }
}
