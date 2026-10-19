package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Fase 1 "Colar e Pronto" + Fase A (cérebro único).
 *
 * [planPaste] decide o destino de qualquer texto colado (Home, Editor, Chat):
 * S-34, V&M Discurso, V&M Demo, rejeição V&M ou texto livre. A estrutura de
 * cards, as refs e o [GenerationProfile] saem daqui — não há mais um builder
 * para a Home e outro para o Editor.
 *
 * Decisões do Discovery: contentHtml no MVP, splitter flexível, sem geração
 * automática (o texto colado É o conteúdo). Puro/testável — sem IO.
 */

/** Subtópico da linha de raciocínio (uma afirmação do corpo V&M). */
data class PastedSubPoint(
    val text: String,
    val bibleRefs: List<String> = emptyList(),
    val publicationRefs: List<PublicationRef> = emptyList(),
)

/** Uma seção pronta do "Colar e Pronto". */
data class PastedSection(
    val role: SectionRole,
    val title: String,
    val minutes: Int,
    val contentHtml: String,
    /** Fase A: refs extraídas do texto desta seção (paridade FAB/Editor). */
    val bibleRefs: List<String> = emptyList(),
    val publicationRefs: List<PublicationRef> = emptyList(),
    /**
     * V&M TALK: cada frase do corpo vira um subtópico da LINHA DE RACIOCÍNIO
     * (com as refs da própria frase).
     */
    val subPoints: List<PastedSubPoint> = emptyList(),
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
) {
    /** Refs agregadas das seções (conveniência p/ testes e feedback). */
    val bibleRefs: List<String> get() = sections.flatMap { it.bibleRefs }.distinct()
    val publicationRefs: List<PublicationRef>
        get() = sections.flatMap { it.publicationRefs }.distinct()
}

/** Destino único de uma colagem (Fase A). */
sealed interface PasteKind {
    data class S34(val outline: ParsedOutline) : PasteKind
    data class VmTalk(val part: VmPart, val ignored: List<VmPart>) : PasteKind
    data class VmDemo(val part: VmPart, val ignored: List<VmPart>) : PasteKind
    data class VmConsideration(
        val part: VmPart,
        val ignored: List<VmPart>,
    ) : PasteKind
    data class VmRejected(val parts: List<VmPart>) : PasteKind
    data object FreeText : PasteKind
}

/**
 * Plano completo da colagem — a única fonte para estrutura de cards, refs,
 * persona de geração e feedback. Puro/testável.
 */
data class PastePlan(
    val kind: PasteKind,
    val noteTitle: String,
    val discourseType: DiscourseType,
    val sections: List<PastedSection>,
    val plainText: String,
    val bibleRefs: List<String>,
    val publicationRefs: List<PublicationRef>,
    val generationProfile: GenerationProfile,
    val notice: String?,
    val ignoredParts: List<VmPart> = emptyList(),
)

/**
 * Resumo puro da colagem para a `PastePreviewSheet` (Fase B).
 * Não inclui destino (é decisão da tela); não faz IO.
 */
data class PastePreview(
    val kindLabel: String,
    val detailLines: List<String>,
    val cardsLabel: String,
    val ignoredParts: List<VmPart>,
    val ignoredLabel: String?,
    val canApply: Boolean,
    val rejectionMessage: String?,
)

