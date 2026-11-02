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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Aba "Treino": esboço atual + prontidão + última preparação.
 *
 * A lista completa vive em Esboços; aqui a troca é explícita ("Trocar esboço"),
 * que abre o picker compartilhado. Sem esboço atual → empty state + picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainHomeScreen(
    vm: HubViewModel,
    onOpenStage: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
    onPickNote: () -> Unit,
    onGoToLibrary: () -> Unit,
    onBack: () -> Unit,
) {
    val card by vm.trainCard.collectAsState()
    val all by vm.cards.collectAsState()
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Treino") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val current = card
            when {
                all.isEmpty() -> HubEmptyState(
                    text = "Crie seu primeiro esboço para treinar.",
                    actionLabel = "Ir para Esboços",
                    onAction = onGoToLibrary,
                )
                current == null -> HubEmptyState(
                    text = "Escolha um esboço para treinar.",
                    actionLabel = "Escolher esboço",
                    onAction = onPickNote,
                )
                else -> HubCurrentNoteCard(
                    title = current.title,
                    badge = if (current.isDemonstration) "Demonstração" else "Discurso",
                    minutes = current.minutes,
                    sectionsCount = current.sectionsCount,
                    status = if (current.hasText) {
                        "Pronto para treinar"
                    } else {
                        "Sem texto para treinar"
                    },
                    statusOk = current.hasText,
                    detail = current.lastPrepLabel?.let { "Última preparação: $it" },
                    primaryLabel = if (current.hasText) "Começar treino" else "Abrir editor",
                    onPrimary = {
                        if (current.hasText) onOpenStage(current.id)
                        else onOpenEditor(current.id)
                    },
                    secondaryLabel = "Trocar esboço",
                    onSecondary = onPickNote,
                )
            }
        }
    }
}
