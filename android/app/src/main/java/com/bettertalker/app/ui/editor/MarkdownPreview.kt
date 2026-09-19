package com.bettertalker.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Preview renderizado do Markdown (sem dependências): títulos, N/I, listas, checklist, citação. */
@Composable
fun MarkdownPreview(md: String, modifier: Modifier = Modifier) {
    if (md.isBlank()) {
        Text(
            "Nada para pré-visualizar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = modifier
        )
        return
    }
    val blocks = remember(md) { parseBlocks(md) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Title -> Text(
                    block.text, style = if (block.level <= 1) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                is MdBlock.Quote -> Row(
                    Modifier
                        .padding(vertical = 4.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                is MdBlock.Bullet -> Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Check -> Row(verticalAlignment = Alignment.Top) {
                    Text(
                        if (block.done) "☑  " else "☐  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (block.done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary
                    )
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Para -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

private sealed interface MdBlock {
    data class Title(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Check(val done: Boolean, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
}

private val CHECK_RE = Regex("^-[ \\t]*\\[([ xX])\\][ \\t]*(.*)$")

private fun parseBlocks(md: String): List<MdBlock> {
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
private fun inline(text: String) = remember(text) {
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
