package com.bettertalker.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.bettertalker.app.data.publication.PublicationReferenceOccurrence
import java.net.URLEncoder

/** Blocos markdown (títulos, N/I, listas, checklist, citação). Compartilhado editor/chat. */
sealed interface MdBlock {
    data class Title(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** [number] != null = item de lista ordenada (o número é preservado). */
    data class Bullet(
        val text: String,
        val number: Int? = null,
        /** #22-fix-b: nível da lista (0 = topo; 1 = sub; 2 = sub-sub). */
        val depth: Int = 0,
    ) : MdBlock
    data class Check(
        val done: Boolean,
        val text: String,
        /** #22-fix-b: nível do checklist (0 = topo). */
        val depth: Int = 0,
    ) : MdBlock
    data class Para(val text: String) : MdBlock
    /** #21-fix: trecho pronto para aplicar no card (`<texto-do-card>`). */
    data class TextForCard(val text: String) : MdBlock
    /** T4 — `---` vira linha divisória (antes renderizava literal). */
    data object Divider : MdBlock
}

// #21-fix: marcadores do bloco aplicável (ASCII, nomes exatos).
const val TEXT_FOR_CARD_OPEN = "<texto-do-card>"
const val TEXT_FOR_CARD_CLOSE = "</texto-do-card>"

private val CHECK_RE = Regex("^-[ \\t]*\\[([ xX])\\][ \\t]*(.*)$")
private val ORDERED_RE = Regex("^(?:\\*\\*\\s*)?(\\d{1,3})[.)](?:\\*\\*)?\\s+(.*)$")
private val HR_RE = Regex("^-{3,}$")

fun parseBlocks(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    val card = StringBuilder()
    var inCard = false
    fun flush() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out += MdBlock.Para(t)
        para.clear()
    }
    fun flushCard() {
        val t = card.toString().trim()
        if (t.isNotEmpty()) out += MdBlock.TextForCard(t)
        card.clear()
        inCard = false
    }
    fun appendRest(rest: String) {
        if (rest.isNotEmpty()) {
            if (para.isNotEmpty()) para.append(' ')
            para.append(rest)
        }
    }
    md.lines().forEach { raw ->
        val line = raw.trimEnd()
        // #21-fix: <texto-do-card>…</texto-do-card> — bloco próprio na posição
        // do texto (abre/fecha na mesma linha ou em linhas separadas).
        if (!inCard) {
            val openIdx = line.indexOf(TEXT_FOR_CARD_OPEN)
            if (openIdx >= 0) {
                flush()
                val after = line.substring(openIdx + TEXT_FOR_CARD_OPEN.length)
                val closeIdx = after.indexOf(TEXT_FOR_CARD_CLOSE)
                if (closeIdx >= 0) {
                    card.append(after.substring(0, closeIdx))
                    flushCard()
                    appendRest(after.substring(closeIdx + TEXT_FOR_CARD_CLOSE.length).trim())
                } else {
                    inCard = true
                    card.append(after)
                }
                return@forEach
            }
        } else {
            val closeIdx = line.indexOf(TEXT_FOR_CARD_CLOSE)
            if (closeIdx >= 0) {
                card.append('\n').append(line.substring(0, closeIdx))
                flushCard()
                appendRest(line.substring(closeIdx + TEXT_FOR_CARD_CLOSE.length).trim())
            } else {
                card.append('\n').append(line)
            }
            return@forEach
        }
        if (line.isBlank()) { flush(); return@forEach }
        // #22-fix-b: a indentação (contada ANTES do trimStart) define a
        // profundidade da lista — 2 espaços = sub, 4+ = sub-sub; tab = 2.
        val indentWidth = line.takeWhile { it == ' ' || it == '\t' }
            .replace("\t", "  ").length
        val depth = when {
            indentWidth >= 4 -> 2
            indentWidth >= 2 -> 1
            else -> 0
        }
        val t = line.trimStart()
        when {
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out += MdBlock.Title(level, t.drop(level).trim())
            }
            HR_RE.matches(t) -> { flush(); out += MdBlock.Divider }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.drop(1).trim()) }
            CHECK_RE.matches(t) -> {
                flush()
                val m = CHECK_RE.find(t)!!
                out += MdBlock.Check(m.groupValues[1].lowercase() == "x", m.groupValues[2], depth)
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                flush(); out += MdBlock.Bullet(t.drop(2), null, depth)
            }
            ORDERED_RE.matches(t) -> {
                // T2: preserva o número (lista ordenada), em vez de virar "•".
                // #25-fix-b: o LLM às vezes embrulha o número em `**` ("**1. ...")
                // — o regex engole o `**` da abertura. Se o fechamento:
                //   • sobrou no FIM do item → removido (bold vazaria);
                //   • fechou NO MEIO ("**2. Título.** resto") → o `**` é
                //     reaberto no início para envolver o título (preserva a
                //     ênfase e não deixa o resto do item em negrito).
                flush()
                val m = ORDERED_RE.find(t)!!
                var text = m.groupValues[2].trim()
                if (t.startsWith("**")) {
                    text = when {
                        text.endsWith("**") -> text.dropLast(2).trim()
                        text.contains("**") -> "**$text"
                        else -> text
                    }
                }
                out += MdBlock.Bullet(
                    text,
                    m.groupValues[1].toIntOrNull(),
                    depth,
                )
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    // #21-fix: tag aberta sem fechar (stream/corte) — emite o conteúdo mesmo
    // assim (o texto nunca some).
    if (inCard) flushCard()
    flush()
    return out
}

