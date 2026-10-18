package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.bettertalker.app.ui.components.ViewModeMenu

/**
 * Hub "Apresentar": escolha visual da nota antes da Tribuna (mapa de memória).
 * Substitui o atalho silencioso de última nota / sheet de escolha.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentHomeScreen(
    vm: HubViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val cards by vm.cards.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    Scaffold(
        // O Scaffold global (MainActivity) + a barra cuidam dos insets do
        // sistema — sem inset extra aqui (some o "buraco" no fim da lista).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
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
                onOpen = onOpenNote,
            )
        }
    }
}
