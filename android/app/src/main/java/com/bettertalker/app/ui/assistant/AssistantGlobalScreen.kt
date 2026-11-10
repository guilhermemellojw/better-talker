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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.voice.PttState
import com.bettertalker.app.ui.copilot.ChatMessageText
import com.bettertalker.app.ui.copilot.PttMicButton

private const val GLOBAL_GREETING =
    "Oi! Eu sou o BT. Segure para falar. Posso abrir um esboço, " +
        "tirar dúvidas sobre oratória ou ajudar a preparar sua parte."

/**
 * Home global do Assistente (v5.1): título/seletor de contexto, última fala
 * completa do BT no topo e PTT como única ação do rodapé.
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
    onPickNote: () -> Unit,
    onOpenMyOutlines: () -> Unit,
    onCreateNote: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPrepare: () -> Unit,
    onOpenTrain: () -> Unit,
    onOpenPresent: () -> Unit,
    onConfigureAi: () -> Unit,
    onHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastResponse = remember(turns) {
        turns.lastOrNull { !it.fromMe && it.text.isNotBlank() }
    }
    val content = lastResponse?.text
        ?: if (loading) "Carregando seus esboços…" else GLOBAL_GREETING
    val bodyScroll = rememberScrollState()
    LaunchedEffect(lastResponse?.text) {
        if (lastResponse != null) bodyScroll.scrollTo(0)
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
        AssistantTopBar(
            title = assistantTitle(hasNote = false, noteTitle = ""),
            hasNote = false,
            onOpenChat = onOpenChat,
            onOpenHelp = onHelp,
            onOpenSettings = onOpenSettings,
            onPickNote = onPickNote,
            onOpenMyOutlines = onOpenMyOutlines,
            onCreateNote = onCreateNote,
            onOpenLibrary = onOpenLibrary,
            onOpenPrepare = onOpenPrepare,
            onOpenTrain = onOpenTrain,
            onOpenPresent = onOpenPresent,
            titleStyle = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(10.dp))

        // Texto completo da última fala: sem trim, selecionável e scrollável.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(bodyScroll),
        ) {
            if (lastResponse != null) {
                ChatMessageText(lastResponse.text, Modifier.fillMaxWidth())
            } else {
                SelectionContainer {
                    Text(
                        content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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

        // CTA único do primeiro uso; o menu do título também cria um esboço.
        if (notes.isEmpty() && !loading) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onCreateNote) {
                Text("Criar primeiro esboço")
            }
        }

        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(12.dp))
    }
}
