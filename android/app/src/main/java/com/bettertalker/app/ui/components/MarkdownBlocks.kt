package com.bettertalker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Blocos markdown (títulos, N/I, listas, checklist, citação). Compartilhado editor/chat. */
sealed interface MdBlock {
    data class Title(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Check(val done: Boolean, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
}

private val CHECK_RE = Regex("^-[ \\t]*\\[([ xX])\\][ \\t]*(.*)$")

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
            t.matches(Regex("\\d+[.)] .*")) -> {
                flush(); out += MdBlock.Bullet(t.substringAfter(' ').substringAfter('.').trim())
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
fun inline(text: String) = remember(text) {
    buildAnnotatedString {
        var i = 0
        var bold = false
        var italic = false
        val buf = StringBuilder()
        fun emit() {
            if (buf.isEmpty()) return
            val s = buf.toString()
            buf.clear()
            when {
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
                text[i] == '`' -> { emit(); italic = !italic; i += 1 }
                else -> { buf.append(text[i]); i += 1 }
            }
        }
        emit()
    }
}
