package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Progresso não cancelável da geração da Tribuna no Palco. */
@Composable
fun TribunaProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Gerando Tribuna…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Isso pode levar alguns segundos.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
    )
}

/**
 * Erro real da geração, com as ações que destravam o caso:
 * tentar de novo; abrir o editor quando falta texto/seções; configurar a IA
 * quando falta a chave BYOD; fechar.
 */
@Composable
fun TribunaErrorDialog(
    message: String,
    canOpenEditor: Boolean,
    canConfigureAi: Boolean,
    onRetry: () -> Unit,
    onOpenEditor: () -> Unit,
    onConfigureAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Não foi possível gerar a Tribuna") },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onRetry) { Text("Tentar novamente") }
        },
        dismissButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (canOpenEditor) {
                    TextButton(onClick = onOpenEditor) { Text("Abrir editor") }
                }
                if (canConfigureAi) {
                    TextButton(onClick = onConfigureAi) { Text("Configurar IA") }
                }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
    )
}
