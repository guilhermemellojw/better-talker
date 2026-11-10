package com.bettertalker.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.cloud.SyncStatus
import com.bettertalker.app.data.cloud.syncIndicatorText
import com.bettertalker.app.data.images.ImageBackupStats
import com.bettertalker.app.data.images.ImageRestoreReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    vm: AccountViewModel,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenNote: (String) -> Unit = {},
) {
    val user by vm.user.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val syncBetaEnabled by vm.syncBetaEnabled.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val imageBackupEnabled by vm.imageBackupEnabled.collectAsState()
    val imageStats by vm.imageStats.collectAsState()
    val restoreBusy by vm.restoreBusy.collectAsState()
    val restoreReport by vm.restoreReport.collectAsState()
    val restoreError by vm.restoreError.collectAsState()
    val pendingCloudDeletes by vm.pendingCloudDeletes.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val message by vm.message.collectAsState()
    val confirmWipe by vm.confirmWipe.collectAsState()
    val confirmLocalWipe by vm.confirmLocalWipe.collectAsState()
    val accountConflict by vm.accountConflict.collectAsState()
    val showFirstSync by vm.showFirstSync.collectAsState()
    val conflicts by vm.conflicts.collectAsState()
    val snack = remember { SnackbarHostState() }
    val uiContext = LocalContext.current
    var showAllConflicts by remember { mutableStateOf(false) }

    LaunchedEffect(error) { if (error.isNotEmpty()) { snack.showSnackbar(error); vm.consumeError() } }
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            snack.showSnackbar(message)
            vm.consumeMessage()
        }
    }

    if (vm.syncBeta && showFirstSync) {
        AlertDialog(
            onDismissRequest = { /* escolha obrigatória */ },
            title = { Text("Primeiro sync") },
            text = {
                Text(
                    "Este aparelho já tem notas. Como você quer sincronizar " +
                        "com a conta?\n\n" +
                        "• Mesclar: une os dois lados e cria uma cópia (conflito) " +
                        "quando a mesma nota mudou nos dois.\n" +
                        "• Enviar: o que está neste aparelho vence.\n" +
                        "• Baixar: traz a conta; nada daqui é enviado."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.firstSync("merge") }) {
                    Text("Mesclar (recomendado)")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.firstSync("upload") }) { Text("Enviar") }
                    TextButton(onClick = { vm.firstSync("download") }) { Text("Baixar") }
                }
            }
        )
    }

    if (accountConflict != null) {
        AlertDialog(
            onDismissRequest = vm::cancelAccountConflict,
            title = { Text("Outra conta neste aparelho") },
            text = {
                Text(
                    if (vm.syncBeta) {
                        "Este aparelho já está vinculado a outra conta. Para entrar " +
                            "com a conta nova, é preciso apagar os dados locais " +
                            "(notas, pastas, anexos, conversas e imagens). O que já foi " +
                            "sincronizado continua na nuvem da conta anterior."
                    } else {
                        "Este aparelho já está vinculado a outra conta. Para entrar " +
                            "com a conta nova, é preciso apagar os dados locais " +
                            "(notas, pastas, anexos, conversas e imagens). O backup " +
                            "de imagens da conta anterior continua no Storage."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.wipeLocalAndRetry(uiContext) }, enabled = !busy) {
                    Text("Apagar dados locais e entrar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onOpenBackup) { Text("Exportar backup") }
                    TextButton(onClick = vm::cancelAccountConflict) { Text("Cancelar") }
                }
            }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = vm::cancelWipe,
            title = { Text(if (vm.syncBeta) "Apagar nuvem?" else "Apagar backup de imagens?") },
            text = {
                Text(
                    if (vm.syncBeta) {
                        "Apaga notas, pastas, metadados, seções, esboços e imagens " +
                            "no servidor. O aparelho mantém tudo — e o próximo sync " +
                            "reenvia o que ainda estiver no local. Para zerar de " +
                            "verdade, apague também os dados locais."
                    } else {
                        "Apaga as imagens da conta no Firebase Storage. Notas, " +
                            "conversas e imagens locais não são tocadas."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = vm::wipeCloud) {
                    Text("Apagar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelWipe) { Text("Cancelar") } }
        )
    }

    if (confirmLocalWipe) {
        AlertDialog(
            onDismissRequest = vm::cancelLocalWipe,
            title = { Text("Apagar dados deste aparelho?") },
            text = {
                Text(
                    if (vm.syncBeta) {
                        "Notas, pastas, anexos, conversas e imagens locais serão " +
                            "apagados. A conta continua conectada, então o próximo " +
                            "sync pode baixar os dados da nuvem de volta. Exporte " +
                            "um backup antes."
                    } else {
                        "Notas, pastas, anexos, conversas e imagens locais serão " +
                            "apagados. O backup de imagens no Storage não é apagado; " +
                            "depois você pode usar “Baixar imagens”. Exporte um " +
                            "backup local antes."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = vm::wipeLocal, enabled = !busy) {
                    Text("Apagar dados locais", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onOpenBackup) { Text("Exportar backup") }
                    TextButton(onClick = vm::cancelLocalWipe) { Text("Cancelar") }
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                title = { Text("Conta e nuvem") }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!vm.syncBeta) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Neste aparelho", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Notas, chat e anexos ficam locais. O backup de imagens " +
                                "é opcional e usa login Google; o Firestore de notas " +
                                "continua no build beta separado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Sincronização em nuvem",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        "Experimental",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Switch(
                                checked = syncBetaEnabled,
                                onCheckedChange = vm::setSyncBetaEnabled,
                                enabled = !busy && vm.configured,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                !vm.configured ->
                                    "Este build beta não tem google-services.json. " +
                                        "A sincronização fica inativa."
                                syncBetaEnabled ->
                                    "Ligada. Entre com sua conta Google; notas e " +
                                        "metadados sobem, arquivos e trechos nunca."
                                else ->
                                    "Desligada por padrão. Suas notas ficam só neste " +
                                        "aparelho até você ativar explicitamente."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (syncBetaEnabled && vm.configured) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            if (user == null) {
                                Text("Não conectado", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Entre com sua conta Google para sincronizar notas entre aparelhos.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { vm.signIn(uiContext) }, enabled = !busy) {
                                    Text(if (busy) "Entrando…" else "Entrar com Google")
                                }
                                Spacer(Modifier.height(12.dp))
                                Sha1Row()
                            } else {
                                Text(user?.email ?: user?.uid ?: "Conectado", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                SyncIndicator(state = syncState, lastSync = lastSync, vm = vm)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = vm::syncNow, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Sincronizar agora")
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) {
                                    Text("Sair")
                                }
                            }
                        }
                    }
                }
            }
            if (!vm.syncBeta) {
                ImageBackupCard(
                    configured = vm.configured,
                    signedIn = user != null,
                    email = user?.email,
                    enabled = imageBackupEnabled,
                    stats = imageStats,
                    busy = busy,
                    restoreBusy = restoreBusy,
                    restoreReport = restoreReport,
                    restoreError = restoreError,
                    pendingCloudDeletes = pendingCloudDeletes,
                    onSignIn = { vm.signInImages(uiContext) },
                    onSignOut = vm::signOutImages,
                    onToggle = vm::setImageBackupEnabled,
                    onBackup = vm::backupPendingImages,
                    onRestore = vm::restoreImages,
                    onRetryDeletes = vm::retryCloudDeletes,
                    onWipeCloud = vm::askWipe,
                )
            }

            if (vm.syncBeta && syncBetaEnabled && conflicts.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Conflitos de sincronização (${conflicts.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Edições simultâneas foram preservadas em cópias. " +
                                "Revise e exclua a versão que não precisar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.height(4.dp))
                        val visibleConflicts = if (showAllConflicts) conflicts else conflicts.take(5)
                        visibleConflicts.forEach { note ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    note.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                TextButton(onClick = { onOpenNote(note.id) }) { Text("Abrir") }
                            }
                        }
                        if (conflicts.size > 5) {
                            TextButton(onClick = { showAllConflicts = !showAllConflicts }) {
                                Text(
                                    if (showAllConflicts) "Mostrar menos"
                                    else "Ver todos (${conflicts.size})",
                                )
                            }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Dados deste aparelho", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Anexos e trechos indexados nunca sobem — ficam no aparelho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                        Text("Exportar backup")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::askLocalWipe, modifier = Modifier.fillMaxWidth()) {
                        Text("Apagar dados locais", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (vm.syncBeta && syncBetaEnabled && user != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Zona de perigo da conta", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A nuvem é separada deste aparelho. Apagar aqui não remove seus arquivos locais.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::askWipe, modifier = Modifier.fillMaxWidth()) {
                            Text("Apagar meus dados da nuvem", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Text(
                "Versão ${com.bettertalker.app.BuildConfig.VERSION_NAME} (${com.bettertalker.app.BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 5.4 — backup opcional das imagens. O login não liga o Firestore nem o
 * sync beta; sem login, nenhuma imagem sai do aparelho.
 */
@Composable
private fun ImageBackupCard(
    configured: Boolean,
    signedIn: Boolean,
    email: String?,
    enabled: Boolean,
    stats: ImageBackupStats,
    busy: Boolean,
    restoreBusy: Boolean,
    restoreReport: ImageRestoreReport?,
    restoreError: String,
    pendingCloudDeletes: Int,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRetryDeletes: () -> Unit,
    onWipeCloud: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Backup de imagens", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Opcional. Só as imagens vão para o Firebase Storage; notas, " +
                    "chat e anexos ficam locais. O Palco continua offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (!configured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Este build não tem google-services.json; o backup de " +
                        "imagens fica indisponível.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            if (!signedIn) {
                Text("Não conectado", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Entre com Google para ativar o envio automático das " +
                        "imagens anexadas às notas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSignIn, enabled = !busy) {
                    Text(if (busy) "Entrando…" else "Entrar com Google")
                }
                Spacer(Modifier.height(12.dp))
                Sha1Row()
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            email ?: "Conectado",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (enabled) "Envio automático ligado"
                            else "Envio automático pausado",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        enabled = !busy,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${stats.uploaded} de ${stats.total} imagens · " +
                        android.text.format.Formatter.formatFileSize(
                            ctx, stats.uploadedBytes,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (stats.failed > 0) {
                    Text(
                        "${stats.failed} imagem(ns) com falha no envio." +
                            (stats.lastError?.let { " $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                restoreReport?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        report.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    report.errors.firstOrNull()?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (restoreError.isNotBlank()) {
                    Text(
                        restoreError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (pendingCloudDeletes > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$pendingCloudDeletes imagem(ns) aguardando remoção da nuvem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    OutlinedButton(
                        onClick = onRetryDeletes,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Tentar remover da nuvem")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !busy && !restoreBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (restoreBusy) "Baixando imagens…" else "Baixar imagens")
                }
                if (restoreBusy) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Listando e baixando do Storage…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (stats.pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBackup,
                        enabled = !busy && enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enviar ${stats.pending} pendente(s)")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sair")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onWipeCloud,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Apagar backup de imagens",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * F5g: indicador do estado do sync (4 estados). Idle com sync anterior usa o
 * texto persistido (`lastSync`) para não voltar a "Nunca sincronizado".
 */
@Composable
private fun SyncIndicator(
    state: SyncStatus.State,
    lastSync: Long,
    vm: AccountViewModel,
) {
    val now = System.currentTimeMillis()
    when (state) {
        is SyncStatus.State.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                syncIndicatorText(state, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        is SyncStatus.State.Error -> Column {
            Text(
                syncIndicatorText(state, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) {
                Text("Tentar de novo")
            }
        }
        is SyncStatus.State.Success -> Text(
            syncIndicatorText(state, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        is SyncStatus.State.Idle -> Text(
            if (lastSync > 0L) vm.lastSyncText(lastSync) else syncIndicatorText(state, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun Sha1Row() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sha1 = remember { com.bettertalker.app.data.util.buildSha1(ctx) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Column {
        Text(
            "Se o login falhar com “no credentials”, registre este SHA-1 no Firebase Console (Configurações do projeto → seu app Android → Impressões digitais):",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sha1,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString(sha1))
            }) { Text("Copiar") }
        }
    }
}