/** Monta o resumo de UI a partir do plano (S34/TALK/DEMO/Consideração/etc.). */
fun pastePreviewOf(plan: PastePlan): PastePreview {
    val kind = plan.kind
    val kindLabel = when (kind) {
        is PasteKind.S34 -> "Esboço S-34"
        is PasteKind.VmTalk -> "V&M · Discurso"
        is PasteKind.VmDemo -> "V&M · Demonstração"
        is PasteKind.VmConsideration -> "V&M · Consideração"
        is PasteKind.VmRejected -> "V&M · sem parte aplicável"
        PasteKind.FreeText -> "Texto livre"
    }

    val detailLines = mutableListOf<String>()
    when (kind) {
        is PasteKind.S34 -> {
            detailLines += "${kind.outline.sections.size} seções no esboço"
            kind.outline.totalMinutes?.let { detailLines += "Duração total: $it min" }
        }
        is PasteKind.VmTalk -> {
            detailLines += vmPartPreviewLines(kind.part)
            val subCount = plan.sections.sumOf { it.subPoints.size }
            if (subCount > 0) detailLines += "$subCount subtópicos na linha de raciocínio"
        }
        is PasteKind.VmDemo -> detailLines += vmPartPreviewLines(kind.part)
        is PasteKind.VmConsideration -> detailLines += vmPartPreviewLines(kind.part)
        is PasteKind.VmRejected ->
            detailLines += kind.parts.map { "Parte ${it.partNumber} · ${it.nature.label()}" }
        PasteKind.FreeText -> detailLines += "Sem V&M/S-34 detectado"
    }
    if (plan.bibleRefs.isNotEmpty()) {
        detailLines += "Bíblia: ${plan.bibleRefs.joinToString(", ")}"
    }
    if (plan.publicationRefs.isNotEmpty()) {
        detailLines += "Publicações: ${formatPublicationRefs(plan.publicationRefs)}"
    }

    val cardsLabel = when (kind) {
        is PasteKind.S34 -> "Introdução + ${kind.outline.sections.size} corpos + Conclusão"
        is PasteKind.VmTalk -> "3 cards (Introdução · Corpo · Conclusão)"
        is PasteKind.VmDemo -> "1 card (diálogo)"
        is PasteKind.VmConsideration -> "1 card (consideração)"
        is PasteKind.VmRejected -> "0 cards"
        PasteKind.FreeText -> "1 card (Corpo)"
    }
    val ignored = plan.ignoredParts
    val ignoredLabel = if (ignored.isEmpty()) {
        null
    } else {
        "${ignored.size} parte${if (ignored.size > 1) "s" else ""} " +
            "ignorada${if (ignored.size > 1) "s" else ""}"
    }
    return PastePreview(
        kindLabel = kindLabel,
        detailLines = detailLines,
        cardsLabel = cardsLabel,
        ignoredParts = ignored,
        ignoredLabel = ignoredLabel,
        canApply = kind !is PasteKind.VmRejected,
        rejectionMessage = if (kind is PasteKind.VmRejected) plan.notice else null,
    )
}

private fun vmPartPreviewLines(part: VmPart): List<String> = buildList {
    add(
        "Parte ${part.partNumber} · " +
            if (part.minutes > 0) "${part.minutes} min" else "duração não declarada"
    )
    part.title.takeIf { it.isNotBlank() }?.let { add("Título: $it") }
    part.partType?.takeIf { it.isNotBlank() }?.let { add("Tipo: $it") }
    part.scenario?.let {
        add("Cenário: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}")
    }
    part.reference?.let { add("Referência: $it") }
}

private fun formatPublicationRefs(refs: List<PublicationRef>): String =
    refs.joinToString("; ") { ref ->
        buildString {
            append(ref.symbol)
            ref.page?.let { append(" p. $it") }
            ref.paragraph?.let { append(" §$it") }
        }
    }

/**
 * Mensagem curta de feedback após aplicar (Fase B). `reviewInChat` é true
 * quando o Editor/Chat mantém o draft de S34/FreeText para revisão.
 */
