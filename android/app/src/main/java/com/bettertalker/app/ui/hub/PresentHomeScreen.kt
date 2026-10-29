package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bettertalker.app.ui.components.ViewModeMenu
import com.bettertalker.app.ui.present.PresentReadiness
import com.bettertalker.app.ui.present.presentReadinessLabel

/**
 * Hub "Apresentar": escolhe a nota e mostra a prontidão da Tribuna. Nota sem
 * Tribuna não abre palco vazio: oferece o editor para gerar/editar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentHomeScreen(
    vm: HubViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenEditor: (String) -> Unit = {},
) {
    val cards by vm.cards.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    var blockedNoteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(
        containerColor = Color.Transparent,
        // O Scaffold global (MainActivity) + a barra cuidam dos insets do
        // sistema — sem inset extra aqui (some o "buraco" no fim da lista).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Apresentar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    // Modos de visualização (global, compartilhado com a Home).
                    ViewModeMenu(viewMode = viewMode, onSelect = vm::setViewMode)
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            HubNoteList(
                cards = cards,
                mode = viewMode,
                emptyText = "Nenhuma nota ainda. Crie uma na Home para apresentar.",
                onOpen = { id ->
                    val readiness = cards.firstOrNull { it.id == id }?.presentReadiness
                    if (readiness == PresentReadiness.MISSING) {
                        blockedNoteId = id
                    } else {
                        onOpenNote(id)
                    }
                },
                statusText = { card ->
                    card.presentReadiness?.let { presentReadinessLabel(it) }
                },
            )
        }
    }

    val blocked = blockedNoteId?.let { id -> cards.firstOrNull { it.id == id } }
    if (blocked != null) {
        AlertDialog(
            onDismissRequest = { blockedNoteId = null },
            title = { Text("Falta gerar a Tribuna") },
            text = {
                Text(
                    "Esta nota ainda não tem uma Tribuna para o palco. " +
                        "Abra o editor e toque em ⋮ → Gerar Tribuna; " +
                        "no modo demonstração, escreva o diálogo no editor."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockedNoteId = null
                    onOpenEditor(blocked.id)
                }) { Text("Abrir editor") }
            },
            dismissButton = {
                TextButton(onClick = { blockedNoteId = null }) { Text("Cancelar") }
            },
        )
    }
}
