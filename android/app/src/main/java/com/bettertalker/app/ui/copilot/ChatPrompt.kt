package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.ByodNotice

/** MIMEs aceitos para importar esboço (DOCX, PDF, JWPUB genérico). */
val OUTLINE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/pdf",
    "application/octet-stream"
)

/**
 * Barra de prompt única (estilo Gemini, box-less): campo sem contorno,
 * + e Ferramentas à esquerda, enviar em círculo à direita.
 */
@Composable
fun ChatPromptBar(
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = input.isNotBlank() && !busy
    Surface(
        modifier = modifier.fillMaxWidth().imePadding(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("Pergunte…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Default.Add, "Anexar esboço")
                }
                IconButton(onClick = onTools) {
                    Icon(Icons.Default.Tune, "Ferramentas")
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            }
        }
    }
}

/** Legenda discreta sob o prompt. */
@Composable
fun ChatCaption(modifier: Modifier = Modifier) {
    Text(
        "100% offline • confira as citações",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(top = 2.dp)
    )
}

/** Sugestões do olá: só até a primeira mensagem do usuário (somem ao conversar). */
@Composable
fun ChatSuggestions(
    hasOutline: Boolean,
    onPick: (String) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    val texts = if (hasOutline) {
        listOf(
            "Ideias para a introdução",
            "Exemplo de introdução",
            "Desenvolva a introdução",
            "Resumir",
            "Quais refs faltam?",
            "Mostre as seções"
        )
    } else {
        listOf(
            "Resumir",
            "Quais refs faltam?"
        )
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasOutline) {
            item {
                AssistChip(onClick = onAttach, label = { Text("Anexar esboço") })
            }
        }
        items(texts) { text ->
            AssistChip(onClick = { onPick(text) }, label = { Text(text) })
        }
    }
}

/** Sheet do + : importar arquivo ou colar texto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AttachOption(
                icon = Icons.Default.UploadFile,
                title = "Importar esboço",
                desc = "DOCX, PDF ou JWPUB",
                onClick = onImport
            )
            AttachOption(
                icon = Icons.Default.ContentPaste,
                title = "Colar texto",
                desc = "Ex: ponto da apostila",
                onClick = onPaste
            )
            Spacer(Modifier.height(8.dp))
            ByodNotice()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private data class ToolItem(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val send: String?
)

/** Sheet Ferramentas: ações do Copilot + conversa (era o ⋮ da top bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit
) {
    val tools = listOf(
        ToolItem(Icons.Default.Summarize, "Resumir", "Resumo da nota com citações", "Resumir"),
        ToolItem(Icons.Default.Lightbulb, "Ideias", "Gerar para uma seção", "Ideias"),
        ToolItem(Icons.Default.FormatQuote, "Dar exemplo",
            "Modelo pronto seguindo as publicações", "Exemplo"),
        ToolItem(Icons.Default.Edit, "Desenvolver",
            "Rascunho redigido da seção", "Desenvolva"),
        ToolItem(Icons.Default.FactCheck, "Verificar referências",
            "Publicações citadas na nota", "Quais referências faltam?"),
        ToolItem(Icons.Default.Download, "Refs do esboço",
            "Baixar o que o esboço cita", "refs do esboço"),
        ToolItem(Icons.Default.FormatListBulleted, "Mostrar seções",
            "Listar e gerar por seção", "Mostre as seções"),
        ToolItem(Icons.Default.Sync, "Sincronizar",
            "Atualizar esboço pelo texto da nota", "Sincronizar esboço")
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Ferramentas", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            tools.forEach { t ->
                androidx.compose.material3.TextButton(onClick = { onSend(t.send!!) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Conversa", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = onClear) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, null,
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Limpar conversa", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
