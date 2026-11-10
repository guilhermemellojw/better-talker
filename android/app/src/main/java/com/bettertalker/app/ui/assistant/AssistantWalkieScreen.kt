package com.bettertalker.app.ui.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.TurnUiState
import com.bettertalker.app.data.voice.PttState
import com.bettertalker.app.ui.copilot.ChatMessageText
import com.bettertalker.app.ui.copilot.CopilotViewModel
import com.bettertalker.app.ui.copilot.MessageOrigin
import com.bettertalker.app.ui.copilot.PttMicButton

/**
 * v5.1 — walkie do Assistente com nota vinculada.
 *
 * A última fala completa do BT fica no topo, selecionável e scrollável.
 * O rodapé é só status/transcrição → PTT → “Segure para falar”.
 */
@Composable
fun AssistantWalkieScreen(
    noteTitle: String,
    practiceContext: PracticeSnapshot?,
    messages: List<CopilotViewModel.ChatItem>,
    activeTurn: TurnUiState?,
    pttState: PttState,
    pttPartial: String,
    ttsSpeaking: Boolean,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onPttCancel: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenNote: () -> Unit,
    onChangeNote: () -> Unit,
    onGeneralChat: () -> Unit,
    onOpenMyOutlines: () -> Unit = {},
    onCreateNote: () -> Unit = {},
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHelp: () -> Unit = {},
    onConfigureAi: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val lastResponse = remember(messages) {
        // Prefere a resposta real do Copilot; mensagens locais de navegação
        // ("Abrindo ponto 1…") não viram o texto principal.
        (messages.lastOrNull {
            !it.fromMe && it.kind == "text" && it.text.isNotBlank() &&
                it.origin != MessageOrigin.LOCAL
        } ?: messages.lastOrNull {
            !it.fromMe && it.kind == "text" && it.text.isNotBlank()
        })?.text
    }
    val practiceLine = remember(practiceContext) {
        walkiePracticeLine(practiceContext)
    }
    val bodyScroll = rememberScrollState()
    LaunchedEffect(lastResponse) {
        if (lastResponse != null) bodyScroll.scrollTo(0)
    }
    val status = walkieStatusLabel(
        pttState = pttState,
        turnPhase = activeTurn?.phase,
        ttsSpeaking = ttsSpeaking,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        AssistantTopBar(
            title = assistantTitle(hasNote = true, noteTitle = noteTitle),
            hasNote = true,
            onOpenChat = onOpenChat,
            onOpenHelp = onOpenHelp,
            onOpenSettings = onOpenSettings,
            onOpenNote = onOpenNote,
            onChangeNote = onChangeNote,
            onGeneralChat = onGeneralChat,
            onOpenMyOutlines = onOpenMyOutlines,
            onCreateNote = onCreateNote,
            titleStyle = MaterialTheme.typography.titleMedium,
        )
        practiceLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))

        // Texto completo da última fala: sem trim, selecionável e scrollável.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(bodyScroll),
        ) {
            ChatMessageText(lastResponse.orEmpty(), Modifier.fillMaxWidth())
        }

        if (assistantNeedsKey(lastResponse)) {
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = onConfigureAi,
                label = { Text("Configurar IA") },
            )
        }
        if (activeTurn?.error != null) {
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = onRetry,
                label = { Text("Tentar novamente") },
            )
        }

        Spacer(Modifier.height(8.dp))
        // Slot de estado fixo: no idle o texto é redundante com a dica abaixo
        // do microfone; nos estados ativos ele é a informação principal.
        Box(
            Modifier.height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (status != "Segure para falar") {
                Text(
                    status,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