/** UX-REF-1A2 — link bíblico inline já mapeado para o texto-fonte. */
data class InlineBibleLink(
    val start: Int,
    val endExclusive: Int,
    val tag: String,
    val label: String,
)

/** UX-REF-1B.3 — link de publicação inline já mapeado para o texto-fonte. */
data class PublicationDisplayLink(
    val start: Int,
    val endExclusive: Int,
    /** Identidade estável dentro da mensagem renderizada (índice do bloco : ocorrência). */
    val id: String,
    /** Ocorrência íntegra (inclusive validationStart/End); nunca é recomposta no clique. */
    val occurrence: PublicationReferenceOccurrence,
    /** Texto renderizado do bloco que originou os offsets — usado na revalidação. */
    val sourceText: String,
    /** Índice do bloco de origem (redundante com [id], mas explícito/testável). */
    val blockIndex: Int = 0,
    /** Índice da ocorrência dentro do bloco (redundante com [id]). */
    val occurrenceIndex: Int = 0,
)

/** Scheme próprio, sem colisão com `bettertalker-bible://`. */
const val PUBLICATION_LINK_URI_PREFIX = "bettertalker-publication://open?i="

/** URI emitida nos spans; o id é codificado para sobreviver ao UriHandler. */
internal fun publicationLinkUri(id: String): String =
    PUBLICATION_LINK_URI_PREFIX + URLEncoder.encode(id, "UTF-8")

/**
 * Mapeia ocorrências detectadas em [sourceText] para links exibíveis.
 *
 * Os offsets são relativos ao texto renderizado do bloco; [sourceText] é a
 * mesma string usada na detecção, preservando o contrato B2 entre span visual
 * e trecho de validação.
 */

/**
 * Validação pura e reutilizável de um span de publicação contra o texto-fonte.
 * Mesmas invariantes exigidas por PublicationReferenceResolver.validatedDetection():
 *  - span dentro dos limites;
 *  - texto no intervalo idêntico a [raw];
 *  - offsets de validação ambos presentes ou ambos ausentes;
 *  - validationStart == start;
 *  - validationEndExclusive > endExclusive;
 *  - validationEndExclusive <= sourceText.length.
 */
internal fun isValidPublicationSpan(
    start: Int,
    endExclusive: Int,
    raw: String,
    validationStart: Int?,
    validationEndExclusive: Int?,
    sourceText: String,
): Boolean {
    if (raw.isBlank()) return false
    if (start < 0 || endExclusive <= start) return false
    if (endExclusive > sourceText.length) return false
    if (sourceText.substring(start, endExclusive) != raw) return false
    if (validationStart != null || validationEndExclusive != null) {
        if (validationStart == null || validationEndExclusive == null) return false
        if (validationStart != start) return false
        if (validationEndExclusive <= endExclusive) return false
        if (validationEndExclusive > sourceText.length) return false
    }
    return true
}

