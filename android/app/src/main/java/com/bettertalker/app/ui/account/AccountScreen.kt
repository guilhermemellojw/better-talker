package com.bettertalker.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val user by vm.user.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val confirmWipe by vm.confirmWipe.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(error) { if (error.isNotEmpty()) { snack.showSnackbar(error); vm.consumeError() } }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = vm::cancelWipe,
            title = { Text("Apagar nuvem?") },
            text = { Text("Apaga todas as suas notas, pastas e metadados no servidor. O aparelho mantém tudo.") },
            confirmButton = {
                TextButton(onClick = vm::wipeCloud) {
                    Text("Apagar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelWipe) { Text("Cancelar") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                title = { Text("Conta e nuvem") }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!vm.configured) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Firebase não configurado neste build — o app funciona 100% local.",
                        Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Column
            }
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
                        Button(onClick = vm::signIn, enabled = !busy) {
                            Text(if (busy) "Entrando…" else "Entrar com Google")
                        }
                        Spacer(Modifier.height(12.dp))
                        Sha1Row()
                    } else {
                        Text(user?.email ?: user?.uid ?: "Conectado", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            vm.lastSyncText(lastSync),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
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
            if (user != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Zona de perigo", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Anexos e trechos indexados nunca sobem — ficam no aparelho.",
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
        }
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
