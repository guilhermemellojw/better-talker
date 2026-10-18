package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Fase 1 "Colar e Pronto" (Home): transforma o texto colado numa nota pronta.
 *
 * - TALK (V&M detectado) → template de discurso: INTRO + BODY (o texto colado)
 *   + CONCLUSION vazias — abertura/fechamento prontos para preencher ou pedir
 *   ao Copilot;
 * - DEMO (V&M) → BODY com a descrição no título e o diálogo em turnos;
 * - Esboço estruturado (seções com "(N min)") → `outline` preenchido — o
 *   caller cria as seções via OutlineConverter;
 * - Genérico → BODY "Corpo" com o texto (nota visível/editável no editor).
 *
 * Decisões do Discovery: contentHtml no MVP, splitter flexível, sem geração
 * automática (o texto colado É o conteúdo). Puro/testável — sem IO.
 */

/** Uma seção pronta do "Colar e Pronto". */
data class PastedSection(
    val role: SectionRole,
    val title: String,
    val minutes: Int,
    val contentHtml: String,
)

/** Nota pronta construída a partir do texto colado. */
data class PastedNote(
    val title: String,
    val discourseType: DiscourseType,
    /** Texto puro (preview/busca da nota). */
    val plainText: String,
    /** Seções prontas (vazio = nota sem seção, ex.: texto em branco). */
    val sections: List<PastedSection> = emptyList(),
    /** TALK cru: template de discurso aplicado (aviso de criação na Home). */
    val speechTemplate: Boolean = false,
    /**
     * "Colar e Pronto" v3: esboço estruturado (S-34) detectado pelo
     * [OutlineParser] — o caller cria a nota + seções via OutlineConverter
     * (Introdução + corpos + Conclusão). Null = não é esboço.
     */
    val outline: ParsedOutline? = null,
)

/** Minutos dos cards vazios de abertura/fechamento (igual ao "Adicionar parte"). */
internal const val EDGE_SECTION_MINUTES = 1

/** Duração assumida para texto corrido sem tempo declarado (igual ao fallback do esboço). */
internal const val GENERIC_SECTION_MINUTES = 5

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
        val partTitle = talk.title.ifBlank { "Discurso" }
        val minutes = talk.minutes.takeIf { it > 0 } ?: 10
        return PastedNote(
            // Título da NOTA: parte + duração ("A ordem nos céus (10 min)").
            title = "$partTitle ($minutes min)",
            discourseType = discourseTypeFor(talk),
            plainText = normalizePastedText(body),
            // Template de discurso: abertura e fechamento vazios para o
            // orador (ou o Copilot) preencherem; o texto colado fica no
            // desenvolvimento. Minutos iguais ao "Adicionar parte" manual.
            sections = listOf(
                PastedSection(SectionRole.INTRO, "Introdução", EDGE_SECTION_MINUTES, ""),
                PastedSection(SectionRole.BODY, partTitle, minutes, paragraphsToHtml(body)),
                PastedSection(SectionRole.CONCLUSION, "Conclusão", EDGE_SECTION_MINUTES, ""),
            ),
            speechTemplate = true,
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
            sections = listOf(
                PastedSection(
                    role = SectionRole.BODY,
                    // Título do CARD: a descrição (cenário/instrução); o
                    // corpo guarda só o diálogo (vazio quando não há turnos).
                    title = description.ifBlank { partTitle },
                    minutes = minutes,
                    contentHtml = if (isDialogue) turnsToHtml(turns) else "",
                ),
            ),
        )
    }
    // "Colar e Pronto" v3: esboço estruturado (S-34 ou texto com seções
    // temporizadas "(N min)") → o caller cria a nota + seções via
    // OutlineConverter (Introdução + corpos + Conclusão). Puro: só o parse.
    val parsed = OutlineParser.parse(trimmed, "texto colado")
    if (parsed.sections.size >= 2) {
        return PastedNote(
            title = parsed.title.ifBlank { pastedTitleOf(trimmed) },
            discourseType = DiscourseType.S34_DISCOURSE,
            plainText = normalizePastedText(trimmed),
            outline = parsed,
        )
    }
    // Genérico: nota simples — o texto vira uma seção BODY (visível/editável
    // no editor; antes o texto ficava só no mdText, invisível).
    return PastedNote(
        title = pastedTitleOf(trimmed),
        discourseType = DiscourseType.S34_DISCOURSE,
        plainText = normalizePastedText(trimmed),
        sections = if (trimmed.isBlank()) emptyList() else listOf(
            PastedSection(
                SectionRole.BODY, "Corpo", GENERIC_SECTION_MINUTES,
                paragraphsToHtml(trimmed),
            ),
        ),
    )
}
