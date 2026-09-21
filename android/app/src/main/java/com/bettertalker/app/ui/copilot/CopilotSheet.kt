package com.bettertalker.app.ui.copilot

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.ui.components.ByodNotice
import com.bettertalker.app.ui.jw.JwDownloadDialog
import kotlinx.coroutines.launch

private val OUTLINE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/pdf",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotSheet(
    vm: CopilotViewModel,
    onDismiss: () -> Unit,
    onInsert: (text: String, heading: String?) -> Unit,
    noteText: String = "",
    headings: List<String> = emptyList()
) {
    val q by vm.query.collectAsState()
    val busy by vm.busy.collectAsState()
    val summary by vm.summary.collectAsState()
    val ideas by vm.ideas.collectAsState()
    val sectionBusy by vm.sectionBusy.collectAsState()
    val insert by vm.insertReq.collectAsState()
    val missing by vm.missing.collectAsState()
    val refs by vm.refs.collectAsState()
    val refsBusy by vm.refsBusy.collectAsState()
    val outlineInfo by vm.outlineInfo.collectAsState()
    val outlineSections by vm.outlineSections.collectAsState()
    val outlineRefs by vm.outlineRefs.collectAsState()
    val dlError by vm.dlError.collectAsState()
    val draft by vm.draft.collectAsState()
    val draftName by vm.draftName.collectAsState()
    val draftTitle by vm.draftTitle.collectAsState()
    val draftTotal by vm.draftTotal.collectAsState()
    val draftPreamble by vm.draftPreamble.collectAsState()
    val draftError by vm.draftError.collectAsState()
    val draftBusy by vm.draftBusy.collectAsState()
    val dropped by vm.dropped.collectAsState()
    val merges by vm.merges.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    var dlUrl by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    // Android 13+: permissão de notificação para o DownloadManager concluir com aviso
    val notifPermission = remember { mutableStateOf<String?>(null) }
    var pendingDirect by remember {
        mutableStateOf<com.bettertalker.app.data.util.RefDetector.RefStatus?>(null)
    }
    fun hasNotif(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDirect?.let { st ->
            pendingDirect = null
            scope.launch { if (!vm.downloadEdition(st)) dlUrl = st.downloadUrl }
            return@rememberLauncherForActivityResult
        }
        dlUrl = notifPermission.value
    }
    val openDownload: (String) -> Unit = { url ->
        if (!hasNotif()) {
            notifPermission.value = url
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            dlUrl = url
        }
    }
    fun downloadRef(st: com.bettertalker.app.data.util.RefDetector.RefStatus) {
        if (st.apiPub == null && st.probePub == null) {
            openDownload(st.downloadUrl)
            return
        }
        if (!hasNotif()) {
            pendingDirect = st
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { if (!vm.downloadEdition(st)) dlUrl = st.downloadUrl }
        }
    }
    val outlinePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importOutlineFile(uri) }

    LaunchedEffect(insert) {
        val req = insert
        if (req != null) {
            onInsert(req.text, req.heading)
            vm.consumeInsert()
            scope.launch { snack.showSnackbar("Inserido na nota") }
        }
    }
    LaunchedEffect(dlError) {
        if (dlError.isNotEmpty()) {
            snack.showSnackbar(dlError)
            vm.consumeDlError()
        }
    }

    dlUrl?.let { url ->
        JwDownloadDialog(
            url = url,
            onDismiss = {
                dlUrl = null
                vm.refreshBases()
                vm.refreshOutlineRefs()
                if (refs != null) vm.checkRefs(noteText)
            }
        )
    }

    if (showPaste) {
        var t by remember { mutableStateOf(pasteText) }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Colar esboço") },
            text = {
                OutlinedTextField(
                    value = t, onValueChange = { t = it },
                    placeholder = { Text("Cole o texto do esboço (ex: ponto da apostila)…") },
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasteText = t
                    showPaste = false
                    vm.pasteOutline(t)
                }) { Text("Analisar") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancelar") } }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text("Copilot local — guia: esboço", style = MaterialTheme.typography.titleMedium) }
                item { ByodNotice() }
                // Estado: publicações-base ausentes
                if (missing.isNotEmpty()) {
                    item {
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
                                        Button(onClick = { openDownload(pub.landingUrl) }) { Text("Baixar") }
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
                    }
                }

                // ============ ESBOÇO ============
                if (outlineInfo == null && draft.isEmpty() && !draftBusy) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Estruture pelo esboço", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Importe o esboço em DOCX, PDF ou JWPUB, ou cole o texto (ex: ponto da apostila) — as ideias seguem as seções, na ordem.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { outlinePicker.launch(OUTLINE_MIMES) }) { Text("Importar") }
                                    OutlinedButton(onClick = { showPaste = true }) { Text("Colar texto") }
                                }
                            }
                        }
                    }
                }

                if (draftBusy) {
                    item { Text("Analisando esboço…", style = MaterialTheme.typography.bodySmall) }
                }
                if (draftError.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(draftError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.clearDraft() }) { Text("OK") }
                            }
                        }
                    }
                }
                if (draft.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Prévia do esboço — ajuste e vincule", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = draftTitle, onValueChange = vm::setDraftTitle,
                                    label = { Text("Título") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = draftTotal?.toString() ?: "",
                                    onValueChange = { vm.setDraftTotal(it.toIntOrNull()) },
                                    label = { Text("Total min (opcional)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(180.dp)
                                )
                                if (dropped > 0) {
                                    Text(
                                        "$dropped linha(s) estruturais ignoradas (cabeçalhos, metadados).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (draftPreamble.isNotBlank()) {
                                    Text(
                                        "Cabeçalho: " + draftPreamble.take(140),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                    draft.forEachIndexed { i, d ->
                        item(key = "draft-$i-${d.title.hashCode()}") {
                            DraftRow(
                                index = i,
                                title = d.title,
                                minutes = d.minutes,
                                included = d.included,
                                body = d.body,
                                onTitle = { vm.updateDraftTitle(i, it) },
                                onMinutes = { vm.updateDraftMinutes(i, it) },
                                onToggle = { vm.toggleDraftInclude(i) },
                                onRemove = { vm.removeDraft(i) }
                            )
                        }
                    }
                    merges.forEach { m ->
                        val a = draft.getOrNull(m.a)?.title ?: return@forEach
                        val b = draft.getOrNull(m.b)?.title ?: return@forEach
                        item(key = "merge-${m.a}-${m.b}") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        "Parecem o mesmo tópico — fundir?",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text("• $a", style = MaterialTheme.typography.bodySmall)
                                    Text("• $b", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { vm.acceptMerge(m.a, m.b) }) { Text("Fundir") }
                                        TextButton(onClick = { vm.dismissMerge(m.a, m.b) }) { Text("Manter separados") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.linkDraft() },
                                enabled = draft.size >= 2
                            ) { Text("Vincular como base") }
                            TextButton(onClick = { vm.clearDraft() }) { Text("Cancelar") }
                        }
                    }
                }

                outlineInfo?.let { o ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("📋 ${o.title}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${outlineSections.size} seções" +
                                        (o.totalMinutes?.let { " • $it min" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { vm.clearDraft(); outlinePicker.launch(OUTLINE_MIMES) }) {
                                        Text("Trocar")
                                    }
                                    TextButton(onClick = { vm.unlinkOutline() }) {
                                        Text("Desvincular", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    // referências citadas no esboço (edição exata)
                    outlineRefs?.let { olist ->
                        val oOk = olist.filter { it.resolved }
                        val oMissing = olist.filter { !it.resolved }
                        if (olist.isNotEmpty()) {
                            item {
                                Text(
                                    "Referências do esboço (${oOk.size}/${olist.size} baixadas)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                        oOk.forEach { st ->
                            item(key = "oref-ok-${st.ref.editionKey}-${st.ref.raw.hashCode()}") {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("✅ ${st.ref.label}", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "No arquivo: ${st.fileName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                        oMissing.forEach { st ->
                            item(key = "oref-miss-${st.ref.editionKey}-${st.ref.raw.hashCode()}") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("⬇️ ${st.ref.label}", style = MaterialTheme.typography.bodySmall)
                                        if (st.hint.isNotEmpty()) {
                                            Text(st.hint, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Button(onClick = { downloadRef(st) }) { Text(if (!st.exact && st.probePub == null) "Procurar edição" else "Baixar esta edição") }
                                    }
                                }
                            }
                        }
                    }
                    outlineSections.forEachIndexed { idx, s ->
                        val neighbors = listOfNotNull(
                            outlineSections.getOrNull(idx - 1)?.title,
                            outlineSections.getOrNull(idx + 1)?.title
                        )
                        item(key = "sec-${idx}-${s.title.hashCode()}") {
                            SectionRow(
                                index = idx,
                                section = s,
                                busy = sectionBusy == s.title,
                                onGenerate = { vm.generateForSection(s, neighbors) }
                            )
                        }
                        ideas.filter { it.sectionTitle == s.title }.forEach { card ->
                            item(key = "idea-${s.title.hashCode()}-${card.title.hashCode()}-${card.snippet.hashCode()}") {
                                IdeaCardRow(
                                    card = card,
                                    headings = headings,
                                    onInsert = { body, dest -> vm.insert(card, body, dest) },
                                    onDismiss = { vm.dismissIdea(card) }
                                )
                            }
                        }
                    }
                }

                // consulta livre (exige esboço)
                item {
                    OutlinedTextField(
                        value = q, onValueChange = vm::setQuery,
                        label = { Text("Tema ou pergunta (ex: fé, oração)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = outlineInfo != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            vm.ideas()
                        })
                    )
                }
                if (outlineInfo == null) {
                    item {
                        Text(
                            "Vincule um esboço para gerar ideias e resumos.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { keyboard?.hide(); vm.ideas() }, enabled = !busy) { Text("Gerar ideias") }
                            OutlinedButton(onClick = { keyboard?.hide(); vm.summarize() }, enabled = !busy) { Text("Resumir") }
                        }
                    }
                }
                // Verificação de referências da nota (sob demanda, edição exata)
                item {
                    OutlinedButton(
                        onClick = { vm.checkRefs(noteText) },
                        enabled = !refsBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (refsBusy) "Verificando…" else "Verificar referências da nota") }
                }
                refs?.let { list ->
                    val ok = list.filter { it.resolved }
                    val lacking = list.filter { !it.resolved }
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "Nenhuma referência a publicação encontrada na nota.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    if (ok.isNotEmpty()) {
                        item { Text("Disponíveis (${ok.size})", style = MaterialTheme.typography.titleSmall) }
                        ok.forEach { st ->
                            item(key = "ok-${st.ref.editionKey}") {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("✅ ${st.ref.label}", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "No arquivo: ${st.fileName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (lacking.isNotEmpty()) {
                        item { Text("Faltando (${lacking.size})", style = MaterialTheme.typography.titleSmall) }
                        item {
                            Text(
                                "Baixe no site oficial e anexe ao app — depois toque Verificar de novo.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        lacking.forEach { st ->
                            item(key = "miss-${st.ref.editionKey}") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("⬇️ ${st.ref.label}", style = MaterialTheme.typography.bodySmall)
                                        if (st.hint.isNotEmpty()) {
                                            Text(st.hint, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Button(onClick = { downloadRef(st) }) { Text(if (!st.exact && st.probePub == null) "Procurar edição" else "Baixar esta edição") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (summary.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(summary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // ideias genéricas (sem seção) — legado quando existirem
                ideas.filter { it.sectionTitle.isEmpty() }.forEach { card ->
                    item(key = "gen-${card.title.hashCode()}-${card.snippet.hashCode()}") {
                        IdeaCardRow(
                            card = card,
                            headings = headings,
                            onInsert = { body, dest -> vm.insert(card, body, dest) },
                            onDismiss = { vm.dismissIdea(card) }
                        )
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SectionRow(
    index: Int,
    section: OutlineSection,
    busy: Boolean,
    onGenerate: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
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
private fun DraftRow(
    index: Int,
    title: String,
    minutes: Int?,
    included: Boolean,
    body: String,
    onTitle: (String) -> Unit,
    onMinutes: (Int?) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
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
private fun IdeaCardRow(
    card: IdeaCard,
    headings: List<String>,
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
                    Text(card.title, style = MaterialTheme.typography.titleSmall)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Delete, "Descartar") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onInsert(body, dest) }) { Text("Inserir") }
            }
        }
    }
}
