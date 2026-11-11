package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.bettertalker.app.ui.present.PresentAction
import com.bettertalker.app.ui.present.PresentReadiness
import com.bettertalker.app.ui.present.presentActionLabel
import com.bettertalker.app.ui.present.presentCardActions
import com.bettertalker.app.ui.present.presentReadinessLabel

/**
 * Aba "Palco": esboço atual + prontidão + retomada.
 *
 * Discurso usa a Tribuna (cápsulas); demonstração usa o roteiro (turnos).
 * Sem artefato, a ação leva ao lugar certo: gerar Tribuna ou abrir o editor.
 * Sem esboço atual → empty state + picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentHomeScreen(
    vm: HubViewModel,
    onPresent: (String) -> Unit,
    onOpenTribuna: (String) -> Unit,
    onGenerateTribuna: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
    onPickNote: () -> Unit,
    onGoToLibrary: () -> Unit,
    onOpenHelp: () -> Unit = {},
) {
    val card by vm.presentCard.collectAsState()
    val all by vm.cards.collectAsState()
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Palco") },
                actions = {
                    com.bettertalker.app.ui.help.HelpTopBarButton(onOpenHelp)
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val current = card
            when {
                all.isEmpty() -> HubEmptyState(
                    text = "Crie seu primeiro esboço para apresentar.",
                    actionLabel = "Ir para Esboços",
                    onAction = onGoToLibrary,
                )
                current == null -> HubEmptyState(
                    text = "Escolha um esboço para apresentar.",
                    actionLabel = "Escolher esboço",
                    onAction = onPickNote,
                )
                else -> {
                    val demo = current.isDemonstration
                    val readiness = current.presentReadiness ?: PresentReadiness.MISSING
                    val actions = presentCardActions(readiness, demo)
                    val onAction: (PresentAction) -> (() -> Unit) = { action ->
                        when (action) {
                            PresentAction.PRESENT,
                            PresentAction.OPEN_SCRIPT -> ({ onPresent(current.id) })
                            PresentAction.OPEN_TRIBUNA -> ({ onOpenTribuna(current.id) })
                            PresentAction.GENERATE_TRIBUNA -> ({ onGenerateTribuna(current.id) })
                            PresentAction.OPEN_EDITOR -> ({ onOpenEditor(current.id) })
                        }
                    }
                    HubCurrentNoteCard(
                        title = hubCardTitle(current.title, current.minutes),
                        badge = if (demo) "Demonstração" else "Discurso",
                        minutes = current.minutes,
                        sectionsCount = current.sectionsCount,
                        status = presentReadinessLabel(readiness, demo),
                        statusOk = readiness == PresentReadiness.READY,
                        detail = current.presentResumeLabel,
                        primaryLabel = presentActionLabel(actions.primary),
                        onPrimary = onAction(actions.primary),
                        secondaryLabel = "Trocar esboço",
                        onSecondary = onPickNote,
                        tertiaryLabel = actions.tertiary?.let(::presentActionLabel),
                        onTertiary = actions.tertiary?.let(onAction),
                        onEditMap = if (demo) null else ({ onOpenTribuna(current.id) }),
                    )
                }
            }
        }
    }
}
