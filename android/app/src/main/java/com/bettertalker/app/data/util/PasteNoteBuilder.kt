package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.DiscourseType

/**
 * Fase 1 "Colar e Pronto" (Home): transforma o texto colado numa nota pronta.
 *
 * - TALK (V&M detectado) → nota tipada com o título da parte + BODY com o texto;
 * - DEMO (V&M) → BODY com o texto quebrado em turnos (`<p><b>Papel:</b> fala</p>`);
 * - Genérico → nota simples com o texto no corpo (sem seção).
 *
 * Decisões do Discovery: contentHtml no MVP, splitter flexível, sem geração
 * automática (o texto colado É o conteúdo). Puro/testável — sem IO.
 */

/** Nota pronta construída a partir do texto colado. */
data class PastedNote(
    val title: String,
    val discourseType: DiscourseType,
    /** Texto puro (preview/busca da nota). */
    val plainText: String,
    /** Corpo estruturado (demo/discurso) — null = nota simples (sem seção). */
    val bodyHtml: String?,
    val bodyTitle: String,
    val bodyMinutes: Int,
)

/** Um turno da demonstração (papel + fala). */
data class PasteTurn(val speaker: String, val text: String)

/**
 * Rótulo de papel no início da linha: "Publicador:", "Morador:", "Irmã Ana:",
 * "Morador (dono da casa):". Flexível a acentos/espaços/variações; ≤25 chars
 * para não engolir frases inteiras. Puro.
 */
private val TURN_LABEL_RE =
    Regex("""^\s*([\p{L}][\p{L}0-9 .'()\-]{0,24}?)\s*:\s*(.*)$""")

/**
 * Divide o texto em turnos pelos rótulos de papel (linha a linha). O texto de
 * um turno vai até o próximo rótulo; quebras internas viram espaço. Texto
 * antes do 1º rótulo é ignorado (é cenário/título, não fala). Puro/testável.
 */
fun splitDemonstrationTurns(text: String): List<PasteTurn> {
    val turns = mutableListOf<PasteTurn>()
    var speaker: String? = null
    val buf = StringBuilder()
    fun flush() {
        val t = buf.toString().trim()
        val sp = speaker
        if (t.isNotEmpty() && sp != null) turns += PasteTurn(sp, t)
        buf.clear()
    }
    for (raw in text.lines()) {
        val line = raw.trim()
        val m = TURN_LABEL_RE.find(line)
        val label = m?.groupValues?.get(1)?.trim().orEmpty()
        if (m != null && label.isNotEmpty()) {
            flush()
            speaker = label
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) buf.append(rest)
        } else if (line.isNotEmpty() && speaker != null) {
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(line)
        }
    }
    flush()
    return turns
}

/** Escapa o essencial de HTML no texto colado. Puro. */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Turnos → HTML de diálogo (`<p><b>Papel:</b> fala</p>`). Puro. */
fun turnsToHtml(turns: List<PasteTurn>): String =
    turns.joinToString("") { t ->
        "<p><b>${escapeHtml(t.speaker)}:</b> ${escapeHtml(t.text)}</p>"
    }

/** Texto corrido → parágrafos HTML. Puro. */
fun paragraphsToHtml(text: String): String =
    text.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("") { "<p>${escapeHtml(it)}</p>" }

/** Tipo de nota do part V&M (TALK/DEMO → tipos curtos do domínio). Puro. */
internal fun discourseTypeFor(part: VmPart): DiscourseType = when {
    part.nature == VmNature.DEMO -> DiscourseType.MINISTRY_PART
    part.section == VmSection.TREASURES -> DiscourseType.TREASURES_TALK
    part.section == VmSection.MINISTRY -> DiscourseType.MINISTRY_PART
    else -> DiscourseType.AVULSO
}

/** Normaliza espaços/quebras do texto puro. Puro. */
internal fun normalizePastedText(text: String): String =
    text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

/** Título amigável do caso genérico: 1ª linha não vazia (cap 80). Puro. */
internal fun pastedTitleOf(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.take(80)?.trimEnd().orEmpty().ifBlank { "Nota colada" }

/**
 * Constrói a [PastedNote] a partir do texto colado (detecção V&M → TALK/DEMO;
 * senão genérico). Puro/testável.
 */
fun buildPastedNote(text: String): PastedNote {
    val trimmed = text.trim()
    val parts = detectVmMaterial(trimmed)
    val talk = parts.firstOrNull { it.nature == VmNature.TALK }
    if (talk != null) {
        val body = talk.body.ifBlank { trimmed }
        return PastedNote(
            title = talk.title.ifBlank { "Discurso" },
            discourseType = discourseTypeFor(talk),
            plainText = normalizePastedText(body),
            bodyHtml = paragraphsToHtml(body),
            bodyTitle = talk.title.ifBlank { "Discurso" },
            bodyMinutes = talk.minutes.takeIf { it > 0 } ?: 10,
        )
    }
    val demo = parts.firstOrNull { it.nature == VmNature.DEMO }
    if (demo != null) {
        val body = demo.body.ifBlank { trimmed }
        val turns = splitDemonstrationTurns(body)
        val isDialogue = turns.size >= 2 &&
            turns.map { it.speaker.lowercase() }.distinct().size >= 2
        // Diálogo: descrição = texto antes do 1º turno (cenário/instrução).
        // Sem diálogo válido: o texto inteiro é a descrição da parte.
        val description = if (isDialogue) {
            val idx = body.indexOf("${turns.first().speaker}:")
            if (idx > 0) body.substring(0, idx).trim() else ""
        } else {
            body
        }
        val partTitle = demo.title.ifBlank { "Demonstração" }
        val minutes = demo.minutes.takeIf { it > 0 } ?: 4
        return PastedNote(
            // Título da NOTA: parte + duração ("Cultivando o interesse (4 min)").
            title = "$partTitle ($minutes min)",
            discourseType = DiscourseType.MINISTRY_PART,
            plainText = normalizePastedText(
                description.ifBlank {
                    turns.joinToString(" ") { "${it.speaker}: ${it.text}" }
                }
            ),
            // Corpo: só o diálogo (vazio quando não há turnos) — a descrição
            // vira o título do card da seção.
            bodyHtml = if (isDialogue) turnsToHtml(turns) else "",
            // Título do CARD da seção: a descrição (cenário/instrução).
            bodyTitle = description.ifBlank { partTitle },
            bodyMinutes = minutes,
        )
    }
    // Genérico: nota simples com o texto no corpo (sem seção).
    return PastedNote(
        title = pastedTitleOf(trimmed),
        discourseType = DiscourseType.S34_DISCOURSE,
        plainText = normalizePastedText(trimmed),
        bodyHtml = null,
        bodyTitle = "",
        bodyMinutes = 0,
    )
}
