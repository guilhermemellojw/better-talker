package com.bettertalker.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.voice.PttState
import com.bettertalker.app.ui.copilot.PttMicButton

/**
 * Home global do Assistente (v4.1): PTT como ação principal, saudação local,
 * última resposta e chips de esboços recentes — sem exigir nota aberta.
 */
@Composable
fun AssistantGlobalScreen(
    notes: List<GlobalNote>,
    loading: Boolean,
    turns: List<GlobalTurn>,
    busy: Boolean,
    pttState: PttState,
    pttPartial: String,
    ttsSpeaking: Boolean,
    pending: GlobalPending?,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onPttCancel: () -> Unit,
    onOpenChat: () -> Unit,
    onDigit: () -> Unit,
    onPickNote: () -> Unit,
    onBindNote: (String) -> Unit,
    onCreateFirst: () -> Unit,
    onConfigureAi: () -> Unit,
    onHelp: () -> Unit,
    onRetry: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val lastResponse = remember(turns) {
        turns.lastOrNull { !it.fromMe && it.text.isNotBlank() }
    }
    val preview = remember(lastResponse) {
        lastResponse?.let { walkiePreview(it.text, maxChars = 280) }
    }
    val status = when {
        busy -> "Pensando…"
        else -> walkieStatusLabel(pttState, null, ttsSpeaking)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Assistente",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Mais opções do Assistente")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Escolher esboço") },
                        onClick = { menuOpen = false; onPickNote() },
                    )
                    DropdownMenuItem(
                        text = { Text("Configurar IA") },
                        onClick = { menuOpen = false; onConfigureAi() },
                    )
                    DropdownMenuItem(
                        text = { Text("Ajuda") },
                        onClick = { menuOpen = false; onHelp() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val text = preview ?: if (loading) {
                "Carregando seus esboços…"
            } else {
                "Oi! Eu sou o BT. Segure para falar. Posso abrir um esboço, " +
                    "tirar dúvidas sobre oratória ou ajudar a preparar sua parte."
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        lastResponse?.action?.let { action ->
            Spacer(Modifier.height(6.dp))
            when (action) {
                GlobalTurnAction.CONFIGURE_AI -> AssistChip(
                    onClick = onConfigureAi,
                    label = { Text("Configurar IA") },
                )
                GlobalTurnAction.RETRY -> AssistChip(
                    onClick = onRetry,
                    label = { Text("Tentar novamente") },
                )
            }
        }
        if (pending != null) {
            Spacer(Modifier.height(6.dp))
            when (val p = pending) {
                is GlobalPending.ChooseNote -> LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(p.candidates.take(6)) { note ->
                        val index = p.candidates.indexOf(note) + 1
                        AssistChip(
                            onClick = { onSendText(index.toString()) },
                            label = {
                                Text(
                                    "$index) ${note.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onSendText("Sim") }, label = { Text("Sim") })
                    AssistChip(onClick = { onSendText("Não") }, label = { Text("Não") })
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            if (status != "Segure para falar") {
                Text(
                    status,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (pttPartial.isNotBlank()) {
            Text(
                "“$pttPartial”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BoxWithConstraints {
            val pttSize = walkiePttSizeDp(maxWidth.value).dp
            PttMicButton(
                state = pttState,
                onPress = onPttPress,
                onRelease = onPttRelease,
                onCancel = onPttCancel,
                size = pttSize,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Segure para falar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        if (notes.isEmpty() && !loading) {
            FilledTonalButton(onClick = onCreateFirst) {
                Text("Criar primeiro esboço")
            }
        } else if (notes.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes.take(6), key = { it.id }) { note ->
                    AssistChip(
                        onClick = { onBindNote(note.id) },
                        label = {
                            Text(
                                note.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpenChat) { Text("Ver conversa") }
            OutlinedButton(onClick = onDigit) { Text("Digitar") }
        }
        TextButton(onClick = onPickNote) { Text("Escolher esboço") }
        Spacer(Modifier.height(10.dp))
    }
}
