package com.bettertalker.app.ui.aimodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Tela de IA — APENAS DeepSeek BYOD (cleanup multi-LLM). O app funciona
 * 100% sem chave (motor determinístico); com a chave, o Copilot usa o
 * chat de raciocínio remoto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: ModelViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = { Text("IA (DeepSeek)") }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Sem chave, tudo continua 100% offline no motor determinístico. " +
                    "Com a chave, o Copilot conversa pelo DeepSeek (BYOD).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            DeepSeekCard(vm)
        }
    }
}

/**
 * T4 — card DeepSeek (BYOD): chave mascarada com mostrar/ocultar, seletor de
 * modelo (flash|v4-pro), status real (sonda de 1 token) e instruções.
 */
@Composable
private fun DeepSeekCard(vm: ModelViewModel) {
    val key by vm.deepseekApiKey.collectAsState()
    val model by vm.deepseekModel.collectAsState()
    val probe by vm.deepseekProbe.collectAsState()
    val status = deepSeekUiStatus(key, probe)
    var draft by remember(key) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DeepSeek — chat de raciocínio (BYOD)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Para desenvolver tópico por tópico. Crie sua chave em platform.deepseek.com — " +
                    "ela fica só neste aparelho (DataStore local).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "Status: ${deepSeekStatusLabel(status)}",
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    // A11y: amarelo primário falha AA no claro; onSurfaceVariant
                    // passa sobre o card (7.63/7.20+).
                    DeepSeekUiStatus.ONLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                    DeepSeekUiStatus.AUTH_ERROR, DeepSeekUiStatus.ERROR ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.secondary
                }
            )
            if (key.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.clearDeepseekApiKey() }) { Text("Remover chave") }
                    OutlinedButton(
                        onClick = { vm.checkDeepSeek() },
                        enabled = probe != "checking"
                    ) { Text("Testar conexão") }
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Chave DeepSeek (sk-…)") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Ocultar chave" else "Mostrar chave"
                            )
                        }
                    }
                )
                Button(
                    onClick = { vm.saveDeepseekApiKey(draft); draft = "" },
                    enabled = draft.isNotBlank()
                ) { Text("Salvar chave") }
            }
            Text("Modelo:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = model == "deepseek-flash",
                    onClick = { vm.selectDeepseekModel("deepseek-flash") },
                    label = { Text("deepseek-flash (padrão)") }
                )
                FilterChip(
                    selected = model == "deepseek-v4-pro",
                    onClick = { vm.selectDeepseekModel("deepseek-v4-pro") },
                    label = { Text("deepseek-v4-pro") }
                )
            }
        }
    }
}

private fun deepSeekStatusLabel(s: DeepSeekUiStatus): String = when (s) {
    DeepSeekUiStatus.NO_KEY -> "Sem chave"
    DeepSeekUiStatus.CONFIGURED -> "Chave salva (não verificada)"
    DeepSeekUiStatus.CHECKING -> "Testando…"
    DeepSeekUiStatus.ONLINE -> "Online"
    DeepSeekUiStatus.AUTH_ERROR -> "Erro de autenticação"
    DeepSeekUiStatus.ERROR -> "Erro de conexão"
}
