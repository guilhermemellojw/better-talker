package com.bettertalker.app.ui.aimodel

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.ai.LlmModelConfig
import com.bettertalker.app.data.ai.ModelDlState
import com.bettertalker.app.data.ai.downloadProgress

private fun gb(v: Long): String = "%.1f GB".format(v / 1024.0 / 1024 / 1024)

/**
 * T3 — tela de IA: modelo local **Gemma 4 E2B (LiteRT-LM)** + provedores.
 * Sem modelo, o app segue determinístico; o aceite da licença (Apache-2.0)
 * é exigido antes de baixar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: ModelViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val ram by vm.totalRam.collectAsState()
    val ramOk = vm.ramOk()
    val configured = LlmModelConfig.configured()
    val modelPresent = vm.isModelPresent()
    val uri = LocalUriHandler.current
    var licenseAccepted by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = { Text("Modelo IA (local)") }
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
            GemmaModelCard(
                state = state,
                ram = ram,
                ramOk = ramOk,
                configured = configured,
                modelPresent = modelPresent,
                licenseAccepted = licenseAccepted,
                onAcceptLicense = { licenseAccepted = it },
                onOpenLicense = { uri.openUri(LlmModelConfig.LICENSE_URL) },
                onOpenLicenses = { showLicenses = true },
                onDownload = { vm.start() },
                onCancel = { vm.cancel() },
                onDelete = { vm.delete() },
            )
            Text(
                "Sem o modelo, tudo continua 100% offline no motor determinístico. " +
                    "Com o modelo, ele compõe sob RAG estrito + trava anti-alucinação.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            CopilotProviderCard(vm, modelPresent)
            DeepSeekCard(vm)
            CopilotRemoteKeyCard(vm)
        }
    }
    if (showLicenses) {
        ThirdPartyLicensesDialog(onClose = { showLicenses = false })
    }
}

@Composable
private fun GemmaModelCard(
    state: ModelDlState,
    ram: Long,
    ramOk: Boolean,
    configured: Boolean,
    modelPresent: Boolean,
    licenseAccepted: Boolean,
    onAcceptLicense: (Boolean) -> Unit,
    onOpenLicense: () -> Unit,
    onOpenLicenses: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gemma 4 E2B — IA on-device", style = MaterialTheme.typography.titleSmall)
            Text(
                "Licença ${LlmModelConfig.LICENSE_NAME} · ${LlmModelConfig.ATTRIBUTION}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "RAM do aparelho: ${if (ram > 0) gb(ram) else "…"} " +
                    (if (ramOk) "✓ (mínimo 8 GB)" else "— abaixo do mínimo de 8 GB"),
                style = MaterialTheme.typography.bodySmall,
                color = if (ramOk) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error
            )
            Text(
                "Tamanho: ${LlmModelConfig.DISPLAY_SIZE} (download único, só Wi-Fi)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            when (state) {
                is ModelDlState.Downloading -> {
                    val p = downloadProgress(state.doneBytes, state.totalBytes)
                    if (p >= 0) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Baixando… ${state.doneBytes / 1024 / 1024} / " +
                            (if (state.totalBytes > 0) "${state.totalBytes / 1024 / 1024} MB" else "?")
                    )
                    OutlinedButton(onClick = onCancel) { Text("Cancelar") }
                }
                is ModelDlState.Verifying -> Text("Verificando integridade (SHA-256)…")
                is ModelDlState.Ready -> {
                    Text("Modelo pronto ✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.path, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = onDelete) { Text("Excluir") }
                }
                is ModelDlState.Failed -> {
                    Text(state.msg, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload, enabled = configured && ramOk && licenseAccepted) {
                        Text("Tentar de novo")
                    }
                }
                is ModelDlState.WaitingWifi -> {
                    Text("Aguardando Wi-Fi. Conecte-se e toque em Baixar.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload, enabled = configured && ramOk && licenseAccepted) {
                        Text("Baixar modelo (${LlmModelConfig.DISPLAY_SIZE})")
                    }
                }
                else -> {
                    if (modelPresent) {
                        Text("Modelo presente ✓ (instalado)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onDelete) { Text("Excluir") }
                    } else {
                        Text(
                            when {
                                !configured -> "URL do modelo ainda não configurada."
                                !ramOk && ram > 0 -> "Aparelho abaixo do mínimo — motor determinístico ativo."
                                else -> "Modelo ainda não baixado. O app funciona sem ele."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = licenseAccepted, onCheckedChange = onAcceptLicense)
                            Text(
                                "Li e aceito a licença ${LlmModelConfig.LICENSE_NAME} do modelo",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(onClick = onOpenLicense) { Text("Ver licença completa") }
                        Button(onClick = onDownload, enabled = configured && ramOk && licenseAccepted) {
                            Text("Baixar modelo (${LlmModelConfig.DISPLAY_SIZE}, só Wi-Fi)")
                        }
                    }
                }
            }
            TextButton(onClick = onOpenLicenses) { Text("Licenças de terceiros") }
        }
    }
}

/**
 * T3 — seletor de provedor. "Automático" (default): Gemma local se o modelo
 * estiver presente → remoto com chave → motor determinístico.
 * Valores explícitos mantêm o comportamento anterior.
 */