internal fun publicationDisplayLinks(
    sourceText: String,
    occurrences: List<PublicationReferenceOccurrence>,
    blockIndex: Int = 0,
): List<PublicationDisplayLink> = occurrences.mapIndexedNotNull { index, occurrence ->
    if (!isValidPublicationSpan(
            occurrence.start,
            occurrence.endExclusive,
            occurrence.raw,
            occurrence.validationStart,
            occurrence.validationEndExclusive,
            sourceText,
        )
    ) return@mapIndexedNotNull null
    PublicationDisplayLink(
        start = occurrence.start,
        endExclusive = occurrence.endExclusive,
        id = "$blockIndex:$index",
        occurrence = occurrence,
        sourceText = sourceText,
        blockIndex = blockIndex,
        occurrenceIndex = index,
    )
}

/**
 * T3 — só domínios oficiais (jw.org e subdomínios, ex.: wol.jw.org) são
 * clicáveis. Puro/testável.
 */
internal fun isJwUrl(url: String): Boolean {
    val host = url.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringBefore(':')
    return host == "jw.org" || host.endsWith(".jw.org")
}

@Composable
fun inline(
    text: String,
    bibleLinks: List<InlineBibleLink> = emptyList(),
    publicationLinks: List<PublicationDisplayLink> = emptyList(),
    leading: androidx.compose.ui.text.AnnotatedString? = null,
): androidx.compose.ui.text.AnnotatedString {
    // Cores lidas fora do remember (leitura composable não pode viver no lambda).
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.tertiary
    return remember(text, codeBackground, linkColor, bibleLinks, publicationLinks, leading) {
        buildInline(
            text = text,
            codeBackground = codeBackground,
            linkColor = linkColor,
            leading = leading,
            bibleLinks = bibleLinks,
            publicationLinks = publicationLinks,
        )
    }
}

/**
 * T2/T3/T4 — constrói o texto com ênfase (`**` negrito, `*`/`_` itálico),
 * `código` em monoespaçado (com fundo [codeBackground]) e links markdown
 * `[texto](url)` (clicáveis apenas para jw.org; outros domínios ficam
 * sublinhados e inertes). UX-REF-1A2: [bibleLinks] usam spans do texto-fonte
 * e viram links; UX-REF-1B.3: [publicationLinks] usam o mesmo mecanismo com
 * URI própria (publicação vence em:start igual). [leading] entra antes do
 * texto sem deslocar os spans porque os links são aplicados na emissão.
 * Puro/testável.
 */
