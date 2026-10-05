package com.bettertalker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Blocos markdown (títulos, N/I, listas, checklist, citação). Compartilhado editor/chat. */
sealed interface MdBlock {
    data class Title(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** [number] != null = item de lista ordenada (o número é preservado). */
    data class Bullet(val text: String, val number: Int? = null) : MdBlock
    data class Check(val done: Boolean, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
}

private val CHECK_RE = Regex("^-[ \\t]*\\[([ xX])\\][ \\t]*(.*)$")
private val ORDERED_RE = Regex("^(\\d{1,3})[.)]\\s+(.*)$")

fun parseBlocks(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out += MdBlock.Para(t)
        para.clear()
    }
    md.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.isBlank()) { flush(); return@forEach }
        val t = line.trimStart()
        when {
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out += MdBlock.Title(level, t.drop(level).trim())
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.drop(1).trim()) }
            CHECK_RE.matches(t) -> {
                flush()
                val m = CHECK_RE.find(t)!!
                out += MdBlock.Check(m.groupValues[1].lowercase() == "x", m.groupValues[2])
            }
            t.startsWith("- ") || t.startsWith("* ") -> { flush(); out += MdBlock.Bullet(t.drop(2)) }
            ORDERED_RE.matches(t) -> {
                // T2: preserva o número (lista ordenada), em vez de virar "•".
                flush()
                val m = ORDERED_RE.find(t)!!
                out += MdBlock.Bullet(
                    m.groupValues[2].trim(),
                    m.groupValues[1].toIntOrNull()
                )
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    flush()
    return out
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
fun inline(text: String) = remember(text) { buildInline(text) }

/**
 * T2/T3 — constrói o texto com ênfase (`**` negrito, `*`/`_` itálico),
 * `código` em monoespaçado e links markdown `[texto](url)` (clicáveis apenas
 * para jw.org; outros domínios ficam sublinhados e inertes). Puro/testável.
 */
fun buildInline(text: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    var italic = false
    var mono = false
    val buf = StringBuilder()
    fun emit() {
        if (buf.isEmpty()) return
        val s = buf.toString()
        buf.clear()
        when {
            mono -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s) }
            bold && italic -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(s) }
            bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s) }
            italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s) }
            else -> append(s)
        }
    }
    while (i < text.length) {
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
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                                append(label)
                            }
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
            text.startsWith("**", i) -> { emit(); bold = !bold; i += 2 }
            text[i] == '*' || text[i] == '_' -> { emit(); italic = !italic; i += 1 }
            text[i] == '`' -> { emit(); mono = !mono; i += 1 }
            else -> { buf.append(text[i]); i += 1 }
        }
    }
    emit()
}
