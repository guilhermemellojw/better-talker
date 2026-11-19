package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * 1C.2.1B2C — pergunta simples de substituição de decisão.
 *
 * Não altera o texto do discurso. Enquanto a operação está em andamento, o
 * diálogo não pode ser fechado nem confirmado de novo.
 */
@Composable
fun DecisionSupersessionDialog(
    prompt: DecisionSupersessionPrompt,
    onKeepBoth: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismissOutcome: () -> Unit,
) {
    val dismissRequest: () -> Unit = when {
        prompt.isSubmitting -> ({})
        prompt.outcome == null -> onCancel
        prompt.canRefresh() || prompt.canRetry() -> onCancel
        else -> onDismissOutcome
    }

    AlertDialog(
        onDismissRequest = dismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = !prompt.isSubmitting,
            dismissOnClickOutside = !prompt.isSubmitting,
        ),
        title = {
            Text(
                if (prompt.outcome == null) {
                    "Quer substituir uma decisão anterior?"
                } else {
                    "Sobre a decisão anterior"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (prompt.isSubmitting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Salvando sua escolha…")
                    }
                } else {
                    if (prompt.outcome == null) {
                        Text(
                            "Você já tinha escolhido uma forma de preparar esta parte " +
                                "do discurso.",
                        )
                    }
                    Text(
                        "Antes: “${prompt.previous.text}”",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Agora: “${prompt.replacement.text}”",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    prompt.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                    if (prompt.outcome == null) {
                        Text(
                            "Você pode manter as duas ou usar a nova no lugar da anterior. " +
                                "Isso muda só o que o Mentor lembra; o texto do discurso " +
                                "não será alterado.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                prompt.isSubmitting -> Unit

                prompt.outcome == null -> Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = onKeepBoth, modifier = Modifier.fillMaxWidth()) {
                        Text("Manter as duas")
                    }
                    TextButton(onClick = onReplace, modifier = Modifier.fillMaxWidth()) {
                        Text("Usar a nova no lugar da anterior")
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Agora não")
                    }
                }

                prompt.canRefresh() -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onRefresh) { Text("Atualizar") }
                    TextButton(onClick = onCancel) { Text("Agora não") }
                }

                prompt.canRetry() -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onRetry) { Text("Tentar novamente") }
                    TextButton(onClick = onCancel) { Text("Agora não") }
                }

                else -> TextButton(
                    onClick = onDismissOutcome,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text("OK")
                }
            }
        },
    )
}
