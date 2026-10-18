package com.bettertalker.app.ui.aimodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Alignment
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
 * modelo em menu suspenso (flash|v4-pro), status real (sonda de 1 token) e
 * instruções. Layout em card único, sem estouro horizontal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeepSeekCard(vm: ModelViewModel) {
    val key by vm.deepseekApiKey.collectAsState()
    val model by vm.deepseekModel.collectAsState()
    val probe by vm.deepseekProbe.collectAsState()
    val status = deepSeekUiStatus(key, probe)
    var draft by remember(key) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("DeepSeek — chat de raciocínio (BYOD)", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Para desenvolver tópico por tópico. Crie sua chave em platform.deepseek.com — " +
                    "ela fica só neste aparelho (DataStore local).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            // Status: Check = verificado/online; Warning = não verificado/erro.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (status) {
                    DeepSeekUiStatus.ONLINE -> Icon(
                        Icons.Default.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    DeepSeekUiStatus.CHECKING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    DeepSeekUiStatus.NO_KEY -> Unit
                    else -> Icon(
                        Icons.Default.WarningAmber, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    deepSeekStatusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        // A11y: amarelo primário falha AA no claro; onSurfaceVariant
                        // passa sobre o card (7.63/7.20+).
                        DeepSeekUiStatus.ONLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                        DeepSeekUiStatus.AUTH_ERROR, DeepSeekUiStatus.ERROR ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    },
                )
            }

            if (key.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.checkDeepSeek() },
                        enabled = probe != "checking",
                    ) { Text("Testar conexão") }
                    OutlinedButton(
                        onClick = { vm.clearDeepseekApiKey() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Remover chave") }
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

            // Modelo: menu suspenso (não estoura o layout como os chips).
            ExposedDropdownMenuBox(
                expanded = modelMenuOpen,
                onExpandedChange = { modelMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = if (model == "deepseek-v4-pro") "deepseek-v4-pro" else "deepseek-flash",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Modelo") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("deepseek-flash (padrão)") },
                        onClick = {
                            vm.selectDeepseekModel("deepseek-flash")
                            modelMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("deepseek-v4-pro") },
                        onClick = {
                            vm.selectDeepseekModel("deepseek-v4-pro")
                            modelMenuOpen = false
                        },
                    )
                }
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
