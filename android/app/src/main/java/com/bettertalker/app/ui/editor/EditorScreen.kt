package com.bettertalker.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.theme.NOTE_COLORS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onCopilot: () -> Unit, onAttach: () -> Unit) {
    val title by vm.title.collectAsState()
    val md by vm.md.collectAsState()
    val saving by vm.saving.collectAsState()
    val color by vm.color.collectAsState()
    val attachments by vm.attachments.collectAsState(initial = emptyList())
    var preview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                title = { Text(if (saving) "Salvando…" else "Salvo local") },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility, "Preview")
                    }
                    IconButton(onClick = onCopilot) { Icon(Icons.Default.AutoAwesome, "Copilot") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            // Título estilo Notes: grande, sem borda
            OutlinedTextField(
                value = title, onValueChange = vm::onTitle,
                placeholder = { Text("Título", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // Seletor de cor da nota
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NOTE_COLORS.forEach { c ->
                    val selected = color == c.value.toLong()
                    Box(
                        Modifier
                            .size(if (selected) 30.dp else 24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(
                                if (selected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape
                                ) else Modifier
                            )
                            .clickable { vm.setColor(c.value.toLong()) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!preview) {
                OutlinedTextField(
                    value = md, onValueChange = vm::onMd,
                    placeholder = { Text("Escreva aqui…") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(8.dp))
                // Toolbar de formatação sobre a seleção
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ToolBtn(Icons.Default.FormatBold, "Negrito") { vm.wrapSelection("**", "**", "negrito") }
                    ToolBtn(Icons.Default.FormatItalic, "Itálico") { vm.wrapSelection("*", "*", "itálico") }
                    ToolBtn(Icons.Default.Title, "Título") { vm.prefixLines("# ") }
                    ToolBtn(Icons.Default.FormatListBulleted, "Lista") { vm.prefixLines("- ") }
                    ToolBtn(Icons.Default.Checklist, "Checklist") { vm.prefixLines("- [ ] ") }
                    ToolBtn(Icons.Default.FormatQuote, "Citação") { vm.prefixLines("> ") }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MarkdownPreview(md.text)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onAttach) {
                    Icon(Icons.Default.AttachFile, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Anexos da nota (${attachments.size})")
                }
            }
            attachments.forEach { a ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "• ${a.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.unlinkAttachment(a.id) }) {
                        Icon(Icons.Default.Close, "Desvincular")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc) }
}