fun buildInline(
    text: String,
    codeBackground: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    leading: androidx.compose.ui.text.AnnotatedString? = null,
    bibleLinks: List<InlineBibleLink> = emptyList(),
    publicationLinks: List<PublicationDisplayLink> = emptyList(),
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    leading?.let { append(it) }
    var i = 0
    var bold = false
    var italic = false
    var mono = false
    val buf = StringBuilder()
    fun linkStyles() = if (linkColor.isSpecified) {
        TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        )
    } else {
        null
    }
    fun appendWithLink(
        s: String,
        bible: InlineBibleLink? = null,
        publication: PublicationDisplayLink? = null,
    ) {
        if (bible == null && publication == null) {
            append(s)
            return
        }
        // Mesma posição: publicação vence (o detector já exclui spans bíblicos).
        val url = if (publication != null) {
            publicationLinkUri(publication.id)
        } else {
            "bettertalker-bible://open?ref=${bible!!.tag}"
        }
        withLink(LinkAnnotation.Url(url, linkStyles())) { append(s) }
    }
    fun emitStyled(
        s: String,
        bible: InlineBibleLink? = null,
        publication: PublicationDisplayLink? = null,
    ) {
        if (s.isEmpty()) return
        when {
            mono -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            ) { appendWithLink(s, bible, publication) }
            bold && italic -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { appendWithLink(s, bible, publication) }
            bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendWithLink(s, bible, publication) }
            italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendWithLink(s, bible, publication) }
            else -> appendWithLink(s, bible, publication)
        }
    }
    fun emit() {
        if (buf.isEmpty()) return
        val s = buf.toString()
        buf.clear()
        emitStyled(s)
    }
    // Validação defensiva + identidade literal: um link só é aplicado quando
    // o span corresponde exatamente à ocorrência e ao texto-fonte. Spans
    // inválidos viram texto comum (nunca link para conteúdo errado).
    val validBibleLinks = bibleLinks.filter { link ->
        link.start >= 0 && link.endExclusive > link.start && link.endExclusive <= text.length
    }
    val validPublicationLinks = publicationLinks.filter { link ->
        link.sourceText == text &&
            isValidPublicationSpan(
                link.start,
                link.endExclusive,
                link.occurrence.raw,
                link.occurrence.validationStart,
                link.occurrence.validationEndExclusive,
                text,
            )
    }

    // Resolução de conflitos (pura, determinística):
    //  - spans disjuntos: ambos preservados.
    //  - spans sobrepostos (inclusive idênticos) ou com mesmo id: descartar
    //    os conflitantes (conservador, sem precedência automática).
    //  - sem links aninhados.
    val droppedBible = mutableSetOf<InlineBibleLink>()
    val droppedPublication = mutableSetOf<PublicationDisplayLink>()
    val allSpans = validPublicationLinks.map { Triple(it.start, it.endExclusive, it) } +
        validBibleLinks.map { Triple(it.start, it.endExclusive, it) }
    for (a in allSpans) {
        for (b in allSpans) {
            if (a === b) continue
            val aStart = a.first; val aEnd = a.second
            val bStart = b.first; val bEnd = b.second
            val overlaps = aStart < bEnd && bStart < aEnd
            val sameId = a.third is PublicationDisplayLink && b.third is PublicationDisplayLink &&
                (a.third as PublicationDisplayLink).id == (b.third as PublicationDisplayLink).id
            if (overlaps || sameId) {
                when (a.third) {
                    is PublicationDisplayLink -> droppedPublication += a.third as PublicationDisplayLink
                    is InlineBibleLink -> droppedBible += a.third as InlineBibleLink
                }
                when (b.third) {
                    is PublicationDisplayLink -> droppedPublication += b.third as PublicationDisplayLink
                    is InlineBibleLink -> droppedBible += b.third as InlineBibleLink
                }
            }
        }
    }

    val allowedPublicationLinks = validPublicationLinks.filter { it !in droppedPublication }
    val allowedBibleLinks = validBibleLinks.filter { it !in droppedBible }

    val bibleLinksByStart = allowedBibleLinks.associateBy { it.start }
    val publicationLinksByStart = allowedPublicationLinks.associateBy { it.start }
    while (i < text.length) {
        val publicationLink = publicationLinksByStart[i]
        val bibleLink = bibleLinksByStart[i]
        if (publicationLink != null || bibleLink != null) {
            emit()
            val end = if (publicationLink != null) publicationLink.endExclusive else bibleLink!!.endExclusive
            emitStyled(
                text.substring(i, end),
                bible = if (publicationLink == null) bibleLink else null,
                publication = publicationLink,
            )
            i = end
            continue
        }
        when {
            // T3: link markdown [texto](url) — tolera espaço entre ] e (.
            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                var paren = close + 1
                while (close > 0 && paren < text.length && text[paren] == ' ') paren++
                val urlEnd = if (close > 0 && paren < text.length && text[paren] == '(') {
                    text.indexOf(')', paren + 1)
                } else -1
                if (close > 0 && urlEnd > paren) {
                    emit()
                    val label = text.substring(i + 1, close)
                    val url = text.substring(paren + 1, urlEnd)
                    if (isJwUrl(url)) {
                        // P1: estilo explícito (cor + sublinhado) quando informado.
                        withLink(LinkAnnotation.Url(url, linkStyles())) {
                            append(label)
                        }
                    } else {
                        // Domínio não oficial: sublinhado, porém não clicável.
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                            append(label)
                        }
                    }
                    i = urlEnd + 1
                } else {
                    buf.append(text[i]); i += 1
                }
            }
            text.startsWith("**", i) -> {
                // Parcial: só abre negrito quando o fechamento já chegou.
                // Se já está aberto, este é o fechamento e sempre fecha.
                if (bold || text.indexOf("**", i + 2) >= 0) {
                    emit()
                    bold = !bold
                }
                i += 2
            }
            text[i] == '*' || text[i] == '_' -> {
                if (italic || text.indexOf(text[i], i + 1) >= 0) {
                    emit()
                    italic = !italic
                }
                i += 1
            }
            text[i] == '`' -> {
                if (mono || text.indexOf('`', i + 1) >= 0) {
                    emit()
                    mono = !mono
                }
                i += 1
            }
            else -> { buf.append(text[i]); i += 1 }
        }
    }
    emit()
}