@Composable
private fun CopilotProviderCard(vm: ModelViewModel, modelPresent: Boolean) {
    val provider by vm.llmProvider.collectAsState()
    val groqSaved by vm.groqApiKey.collectAsState()
    var groqDraft by remember(groqSaved) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Provedor de IA", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider == "auto",
                    onClick = { vm.selectProvider("auto") },
                    label = { Text("Automático (recomendado)") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider == "deepseek",
                    onClick = { vm.selectProvider("deepseek") },
                    label = { Text("DeepSeek") }
                )
                FilterChip(
                    selected = provider == "gemma_local",
                    onClick = { vm.selectProvider("gemma_local") },
                    label = { Text("Gemma local") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider == "gemini",
                    onClick = { vm.selectProvider("gemini") },
                    label = { Text("Gemini") }
                )
                FilterChip(
                    selected = provider == "qwen",
                    onClick = { vm.selectProvider("qwen") },
                    label = { Text("Groq (remoto)") }
                )
            }
            when (provider) {
                "auto" -> Text(
                    "Automático: DeepSeek com chave e online; senão Gemini/Groq com chave; " +
                        "senão Gemma local; senão o motor determinístico." +
                        (if (modelPresent) " Modelo local presente ✓." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                "deepseek" -> Text(
                    "Chat de raciocínio via DeepSeek (chave BYOD). Configure no cartão DeepSeek abaixo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                "gemma_local" -> Text(
                    if (modelPresent) {
                        "Modelo local presente ✓ — Gemma 4 E2B via LiteRT-LM (GPU, contexto 4096)."
                    } else {
                        "Modelo ausente: baixe acima (ou use adb push em dev)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (modelPresent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                "qwen" -> {
                    Text(
                        "Modelo remoto via Groq, com structured output estrito.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (groqSaved.isNotBlank()) {
                        Text(
                            "Chave Groq configurada ✓ — o Copilot usa o modelo remoto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { vm.clearGroqApiKey() }) { Text("Remover chave Groq") }
                    } else {
                        Text(
                            "Cole sua chave do console Groq (gsk_…). Fica só neste aparelho.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        OutlinedTextField(
                            value = groqDraft,
                            onValueChange = { groqDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave Groq (gsk_…)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Button(
                            onClick = { vm.saveGroqApiKey(groqDraft); groqDraft = "" },
                            enabled = groqDraft.isNotBlank()
                        ) { Text("Salvar chave Groq") }
                    }
                }
            }
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
                    DeepSeekUiStatus.ONLINE -> MaterialTheme.colorScheme.primary
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

/**
 * Fase 18 — chave BYOD do assistente remoto (Gemini — alternativa/fallback).
 * Opcional, local, nunca em log.
 */
@Composable
private fun CopilotRemoteKeyCard(vm: ModelViewModel) {
    val saved by vm.llmApiKey.collectAsState()
    var draft by remember(saved) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Assistente remoto (Gemini) — alternativa", style = MaterialTheme.typography.titleSmall)
            if (saved.isNotBlank()) {
                Text("Chave configurada ✓ — o Copilot usa o assistente remoto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.clearApiKey() }) { Text("Remover chave") }
            } else {
                Text("Cole sua chave do AI Studio. Fica só neste aparelho (DataStore local).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Chave de API (AIzaSy…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { vm.saveApiKey(draft); draft = "" },
                    enabled = draft.isNotBlank()
                ) { Text("Salvar chave") }
            }
        }
    }
}

private fun assetText(ctx: Context, path: String): String =
    runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")

/** T5 — Apache-2.0 + atribuições do modelo, acessíveis pelo card. */
@Composable
private fun ThirdPartyLicensesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val notices = remember { assetText(ctx, "licenses/THIRD_PARTY_NOTICES.txt") }
    val apache = remember { assetText(ctx, "licenses/apache-2.0.txt") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Licenças de terceiros") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(notices, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(apache, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } }
    )
}
