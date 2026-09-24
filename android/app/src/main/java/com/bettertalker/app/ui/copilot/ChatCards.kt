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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    headings: List<String>,
    number: Int? = null,
    onInsert: (body: String, dest: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var body by remember(card) { mutableStateOf(card.body) }
    var dest by remember(card) { mutableStateOf<String?>(card.sectionTitle.ifEmpty { null }) }
    var showDest by remember { mutableStateOf(false) }
    val destLabel = dest ?: "Fim da nota"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (number != null) "$number. " else "") + card.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (card.source.isNotEmpty()) {
                        Text(
                            "Fonte: ${card.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "📍 ${if (dest == null) "Fim da nota" else "Sob “$destLabel”"}" +
                            (if (card.placementReason.isNotEmpty() && dest == card.sectionTitle.ifEmpty { null }) " — ${card.placementReason}" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Detalhe")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                if (!editing) {
                    Text(body, style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
                if (card.snippet.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "“${card.snippet}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!card.insertable) {
                Text(
                    "Guia de estrutura — não vai para o discurso.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.insertable) {
                    OutlinedButton(onClick = { showDest = true }) { Text("Destino") }
                    DropdownMenu(expanded = showDest, onDismissRequest = { showDest = false }) {
                        DropdownMenuItem(
                            text = { Text("Fim da nota") },
                            onClick = { dest = null; showDest = false }
                        )
                        headings.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(h.take(40)) },
                                onClick = { dest = h; showDest = false }
                            )
                        }
                    }
                    IconButton(onClick = { editing = !editing }) {
                        Icon(Icons.Default.Edit, if (editing) "Concluir edição" else "Editar")
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Delete, "Descartar") }
                if (card.insertable) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onInsert(body, dest) }) { Text("Inserir") }
                }
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
    Card(
        Modifier.fillMaxWidth()
            .padding(start = (level * 12).dp)
    ) {
        Column(Modifier.padding(10.dp)) {
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
