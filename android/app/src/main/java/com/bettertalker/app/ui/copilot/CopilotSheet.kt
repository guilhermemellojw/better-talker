package com.bettertalker.app.ui.copilot

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bettertalker.app.ui.components.ByodNotice
import com.bettertalker.app.ui.jw.JwDownloadDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotSheet(vm: CopilotViewModel, onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val q by vm.query.collectAsState()
    val busy by vm.busy.collectAsState()
    val summary by vm.summary.collectAsState()
    val ideas by vm.ideas.collectAsState()
    val insert by vm.insertText.collectAsState()
    val missing by vm.missing.collectAsState()
    val ctx = LocalContext.current
    var dlUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(insert) { if (insert.isNotEmpty()) { onInsert(insert); vm.consumeInsert() } }

    dlUrl?.let { url ->
        JwDownloadDialog(url = url, onDismiss = { dlUrl = null; vm.refreshBases() })
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Copilot local — 2 bases + nota", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ByodNotice()
            Spacer(Modifier.height(8.dp))
            // Estado: publicações-base ausentes
            if (missing.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Faltam as publicações-base do Copilot:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        missing.forEach { pub ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "• ${pub.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { dlUrl = pub.landingUrl }) { Text("Baixar") }
                                TextButton(onClick = {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pub.downloadsUrl)))
                                }) { Text("Ver formatos") }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "Baixe diretamente do site oficial dentro do app — o Copilot reconhece sozinho após o download.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = q, onValueChange = vm::setQuery,
                label = { Text("Tema ou pergunta (ex: fé, oração)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::ideas, enabled = !busy) { Text("Gerar ideias") }
                OutlinedButton(onClick = vm::summarize, enabled = !busy) { Text("Resumir") }
            }
            Spacer(Modifier.height(8.dp))
            if (summary.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) { Text(summary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ideas) { card ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall)
                            if (card.source.isNotEmpty()) {
                                Text(
                                    "Fonte: ${card.source}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(card.body, style = MaterialTheme.typography.bodySmall)
                            if (card.snippet.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("“${card.snippet}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.jwUrl)))
                                }) { Text("Abrir no jw.org") }
                                Button(onClick = { vm.insert(card) }) { Text("Inserir") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
