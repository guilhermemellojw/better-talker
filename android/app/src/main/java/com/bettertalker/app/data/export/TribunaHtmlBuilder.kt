package com.bettertalker.app.data.export

import com.bettertalker.app.domain.speech.TribunaCapsule

/** #20-fix-c: dados mínimos de um card da Tribuna para o export. */
data class TribunaCardData(
    val order: Int,
    val sectionTitle: String,
    val minutes: Int,
    val capsule: TribunaCapsule?,
)

/**
 * #20-fix-c: HTML da Tribuna — MAPA DE MEMÓRIA (não o discurso completo).
 * Print-friendly, sem chrome de app; cards sem cápsula são omitidos.
 * Puro/testável.
 */
fun buildTribunaHtml(
    noteTitle: String,
    cards: List<TribunaCardData>,
): String {
    val usable = cards.filter { it.capsule != null }
    val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR"))
        .format(java.util.Date())
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n")
    sb.append("<html lang=\"pt-BR\">\n<head>\n")
    sb.append("<meta charset=\"utf-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<title>Tribuna — ").append(escapeTribuna(noteTitle)).append("</title>\n")
    sb.append("<style>\n").append(TRIBUNA_CSS).append("</style>\n")
    sb.append("</head>\n<body>\n")
    sb.append("<header class=\"doc-header\">\n")
    sb.append("<h1>Tribuna</h1>\n")
    sb.append("<p class=\"sub\">").append(escapeTribuna(noteTitle)).append(" · ")
        .append(date).append("</p>\n")
    sb.append("</header>\n")
    if (usable.isEmpty()) {
        sb.append("<p class=\"empty\">Nenhuma Tribuna gerada.</p>\n")
    } else {
        usable.forEach { c ->
            val cap = c.capsule!!
            sb.append("<section class=\"card\">\n")
            sb.append("<h2>").append(c.order + 1).append(". ")
                .append(escapeTribuna(c.sectionTitle)).append(" — ")
                .append(c.minutes).append(" min</h2>\n")
            sb.append("<p class=\"field\"><span class=\"label\">Ideia central:</span> ")
                .append(escapeTribuna(cap.mainIdea)).append("</p>\n")
            cap.bibleRef?.takeIf { it.isNotBlank() }?.let { ref ->
                sb.append("<p class=\"field\"><span class=\"label\">Texto-chave:</span> ")
                    .append(escapeTribuna(ref)).append("</p>\n")
            }
            sb.append("<p class=\"anchor\"><span class=\"label\">Frase-âncora:</span> ")
                .append(escapeTribuna(cap.anchorPhrase)).append("</p>\n")
            cap.transition?.takeIf { it.isNotBlank() }?.let { tr ->
                sb.append("<p class=\"field\"><span class=\"label\">Transição:</span> ")
                    .append(escapeTribuna(tr)).append("</p>\n")
            }
            sb.append("</section>\n")
        }
    }
    sb.append("</body>\n</html>\n")
    return sb.toString()
}

/** #20-fix-c: escapa HTML (evita injection no export). Puro/testável. */
internal fun escapeTribuna(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private const val TRIBUNA_CSS = """
body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; color: #1a1a1a; margin: 24px; line-height: 1.5; }
.doc-header h1 { margin: 0 0 2px 0; }
.doc-header .sub { color: #666; margin: 0 0 18px 0; }
.card { border: 1px solid #ddd; border-radius: 10px; padding: 14px 16px; margin: 0 0 14px 0; page-break-inside: avoid; }
.card h2 { margin: 0 0 8px 0; font-size: 16px; }
.field { margin: 4px 0; }
.label { color: #666; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; margin-right: 6px; }
.anchor { margin: 8px 0 4px 0; font-weight: 700; font-size: 17px; }
.empty { color: #666; }
@media print { .card { page-break-inside: avoid; } }
"""
