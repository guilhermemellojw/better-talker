package com.bettertalker.app.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bettertalker.app.domain.help.AssistantHelpContext
import com.bettertalker.app.domain.help.HelpAction
import com.bettertalker.app.domain.help.HelpEngine
import com.bettertalker.app.domain.help.HelpTopic

/** Rótulo curto da ação de um tópico. */
fun helpActionLabel(action: HelpAction): String = when (action) {
    HelpAction.OPEN_IMPORT -> "Importar esboço"
    HelpAction.OPEN_PASTE -> "Colar parte V&M"
    HelpAction.OPEN_CREATE -> "Criar do zero"
    HelpAction.OPEN_EDITOR -> "Abrir editor"
    HelpAction.OPEN_LIBRARY -> "Abrir Biblioteca"
    HelpAction.OPEN_MODEL -> "Abrir Modelo IA"
    HelpAction.OPEN_TRAIN -> "Abrir Treino"
    HelpAction.OPEN_PRESENT -> "Abrir Palco"
    HelpAction.OPEN_TRIBUNA -> "Abrir mapa mental"
    HelpAction.OPEN_VOICE -> "Abrir Voz"
    HelpAction.OPEN_ASSISTANT -> "Abrir Assistente"
    HelpAction.OPEN_HELP_TOPIC -> "Ver primeiros passos"
    HelpAction.NONE -> "Fechar"
}

/**
 * Sheet local de ajuda. Tudo vem do catálogo em memória; nenhuma chamada de
 * rede/LLM. “Perguntar ao BT” apenas entrega a navegação ao chat existente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(
    context: AssistantHelpContext,
    onDismiss: () -> Unit,
    onAction: (HelpAction) -> Unit,
    onAskBt: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onOpenUpgradeNotice: () -> Unit = {},
    onTopicAction: (HelpTopic) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val topics = remember(context, query) { HelpEngine.topicsFor(context, query) }
    val titleFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row {
                Text(
                    "Ajuda do BT",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(titleFocus)
                        .semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Fechar ajuda")
                }
            }
            Text(
                "Respostas curtas, sem chave e sem internet. Escolha uma ação para continuar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar ajuda…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAskBt) { Text("Perguntar ao BT") }
                TextButton(onClick = onReplayOnboarding) { Text("Rever primeiros passos") }
            }
            TextButton(onClick = onOpenUpgradeNotice) {
                Text("Novidades do 5.0")
            }
            if (topics.isEmpty()) {
                Text(
                    "Nada encontrado. Tente outras palavras.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(topics, key = { it.id }) { topic ->
                        HelpTopicCard(
                            topic = topic,
                            onAction = {
                                onTopicAction(topic)
                                onAction(topic.action)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HelpTopicCard(topic: HelpTopic, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(topic.question, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                topic.shortAnswer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (topic.steps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                topic.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onAction) {
                Text(helpActionLabel(topic.action))
            }
        }
    }
}

/** Ícone de ajuda para top bars (alvo 48dp). Some com o teclado aberto. */
@Composable
fun HelpTopBarButton(onOpenHelp: () -> Unit) {
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (imeOpen) return
    IconButton(onClick = onOpenHelp, modifier = Modifier.height(48.dp)) {
        Icon(Icons.Default.HelpOutline, "Ajuda do BT")
    }
}

/**
 * Card inline de gatilho. Preferido a popup; uma ação principal + dismiss
 * permanente (“Não mostrar de novo”).
 */
@Composable
fun HelpTriggerCard(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSnooze: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSnooze != null) {
                    TextButton(onClick = onSnooze) { Text("Agora não") }
                }
                TextButton(onClick = onDismiss) { Text("Não mostrar de novo") }
            }
        }
    }
}
