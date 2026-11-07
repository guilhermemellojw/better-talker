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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.NextStepSuggestion
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.TurnUiState
import com.bettertalker.app.data.voice.PttState
import com.bettertalker.app.ui.copilot.CopilotViewModel
import com.bettertalker.app.ui.copilot.MessageOrigin
import com.bettertalker.app.ui.copilot.PttMicButton

/**
 * v4.0 — modo Walkie do Assistente.
 *
 * A ação principal é o PTT grande. O chat completo (histórico, fontes, cards,
 * sugestões e campo de texto) fica a um toque em “Ver conversa”/“Digitar”.
 * Não cria sessão, não persiste nada e não altera o prompt.
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
    nextStep: NextStepSuggestion?,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onPttCancel: () -> Unit,
    onOpenChat: () -> Unit,
    onDigit: () -> Unit,
    onOpenNote: () -> Unit,
    onChangeNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val lastResponse = remember(messages) {
        // Prefere a resposta real do Copilot; mensagens locais de navegação
        // ("Abrindo ponto 1…") não viram a prévia principal.
        (messages.lastOrNull {
            !it.fromMe && it.kind == "text" && it.text.isNotBlank() &&
                it.origin != MessageOrigin.LOCAL
        } ?: messages.lastOrNull {
            !it.fromMe && it.kind == "text" && it.text.isNotBlank()
        })?.let { walkiePreview(it.text) }
    }
    val practiceLine = remember(practiceContext) {
        walkiePracticeLine(practiceContext)
    }
    val status = walkieStatusLabel(
        pttState = pttState,
        turnPhase = activeTurn?.phase,
        ttsSpeaking = ttsSpeaking,
    )
    val nextStepLabel = walkieNextStepLabel(nextStep)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    noteTitle.ifBlank { "Assistente" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Mais opções")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Abrir nota") },
                        onClick = { menuOpen = false; onOpenNote() },
                    )
                    DropdownMenuItem(
                        text = { Text("Trocar nota") },
                        onClick = { menuOpen = false; onChangeNote() },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
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
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(12.dp))
        Text(
            "Segure para falar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pttPartial.isNotBlank()) {
            Text(
                "“$pttPartial”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        lastResponse?.let {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))

        nextStepLabel?.let {
            AssistChip(
                onClick = onOpenChat,
                label = { Text(it) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpenChat) { Text("Ver conversa") }
            OutlinedButton(onClick = onDigit) { Text("Digitar") }
        }
        TextButton(onClick = onChangeNote) { Text("Trocar nota") }
        Spacer(Modifier.height(12.dp))
    }
}
