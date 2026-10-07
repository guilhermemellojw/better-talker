package com.bettertalker.app.ui.copilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.ui.jw.JwDownloadDialog
import kotlinx.coroutines.launch

// ---------- fluxo de download (era do sheet; agora no chat) ----------

/**
 * Baixar no site (página oficial) vs direto (API), com permissão de notificação.
 * Use [host] dentro da composição para o diálogo + erros.
 */
class DownloadCtl internal constructor(
    private val vm: CopilotViewModel,
    private val snack: SnackbarHostState
) {
    var pageUrl by mutableStateOf<String?>(null)
        private set
    private var pendingDirect by mutableStateOf<RefDetector.RefStatus?>(null)
    private var notifProbe by mutableStateOf<String?>(null)
    private var pendingDone: (() -> Unit)? = null
    internal var requestNotif: () -> Unit = {}

    private fun hasNotif(ctx: android.content.Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun openPage(ctx: android.content.Context, url: String, onDone: (() -> Unit)? = null) {
        if (onDone != null) pendingDone = onDone
        if (!hasNotif(ctx)) {
            notifProbe = url
            requestNotif()
        } else {
            pageUrl = url
        }
    }

    fun downloadDirect(
        ctx: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope,
        st: RefDetector.RefStatus,
        onDone: (() -> Unit)? = null
    ) {
        if (st.apiPub == null && st.probePub == null) {
            openPage(ctx, st.downloadUrl, onDone)
            return
        }
        if (!hasNotif(ctx)) {
            if (onDone != null) pendingDone = onDone
            pendingDirect = st
            requestNotif()
        } else {
            scope.launch {
                if (!vm.downloadEdition(st)) {
                    if (onDone != null) pendingDone = onDone
                    pageUrl = st.downloadUrl
                } else {
                    onDone?.invoke()
                }
            }
        }
    }

    @Composable
    fun host(scope: kotlinx.coroutines.CoroutineScope) {
        val dlError by vm.dlError.collectAsState()
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            pendingDirect?.let { st ->
                pendingDirect = null
                val done = pendingDone
                scope.launch {
                    if (!vm.downloadEdition(st)) {
                        pageUrl = st.downloadUrl
                    } else {
                        pendingDone = null
                        done?.invoke()
                    }
                }
                return@rememberLauncherForActivityResult
            }
            pageUrl = notifProbe
            notifProbe = null
        }
        requestNotif = {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        LaunchedEffect(dlError) {
            if (dlError.isNotEmpty()) {
                snack.showSnackbar(dlError)
                vm.consumeDlError()
            }
        }
        pageUrl?.let { url ->
            JwDownloadDialog(
                url = url,
                onDismiss = {
                    pageUrl = null
                    vm.refreshBases()
                    vm.refreshOutlineRefs()
                    // Onboarding F2a: download pode ter completado — revalida o banner.
                    scope.launch { vm.refreshReadiness() }
                    pendingDone?.let { done ->
                        pendingDone = null
                        done()
                    }
                }
            )
        }
    }
}

@Composable
fun rememberDownloadCtl(vm: CopilotViewModel, snack: SnackbarHostState): DownloadCtl {
    return remember(vm, snack) { DownloadCtl(vm, snack) }
}

// ---------- linhas reaproveitadas do sheet ----------

/**
 * Fase 8 (§14): proveniência básica com distinção visual.
 * Conteúdo = 📖 fonte factual; guia BE/TH = 🎤 técnica + categoria.
 */
@Composable
fun ProvenanceLine(source: String, trainingCategory: String?) {
    if (trainingCategory != null) {
        Text(
            "🎤 Técnica de apresentação ($trainingCategory): $source",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    } else {
        Text(
            "📖 Fonte: $source",
            style = MaterialTheme.typography.labelSmall,
            // A11y: o amarelo primário dá 1.55:1 no tema claro; secondary
            // passa AA sobre o fundo do chat (5.38 claro / 6.97 escuro).
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun SectionRow(
    index: Int,
    section: OutlineSection,
    busy: Boolean,
    onGenerate: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth()
            .padding(start = (section.level * 12).dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${index + 1}. ${section.title}", style = MaterialTheme.typography.titleSmall)
                if (section.minutes != null) {
                    Text(
                        "${section.minutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Button(onClick = onGenerate, enabled = !busy) {
                Text(if (busy) "…" else "Gerar")
            }
        }
    }
}

@Composable
fun IdeaCardRow(
    card: IdeaCard,
    destinations: List<InsertDestination>,
    number: Int? = null,
    onInsert: (body: String, dest: InsertDestination?) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var body by remember(card) { mutableStateOf(card.body) }
    // T5: destino default = tópico do card (quando existe na lista); senão o
    // heading legado do próprio card; senão "Fim da nota".
    var dest by remember(card, destinations) {
        mutableStateOf(
            destinations.firstOrNull {
                card.sectionTitle.isNotBlank() && it.label.startsWith(card.sectionTitle)
            } ?: card.sectionTitle.ifEmpty { null }?.let { InsertDestination(it, heading = it) }
        )
    }
    var showDest by remember { mutableStateOf(false) }
    val destLabel = dest?.label ?: "Fim da nota"

    Column(Modifier.fillMaxWidth()) {
        Text(
            (if (number != null) "$number. " else "") + card.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
                    if (card.source.isNotEmpty()) {
                        ProvenanceLine(source = card.source, trainingCategory = card.trainingCategory)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!editing) {
                        ChatMessageText(body)
                    } else {
                        OutlinedTextField(
                            value = body, onValueChange = { body = it },
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        )
                    }
                    if (card.snippet.isNotBlank() && !body.contains(card.snippet.take(30))) {
                        Spacer(Modifier.height(4.dp))
                        ChatMessageText("> " + card.snippet)
                    }
            Spacer(Modifier.height(4.dp))
            if (!card.insertable) {
                Text(
                    "Guia de estrutura — não vai para o discurso.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.insertable) {
                    TextButton(onClick = { onInsert(body, dest) }) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Inserir")
                    }
                    IconButton(onClick = { editing = !editing }) {
                        Icon(Icons.Default.Edit, if (editing) "Concluir edição" else "Editar")
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Delete, "Descartar") }
                Spacer(Modifier.weight(1f))
                if (card.insertable) {
                    TextButton(onClick = { showDest = true }) {
                        Text("📍 " + destLabel.take(28), style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(expanded = showDest, onDismissRequest = { showDest = false }) {
                        DropdownMenuItem(
                            text = { Text("Fim da nota") },
                            onClick = { dest = null; showDest = false }
                        )
                        destinations.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.label.take(48)) },
                                onClick = { dest = d; showDest = false }
                            )
                        }
                    }
                } else if (card.placementReason.isNotEmpty()) {
                    Text(
                        card.placementReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
}

@Composable
fun DraftRow(
    index: Int,
    title: String,
    minutes: Int?,
    included: Boolean,
    body: String,
    level: Int,
    onTitle: (String) -> Unit,
    onMinutes: (Int?) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(start = (level * 12).dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = included, onCheckedChange = { onToggle() })
                Text("${index + 1}.", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = title, onValueChange = onTitle,
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remover") }
            }
            if (body.isNotBlank()) {
                Text(
                    body.take(160) + if (body.length > 160) "…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = minutes?.toString() ?: "",
                onValueChange = { onMinutes(it.toIntOrNull()) },
                label = { Text("Min (opcional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp)
            )
        }
}

@Composable
fun ToolBtn(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon, desc,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
