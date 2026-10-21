package com.bettertalker.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val trashed by vm.trashed.collectAsState()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var showEmptyDialog by remember { mutableStateOf(false) }
    var emptying by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Excluir para sempre?") },
            text = { Text("A nota e seus anexos vinculados serão apagados. Não há como desfazer.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteForever(id); confirmDelete = null }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } }
        )
    }

    if (showEmptyDialog) {
        AlertDialog(
            onDismissRequest = { if (!emptying) showEmptyDialog = false },
            title = { Text("Esvaziar lixeira?") },
            text = {
                Text(
                    "${trashed.size} nota(s) serão excluídas permanentemente. " +
                        "Esta ação não pode ser desfeita."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !emptying,
                    onClick = {
                        scope.launch {
                            emptying = true
                            try {
                                val count = vm.emptyTrash()
                                showEmptyDialog = false
                                val msg = if (count == 1) {
                                    "1 nota excluída permanentemente"
                                } else {
                                    "$count notas excluídas permanentemente"
                                }
                                snack.showSnackbar(msg)
                            } catch (_: Exception) {
                                showEmptyDialog = false
                                snack.showSnackbar("Não consegui esvaziar a lixeira agora.")
                            } finally {
                                emptying = false
                            }
                        }
                    }
                ) {
                    Text(
                        if (emptying) "Esvaziando..." else "Esvaziar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !emptying,
                    onClick = { showEmptyDialog = false },
                ) { Text("Cancelar") }
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Voltar")
                    }
                },
                title = { Text("Lixeira") },
                actions = {
                    if (trashed.isNotEmpty()) {
                        IconButton(
                            onClick = { showEmptyDialog = true },
                            enabled = !emptying,
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Esvaziar lixeira",
                            )
                        }
                    }
                },
            )
        }
    ) { pad ->
        if (trashed.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Nenhuma nota na lixeira.", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad)
            ) {
                items(trashed, key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                n.title.ifBlank { "Sem título" },
                                style = MaterialTheme.typography.titleMedium, maxLines = 2
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(n.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row {
                                TextButton(onClick = { vm.restore(n.id) }) {
                                    Icon(Icons.Default.Restore, null)
                                    Text(" Restaurar")
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { confirmDelete = n.id }) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        "Excluir para sempre",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