fun pasteApplyFeedback(
    plan: PastePlan,
    reviewInChat: Boolean = false,
    currentNoteHasSections: Boolean = false,
): String {
    val kind = plan.kind
    if (kind is PasteKind.VmRejected) {
        return plan.notice ?: "Material V&M sem parte aplicável."
    }
    if (reviewInChat && (kind is PasteKind.S34 || kind == PasteKind.FreeText)) {
        return "Material enviado para revisão no chat."
    }
    val cards = when (kind) {
        is PasteKind.S34 -> kind.outline.sections.size + 2
        is PasteKind.VmTalk -> if (currentNoteHasSections) 1 else 3
        is PasteKind.VmDemo, is PasteKind.VmConsideration, PasteKind.FreeText -> 1
        is PasteKind.VmRejected -> 0
    }
    val feminine = kind is PasteKind.S34
    val noun = when {
        cards == 1 && feminine -> "seção"
        cards == 1 -> "card"
        feminine -> "seções"
        else -> "cards"
    }
    val action = when {
        currentNoteHasSections && feminine && cards == 1 -> "adicionada"
        currentNoteHasSections && feminine -> "adicionadas"
        currentNoteHasSections && cards == 1 -> "adicionado"
        currentNoteHasSections -> "adicionados"
        feminine && cards == 1 -> "criada"
        feminine -> "criadas"
        cards == 1 -> "criado"
        else -> "criados"
    }
    val ignored = if (plan.ignoredParts.isEmpty()) {
        ""
    } else {
        " · ${plan.ignoredParts.size} parte${if (plan.ignoredParts.size > 1) "s" else ""} " +
            "ignorada${if (plan.ignoredParts.size > 1) "s" else ""}"
    }
    return "$cards $noun $action$ignored"
}

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
    // Fase A: parte 6 "Discurso" NÃO é mais MINISTRY_PART (diálogo).
    part.section == VmSection.MINISTRY -> DiscourseType.MINISTRY_TALK
    else -> DiscourseType.AVULSO
}

/** Normaliza espaços/quebras do texto puro. Puro. */
internal fun normalizePastedText(text: String): String =
    text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

/** Título amigável do caso genérico: 1ª linha não vazia (cap 80). Puro. */
internal fun pastedTitleOf(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.take(80)?.trimEnd().orEmpty().ifBlank { "Nota colada" }

/** Sufixo de feedback para partes ignoradas (nunca descartar em silêncio). */
private fun ignoredSuffix(ignored: List<VmPart>): String =
    if (ignored.isEmpty()) "" else "\nIgnorei: " +
        ignored.joinToString("; ") { "parte ${it.partNumber} — ${it.nature.label()}" } + "."

/** Abreviações que não encerram frase quando seguidas de maiúscula. */
private val SUBTOPIC_ABBREVIATIONS = setOf(
    "sr", "sra", "dr", "dra", "prof", "etc", "ex", "cap", "pag", "p", "n", "v", "vv",
)

/**
 * Divide o corpo de uma parte V&M em frases/subtópicos. A âncora é a
 * pontuação final seguida de espaço + maiúscula (ou fecha parêntese de ref).
 * Evita quebrar refs como `Jer. 45:3`, `w25.07 22` e `p. 22`.
 * Puro/testável.
 */
fun splitVmSubtopics(body: String): List<PastedSubPoint> {
    val normalized = body.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return emptyList()
    val sentences = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < normalized.length) {
        val c = normalized[i]
        val isEnd = c in ".!?…" || c == ')' || c == '"' || c == '”'
        if (isEnd) {
            var j = i + 1
            while (j < normalized.length && normalized[j].isWhitespace()) j++
            if (j < normalized.length && normalized[j].isUpperCase() && isSubtopicBoundary(normalized, i)) {
                sentences += normalized.substring(start, i + 1).trim()
                start = j
                i = j
                continue
            }
        }
        i++
    }
    val last = normalized.substring(start).trim()
    if (last.isNotBlank()) sentences += last
    return sentences.map { sentence ->
        val (bible, pubs) = extractRefsFromBody(sentence)
        PastedSubPoint(sentence, bible, pubs)
    }
}

private fun isSubtopicBoundary(text: String, end: Int): Boolean {
    if (text[end] != '.') return true
    var k = end - 1
    while (k >= 0 && (text[k].isLetterOrDigit() || text[k] == '§')) k--
    val word = text.substring(k + 1, end).lowercase()
    return word !in SUBTOPIC_ABBREVIATIONS
}

/**
 * CÉREBRO ÚNICO da colagem: normaliza, classifica e devolve o plano completo.
 * Prioridade: TALK > DEMO > Rejected > S-34 > texto livre — a mesma do fluxo
 * do Editor/Chat, agora também no FAB.
 */
