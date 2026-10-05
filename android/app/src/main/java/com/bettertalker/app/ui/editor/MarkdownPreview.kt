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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.MdBlock
import com.bettertalker.app.ui.components.inline
import com.bettertalker.app.ui.components.parseBlocks

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
                is MdBlock.Divider -> androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

