package com.bettertalker.app.ui.aimodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.ai.LlmConfig
import com.bettertalker.app.data.ai.LlmModelConfig
import com.bettertalker.app.data.ai.ModelDlState

private fun gb(v: Long): String = "%.1f GB".format(v / 1024.0 / 1024 / 1024)

/** Tela do modelo IA local (download único + portões). Sem modelo = motor determinístico. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: ModelViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val ram by vm.totalRam.collectAsState()
    val ramOk = vm.ramOk()
    val configured = LlmModelConfig.configured()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = { Text("Modelo IA (local)") }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Qwen 2.5 1.5B (q8) — composição supervisionada",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "RAM do aparelho: ${if (ram > 0) gb(ram) else "…"} " +
                            (if (ramOk) "✓ (mínimo 8 GB)" else "— abaixo do mínimo de 8 GB"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ramOk) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Tamanho: ~1,6 GB (teto 2 GB, só Wi-Fi)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (!ramOk && ram > 0) {
                        Text(
                            "Neste aparelho o chat segue no motor determinístico.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            when (val s = state) {
                is ModelDlState.Downloading -> {
                    val p = vm.progressOf(s)
                    if (p >= 0) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Baixando… ${s.doneBytes / 1024 / 1024} / " +
                        (if (s.totalBytes > 0) "${s.totalBytes / 1024 / 1024} MB" else "?"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.cancel() }) { Text("Cancelar") }
                    }
                }
                is ModelDlState.Verifying -> {
                    Text("Verificando integridade (SHA-256)…")
                }
                is ModelDlState.Ready -> {
                    Text("Modelo pronto ✓", color = MaterialTheme.colorScheme.primary)
                    Text(s.path, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.delete() }) { Text("Excluir") }
                    }
                }
                is ModelDlState.Failed -> {
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.start() }, enabled = configured && ramOk) {
                        Text("Tentar de novo")
                    }
                }
                else -> {
                    Text(
                        if (!configured) "URL do modelo ainda não configurada (hospedagem pendente)."
                        else if (!ramOk && ram > 0) "Aparelho abaixo do mínimo — motor determinístico ativo."
                        else "Modelo ainda não baixado. O chat funciona sem ele.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { vm.start() }, enabled = configured && ramOk) {
                        Text("Baixar modelo (~1,6 GB, só Wi-Fi)")
                    }
                }
            }
            Text(
                "Sem o modelo, tudo continua 100% offline no motor determinístico. " +
                    "Com o modelo, ele compõe sob RAG estrito + trava anti-alucinação.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