fun planPaste(raw: String): PastePlan {
    val text = PasteTextNormalizer.normalize(raw)
    val trimmed = text.trim()
    val parts = detectVmMaterial(trimmed)

    val talk = parts.firstOrNull { it.nature == VmNature.TALK }
    if (talk != null) {
        val ignored = parts - talk
        val body = talk.body.ifBlank { trimmed }
        val partTitle = talk.title.ifBlank { "Discurso" }
        val minutes = talk.minutes.takeIf { it > 0 } ?: 10
        val (bible, pubs) = extractRefsFromBody(body)
        val subtopics = splitVmSubtopics(body)
        val sections = listOf(
            PastedSection(SectionRole.INTRO, "Introdução", EDGE_SECTION_MINUTES, ""),
            PastedSection(
                role = SectionRole.BODY,
                title = partTitle,
                minutes = minutes,
                // O corpo colado é a LINHA DE RACIOCÍNIO (subtópicos); o
                // MINI DISCURSO nasce vazio para ser gerado a partir dela.
                contentHtml = "",
                bibleRefs = bible,
                publicationRefs = pubs,
                subPoints = subtopics,
            ),
            PastedSection(SectionRole.CONCLUSION, "Conclusão", EDGE_SECTION_MINUTES, ""),
        )
        return PastePlan(
            kind = PasteKind.VmTalk(talk, ignored),
            noteTitle = "$partTitle ($minutes min)",
            discourseType = discourseTypeFor(talk),
            sections = sections,
            plainText = normalizePastedText(body),
            bibleRefs = bible,
            publicationRefs = pubs,
            generationProfile = GenerationProfile.SPEECH_MENTOR,
            notice = "Estrutura de discurso criada (Introdução, Desenvolvimento, " +
                "Conclusão)." + ignoredSuffix(ignored),
            ignoredParts = ignored,
        )
    }

    val demo = parts.firstOrNull { it.nature == VmNature.DEMO }
    if (demo != null) {
        val ignored = parts - demo
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
        val (bible, pubs) = extractRefsFromBody(body.ifBlank { demo.title })
        val sections = listOf(
            PastedSection(
                role = SectionRole.BODY,
                // Título do CARD: a descrição (cenário/instrução); o corpo
                // guarda só o diálogo (vazio quando não há turnos).
                title = description.ifBlank { partTitle },
                minutes = minutes,
                contentHtml = if (isDialogue) turnsToHtml(turns) else "",
                bibleRefs = bible,
                publicationRefs = pubs,
            ),
        )
        return PastePlan(
            kind = PasteKind.VmDemo(demo, ignored),
            noteTitle = "$partTitle ($minutes min)",
            discourseType = DiscourseType.MINISTRY_PART,
            sections = sections,
            plainText = normalizePastedText(
                description.ifBlank {
                    turns.joinToString(" ") { "${it.speaker}: ${it.text}" }
                }
            ),
            bibleRefs = bible,
            publicationRefs = pubs,
            generationProfile = GenerationProfile.DEMO_SCRIPTWRITER,
            notice = "Demonstração reconhecida: parte ${demo.partNumber} " +
                "(${minutes} min)." + ignoredSuffix(ignored),
            ignoredParts = ignored,
        )
    }

    // Consideração (ex.: "O que você diria?"): UM card único, sem a estrutura
    // de Intro/Corpo/Conclusão do discurso. Persistida como AVULSO (single
    // BODY) para o gerador usar a persona de mentor, não o roteirista de demo.
    val consideration = parts.firstOrNull { it.nature == VmNature.CONSIDERATION }
    if (consideration != null) {
        val ignored = parts - consideration
        val body = consideration.body.ifBlank { trimmed }
        val partTitle = consideration.title.ifBlank { "Consideração" }
        val minutes = consideration.minutes.takeIf { it > 0 } ?: 5
        val (bible, pubs) = extractRefsFromBody(body)
        val sections = listOf(
            PastedSection(
                role = SectionRole.BODY,
                title = partTitle,
                minutes = minutes,
                contentHtml = paragraphsToHtml(body),
                bibleRefs = bible,
                publicationRefs = pubs,
            ),
        )
        return PastePlan(
            kind = PasteKind.VmConsideration(consideration, ignored),
            noteTitle = "$partTitle ($minutes min)",
            discourseType = DiscourseType.AVULSO,
            sections = sections,
            plainText = normalizePastedText(body),
            bibleRefs = bible,
            publicationRefs = pubs,
            generationProfile = GenerationProfile.SPEECH_MENTOR,
            notice = "Consideração reconhecida: parte ${consideration.partNumber} " +
                "(${minutes} min)." + ignoredSuffix(ignored),
            ignoredParts = ignored,
        )
    }

    // V&M reconhecido, mas sem TALK/DEMO/Consideração (Joias, Leitura,
    // Estudo): rejeição explícita — nunca criar nota genérica silenciosamente.
    if (parts.isNotEmpty()) {
        val (bible, pubs) = extractRefsFromBody(trimmed)
        return PastePlan(
            kind = PasteKind.VmRejected(parts),
            noteTitle = pastedTitleOf(trimmed),
            discourseType = DiscourseType.S34_DISCOURSE,
            sections = emptyList(),
            plainText = normalizePastedText(trimmed),
            bibleRefs = bible,
            publicationRefs = pubs,
            generationProfile = GenerationProfile.CHAT,
            notice = vmRejectionMessage(parts),
        )
    }

    // "Colar e Pronto" v3: esboço estruturado (S-34 ou texto com seções
    // temporizadas "(N min)") → o caller cria a nota + seções via
    // OutlineConverter (Introdução + corpos + Conclusão). Puro: só o parse.
    val parsed = OutlineParser.parse(trimmed, "texto colado")
    if (parsed.sections.size >= 2) {
        val (bible, pubs) = extractRefsFromBody(trimmed)
        return PastePlan(
            kind = PasteKind.S34(parsed),
            noteTitle = parsed.title.ifBlank { pastedTitleOf(trimmed) },
            discourseType = DiscourseType.S34_DISCOURSE,
            sections = emptyList(),
            plainText = normalizePastedText(trimmed),
            bibleRefs = bible,
            publicationRefs = pubs,
            generationProfile = GenerationProfile.SPEECH_MENTOR,
            notice = "Esboço reconhecido e estruturado!",
        )
    }

    // Genérico: nota simples — o texto vira uma seção BODY (visível/editável
    // no editor; antes o texto ficava só no mdText, invisível).
    val (bible, pubs) = extractRefsFromBody(trimmed)
    return PastePlan(
        kind = PasteKind.FreeText,
        noteTitle = pastedTitleOf(trimmed),
        discourseType = DiscourseType.S34_DISCOURSE,
        sections = if (trimmed.isBlank()) emptyList() else listOf(
            PastedSection(
                role = SectionRole.BODY,
                title = "Corpo",
                minutes = GENERIC_SECTION_MINUTES,
                contentHtml = paragraphsToHtml(trimmed),
                bibleRefs = bible,
                publicationRefs = pubs,
            ),
        ),
        plainText = normalizePastedText(trimmed),
        bibleRefs = bible,
        publicationRefs = pubs,
        generationProfile = GenerationProfile.CHAT,
        notice = null,
    )
}

/** Converte o plano no formato histórico consumido pelo HomeViewModel. */
fun PastePlan.toPastedNote(): PastedNote = when (val k = kind) {
    is PasteKind.S34 -> PastedNote(
        title = noteTitle,
        discourseType = discourseType,
        plainText = plainText,
        outline = k.outline,
    )
    is PasteKind.VmRejected -> PastedNote(
        title = noteTitle,
        discourseType = discourseType,
        plainText = plainText,
    )
    else -> PastedNote(
        title = noteTitle,
        discourseType = discourseType,
        plainText = plainText,
        sections = sections,
        speechTemplate = kind is PasteKind.VmTalk,
    )
}

/**
 * Constrói a [PastedNote] a partir do texto colado. Mantido como fachada do
 * [planPaste] para os consumidores/testes legados.
 */
fun buildPastedNote(text: String): PastedNote = planPaste(text).toPastedNote()
