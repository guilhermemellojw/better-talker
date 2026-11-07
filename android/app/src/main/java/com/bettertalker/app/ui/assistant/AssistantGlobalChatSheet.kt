package com.bettertalker.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.bettertalker.app.ui.copilot.ChatMessageText

/** Chat completo do assistente global (efêmero, sem cards/fontes de nota). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantGlobalChatSheet(
    turns: List<GlobalTurn>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onConfigureAi: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    focusComposer: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Chat sempre na mensagem mais recente (o histórico é efêmero e curto).
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.scrollToItem(turns.lastIndex)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        onSend(text)
    }

    LaunchedEffect(focusComposer) {
        if (focusComposer) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Conversa com o BT",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Fechar conversa")
                }
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(turns, key = { it.id }) { turn ->
                    if (turn.fromMe) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    turn.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            ChatMessageText(turn.text)
                            turn.action?.let { action ->
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
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Pensando…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Digite para o BT…") },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                IconButton(
                    onClick = { submit() },
                    enabled = input.isNotBlank() && !busy,
                ) {
                    Icon(Icons.Default.Send, "Enviar mensagem")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
