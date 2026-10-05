package com.bettertalker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun inline(text: String) = remember(text) { buildInline(text) }

/**
 * T2 — constrói o texto com ênfase (`**` negrito, `*`/`_` itálico) e `código`
 * em monoespaçado. Puro/testável (a versão @Composable só memoriza).
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
            text.startsWith("**", i) -> { emit(); bold = !bold; i += 2 }
            text[i] == '*' || text[i] == '_' -> { emit(); italic = !italic; i += 1 }
            text[i] == '`' -> { emit(); mono = !mono; i += 1 }
            else -> { buf.append(text[i]); i += 1 }
        }
    }
    emit()
}
